package com.intuit.developer.tutorials.controller;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Date;
import java.util.Iterator;
import java.util.List;

import javax.servlet.http.HttpSession;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.intuit.ipp.data.Account;
import com.intuit.ipp.data.AccountClassificationEnum;
import com.intuit.ipp.data.AccountSubTypeEnum;
import com.intuit.ipp.data.AccountTypeEnum;
import com.intuit.ipp.data.Customer;
import com.intuit.ipp.data.EmailAddress;
import com.intuit.ipp.data.Invoice;
import com.intuit.ipp.data.Item;
import com.intuit.ipp.data.ItemTypeEnum;
import com.intuit.ipp.data.Line;
import com.intuit.ipp.data.LineDetailTypeEnum;
import com.intuit.ipp.data.ReferenceType;
import com.intuit.ipp.data.SalesItemLineDetail;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang3.RandomStringUtils;
import org.apache.log4j.Logger;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import com.intuit.developer.tutorials.client.OAuth2PlatformClientFactory;
import com.intuit.developer.tutorials.helper.QBOServiceHelper;
import com.intuit.ipp.data.Error;
import com.intuit.ipp.exception.FMSException;
import com.intuit.ipp.exception.InvalidTokenException;
import com.intuit.ipp.services.DataService;

/**
 * @author bcole
 *
 */
@Controller
public class InventoryController {
	
	@Autowired
	OAuth2PlatformClientFactory factory;
	
	@Autowired
    public QBOServiceHelper helper;
	
	private static final Logger logger = Logger.getLogger(InventoryController.class);
	
	
	/**
     * Sample QBO API call using OAuth2 tokens
     * 
     * @param session
     * @return
     */
	@ResponseBody
    @RequestMapping("/inventory")
    public String callInventoryConcept(HttpSession session) {

    	String realmId = (String)session.getAttribute("realmId");
    	if (StringUtils.isEmpty(realmId)) {
    		return new JSONObject().put("response", "No realm ID.  QBO calls only work if the accounting scope was passed!").toString();
    	}
    	String accessToken = (String)session.getAttribute("access_token");
    	
        try {
        	
        	// Get DataService
    		DataService service = helper.getDataService(realmId, accessToken);
			
    		// Add inventory item - with initial Quantity on Hand of 10
			Item item = getItemWithAllFields(service);
			Item savedItem = service.add(item);
    		
    		// Create invoice (for 1 item) using the item created above
			Customer customer = getCustomerWithAllFields();
			Customer savedCustomer = service.add(customer);
			Invoice invoice = getInvoiceFields(savedCustomer, savedItem);
			Invoice savedInvoice = service.add(invoice);
    		
    		// Query inventory item - there should be 9 items now!
			Item itemsRemaining = service.findById(savedItem);

			// Return response back - take a look at "qtyOnHand" in the output (should be 9)
    		return processResponse(itemsRemaining);

		} catch (InvalidTokenException e) {
			return new JSONObject().put("response", "InvalidToken - Refresh token and try again").toString();
		} catch (FMSException e) {
			List<Error> list = e.getErrorList();
			list.forEach(error -> logger.error("Error while calling the API :: " + error.getMessage()));
			return new JSONObject().put("response","Failed").toString();
		}
    }


	private static Item getItemWithAllFields(DataService service) throws FMSException {
		Item item = new Item();
		item.setType(ItemTypeEnum.INVENTORY);
		item.setName("Inventory Item " + RandomStringUtils.randomAlphanumeric(5));
		item.setInvStartDate(new Date());

		// Start with 10 items
		item.setQtyOnHand(BigDecimal.valueOf(10));
		item.setTrackQtyOnHand(true);

		Account incomeBankAccount = getIncomeBankAccount(service);
		item.setIncomeAccountRef(getAccountRef(incomeBankAccount));

		Account expenseBankAccount = getExpenseBankAccount(service);
		item.setExpenseAccountRef(getAccountRef(expenseBankAccount));

		Account assetAccount = getAssetAccount(service);
		item.setAssetAccountRef(getAccountRef(assetAccount));

		return item;
	}

	private static Customer getCustomerWithAllFields() {
		Customer customer = new Customer();
		customer.setDisplayName(org.apache.commons.lang.RandomStringUtils.randomAlphanumeric(6));
		customer.setCompanyName("ABC Corporations");

		EmailAddress emailAddr = new EmailAddress();
		emailAddr.setAddress("testconceptsample@mailinator.com");
		customer.setPrimaryEmailAddr(emailAddr);

		return customer;
	}

	private static Invoice getInvoiceFields(Customer customer, Item item) {
		Invoice invoice = new Invoice();

		ReferenceType customerRef = new ReferenceType();
		customerRef.setValue(customer.getId());
		invoice.setCustomerRef(customerRef);

		List<Line> invLine = new ArrayList<Line>();
		Line line = new Line();
		line.setAmount(new BigDecimal("100"));
		line.setDetailType(LineDetailTypeEnum.SALES_ITEM_LINE_DETAIL);

		SalesItemLineDetail silDetails = new SalesItemLineDetail();
		silDetails.setQty(BigDecimal.valueOf(1));

		ReferenceType itemRef = new ReferenceType();
		itemRef.setValue(item.getId());
		silDetails.setItemRef(itemRef);

		line.setSalesItemLineDetail(silDetails);
		invLine.add(line);
		invoice.setLine(invLine);

		return invoice;
	}

	public static ReferenceType getAccountRef(Account account) {
		ReferenceType accountRef = new ReferenceType();
		accountRef.setName(account.getName());
		accountRef.setValue(account.getId());
		return accountRef;
	}

	public static Account getIncomeBankAccount(DataService service) throws FMSException {
		List<Account> accounts = (List<Account>) service.findAll(new Account());
		if (!accounts.isEmpty()) {
			Iterator<Account> itr = accounts.iterator();
			while (itr.hasNext()) {
				Account account = itr.next();
				if (account.getAccountType().equals(AccountTypeEnum.INCOME) &&
						account.getAccountSubType().equals(AccountSubTypeEnum.SALES_OF_PRODUCT_INCOME.value())) {
					return account;
				}
			}
		}
		return createIncomeBankAccount(service);
	}

	private static Account createIncomeBankAccount(DataService service) throws FMSException {
		return service.add(getIncomeBankAccountFields());
	}

	public static Account getIncomeBankAccountFields() throws FMSException {
		Account account = new Account();
		account.setName("Income " + RandomStringUtils.randomAlphabetic(5));
		account.setSubAccount(false);
		account.setFullyQualifiedName(account.getName());
		account.setActive(true);
		account.setClassification(AccountClassificationEnum.REVENUE);
		account.setAccountType(AccountTypeEnum.INCOME);
		account.setAccountSubType(AccountSubTypeEnum.SALES_OF_PRODUCT_INCOME.value());
		account.setCurrentBalance(new BigDecimal("0"));
		account.setCurrentBalanceWithSubAccounts(new BigDecimal("0"));
		ReferenceType currencyRef = new ReferenceType();
		currencyRef.setName("United States Dollar");
		currencyRef.setValue("USD");
		account.setCurrencyRef(currencyRef);

		return account;
	}

	public static Account getExpenseBankAccount(DataService service) throws FMSException {
		List<Account> accounts = (List<Account>) service.findAll(new Account());
		if (!accounts.isEmpty()) {
			Iterator<Account> itr = accounts.iterator();
			while (itr.hasNext()) {
				Account account = itr.next();
				if (account.getAccountType().equals(AccountTypeEnum.COST_OF_GOODS_SOLD) &&
						account.getAccountSubType().equals(AccountSubTypeEnum.SUPPLIES_MATERIALS_COGS.value())) {
					return account;
				}
			}
		}
		return createExpenseBankAccount(service);
	}

	private static Account createExpenseBankAccount(DataService service) throws FMSException {
		return service.add(getExpenseBankAccountFields());
	}

	public static Account getExpenseBankAccountFields() throws FMSException {
		Account account = new Account();
		account.setName("Expense" + RandomStringUtils.randomAlphabetic(5));
		account.setSubAccount(false);
		account.setFullyQualifiedName(account.getName());
		account.setActive(true);
		account.setClassification(AccountClassificationEnum.EXPENSE);
		account.setAccountType(AccountTypeEnum.COST_OF_GOODS_SOLD);
		account.setAccountSubType(AccountSubTypeEnum.SUPPLIES_MATERIALS_COGS.value());
		account.setCurrentBalance(new BigDecimal("0"));
		account.setCurrentBalanceWithSubAccounts(new BigDecimal("0"));
		ReferenceType currencyRef = new ReferenceType();
		currencyRef.setName("United States Dollar");
		currencyRef.setValue("USD");
		account.setCurrencyRef(currencyRef);

		return account;
	}

	public static Account getAssetAccount(DataService service)  throws FMSException{
		List<Account> accounts = (List<Account>) service.findAll(new Account());
		if (!accounts.isEmpty()) {
			Iterator<Account> itr = accounts.iterator();
			while (itr.hasNext()) {
				Account account = itr.next();
				if (account.getAccountType().equals(AccountTypeEnum.OTHER_CURRENT_ASSET) &&
						account.getAccountSubType().equals(AccountSubTypeEnum.INVENTORY.value())) {
					return account;
				}
			}
		}
		return createOtherCurrentAssetAccount(service);
	}

	private static Account createOtherCurrentAssetAccount(DataService service) throws FMSException {
		return service.add(getOtherCurrentAssetAccountFields());
	}

	public static Account getOtherCurrentAssetAccountFields() throws FMSException {
		Account account = new Account();
		account.setName("Other Current Asset " + RandomStringUtils.randomAlphanumeric(5));
		account.setSubAccount(false);
		account.setFullyQualifiedName(account.getName());
		account.setActive(true);
		account.setClassification(AccountClassificationEnum.ASSET);
		account.setAccountType(AccountTypeEnum.OTHER_CURRENT_ASSET);
		account.setAccountSubType(AccountSubTypeEnum.INVENTORY.value());
		account.setCurrentBalance(new BigDecimal("0"));
		account.setCurrentBalanceWithSubAccounts(new BigDecimal("0"));
		ReferenceType currencyRef = new ReferenceType();
		currencyRef.setName("United States Dollar");
		currencyRef.setValue("USD");
		account.setCurrencyRef(currencyRef);

		return account;
	}

	private String processResponse(Object entity) {
		ObjectMapper mapper = new ObjectMapper();
		try {
			String jsonInString = mapper.writeValueAsString(entity);
			return jsonInString;
		} catch (JsonProcessingException e) {
			logger.error("Exception while managing inventory ", e);
			return new JSONObject().put("response","Failed").toString();
		}
	}

}
