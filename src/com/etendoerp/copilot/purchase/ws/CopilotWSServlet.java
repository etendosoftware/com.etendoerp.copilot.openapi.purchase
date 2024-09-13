package com.etendoerp.copilot.purchase.ws;


import java.io.IOException;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

import javax.servlet.ServletException;

import org.apache.commons.lang.StringUtils;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.ScrollMode;
import org.hibernate.ScrollableResults;
import org.hibernate.query.Query;
import org.openbravo.base.exception.OBException;
import org.openbravo.base.session.OBPropertiesProvider;
import org.openbravo.base.structure.BaseOBObject;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.dal.service.OBQuery;
import org.openbravo.erpCommon.businessUtility.PriceAdjustment;
import org.openbravo.erpCommon.businessUtility.Tax;
import org.openbravo.erpCommon.utility.OBMessageUtils;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.common.businesspartner.BusinessPartner;
import org.openbravo.model.common.enterprise.DocumentType;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.model.common.order.Order;
import org.openbravo.model.common.order.OrderLine;
import org.openbravo.model.common.plm.Product;
import org.openbravo.model.financialmgmt.payment.PaymentTerm;
import org.openbravo.model.financialmgmt.tax.TaxRate;
import org.openbravo.model.pricing.pricelist.PriceList;
import org.openbravo.model.pricing.pricelist.ProductPrice;
import org.openbravo.model.project.Project;
import org.openbravo.service.db.DalConnectionProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.smf.securewebservices.service.BaseWebService;
import com.smf.securewebservices.utils.WSResult;
import com.smf.securewebservices.utils.WSResult.Status;


public class CopilotWSServlet extends BaseWebService {

  public static final int MIN_SIM_PERCENT = 30;
  private static final Logger log = LoggerFactory.getLogger(CopilotWSServlet.class);
  public static final String MESSAGE_RESULT_PROPERTY = "message";

  @Override
  /**
   * This method handles GET requests to the web service.
   * It checks the path of the request and calls the appropriate method to handle the request.
   * If the path is "/searchBySimilarity", it calls the handleSimSearch method with the request parameters.
   * If the path is "/getContext", it calls the handleGetContext method.
   * If the path is not recognized, it returns a WSResult object with a status of OK and a message indicating that the path is not supported.
   * If an exception occurs during the execution of the method, it returns a WSResult object with a status of OK and a message containing the exception message.
   *
   * @param path The path of the request.
   * @param requestParams A map of request parameters where the key is the parameter name and the value is the parameter value.
   * @return A WSResult object containing the status of the operation and any data returned by the operation.
   * @throws Exception If an error occurs during the execution of the method.
   */ public WSResult get(String path, Map<String, String> requestParams) throws Exception {
    try {
      if (StringUtils.equalsIgnoreCase("/searchBySimilarity", path)) {
        return handleSimSearch(requestParams);
      } else if (StringUtils.equalsIgnoreCase("/getContext", path)) {
        return handleGetContext();
      } else {
        WSResult wsResult = new WSResult();
        wsResult.setStatus(Status.OK);
        var data = new JSONObject();
        data.put(MESSAGE_RESULT_PROPERTY, "The path is not supported");
        wsResult.setData(data);
        return wsResult;
      }
    } catch (Exception e) {
      WSResult wsResult = new WSResult();
      wsResult.setStatus(Status.OK);
      JSONObject errorJson = new JSONObject();
      errorJson.put("status", "error");
      String errmsg = String.format(e.getMessage());
      errorJson.put(MESSAGE_RESULT_PROPERTY, errmsg);
      return wsResult;
    }
  }

  private WSResult handleGetContext() throws JSONException {
    WSResult wsResult = new WSResult();
    wsResult.setStatus(Status.OK);
    JSONObject context = new JSONObject();
    OBContext obContext = OBContext.getOBContext();
    Client client = obContext.getCurrentClient();
    Organization org = obContext.getCurrentOrganization();
    Organization legalEntity = obContext.getOrganizationStructureProvider(client.getId()).getLegalEntity(org);
    if (legalEntity == null) {
      throw new OBException("Legal Entity not found");
    }
    context.put("legalEntity", legalEntity.getId());
    context.put("warehouse", obContext.getWarehouse().getId());
    context.put("currentOrganization", org.getId());
    //get Date in the format yyyy-MM-dd HH:mm:ss
    context.put("currentDate", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()));
    wsResult.setData(context);
    return wsResult;
  }

  /**
   * This method handles the search for similar entities based on the provided search term and entity name.
   * It first retrieves the search term and entity name from the request parameters.
   * It then creates a map of entity names to their corresponding classes.
   * If the entity name is not provided or is not in the map, it returns an error message.
   * If the entity name is valid, it constructs a where clause for the search query based on the minimum similarity percent.
   * It then calls the searchEntities method to perform the search and returns the results.
   *
   * @param requestParams
   *     A map of request parameters where the key is the parameter name and the value is the parameter value.
   * @return A WSResult object containing the status of the operation and the search results.
   * @throws JSONException
   *     If an error occurs while processing the JSON data.
   * @throws NoSuchFieldException
   *     If a field with the specified name is not found.
   * @throws IllegalAccessException
   *     If the currently executing method does not have access to the definition of the specified field.
   */
  public static WSResult handleSimSearch(
      Map<String, String> requestParams) throws JSONException, NoSuchFieldException, IllegalAccessException {
    String searchTerm = requestParams.get("searchTerm");
    String entityName = requestParams.get("entityName");
    Result result = new Result(searchTerm, entityName);
    int qtyResults = Integer.parseInt(requestParams.getOrDefault("qtyResults", "1"));
    String minSimmilarityPercent = requestParams.getOrDefault("minSimPercent", String.valueOf(MIN_SIM_PERCENT));

    HashMap<String, Class<? extends BaseOBObject>> entityNameClassMap = new HashMap<>();
    entityNameClassMap.put("Product", Product.class);
    entityNameClassMap.put("BusinessPartner", BusinessPartner.class);
    entityNameClassMap.put("PaymentTerm", PaymentTerm.class);
    entityNameClassMap.put("DocumentType", DocumentType.class);

    //if entityName is not provided or not in the map, return a message with error
    if (result.entityName == null || !entityNameClassMap.containsKey(result.entityName)) {
      WSResult wsResult = new WSResult();
      wsResult.setStatus(Status.OK);
      JSONObject errorJson = new JSONObject();
      errorJson.put("status", "error");
      String entityList = entityNameClassMap.keySet().stream().reduce("", (a, b) -> a + ", " + b);
      String errmsg = String.format(OBMessageUtils.messageBD("ETCPOPP_SearchEntityNotSupported"), entityList);

      errorJson.put(MESSAGE_RESULT_PROPERTY, errmsg);

      wsResult.setData(errorJson);
      return wsResult;
    }

    WSResult wsResult = new WSResult();
    JSONArray arrayResponse;
    String whereOrderByClause2 = String.format(
        " as p where  etcpopp_sim_search(:tableName, p.id, :searchTerm) > %s order by etcpopp_sim_search(:tableName, p.id, :searchTerm) desc ",
        Integer.parseInt(minSimmilarityPercent));
    Class<? extends BaseOBObject> entityClass = entityNameClassMap.get(result.entityName);
    arrayResponse = searchEntities(entityClass, whereOrderByClause2, result.searchTerm, qtyResults);
    wsResult.setStatus(Status.OK);
    wsResult.setData(arrayResponse);
    return wsResult;
  }

  /**
   * This is a helper class used to encapsulate the search term and entity name into a single object.
   * It is used in the handleSimSearch method to simplify the handling of these two parameters.
   */
  private static class Result {
    /**
     * The search term to be used in the similarity search.
     */
    public final String searchTerm;

    /**
     * The name of the entity to be searched for similarity.
     */
    public final String entityName;

    /**
     * Constructs a new Result object with the specified search term and entity name.
     *
     * @param searchTerm
     *     The search term to be used in the similarity search.
     * @param entityName
     *     The name of the entity to be searched for similarity.
     */
    public Result(String searchTerm, String entityName) {
      this.searchTerm = searchTerm;
      this.entityName = entityName;
    }
  }


  /**
   * This method is used to search for entities that are similar to the provided search term.
   * It creates an OBQuery object for the specified entity class and sets the where clause and parameters for the query.
   * The query is executed and the results are converted into a JSONArray of JSONObjects.
   * Each JSONObject contains the ID and name of the entity and the similarity percent between the entity and the search term.
   *
   * @param <T>
   *     The type of the entity to be searched. It must be a subclass of BaseOBObject.
   * @param entityClass
   *     The class of the entity to be searched.
   * @param whereOrderByClause2
   *     The where clause for the search query.
   * @param searchTerm
   *     The search term to be used in the similarity search.
   * @param qtyResults
   *     The maximum number of results to be returned by the search.
   * @return A JSONArray of JSONObjects, each representing a search result.
   * @throws JSONException
   *     If an error occurs while processing the JSON data.
   * @throws NoSuchFieldException
   *     If a field with the specified name is not found.
   * @throws IllegalAccessException
   *     If the currently executing method does not have access to the definition of the specified field.
   */
  private static <T extends BaseOBObject> JSONArray searchEntities(Class<T> entityClass, String whereOrderByClause2,
      String searchTerm, int qtyResults) throws JSONException, NoSuchFieldException, IllegalAccessException {

    OBQuery<T> searchQuery = OBDal.getInstance().createQuery(entityClass, whereOrderByClause2);
    Field tableNameField = entityClass.getField("TABLE_NAME");
    String tableName = (String) tableNameField.get(null);
    searchQuery.setNamedParameter("tableName", StringUtils.lowerCase(tableName));
    searchQuery.setNamedParameter("searchTerm", searchTerm);
    searchQuery.setMaxResult(qtyResults);
    var resultList = searchQuery.list();
    JSONArray arrayResponse = new JSONArray();
    for (T resultOBJ : resultList) {
      JSONObject searchResultJson = new JSONObject();
      searchResultJson.put("id", resultOBJ.getId());
      searchResultJson.put("name", resultOBJ.getIdentifier());
      BigDecimal percent = calcSimilarityPercent((String) resultOBJ.getId(), searchTerm, tableName);
      searchResultJson.put("similarity_percent", percent.toString() + "%");
      arrayResponse.put(searchResultJson);
    }
    return arrayResponse;
  }

  private static BigDecimal calcSimilarityPercent(String id, String searchTerm, String tableName) {
    String sql = String.format("select etcpopp_sim_search('%s', '%s', '%s')", tableName, id, searchTerm);
    Query query = OBDal.getInstance().getSession().createSQLQuery(sql);
    ScrollableResults scroll = query.scroll(ScrollMode.FORWARD_ONLY);
    scroll.next();
    BigDecimal percent = (BigDecimal) scroll.get(0);
    return percent.setScale(4, RoundingMode.HALF_UP);
  }

  @Override
  public WSResult post(String path, Map<String, String> parameters, JSONObject body) throws Exception {
    WSResult wsResult = new WSResult();
    if (StringUtils.equalsIgnoreCase("/calcTaxes", path)) {
      return calcTaxes(body, wsResult);
    } else {
      wsResult.setStatus(Status.OK);
      var data = new JSONObject();
      data.put(MESSAGE_RESULT_PROPERTY, "The path is not supported");
      wsResult.setData(data);
    }

    return wsResult;
  }

  private WSResult calcTaxes(JSONObject body, WSResult wsResult) throws JSONException {
    ArrayList<OrderLine> orderLinesList = new ArrayList<>();
    if (body.has("orderLineId")) {
      String orderLineId = body.getString("orderLineId");
      OrderLine ol = OBDal.getInstance().get(OrderLine.class, orderLineId);
      if (ol == null) {
        wsResult.setStatus(Status.BAD_REQUEST);
        wsResult.setMessage("OrderLine not found");
        return wsResult;
      }
      orderLinesList.add(ol);
    } else if (body.has("orderId")) {
      String orderId = body.getString("orderId");
      Order order = OBDal.getInstance().get(Order.class, orderId);
      if (order == null) {
        wsResult.setStatus(Status.BAD_REQUEST);
        wsResult.setMessage("Order not found");
        return wsResult;
      }
      orderLinesList.addAll(order.getOrderLineList());
    }

    JSONArray arrayResponse = new JSONArray();
    for (OrderLine orderLine : orderLinesList) {
      String msg = recalcTaxes(orderLine);
      var itemData = new JSONObject();
      itemData.put(MESSAGE_RESULT_PROPERTY, String.format("OrderLine: %s %s", orderLine.getIdentifier(), msg));
      arrayResponse.put(itemData);
    }
    wsResult.setStatus(Status.OK);
    wsResult.setData(arrayResponse);
    return wsResult;
  }

  public static String recalcTaxes(OrderLine orderLine) {
    try {
      Order order = orderLine.getSalesOrder();
      BigDecimal priceActual;
      String productPriceId = getProductPrice(order.getOrderDate(), order.getPriceList(), orderLine.getProduct());
      BigDecimal priceList = BigDecimal.ZERO;
      BigDecimal priceStd = BigDecimal.ZERO;
      BigDecimal priceLimit = BigDecimal.ZERO;
      if (productPriceId != null) {
        ProductPrice productPrice = OBDal.getInstance().get(ProductPrice.class, productPriceId);

        priceList = productPrice.getListPrice();
        priceStd = productPrice.getStandardPrice();
        priceLimit = productPrice.getPriceLimit();
      }

      boolean isTaxIncludedPriceList = order.getPriceList().isPriceIncludesTax();
      boolean cancelPriceAd = orderLine.isCancelPriceAdjustment();

      BigDecimal netPriceList = priceList;
      BigDecimal grossPriceList = priceList;
      BigDecimal grossBaseUnitPrice = priceStd;

      Product product = orderLine.getProduct();
      if (!cancelPriceAd) {
        BigDecimal orderedQuantity = orderLine.getOrderedQuantity();
        if (isTaxIncludedPriceList) {
          priceActual = PriceAdjustment.calculatePriceActual(order, product, orderedQuantity, grossBaseUnitPrice);
          netPriceList = BigDecimal.ZERO;
        } else {
          priceActual = PriceAdjustment.calculatePriceActual(order, product, orderedQuantity, priceStd);
          grossPriceList = BigDecimal.ZERO;
        }
      } else {
        priceActual = isTaxIncludedPriceList ? grossBaseUnitPrice : priceList;
      }


      // Prices
      if (isTaxIncludedPriceList) {
        //Gross Unit Price
        orderLine.setGrossUnitPrice(priceActual);
        //Gross Price List
        orderLine.setGrossListPrice(grossPriceList);
        //Gross Base Unit Price
        orderLine.setBaseGrossUnitPrice(grossBaseUnitPrice);
      } else {
        //Net Price List
        orderLine.setListPrice(netPriceList);
        //Price Limit
        orderLine.setPriceLimit(priceLimit);
        //Price Std
        orderLine.setStandardPrice(priceStd);
        //Price Actual
        orderLine.setUnitPrice(priceActual);
      }
      // Discount
      BigDecimal calculatedDiscount = BigDecimal.ZERO;
      BigDecimal price = isTaxIncludedPriceList ? grossPriceList : netPriceList;
      if (!BigDecimal.ZERO.equals(price)) {
        int precision = order.getCurrency().getPricePrecision().intValue();
        calculatedDiscount = price.subtract(priceActual).multiply(BigDecimal.valueOf(100)).divide(price, precision,
            RoundingMode.HALF_UP);
      }


      BigDecimal disc = orderLine.getDiscount();
      if (calculatedDiscount.compareTo(disc) != 0) {
        orderLine.setDiscount(calculatedDiscount);
      }
      taxSearchAndSet(orderLine, order, product);
      log.debug(String.format("OrderLine inserted: %s", orderLine.getId()));
      OBDal.getInstance().save(orderLine);
      OBDal.getInstance().flush();
    } catch (Exception e) {
      log.error("Error recalculating taxes", e);
      return "Error recalculating taxes, Adjust the price and taxes manually.";
    }

    return "Taxes recalculated successfully.";
  }

  private static void taxSearchAndSet(OrderLine ol, Order order, Product product) throws ServletException, IOException {
    //formate order.getOrderDate to string year-month-day. Dont use FormatUtilities
    String dateFromat = OBPropertiesProvider.getInstance().getOpenbravoProperties().getProperty("dateFormat.java");
    SimpleDateFormat dateformat = new SimpleDateFormat(dateFromat);
    String strOrderDate = dateformat.format(order.getOrderDate());
    DalConnectionProvider connectionProvider = new DalConnectionProvider(false);
    Project project = order.getProject();

    String strCTaxID = Tax.get(connectionProvider, product.getId(), strOrderDate, order.getOrganization().getId(),
        order.getWarehouse().getId(), order.getPartnerAddress().getId(), order.getPartnerAddress().getId(),
        project != null ? project.getId() : null, order.isSalesTransaction());
    TaxRate tax = OBDal.getInstance().get(TaxRate.class, strCTaxID);

    log.debug(String.format("TaxRate: %s", tax.getName()));
    ol.setTax(tax);


  }


  @Override
  public WSResult put(String path, Map<String, String> parameters, JSONObject body) throws Exception {
    return null;
  }

  @Override
  public WSResult delete(String path, Map<String, String> parameters, JSONObject body) throws Exception {
    return null;
  }

  public static String getProductPrice(Date date, PriceList priceList, Product product) {
    try {
      OBContext.setAdminMode(true);
      //@formatter:off
      String hql = new StringBuilder()
          .append("select pp.id ")
          .append(" from PricingProductPrice as pp ")
          .append(" join pp.priceListVersion as plv ")
          .append(" join plv.priceList as pl ")
          .append(" where pp.product.id = :productId ")
          .append(" and plv.validFromDate <= :date ")
          .append(" and pl.id = :pricelistId ")
          .append(" and pl.active = true ")
          .append(" and pp.active = true ")
          .append(" and plv.active = true ")
          .append(" order by pl.default desc, plv.validFromDate desc")
          .toString();
      //@formatter:on
      return OBDal.getInstance().getSession().createQuery(hql, String.class).setParameter("productId",
              product.getId()).setParameter("date", date).setParameter("pricelistId", priceList.getId())
          .setMaxResults(1).uniqueResult();
    } finally {
      OBContext.restorePreviousMode();
    }
  }

}

