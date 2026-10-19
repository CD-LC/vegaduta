package com.agenticai.core.voice.realtime;

import com.agenticai.core.agents.core.Agent;
import com.agenticai.core.agents.core.AgentRepository;
import com.agenticai.core.json.JsonMappers;
import com.agenticai.core.providers.ProviderCredentialResolver;
import com.agenticai.core.security.TenantContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;

/**
 * Bridges a browser's voice socket to a native speech-to-speech provider
 * session ({@link GeminiLiveRealtimeProvider} / {@link
 * OpenAiRealtimeProvider}). Registered at {@code /webhook/voice/realtime} -
 * under {@code /webhook/**} for the same reason the Twilio relay is: the
 * browser {@code WebSocket} API cannot set an Authorization header, so the
 * handshake can't carry the Keycloak JWT. Authentication is the {@link
 * RealtimeVoiceTicketService} ticket in the first text frame instead, minted
 * by the authenticated {@link RealtimeVoiceSessionController}.
 *
 * <p><b>Downstream protocol (client &rarr; relay):</b>
 * <ul>
 *   <li>first text frame: {@code {"type":"setup","ticket":"..."}} - the
 *       socket is closed if the ticket is missing, forged, or expired
 *   <li>binary frames: signed 16-bit LE PCM at the provider's input rate
 *       (16 kHz Gemini, 24 kHz OpenAI - the client learns both rates from
 *       the session-create response)
 *   <li>text {@code {"type":"interrupt"}}: barge-in (provider-side cancel)
 * </ul>
 *
 * <p><b>Upstream events (relay &rarr; client):</b> binary frames carrying
 * agent audio, and text frames {@code {"type":"transcript","role":"caller|
 * agent","text":...}}, {@code {"type":"interrupted"}}, {@code
 * {"type":"ready","model":...}}, {@code {"type":"error","message":...}}.
 *
 * <p>Downstream sends go through {@link ConcurrentWebSocketSessionDecorator}
 * because provider events arrive on the upstream client's delivery thread
 * while the container owns the downstream session thread.
 */
public class RealtimeVoiceWebSocketHandler extends AbstractWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(RealtimeVoiceWebSocketHandler.class);
    private static final ObjectMapper MAPPER = JsonMappers.plain();

    private static final String ATTR_SAFE_SESSION = "realtime.safeSession";
    private static final String ATTR_UPSTREAM = "realtime.upstream";
    private static final String ATTR_SETUP_DONE = "realtime.setupDone";

    private final RealtimeVoiceTicketService ticketService;
    private final ProviderCredentialResolver credentialResolver;
    private final AgentRepository agentRepository;
    private final List<RealtimeVoiceProvider> providers;

    public RealtimeVoiceWebSocketHandler(
        RealtimeVoiceTicketService ticketService,
        ProviderCredentialResolver credentialResolver,
        AgentRepository agentRepository,
        List<RealtimeVoiceProvider> providers
    ) {
        this.ticketService = ticketService;
        this.credentialResolver = credentialResolver;
        this.agentRepository = agentRepository;
        this.providers = providers;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        session.getAttributes().put(ATTR_SAFE_SESSION,
            new ConcurrentWebSocketSessionDecorator(session, 10_000, 512 * 1024));
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        JsonNode node;
        try {
            node = MAPPER.readTree(message.getPayload());
        } catch (IOException e) {
            log.warn("Malformed realtime-voice frame on {}: {}", session.getId(), e.getMessage());
            return;
        }
        String type = node.path("type").asText("");
        if ("setup".equals(type)) {
            handleSetup(session, node);
        } else if ("interrupt".equals(type)) {
            RealtimeVoiceSession upstream = (RealtimeVoiceSession) session.getAttributes().get(ATTR_UPSTREAM);
            if (upstream != null) {
                upstream.interrupt();
            }
        }
    }

    private void handleSetup(WebSocketSession session, JsonNode node) {
        if (session.getAttributes().containsKey(ATTR_SETUP_DONE)) {
            return;
        }
        Optional<RealtimeVoiceTicketService.Admission> admission =
            ticketService.verifyAndConsume(node.path("ticket").asText(""));
        if (admission.isEmpty()) {
            log.warn("Rejected realtime voice setup on {} - missing/forged/expired/already-used ticket", session.getId());
            closeQuietly(session, CloseStatus.POLICY_VIOLATION);
            return;
        }
        RealtimeVoiceTicketService.Admission adm = admission.get();

        TenantContext.set(TenantContext.of(adm.tenantSchema(), "system:voice-realtime:" + adm.subject(), Set.of()));
        try {
            Agent agent = agentRepository.findById(adm.agentId()).orElse(null);
            if (agent == null) {
                log.warn("Realtime voice setup: agent {} not found in schema {}", adm.agentId(), adm.tenantSchema());
                closeQuietly(session, CloseStatus.POLICY_VIOLATION);
                return;
            }
            String apiKey = credentialResolver.resolveActiveKey(
                RealtimeVoiceSessionController.providerNameFor(adm.provider()));
            RealtimeVoiceProvider provider = providers.stream()
                .filter(p -> p.getProvider() == adm.provider())
                .findFirst()
                .orElseThrow(() -> new RealtimeVoiceException("No provider bean for " + adm.provider()));

            RealtimeVoiceSession upstream = provider.open(
                new RealtimeSessionConfig(apiKey, null, agent.getSystemPrompt(), null),
                new RelayListener(session));
            session.getAttributes().put(ATTR_UPSTREAM, upstream);
            session.getAttributes().put(ATTR_SETUP_DONE, Boolean.TRUE);
            sendJson(session, "ready", Map.of("provider", adm.provider().name()));
        } catch (RuntimeException e) {
            log.warn("Realtime voice setup failed on {}: {}", session.getId(), e.getMessage());
            sendJson(session, "error", Map.of("message", "upstream session failed"));
            closeQuietly(session, CloseStatus.SERVER_ERROR);
        } finally {
            TenantContext.clear();
        }
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) {
        RealtimeVoiceSession upstream = (RealtimeVoiceSession) session.getAttributes().get(ATTR_UPSTREAM);
        if (upstream == null) {
            return; // audio before setup - drop, don't punish the socket
        }
        byte[] pcm = new byte[message.getPayload().remaining()];
        message.getPayload().get(pcm);
        upstream.sendAudio(pcm);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        RealtimeVoiceSession upstream = (RealtimeVoiceSession) session.getAttributes().remove(ATTR_UPSTREAM);
        if (upstream != null) {
            upstream.close();
        }
    }

    private void sendJson(WebSocketSession session, String type, Map<String, String> fields) {
        WebSocketSession safe = (WebSocketSession) session.getAttributes().get(ATTR_SAFE_SESSION);
        if (safe == null) {
            return;
        }
        ObjectNode node = MAPPER.createObjectNode().put("type", type);
        fields.forEach(node::put);
        try {
            safe.sendMessage(new TextMessage(node.toString()));
        } catch (IOException e) {
            log.debug("Downstream send failed on {}: {}", session.getId(), e.getMessage());
        }
    }

    private void closeQuietly(WebSocketSession session, CloseStatus status) {
        try {
            session.close(status);
        } catch (IOException e) {
            log.debug("Close failed on {}: {}", session.getId(), e.getMessage());
        }
    }

    /** Provider events → downstream client frames. */
    private class RelayListener implements RealtimeVoiceListener {

        private final WebSocketSession session;

        RelayListener(WebSocketSession session) {
            this.session = session;
        }

        private WebSocketSession safe() {
            return (WebSocketSession) session.getAttributes().get(ATTR_SAFE_SESSION);
        }

        @Override
        public void onAudioChunk(byte[] pcm16le) {
            WebSocketSession safe = safe();
            if (safe == null) {
                return;
            }
            try {
                safe.sendMessage(new BinaryMessage(pcm16le));
            } catch (IOException e) {
                log.debug("Audio send failed on {}: {}", session.getId(), e.getMessage());
            }
        }

        @Override
        public void onInputTranscript(String text) {
            sendJson(session, "transcript", Map.of("role", "caller", "text", text));
        }

        @Override
        public void onOutputTranscript(String text) {
            sendJson(session, "transcript", Map.of("role", "agent", "text", text));
        }

        @Override
        public void onInterrupted() {
            sendJson(session, "interrupted", Map.of());
        }

        @Override
        public void onError(String message) {
            sendJson(session, "error", Map.of("message", message == null ? "provider error" : message));
        }

        @Override
        public void onClose() {
            closeQuietly(session, CloseStatus.NORMAL);
        }
    }
}
