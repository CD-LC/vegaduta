package com.agenticai.core.knowledge;

import static com.agenticai.core.knowledge.SidecarHttpSupport.describe;
import static com.agenticai.core.knowledge.SidecarHttpSupport.describeClientError;
import static com.agenticai.core.knowledge.SidecarHttpSupport.withConnectRetry;

import com.agenticai.core.knowledge.dto.SidecarExtractRequest;
import com.agenticai.core.knowledge.dto.SidecarExtractResponse;
import com.agenticai.core.knowledge.dto.SidecarTranscribeRequest;
import com.agenticai.core.knowledge.dto.SidecarTranscribeResponse;
import com.agenticai.core.knowledge.ocr.ActiveOcrConfig;
import java.util.Base64;
import java.util.function.Consumer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * The sidecar's file-to-text endpoints ({@code /extract}, {@code /transcribe}) behind the {@link
 * SidecarClient} facade. Both translate a 4xx into {@link InvalidKnowledgeUploadException} (the
 * file was the problem) and anything else into {@link SidecarCommunicationException}. The caller
 * picks the {@link RestClient} (and so the read timeout) per call; see the facade's javadoc.
 */
final class SidecarConversionClient {

    private final Consumer<HttpHeaders> applyInternalToken;

    SidecarConversionClient(Consumer<HttpHeaders> applyInternalToken) {
        this.applyInternalToken = applyInternalToken;
    }

    /** See {@link SidecarClient#extractText(String, byte[], ActiveOcrConfig)}. */
    String extractText(RestClient ingestRestClient, String filename, byte[] content, ActiveOcrConfig ocrConfig) {
        String contentBase64 = Base64.getEncoder().encodeToString(content);
        try {
            SidecarExtractRequest request =
                    ocrConfig == null
                            ? new SidecarExtractRequest(filename, contentBase64)
                            : new SidecarExtractRequest(
                                    filename,
                                    contentBase64,
                                    ocrConfig.provider().name(),
                                    ocrConfig.apiKey());
            SidecarExtractResponse response =
                    withConnectRetry(
                            "/extract",
                            () ->
                                    ingestRestClient
                                            .post()
                                            .uri("/extract")
                                            .contentType(MediaType.APPLICATION_JSON)
                                            .headers(applyInternalToken)
                                            .body(request)
                                            .retrieve()
                                            .body(SidecarExtractResponse.class));
            if (response == null) {
                throw new SidecarCommunicationException(
                        "Sidecar /extract returned an empty response", null);
            }
            return response.text();
        } catch (HttpClientErrorException e) {
            throw new InvalidKnowledgeUploadException(describeClientError(e));
        } catch (RestClientException e) {
            throw new SidecarCommunicationException(describe("/extract", e), e);
        }
    }

    String transcribe(RestClient client, String filename, byte[] content) {
        String contentBase64 = Base64.getEncoder().encodeToString(content);
        try {
            SidecarTranscribeResponse response =
                    withConnectRetry(
                            "/transcribe",
                            () ->
                                    client.post()
                                            .uri("/transcribe")
                                            .contentType(MediaType.APPLICATION_JSON)
                                            .headers(applyInternalToken)
                                            .body(
                                                    new SidecarTranscribeRequest(
                                                            filename, contentBase64))
                                            .retrieve()
                                            .body(SidecarTranscribeResponse.class));
            if (response == null) {
                throw new SidecarCommunicationException(
                        "Sidecar /transcribe returned an empty response", null);
            }
            return response.text();
        } catch (HttpClientErrorException e) {
            throw new InvalidKnowledgeUploadException(describeClientError(e));
        } catch (RestClientException e) {
            throw new SidecarCommunicationException(describe("/transcribe", e), e);
        }
    }
}
