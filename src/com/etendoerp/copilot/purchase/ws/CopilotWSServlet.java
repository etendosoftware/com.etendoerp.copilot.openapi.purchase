package com.etendoerp.copilot.purchase.ws;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.Map;

import org.apache.commons.lang.StringUtils;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.ScrollMode;
import org.hibernate.ScrollableResults;
import org.hibernate.query.Query;
import org.openbravo.base.structure.BaseOBObject;
import org.openbravo.dal.service.OBDal;
import org.openbravo.dal.service.OBQuery;
import org.openbravo.erpCommon.utility.OBMessageUtils;
import org.openbravo.model.common.businesspartner.BusinessPartner;
import org.openbravo.model.common.plm.Product;
import org.openbravo.model.financialmgmt.payment.PaymentTerm;

import com.smf.securewebservices.service.BaseWebService;
import com.smf.securewebservices.utils.WSResult;
import com.smf.securewebservices.utils.WSResult.Status;

/**
 * @author androettop
 */
public class CopilotWSServlet extends BaseWebService {

  public static final int MIN_SIM_PERCENT = 30;

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
      String entityList = entityNameClassMap.keySet().stream().reduce(
          "", (a, b) -> a + ", " + b);
      String errmsg = String.format(
          OBMessageUtils.messageBD("ETCPOPP_SearchEntityNotSupported"),
          entityList);

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
    String sql = String.format("select etcpopp_sim_search('%s','%s', '%s')", tableName, id, searchTerm);
    Query query = OBDal.getInstance().getSession().createSQLQuery(sql);
    ScrollableResults scroll = query.scroll(ScrollMode.FORWARD_ONLY);
    scroll.next();
    BigDecimal percent = (BigDecimal) scroll.get(0);
    return percent.setScale(4, RoundingMode.HALF_UP);
  }

  @Override
  public WSResult post(String path, Map<String, String> parameters, JSONObject body) throws Exception {
    return null;
  }

  @Override
  public WSResult put(String path, Map<String, String> parameters, JSONObject body) throws Exception {
    return null;
  }

  @Override
  public WSResult delete(String path, Map<String, String> parameters, JSONObject body) throws Exception {
    return null;
  }
}

