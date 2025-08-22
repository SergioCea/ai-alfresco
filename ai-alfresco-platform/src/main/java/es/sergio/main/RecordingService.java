package es.sergio.main;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.Serializable;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.awt.image.BufferedImage;

import org.alfresco.error.AlfrescoRuntimeException;
import org.alfresco.model.ContentModel;
import org.alfresco.repo.action.ParameterDefinitionImpl;
import org.alfresco.repo.action.executer.ActionExecuterAbstractBase;
import org.alfresco.service.ServiceRegistry;
import org.alfresco.service.cmr.action.Action;
import org.alfresco.service.cmr.action.ParameterDefinition;
import org.alfresco.service.cmr.dictionary.DataTypeDefinition;
import org.alfresco.service.cmr.model.FileExistsException;
import org.alfresco.service.cmr.model.FileInfo;
import org.alfresco.service.cmr.repository.ContentReader;
import org.alfresco.service.cmr.repository.ContentWriter;
import org.alfresco.service.cmr.repository.DuplicateChildNodeNameException;
import org.alfresco.service.cmr.repository.NodeRef;
import org.alfresco.service.namespace.NamespaceService;
import org.alfresco.service.namespace.QName;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.tools.imageio.ImageIOUtil;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;

import es.sergio.aws.Polly;
import es.sergio.aws.Textract;
import software.amazon.awssdk.services.polly.model.OutputFormat;
import software.amazon.awssdk.services.textract.model.Block;
import software.amazon.awssdk.services.textract.model.BlockType;
import software.amazon.awssdk.services.textract.model.DetectDocumentTextResponse;

public class RecordingService extends ActionExecuterAbstractBase {
    private static final Log logger = LogFactory.getLog(RecordingService.class);
    private ServiceRegistry serviceRegistry;
    private Textract textractService;
    private Polly pollyService;

    public void setServiceRegistry(ServiceRegistry serviceRegistry) {
        this.serviceRegistry = serviceRegistry;
    }

    public void setPollyService(Polly pollyService) {
        this.pollyService = pollyService;
    }

    public void setTextractService(Textract textractService) {
        this.textractService = textractService;
    }

    @Override
    protected void executeImpl(Action action, NodeRef actionedUponNodeRef) {
        if (serviceRegistry.getNodeService().exists(actionedUponNodeRef)) {

            // Get document filename
            Serializable filename = serviceRegistry.getNodeService().getProperty(
                    actionedUponNodeRef, ContentModel.PROP_NAME);
            if (filename == null) {
                throw new AlfrescoRuntimeException("Document filename is null");
            }

            String documentName = (String) filename;
            String nameWithoutExt = FilenameUtils.removeExtension(documentName);
            String recordingDocumentname = "recording_" + nameWithoutExt + ".mp3";
            NodeRef parentRef = this.serviceRegistry.getNodeService().getPrimaryParent(actionedUponNodeRef)
                    .getParentRef();
            String mimeType = this.serviceRegistry.getContentService()
                    .getReader(actionedUponNodeRef, ContentModel.PROP_CONTENT).getMimetype();

            logger.info("Recording: " + documentName);

            byte[] documentData = getDocumentContentBytes(actionedUponNodeRef, documentName);

            // Get the parent folder
            FileInfo uploadNodeRef = null;
            try {
                uploadNodeRef = this.serviceRegistry.getFileFolderService().create(parentRef, recordingDocumentname,
                        ContentModel.PROP_CONTENT);

                // Copiar aspectos y sus propiedades del documento original al traducido
                List<QName> originalAspects = new ArrayList<>(
                        serviceRegistry.getNodeService().getAspects(actionedUponNodeRef));
                for (QName aspect : originalAspects) {
                    if (!aspect.getNamespaceURI().contains(NamespaceService.ALFRESCO_URI)) {
                        // Obtener las propiedades del aspecto en el nodo original
                        Map<QName, Serializable> aspectProps = serviceRegistry.getNodeService()
                                .getProperties(actionedUponNodeRef);
                        Map<QName, Serializable> filteredProps = new java.util.HashMap<>();
                        for (Map.Entry<QName, Serializable> entry : aspectProps.entrySet()) {
                            // Solo copiar las propiedades que pertenecen a este aspecto
                            if (entry.getKey().getNamespaceURI().equals(aspect.getNamespaceURI())) {
                                filteredProps.put(entry.getKey(), entry.getValue());
                            }
                        }
                        logger.debug("Adding aspect: " + aspect + " with properties: " + filteredProps);
                        try {
                            serviceRegistry.getNodeService().addAspect(uploadNodeRef.getNodeRef(), aspect,
                                    filteredProps);
                        } catch (Exception e) {
                            logger.error("Error al agregar el aspecto: " + aspect + " al nodo: "
                                    + uploadNodeRef.getNodeRef());
                            throw new AlfrescoRuntimeException("Error al agregar el aspecto: " + aspect + " al nodo: "
                                    + uploadNodeRef.getNodeRef(), e);
                        }
                    }
                }
            } catch (FileExistsException e) {
                logger.error("Error al subir el documento. El documento ya existe.");
                throw new DuplicateChildNodeNameException(parentRef, ContentModel.PROP_CONTENT, recordingDocumentname,
                        e);
            }

            try {
                if (mimeType.equalsIgnoreCase("text/plain")) {
                    String text = new String(documentData, StandardCharsets.UTF_8);

                    InputStream recordingContent = this.pollyService.synthesize(text, OutputFormat.MP3);

                    // Update content from uploadnoderef
                    ContentWriter writer = this.serviceRegistry.getContentService()
                            .getWriter(uploadNodeRef.getNodeRef(), ContentModel.PROP_CONTENT, true);
                    writer.setMimetype("audio/mpeg");
                    writer.putContent(recordingContent);
                } else if (mimeType.equalsIgnoreCase("application/pdf")) {
                    logger.info("Generating searchable pdf from: " + documentName);

                    StringBuilder fullText = new StringBuilder();

                    PDDocument inputDocument = PDDocument.load(new ByteArrayInputStream(documentData));
                    PDFRenderer pdfRenderer = new PDFRenderer(inputDocument);

                    for (int page = 0; page < inputDocument.getNumberOfPages(); ++page) {
                        int pageNumber = page + 1;
                        logger.info("Processing page " + pageNumber);

                        // Render image
                        BufferedImage image = pdfRenderer.renderImage(page, 1,
                                org.apache.pdfbox.rendering.ImageType.RGB);

                        // Get image bytes
                        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
                        ImageIOUtil.writeImage(image, "jpeg", byteArrayOutputStream);
                        byteArrayOutputStream.flush();
                        ByteBuffer imageBytes = ByteBuffer.wrap(byteArrayOutputStream.toByteArray());

                        // Extract text from page
                        String pageText = this.extractTextFromImage(imageBytes);
                        fullText.append(pageText).append("\n");

                        logger.info("Processed page " + pageNumber);
                    }

                    inputDocument.close();
                    logger.info("Sending full text to Polly...");
                    InputStream recordingContent = synthesizeLargeText(fullText.toString());

                    // Update content from uploadnoderef
                    ContentWriter writer = this.serviceRegistry.getContentService()
                            .getWriter(uploadNodeRef.getNodeRef(), ContentModel.PROP_CONTENT, true);
                    writer.setMimetype("audio/mpeg");
                    writer.putContent(recordingContent);
                } else if (mimeType
                        .equalsIgnoreCase("application/vnd.openxmlformats-officedocument.wordprocessingml.document") ||
                        mimeType.equalsIgnoreCase("application/msword")) {
                    try (InputStream is = new ByteArrayInputStream(documentData);
                            XWPFDocument doc = new XWPFDocument(is)) {

                        StringBuilder text = new StringBuilder();
                        for (XWPFParagraph p : doc.getParagraphs()) {
                            text.append(p.getText()).append("\n");
                        }

                        InputStream recordingContent = this.pollyService.synthesize(text.toString(), OutputFormat.MP3);

                        ContentWriter writer = this.serviceRegistry.getContentService()
                                .getWriter(uploadNodeRef.getNodeRef(), ContentModel.PROP_CONTENT, true);
                        writer.setMimetype("audio/mpeg");
                        writer.putContent(recordingContent);
                    }
                }
            } catch (Exception e) {
                // TODO: handle exception
                logger.error(e);
            }
        }
    }

    @Override
    protected void addParameterDefinitions(List<ParameterDefinition> paramList) {

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
        ContentReader contentReader = this.serviceRegistry.getContentService().getReader(
                documentRef, ContentModel.PROP_CONTENT);
        if (contentReader == null) {
            logger.error("Content reader was null [filename=" + documentFilename + "][docNodeRef=" + documentRef + "]");

            return new byte[0];
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
            return new byte[0];
        } finally {
            if (is != null) {
                try {
                    is.close();
                } catch (Exception e) {
                    logger.error("Could not close doc content input stream: " + e.getMessage() +
                            " [filename=" + documentFilename + "][docNodeRef=" + documentRef + "]");
                }
            }
        }

        return documentData;
    }

    private String extractTextFromImage(ByteBuffer buffer) throws IOException {
        DetectDocumentTextResponse textResponse = this.textractService.detectDocumentText(buffer);

        StringBuilder extractedText = new StringBuilder();
        for (Block block : textResponse.blocks()) {
            if (block.blockType().equals(BlockType.LINE)) {
                extractedText.append(block.text()).append("\n");
            }
        }
        return extractedText.toString();
    }

    private InputStream synthesizeLargeText(String text) throws IOException {
        int maxLength = 3000;
        List<InputStream> audioChunks = new ArrayList<>();

        for (int start = 0; start < text.length(); start += maxLength) {
            int end = Math.min(text.length(), start + maxLength);
            String chunk = text.substring(start, end);

            InputStream audio = this.pollyService.synthesize(chunk, OutputFormat.MP3);
            audioChunks.add(audio);
        }

        // Concatenar todos los MP3 en memoria
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];

        for (InputStream chunk : audioChunks) {
            int bytesRead;
            while ((bytesRead = chunk.read(buffer)) != -1) {
                outputStream.write(buffer, 0, bytesRead);
            }
            chunk.close();
        }

        return new ByteArrayInputStream(outputStream.toByteArray());
    }

}
