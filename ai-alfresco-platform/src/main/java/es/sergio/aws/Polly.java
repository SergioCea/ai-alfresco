package es.sergio.aws;

import java.io.IOException;
import java.io.InputStream;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.polly.PollyClient;
import software.amazon.awssdk.services.polly.model.OutputFormat;
import software.amazon.awssdk.services.polly.model.SynthesizeSpeechRequest;
import software.amazon.awssdk.services.polly.model.Voice;
import software.amazon.awssdk.services.polly.model.DescribeVoicesRequest;
import software.amazon.awssdk.services.polly.model.DescribeVoicesResponse;
import software.amazon.awssdk.services.polly.model.LanguageCode;

public class Polly {
        private final PollyClient pollyClient;
        private final Voice voice;

        public Polly(String awsKey, String awsSecret) {
                AwsBasicCredentials awsCredentials = AwsBasicCredentials.create(awsKey, awsSecret);
                this.pollyClient = PollyClient.builder()
                                .region(Region.EU_WEST_1)
                                .credentialsProvider(StaticCredentialsProvider.create(awsCredentials))
                                .build();

                DescribeVoicesRequest describeVoiceRequest = DescribeVoicesRequest.builder()
                                .engine("neural")
                                .languageCode(LanguageCode.ES_ES)
                                .build();

                DescribeVoicesResponse describeVoicesResult = this.pollyClient.describeVoices(describeVoiceRequest);
                this.voice = describeVoicesResult.voices().stream()
                                .filter(v -> v.name().equals("Lucia"))
                                .findFirst()
                                .orElseThrow(() -> new RuntimeException("Voice not found"));
        }

        public InputStream synthesize(String text, OutputFormat format)
                        throws IOException {
                SynthesizeSpeechRequest synthReq = SynthesizeSpeechRequest.builder()
                                .text(text)
                                .voiceId(this.voice.id())
                                .outputFormat(format)
                                .build();

                return this.pollyClient.synthesizeSpeech(synthReq);
        }
}
