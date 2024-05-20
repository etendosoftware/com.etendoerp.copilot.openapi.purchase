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
import org.openbravo.base.session.OBPropertiesProvider;
import org.openbravo.base.structure.BaseOBObject;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.dal.service.OBQuery;
import org.openbravo.erpCommon.businessUtility.PriceAdjustment;
import org.openbravo.erpCommon.businessUtility.Tax;
import org.openbravo.erpCommon.utility.OBMessageUtils;
import org.openbravo.model.common.businesspartner.BusinessPartner;
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

/**
 * @author androettop
 */
public class CopilotWSServlet extends BaseWebService {

  public static final int MIN_SIM_PERCENT = 30;
  private static final Logger log = LoggerFactory.getLogger(CopilotWSServlet.class);

  @Override
  public WSResult get(String path, Map<String, String> requestParams) throws Exception {
    String searchTerm = requestParams.get("searchTerm");
    String entityName = requestParams.get("entityName");

    HashMap<String, Class<? extends BaseOBObject>> entityNameClassMap = new HashMap<>();
    entityNameClassMap.put("Product", Product.class);
    entityNameClassMap.put("BusinessPartner", BusinessPartner.class);
    entityNameClassMap.put("PaymentTerm", PaymentTerm.class);

    //if entityName is not provided or not in the map, return a message with error
    if (entityName == null || !entityNameClassMap.containsKey(entityName)) {
      WSResult wsResult = new WSResult();
      wsResult.setStatus(Status.OK);
      JSONObject errorJson = new JSONObject();
      errorJson.put("status", "error");
      String entityList = entityNameClassMap.keySet().stream().reduce("", (a, b) -> a + ", " + b);
      String errmsg = String.format(OBMessageUtils.messageBD("ETCPOPP_SearchEntityNotSupported"), entityList);

      errorJson.put("message", errmsg);

      wsResult.setData(errorJson);
      return wsResult;
    }

    WSResult wsResult = new WSResult();
    //read searchTerm
    JSONArray arrayResponse = new JSONArray();


    String whereOrderByClause2 = String.format(
        " as p where  etcpopp_sim_search(:tableName, p.id, :searchTerm) > %s order by etcpopp_sim_search(:tableName, p.id, :searchTerm) desc ",
        MIN_SIM_PERCENT);
    Class<? extends BaseOBObject> entityClass = entityNameClassMap.get(entityName);
    JSONObject searchResultJson = searchEntities(entityClass, whereOrderByClause2, searchTerm);
    arrayResponse.put(searchResultJson);


    wsResult.setStatus(Status.OK);
    wsResult.setData(arrayResponse);
    return wsResult;
  }


  private <T extends BaseOBObject> JSONObject searchEntities(Class<T> entityClass, String whereOrderByClause2,
      String searchTerm) throws JSONException, NoSuchFieldException, IllegalAccessException {

    OBQuery<T> searchQuery = OBDal.getInstance().createQuery(entityClass, whereOrderByClause2);
    Field tableNameField = entityClass.getField("TABLE_NAME");
    String tableName = (String) tableNameField.get(null);
    searchQuery.setNamedParameter("tableName", StringUtils.lowerCase(tableName));
    searchQuery.setNamedParameter("searchTerm", searchTerm);
    searchQuery.setMaxResult(1);
    T resultOBJ = searchQuery.uniqueResult();
    JSONObject searchResultJson = new JSONObject();
    if (resultOBJ == null) {
      return searchResultJson;
    }
    searchResultJson.put("id", resultOBJ.getId());
    searchResultJson.put("name", resultOBJ.getIdentifier());
    BigDecimal percent = calcSimilarityPercent((String) resultOBJ.getId(), searchTerm, tableName);
    searchResultJson.put("similarity_percent", percent.toString() + "%");
    entityClass.getFields();
    return searchResultJson;
  }

  private BigDecimal calcSimilarityPercent(String id, String searchTerm, String tableName) {
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
      data.put("message", "The path is not supported");
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
      itemData.put("message", String.format("OrderLine: %s %s", orderLine.getIdentifier(), msg));
      arrayResponse.put(itemData);
    }
    wsResult.setStatus(Status.OK);
    wsResult.setData(arrayResponse);
    return wsResult;
  }

  private String recalcTaxes(OrderLine orderLine) {
    try {
      var ol = orderLine;
      Order order = ol.getSalesOrder();

      BigDecimal priceActual;
      String productPriceId = getProductPrice(order.getOrderDate(), order.getPriceList(), ol.getProduct());
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
      boolean cancelPriceAd = ol.isCancelPriceAdjustment();

      BigDecimal netPriceList = priceList;
      BigDecimal grossPriceList = priceList;
      BigDecimal grossBaseUnitPrice = priceStd;

      Product product = ol.getProduct();
      if (!cancelPriceAd) {
        BigDecimal orderedQuantity = ol.getOrderedQuantity();
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
        ol.setGrossUnitPrice(priceActual);
        //Gross Price List
        ol.setGrossListPrice(grossPriceList);
        //Gross Base Unit Price
        ol.setBaseGrossUnitPrice(grossBaseUnitPrice);
      } else {
        //Net Price List
        ol.setListPrice(netPriceList);
        //Price Limit
        ol.setPriceLimit(priceLimit);
        //Price Std
        ol.setStandardPrice(priceStd);
        //Price Actual
        ol.setUnitPrice(priceActual);
      }
      // Discount
      BigDecimal calculatedDiscount = BigDecimal.ZERO;
      BigDecimal price = isTaxIncludedPriceList ? grossPriceList : netPriceList;
      if (!BigDecimal.ZERO.equals(price)) {
        int precision = order.getCurrency().getPricePrecision().intValue();
        calculatedDiscount = price.subtract(priceActual).multiply(BigDecimal.valueOf(100)).divide(price, precision,
            RoundingMode.HALF_UP);
      }


      BigDecimal disc = ol.getDiscount();
      if (calculatedDiscount.compareTo(disc) != 0) {
        ol.setDiscount(calculatedDiscount);
      }
      taxSearchAndSet(ol, order, product);
      log.debug("OrderLine inserted: " + ol.getId());
      OBDal.getInstance().save(ol);
      OBDal.getInstance().flush();
    } catch (Exception e) {
      log.error("Error recalculating taxes", e);
      return "Error recalculating taxes, Adjust the price and taxes manually.";
    }

    return "Taxes recalculated successfully.";
  }

  private void taxSearchAndSet(OrderLine ol, Order order, Product product) throws ServletException, IOException {
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

    log.info("TaxRate: " + tax.getName());
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
      return OBDal.getInstance()
          .getSession()
          .createQuery(hql, String.class)
          .setParameter("productId", product.getId())
          .setParameter("date", date)
          .setParameter("pricelistId", priceList.getId())
          .setMaxResults(1)
          .uniqueResult();
    } finally {
      OBContext.restorePreviousMode();
    }
  }

}

