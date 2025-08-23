package es.sergio.main;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.awt.image.BufferedImage;

import org.alfresco.model.ContentModel;
import org.alfresco.repo.action.executer.ActionExecuterAbstractBase;
import org.alfresco.service.ServiceRegistry;
import org.alfresco.service.cmr.action.Action;
import org.alfresco.service.cmr.action.ParameterDefinition;
import org.alfresco.service.cmr.repository.ContentWriter;
import org.alfresco.service.cmr.repository.NodeRef;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.tools.imageio.ImageIOUtil;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;

import es.sergio.aws.Polly;
import es.sergio.aws.Textract;
import es.sergio.utils.Utils;
import es.sergio.utils.Utils.ActionType;
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
    protected void addParameterDefinitions(List<ParameterDefinition> paramList) {
    }

    @Override
    protected void executeImpl(Action action, NodeRef actionedUponNodeRef) {
        if (!serviceRegistry.getNodeService().exists(actionedUponNodeRef)) {
            return;
        }

        Utils utils = new Utils(serviceRegistry);
        utils.getNodeInfo(actionedUponNodeRef, ActionType.RECORDING);
        utils.copyAspects(actionedUponNodeRef);

        try {
            String text = extractTextByMimeType(utils);
            if (text != null && !text.trim().isEmpty()) {
                InputStream audioContent = text.length() > 3000 ? synthesizeLargeText(text)
                        : pollyService.synthesize(text, OutputFormat.MP3);
                writeAudioContent(utils.getParentNodeRef().getNodeRef(), audioContent);
            }
        } catch (IOException e) {
            logger.error("Failed to process document: " + utils.getDocumentName(), e);
        }
    }

    private String extractTextByMimeType(Utils utils) throws IOException {
        String mimeType = utils.getMimeType().toLowerCase();

        switch (mimeType) {
            case "text/plain":
                return new String(utils.getDocumentData(), StandardCharsets.UTF_8);
            case "application/pdf":
                return extractTextFromPdf(utils.getDocumentData());
            case "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "application/msword":
                return extractTextFromWord(utils.getDocumentData());
            default:
                logger.warn("Unsupported mime type: " + mimeType);
                return null;
        }
    }

    private String extractTextFromPdf(byte[] pdfData) throws IOException {
        try (PDDocument document = PDDocument.load(new ByteArrayInputStream(pdfData))) {
            PDFTextStripper textStripper = new PDFTextStripper();
            String text = textStripper.getText(document).trim();
            
            if (!text.isEmpty()) {
                return text;
            }

            PDFRenderer renderer = new PDFRenderer(document);
            int totalPages = document.getNumberOfPages();
            
            ExecutorService executor = Executors.newFixedThreadPool(
                    Math.min(totalPages, Runtime.getRuntime().availableProcessors()));
            try {
                List<Future<String>> futures = new ArrayList<>();
                for (int page = 0; page < totalPages; page++) {
                    final int pageIndex = page;
                    futures.add(executor.submit(() -> {
                        try {
                            BufferedImage image = renderer.renderImage(pageIndex, 1, 
                                org.apache.pdfbox.rendering.ImageType.RGB);
                            try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
                                ImageIOUtil.writeImage(image, "jpeg", baos);
                                return extractTextFromImage(ByteBuffer.wrap(baos.toByteArray()));
                            }
                        } catch (IOException e) {
                            logger.error("Error processing page " + pageIndex, e);
                            return "";
                        }
                    }));
                }

                StringBuilder result = new StringBuilder();
                for (Future<String> future : futures) {
                    try {
                        result.append(future.get()).append("\n");
                    } catch (Exception e) {
                        logger.error("Error getting thread result", e);
                    }
                }
                return result.toString();
            } finally {
                executor.shutdown();
            }
        }
    }

    private String extractTextFromWord(byte[] wordData) throws IOException {
        try (InputStream is = new ByteArrayInputStream(wordData);
                XWPFDocument doc = new XWPFDocument(is)) {

            return doc.getParagraphs().stream()
                    .map(XWPFParagraph::getText)
                    .reduce("", (a, b) -> a + b + "\n");
        }
    }

    private void writeAudioContent(NodeRef nodeRef, InputStream audioContent) {
        ContentWriter writer = serviceRegistry.getContentService()
                .getWriter(nodeRef, ContentModel.PROP_CONTENT, true);
        writer.setMimetype("audio/mpeg");
        writer.putContent(audioContent);
    }

    private String extractTextFromImage(ByteBuffer buffer) {
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
