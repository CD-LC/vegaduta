package com.agenticai.core.agents.tools.web;

import com.agenticai.core.secrets.SecretRedactor;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;

/**
 * ReadAndRedactStage of the {@link HttpRequestExecutor} pipeline: turns a {@link
 * HttpRequestExecutor.RawResponse} into the {@link HttpRequestResult} the model sees (binary
 * summarised, text clipped with an explicit marker, safe mode additionally dropping Set-Cookie and
 * applying the content-type allow-list and byte cap) and passes every string that travels back
 * through the {@link SecretRedactor}.
 */
final class HttpResponseReader {

    private final ObjectProvider<SecretRedactor> secretRedactor;

    HttpResponseReader(ObjectProvider<SecretRedactor> secretRedactor) {
        this.secretRedactor = secretRedactor;
    }

    HttpRequestResult readResult(
            HttpRequestExecutor.RawResponse response, URI url, int maxChars) {
        Map<String, String> responseHeaders = new LinkedHashMap<>();
        response.headers()
                .forEach(
                        (name, values) -> {
                            if (name != null && values != null && !values.isEmpty()) {
                                responseHeaders.put(name.toLowerCase(Locale.ROOT), values.get(0));
                            }
                        });
        String contentType = response.header("Content-Type").orElse("");
        if (isBinary(contentType)) {
            return new HttpRequestResult(
                    response.status(),
                    url.toString(),
                    responseHeaders,
                    contentType,
                    "[binary response: " + contentType + " - not decoded]",
                    false);
        }
        boolean truncated = response.truncated();
        String text = new String(response.body(), StandardCharsets.UTF_8);
        if (text.length() > maxChars) {
            text = text.substring(0, maxChars) + HttpRequestExecutor.TRUNCATION_MARKER;
            truncated = true;
        }
        return new HttpRequestResult(
                response.status(), url.toString(), responseHeaders, contentType, text, truncated);
    }

    HttpRequestResult readResultSafe(
            HttpRequestExecutor.RawResponse response,
            URI url,
            int maxChars,
            HttpRequestPolicy policy) {
        Map<String, String> responseHeaders = new LinkedHashMap<>();
        response.headers()
                .forEach(
                        (name, values) -> {
                            // A third party's Set-Cookie has no business in a transcript.
                            if (name != null
                                    && values != null
                                    && !values.isEmpty()
                                    && !"set-cookie".equalsIgnoreCase(name)) {
                                responseHeaders.put(name.toLowerCase(Locale.ROOT), values.get(0));
                            }
                        });
        String contentType = response.header("Content-Type").orElse("");
        if (isBinary(contentType)) {
            return new HttpRequestResult(
                    response.status(),
                    url.toString(),
                    responseHeaders,
                    contentType,
                    "[binary response: " + contentType + " - not decoded]",
                    false);
        }
        if (!policy.contentTypeAllowed(contentType)) {
            return new HttpRequestResult(
                    response.status(),
                    url.toString(),
                    responseHeaders,
                    contentType,
                    "[response content type "
                            + contentType
                            + " is not allowed by this tool - body not shown]",
                    false);
        }
        // send() already read at most MAX_RESPONSE_BYTES (pinned-DNS client, per-read timeout); the
        // policy's own,
        // usually smaller, byte cap is applied on top.
        byte[] bytes = response.body();
        boolean truncated = response.truncated();
        if (bytes.length > policy.maxResponseBytes()) {
            bytes = Arrays.copyOf(bytes, policy.maxResponseBytes());
            truncated = true;
        }
        String text = new String(bytes, StandardCharsets.UTF_8);
        if (text.length() > maxChars) {
            text = text.substring(0, maxChars) + HttpRequestExecutor.TRUNCATION_MARKER;
            truncated = true;
        } else if (truncated) {
            text = text + HttpRequestExecutor.TRUNCATION_MARKER;
        }
        return new HttpRequestResult(
                response.status(), url.toString(), responseHeaders, contentType, text, truncated);
    }

    HttpRequestResult redact(HttpRequestResult result, Set<String> values) {
        if (values.isEmpty()) {
            return result;
        }
        Map<String, String> headers = new LinkedHashMap<>();
        result.headers().forEach((k, v) -> headers.put(k, redactText(v, values)));
        return new HttpRequestResult(
                result.status(),
                redactText(result.url(), values),
                headers,
                result.contentType(),
                redactText(result.body(), values),
                result.bodyTruncated());
    }

    String redactText(String text, Set<String> values) {
        if (text == null || values == null || values.isEmpty()) {
            return text;
        }
        SecretRedactor redactor = secretRedactor.getIfAvailable();
        if (redactor == null) {
            // No redactor bean means no resolver bean either (same module), so
            // nothing could have been substituted; values is empty here.
            return text;
        }
        return redactor.redact(text, values);
    }

    static String firstHeader(Map<String, List<String>> headers, String lowerName) {
        List<String> values = headers.get(lowerName);
        return values == null || values.isEmpty() ? "" : values.get(0);
    }

    static boolean isBinary(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return false;
        }
        String lower = contentType.toLowerCase(Locale.ROOT);
        if (lower.contains("json")
                || lower.contains("xml")
                || lower.contains("text")
                || lower.contains("javascript")
                || lower.contains("x-www-form-urlencoded")) {
            return false;
        }
        return lower.startsWith("image/")
                || lower.startsWith("audio/")
                || lower.startsWith("video/")
                || lower.contains("octet-stream")
                || lower.contains("pdf")
                || lower.contains("zip")
                || lower.startsWith("font/");
    }
}
