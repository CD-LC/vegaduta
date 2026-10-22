package com.agenticai.core.knowledge;

import com.agenticai.core.json.JsonMappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.ConnectException;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * Shared HTTP plumbing for the sidecar client family: the single connect-refused retry and the
 * error message builders. Extracted unchanged from {@link SidecarClient}; the logger keeps that
 * class's name so existing log filters still match.
 */
final class SidecarHttpSupport {

    private static final Logger log = LoggerFactory.getLogger(SidecarClient.class);

    private static final long CONNECT_RETRY_DELAY_MS = 250;

    private static final ObjectMapper MAPPER = JsonMappers.plain();

    private SidecarHttpSupport() {}

    /**
     * Retries {@code call} exactly once when it fails with a {@link ConnectException} anywhere in
     * its cause chain - the one failure mode where the request provably never reached the sidecar,
     * making a retry unconditionally safe (nothing ran) and cheap (connection refusal is immediate,
     * unlike a read timeout). Everything else - HTTP error responses, read timeouts,
     * deserialization failures - propagates unchanged on the first attempt; see the class javadoc
     * for why those are deliberately not retried.
     */
    static <T> T withConnectRetry(String endpoint, Supplier<T> call) {
        try {
            return call.get();
        } catch (RestClientException first) {
            if (!isConnectionFailure(first)) {
                throw first;
            }
            log.warn(
                    "Sidecar {} connection failed ({}) - retrying once",
                    endpoint,
                    first.getMessage());
            try {
                Thread.sleep(CONNECT_RETRY_DELAY_MS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw first;
            }
            return call.get();
        }
    }

    static boolean isConnectionFailure(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof ConnectException) {
                return true;
            }
        }
        return false;
    }

    /**
     * FastAPI's HTTPException bodies are JSON like {@code {"detail": "..."}} - this pulls out just
     * that message rather than surfacing raw JSON to whoever is uploading the file.
     */
    static String describeClientError(HttpClientErrorException e) {
        String body = e.getResponseBodyAsString();
        try {
            JsonNode node = MAPPER.readTree(body);
            JsonNode detail = node.get("detail");
            if (detail != null && detail.isTextual()) {
                return detail.asText();
            }
        } catch (Exception ignored) {
            // Not JSON, or no "detail" field - fall through to the raw body below.
        }
        return body.isBlank()
                ? "The file could not be processed (HTTP " + e.getStatusCode().value() + ")"
                : body;
    }

    /**
     * Builds an error message that actually says what happened, instead of a generic "is it
     * running?" guess - distinguishes "sidecar returned a real HTTP error" (status + body, e.g. a
     * validation failure) from "couldn't connect at all" (refused/timeout/DNS), since those need
     * very different fixes.
     */
    static String describe(String endpoint, RestClientException e) {
        if (e instanceof RestClientResponseException responseException) {
            return "Sidecar "
                    + endpoint
                    + " returned HTTP "
                    + responseException.getStatusCode().value()
                    + ": "
                    + responseException.getResponseBodyAsString();
        }
        return "Failed to reach sidecar "
                + endpoint
                + " - is it running (see README Stage 5)? Cause: "
                + e.getClass().getSimpleName()
                + ": "
                + e.getMessage();
    }
}
