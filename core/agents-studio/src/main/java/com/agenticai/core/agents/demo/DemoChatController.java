package com.agenticai.core.agents.demo;

import com.agenticai.core.agents.turn.AgentChatService;
import com.agenticai.core.agents.turn.ChatTurnRequest;
import com.agenticai.core.agents.turn.TurnContext;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.tenancy.demo.DemoSession;
import com.agenticai.core.tenancy.demo.DemoSessionService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /api/demo/chat} - the one thing a sandbox visitor can actually DO. Resolves the
 * tenant from the opaque demo token, then runs the turn through the ordinary {@link
 * AgentChatService#chat} path, in the demo tenant's own schema, against the demo tenant's own
 * agent. There is no separate "demo chat engine": the visitor is talking to the real product.
 *
 * <p><b>Why it lives in {@code core/agents} and not next to the rest of the demo package in {@code
 * core/tenancy}:</b> {@link AgentChatService} is here, and the module graph runs {@code agents ->
 * tenancy}. Putting this one controller on this side of that edge is the same call {@code
 * DemoAgentSeeder} makes; the alternative (inverting yet another SPI just to host an HTTP endpoint)
 * would be ceremony for its own sake.
 *
 * <p><b>Non-streaming on purpose.</b> The authenticated Playground streams (SSE, {@code
 * AgentController#chat}). This returns one JSON object instead, because streaming and the hard
 * requirement below are in direct tension: a stream that dies at token 40 has already committed to
 * a response and can only leave a truncated mess on screen. Collecting the turn ({@code
 * .collect(joining()).block(...)}, exactly as {@code QuickTaskService#execute} already does) means
 * a failure is still fully recoverable into a clean fallback. The {@code /try} page fakes the
 * typing effect client-side, which is indistinguishable to the visitor.
 *
 * <p><b>The hard requirement: this endpoint never returns an error.</b> Not "rarely" - never, for
 * any reason short of the JVM being gone. The platform-hosted LLM pool is a live, known-fragile
 * dependency (as of this writing it is returning model-not-found and is being fixed by another
 * workstream), and a broken shop window is worse than no shop window. So every failure path below
 * lands on a canned response with {@code simulated: true}, which the UI labels honestly as a sample
 * rather than passing off as the model's work. The visitor's experience degrades from "impressive"
 * to "still coherent"; it never degrades to "broken".
 */
@RestController
@RequestMapping("/api/demo")
public class DemoChatController {

    private static final Logger log = LoggerFactory.getLogger(DemoChatController.class);

    private static final String DEMO_TOKEN_HEADER = "X-Demo-Token";

    /**
     * Ceiling on one demo turn. Generous enough for a real tool-using turn on a cold pool, short
     * enough that a hung upstream becomes a fallback response rather than a spinner the visitor
     * abandons.
     */
    private static final Duration TURN_TIMEOUT = Duration.ofSeconds(45);

    private final DemoSessionService demoSessionService;
    private final AgentChatService agentChatService;

    public DemoChatController(
            DemoSessionService demoSessionService, AgentChatService agentChatService) {
        this.demoSessionService = demoSessionService;
        this.agentChatService = agentChatService;
    }

    public record DemoChatRequest(
            @NotBlank(message = "message is required")
                    @Size(max = 2000, message = "message is too long")
                    String message,
            /** Optional - omit on the first turn, echo back afterwards to keep memory. */
            String sessionId,
            /** Optional, only used the first time a sandbox needs its agent seeded late. */
            @Size(max = 60) String businessName) {}

    /**
     * @param simulated true when {@code reply} is a canned fallback rather than model output. The
     *     UI MUST surface this - see the class javadoc. Presenting a fallback as a real answer
     *     would be exactly the kind of fabricated demo this feature is supposed to replace.
     */
    /**
     * @param readOnly true when the sandbox is past its editable window ({@code
     *     demo.editable-hours}) - the reply is then a soft "view-only now" nudge and the UI should
     *     flip its composer into the read-only state rather than inviting another message.
     */
    public record DemoChatResponse(
            String reply,
            String sessionId,
            boolean simulated,
            int messagesUsed,
            int messageLimit,
            long daysRemaining,
            boolean expired,
            boolean readOnly) {}

    @PostMapping("/chat")
    public ResponseEntity<DemoChatResponse> chat(
            @RequestHeader(value = DEMO_TOKEN_HEADER, required = false) String demoToken,
            @Valid @RequestBody DemoChatRequest request) {
        Optional<DemoSession> found = demoSessionService.resolve(demoToken);
        if (found.isEmpty()) {
            // The one case that is genuinely not a chat turn: there is no
            // sandbox to talk to. 404 so the page resets itself cleanly to
            // "start a new sandbox" instead of looping on a dead token.
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(
                            new DemoChatResponse(
                                    "This sandbox isn't available any more. You can start a new"
                                            + " one.",
                                    request.sessionId(),
                                    true,
                                    0,
                                    demoSessionService.messageLimit(),
                                    0,
                                    true,
                                    false));
        }

        DemoSession session = found.get();
        Instant now = Instant.now();
        int limit = demoSessionService.messageLimit();
        String sessionId =
                request.sessionId() != null && !request.sessionId().isBlank()
                        ? request.sessionId()
                        : UUID.randomUUID().toString();

        if (session.isExpired(now)) {
            return ResponseEntity.ok(
                    new DemoChatResponse(
                            "Your 5-day sandbox has ended. Everything you built is still here -"
                                    + " create a free account to carry on where you left off.",
                            sessionId,
                            true,
                            session.getMessageCount(),
                            limit,
                            0,
                            true,
                            false));
        }
        // The 24h-editable / view-only split (demo.editable-hours). Refused
        // BEFORE the message is counted - a nudge must never spend allowance.
        if (demoSessionService.isReadOnly(session, now)) {
            long daysLeft = Math.max(1, session.daysRemaining(now));
            return ResponseEntity.ok(
                    new DemoChatResponse(
                            "This sandbox's 24-hour hands-on window has ended, so it's now"
                                    + " view-only - everything you built stays right here for "
                                    + daysLeft
                                    + " more day"
                                    + (daysLeft == 1 ? "" : "s")
                                    + ". Create a free account to keep building where you left"
                                    + " off.",
                            sessionId,
                            true,
                            session.getMessageCount(),
                            limit,
                            session.daysRemaining(now),
                            false,
                            true));
        }
        if (demoSessionService.isMessageCapReached(session)) {
            return ResponseEntity.ok(
                    new DemoChatResponse(
                            "You've used all "
                                    + limit
                                    + " messages in this sandbox. Create a free account to keep"
                                    + " going - the free plan gives you your own workspace and a"
                                    + " much higher daily allowance.",
                            sessionId,
                            true,
                            session.getMessageCount(),
                            limit,
                            session.daysRemaining(now),
                            false,
                            false));
        }

        // Counted before the call, deliberately: otherwise a turn that fails
        // upstream is free, and "make the upstream fail" is not a hard bar to
        // clear for someone trying to farm an unauthenticated endpoint.
        demoSessionService.recordMessage(session);
        int used = session.getMessageCount() + 1;

        Optional<UUID> agentId =
                demoSessionService.ensureSeededAgent(session, request.businessName());
        if (agentId.isEmpty()) {
            log.warn(
                    "Demo sandbox {} still has no agent after a re-seed attempt - serving a"
                            + " fallback",
                    session.getId());
            return respondWithTurn(
                    session,
                    request.message(),
                    fallbackFor(request.message()),
                    true,
                    sessionId,
                    used,
                    limit,
                    now);
        }

        try {
            String reply =
                    demoSessionService.inDemoTenantContext(
                            session,
                            () -> {
                                // No attachment, no extra delegation tools, no RL turnRef. Same
                                // call shape
                                // every non-Playground caller (omnichannel, Quick Task) uses.
                                ChatTurnRequest chatTurn =
                                        ChatTurnRequest.builder(
                                                        agentId.get(),
                                                        sessionId,
                                                        "demo:" + session.getId(),
                                                        request.message())
                                                .build();
                                // ADR-0022 stage 4: the turn context carries the demo tenant set
                                // just above.
                                TurnContext turnContext =
                                        TurnContext.forEntry(
                                                TenantContext.current().orElse(null), chatTurn);
                                return agentChatService
                                        .chat(turnContext, chatTurn)
                                        .collect(Collectors.joining())
                                        .block(TURN_TIMEOUT);
                            });
            if (reply == null || reply.isBlank()) {
                // An empty completion is a failure wearing a success's
                // clothes - treat it as one rather than rendering a blank
                // bubble at the visitor.
                log.warn(
                        "Demo sandbox {} got an empty completion - serving a fallback",
                        session.getId());
                return respondWithTurn(
                        session,
                        request.message(),
                        fallbackFor(request.message()),
                        true,
                        sessionId,
                        used,
                        limit,
                        now);
            }
            return respondWithTurn(
                    session, request.message(), reply, false, sessionId, used, limit, now);
        } catch (RuntimeException e) {
            // Catches everything on purpose - the FREE-tier pool being empty
            // or misconfigured, a provider 404/429/timeout, a guardrail
            // refusal, a tenant-status flip mid-turn, an unexpected NPE. See
            // the class javadoc: none of these may reach the visitor as an
            // error. Logged at WARN with the sandbox id so this is still
            // diagnosable from the server side.
            log.warn(
                    "Demo chat turn failed for sandbox {} - serving a fallback response",
                    session.getId(),
                    e);
            return respondWithTurn(
                    session,
                    request.message(),
                    fallbackFor(request.message()),
                    true,
                    sessionId,
                    used,
                    limit,
                    now);
        }
    }

    /**
     * Every path that represents a real consumed turn funnels through here so the durable
     * transcript (migration V146 - what the read-only phase replays) always records exactly what
     * the visitor saw, fallbacks included and labelled. The 404/expired/read-only/cap refusals
     * above deliberately do NOT pass through: they consume no allowance and are state banners, not
     * conversation.
     */
    private ResponseEntity<DemoChatResponse> respondWithTurn(
            DemoSession session,
            String userMessage,
            String reply,
            boolean simulated,
            String sessionId,
            int used,
            int limit,
            Instant now) {
        demoSessionService.recordTranscriptTurn(session, userMessage, reply, simulated);
        return ResponseEntity.ok(
                new DemoChatResponse(
                        reply,
                        sessionId,
                        simulated,
                        used,
                        limit,
                        session.daysRemaining(now),
                        false,
                        false));
    }

    /**
     * The graceful-degradation response. Deliberately generic and clearly a sample - it does NOT
     * try to impersonate a real answer to the specific question, because a canned reply that
     * pretends to be model output is a fabrication, and the UI already labels this as a sample.
     * What it does do is pick one of a few shapes based on what the visitor asked, so the fallback
     * is at least on-topic rather than a flat apology.
     */
    private String fallbackFor(String message) {
        String m = message == null ? "" : message.toLowerCase(Locale.ROOT);
        if (m.contains("whatsapp") || m.contains("व्हाट्सएप")) {
            return """
(Sample response - the live model is busy right now, so here's an example of the shape \
of answer this agent gives.)

On a real account, this agent answers WhatsApp messages for you: a customer messages your \
business number, the agent replies in their language, captures the order details and hands \
anything it isn't sure about to you. Connecting a WhatsApp number needs a verified business \
account, which is set up after you register.\
""";
        }
        if (m.contains("price")
                || m.contains("order")
                || m.contains("payment")
                || m.contains("upi")) {
            return """
(Sample response - the live model is busy right now, so here's an example of the shape \
of answer this agent gives.)

On a real account, this agent handles order and price enquiries end to end: it answers from \
your price list, confirms quantities, and can hand off to a payment link once you've \
connected a payment provider. Try asking it to draft a customer reply instead - that works \
entirely inside this sandbox.\
""";
        }
        return """
(Sample response - the live model is busy right now, so here's an example of the shape of \
answer this agent gives.)

This agent is set up to reply to customers in their own language, draft messages and product \
descriptions, summarise long enquiries and translate between Indian languages. Give it a \
specific task - "write a polite reply to a customer asking if we deliver to Whitefield" - and \
it will write the actual text, not a description of what it could do.\
""";
    }
}
