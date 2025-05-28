package es.sergio.main;

import es.sergio.aws.Textract;
import es.sergio.aws.Translate;
import es.sergio.pdf.ImageType;
import es.sergio.pdf.PDFDocument;
import es.sergio.pdf.TextLine;

import java.util.ArrayList;
import java.util.List;
import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.Serializable;
import java.nio.ByteBuffer;
import java.awt.image.BufferedImage;

import org.alfresco.error.AlfrescoRuntimeException;
import org.alfresco.service.ServiceRegistry;
import org.alfresco.repo.action.ParameterDefinitionImpl;
import org.alfresco.repo.action.executer.ActionExecuterAbstractBase;
import org.alfresco.service.cmr.action.Action;
import org.alfresco.service.cmr.action.ParameterDefinition;
import org.alfresco.service.cmr.dictionary.DataTypeDefinition;
import org.alfresco.service.cmr.dictionary.InvalidTypeException;
import org.alfresco.service.cmr.model.FileExistsException;
import org.alfresco.service.cmr.model.FileInfo;
import org.alfresco.service.cmr.repository.NodeRef;
import org.alfresco.service.cmr.repository.ContentReader;
import org.alfresco.service.cmr.repository.ContentWriter;
import org.alfresco.service.cmr.repository.DuplicateChildNodeNameException;
import org.alfresco.service.cmr.repository.InvalidNodeRefException;
import org.alfresco.model.ContentModel;

import org.apache.commons.logging.LogFactory;
import org.apache.commons.logging.Log;

import software.amazon.awssdk.services.textract.model.*;
import software.amazon.awssdk.services.translate.model.TranslateException;
import software.amazon.awssdk.services.translate.model.TranslateTextResponse;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.font.PDFont;
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
		if (serviceRegistry.getNodeService().exists(actionedUponNodeRef) == true) {
			// Get the inputs properties entered via Share Form
	        String input_lang = (String) action.getParameterValue(PARAM_INPUT_LANG);
	        String output_lang = (String) action.getParameterValue(PARAM_OUTPUT_LANG);
	        String formatting = (String) action.getParameterValue(PARAM_FORMATTING);
            logger.info("Formatting: " + formatting);
	        
	        // Get document filename
	        Serializable filename = serviceRegistry.getNodeService().getProperty(
	        		actionedUponNodeRef, ContentModel.PROP_NAME);
	        if (filename == null) {
	        	throw new AlfrescoRuntimeException("Document filename is null");
	        }

	        String documentName = (String) filename;
            String translatedDocumentame = "translated_" + documentName;
            NodeRef parentRef = this.serviceRegistry.getNodeService().getPrimaryParent(actionedUponNodeRef).getParentRef();
            String mimeType = this.serviceRegistry.getContentService().getReader(actionedUponNodeRef, ContentModel.PROP_CONTENT).getMimetype();
            byte[] documentData = getDocumentContentBytes(actionedUponNodeRef, documentName);
            
            // Get the parent folder
            FileInfo uploadNodeRef = null;
            try {
                uploadNodeRef = this.serviceRegistry.getFileFolderService().create(parentRef, translatedDocumentame, ContentModel.PROP_CONTENT);
            } catch (FileExistsException e) {
                logger.error("Error al subir el documento. El documento ya existe.");
                throw new DuplicateChildNodeNameException(parentRef, ContentModel.PROP_CONTENT, translatedDocumentame, e);
            }
            
	        try {
                if (!mimeType.equalsIgnoreCase("application/pdf")) {
                    InputStream translateFile = this.translateService.translateDocument(documentData, input_lang, output_lang, documentName, mimeType);
                    
                    // Update content from uploadnoderef
                    ContentWriter writer = this.serviceRegistry.getContentService().getWriter(uploadNodeRef.getNodeRef(), ContentModel.PROP_CONTENT, true);
                    writer.setMimetype(mimeType);
                    writer.putContent(translateFile);
                } else {
                    logger.info("Generating searchable pdf from: " + documentName);

                    PDFont font;
                    //Default Font
                    font = PDType1Font.HELVETICA;
                    
                    PDFDocument pdfDocument = new PDFDocument(font);
                    
                    List<TextLine> lines;
                    BufferedImage image;
                    ByteArrayOutputStream byteArrayOutputStream;
                    ByteBuffer imageBytes;

                    //Load pdf document and process each page as image
                    PDDocument inputDocument = PDDocument.load(new java.io.ByteArrayInputStream(documentData));
                    PDFRenderer pdfRenderer = new PDFRenderer(inputDocument);
                    for (int page = 0; page < inputDocument.getNumberOfPages(); ++page) {
                        int pageNumber = page + 1;
                        logger.info("processing page " + pageNumber);
                        //Render image
                        image = pdfRenderer.renderImage(page, 1, org.apache.pdfbox.rendering.ImageType.RGB);

                        //Get image bytes
                        byteArrayOutputStream = new ByteArrayOutputStream();
                        ImageIOUtil.writeImage(image, "jpeg", byteArrayOutputStream);
                        byteArrayOutputStream.flush();
                        imageBytes = ByteBuffer.wrap(byteArrayOutputStream.toByteArray());

                        //Extract text
                        lines = extractTextAndTranslate(imageBytes, input_lang, output_lang);

                        //Add page with text layer and image in the pdf document
                        if (formatting != null && formatting.equalsIgnoreCase("true")) {
                            pdfDocument.addPageWithFormatting(image, ImageType.JPEG, lines);
                        } else {
                            pdfDocument.addPageWithoutFormatting(image, ImageType.JPEG, lines);
                        }

                        logger.info("Processed page " + pageNumber);
                    }

                    inputDocument.close();
                    
                    ByteArrayOutputStream pdfOutputStream = new ByteArrayOutputStream();
                    pdfDocument.save(pdfOutputStream);

                    // Write the translated PDF to the new node
                    ContentWriter writer = this.serviceRegistry.getContentService().getWriter(uploadNodeRef.getNodeRef(), ContentModel.PROP_CONTENT, true);
                    writer.setMimetype(mimeType);
                    writer.putContent(new java.io.ByteArrayInputStream(pdfOutputStream.toByteArray()));

                    pdfDocument.close();
                    logger.info("Generated searchable pdf: " + translatedDocumentame);
                }
			} catch (TranslateException e) {
                logger.error("Asegurese de que las credenciales estan cofiguradas en el fichero correspondiente o sean correctas.");
                e.printStackTrace();				throw new AlfrescoRuntimeException("Error al traducir el documento: " + e.getMessage(), e);
			} catch (InvalidNodeRefException e) {
                logger.error("El nodo no existe.");
                e.printStackTrace();	
                throw new InvalidNodeRefException(parentRef);
            } catch (InvalidTypeException e) {
                logger.error("El tipo de dato no es valido, debe ser content.");
                e.printStackTrace();	
                throw new InvalidTypeException("El tipo de dato no es valido, debe ser content.", ContentModel.PROP_CONTENT);
            } catch (Exception e) {
                logger.error("Error al traducir el documento.");
                e.printStackTrace();
				throw new AlfrescoRuntimeException("Error al traducir el documento: " + e.getMessage(), e);
			}
		}
		
	}

	@Override
	protected void addParameterDefinitions(List<ParameterDefinition> paramList) {
		for (String s : new String[]{PARAM_INPUT_LANG, PARAM_OUTPUT_LANG, PARAM_FORMATTING}) {
            paramList.add(new ParameterDefinitionImpl(s, DataTypeDefinition.TEXT, true, getParamDisplayLabel(s)));
        }

	}
	
	/**
     * Get the content bytes for the document with passed in node reference.
     *
     * @param documentRef      the node reference for the document we want the content bytes for
     * @param documentFilename document filename for logging
     * @return a byte array containing the document content or null if not found
     */
    private byte[] getDocumentContentBytes(NodeRef documentRef, String documentFilename) {
        // Get a content reader
        ContentReader contentReader = this.serviceRegistry.getContentService().getReader(
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

    /**
     * Extracts text from the image bytes and translates it.
     *
     * @param imageBytes The image bytes to process.
     * @param sourceLanguage The source language code.
     * @param destinationLanguage The destination language code.
     * @return A list of TextLine objects containing the translated text and bounding box information.
     */
    private List<TextLine> extractTextAndTranslate(ByteBuffer imageBytes, String sourceLanguage, String destinationLanguage) {
        logger.info("Extracting text");

        DetectDocumentTextResponse textResponse = this.textractService.detectDocumentText(imageBytes);

        List<Block> blocks = textResponse.blocks();
        List<TextLine> lines = new ArrayList<>();
        BoundingBox boundingBox;

        for (Block block : blocks) {
            if ((block.blockType()).equals(BlockType.LINE)) {
                String source = block.text();

                TranslateTextResponse resultTranslate = this.translateService.translateText(source, sourceLanguage, destinationLanguage);

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
	  