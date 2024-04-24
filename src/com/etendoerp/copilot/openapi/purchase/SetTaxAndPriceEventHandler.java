package com.etendoerp.copilot.openapi.purchase;


import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.SimpleDateFormat;
import java.util.Date;

import javax.enterprise.event.Observes;

import org.apache.commons.lang.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.openbravo.base.exception.OBException;
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

  /**
   * This method handles the insertion of a new order line.
   * It first checks if the event is valid and if the order is from Copilot and in draft status.
   * Then, it retrieves the product price based on the order date, price list, and product.
   * It calculates the actual price based on whether the price list includes tax and whether the price adjustment is cancelled.
   * It sets the prices and discount for the order line.
   * Finally, it sets the tax for the order line and logs the insertion of the order line.
   *
   * @param event
   *     The EntityNewEvent object representing the event of creating a new order line.
   * @throws OBException
   *     If there is an error retrieving the product price or setting the prices, discount, or tax for the order line.
   */
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
    setPrices(event, isTaxIncludedPriceList, entity, priceActual, grossPriceList, grossBaseUnitPrice, netPriceList,
        priceLimit, priceStd);


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
    Property propertyDiscount = entity.getProperty(OrderLine.PROPERTY_DISCOUNT);
    if (calculatedDiscount.compareTo((BigDecimal) event.getCurrentState(propertyDiscount)) != 0) {
      event.setCurrentState(propertyDiscount, calculatedDiscount);
    }
    taxSearchAndSet(event, entity, order, product);
    log.debug("OrderLine inserted: " + ol.getId());

  }

  /**
   * This method sets the prices for an order line during its creation.
   * It checks if the price list includes tax and sets the prices accordingly.
   *
   * @param event
   *     The EntityNewEvent object representing the event of creating a new order line.
   * @param isTaxIncludedPriceList
   *     A boolean indicating whether the price list includes tax.
   * @param entity
   *     The Entity object representing the order line being created.
   * @param priceActual
   *     The actual price of the product.
   * @param grossPriceList
   *     The gross price from the price list.
   * @param grossBaseUnitPrice
   *     The gross base unit price of the product.
   * @param netPriceList
   *     The net price from the price list.
   * @param priceLimit
   *     The price limit for the product.
   * @param priceStd
   *     The standard price for the product.
   */
  private void setPrices(EntityNewEvent event, boolean isTaxIncludedPriceList, Entity entity, BigDecimal priceActual,
      BigDecimal grossPriceList, BigDecimal grossBaseUnitPrice, BigDecimal netPriceList, BigDecimal priceLimit,
      BigDecimal priceStd) {
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
  }

  /**
   * This method sets the tax for an order line during its creation.
   * It retrieves the tax ID based on the product, order date, organization, warehouse, partner address, project, and sales transaction status.
   * Then, it sets the tax for the order line.
   *
   * @param event
   *     The EntityNewEvent object representing the event of creating a new order line.
   * @param entity
   *     The Entity object representing the order line being created.
   * @param order
   *     The Order object representing the order that the order line belongs to.
   * @param product
   *     The Product object representing the product that the order line is for.
   * @throws OBException
   *     If there is an error retrieving the tax ID or setting the tax for the order line.
   */
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
      log.error("OrderLine error: " + e.getMessage());
      throw new OBException(e);
    }
  }

  /**
   * This method checks if the provided sales order is in draft status.
   * It compares the document status of the sales order with the string "DR" (representing draft status) in a case-insensitive manner.
   *
   * @param salesOrder
   *     The Order object representing the sales order to be checked.
   * @return A boolean indicating whether the sales order is in draft status. Returns true if the sales order is in draft status, false otherwise.
   */
  private boolean orderIsDraft(Order salesOrder) {
    return StringUtils.equalsIgnoreCase(salesOrder.getDocumentStatus(), "DR");
  }

  /**
   * This method checks if the provided sales order is in draft status.
   * It compares the document status of the sales order with the string "DR" (representing draft status) in a case-insensitive manner.
   *
   * @param salesOrder
   *     The Order object representing the sales order to be checked.
   * @return A boolean indicating whether the sales order is in draft status. Returns true if the sales order is in draft status, false otherwise.
   */
  private boolean orderIsFromCopilot(Order salesOrder) {
    return salesOrder.isETCPOPPFromCopilot();
  }

  /**
   * This method retrieves the product price ID from the PricingProductPrice table.
   * It constructs an HQL query to select the product price ID based on the product ID, date, and price list ID.
   * The method sets the admin mode to true before executing the query and restores the previous mode after execution.
   *
   * @param date
   *     The Date object representing the date to be used in the query.
   * @param priceList
   *     The PriceList object representing the price list to be used in the query.
   * @param product
   *     The Product object representing the product to be used in the query.
   * @return A String representing the product price ID retrieved from the query. Returns null if no matching product price ID is found.
   */
  public static String getProductPrice(Date date, PriceList priceList, Product product) {
    OBContext.setAdminMode(true);
    try {
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
