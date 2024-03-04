package com.etendoerp.copilot.purchase.ws;

import java.util.List;
import java.util.Map;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.criterion.Restrictions;
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

  @Override
  public WSResult get(String path, Map<String, String> requestParams) throws Exception {
    Map<String, String> parameters = OBRestUtils.mapRestParameters(requestParams);
    String searchTerm = parameters.get("searchTerm");

    WSResult wsResult = new WSResult();
    boolean ilike = false;
    //read searchTerm
    List<Product> prodL = null;
    if (ilike) {
      prodL = OBDal.getInstance().createCriteria(Product.class)
          .add(Restrictions.ilike(Product.PROPERTY_NAME, "%" + searchTerm + "%"))
          .list();
    } else {
      OBQuery<Product> prodlQuery = OBDal.getInstance().createQuery(Product.class,
          "as p where p.id = etcpopp_sim_search(:tableName,:searchTerm)");

      prodlQuery.setNamedParameter("tableName", Product.TABLE_NAME.toLowerCase());
      prodlQuery.setNamedParameter("searchTerm", searchTerm);
      prodL = prodlQuery.list();
    }
    JSONArray arrayResponse = new JSONArray();
    for (Product product : prodL) {
      JSONObject productJson = new JSONObject();
      productJson.put("id", product.getId());
      productJson.put("name", product.getName());
      arrayResponse.put(productJson);
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

