package com.etendoerp.copilot.openapi.purchase.webhooks;

import com.etendoerp.webhookevents.services.BaseWebhookService;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.openbravo.base.weld.WeldUtils;
import org.openbravo.client.application.attachment.AttachImplementationManager;
import org.openbravo.dal.service.OBDal;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;

public class AttachFileWebhook extends BaseWebhookService {

  private static final Logger log = LogManager.getLogger();

  @Override
  public void get(Map<String, String> parameter, Map<String, String> responseVars) {
    log.info("Executing AttachmentWebHook process");

    String adTableId = parameter.get("ADTabId");
    String recordId = parameter.get("RecordId");
    String fileName = parameter.get("FileName");
    String fileContent = parameter.get("FileContent");

    if (adTableId == null || recordId == null || fileContent == null || fileName == null) {
      responseVars.put("error", "Missing required parameters");
      return;
    }

    try {
      File file = storeBase64ToTempFile(fileContent, fileName);
      createAttachment(adTableId, recordId, fileName, file);
      responseVars.put("message", "Attachment created successfully");
    } catch (Exception e) {
      log.error("Error creating attachment", e);
      responseVars.put("error", e.getMessage());
    }
  }

  private File storeBase64ToTempFile(String fileContent, String fileName) {
    File tempFile = null;
    try {
      tempFile = Files.createTempFile(null, fileName).toFile();
      try (FileOutputStream fos = new FileOutputStream(tempFile)) {
        byte[] fileBytes = javax.xml.bind.DatatypeConverter.parseBase64Binary(fileContent);
        fos.write(fileBytes);
      }
    } catch (Exception e) {
      log.error("Error storing base64 content to temp file", e);
    }
    return tempFile;
  }

  private void createAttachment(String adTableId, String recordId, String fileName, File file) {
    try {
      AttachImplementationManager aim = WeldUtils.getInstanceFromStaticBeanManager(
          AttachImplementationManager.class);
      aim.upload(new HashMap<>(), adTableId, recordId, fileName, file);
    } catch (Exception e) {
      OBDal.getInstance().rollbackAndClose();
      throw e;
    }
  }

}