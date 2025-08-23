package es.sergio.main;

import es.sergio.aws.Textract;
import es.sergio.model.AiAlfrescoModel;
import es.sergio.utils.Utils;
import es.sergio.utils.Utils.ActionType;

import java.io.Serializable;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

import org.alfresco.repo.action.ParameterDefinitionImpl;
import org.alfresco.repo.action.executer.ActionExecuterAbstractBase;
import org.alfresco.service.ServiceRegistry;
import org.alfresco.service.cmr.action.Action;
import org.alfresco.service.cmr.action.ParameterDefinition;
import org.alfresco.service.cmr.dictionary.DataTypeDefinition;
import org.alfresco.service.cmr.repository.NodeRef;
import org.alfresco.service.namespace.QName;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import software.amazon.awssdk.services.textract.model.AnalyzeExpenseResponse;
import software.amazon.awssdk.services.textract.model.ExpenseField;

public class MetadataExtractService extends ActionExecuterAbstractBase {
    private static final Log logger = LogFactory.getLog(MetadataExtractService.class);
    private ServiceRegistry serviceRegistry;
    private Textract textractService;

    public static final String PARAM_TYPE_DOCUMENT = "type_document";

    public void setServiceRegistry(ServiceRegistry serviceRegistry) {
        this.serviceRegistry = serviceRegistry;
    }

    public void setTextractService(Textract textractService) {
        this.textractService = textractService;
    }

    @Override
    protected void executeImpl(Action action, NodeRef actionedUponNodeRef) {
        if (!serviceRegistry.getNodeService().exists(actionedUponNodeRef)) {
            return;
        }

        Utils utils = new Utils(serviceRegistry);
        String typeDoc = (String) action.getParameterValue(PARAM_TYPE_DOCUMENT);
        logger.info("Processing document type: " + typeDoc);

        utils.getNodeInfo(actionedUponNodeRef, ActionType.EXTRACT);

        try {
            AnalyzeExpenseResponse response = textractService.extractMetadata(utils.getDocumentData());
            logger.debug(response.toString());

            Map<QName, Serializable> properties = extractProperties(response, typeDoc);

            if (!properties.isEmpty()) {
                serviceRegistry.getNodeService().addProperties(actionedUponNodeRef, properties);
            }

        } catch (Exception e) {
            logger.error("Error extracting metadata: " + e.getMessage() +
                    " [filename=" + utils.getDocumentName() + "][node=" + actionedUponNodeRef + "]");
        }
    }

    private Map<QName, Serializable> extractProperties(AnalyzeExpenseResponse response, String typeDoc) {
        Map<QName, Serializable> properties = new HashMap<>();
        List<ExpenseField> allFields = new ArrayList<>();

        response.expenseDocuments()
                .forEach(doc -> allFields.addAll(doc.summaryFields()));

        // Pool de hilos con número de núcleos disponibles
        ExecutorService executor = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
        List<Future<Void>> futures = new ArrayList<>();

        for (ExpenseField field : allFields) {
            futures.add(executor.submit(() -> {
                processField(field, typeDoc, properties);
                return null;
            }));
        }

        // Esperar a que terminen todos los hilos
        for (Future<Void> f : futures) {
            try {
                f.get();
            } catch (Exception e) {
                logger.error("Error procesando campo con hilos", e);
            }
        }

        executor.shutdown();
        return properties;
    }

    private void processField(ExpenseField field, String typeDoc, Map<QName, Serializable> properties) {
        String fieldType = field.type().text();
        String value = field.valueDetection().text();

        if ("ticket".equalsIgnoreCase(typeDoc)) {
            processTicketField(fieldType, value, properties);
        } else if ("factura".equalsIgnoreCase(typeDoc)) {
            processInvoiceField(fieldType, value, properties);
        }
    }

    private void processTicketField(String fieldType, String value, Map<QName, Serializable> properties) {
        switch (fieldType.toUpperCase()) {
            case "COUNTRY":
                properties.put(AiAlfrescoModel.METADATA_TICKET_COUNTRY, value);
                break;
            case "INVOICE_RECEIPT_DATE":
                parseDate(value, "dd/MM/yyyy")
                        .ifPresent(date -> properties.put(AiAlfrescoModel.METADATA_TICKET_DATE, date));
                break;
            case "TAX":
                properties.put(AiAlfrescoModel.METADATA_TICKET_TAX, value);
                break;
            case "TOTAL":
                properties.put(AiAlfrescoModel.METADATA_TICKET_TOTAL, value);
                break;
        }
    }

    private void processInvoiceField(String fieldType, String value, Map<QName, Serializable> properties) {
        switch (fieldType.toUpperCase()) {
            case "INVOICE_RECEIPT_ID":
                properties.put(AiAlfrescoModel.METADATA_INVOICE_NUMBER, value);
                break;
            case "INVOICE_RECEIPT_DATE":
                parseDate(value, "dd-MM-yyyy")
                        .ifPresent(date -> properties.put(AiAlfrescoModel.METADATA_INVOICE_DATE, date));
                break;
            case "TAX":
                properties.putIfAbsent(AiAlfrescoModel.METADATA_INVOICE_TAX, value);
                break;
            case "TOTAL":
                properties.put(AiAlfrescoModel.METADATA_INVOICE_TOTAL, value);
                break;
        }
    }

    private Optional<String> parseDate(String value, String pattern) {
        try {
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern(pattern);
            LocalDate date = LocalDate.parse(value, formatter);
            return java.util.Optional.of(date.toString());
        } catch (Exception e) {
            logger.warn("Failed to parse date: " + value);
            return java.util.Optional.empty();
        }
    }

    @Override
    protected void addParameterDefinitions(List<ParameterDefinition> paramList) {
        for (String s : new String[] { PARAM_TYPE_DOCUMENT }) {
            paramList.add(new ParameterDefinitionImpl(s, DataTypeDefinition.TEXT, true, getParamDisplayLabel(s)));
        }
    }
}
