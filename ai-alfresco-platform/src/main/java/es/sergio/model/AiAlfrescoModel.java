package es.sergio.model;

import org.alfresco.service.namespace.QName;

public class AiAlfrescoModel {
    public static final String AI_ALFRESCO_MODEL_1_0_URI = "http://www.ai-alfresco.org/model/content/1.0";

    // Ticket Metadata Model
    public static final QName METADATA_TICKET_COUNTRY = QName.createQName(AI_ALFRESCO_MODEL_1_0_URI, "ticketCountry");
    public static final QName METADATA_TICKET_DATE = QName.createQName(AI_ALFRESCO_MODEL_1_0_URI, "ticketDate");
    public static final QName METADATA_TICKET_TAX = QName.createQName(AI_ALFRESCO_MODEL_1_0_URI, "ticketTax");
    public static final QName METADATA_TICKET_TOTAL = QName.createQName(AI_ALFRESCO_MODEL_1_0_URI, "ticketTotal");

    // Invoice Metadata Model
    public static final QName METADATA_INVOICE_DATE = QName.createQName(AI_ALFRESCO_MODEL_1_0_URI, "invoiceDate");
    public static final QName METADATA_INVOICE_TAX = QName.createQName(AI_ALFRESCO_MODEL_1_0_URI, "invoiceTax");
    public static final QName METADATA_INVOICE_TOTAL = QName.createQName(AI_ALFRESCO_MODEL_1_0_URI, "invoiceTotal");
    public static final QName METADATA_INVOICE_NUMBER = QName.createQName(AI_ALFRESCO_MODEL_1_0_URI, "invoiceNumber");
}
