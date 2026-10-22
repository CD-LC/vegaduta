package com.agenticai.core.omnichannel.matrix;

import com.agenticai.core.security.net.SafeHttpClients;
import com.agenticai.core.security.net.SsrfBlockedException;
import com.agenticai.core.security.net.SsrfGuard;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.hc.client5.http.classic.methods.HttpUriRequestBase;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.io.entity.ByteArrayEntity;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.apache.hc.core5.util.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Matrix Client-Server API over the platform's guarded HTTP client. The homeserver is chosen by the
 * operator, so every request is checked by {@link SsrfGuard} (operator allow-list for private
 * homeservers, internal ranges refused, DNS pinned to the checked addresses, redirects off) and the
 * access token only ever travels in the {@code Authorization} header: it is never part of a URL, a
 * log line or an exception message.
 *
 * <p>Plain http is accepted only for a host the operator listed in {@code
 * app.security.outbound.allowed-private-hosts} (a homeserver on the same laptop or LAN); every
 * public homeserver must be https.
 *
 * <p>End-to-end encryption is not implemented here, by design (see {@code docs} for the channel).
 */
@Component
public class MatrixApiClient {

    /** Where to call and as whom. The token never appears in {@code toString}. */
    public record Endpoint(String homeserverUrl, String accessToken) {
        @Override
        public String toString() {
            return "Endpoint[" + homeserverUrl + "]";
        }
    }

    public record WhoAmI(String userId, String deviceId) {}

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(20);
    private static final int MAX_JSON_RESPONSE_BYTES = 16 * 1024 * 1024;

    /** Matrix caps an event at 64 KiB; stay well below it so multi-byte text fits. */
    static final int MAX_BODY_CHARS = 16_000;

    static final String SYNC_FILTER =
            "{\"presence\":{\"types\":[]},\"account_data\":{\"types\":[]},"
                    + "\"room\":{\"ephemeral\":{\"types\":[]},\"account_data\":{\"types\":[]},"
                    + "\"state\":{\"types\":[\"m.room.encryption\"],\"lazy_load_members\":true},"
                    + "\"timeline\":{\"limit\":50,\"types\":[\"m.room.message\","
                    + "\"m.room.encrypted\",\"m.room.encryption\"]}}}";

    private static final Pattern MXC =
            Pattern.compile("^mxc://([A-Za-z0-9.:\\-\\[\\]]+)/([A-Za-z0-9_\\-]+)$");

    private final ObjectMapper mapper;
    private final Supplier<SsrfGuard> guardSupplier;
    private volatile SsrfGuard clientGuard;
    private volatile CloseableHttpClient client;

    @Autowired
    public MatrixApiClient(ObjectMapper mapper) {
        this(mapper, SsrfGuard::defaultGuard);
    }

    /** Test seam: a guard that admits a local fake homeserver. */
    MatrixApiClient(ObjectMapper mapper, Supplier<SsrfGuard> guardSupplier) {
        this.mapper = mapper;
        this.guardSupplier = guardSupplier;
    }

    // ---------------------------------------------------------------- API calls

    /** Proves the token and tells us which account it belongs to. */
    public WhoAmI whoAmI(Endpoint ep) {
        JsonNode node =
                get(
                        ep,
                        "/_matrix/client/v3/account/whoami",
                        "",
                        DEFAULT_TIMEOUT,
                        MAX_JSON_RESPONSE_BYTES);
        String userId = node.path("user_id").asString(null);
        if (userId == null || userId.isBlank()) {
            throw new MatrixApiException(
                    200, "M_UNKNOWN", "The homeserver did not say who this token belongs to");
        }
        return new WhoAmI(userId, node.path("device_id").asString(null));
    }

    /**
     * Long-polls {@code /sync}. {@code since} is null for the first call (initial sync). The
     * homeserver holds the request up to {@code timeoutMs}.
     */
    public JsonNode sync(Endpoint ep, String since, int timeoutMs) {
        StringBuilder query = new StringBuilder("?timeout=").append(Math.max(0, timeoutMs));
        query.append("&filter=").append(encode(SYNC_FILTER));
        if (since != null && !since.isBlank()) {
            query.append("&since=").append(encode(since));
        }
        Duration timeout = Duration.ofMillis(Math.max(0, timeoutMs) + 20_000L);
        return get(
                ep, "/_matrix/client/v3/sync", query.toString(), timeout, MAX_JSON_RESPONSE_BYTES);
    }

    public void joinRoom(Endpoint ep, String roomId) {
        post(ep, "/_matrix/client/v3/rooms/" + encode(roomId) + "/join", "{}");
    }

    /**
     * Number of joined members; used to tell a DM from a group room when {@code /sync} gave no
     * summary.
     */
    public int joinedMemberCount(Endpoint ep, String roomId) {
        JsonNode node =
                get(
                        ep,
                        "/_matrix/client/v3/rooms/" + encode(roomId) + "/joined_members",
                        "",
                        DEFAULT_TIMEOUT,
                        MAX_JSON_RESPONSE_BYTES);
        return node.path("joined").size();
    }

    /**
     * Sends plain text, split into several events when it is longer than a single event allows.
     * {@code txnId} makes a retry idempotent on the homeserver (it de-duplicates per access token).
     *
     * @return the event id of the last event sent
     */
    public String sendText(
            Endpoint ep,
            String roomId,
            String txnId,
            String body,
            String replyToEventId,
            String threadRootEventId) {
        String text = body == null ? "" : body;
        String lastEventId = null;
        int index = 0;
        int offset = 0;
        do {
            int end = Math.min(text.length(), offset + MAX_BODY_CHARS);
            if (end < text.length() && Character.isHighSurrogate(text.charAt(end - 1))) {
                end--;
            }
            Map<String, Object> content =
                    textContent(text.substring(offset, end), replyToEventId, threadRootEventId);
            lastEventId = sendEvent(ep, roomId, index == 0 ? txnId : txnId + "-" + index, content);
            offset = end;
            index++;
        } while (offset < text.length());
        return lastEventId;
    }

    /**
     * Uploads {@code bytes} and posts them to the room as an m.file/m.image/m.audio/m.video event.
     */
    public String sendFile(
            Endpoint ep,
            String roomId,
            String txnId,
            String filename,
            String contentType,
            byte[] bytes,
            String caption,
            String replyToEventId,
            String threadRootEventId) {
        String mime =
                contentType == null || contentType.isBlank()
                        ? "application/octet-stream"
                        : contentType;
        String name = filename == null || filename.isBlank() ? "file" : filename;
        String mxc = uploadMedia(ep, name, mime, bytes);
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("msgtype", msgtypeFor(mime));
        content.put("body", caption != null && !caption.isBlank() ? caption : name);
        content.put("filename", name);
        content.put("url", mxc);
        content.put("info", Map.of("mimetype", mime, "size", bytes.length));
        addRelation(content, replyToEventId, threadRootEventId);
        return sendEvent(ep, roomId, txnId, content);
    }

    public String uploadMedia(Endpoint ep, String filename, String contentType, byte[] bytes) {
        ContentType type;
        try {
            type = ContentType.parse(contentType);
        } catch (RuntimeException e) {
            type = ContentType.APPLICATION_OCTET_STREAM;
        }
        JsonNode node =
                execute(
                        ep,
                        "POST",
                        new ByteArrayEntity(
                                bytes, type == null ? ContentType.APPLICATION_OCTET_STREAM : type),
                        "/_matrix/media/v3/upload",
                        "?filename=" + encode(filename),
                        Duration.ofSeconds(120),
                        MAX_JSON_RESPONSE_BYTES);
        String uri = node.path("content_uri").asString(null);
        if (uri == null || !MXC.matcher(uri).matches()) {
            throw new MatrixApiException(
                    200, "M_UNKNOWN", "The homeserver did not return a media URI");
        }
        return uri;
    }

    /**
     * Downloads an {@code mxc://} file with the bot's token (authenticated media, falling back to
     * the legacy endpoint on a homeserver that predates it). Larger than {@code maxBytes} is
     * refused.
     */
    public byte[] downloadMedia(Endpoint ep, String mxcUri, int maxBytes) {
        Matcher m = mxcUri == null ? null : MXC.matcher(mxcUri);
        if (m == null || !m.matches()) {
            throw new MatrixApiException(-1, "M_INVALID_PARAM", "Not a Matrix media URI");
        }
        String tail = encode(m.group(1)) + "/" + encode(m.group(2));
        try {
            return getBytes(ep, "/_matrix/client/v1/media/download/" + tail, maxBytes);
        } catch (MatrixApiException e) {
            if (e.status() == 404 || e.status() == 400) {
                return getBytes(ep, "/_matrix/media/v3/download/" + tail, maxBytes);
            }
            throw e;
        }
    }

    /** Best-effort "typing..." indicator; never throws. */
    public void sendTyping(Endpoint ep, String roomId, String botUserId) {
        try {
            execute(
                    ep,
                    "PUT",
                    new StringEntity(
                            "{\"typing\":true,\"timeout\":15000}", ContentType.APPLICATION_JSON),
                    "/_matrix/client/v3/rooms/" + encode(roomId) + "/typing/" + encode(botUserId),
                    "",
                    Duration.ofSeconds(5),
                    1024 * 1024);
        } catch (RuntimeException e) {
            // pure UX nicety
        }
    }

    // ------------------------------------------------------------- content builders

    static Map<String, Object> textContent(
            String body, String replyToEventId, String threadRootEventId) {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("msgtype", "m.text");
        content.put("body", body);
        addRelation(content, replyToEventId, threadRootEventId);
        return content;
    }

    /**
     * A thread reply carries the thread relation (and, as the spec's fallback, an in_reply_to for
     * clients without threads); a plain reply carries only in_reply_to.
     */
    static void addRelation(
            Map<String, Object> content, String replyToEventId, String threadRootEventId) {
        boolean hasReply = replyToEventId != null && !replyToEventId.isBlank();
        boolean hasThread = threadRootEventId != null && !threadRootEventId.isBlank();
        if (hasThread) {
            Map<String, Object> relation = new LinkedHashMap<>();
            relation.put("rel_type", "m.thread");
            relation.put("event_id", threadRootEventId);
            relation.put("is_falling_back", !hasReply);
            relation.put(
                    "m.in_reply_to",
                    Map.of("event_id", hasReply ? replyToEventId : threadRootEventId));
            content.put("m.relates_to", relation);
        } else if (hasReply) {
            content.put(
                    "m.relates_to", Map.of("m.in_reply_to", Map.of("event_id", replyToEventId)));
        }
    }

    static String msgtypeFor(String mime) {
        String m = mime == null ? "" : mime.toLowerCase(Locale.ROOT);
        if (m.startsWith("image/")) {
            return "m.image";
        }
        if (m.startsWith("audio/")) {
            return "m.audio";
        }
        if (m.startsWith("video/")) {
            return "m.video";
        }
        return "m.file";
    }

    // -------------------------------------------------------------------- plumbing

    private String sendEvent(
            Endpoint ep, String roomId, String txnId, Map<String, Object> content) {
        String json;
        try {
            json = mapper.writeValueAsString(content);
        } catch (JacksonException e) {
            throw new MatrixApiException(-1, "M_BAD_JSON", "Could not serialise the Matrix event");
        }
        JsonNode node =
                execute(
                        ep,
                        "PUT",
                        new StringEntity(json, ContentType.APPLICATION_JSON),
                        "/_matrix/client/v3/rooms/"
                                + encode(roomId)
                                + "/send/m.room.message/"
                                + encode(txnId),
                        "",
                        DEFAULT_TIMEOUT,
                        MAX_JSON_RESPONSE_BYTES);
        return node.path("event_id").asString(null);
    }

    private JsonNode get(Endpoint ep, String path, String query, Duration timeout, int maxBytes) {
        return execute(ep, "GET", null, path, query, timeout, maxBytes);
    }

    private JsonNode post(Endpoint ep, String path, String jsonBody) {
        return execute(
                ep,
                "POST",
                new StringEntity(jsonBody, ContentType.APPLICATION_JSON),
                path,
                "",
                DEFAULT_TIMEOUT,
                MAX_JSON_RESPONSE_BYTES);
    }

    private byte[] getBytes(Endpoint ep, String path, int maxBytes) {
        return send(ep, "GET", null, path, "", Duration.ofSeconds(60), maxBytes).body();
    }

    private JsonNode execute(
            Endpoint ep,
            String method,
            HttpEntity entity,
            String path,
            String query,
            Duration timeout,
            int maxBytes) {
        Reply reply = send(ep, method, entity, path, query, timeout, maxBytes);
        try {
            JsonNode node = mapper.readTree(reply.body());
            return node == null ? mapper.createObjectNode() : node;
        } catch (JacksonException e) {
            throw new MatrixApiException(
                    reply.status(),
                    "M_NOT_JSON",
                    "The homeserver answered with something that is not JSON");
        }
    }

    private record Reply(int status, byte[] body) {}

    private Reply send(
            Endpoint ep,
            String method,
            HttpEntity entity,
            String path,
            String query,
            Duration timeout,
            int maxBytes) {
        SsrfGuard guard = guardSupplier.get();
        String base = trimTrailingSlash(ep.homeserverUrl());
        String url = base + path + query;
        HttpUriRequestBase request;
        try {
            SsrfGuard.Target target = guard.validate(url);
            if (!"https".equalsIgnoreCase(target.uri().getScheme()) && !target.operatorAllowed()) {
                throw new MatrixApiException(
                        -1,
                        "M_INSECURE_HOMESERVER",
                        "The homeserver URL must use https (plain http is only allowed for hosts"
                                + " listed in app.security.outbound.allowed-private-hosts)");
            }
            request = new HttpUriRequestBase(method, target.uri());
        } catch (SsrfBlockedException e) {
            throw new MatrixApiException(
                    -1, "M_SSRF_BLOCKED", "Homeserver address refused: " + e.getMessage());
        }
        if (entity != null) {
            request.setEntity(entity);
        }
        request.setHeader("Authorization", "Bearer " + ep.accessToken());
        request.setHeader("Accept", "application/json");
        request.setConfig(
                RequestConfig.custom()
                        .setResponseTimeout(Timeout.of(timeout))
                        .setConnectionRequestTimeout(Timeout.of(CONNECT_TIMEOUT))
                        .build());
        try {
            return httpClient(guard)
                    .execute(
                            request,
                            response -> {
                                int status = response.getCode();
                                HttpEntity responseEntity = response.getEntity();
                                byte[] bytes = new byte[0];
                                if (responseEntity != null) {
                                    try (var in = responseEntity.getContent()) {
                                        bytes = in.readNBytes(maxBytes + 1);
                                    }
                                }
                                if (bytes.length > maxBytes) {
                                    throw new MatrixApiException(
                                            status,
                                            "M_TOO_LARGE",
                                            "The homeserver response is too large");
                                }
                                if (status < 200 || status >= 300) {
                                    throw errorFrom(status, bytes);
                                }
                                return new Reply(status, bytes);
                            });
        } catch (MatrixApiException e) {
            throw e;
        } catch (IOException e) {
            // Class name only: the message of a connect failure can carry the resolved address.
            throw new MatrixApiException(
                    -1,
                    "M_NETWORK",
                    "Could not reach the homeserver (" + e.getClass().getSimpleName() + ")",
                    0L,
                    e);
        }
    }

    private MatrixApiException errorFrom(int status, byte[] body) {
        String errcode = null;
        String error = null;
        long retryAfter = 0L;
        try {
            JsonNode node = mapper.readTree(body);
            errcode = node.path("errcode").asString(null);
            error = node.path("error").asString(null);
            retryAfter = node.path("retry_after_ms").asLong(0L);
        } catch (RuntimeException ignored) {
            // not JSON: fall back to the status
        }
        String message =
                "Matrix homeserver answered HTTP "
                        + status
                        + (errcode != null ? " " + errcode : "");
        if (error != null && !error.isBlank()) {
            message += ": " + (error.length() > 300 ? error.substring(0, 300) : error);
        }
        return new MatrixApiException(status, errcode, message, retryAfter, null);
    }

    private CloseableHttpClient httpClient(SsrfGuard guard) {
        CloseableHttpClient current = client;
        if (current != null && clientGuard == guard) {
            return current;
        }
        synchronized (this) {
            if (client == null || clientGuard != guard) {
                CloseableHttpClient old = client;
                client = SafeHttpClients.create(guard, CONNECT_TIMEOUT, DEFAULT_TIMEOUT, false);
                clientGuard = guard;
                if (old != null) {
                    try {
                        old.close();
                    } catch (IOException ignored) {
                        // best effort
                    }
                }
            }
            return client;
        }
    }

    private static String trimTrailingSlash(String url) {
        String u = url == null ? "" : url.strip();
        while (u.endsWith("/")) {
            u = u.substring(0, u.length() - 1);
        }
        return u;
    }

    static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
