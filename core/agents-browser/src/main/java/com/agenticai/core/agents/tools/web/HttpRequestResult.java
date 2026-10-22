package com.agenticai.core.agents.tools.web;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * What came back from an {@link HttpRequestExecutor#execute} call, already capped and
 * secret-redacted. Immutable; the body is text (decoded UTF-8), possibly clipped - {@link
 * #bodyTruncated} says so.
 *
 * @param status HTTP status code of the FINAL hop (redirects are followed).
 * @param url the final URL actually fetched, with any query params appended and any redirect
 *     followed - never carries resolved secrets (secrets are only ever substituted into
 *     headers/query/body values, and the query values ARE redacted before this is built).
 * @param headers response headers, first value per name, lower-cased names.
 * @param contentType the response Content-Type, or empty.
 * @param body decoded body text, clipped to the requested maxChars.
 * @param bodyTruncated true when the body was clipped (by byte cap or char cap).
 */
public record HttpRequestResult(
        int status,
        String url,
        Map<String, String> headers,
        String contentType,
        String body,
        boolean bodyTruncated) {

    public HttpRequestResult {
        headers =
                headers == null
                        ? Map.of()
                        : Collections.unmodifiableMap(new LinkedHashMap<>(headers));
        contentType = contentType == null ? "" : contentType;
        body = body == null ? "" : body;
    }

    public boolean ok() {
        return status >= 200 && status < 300;
    }

    /**
     * The body parsed as JSON when it is one (either by Content-Type or by shape - plenty of APIs
     * send JSON as text/plain), otherwise null. Never throws: a body that merely LOOKS like JSON
     * but is not parses to null, and the caller falls back to the raw text.
     */
    public JsonNode bodyAsJson(ObjectMapper mapper) {
        if (bodyTruncated) {
            // A clipped JSON document is never valid JSON; do not pretend.
            return null;
        }
        String trimmed = body.strip();
        boolean looksJson = trimmed.startsWith("{") || trimmed.startsWith("[");
        boolean declaredJson = contentType.toLowerCase(java.util.Locale.ROOT).contains("json");
        if (!looksJson && !declaredJson) {
            return null;
        }
        if (trimmed.isEmpty()) {
            return null;
        }
        try {
            return mapper.readTree(trimmed);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * The canonical machine-readable shape both consumers hand onward - the LLM tool result and the
     * workflow node's output use the exact same keys so a person reading either recognises the
     * other: {@code {status, ok, url, contentType, headers, body, truncated}} where {@code body} is
     * a parsed JSON value when the response was JSON and a plain string otherwise.
     */
    public ObjectNode toJson(ObjectMapper mapper) {
        ObjectNode node = mapper.createObjectNode();
        node.put("status", status);
        node.put("ok", ok());
        node.put("url", url);
        node.put("contentType", contentType);
        ObjectNode headerNode = node.putObject("headers");
        headers.forEach(headerNode::put);
        JsonNode json = bodyAsJson(mapper);
        if (json != null) {
            node.set("body", json);
        } else {
            node.put("body", body);
        }
        node.put("truncated", bodyTruncated);
        return node;
    }
}
