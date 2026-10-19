package com.agenticai.core.common.security;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.Collections;
import java.util.List;

/**
 * hCaptcha API client for server-side verification of CAPTCHA tokens.
 * Sends token to hCaptcha servers and gets back success/failure response.
 */
@Slf4j
@RequiredArgsConstructor
public class HCaptchaClient {
    private static final String HCAPTCHA_VERIFY_URL = "https://hcaptcha.com/siteverify";

    private final HCaptchaConfig config;
    private final RestClient restClient;

    /**
     * Verify an hCaptcha token submitted by the client.
     *
     * @param token The hCaptcha response token from the client
     * @param remoteIp The client's IP address (optional but recommended)
     * @return HCaptchaVerifyResponse with success flag
     * @throws HCaptchaException if verification fails or API is unreachable
     */
    public HCaptchaVerifyResponse verify(String token, String remoteIp) throws HCaptchaException {
        if (token == null || token.isEmpty()) {
            throw new HCaptchaException("hCaptcha token is required");
        }

        if (!config.isEnabled()) {
            // If hCaptcha is disabled, accept all tokens (for testing/dev)
            log.warn("hCaptcha verification is disabled");
            HCaptchaVerifyResponse response = new HCaptchaVerifyResponse();
            response.setSuccess(true);
            return response;
        }

        HCaptchaVerifyRequest request = new HCaptchaVerifyRequest();
        request.setSecret(config.getSecretKey());
        request.setResponse(token);
        if (remoteIp != null && !remoteIp.isEmpty()) {
            request.setRemoteIp(remoteIp);
        }

        try {
            HCaptchaVerifyResponse response = restClient.post()
                    .uri(HCAPTCHA_VERIFY_URL)
                    .body(request)
                    .retrieve()
                    .body(HCaptchaVerifyResponse.class);

            if (response == null) {
                throw new HCaptchaException("Empty response from hCaptcha API");
            }

            if (!response.isSuccess()) {
                log.warn("hCaptcha verification failed. Error codes: {}", response.getErrorCodes());
            }

            return response;
        } catch (RestClientException e) {
            throw new HCaptchaException("hCaptcha API request failed", e);
        }
    }

    /**
     * Configuration properties for hCaptcha integration.
     */
    @Getter
    @Setter
    @ConfigurationProperties(prefix = "fraud-detection.hcaptcha")
    public static class HCaptchaConfig {
        private boolean enabled = true;
        private String siteKey;
        private String secretKey;
    }

    /**
     * hCaptcha verify request DTO.
     */
    @Getter
    @Setter
    public static class HCaptchaVerifyRequest {
        private String secret;
        private String response;

        @JsonProperty("remoteip")
        private String remoteIp;
    }

    /**
     * hCaptcha verify response DTO.
     */
    @Getter
    @Setter
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class HCaptchaVerifyResponse {
        @JsonProperty("success")
        private boolean success;

        @JsonProperty("challenge_ts")
        private String challengeTimestamp;

        @JsonProperty("hostname")
        private String hostname;

        @JsonProperty("error-codes")
        private List<String> errorCodes = Collections.emptyList();

        @JsonProperty("score")
        private Double score; // hCaptcha score (0.0-1.0) if using score endpoint

        @JsonProperty("score_reason")
        private String scoreReason;

        public boolean isErrorCodes() {
            return errorCodes != null && !errorCodes.isEmpty();
        }

        @Override
        public String toString() {
            return "HCaptchaVerifyResponse{" +
                    "success=" + success +
                    ", hostname='" + hostname + '\'' +
                    ", errorCodes=" + errorCodes +
                    ", score=" + score +
                    '}';
        }
    }

    /**
     * Exception thrown when hCaptcha verification fails.
     */
    public static class HCaptchaException extends Exception {
        public HCaptchaException(String message) {
            super(message);
        }

        public HCaptchaException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
