package es.sergio.utils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.Serializable;
import java.util.Map;

import org.alfresco.error.AlfrescoRuntimeException;
import org.alfresco.model.ContentModel;
import org.alfresco.service.ServiceRegistry;
import org.alfresco.service.cmr.model.FileExistsException;
import org.alfresco.service.cmr.model.FileInfo;
import org.alfresco.service.cmr.repository.ContentReader;
import org.alfresco.service.cmr.repository.DuplicateChildNodeNameException;
import org.alfresco.service.cmr.repository.NodeRef;
import org.alfresco.service.namespace.NamespaceService;
import org.alfresco.service.namespace.QName;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

public class Utils {
    private static final Log logger = LogFactory.getLog(Utils.class);

    private String documentName;
    private String mimeType;
    private FileInfo parentNodeRef;
    private byte[] documentData;
    private final ServiceRegistry serviceRegistry;

    public enum ActionType {
        RECORDING, TRANSLATE, EXTRACT
    }

    public Utils(ServiceRegistry serviceRegistry) {
        this.serviceRegistry = serviceRegistry;
    }

    public String getDocumentName() {
        return documentName;
    }

    public String getMimeType() {
        return mimeType;
    }

    public FileInfo getParentNodeRef() {
        return parentNodeRef;
    }

    public byte[] getDocumentData() {
        return documentData;
    }

    public void getNodeInfo(NodeRef actionedUponNodeRef, ActionType action) {
        // Get document filename
        Serializable filename = serviceRegistry.getNodeService().getProperty(
                actionedUponNodeRef, ContentModel.PROP_NAME);
        if (filename == null) {
            throw new AlfrescoRuntimeException("Document filename is null");
        }

        this.documentName = action == ActionType.RECORDING ? FilenameUtils.removeExtension((String) filename)
                : (String) filename;
        if (action == ActionType.TRANSLATE || action == ActionType.RECORDING) {
            String prefix = action == ActionType.TRANSLATE ? "translated_" : "recording_";
            this.documentName = prefix + this.documentName;

            if (action == ActionType.RECORDING) {
                this.documentName = this.documentName.concat(".mp3");
            }

            NodeRef parentRef = serviceRegistry.getNodeService().getPrimaryParent(actionedUponNodeRef)
                    .getParentRef();

            try {
                this.parentNodeRef = this.serviceRegistry.getFileFolderService().create(parentRef, this.documentName,
                        ContentModel.PROP_CONTENT);
            } catch (FileExistsException e) {
                logger.error("Error al subir el documento. El documento ya existe.");
                throw new DuplicateChildNodeNameException(parentRef, ContentModel.PROP_CONTENT, this.documentName,
                        e);
            }
        }
        ContentReader reader = serviceRegistry.getContentService()
                .getReader(actionedUponNodeRef, ContentModel.PROP_CONTENT);
        this.mimeType = reader.getMimetype();
        this.documentData = getDocumentContentBytes(reader, (String) filename);
    }

    /**
     * Get the content bytes for the document with passed in node reference.
     *
     * @param documentRef      the node reference for the document we want the
     *                         content bytes for
     * @param documentFilename document filename for logging
     * @return a byte array containing the document content or null if not found
     */
    private byte[] getDocumentContentBytes(ContentReader contentReader, String filename) {
        if (contentReader == null) {
            logger.error("Content reader was null [filename=" + filename + "]");
            return new byte[0];
        }

        try (InputStream is = contentReader.getContentInputStream();
                ByteArrayOutputStream bos = new ByteArrayOutputStream()) {

            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = is.read(buffer)) != -1) {
                bos.write(buffer, 0, bytesRead);
            }
            return bos.toByteArray();

        } catch (IOException e) {
            logger.error("Failed to read content [filename=" + filename + "]: " + e.getMessage());
            return new byte[0];
        }
    }

    public void copyAspects(NodeRef sourceNodeRef) {
        Map<QName, Serializable> allProps = serviceRegistry.getNodeService().getProperties(sourceNodeRef);

        serviceRegistry.getNodeService().getAspects(sourceNodeRef).stream()
                .filter(aspect -> !aspect.getNamespaceURI().contains(NamespaceService.ALFRESCO_URI))
                .forEach(aspect -> {
                    Map<QName, Serializable> aspectProps = allProps.entrySet().stream()
                            .filter(entry -> entry.getKey().getNamespaceURI().equals(aspect.getNamespaceURI()))
                            .collect(java.util.stream.Collectors.toMap(
                                    Map.Entry::getKey,
                                    Map.Entry::getValue));

                    logger.debug("Adding aspect: " + aspect + " with properties: " + aspectProps);

                    try {
                        serviceRegistry.getNodeService().addAspect(
                                parentNodeRef.getNodeRef(), aspect, aspectProps);
                    } catch (Exception e) {
                        logger.error("Failed to add aspect " + aspect + " to node " + parentNodeRef.getNodeRef());
                        throw new AlfrescoRuntimeException(
                                "Failed to add aspect " + aspect + " to node " + parentNodeRef.getNodeRef(), e);
                    }
                });
    }

}
