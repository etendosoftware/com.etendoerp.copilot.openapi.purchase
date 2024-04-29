
package com.etendoerp.copilot.openapi.purchase.modulescript;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;

import org.apache.commons.lang.StringUtils;

import org.openbravo.utils.FileUtility;
import org.openbravo.utils.FormatUtilities;
import org.openbravo.database.ConnectionProvider;
import org.openbravo.modulescript.ModuleScript;
import org.openbravo.modulescript.ModuleScriptExecutionLimits;
import org.openbravo.modulescript.OpenbravoVersion;

public class AddExtensionTrgm  extends ModuleScript {

  public void execute() {
    try {
      ConnectionProvider cp = getConnectionProvider();
      PreparedStatement ps = cp
          .getPreparedStatement("create extension if not exists pg_trgm;");
      ps.executeUpdate();
    } catch (Exception e) {
      handleError(e);
    }
  }
}
