package es.sergio.aws;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.translate.TranslateClient;
import software.amazon.awssdk.services.translate.model.Document;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.translate.model.TranslateDocumentRequest;
import software.amazon.awssdk.services.translate.model.TranslateDocumentResponse;
import software.amazon.awssdk.services.translate.model.TranslateTextRequest;
import software.amazon.awssdk.services.translate.model.TranslateTextResponse;

import java.io.InputStream;

import org.apache.commons.logging.LogFactory;
import org.apache.commons.logging.Log;

public class Translate {
        private static final Log logger = LogFactory.getLog(Translate.class);
        public final TranslateClient translateClient;

        public Translate(String awsKey, String awsSecret) {
                AwsBasicCredentials awsCredentials = AwsBasicCredentials.create(awsKey, awsSecret);
                this.translateClient = TranslateClient.builder()
                                .region(Region.EU_WEST_1)
                                .credentialsProvider(StaticCredentialsProvider.create(awsCredentials))
                                .build();
        }

        /**
         * Translate a document from a source language to a target language.
         *
         * @param content            The document content as a byte array.
         * @param sourceLanguageCode The language code for the source language.
         * @param targetLanguageCode The language code for the target language.
         * @param fileName           The name of the document file.
         * @param mimeType           The MIME type of the document.
         * @return The translated document as an InputStream.
         */
        public InputStream translateDocument(byte[] content, String sourceLanguageCode, String targetLanguageCode,
                        String fileName, String mimeType) {
                Document document = Document.builder()
                                .content(SdkBytes.fromByteArray(content))
                                .contentType(mimeType)
                                .build();

                TranslateDocumentRequest request = TranslateDocumentRequest.builder()
                                .document(document)
                                .sourceLanguageCode(sourceLanguageCode.trim())
                                .targetLanguageCode(targetLanguageCode.trim())
                                .build();
                TranslateDocumentResponse result = this.translateClient.translateDocument(request);
                return result.translatedDocument().content().asInputStream();
        }


        /**
         * Translate a text from a source language to a target language.
         *
         * @param text               The text to translate.
         * @param sourceLanguageCode The language code for the source language.
         * @param targetLanguageCode The language code for the target language.
         * @return The translation response containing the translated text.
         */
        public TranslateTextResponse translateText(String text, String sourceLanguageCode, String targetLanguageCode) {
                TranslateTextRequest request = TranslateTextRequest.builder()
                                .text(text)
                                .sourceLanguageCode(sourceLanguageCode.trim())
                                .targetLanguageCode(targetLanguageCode.trim())
                                .build();
                return this.translateClient.translateText(request);
        }

}