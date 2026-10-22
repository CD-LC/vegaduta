package com.agenticai.core.omnichannel.imessage;

import com.agenticai.core.omnichannel.ChannelConfigurationException;
import com.agenticai.core.omnichannel.ChannelDeliveryException;
import com.agenticai.core.security.net.SafeHttpClients;
import com.agenticai.core.security.net.SsrfBlockedException;
import com.agenticai.core.security.net.SsrfGuard;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.classic.methods.HttpUriRequestBase;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.io.entity.ByteArrayEntity;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.apache.hc.core5.util.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Client for the BlueBubbles server REST API ({@code /api/v1/...}), the macOS bridge that exposes
 * the Mac's Messages app. BlueBubbles authenticates every call with the server password as a {@code
 * password} query parameter, so a request URL is a credential: it is never logged, never put in an
 * exception message, and error text is scrubbed of the password before it leaves here.
 *
 * <p><b>SSRF.</b> The server URL is tenant-supplied and is nearly always a private address (a Mac
 * on the LAN, a tailnet name). Every call goes through {@link SsrfGuard}: {@link
 * SsrfGuard#validate(String)} before the request and a guarded {@link SafeHttpClients} client for
 * the connect itself, so a private host works only when the operator lists it in {@code
 * app.security.outbound.allowed-private-hosts}. A tenant cannot point this at the cloud metadata
 * endpoint or an internal service by pasting a URL.
 */
@Component
public class ImessageApiClient {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration VALIDATE_TIMEOUT = Duration.ofSeconds(8);
    private static final Duration UPLOAD_TIMEOUT = Duration.ofSeconds(90);
    private static final int MAX_BODY_BYTES = 4 * 1024 * 1024;
    private static final int MAX_ERROR_CHARS = 200;

    private final ObjectMapper objectMapper;
    private final Supplier<SsrfGuard> guardSupplier;
    private final Object clientLock = new Object();
    private SsrfGuard clientGuard;
    private CloseableHttpClient client;

    @Autowired
    public ImessageApiClient(ObjectMapper objectMapper) {
        this(objectMapper, SsrfGuard::defaultGuard);
    }

    /** Test seam: a fixed guard (for a fake server on a loopback address). */
    ImessageApiClient(ObjectMapper objectMapper, Supplier<SsrfGuard> guardSupplier) {
        this.objectMapper = objectMapper;
        this.guardSupplier = guardSupplier;
    }

    /**
     * What the server reports about itself; shown on the Channels card and proves URL + password
     * work.
     */
    public record ServerInfo(
            String serverVersion, String osVersion, boolean privateApi, boolean helperConnected) {}

    /** Validates the URL and password with a real call - "prove it before persisting". */
    public ServerInfo fetchServerInfo(String serverUrl, String password) {
        JsonNode data =
                call(serverUrl, password, "GET", "/api/v1/server/info", null, VALIDATE_TIMEOUT);
        return new ServerInfo(
                data.path("server_version").asText(null),
                data.path("os_version").asText(null),
                data.path("private_api").asBoolean(false),
                data.path("helper_connected").asBoolean(false));
    }

    /**
     * Sends a text. {@code to} is a chat GUID ({@code iMessage;-;+15550100100}, groups use {@code
     * ;+;}) or a bare phone/email, which is mapped to the direct chat GUID and, when that chat does
     * not exist yet, created.
     *
     * @return a short, human-readable log line describing what happened (no recipient detail).
     */
    public String sendText(String serverUrl, String password, String to, String text) {
        String chatGuid = toChatGuid(to);
        try {
            call(
                    serverUrl,
                    password,
                    "POST",
                    "/api/v1/message/text",
                    textBody(chatGuid, text),
                    DEFAULT_TIMEOUT);
            return "Sent iMessage to chat " + ImessageAddress.mask(chatGuid) + ".";
        } catch (ImessageHttpException e) {
            if (!isBareHandle(to)
                    || e.status < 400
                    || e.status >= 500
                    || e.status == 401
                    || e.status == 403) {
                throw new ChannelDeliveryException("imessage", e.getMessage());
            }
            // No chat with this handle yet: ask BlueBubbles to open one with the first message.
            return createChat(serverUrl, password, ImessageAddress.normalize(to), text);
        } catch (ChannelConfigurationException e) {
            throw new ChannelDeliveryException("imessage", e.getMessage());
        }
    }

    private String createChat(String serverUrl, String password, String address, String text) {
        if (address == null) {
            throw new ChannelDeliveryException("imessage", "Not a valid iMessage recipient.");
        }
        ObjectNode body = objectMapper.createObjectNode();
        body.putArray("addresses").add(address);
        body.put("message", text);
        body.put("method", "apple-script");
        try {
            call(serverUrl, password, "POST", "/api/v1/chat/new", body, DEFAULT_TIMEOUT);
            return "Started an iMessage chat with " + ImessageAddress.mask(address) + ".";
        } catch (ChannelConfigurationException e) {
            throw new ChannelDeliveryException("imessage", e.getMessage());
        }
    }

    /** Sends one attachment (with an optional caption as a following text) to a chat. */
    public void sendAttachment(
            String serverUrl,
            String password,
            String to,
            byte[] bytes,
            String contentType,
            String filename,
            String caption) {
        String chatGuid = toChatGuid(to);
        String boundary = "vegaduta" + UUID.randomUUID().toString().replace("-", "");
        ByteArrayOutputStream out = new ByteArrayOutputStream(bytes.length + 512);
        try {
            part(out, boundary, "chatGuid", chatGuid);
            part(out, boundary, "tempGuid", "temp-" + UUID.randomUUID());
            part(out, boundary, "name", safeFilename(filename));
            part(out, boundary, "method", "apple-script");
            out.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
            out.write(
                    ("Content-Disposition: form-data; name=\"attachment\"; filename=\""
                                    + safeFilename(filename)
                                    + "\"\r\nContent-Type: "
                                    + safeContentType(contentType)
                                    + "\r\n\r\n")
                            .getBytes(StandardCharsets.UTF_8));
            out.write(bytes);
            out.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new ChannelDeliveryException("imessage", "Could not prepare the attachment.");
        }
        try {
            callRaw(
                    serverUrl,
                    password,
                    "POST",
                    "/api/v1/message/attachment",
                    new ByteArrayEntity(
                            out.toByteArray(),
                            ContentType.parse("multipart/form-data; boundary=" + boundary)),
                    UPLOAD_TIMEOUT);
        } catch (ChannelConfigurationException e) {
            throw new ChannelDeliveryException("imessage", e.getMessage());
        }
        if (caption != null && !caption.isBlank()) {
            sendText(serverUrl, password, to, caption);
        }
    }

    /**
     * Messages newer than {@code afterMs} (epoch ms), oldest first, each with its chat and handle
     * embedded. Used by the polling fallback.
     */
    public List<JsonNode> queryMessages(
            String serverUrl, String password, long afterMs, int limit) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("limit", limit);
        body.put("offset", 0);
        body.put("sort", "ASC");
        body.put("after", afterMs);
        ArrayNode with = body.putArray("with");
        with.add("chat");
        with.add("handle");
        with.add("attachment");
        JsonNode data =
                call(serverUrl, password, "POST", "/api/v1/message/query", body, DEFAULT_TIMEOUT);
        List<JsonNode> out = new ArrayList<>();
        if (data.isArray()) {
            data.forEach(out::add);
        }
        return out;
    }

    /** Maps a bare handle to the direct chat GUID; a GUID (contains ';') is used as given. */
    static String toChatGuid(String to) {
        if (to == null || to.isBlank()) {
            throw new ChannelDeliveryException("imessage", "No iMessage recipient given.");
        }
        String trimmed = to.strip();
        if (trimmed.chars().anyMatch(ch -> ch < 0x20 || ch == 0x7f)) {
            // The GUID goes into a multipart part for attachments; a line break would forge parts.
            throw new ChannelDeliveryException("imessage", "Not a valid iMessage recipient.");
        }
        if (trimmed.indexOf(';') >= 0) {
            return trimmed;
        }
        String address = ImessageAddress.normalize(trimmed);
        if (address == null) {
            throw new ChannelDeliveryException("imessage", "Not a valid iMessage recipient.");
        }
        return "iMessage;-;" + address;
    }

    private static boolean isBareHandle(String to) {
        return to != null && to.indexOf(';') < 0;
    }

    private ObjectNode textBody(String chatGuid, String text) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("chatGuid", chatGuid);
        body.put("tempGuid", "temp-" + UUID.randomUUID());
        body.put("message", text);
        body.put("method", "apple-script");
        return body;
    }

    private static void part(ByteArrayOutputStream out, String boundary, String name, String value)
            throws IOException {
        out.write(
                ("--"
                                + boundary
                                + "\r\nContent-Disposition: form-data; name=\""
                                + name
                                + "\"\r\n\r\n"
                                + value
                                + "\r\n")
                        .getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Header value built from caller text: anything but a plain {@code type/subtype} becomes
     * octet-stream.
     */
    private static String safeContentType(String contentType) {
        return contentType != null
                        && contentType
                                .strip()
                                .matches(
                                        "[A-Za-z0-9.+-]+/[A-Za-z0-9.+-]+(\\s*;\\s*[A-Za-z0-9=._ \"-]+)?")
                ? contentType.strip()
                : "application/octet-stream";
    }

    private static String safeFilename(String filename) {
        String name = filename == null || filename.isBlank() ? "attachment" : filename;
        return name.replaceAll("[\\r\\n\"\\\\/]", "_");
    }

    // ---------------------------------------------------------------- transport

    /**
     * Thrown for a non-2xx answer; carries the HTTP status so callers can tell "no such chat" from
     * "bad password".
     */
    private static final class ImessageHttpException extends ChannelConfigurationException {
        private static final long serialVersionUID = 1L;
        final int status;

        ImessageHttpException(int status, String message) {
            super("imessage", message);
            this.status = status;
        }
    }

    private JsonNode call(
            String serverUrl,
            String password,
            String method,
            String path,
            JsonNode body,
            Duration timeout) {
        StringEntity entity = null;
        if (body != null) {
            entity = new StringEntity(body.toString(), ContentType.APPLICATION_JSON);
        }
        return callRaw(serverUrl, password, method, path, entity, timeout);
    }

    private JsonNode callRaw(
            String serverUrl,
            String password,
            String method,
            String path,
            org.apache.hc.core5.http.HttpEntity entity,
            Duration timeout) {
        SsrfGuard guard = guardSupplier.get();
        try {
            guard.validate(serverUrl);
        } catch (SsrfBlockedException e) {
            throw new ChannelConfigurationException(
                    "imessage",
                    "This server address is not allowed: "
                            + e.getMessage()
                            + ". A BlueBubbles server on a private network must be listed by the operator"
                            + " in app.security.outbound.allowed-private-hosts.");
        }
        URI uri = buildUri(serverUrl, path, password);
        HttpUriRequestBase request = "GET".equals(method) ? new HttpGet(uri) : new HttpPost(uri);
        request.setConfig(
                RequestConfig.custom()
                        .setResponseTimeout(Timeout.of(timeout))
                        .setConnectionRequestTimeout(Timeout.of(CONNECT_TIMEOUT))
                        .build());
        if (entity != null) {
            request.setEntity(entity);
        }
        try {
            return clientFor(guard)
                    .execute(
                            request,
                            response -> {
                                byte[] raw =
                                        response.getEntity() == null
                                                ? new byte[0]
                                                : response.getEntity()
                                                        .getContent()
                                                        .readNBytes(MAX_BODY_BYTES);
                                JsonNode node;
                                try {
                                    node = objectMapper.readTree(raw);
                                } catch (Exception e) {
                                    node = null;
                                }
                                int code = response.getCode();
                                if (code < 200 || code >= 300 || node == null) {
                                    throw new ImessageHttpException(
                                            code, describeFailure(code, node, password));
                                }
                                return node.path("data");
                            });
        } catch (ChannelConfigurationException e) {
            throw e;
        } catch (IOException e) {
            // Deliberately the exception CLASS only: messages from the HTTP stack can echo the
            // request line.
            throw new ChannelConfigurationException(
                    "imessage",
                    "Could not reach the BlueBubbles server ("
                            + e.getClass().getSimpleName()
                            + "). Check the address, that the Mac is awake and BlueBubbles is running.");
        }
    }

    private static URI buildUri(String serverUrl, String path, String password) {
        String base = serverUrl.strip();
        if (base.indexOf('?') >= 0 || base.indexOf('#') >= 0) {
            // A query or fragment would swallow the API path (and the password), sending it to the
            // wrong place.
            throw new ChannelConfigurationException(
                    "imessage",
                    "The server address must be just the base URL, for example http://192.168.1.20:1234");
        }
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return URI.create(
                base + path + "?password=" + URLEncoder.encode(password, StandardCharsets.UTF_8));
    }

    private static String describeFailure(int code, JsonNode node, String password) {
        if (code == 401 || code == 403) {
            return "The BlueBubbles server rejected the password.";
        }
        String detail = null;
        if (node != null) {
            detail = node.path("error").path("error").asText(null);
            if (detail == null || detail.isBlank()) {
                detail = node.path("message").asText(null);
            }
        }
        if (detail == null || detail.isBlank()) {
            return "The BlueBubbles server answered HTTP " + code + ".";
        }
        String scrubbed =
                password == null || password.isEmpty() ? detail : detail.replace(password, "***");
        if (scrubbed.length() > MAX_ERROR_CHARS) {
            scrubbed = scrubbed.substring(0, MAX_ERROR_CHARS);
        }
        return "The BlueBubbles server answered HTTP " + code + ": " + scrubbed;
    }

    private CloseableHttpClient clientFor(SsrfGuard guard) {
        synchronized (clientLock) {
            if (client == null || clientGuard != guard) {
                CloseableHttpClient old = client;
                client = SafeHttpClients.create(guard, CONNECT_TIMEOUT, DEFAULT_TIMEOUT, false);
                clientGuard = guard;
                if (old != null) {
                    try {
                        old.close();
                    } catch (IOException ignored) {
                        // closing a replaced pool is best-effort
                    }
                }
            }
            return client;
        }
    }
}
