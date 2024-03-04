package com.etendoerp.copilot.openapi.purchase;
import java.util.HashMap;
import java.util.Map;

import org.hibernate.dialect.function.SQLFunction;
import org.hibernate.dialect.function.StandardSQLFunction;
import org.hibernate.type.StandardBasicTypes;
import org.openbravo.dal.core.SQLFunctionRegister;

public class SqlToHqlInitializer implements SQLFunctionRegister {

  @Override
  public Map<String, SQLFunction> getSQLFunctions() {
    Map<String, SQLFunction> sqlFunctions = new HashMap<>();

    sqlFunctions.put("etcpopp_sim_search",
        new StandardSQLFunction("etcpopp_sim_search", StandardBasicTypes.STRING));
    return sqlFunctions;
  }
}
