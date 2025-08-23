package es.sergio.main;

import es.sergio.aws.Textract;
import es.sergio.aws.Translate;
import es.sergio.pdf.ImageType;
import es.sergio.pdf.PDFDocument;
import es.sergio.pdf.TextLine;
import es.sergio.utils.Utils;
import es.sergio.utils.Utils.ActionType;

import java.util.ArrayList;
import java.util.List;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.awt.image.BufferedImage;

import org.alfresco.error.AlfrescoRuntimeException;
import org.alfresco.service.ServiceRegistry;
import org.alfresco.repo.action.ParameterDefinitionImpl;
import org.alfresco.repo.action.executer.ActionExecuterAbstractBase;
import org.alfresco.service.cmr.action.Action;
import org.alfresco.service.cmr.action.ParameterDefinition;
import org.alfresco.service.cmr.dictionary.DataTypeDefinition;
import java.io.ByteArrayInputStream;

import org.alfresco.service.cmr.repository.NodeRef;
import org.alfresco.service.cmr.repository.ContentWriter;
import org.alfresco.model.ContentModel;

import org.apache.commons.logging.LogFactory;
import org.apache.commons.logging.Log;

import software.amazon.awssdk.services.textract.model.*;
import software.amazon.awssdk.services.translate.model.TranslateTextResponse;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.tools.imageio.ImageIOUtil;

public class TranslateService extends ActionExecuterAbstractBase {
    private static final Log logger = LogFactory.getLog(TranslateService.class);
    private ServiceRegistry serviceRegistry;
    private Translate translateService;
    private Textract textractService;

    public static final String PARAM_INPUT_LANG = "input_lang";
    public static final String PARAM_OUTPUT_LANG = "output_lang";
    public static final String PARAM_FORMATTING = "formatting";

    public void setServiceRegistry(ServiceRegistry serviceRegistry) {
        this.serviceRegistry = serviceRegistry;
    }

    public void setTranslateService(Translate translateService) {
        this.translateService = translateService;
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
        String inputLang = (String) action.getParameterValue(PARAM_INPUT_LANG);
        String outputLang = (String) action.getParameterValue(PARAM_OUTPUT_LANG);
        boolean withFormatting = "true".equalsIgnoreCase((String) action.getParameterValue(PARAM_FORMATTING));

        utils.getNodeInfo(actionedUponNodeRef, ActionType.TRANSLATE);
        utils.copyAspects(actionedUponNodeRef);

        try {
            InputStream translatedContent = utils.getMimeType().equalsIgnoreCase("application/pdf")
                    ? translatePdf(utils, inputLang, outputLang, withFormatting)
                    : translateService.translateDocument(utils.getDocumentData(), inputLang, outputLang,
                            utils.getDocumentName(), utils.getMimeType());

            writeTranslatedContent(utils, translatedContent);

        } catch (Exception e) {
            logger.error("Translation failed for document: " + utils.getDocumentName(), e);
            throw new AlfrescoRuntimeException("Translation failed: " + e.getMessage(), e);
        }
    }

    private InputStream translatePdf(Utils utils, String inputLang, String outputLang, boolean withFormatting)
            throws IOException {
        logger.info("Translating PDF: " + utils.getDocumentName());

        try (PDDocument inputDocument = PDDocument.load(new ByteArrayInputStream(utils.getDocumentData()));
                ByteArrayOutputStream pdfOutput = new ByteArrayOutputStream()) {

            PDFDocument pdfDocument = new PDFDocument(PDType1Font.HELVETICA);
            PDFRenderer renderer = new PDFRenderer(inputDocument);

            for (int page = 0; page < inputDocument.getNumberOfPages(); page++) {
                BufferedImage image = renderer.renderImage(page, 1, org.apache.pdfbox.rendering.ImageType.RGB);
                ByteBuffer imageBytes = renderImageToBytes(image);
                List<TextLine> translatedLines = extractTextAndTranslate(imageBytes, inputLang, outputLang);

                if (withFormatting) {
                    pdfDocument.addPageWithFormatting(image, ImageType.JPEG, translatedLines);
                } else {
                    pdfDocument.addPageWithoutFormatting(image, translatedLines);
                }
            }

            pdfDocument.save(pdfOutput);
            pdfDocument.close();
            return new ByteArrayInputStream(pdfOutput.toByteArray());
        }
    }

    private ByteBuffer renderImageToBytes(BufferedImage image) throws IOException {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            ImageIOUtil.writeImage(image, "jpeg", baos);
            return ByteBuffer.wrap(baos.toByteArray());
        }
    }

    private void writeTranslatedContent(Utils utils, InputStream content) {
        ContentWriter writer = serviceRegistry.getContentService()
                .getWriter(utils.getParentNodeRef().getNodeRef(), ContentModel.PROP_CONTENT, true);
        writer.setMimetype(utils.getMimeType());
        writer.putContent(content);
    }

    @Override
    protected void addParameterDefinitions(List<ParameterDefinition> paramList) {
        for (String s : new String[] { PARAM_INPUT_LANG, PARAM_OUTPUT_LANG, PARAM_FORMATTING }) {
            paramList.add(new ParameterDefinitionImpl(s, DataTypeDefinition.TEXT, true, getParamDisplayLabel(s)));
        }

    }

    /**
     * Extracts text from the image bytes and translates it.
     *
     * @param imageBytes          The image bytes to process.
     * @param sourceLanguage      The source language code.
     * @param destinationLanguage The destination language code.
     * @return A list of TextLine objects containing the translated text and
     *         bounding box information.
     */
    private List<TextLine> extractTextAndTranslate(ByteBuffer imageBytes, String sourceLanguage,
            String destinationLanguage) {
        logger.info("Extracting text");

        DetectDocumentTextResponse textResponse = this.textractService.detectDocumentText(imageBytes);

        List<Block> blocks = textResponse.blocks();
        List<TextLine> lines = new ArrayList<>();
        BoundingBox boundingBox;

        for (Block block : blocks) {
            if ((block.blockType()).equals(BlockType.LINE)) {
                String source = block.text();

                TranslateTextResponse resultTranslate = this.translateService.translateText(source, sourceLanguage,
                        destinationLanguage);

                boundingBox = block.geometry().boundingBox();
                lines.add(new TextLine(boundingBox.left(),
                        boundingBox.top(),
                        boundingBox.width(),
                        boundingBox.height(),
                        resultTranslate.translatedText(),
                        source));
            }
        }
        return lines;
    }

}
