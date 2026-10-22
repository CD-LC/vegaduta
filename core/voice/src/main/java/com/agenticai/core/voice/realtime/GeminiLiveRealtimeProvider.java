package com.agenticai.core.voice.realtime;

import com.agenticai.core.json.JsonMappers;
import com.agenticai.core.security.edition.ConditionalOnEditionFeature;
import com.agenticai.core.security.edition.EditionFeature;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.Base64;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Gemini Live API ({@code BidiGenerateContent}) realtime provider - Google's native audio-to-audio
 * model.
 *
 * <p><b>Wire protocol (v1alpha bidirectional stream):</b>
 *
 * <ul>
 *   <li>connect {@code wss://generativelanguage.googleapis.com/ws/
 *       google.ai.generativelanguage.v1alpha.GenerativeService.BidiGenerateContent?key=...}
 *   <li>first outbound frame: {@code {"setup":{model, generationConfig{
 *       responseModalities:["AUDIO"], speechConfig}, systemInstruction}}}
 *   <li>the provider answers {@code {"setupComplete":{}}} - {@link #open} waits for that before
 *       returning, so a bad key/model fails fast at session-create time instead of silently eating
 *       audio
 *   <li>audio in: {@code {"realtimeInput":{"mediaChunks":[{mimeType: "audio/pcm;rate=16000", data:
 *       base64}]}}}
 *   <li>audio out: {@code serverContent.modelTurn.parts[].inlineData.data} (PCM16 @ 24 kHz),
 *       transcripts via {@code inputTranscription}/ {@code outputTranscription}, barge-in via
 *       {@code serverContent. interrupted}
 * </ul>
 *
 * <p>The API key travels in the connect URI because the Live API does not take a bearer header on
 * the WebSocket - that is why the relay exists: the browser never sees this URL.
 */
@ConditionalOnEditionFeature(EditionFeature.VOICE_REALTIME_SHARE)
@Component
public class GeminiLiveRealtimeProvider implements RealtimeVoiceProvider {

    private static final Logger log = LoggerFactory.getLogger(GeminiLiveRealtimeProvider.class);
    private static final ObjectMapper MAPPER = JsonMappers.plain();

    static final String DEFAULT_MODEL = "gemini-2.5-flash-native-audio-preview-09-2025";
    static final String ENDPOINT =
            "wss://generativelanguage.googleapis.com/ws/"
                    + "google.ai.generativelanguage.v1alpha.GenerativeService.BidiGenerateContent";
    static final int INPUT_SAMPLE_RATE = 16000;
    static final int OUTPUT_SAMPLE_RATE = 24000;
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

    private final HttpClient httpClient;

    public GeminiLiveRealtimeProvider() {
        this(HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build());
    }

    GeminiLiveRealtimeProvider(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    @Override
    public RealtimeProvider getProvider() {
        return RealtimeProvider.GEMINI_LIVE;
    }

    @Override
    public RealtimeVoiceSession open(RealtimeSessionConfig config, RealtimeVoiceListener listener) {
        CountDownLatch setupDone = new CountDownLatch(1);
        GeminiSession session = new GeminiSession(listener, setupDone);

        WebSocket ws;
        try {
            // Gemini Live only accepts the key as a query parameter, so it is percent-encoded and
            // every message below is scrubbed: a JDK URI syntax error or a handshake failure can
            // quote the full URL, key included, and this message reaches logs and the relay close.
            ws =
                    httpClient
                            .newWebSocketBuilder()
                            .buildAsync(
                                    URI.create(
                                            ENDPOINT
                                                    + "?key="
                                                    + java.net.URLEncoder.encode(
                                                            config.apiKey(),
                                                            java.nio.charset.StandardCharsets
                                                                    .UTF_8)),
                                    session)
                            .orTimeout(CONNECT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
                            .join();
        } catch (IllegalArgumentException e) {
            // No cause: its message is the whole URI.
            throw new RealtimeVoiceException(
                    "Gemini Live connect failed: the endpoint URL is invalid");
        } catch (RuntimeException e) {
            throw new RealtimeVoiceException(
                    "Gemini Live connect failed: "
                            + com.agenticai.core.security.LogMasking.secrets(e.getMessage()),
                    new RuntimeException(
                            e.getClass().getName()
                                    + " (details withheld: may quote the request URL)"));
        }
        session.webSocket = ws;

        ws.sendText(buildSetupFrame(config), true).join();
        try {
            if (!setupDone.await(CONNECT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                ws.abort();
                throw new RealtimeVoiceException(
                        "Gemini Live setup timed out waiting for setupComplete");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            ws.abort();
            throw new RealtimeVoiceException("Interrupted during Gemini Live setup", e);
        }
        if (session.setupError != null) {
            ws.abort();
            throw new RealtimeVoiceException("Gemini Live rejected setup: " + session.setupError);
        }
        // Every later frame goes through the queue so sends never overlap.
        session.sender = new WebSocketSendQueue(ws, "Gemini Live");
        return session;
    }

    /**
     * The session itself: a WebSocket.Listener that parses inbound provider frames into listener
     * events, plus the outbound send methods.
     */
    static final class GeminiSession implements WebSocket.Listener, RealtimeVoiceSession {

        private final RealtimeVoiceListener listener;
        private final CountDownLatch setupDone;
        private final StringBuilder textBuf = new StringBuilder();
        volatile WebSocket webSocket;
        volatile WebSocketSendQueue sender;
        volatile String setupError;
        private volatile boolean closed;

        GeminiSession(RealtimeVoiceListener listener, CountDownLatch setupDone) {
            this.listener = listener;
            this.setupDone = setupDone;
        }

        // --------------------------- outbound ------------------------------

        @Override
        public void sendAudio(byte[] pcm16le) {
            WebSocketSendQueue out = sender;
            if (closed || out == null) {
                return;
            }
            ObjectNode chunk =
                    MAPPER.createObjectNode()
                            .put("mimeType", "audio/pcm;rate=" + INPUT_SAMPLE_RATE)
                            .put("data", Base64.getEncoder().encodeToString(pcm16le));
            ObjectNode frame = MAPPER.createObjectNode();
            frame.putObject("realtimeInput").putArray("mediaChunks").add(chunk);
            out.sendText(frame.toString());
        }

        @Override
        public void interrupt() {
            // Gemini Live's server-side VAD handles barge-in itself and tells
            // us via serverContent.interrupted - nothing to send.
        }

        @Override
        public void close() {
            closed = true;
            WebSocketSendQueue out = sender;
            if (out != null) {
                out.sendClose();
            }
        }

        // --------------------------- inbound -------------------------------

        @Override
        public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
            textBuf.append(data);
            if (!last) {
                ws.request(1);
                return null;
            }
            String frame = textBuf.toString();
            textBuf.setLength(0);
            try {
                JsonNode root = MAPPER.readTree(frame);
                if (root.has("setupComplete")) {
                    setupDone.countDown();
                } else if (root.has("error")) {
                    String msg = root.path("error").path("message").asString("unknown");
                    if (setupDone.getCount() > 0) {
                        setupError = msg;
                        setupDone.countDown();
                    } else {
                        listener.onError(msg);
                    }
                } else {
                    JsonNode content = root.path("serverContent");
                    if (content.path("interrupted").asBoolean(false)) {
                        listener.onInterrupted();
                    }
                    for (JsonNode part : content.path("modelTurn").path("parts")) {
                        JsonNode inline = part.path("inlineData");
                        if (inline.has("data")) {
                            listener.onAudioChunk(
                                    Base64.getDecoder().decode(inline.get("data").asString()));
                        }
                    }
                    String in = content.path("inputTranscription").path("text").asString("");
                    if (!in.isBlank()) {
                        listener.onInputTranscript(in);
                    }
                    String out = content.path("outputTranscription").path("text").asString("");
                    if (!out.isBlank()) {
                        listener.onOutputTranscript(out);
                    }
                }
            } catch (Exception e) {
                log.warn("Undecodable Gemini Live frame: {}", e.getMessage());
            }
            ws.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onBinary(WebSocket ws, ByteBuffer data, boolean last) {
            ws.request(1);
            return null;
        }

        @Override
        public void onError(WebSocket ws, Throwable error) {
            if (setupDone.getCount() > 0) {
                setupError = error.getMessage();
                setupDone.countDown();
            }
            if (!closed) {
                listener.onError(error.getMessage());
            }
        }

        @Override
        public CompletionStage<?> onClose(WebSocket ws, int statusCode, String reason) {
            if (setupDone.getCount() > 0) {
                setupError = "closed before setupComplete (" + statusCode + " " + reason + ")";
                setupDone.countDown();
            }
            if (!closed) {
                closed = true;
                listener.onClose();
            }
            return null;
        }
    }

    /** Builds the exact setup frame {@link #open} sends first. */
    static String buildSetupFrame(RealtimeSessionConfig config) {
        String model =
                config.model() == null || config.model().isBlank() ? DEFAULT_MODEL : config.model();
        ObjectNode generationConfig = MAPPER.createObjectNode();
        generationConfig.putArray("responseModalities").add("AUDIO");
        generationConfig
                .putObject("speechConfig")
                .putObject("voiceConfig")
                .putObject("prebuiltVoiceConfig")
                .put(
                        "voiceName",
                        config.voice() == null || config.voice().isBlank()
                                ? "Puck"
                                : config.voice());
        ObjectNode setup = MAPPER.createObjectNode().putObject("setup");
        setup.put("model", model.startsWith("models/") ? model : "models/" + model);
        setup.set("generationConfig", generationConfig);
        if (config.systemPrompt() != null && !config.systemPrompt().isBlank()) {
            setup.putObject("systemInstruction")
                    .putArray("parts")
                    .addObject()
                    .put("text", config.systemPrompt());
        }
        return MAPPER.createObjectNode().set("setup", setup).toString();
    }
}
