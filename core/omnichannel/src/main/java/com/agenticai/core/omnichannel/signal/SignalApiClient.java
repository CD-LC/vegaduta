package com.agenticai.core.omnichannel.signal;

import com.agenticai.core.security.net.SafeHttpClients;
import com.agenticai.core.security.net.SafeWebSockets;
import com.agenticai.core.security.net.SsrfBlockedException;
import com.agenticai.core.security.net.SsrfGuard;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import org.apache.hc.client5.http.classic.methods.HttpUriRequestBase;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.apache.hc.core5.util.Timeout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Client for the signal-cli REST API (bbernhard/signal-cli-rest-api) the operator runs. Every REST
 * call goes through an address-pinned, proxy-free, redirect-free client built on {@link SsrfGuard}:
 * a private signal-cli host works only when the operator lists it in {@code
 * app.security.outbound.allowed-private-hosts}, exactly like a self-hosted Ollama or MCP server.
 * The URL is also re-validated before every call, which enforces the {@code host:port} form of that
 * list (the resolver only sees the host).
 *
 * <p>The optional bearer token (for a reverse proxy in front of signal-cli) is sent only over
 * https, or to a host the operator allow-listed. It is never part of a URL, a log line or an
 * exception message.
 *
 * <p>Two ways to receive, chosen by the caller from {@link #about}: {@link #receive} polls {@code
 * GET /v1/receive/{number}} (signal-cli "normal" and "native" modes), {@link #openStream} holds the
 * websocket the same path becomes in "json-rpc" mode. The websocket uses the JDK client, which has
 * no pluggable resolver: the URL is validated just before connecting, which covers address ranges
 * but not a DNS answer that changes between that check and the connect.
 */
@Component
public class SignalApiClient {

    private static final Logger log = LoggerFactory.getLogger(SignalApiClient.class);

    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(20);

    /** Activation and probe run while someone waits on a spinner. */
    static final Duration VALIDATE_TIMEOUT = Duration.ofSeconds(8);

    private static final int MAX_RESPONSE_BYTES = 8 * 1024 * 1024;
    private static final int MAX_WS_FRAME_CHARS = 4 * 1024 * 1024;
    private static final int STREAM_QUEUE = 256;

    /**
     * Empty polls of silence before the stream probes the server: a dropped connection the JDK
     * never reported then surfaces through the ping write (or the answering RST) instead of leaving
     * the stream looking alive forever.
     */
    private static final int PING_AFTER_EMPTY_POLLS = 3;

    private final ObjectMapper objectMapper;
    private final Supplier<SsrfGuard> guardSupplier;
    private CloseableHttpClient client;
    private SsrfGuard clientGuard;

    @Autowired
    public SignalApiClient(ObjectMapper objectMapper) {
        this(objectMapper, SsrfGuard::defaultGuard);
    }

    /** Test seam: a guard that admits the local fake signal-cli. */
    SignalApiClient(ObjectMapper objectMapper, Supplier<SsrfGuard> guardSupplier) {
        this.objectMapper = objectMapper;
        this.guardSupplier = guardSupplier;
    }

    /** What {@code GET /v1/about} says about the server. */
    public record About(String mode, String version) {
        /** json-rpc mode only offers receive as a websocket. */
        public boolean websocketReceive() {
            return "json-rpc".equalsIgnoreCase(mode);
        }
    }

    /** A file to send. */
    public record Attachment(byte[] bytes, String contentType, String filename) {}

    // ------------------------------------------------------------------ calls

    public About about(SignalConnection c) {
        JsonNode body = call(c, "GET", "/v1/about", null, VALIDATE_TIMEOUT);
        return new About(body.path("mode").asText(""), body.path("version").asText(""));
    }

    /** The numbers registered with this signal-cli. */
    public List<String> accounts(SignalConnection c) {
        JsonNode body = call(c, "GET", "/v1/accounts", null, VALIDATE_TIMEOUT);
        List<String> numbers = new ArrayList<>();
        for (JsonNode item : body) {
            String number = item.isTextual() ? item.asText() : item.path("number").asText("");
            if (!number.isBlank()) {
                numbers.add(number);
            }
        }
        return numbers;
    }

    /**
     * Long-poll for new messages (signal-cli "normal"/"native" mode). Attachments are not
     * downloaded: the agent is told an attachment exists, not given its bytes.
     */
    public JsonNode receive(SignalConnection c, int waitSeconds) {
        String path =
                "/v1/receive/"
                        + enc(c.accountNumber())
                        + "?timeout="
                        + waitSeconds
                        + "&ignore_attachments=true&ignore_stories=true";
        JsonNode body = call(c, "GET", path, null, Duration.ofSeconds(waitSeconds + 10L));
        return body.isArray() ? body : objectMapper.createArrayNode();
    }

    /**
     * Sends text and/or attachments. {@code recipient} is already in wire form (see {@link
     * SignalAddress#toRecipient}).
     */
    public void send(SignalConnection c, String recipient, String text, List<Attachment> files) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("message", text == null ? "" : text);
        body.put("number", c.accountNumber());
        body.putArray("recipients").add(recipient);
        if (files != null && !files.isEmpty()) {
            ArrayNode attachments = body.putArray("base64_attachments");
            for (Attachment file : files) {
                attachments.add(dataUri(file));
            }
        }
        try {
            call(c, "POST", "/v2/send", objectMapper.writeValueAsString(body), REQUEST_TIMEOUT);
        } catch (JsonProcessingException e) {
            throw new SignalApiException(
                    SignalApiException.IO, "Could not build the Signal send request.", e);
        }
    }

    /**
     * Shows "typing..." to the recipient. Best effort: a UX nicety, never a reason to fail a reply.
     */
    public void sendTypingIndicator(SignalConnection c, String recipient) {
        try {
            ObjectNode body = objectMapper.createObjectNode();
            body.put("recipient", recipient);
            call(
                    c,
                    "PUT",
                    "/v1/typing-indicator/" + enc(c.accountNumber()),
                    objectMapper.writeValueAsString(body),
                    Duration.ofSeconds(5));
        } catch (Exception e) {
            // Best effort only.
        }
    }

    // ----------------------------------------------------------- websocket

    /** Opens the json-rpc mode receive websocket. */
    public SignalStream openStream(SignalConnection c) {
        SsrfGuard.Target target = validate(c);
        URI base = target.uri();
        String scheme = "https".equalsIgnoreCase(base.getScheme()) ? "wss" : "ws";
        URI uri =
                URI.create(
                        scheme
                                + "://"
                                + base.getRawAuthority()
                                + trimTrailingSlash(base.getRawPath())
                                + "/v1/receive/"
                                + enc(c.accountNumber()));
        WsStream stream = new WsStream();
        uri = SafeWebSockets.pin(target, uri);
        HttpClient http = SafeWebSockets.client(CONNECT_TIMEOUT);
        WebSocket.Builder builder = http.newWebSocketBuilder().connectTimeout(CONNECT_TIMEOUT);
        if (c.hasAuthToken()) {
            builder.header("Authorization", "Bearer " + c.authToken());
        }
        stream.http = http;
        try {
            stream.socket =
                    builder.buildAsync(uri, stream)
                            .get(CONNECT_TIMEOUT.toSeconds() + 5, TimeUnit.SECONDS);
            return stream;
        } catch (InterruptedException e) {
            stream.close();
            Thread.currentThread().interrupt();
            throw new SignalApiException(
                    SignalApiException.IO, "Interrupted while connecting to signal-cli.", e);
        } catch (ExecutionException | TimeoutException e) {
            stream.close();
            Throwable cause =
                    e instanceof ExecutionException && e.getCause() != null ? e.getCause() : e;
            throw new SignalApiException(
                    SignalApiException.IO,
                    "Could not open the signal-cli receive websocket: "
                            + cause.getClass().getSimpleName(),
                    cause);
        }
    }

    private final class WsStream implements SignalStream, WebSocket.Listener {
        private final Object closed = new Object();
        private final BlockingQueue<Object> queue = new LinkedBlockingQueue<>(STREAM_QUEUE);
        private final StringBuilder partial = new StringBuilder();
        private final AtomicBoolean pingInFlight = new AtomicBoolean();
        private volatile boolean open = true;
        private volatile WebSocket socket;

        /**
         * One client per connection: released with the stream so reconnects do not pile up selector
         * threads.
         */
        private volatile HttpClient http;

        /**
         * Consecutive polls that saw nothing: resets on every frame, so a dead-but-silent
         * connection is probed after {@link #PING_AFTER_EMPTY_POLLS}.
         */
        private int emptyPolls;

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
        }

        @Override
        public java.util.concurrent.CompletionStage<?> onText(
                WebSocket webSocket, CharSequence data, boolean last) {
            partial.append(data);
            if (partial.length() > MAX_WS_FRAME_CHARS) {
                partial.setLength(0);
                markClosed();
                webSocket.abort();
                return null;
            }
            if (last) {
                String frame = partial.toString();
                partial.setLength(0);
                try {
                    queue.put(objectMapper.readTree(frame));
                } catch (JsonProcessingException e) {
                    log.debug("Ignoring a non-JSON Signal websocket frame");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    markClosed();
                    return null;
                }
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public java.util.concurrent.CompletionStage<?> onPing(
                WebSocket webSocket, ByteBuffer message) {
            // Control frames spend one listener demand credit like any message; keep the read
            // armed.
            webSocket.request(1);
            return null;
        }

        @Override
        public java.util.concurrent.CompletionStage<?> onPong(
                WebSocket webSocket, ByteBuffer message) {
            webSocket.request(1);
            return null;
        }

        @Override
        public java.util.concurrent.CompletionStage<?> onClose(
                WebSocket webSocket, int statusCode, String reason) {
            markClosed();
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            log.debug("Signal websocket error: {}", error.getClass().getSimpleName());
            markClosed();
        }

        private void markClosed() {
            open = false;
            queue.offer(closed);
        }

        @Override
        public JsonNode poll(Duration timeout) throws InterruptedException {
            Object next = queue.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (next == null) {
                if (open && ++emptyPolls >= PING_AFTER_EMPTY_POLLS) {
                    emptyPolls = 0;
                    ping();
                }
                return null;
            }
            emptyPolls = 0;
            if (next == closed) {
                queue.offer(closed); // stay closed for any later poll
                throw new SignalApiException(
                        SignalApiException.IO, "signal-cli closed the receive websocket.");
            }
            return (JsonNode) next;
        }

        /**
         * Probes an idle stream: the JDK websocket reports a dropped connection only through a
         * frame or a transport error, and both have been observed to go missing under CI load. A
         * ping forces the dead half of the connection to answer - the write fails or the peer's RST
         * reaches the armed read - and then {@link #onError} (or this completion) marks the stream
         * closed.
         */
        private void ping() {
            WebSocket s = socket;
            if (s == null || !pingInFlight.compareAndSet(false, true)) {
                return;
            }
            s.sendPing(ByteBuffer.allocate(0))
                    .whenComplete(
                            (r, e) -> {
                                pingInFlight.set(false);
                                if (e != null) {
                                    markClosed();
                                }
                            });
        }

        @Override
        public boolean isOpen() {
            return open;
        }

        @Override
        public void close() {
            open = false;
            WebSocket s = socket;
            if (s != null) {
                s.abort();
            }
            HttpClient client = http;
            if (client != null) {
                client.shutdownNow();
            }
        }
    }

    // --------------------------------------------------------------- plumbing

    private SsrfGuard.Target validate(SignalConnection c) {
        SsrfGuard.Target target;
        try {
            target = guardSupplier.get().validate(c.baseUrl());
        } catch (SsrfBlockedException e) {
            throw new SignalApiException(
                    SignalApiException.REFUSED,
                    "The Signal base URL was refused by the outbound request policy ("
                            + e.getMessage()
                            + "). If signal-cli runs on a private network, an operator must list its"
                            + " host in app.security.outbound.allowed-private-hosts.");
        }
        boolean clearText = !"https".equalsIgnoreCase(target.uri().getScheme());
        if (c.hasAuthToken() && clearText && !target.operatorAllowed()) {
            throw new SignalApiException(
                    SignalApiException.REFUSED,
                    "An auth token is only sent over https (or to a host the operator allow-listed).");
        }
        return target;
    }

    private synchronized CloseableHttpClient httpClient() {
        SsrfGuard guard = guardSupplier.get();
        if (client == null || guard != clientGuard) {
            closeQuietly(client);
            client = SafeHttpClients.create(guard, CONNECT_TIMEOUT, REQUEST_TIMEOUT, false);
            clientGuard = guard;
        }
        return client;
    }

    private static void closeQuietly(CloseableHttpClient c) {
        if (c != null) {
            try {
                c.close();
            } catch (IOException e) {
                // nothing to do
            }
        }
    }

    private record Raw(int status, String body) {}

    private JsonNode call(
            SignalConnection c, String method, String path, String jsonBody, Duration timeout) {
        validate(c);
        HttpUriRequestBase request =
                new HttpUriRequestBase(method, URI.create(trimTrailingSlash(c.baseUrl()) + path));
        request.setHeader("Accept", "application/json");
        if (c.hasAuthToken()) {
            request.setHeader("Authorization", "Bearer " + c.authToken());
        }
        if (jsonBody != null) {
            request.setEntity(new StringEntity(jsonBody, ContentType.APPLICATION_JSON));
        }
        request.setConfig(RequestConfig.custom().setResponseTimeout(Timeout.of(timeout)).build());
        Raw raw;
        try {
            raw =
                    httpClient()
                            .execute(
                                    request,
                                    response -> {
                                        String body = "";
                                        if (response.getEntity() != null) {
                                            try (InputStream in =
                                                    response.getEntity().getContent()) {
                                                byte[] bytes =
                                                        in.readNBytes(MAX_RESPONSE_BYTES + 1);
                                                if (bytes.length > MAX_RESPONSE_BYTES) {
                                                    throw new IOException("response too large");
                                                }
                                                body = new String(bytes, StandardCharsets.UTF_8);
                                            }
                                        }
                                        return new Raw(response.getCode(), body);
                                    });
        } catch (IOException e) {
            throw new SignalApiException(
                    SignalApiException.IO,
                    "Could not reach signal-cli: " + e.getClass().getSimpleName() + describe(e),
                    e);
        }
        if (raw.status() < 200 || raw.status() >= 300) {
            throw new SignalApiException(
                    raw.status(),
                    "signal-cli answered HTTP " + raw.status() + errorText(raw.body()));
        }
        if (raw.body().isBlank()) {
            return objectMapper.nullNode();
        }
        try {
            return objectMapper.readTree(raw.body());
        } catch (JsonProcessingException e) {
            throw new SignalApiException(
                    raw.status(), "signal-cli sent a response that is not JSON.", e);
        }
    }

    private String errorText(String body) {
        try {
            String error = objectMapper.readTree(body).path("error").asText("");
            if (!error.isBlank()) {
                return ": " + (error.length() > 200 ? error.substring(0, 200) : error);
            }
        } catch (Exception e) {
            // not JSON: say nothing about the body
        }
        return "";
    }

    private static String describe(IOException e) {
        String message = e.getMessage();
        return message == null || message.isBlank()
                ? ""
                : " (" + (message.length() > 120 ? message.substring(0, 120) : message) + ")";
    }

    /** {@code data:<type>;filename=<name>;base64,<data>}, the form signal-cli-rest-api takes. */
    static String dataUri(Attachment file) {
        String type =
                file.contentType() == null || file.contentType().isBlank()
                        ? "application/octet-stream"
                        : file.contentType().replaceAll("[^A-Za-z0-9.+/_-]", "");
        String name =
                file.filename() == null ? "" : file.filename().replaceAll("[^A-Za-z0-9._ -]", "_");
        return "data:"
                + type.toLowerCase(Locale.ROOT)
                + (name.isBlank() ? "" : ";filename=" + name)
                + ";base64,"
                + Base64.getEncoder().encodeToString(file.bytes());
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String trimTrailingSlash(String value) {
        String v = value == null ? "" : value;
        while (v.endsWith("/")) {
            v = v.substring(0, v.length() - 1);
        }
        return v;
    }
}
