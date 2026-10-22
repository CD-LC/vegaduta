package com.agenticai.core.agents.tools.openapi;

import com.agenticai.core.json.JsonMappers;
import com.agenticai.core.json.JsonNodes;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.apache.hc.client5.http.classic.methods.HttpUriRequestBase;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.springframework.util.StringUtils;

/**
 * Request assembly, header checks, the cancel-on-close response wrapper and the small encode /
 * parse / redact helpers behind {@link OpenApiToolExecutor}. Stateless and static; split out of the
 * executor only to keep each file reviewable - the security rules are unchanged and still
 * documented on the executor.
 */
final class OpenApiHttpSupport {

    private static final ObjectMapper MAPPER = JsonMappers.plain();
    static final String USER_AGENT = "VegaDuta-Agent/1.0 (+https://github.com/CD-LC/vegaduta)";
    static final String REDACTED = "[redacted]";

    /**
     * Assembles the tool-call request - extracted from {@link #execute} so the OAuth2 retry-on-401
     * path can rebuild the IDENTICAL request with a fresh token instead of mutating a used builder.
     */
    static HttpUriRequestBase toolRequest(
            URI uri,
            Map<String, String> headers,
            String secret,
            String bearerToken,
            OpenApiToolSource source,
            String contentType,
            String method,
            String body) {
        HttpUriRequestBase request = new HttpUriRequestBase(method, uri);
        request.addHeader("User-Agent", USER_AGENT);
        request.addHeader("Accept", "application/json, text/plain;q=0.8, */*;q=0.5");
        for (Map.Entry<String, String> header : headers.entrySet()) {
            setCheckedHeader(request, header.getKey(), header.getValue());
        }
        if (bearerToken != null) {
            request.setHeader("Authorization", "Bearer " + bearerToken);
        } else if (secret != null) {
            if (source.getAuthType() == OpenApiAuthType.BEARER) {
                request.setHeader("Authorization", "Bearer " + secret);
            } else if (source.getAuthType() == OpenApiAuthType.API_KEY_HEADER) {
                String headerName =
                        StringUtils.hasText(source.getAuthHeaderName())
                                ? source.getAuthHeaderName()
                                : "X-API-Key";
                setCheckedHeader(request, headerName, secret);
            }
        }
        if (body != null) {
            setCheckedHeader(request, "Content-Type", contentType);
            request.setEntity(new StringEntity(body, StandardCharsets.UTF_8));
        }
        return request;
    }

    /**
     * Hop-by-hop / framing headers the JDK client used to refuse; still refused so a tool cannot
     * set them.
     */
    static final Set<String> RESTRICTED_HEADERS =
            Set.of("connection", "content-length", "expect", "host", "upgrade");

    /**
     * Same header rules the JDK {@code HttpRequest.Builder} enforced before the switch to the
     * guarded client: RFC 7230 token names, no CR/LF in values (header injection), and no
     * framing/hop-by-hop headers.
     */
    static void setCheckedHeader(HttpUriRequestBase request, String name, String value) {
        if (name == null
                || name.isEmpty()
                || !name.chars().allMatch(OpenApiHttpSupport::isTokenChar)) {
            throw new IllegalArgumentException("invalid header name: \"" + name + "\"");
        }
        if (RESTRICTED_HEADERS.contains(name.toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException("restricted header name: \"" + name + "\"");
        }
        if (value != null && (value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0)) {
            throw new IllegalArgumentException("invalid header value for \"" + name + "\"");
        }
        request.setHeader(name, value == null ? "" : value);
    }

    static boolean isTokenChar(int c) {
        return c > 0x20 && c < 0x7f && "()<>@,;:\\\"/[]?={}".indexOf(c) < 0;
    }

    /**
     * An open response plus the request that produced it. Closing it (or its body stream) CANCELS
     * the exchange - the connection is shut down rather than drained, so a capped read of a huge or
     * endless body cannot keep reading after the cap.
     */
    static final class PinnedResponse implements AutoCloseable {
        private final HttpUriRequestBase request;
        private final ClassicHttpResponse response;

        PinnedResponse(HttpUriRequestBase request, ClassicHttpResponse response) {
            this.request = request;
            this.response = response;
        }

        int statusCode() {
            return response.getCode();
        }

        java.util.Optional<String> header(String name) {
            Header h = response.getFirstHeader(name);
            return java.util.Optional.ofNullable(h == null ? null : h.getValue());
        }

        InputStream body() throws IOException {
            HttpEntity entity = response.getEntity();
            InputStream raw = entity == null ? InputStream.nullInputStream() : entity.getContent();
            return new java.io.FilterInputStream(raw) {
                @Override
                public void close() {
                    PinnedResponse.this.close();
                }
            };
        }

        @Override
        public void close() {
            request.cancel();
            try {
                response.close();
            } catch (IOException ignored) {
                // Best-effort - the connection is being abandoned either way.
            }
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    static ObjectNode parseInput(String toolInputJson) {
        if (!StringUtils.hasText(toolInputJson)) {
            return MAPPER.createObjectNode();
        }
        try {
            JsonNode node = MAPPER.readTree(toolInputJson);
            return node != null && node.isObject() ? (ObjectNode) node : MAPPER.createObjectNode();
        } catch (JsonProcessingException e) {
            throw new OpenApiToolException(
                    "The tool input is not valid JSON: " + e.getOriginalMessage());
        }
    }

    static String serializeBody(JsonNode body, String contentType) {
        if (contentType.toLowerCase(Locale.ROOT).startsWith("application/x-www-form-urlencoded")
                && body.isObject()) {
            StringBuilder form = new StringBuilder();
            Iterator<Map.Entry<String, JsonNode>> it = JsonNodes.fields(body);
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> field = it.next();
                if (field.getValue().isArray()) {
                    for (JsonNode element : field.getValue()) {
                        appendQuery(form, field.getKey(), stringify(element));
                    }
                } else {
                    appendQuery(form, field.getKey(), stringify(field.getValue()));
                }
            }
            return form.toString();
        }
        return body.toString();
    }

    static void appendQuery(StringBuilder sb, String name, String value) {
        if (sb.length() > 0) {
            sb.append('&');
        }
        sb.append(encodeComponent(name)).append('=').append(encodeComponent(value));
    }

    /**
     * RFC 3986 component encoding - {@link URLEncoder} is form encoding, so its {@code +} for space
     * is corrected.
     */
    static String encodeComponent(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8)
                .replace("+", "%20");
    }

    static String stringify(JsonNode value) {
        if (value == null || value.isNull()) {
            return "";
        }
        return value.isValueNode() ? value.asText() : value.toString();
    }

    static JsonNode tryParseJson(String text, String contentType, boolean truncated) {
        if (truncated || text.isBlank()) {
            return null;
        }
        String trimmed = text.strip();
        boolean looksJson =
                contentType.toLowerCase(Locale.ROOT).contains("json")
                        || trimmed.startsWith("{")
                        || trimmed.startsWith("[");
        if (!looksJson) {
            return null;
        }
        try {
            return MAPPER.readTree(trimmed);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    static byte[] readCapped(InputStream in, int maxBytes) throws IOException {
        try (in) {
            byte[] buffer = new byte[8_192];
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            int read;
            while ((read = in.read(buffer)) != -1) {
                int room = maxBytes - out.size();
                if (room <= 0) {
                    break;
                }
                out.write(buffer, 0, Math.min(read, room));
                if (out.size() >= maxBytes) {
                    break;
                }
            }
            return out.toByteArray();
        }
    }

    static void closeQuietly(PinnedResponse response) {
        response.close();
    }

    static String errorJson(String message, String... secrets) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("error", redact(message, secrets));
        return node.toString();
    }

    /**
     * Literal-match scrub of the credential(s) - the stored secret AND, for OAuth2 sources, the
     * minted access token, which is itself a bearer credential. A guard against a response or error
     * ACCIDENTALLY echoing the key (an API that reflects its request headers in a 400, say) - never
     * a guarantee against an API that wants to leak it, which nothing at this layer could provide.
     */
    static String redact(String text, String... secrets) {
        if (text == null || secrets == null) {
            return text;
        }
        for (String secret : secrets) {
            if (secret != null && secret.length() >= 4) {
                text = text.replace(secret, REDACTED);
            }
        }
        return text;
    }

    /** Host of an absolute URL, or null when it has none (SEC-006 destination binding). */
    static String hostOf(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        try {
            return URI.create(url.strip()).getHost();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private OpenApiHttpSupport() {}
}
