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
 * OpenAI Realtime API provider - {@code wss://api.openai.com/v1/realtime} with a bearer key, {@code
 * session.update} configuration, and {@code input_audio_buffer.append}/{@code response.audio.delta}
 * audio frames. Audio is PCM16 @ 24 kHz in both directions.
 *
 * <p>Handshake is considered done when the provider answers the connect with {@code
 * session.created}, so a bad key fails at session-create time. Server-side VAD ({@code
 * turn_detection: server_vad}) is enabled by default so the caller never has to send explicit
 * commits; {@link RealtimeVoiceSession#interrupt} maps to {@code response.cancel}.
 */
@ConditionalOnEditionFeature(EditionFeature.VOICE_REALTIME_SHARE)
@Component
public class OpenAiRealtimeProvider implements RealtimeVoiceProvider {

    private static final Logger log = LoggerFactory.getLogger(OpenAiRealtimeProvider.class);
    private static final ObjectMapper MAPPER = JsonMappers.plain();

    static final String DEFAULT_MODEL = "gpt-realtime";
    static final String ENDPOINT = "wss://api.openai.com/v1/realtime";
    static final int SAMPLE_RATE = 24000;
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

    private final HttpClient httpClient;

    public OpenAiRealtimeProvider() {
        this(HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build());
    }

    OpenAiRealtimeProvider(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    @Override
    public RealtimeProvider getProvider() {
        return RealtimeProvider.OPENAI_REALTIME;
    }

    @Override
    public RealtimeVoiceSession open(RealtimeSessionConfig config, RealtimeVoiceListener listener) {
        String model =
                config.model() == null || config.model().isBlank() ? DEFAULT_MODEL : config.model();
        CountDownLatch ready = new CountDownLatch(1);
        OpenAiSession session = new OpenAiSession(listener, ready);

        WebSocket ws;
        try {
            ws =
                    httpClient
                            .newWebSocketBuilder()
                            .header("Authorization", "Bearer " + config.apiKey())
                            .header("OpenAI-Beta", "realtime=v1")
                            .buildAsync(URI.create(ENDPOINT + "?model=" + model), session)
                            .orTimeout(CONNECT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
                            .join();
        } catch (RuntimeException e) {
            throw new RealtimeVoiceException(
                    "OpenAI Realtime connect failed: " + e.getMessage(), e);
        }
        session.webSocket = ws;

        try {
            if (!ready.await(CONNECT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                ws.abort();
                throw new RealtimeVoiceException(
                        "OpenAI Realtime timed out waiting for session.created");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            ws.abort();
            throw new RealtimeVoiceException("Interrupted during OpenAI Realtime connect", e);
        }
        if (session.setupError != null) {
            ws.abort();
            throw new RealtimeVoiceException(
                    "OpenAI Realtime rejected the session: " + session.setupError);
        }

        // session.created arrived - now configure the session.
        ws.sendText(buildSessionUpdate(config), true).join();
        // Only now may other threads send: every later frame goes through the
        // queue so sends never overlap (see WebSocketSendQueue).
        session.sender = new WebSocketSendQueue(ws, "OpenAI Realtime");
        return session;
    }

    /** The {@code session.update} frame sent right after session.created. */
    static String buildSessionUpdate(RealtimeSessionConfig config) {
        ObjectNode session = MAPPER.createObjectNode();
        session.putArray("modalities").add("audio").add("text");
        if (config.systemPrompt() != null && !config.systemPrompt().isBlank()) {
            session.put("instructions", config.systemPrompt());
        }
        session.put(
                "voice",
                config.voice() == null || config.voice().isBlank() ? "alloy" : config.voice());
        session.put("input_audio_format", "pcm16");
        session.put("output_audio_format", "pcm16");
        session.putObject("turn_detection").put("type", "server_vad");
        session.putObject("input_audio_transcription").put("model", "whisper-1");
        ObjectNode frame = MAPPER.createObjectNode();
        frame.put("type", "session.update");
        frame.set("session", session);
        return frame.toString();
    }

    static final class OpenAiSession implements WebSocket.Listener, RealtimeVoiceSession {

        private final RealtimeVoiceListener listener;
        private final CountDownLatch ready;
        private final StringBuilder textBuf = new StringBuilder();
        volatile WebSocket webSocket;
        volatile WebSocketSendQueue sender;
        volatile String setupError;
        private volatile boolean closed;

        OpenAiSession(RealtimeVoiceListener listener, CountDownLatch ready) {
            this.listener = listener;
            this.ready = ready;
        }

        @Override
        public void sendAudio(byte[] pcm16le) {
            WebSocketSendQueue out = sender;
            if (closed || out == null) {
                return;
            }
            ObjectNode frame = MAPPER.createObjectNode();
            frame.put("type", "input_audio_buffer.append");
            frame.put("audio", Base64.getEncoder().encodeToString(pcm16le));
            out.sendText(frame.toString());
        }

        @Override
        public void interrupt() {
            WebSocketSendQueue out = sender;
            if (closed || out == null) {
                return;
            }
            out.sendText("{\"type\":\"response.cancel\"}");
        }

        @Override
        public void close() {
            closed = true;
            WebSocketSendQueue out = sender;
            if (out != null) {
                out.sendClose();
            }
        }

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
                String type = root.path("type").asString("");
                switch (type) {
                    case "session.created", "session.updated" -> ready.countDown();
                    case "response.audio.delta" -> {
                        String b64 = root.path("delta").asString("");
                        if (!b64.isEmpty()) {
                            listener.onAudioChunk(Base64.getDecoder().decode(b64));
                        }
                    }
                    case "response.audio_transcript.delta" -> {
                        String delta = root.path("delta").asString("");
                        if (!delta.isEmpty()) {
                            listener.onOutputTranscript(delta);
                        }
                    }
                    case "conversation.item.input_audio_transcription.completed" -> {
                        String text = root.path("transcript").asString("");
                        if (!text.isEmpty()) {
                            listener.onInputTranscript(text);
                        }
                    }
                    case "input_audio_buffer.speech_started" -> listener.onInterrupted();
                    case "error" -> {
                        String msg = root.path("error").path("message").asString("unknown");
                        if (ready.getCount() > 0) {
                            setupError = msg;
                            ready.countDown();
                        } else {
                            listener.onError(msg);
                        }
                    }
                    default -> {
                        /* rate_limits, response.done, etc. - informational only */
                    }
                }
            } catch (Exception e) {
                log.warn("Undecodable OpenAI Realtime frame: {}", e.getMessage());
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
            if (ready.getCount() > 0) {
                setupError = error.getMessage();
                ready.countDown();
            }
            if (!closed) {
                listener.onError(error.getMessage());
            }
        }

        @Override
        public CompletionStage<?> onClose(WebSocket ws, int statusCode, String reason) {
            if (ready.getCount() > 0) {
                setupError = "closed before session.created (" + statusCode + " " + reason + ")";
                ready.countDown();
            }
            if (!closed) {
                closed = true;
                listener.onClose();
            }
            return null;
        }
    }
}
