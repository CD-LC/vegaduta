package com.agenticai.core.omnichannel.twilio;

import com.agenticai.core.agents.turn.AgentChatService;
import com.agenticai.core.agents.turn.ChatTurnRequest;
import com.agenticai.core.agents.turn.TenantScopedFlux;
import com.agenticai.core.agents.turn.TurnContext;
import com.agenticai.core.json.JsonMappers;
import com.agenticai.core.omnichannel.session.ChannelSession;
import com.agenticai.core.omnichannel.session.ChannelSessionService;
import com.agenticai.core.omnichannel.session.ChannelType;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.TenantScope;
import com.agenticai.core.security.concurrent.TenantAwareSchedulers;
import com.agenticai.core.speech.SpeechStreamNormalizer;
import com.agenticai.core.tenancy.ExternalAccountProvider;
import com.agenticai.core.tenancy.TenantResolutionService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import reactor.core.Disposable;
import reactor.core.scheduler.Scheduler;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Master Roadmap 2026 item #17 (real-time voice agents) - bridges Twilio Conversation Relay's
 * WebSocket protocol (see <a
 * href="https://www.twilio.com/docs/voice/conversationrelay/websocket-messages">Twilio's
 * message-format docs</a>) to {@link AgentChatService}, the same chat engine every other channel
 * already uses. Registered at {@code /webhook/twilio/voice-relay} by {@link
 * VoiceRelayWebSocketConfig} - falls under the existing {@code /webhook/**} {@code permitAll()}
 * Spring Security rule (the WebSocket handshake is itself a plain HTTP GET request that matcher
 * already covers), same trust posture as every other inbound webhook: no Keycloak JWT,
 * authenticated instead by the {@code accountSid} in the "setup" message resolving to a real,
 * already-activated {@link TwilioCredential} for some tenant (an attacker who doesn't hold that
 * tenant's Twilio account can open this socket but has no way to make Twilio itself dial it with a
 * real call, and nothing here trusts caller-supplied data before that point beyond routing).
 *
 * <p><b>Wire protocol handled here:</b> {@code setup} (resolve tenant + channel session + the agent
 * that answers - see {@link #resolveDialedNumber} for per-number routing), {@code prompt} with
 * {@code last=true} (a finalized caller utterance - kicks off {@link AgentChatService#chat}),
 * {@code interrupt} (cancels the in-flight reply so the agent doesn't keep talking over a caller
 * who's already speaking again), {@code dtmf} (Master Roadmap 2026 item #17 fast-follow - see
 * {@link #handleDtmf}).
 *
 * <p><b>No {@link com.agenticai.core.approval.HumanApprovalGateService} in this path</b> - a live
 * phone call cannot pause mid-conversation for a human to click "approve" in the console the way a
 * WhatsApp/Telegram auto-reply can queue and wait; dead air has a hard real-time cost a queued text
 * message doesn't. The pairing gate below (identical to every other channel's
 * untrusted-inbound-sender protection) still keeps an unknown caller from reaching a tenant's agent
 * at all, and {@code AgentToolProvider}'s existing per-tool/autonomy-tier guardrails still govern
 * what the agent can actually DO mid-call - this narrows only to "is the spoken reply itself
 * individually approved before being said aloud," which voice cannot support and therefore does not
 * get.
 *
 * <p><b>Concurrency:</b> {@link AgentChatService#chat}'s {@code Flux<String>} is subscribed on
 * {@link Schedulers#boundedElastic()} so token streaming never blocks this WebSocket container's
 * I/O thread; the raw {@link WebSocketSession} is wrapped in a {@link
 * ConcurrentWebSocketSessionDecorator} (Spring's own documented fix for "sendMessage isn't safe to
 * call from multiple threads") since the streamed tokens are sent from that background thread while
 * {@code handleTextMessage} itself keeps running on the container's session thread for the next
 * inbound Twilio message.
 *
 * <p><b>Spoken text is normalized.</b> The {@code text} messages sent from here are fed to Twilio's
 * own text-to-speech, so raw markdown in them is read out character by character - the same live
 * bug (2026-07-29) {@code SpeechTextNormalizer} fixes for every other voice surface. This path is
 * the only STREAMING one, and per-token normalization does not work (a {@code **} arrives split
 * across tokens, and whitespace-trimmed tokens glue into "helloworld"), so tokens go through {@link
 * SpeechStreamNormalizer}, which buffers to a sentence/word boundary before normalizing and
 * sending. One instance per turn, created in {@link #handlePrompt} and driven only from the
 * serialized {@code onNext}/{@code onComplete} of that turn's subscription - see its javadoc for
 * why that makes the un-synchronized buffer safe on the {@code boundedElastic} thread described
 * above.
 */
public class VoiceRelayWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(VoiceRelayWebSocketHandler.class);
    private static final ObjectMapper MAPPER = JsonMappers.plain();
    private static final String REQUESTED_BY_PREFIX = "system:voice-inbound:";

    private static final String ATTR_TENANT_SCHEMA = "voiceRelay.tenantSchema";
    private static final String ATTR_AGENT_ID = "voiceRelay.agentId";
    private static final String ATTR_MEMORY_SESSION_ID = "voiceRelay.memorySessionId";
    private static final String ATTR_FROM = "voiceRelay.from";
    private static final String ATTR_DIALED_NUMBER = "voiceRelay.dialedNumber";
    private static final String ATTR_CALL_SID = "voiceRelay.callSid";
    private static final String ATTR_CHANNEL_SESSION_ID = "voiceRelay.channelSessionId";
    private static final String ATTR_SAFE_SESSION = "voiceRelay.safeSession";
    private static final String ATTR_ACTIVE_SUBSCRIPTION = "voiceRelay.activeSubscription";

    private static final int SEND_TIME_LIMIT_MS = 10_000;
    private static final int SEND_BUFFER_SIZE_LIMIT = 512 * 1024;

    private final TenantResolutionService tenantResolutionService;
    private final ChannelSessionService channelSessionService;
    private final AgentChatService agentChatService;
    private final VoiceDtmfMappingService voiceDtmfMappingService;
    private final VoiceNumberAgentService voiceNumberAgentService;
    private final VoiceRelayTicketService voiceRelayTicketService;

    /**
     * Where a prompt's chat turn is subscribed; package-private so a test can pin it to one thread.
     */
    Scheduler promptScheduler = TenantAwareSchedulers.boundedElastic();

    public VoiceRelayWebSocketHandler(
            TenantResolutionService tenantResolutionService,
            ChannelSessionService channelSessionService,
            AgentChatService agentChatService,
            VoiceDtmfMappingService voiceDtmfMappingService,
            VoiceNumberAgentService voiceNumberAgentService,
            VoiceRelayTicketService voiceRelayTicketService) {
        this.voiceRelayTicketService = voiceRelayTicketService;
        this.tenantResolutionService = tenantResolutionService;
        this.channelSessionService = channelSessionService;
        this.agentChatService = agentChatService;
        this.voiceDtmfMappingService = voiceDtmfMappingService;
        this.voiceNumberAgentService = voiceNumberAgentService;
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        JsonNode node;
        try {
            node = MAPPER.readTree(message.getPayload());
        } catch (JacksonException e) {
            log.warn(
                    "Malformed Conversation Relay message on session {}: {}",
                    session.getId(),
                    e.getMessage());
            return;
        }
        String type = node.path("type").asString("");
        switch (type) {
            case "setup" -> handleSetup(session, node);
            case "prompt" -> handlePrompt(session, node);
            case "interrupt" -> disposeActive(session);
            case "dtmf" -> handleDtmf(session, node);
            case "error" ->
                    log.warn(
                            "Conversation Relay reported an error on session {}: {}",
                            session.getId(),
                            node.path("description").asString(""));
            default ->
                    log.debug(
                            "Unhandled Conversation Relay message type '{}' on session {}",
                            type,
                            session.getId());
        }
    }

    /**
     * Mirrors {@code TwilioWebhookController#inboundWhatsApp}'s tenant resolution exactly (resolve
     * schema from the unauthenticated {@code accountSid} claim, same as every channel with no JWT),
     * then reuses the SAME pairing/default-agent machinery every other channel already has - {@link
     * ChannelSessionService#resolveOrCreate} with {@link ChannelType#VOICE} and the caller's phone
     * number as {@code externalId} (so the same caller's memory persists call to call, exactly like
     * WhatsApp's phone-number-keyed sessions).
     */
    private void handleSetup(WebSocketSession session, JsonNode node) {
        // Security audit 2026-09-18: nothing the frame CLAIMS is trusted any
        // more. The tenant, call and caller come from the admission ticket the
        // signature-verified inbound-call webhook minted; a socket without a
        // valid one is closed before any tenant is resolved.
        String presented = node.path("customParameters").path("ticket").asString("");
        if (presented.isBlank()) {
            presented = queryParam(session.getUri(), "ticket").orElse("");
        }
        Optional<VoiceRelayTicketService.Admission> admission =
                voiceRelayTicketService.verify(presented);
        if (admission.isEmpty()
                || !admission.get().callSid().equals(node.path("callSid").asString(""))) {
            log.warn(
                    "Rejected voice relay setup on session {} - missing, expired or mismatched"
                            + " admission ticket",
                    session.getId());
            closeQuietly(session);
            return;
        }
        String accountSid = admission.get().accountSid();
        String callSid = admission.get().callSid();
        String from = admission.get().from();
        String dialedNumber = resolveDialedNumber(session, node);
        session.getAttributes().put(ATTR_CALL_SID, callSid);
        session.getAttributes().put(ATTR_FROM, from);
        session.getAttributes().put(ATTR_DIALED_NUMBER, dialedNumber);

        // No fallback to the default schema: an AccountSid that maps to no
        // tenant is not "the public tenant's call", it is nobody's call.
        Optional<String> resolvedSchema =
                tenantResolutionService.resolveTenantSchema(
                        ExternalAccountProvider.TWILIO, accountSid);
        if (resolvedSchema.isEmpty()) {
            log.warn(
                    "Rejected voice relay setup for call {} - Twilio account is not linked to any"
                            + " tenant",
                    callSid);
            closeQuietly(session);
            return;
        }
        String tenantSchema = resolvedSchema.get();

        TenantContext.set(
                TenantContext.of(tenantSchema, REQUESTED_BY_PREFIX + accountSid, Set.of()));
        try {
            ChannelSession channelSession =
                    channelSessionService.resolveOrCreate(ChannelType.VOICE, from, "(voice call)");
            if (!channelSession.isPaired()) {
                log.warn(
                        "Unpaired voice caller {} on call {} - declining and ending session",
                        maskFrom(from),
                        callSid);
                sendFinalTextAndEnd(
                        session,
                        "For security, an admin needs to approve this caller before I can help. "
                                + "Your pairing code is "
                                + channelSession.getPairingCode()
                                + ". Goodbye.",
                        "{\"reasonCode\":\"unpaired-caller\"}");
                channelSessionService.markPairingNoticeSent(channelSession.getId());
                return;
            }
            // Per-number routing. The ChannelSession above is keyed on the
            // CALLER, so its stamped agent answers for "whoever this person
            // last spoke to"; a tenant running sales and support on two
            // numbers needs "whatever they dialled" to win instead.
            // Overriding only the session attribute keeps the caller's
            // conversation memory continuous while changing who answers -
            // the same per-call mechanism handleDtmf uses, and deliberately
            // NOT a rebind of the ChannelSession row. See VoiceNumberAgent.
            UUID routedAgentId =
                    voiceNumberAgentService
                            .findAgentIdForNumber(dialedNumber)
                            .orElse(channelSession.getAgentId());

            session.getAttributes().put(ATTR_TENANT_SCHEMA, tenantSchema);
            session.getAttributes().put(ATTR_AGENT_ID, routedAgentId);
            session.getAttributes().put(ATTR_MEMORY_SESSION_ID, channelSession.getSessionId());
            session.getAttributes().put(ATTR_CHANNEL_SESSION_ID, channelSession.getId());
            if (!routedAgentId.equals(channelSession.getAgentId())) {
                log.info(
                        "Voice call {} routed by dialled number {} to agent {} (channel default was"
                                + " {})",
                        callSid,
                        maskFrom(dialedNumber),
                        routedAgentId,
                        channelSession.getAgentId());
            }
            log.info(
                    "Voice call {} set up for tenant schema {} - agent {}",
                    callSid,
                    tenantSchema,
                    routedAgentId);
        } catch (RuntimeException e) {
            log.warn(
                    "Failed to set up voice relay session for call {}: {}",
                    callSid,
                    e.getMessage(),
                    e);
            sendFinalTextAndEnd(
                    session, "Sorry, something went wrong setting up this call. Goodbye.", null);
        } finally {
            TenantContext.clear();
        }
    }

    /**
     * Only acts once Twilio reports the caller's utterance is final ({@code last=true}) -
     * partial/interim transcripts exist so a UI could show live captions, which this backend has no
     * use for. Streams {@link AgentChatService#chat}'s tokens back as Conversation Relay {@code
     * text} messages a sentence at a time (Twilio's own docs recommend streaming rather than
     * waiting for the full reply, to cut perceived latency; the sentence-sized buffering is {@link
     * SpeechStreamNormalizer}'s, and is what lets the markdown be stripped before Twilio's TTS
     * reads it out), closing the turn with a final empty {@code last:true} token.
     */
    private void handlePrompt(WebSocketSession session, JsonNode node) {
        if (!node.path("last").asBoolean(false)) {
            return;
        }
        String text = node.path("voicePrompt").asString("");
        if (text.isBlank()) {
            return;
        }

        var attrs = session.getAttributes();
        String tenantSchema = (String) attrs.get(ATTR_TENANT_SCHEMA);
        UUID agentId = (UUID) attrs.get(ATTR_AGENT_ID);
        String memorySessionId = (String) attrs.get(ATTR_MEMORY_SESSION_ID);
        String from = (String) attrs.get(ATTR_FROM);
        if (tenantSchema == null || agentId == null) {
            // setup never completed successfully (unpaired caller, or setup
            // itself errored and already ended the session) - nothing to do.
            return;
        }

        disposeActive(session);

        // One buffer per turn - see the class javadoc's "Spoken text is
        // normalized". Deliberately NOT flushed on interrupt: disposing the
        // subscription drops it with whatever it was still holding, which is
        // exactly right when the caller has started speaking again.
        SpeechStreamNormalizer speech = new SpeechStreamNormalizer();
        Consumer<String> speak = spoken -> sendTextToken(session, spoken, false);

        // The tenant is applied for the synchronous subscription only, and the pooled scheduler
        // thread is put back as found. (It used to be set inside Flux.defer and cleared in
        // doFinally, which runs on whichever thread delivers the terminal signal: the scheduler
        // thread kept the tenant after the subscribe and ran unrelated work, possibly another
        // tenant's, under it.)
        // ADR-0022 stage 4: the turn context is built from the same explicit channel principal.
        TenantContext caller = TenantContext.of(tenantSchema, REQUESTED_BY_PREFIX + from, Set.of());
        TenantScope tenant = TenantScope.of(caller);
        ChatTurnRequest turn =
                ChatTurnRequest.builder(agentId, memorySessionId, from, text).build();
        Disposable subscription =
                TenantScopedFlux.subscribeInScope(
                                tenant,
                                () ->
                                        agentChatService.chat(
                                                TurnContext.forEntry(caller, turn), turn))
                        .subscribeOn(promptScheduler)
                        .subscribe(
                                token -> speech.accept(token, speak),
                                error -> {
                                    log.warn(
                                            "Voice relay chat error on call {}: {}",
                                            attrs.get(ATTR_CALL_SID),
                                            error.getMessage(),
                                            error);
                                    // Say the part of the answer that did arrive before
                                    // apologising for the rest - the caller had already heard
                                    // it before this path buffered. The leading space is what
                                    // keeps it from running into that partial sentence.
                                    speech.flush(speak);
                                    sendTextToken(
                                            session, " Sorry, I ran into a problem there.", true);
                                },
                                () -> {
                                    speech.flush(speak);
                                    sendTextToken(session, "", true);
                                });
        attrs.put(ATTR_ACTIVE_SUBSCRIPTION, subscription);
    }

    /**
     * Master Roadmap 2026 item #17 fast-follow - looks up the caller's pressed digit against this
     * tenant's {@link VoiceDtmfMappingService} menu. A match swaps only this session's in-memory
     * {@code ATTR_AGENT_ID} attribute (so the NEXT {@code prompt} routes to the new agent) and
     * speaks a short confirmation; no match is a silent no-op - an unmapped digit (or a tenant that
     * hasn't configured a menu at all) just means the caller keeps talking to whichever agent
     * already answered, exactly like today. Deliberately does not touch the {@code ChannelSession}
     * row (see {@link VoiceDtmfMapping}'s javadoc) - the switch is scoped to this one call.
     */
    private void handleDtmf(WebSocketSession session, JsonNode node) {
        String digit = node.path("digit").asString("");
        var attrs = session.getAttributes();
        String tenantSchema = (String) attrs.get(ATTR_TENANT_SCHEMA);
        if (tenantSchema == null || digit.isBlank()) {
            return;
        }
        TenantContext.set(TenantContext.of(tenantSchema, REQUESTED_BY_PREFIX + "dtmf", Set.of()));
        try {
            voiceDtmfMappingService
                    .findAgentIdForDigit(digit)
                    .ifPresent(
                            agentId -> {
                                attrs.put(ATTR_AGENT_ID, agentId);
                                log.info(
                                        "Voice call {} DTMF digit '{}' switched to agent {}",
                                        attrs.get(ATTR_CALL_SID),
                                        digit,
                                        agentId);
                                sendTextToken(session, "Connecting you now.", true);
                            });
        } finally {
            TenantContext.clear();
        }
    }

    /**
     * Works out which of the tenant's numbers this caller actually dialled.
     *
     * <p>Read in priority order: the {@code to} field of Conversation Relay's own {@code setup}
     * message, then a {@code ?to=} query parameter that {@link TwilioVoiceWebhookController}
     * appends to the {@code wss://} URL it hands Twilio. Both are read because the query parameter
     * is the half this codebase controls outright - it is derived from the {@code To} form field of
     * an inbound webhook whose Twilio signature was already verified - whereas {@code setup.to}
     * depends on Conversation Relay's own payload shape. Preferring {@code setup.to} keeps the
     * common path working for a socket opened without the query parameter (an older TwiML response
     * still cached at Twilio, say); the query parameter is the fallback that guarantees a value
     * regardless.
     *
     * <p><b>Trust.</b> Neither source is authenticated - anyone who can open this socket can claim
     * any {@code to} they like, exactly as they can already claim any {@code accountSid}. That does
     * not widen the blast radius: a forged number can only select an agent the tenant has
     * explicitly published on a phone line, which is precisely the set of agents already reachable
     * by dialling one. It cannot reach an arbitrary agent, and it cannot bypass the pairing gate
     * above, which runs first and is keyed on the caller. This is exactly why routing passes the
     * NUMBER to a server-side lookup instead of letting the URL carry an {@code agentId} directly -
     * the latter would have turned an unauthenticated socket into "reach any agent in the tenant".
     */
    private String resolveDialedNumber(WebSocketSession session, JsonNode node) {
        String fromSetup = node.path("to").asString("");
        if (!fromSetup.isBlank()) {
            return fromSetup;
        }
        return queryParam(session.getUri(), "to").orElse("");
    }

    private static void closeQuietly(WebSocketSession session) {
        try {
            session.close(CloseStatus.POLICY_VIOLATION);
        } catch (IOException | RuntimeException e) {
            log.debug(
                    "Closing rejected voice relay session {} failed: {}",
                    session.getId(),
                    e.getMessage());
        }
    }

    /**
     * Minimal single-parameter query reader - not worth pulling a URI-builder dependency into this
     * handler for one lookup.
     */
    private static Optional<String> queryParam(URI uri, String name) {
        if (uri == null || uri.getRawQuery() == null) {
            return Optional.empty();
        }
        String prefix = name + "=";
        for (String pair : uri.getRawQuery().split("&")) {
            if (pair.startsWith(prefix)) {
                return Optional.of(
                        URLDecoder.decode(pair.substring(prefix.length()), StandardCharsets.UTF_8));
            }
        }
        return Optional.empty();
    }

    private void disposeActive(WebSocketSession session) {
        Object existing = session.getAttributes().remove(ATTR_ACTIVE_SUBSCRIPTION);
        if (existing instanceof Disposable d && !d.isDisposed()) {
            d.dispose();
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        disposeActive(session);
        log.debug("Voice relay WS session {} closed: {}", session.getId(), status);
    }

    private void sendTextToken(WebSocketSession session, String token, boolean last) {
        ObjectNode msg = MAPPER.createObjectNode();
        msg.put("type", "text");
        msg.put("token", token);
        msg.put("last", last);
        send(session, msg);
    }

    private void sendFinalTextAndEnd(WebSocketSession session, String message, String handoffData) {
        sendTextToken(session, message, true);
        ObjectNode end = MAPPER.createObjectNode();
        end.put("type", "end");
        if (handoffData != null) {
            end.put("handoffData", handoffData);
        }
        send(session, end);
    }

    private void send(WebSocketSession session, ObjectNode payload) {
        try {
            WebSocketSession safe = safe(session);
            if (!safe.isOpen()) {
                return;
            }
            safe.sendMessage(new TextMessage(MAPPER.writeValueAsString(payload)));
        } catch (java.io.IOException | JacksonException e) {
            log.warn(
                    "Failed to send Conversation Relay message on session {}: {}",
                    session.getId(),
                    e.getMessage());
        }
    }

    /**
     * Spring's documented fix for concurrent {@code sendMessage} calls (background token stream vs.
     * the container's own message thread) - see class javadoc's "Concurrency" section.
     */
    private WebSocketSession safe(WebSocketSession session) {
        return (WebSocketSession)
                session.getAttributes()
                        .computeIfAbsent(
                                ATTR_SAFE_SESSION,
                                k ->
                                        new ConcurrentWebSocketSessionDecorator(
                                                session,
                                                SEND_TIME_LIMIT_MS,
                                                SEND_BUFFER_SIZE_LIMIT));
    }

    private static String maskFrom(String from) {
        if (from == null || from.length() < 4) {
            return "***";
        }
        return "***" + from.substring(from.length() - 4);
    }
}
