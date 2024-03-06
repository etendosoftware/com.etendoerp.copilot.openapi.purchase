package com.etendoerp.copilot.purchase.ws;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.ScrollMode;
import org.hibernate.ScrollableResults;
import org.hibernate.criterion.Restrictions;
import org.hibernate.query.Query;
import org.openbravo.dal.service.OBDal;
import org.openbravo.dal.service.OBQuery;
import org.openbravo.model.common.plm.Product;

import com.smf.securewebservices.rsql.OBRestUtils;
import com.smf.securewebservices.service.BaseWebService;
import com.smf.securewebservices.utils.WSResult;
import com.smf.securewebservices.utils.WSResult.Status;

/**
 * @author androettop
 */
public class copilotWSServlet extends BaseWebService {

  public static final int MIN_SIM_PERCENT = 40;

  @Override
  public WSResult get(String path, Map<String, String> requestParams) throws Exception {
    Map<String, String> parameters = OBRestUtils.mapRestParameters(requestParams);
    String searchTerm = requestParams.get("searchTerm");

    WSResult wsResult = new WSResult();
    boolean ilike = false;
    //read searchTerm
    List<Product> prodL = null;
    JSONArray arrayResponse = new JSONArray();

    if (ilike) { //TODO, do a ilike search
      prodL = OBDal.getInstance().createCriteria(Product.class)
          .add(Restrictions.ilike(Product.PROPERTY_NAME, "%" + searchTerm + "%"))
          .list();
      for (Product product : prodL) {
        JSONObject productJson = new JSONObject();
        productJson.put("id", product.getId());
        productJson.put("name", product.getName());
        arrayResponse.put(productJson);
      }

    } else {
      String whereOrderByClause = String.format(
          " select p.id, p.name, etcpopp_sim_search(:tableName, p.id, :searchTerm) as similarity_percent  from Product as p where  etcpopp_sim_search(:tableName, p.id, :searchTerm) > %s order by etcpopp_sim_search(:tableName, p.id, :searchTerm) desc ",
          MIN_SIM_PERCENT);
      Query prodlQuery = OBDal.getInstance().getSession().createQuery(whereOrderByClause);
      prodlQuery.setParameter("tableName", Product.TABLE_NAME.toLowerCase());
      prodlQuery.setParameter("searchTerm", searchTerm);
      prodlQuery.setMaxResults(10);
      ScrollableResults results = prodlQuery.scroll(ScrollMode.FORWARD_ONLY);
      while (results.next()) {
        JSONObject productJson = new JSONObject();
        productJson.put("id", (String) results.get(0));
        productJson.put("name", (String) results.get(1));
        BigDecimal percent = ((BigDecimal) results.get(2)).setScale(4, RoundingMode.HALF_UP);
        productJson.put("similarity_percent", percent.toString() + "%");
        arrayResponse.put(productJson);
        break;
      }
    }


    wsResult.setStatus(Status.OK);
    wsResult.setData(arrayResponse);
    return wsResult;
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

