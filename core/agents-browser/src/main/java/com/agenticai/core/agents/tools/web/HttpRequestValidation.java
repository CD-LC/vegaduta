package com.agenticai.core.agents.tools.web;

import com.agenticai.core.json.JsonMappers;
import com.agenticai.core.json.JsonNodes;
import com.agenticai.core.security.TenantContext;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * ValidateStage of the {@link HttpRequestExecutor} pipeline: everything that decides, from the spec
 * alone and before any secret is resolved or any host looked up, whether a request is well formed -
 * method, clamps, header names and values, body encoding, the caller's trust class and the default
 * headers. All static and side-effect free; each rule is the one that lived inline in the executor.
 */
final class HttpRequestValidation {

    private static final ObjectMapper MAPPER = JsonMappers.plain();

    static final Set<String> ALLOWED_METHODS =
            Set.of("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS");

    /**
     * Headers a caller may not set: they would let a caller desynchronise the connection or lie
     * about the destination. Rejected with a clear reason rather than silently dropped.
     */
    static final Set<String> FORBIDDEN_HEADERS =
            Set.of(
                    "host",
                    "content-length",
                    "connection",
                    "expect",
                    "upgrade",
                    "transfer-encoding",
                    "te",
                    "trailer",
                    "keep-alive",
                    "proxy-connection");

    /** RFC 7230 token - a header name. */
    private static final Pattern HEADER_NAME = Pattern.compile("[!#$%&'*+.^_`|~0-9A-Za-z-]+");

    private HttpRequestValidation() {}

    static String normaliseMethod(String method) {
        String upper =
                method == null || method.isBlank()
                        ? "GET"
                        : method.strip().toUpperCase(Locale.ROOT);
        if (!ALLOWED_METHODS.contains(upper)) {
            throw new WebPageFetchException(
                    "HTTP method '"
                            + method.strip()
                            + "' is not supported (use GET, POST, PUT, PATCH, DELETE, HEAD or OPTIONS)");
        }
        return upper;
    }

    static int clampTimeout(Integer requested) {
        if (requested == null || requested <= 0) {
            return HttpRequestExecutor.DEFAULT_TIMEOUT_SECONDS;
        }
        return Math.min(requested, HttpRequestExecutor.MAX_TIMEOUT_SECONDS);
    }

    static int clampMaxChars(Integer requested) {
        if (requested == null || requested <= 0) {
            return HttpRequestExecutor.DEFAULT_MAX_BODY_CHARS;
        }
        return Math.max(
                HttpRequestExecutor.MIN_MAX_BODY_CHARS,
                Math.min(requested, HttpRequestExecutor.HARD_MAX_BODY_CHARS));
    }

    /**
     * True for callers that are not an authenticated workspace member: public share visitors,
     * workflow share runs and webhook triggers, and inbound channel messages. Their turns may be
     * driven by anyone on the internet, so they never resolve USER-scoped secrets and never spend
     * x402 funds (SEC-006/029). Subjects come from {@code TenantContext} as set by those entry
     * points.
     */
    static boolean isUntrustedSubject(String subject) {
        if (subject == null || subject.isBlank()) {
            return false;
        }
        String s = subject.strip().toLowerCase(Locale.ROOT);
        return s.startsWith("agent-share:")
                || s.startsWith("share:")
                || s.startsWith("system:agent-share:")
                || s.startsWith("workflow-share")
                || s.startsWith("system:workflow-share")
                || s.startsWith("system:workflow-webhook")
                || s.startsWith("system:email-poll")
                || (s.startsWith("system:") && s.contains("-inbound"));
    }

    /** The explicit user or the tenant context's subject is a synthetic/untrusted caller. */
    static boolean untrustedCaller(String userId, TenantContext ctx) {
        return isUntrustedSubject(userId) || (ctx != null && isUntrustedSubject(ctx.getSubject()));
    }

    static String bodyType(HttpRequestSpec spec) {
        return spec.bodyType() == null ? "" : spec.bodyType().strip().toLowerCase(Locale.ROOT);
    }

    /** The Content-Type a body of {@code bodyType} gets when the caller named none. */
    static String defaultContentType(String bodyType) {
        return switch (bodyType) {
            case "form" -> "application/x-www-form-urlencoded; charset=utf-8";
            case "text" -> "text/plain; charset=utf-8";
            default -> "application/json";
        };
    }

    /**
     * Validates and copies caller headers. Order of refusals is part of the contract: a forbidden
     * name first, then (safe mode only, {@code policy} non-null) the policy deny-list, then the
     * token / value syntax.
     */
    static Map<String, String> checkedHeaders(Map<String, String> raw, HttpRequestPolicy policy) {
        Map<String, String> requestHeaders = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : raw.entrySet()) {
            String name = entry.getKey() == null ? "" : entry.getKey().strip();
            if (name.isEmpty()) {
                continue;
            }
            String lower = name.toLowerCase(Locale.ROOT);
            if (FORBIDDEN_HEADERS.contains(lower)) {
                throw new WebPageFetchException(
                        "the '" + name + "' header cannot be set by a request");
            }
            if (policy != null && policy.headerDenied(lower)) {
                throw new WebPageFetchException(
                        "the '"
                                + name
                                + "' header is not allowed on this tool - "
                                + "credential and proxy headers are blocked; credentials go through the 'auth' parameter");
            }
            String value = entry.getValue() == null ? "" : entry.getValue();
            if (!HEADER_NAME.matcher(name).matches() || !isValidHeaderValue(value)) {
                throw new WebPageFetchException("the URL or one of the headers is not valid");
            }
            requestHeaders.put(name, value);
        }
        return requestHeaders;
    }

    /** Adds User-Agent and Accept unless the caller set them. */
    static void addDefaultHeaders(Map<String, String> headers, String accept) {
        if (!containsHeader(headers, "user-agent")) {
            headers.put("User-Agent", "VegaDuta-Agent/1.0 (+https://github.com/CD-LC/vegaduta)");
        }
        if (!containsHeader(headers, "accept")) {
            headers.put("Accept", accept);
        }
    }

    static boolean isValidHeaderValue(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\r' || c == '\n' || c == 0 || (c < 0x20 && c != '\t') || c == 0x7f) {
                return false;
            }
        }
        return true;
    }

    static boolean containsHeader(Map<String, String> headers, String lowerName) {
        for (String key : headers.keySet()) {
            if (key.equalsIgnoreCase(lowerName)) {
                return true;
            }
        }
        return false;
    }

    static String headerValue(Map<String, String> headers, String lowerName) {
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(lowerName)) {
                return entry.getValue();
            }
        }
        return null;
    }

    /**
     * Appends URL-encoded {@code query} pairs, respecting an existing '?'. Package-private test
     * seam (via {@link HttpRequestExecutor#appendQuery}).
     */
    static String appendQuery(String url, Map<String, String> query) {
        if (query == null || query.isEmpty()) {
            return url;
        }
        StringBuilder out = new StringBuilder(url);
        boolean first = !url.contains("?");
        for (Map.Entry<String, String> entry : query.entrySet()) {
            String key = entry.getKey() == null ? "" : entry.getKey().strip();
            if (key.isEmpty()) {
                continue;
            }
            out.append(first ? '?' : '&');
            first = false;
            out.append(encode(key))
                    .append('=')
                    .append(encode(entry.getValue() == null ? "" : entry.getValue()));
        }
        return out.toString();
    }

    private static String encode(String value) {
        // URLEncoder is form-encoding ('+' for space); query strings want %20.
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    /**
     * A form body written as a JSON object ({@code {"a":"1","b":"2"}} - the shape a model naturally
     * produces) becomes {@code a=1&b=2}; anything else is assumed to be form-encoded already.
     */
    static String formEncodeIfJsonObject(String body) {
        String trimmed = body.strip();
        if (!trimmed.startsWith("{")) {
            return body;
        }
        try {
            JsonNode node = MAPPER.readTree(trimmed);
            if (!node.isObject()) {
                return body;
            }
            StringBuilder out = new StringBuilder();
            Iterator<Map.Entry<String, JsonNode>> fields = JsonNodes.fields(node);
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                if (out.length() > 0) {
                    out.append('&');
                }
                String value =
                        field.getValue().isString()
                                ? field.getValue().asString()
                                : field.getValue().toString();
                out.append(URLEncoder.encode(field.getKey(), StandardCharsets.UTF_8))
                        .append('=')
                        .append(URLEncoder.encode(value, StandardCharsets.UTF_8));
            }
            return out.toString();
        } catch (Exception e) {
            return body;
        }
    }
}
