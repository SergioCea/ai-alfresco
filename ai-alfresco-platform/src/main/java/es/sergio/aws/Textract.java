package es.sergio.aws;

import java.nio.ByteBuffer;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.textract.TextractClient;
import software.amazon.awssdk.services.textract.model.*;

public class Textract {
        private final TextractClient textractClient;

        public Textract(String awsKey, String awsSecret) {
                AwsBasicCredentials awsCredentials = AwsBasicCredentials.create(awsKey, awsSecret);
                this.textractClient = TextractClient.builder()
                                .region(Region.EU_WEST_1)
                                .credentialsProvider(StaticCredentialsProvider.create(awsCredentials))
                                .build();
        }

        /**
         * Detect text in a document using Amazon Textract.
         *
         * @param imageBytes The document content as a ByteBuffer.
         * @return The response containing detected text.
         */
        public DetectDocumentTextResponse detectDocumentText(ByteBuffer imageBytes) {
                Document pdfDoc = Document.builder()
                                .bytes(SdkBytes.fromByteBuffer(imageBytes))
                                .build();

                DetectDocumentTextRequest detectDocumentTextRequest = DetectDocumentTextRequest.builder()
                                .document(pdfDoc)
                                .build();
                return this.textractClient.detectDocumentText(detectDocumentTextRequest);
        }

        /**
         * Extract metadata from a document using Amazon Textract.
         *
         * @param content The document content as a byte array.
         * @return The response containing extracted metadata.
         */
        public AnalyzeExpenseResponse extractMetadata(byte[] content) {
                Document img = Document.builder()
                                .bytes(SdkBytes.fromByteArray(content))
                                .build();

                AnalyzeExpenseRequest analyzeExpenseRequest = AnalyzeExpenseRequest.builder()
                                .document(img)
                                .build();

                return this.textractClient.analyzeExpense(analyzeExpenseRequest);
        }
}
