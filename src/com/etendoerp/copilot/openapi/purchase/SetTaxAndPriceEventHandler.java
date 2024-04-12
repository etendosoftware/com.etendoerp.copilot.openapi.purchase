package com.etendoerp.copilot.openapi.purchase;


import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Properties;

import javax.enterprise.event.Observes;

import org.apache.commons.lang.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.openbravo.base.model.Entity;
import org.openbravo.base.model.ModelProvider;
import org.openbravo.base.model.Property;
import org.openbravo.base.session.OBPropertiesProvider;
import org.openbravo.client.kernel.event.EntityNewEvent;
import org.openbravo.client.kernel.event.EntityPersistenceEventObserver;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.erpCommon.businessUtility.PriceAdjustment;
import org.openbravo.erpCommon.businessUtility.Tax;
import org.openbravo.model.common.order.Order;
import org.openbravo.model.common.order.OrderLine;
import org.openbravo.model.common.plm.Product;
import org.openbravo.model.financialmgmt.tax.TaxRate;
import org.openbravo.model.pricing.pricelist.PriceList;
import org.openbravo.model.pricing.pricelist.ProductPrice;
import org.openbravo.model.project.Project;
import org.openbravo.service.db.DalConnectionProvider;

public class SetTaxAndPriceEventHandler extends EntityPersistenceEventObserver {
  private static Entity[] entities = {
      ModelProvider.getInstance().getEntity(OrderLine.ENTITY_NAME) };

  @Override
  protected Entity[] getObservedEntities() {
    return entities;
  }

  private static final Logger log = LogManager.getLogger(SetTaxAndPriceEventHandler.class);

  private void onInsert(@Observes EntityNewEvent event) {
    if (!isValidEvent(event)) {
      return;
    }
    OrderLine ol = (OrderLine) event.getTargetInstance();
    if (!(orderIsFromCopilot(ol.getSalesOrder()) && orderIsDraft(ol.getSalesOrder()))) {
      return;
    }

    Entity entity = event.getTargetInstance().getEntity();
    Property propertyCancelPriceAd = entity.getProperty(OrderLine.PROPERTY_CANCELPRICEADJUSTMENT);
    Order order = ol.getSalesOrder();

    BigDecimal priceActual = BigDecimal.ZERO;
    String productProdId = getProductPrice(order.getOrderDate(), order.getPriceList(), ol.getProduct());
    BigDecimal priceList = BigDecimal.ZERO;
    BigDecimal priceStd = BigDecimal.ZERO;
    BigDecimal priceLimit = BigDecimal.ZERO;
    if (productProdId != null) {
      ProductPrice productPrice = OBDal.getInstance().get(ProductPrice.class, productProdId);

      priceList = productPrice.getListPrice();
      priceStd = productPrice.getStandardPrice();
      priceLimit = productPrice.getPriceLimit();
    }

    boolean isTaxIncludedPriceList = order.getPriceList().isPriceIncludesTax();
    boolean cancelPriceAd = (boolean) event.getCurrentState(propertyCancelPriceAd);

    BigDecimal netPriceList = priceList;
    BigDecimal grossPriceList = priceList;
    BigDecimal grossBaseUnitPrice = priceStd;

    Product product = ol.getProduct();
    if (!cancelPriceAd) {
      BigDecimal orderedQuantity = ol.getOrderedQuantity();
      if (isTaxIncludedPriceList) {
        priceActual = PriceAdjustment.calculatePriceActual(order, product, orderedQuantity,
            grossBaseUnitPrice);
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
      Property propertyGrossUnitPrice = entity.getProperty(OrderLine.PROPERTY_GROSSUNITPRICE);
      event.setCurrentState(propertyGrossUnitPrice, priceActual);
      //Gross Price List
      Property propertyGrossPriceList = entity.getProperty(OrderLine.PROPERTY_GROSSLISTPRICE);
      event.setCurrentState(propertyGrossPriceList, grossPriceList);
      //Gross Base Unit Price
      Property propertyGrossBaseUnitPrice = entity.getProperty(OrderLine.PROPERTY_BASEGROSSUNITPRICE);
      event.setCurrentState(propertyGrossBaseUnitPrice, grossBaseUnitPrice);

    } else {

      //Net Price List
      Property propertyNetPriceList = entity.getProperty(OrderLine.PROPERTY_LISTPRICE);
      event.setCurrentState(propertyNetPriceList, netPriceList);
      //Price Limit
      Property propertyPriceLimit = entity.getProperty(OrderLine.PROPERTY_PRICELIMIT);
      event.setCurrentState(propertyPriceLimit, priceLimit);
      //Price Std
      Property propertyPriceStd = entity.getProperty(OrderLine.PROPERTY_STANDARDPRICE);
      event.setCurrentState(propertyPriceStd, priceStd);
      //Price Actual
      Property propertyPriceActual = entity.getProperty(OrderLine.PROPERTY_UNITPRICE);
      event.setCurrentState(propertyPriceActual, priceActual);

    }


    // Discount
    BigDecimal calculatedDiscount = BigDecimal.ZERO;
    BigDecimal price = isTaxIncludedPriceList ? grossPriceList : netPriceList;
    if (!BigDecimal.ZERO.equals(price)) {
      int precision = order.getCurrency().getPricePrecision().intValue();
      calculatedDiscount = price.subtract(priceActual)
          .multiply(BigDecimal.valueOf(100))
          .divide(price, precision)
          .setScale(0, RoundingMode.HALF_UP);
    }
    Property PropertyDiscount = entity.getProperty(OrderLine.PROPERTY_DISCOUNT);
    if (calculatedDiscount.compareTo((BigDecimal) event.getCurrentState(PropertyDiscount)) != 0) {
      event.setCurrentState(PropertyDiscount, calculatedDiscount);
    }
    taxSearchAndSet(event, entity, order, product);

    log.debug("OrderLine inserted: " + ol.getId());

  }

  private void taxSearchAndSet(EntityNewEvent event, Entity entity, Order order, Product product) {
    Property propertyTax = entity.getProperty(OrderLine.PROPERTY_TAX);
    //formate order.getOrderDate to string year-month-day. Dont use FormatUtilities
    String dateFromat = OBPropertiesProvider.getInstance().getOpenbravoProperties().getProperty("dateFormat.java");
    SimpleDateFormat dateformat = new SimpleDateFormat(dateFromat);
    String strOrderDate = dateformat.format(order.getOrderDate());
    try {
      DalConnectionProvider connectionProvider = new DalConnectionProvider(false);
      Project project = order.getProject();

      String strCTaxID = Tax.get(connectionProvider, product.getId(), strOrderDate, order.getOrganization().getId(),
          order.getWarehouse().getId(),
          order.getPartnerAddress().getId(),
          order.getPartnerAddress().getId(), project != null ? project.getId() : null, order.isSalesTransaction());
      TaxRate tax = OBDal.getInstance().get(TaxRate.class, strCTaxID);
      log.info("TaxRate: " + tax.getName());
      event.setCurrentState(propertyTax, tax);
    } catch (Exception e) {
      log.error("OrderLin error: " + e.getMessage());

    }
  }

  private boolean orderIsDraft(Order salesOrder) {
    return StringUtils.equalsIgnoreCase(salesOrder.getDocumentStatus(), "DR");
  }

  private boolean orderIsFromCopilot(Order salesOrder) {
    return salesOrder.isETCPOPPFromCopilot();
  }


  public static String getProductPrice(Date date, PriceList priceList, Product product) {
    OBContext.setAdminMode(true);
    try {
      //@formatter:off
      String hql = "select pp.id "
          +" from PricingProductPrice as pp "
          + " join pp.priceListVersion as plv "
          + " join plv.priceList as pl "
          + " where pp.product.id = :productId "
          + " and plv.validFromDate <= :date "
          + " and pl.id = :pricelistId "
          + " and pl.active = true "
          + " and pp.active = true "
          + " and plv.active = true "
          + " order by pl.default desc, plv.validFromDate desc";
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
