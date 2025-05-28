package es.sergio.main;

import es.sergio.aws.Textract;
import es.sergio.model.AiAlfrescoModel;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.Serializable;
import java.util.List;
import java.util.Map;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

import org.alfresco.error.AlfrescoRuntimeException;
import org.alfresco.model.ContentModel;
import org.alfresco.repo.action.ParameterDefinitionImpl;
import org.alfresco.repo.action.executer.ActionExecuterAbstractBase;
import org.alfresco.service.ServiceRegistry;
import org.alfresco.service.cmr.action.Action;
import org.alfresco.service.cmr.action.ParameterDefinition;
import org.alfresco.service.cmr.dictionary.DataTypeDefinition;
import org.alfresco.service.cmr.repository.ContentReader;
import org.alfresco.service.cmr.repository.NodeRef;
import org.alfresco.service.namespace.QName;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import software.amazon.awssdk.services.textract.model.AnalyzeExpenseResponse;
import software.amazon.awssdk.services.textract.model.ExpenseDocument;
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
        if (serviceRegistry.getNodeService().exists(actionedUponNodeRef) == true) {
            String typeDoc = (String) action.getParameterValue(PARAM_TYPE_DOCUMENT);
            logger.info("Processing document type: " + typeDoc);

            Serializable filename = serviceRegistry.getNodeService().getProperty(
                    actionedUponNodeRef, ContentModel.PROP_NAME);
            if (filename == null) {
                throw new AlfrescoRuntimeException("Document filename is null");
            }

            String documentName = (String) filename;
            byte[] documentData = getDocumentContentBytes(actionedUponNodeRef, documentName);

            try {
                AnalyzeExpenseResponse response = this.textractService.extractMetadata(documentData);
                logger.debug(response.toString());

                Map<QName, Serializable> properties = new java.util.HashMap<>();

                for (ExpenseDocument doc : response.expenseDocuments()) {
                    for (ExpenseField field : doc.summaryFields()) {
                        String fieldType = field.type().text();
                        String value = field.valueDetection().text();

                        if ("ticket".equalsIgnoreCase(typeDoc)) {
                            if ("COUNTRY".equalsIgnoreCase(fieldType)) {
                                properties.put(AiAlfrescoModel.METADATA_TICKET_COUNTRY, value);
                            }
                            if ("INVOICE_RECEIPT_DATE".equalsIgnoreCase(fieldType)) {
                                // Ajusta el formato de fecha si es necesario
                                try {
                                    DateTimeFormatter formatoOriginal = DateTimeFormatter.ofPattern("dd/MM/yyyy");
                                    LocalDate fecha = LocalDate.parse(value, formatoOriginal);
                                    properties.put(AiAlfrescoModel.METADATA_TICKET_DATE, fecha.toString());
                                } catch (Exception e) {
                                    logger.warn("No se pudo parsear la fecha: " + value);
                                }
                            }
                            if ("TAX".equalsIgnoreCase(fieldType)) {
                                properties.put(AiAlfrescoModel.METADATA_TICKET_TAX, value);
                            }
                            if ("TOTAL".equalsIgnoreCase(fieldType)) {
                                properties.put(AiAlfrescoModel.METADATA_TICKET_TOTAL, value);
                            }
                        } else if ("factura".equalsIgnoreCase(typeDoc)) {
                            if ("INVOICE_RECEIPT_ID".equalsIgnoreCase(fieldType)) {
                                properties.put(AiAlfrescoModel.METADATA_INVOICE_NUMBER, value);
                            }
                            if ("INVOICE_RECEIPT_DATE".equalsIgnoreCase(fieldType)) {
                                try {
                                    DateTimeFormatter formatoOriginal = DateTimeFormatter.ofPattern("dd-MM-yyyy");
                                    LocalDate fecha = LocalDate.parse(value, formatoOriginal);
                                    properties.put(AiAlfrescoModel.METADATA_INVOICE_DATE, fecha.toString());
                                } catch (Exception e) {
                                    logger.warn("No se pudo parsear la fecha: " + value);
                                }
                            }
                            if ("TAX".equalsIgnoreCase(fieldType)) {
                                if (!properties.containsKey(AiAlfrescoModel.METADATA_INVOICE_TAX)) {
                                    properties.put(AiAlfrescoModel.METADATA_INVOICE_TAX, value);
                                }
                            }
                            if ("TOTAL".equalsIgnoreCase(fieldType)) {
                                properties.put(AiAlfrescoModel.METADATA_INVOICE_TOTAL, value);
                            }
                        }
                    }
                }
                if (!properties.isEmpty()) {
                    this.serviceRegistry.getNodeService().addProperties(actionedUponNodeRef, properties);
                }

            } catch (Exception e) {
                logger.error("Error extracting metadata from document: " + e.getMessage() +
                        " [filename=" + documentName + "][docNodeRef=" + actionedUponNodeRef + "]");
                e.printStackTrace();
            }

        }
    }

    @Override
    protected void addParameterDefinitions(List<ParameterDefinition> paramList) {
        for (String s : new String[] { PARAM_TYPE_DOCUMENT }) {
            paramList.add(new ParameterDefinitionImpl(s, DataTypeDefinition.TEXT, true, getParamDisplayLabel(s)));
        }
    }

    /**
     * Get the content bytes for the document with passed in node reference.
     *
     * @param documentRef      the node reference for the document we want the
     *                         content bytes for
     * @param documentFilename document filename for logging
     * @return a byte array containing the document content or null if not found
     */
    private byte[] getDocumentContentBytes(NodeRef documentRef, String documentFilename) {
        // Get a content reader
        ContentReader contentReader = serviceRegistry.getContentService().getReader(
                documentRef, ContentModel.PROP_CONTENT);
        if (contentReader == null) {
            logger.error("Content reader was null [filename=" + documentFilename + "][docNodeRef=" + documentRef + "]");

            return null;
        }

        // Get the document content bytes
        InputStream is = contentReader.getContentInputStream();
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] documentData = null;

        try {
            byte[] buf = new byte[1024];
            int len = 0;
            while ((len = is.read(buf)) > 0) {
                bos.write(buf, 0, len);
            }
            documentData = bos.toByteArray();
        } catch (IOException ioe) {
            logger.error("Content could not be read: " + ioe.getMessage() +
                    " [filename=" + documentFilename + "][docNodeRef=" + documentRef + "]");
            return null;
        } finally {
            if (is != null) {
                try {
                    is.close();
                } catch (Throwable e) {
                    logger.error("Could not close doc content input stream: " + e.getMessage() +
                            " [filename=" + documentFilename + "][docNodeRef=" + documentRef + "]");
                }
            }
        }

        return documentData;
    }

}
