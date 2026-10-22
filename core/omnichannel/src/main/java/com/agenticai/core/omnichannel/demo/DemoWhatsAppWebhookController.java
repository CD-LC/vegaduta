package com.agenticai.core.omnichannel.demo;

import com.agenticai.core.agents.core.Agent;
import com.agenticai.core.agents.core.AgentRepository;
import com.agenticai.core.agents.turn.AgentChatService;
import com.agenticai.core.agents.turn.ChatTurnRequest;
import com.agenticai.core.agents.turn.TurnContext;
import com.agenticai.core.json.JsonMappers;
import com.agenticai.core.omnichannel.session.ChannelType;
import com.agenticai.core.omnichannel.session.InboundMessageDedupService;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.concurrent.TenantAwareExecutors;
import com.agenticai.core.tenancy.demo.DemoChannelLink;
import com.agenticai.core.tenancy.demo.DemoChannelLinkService;
import com.agenticai.core.tenancy.demo.DemoChannelMessage;
import com.agenticai.core.tenancy.demo.DemoChannelRateLimiter;
import com.agenticai.core.tenancy.demo.DemoChannelType;
import com.agenticai.core.tenancy.demo.DemoSession;
import com.agenticai.core.tenancy.demo.DemoSessionService;
import com.agenticai.core.tenancy.demo.DemoWhatsAppPhoneGuard;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Inbound webhook for the SHARED platform demo WhatsApp number - {@code /webhook/demo/whatsapp}.
 * The WhatsApp twin of {@link DemoTelegramWebhookController}; read that class's javadoc for why
 * this is a separate controller and never goes through the tenant path ({@code
 * resolveTenantSchema}, pairing-approval gate, human-approval gate).
 *
 * <p>Order of operations, each step before the next can cost anything:
 *
 * <ol>
 *   <li>Not configured -> no-op 200.
 *   <li>{@code X-Hub-Signature-256} over the RAW body under the platform app secret, constant time
 *       -> 403 on failure (the only non-200).
 *   <li>Everything after that is 200 no matter what, so Meta never replays a turn that has already
 *       been answered. Processing runs off the request thread so the 200 is not held behind a model
 *       call.
 *   <li>Per message: {@code wamid} dedup -> text only -> pairing code in the text redeems and
 *       binds; otherwise the sender must already be bound; per-phone rate limit -> session live and
 *       not message-capped -> per-phone lifetime cap -> {@code recordMessage} BEFORE the model call
 *       -> the same {@code AgentChatService} path inside the demo tenant's context with a timeout
 *       and a labelled fallback.
 * </ol>
 *
 * <p>Replies only ever answer an inbound message (Meta's 24-hour window). Nothing here sends a
 * template or a proactive message.
 */
@RestController
@RequestMapping(DemoWhatsAppBot.WEBHOOK_PATH)
public class DemoWhatsAppWebhookController {

    private static final Logger log = LoggerFactory.getLogger(DemoWhatsAppWebhookController.class);
    private static final ObjectMapper MAPPER = JsonMappers.plain();

    private static final Duration TURN_TIMEOUT = Duration.ofSeconds(40);
    private static final int MAX_INBOUND_CHARS = 2000;

    private final DemoWhatsAppBot bot;
    private final DemoSessionService demoSessionService;
    private final DemoChannelLinkService linkService;
    private final DemoChannelRateLimiter rateLimiter;
    private final DemoWhatsAppPhoneGuard phoneGuard;
    private final AgentChatService agentChatService;
    private final AgentRepository agentRepository;
    private final InboundMessageDedupService dedupService;
    private final Executor executor;
    private final int maxTurnsPerPhone;
    private final int maxPairsPerPhone;

    @Autowired
    public DemoWhatsAppWebhookController(
            DemoWhatsAppBot bot,
            DemoSessionService demoSessionService,
            DemoChannelLinkService linkService,
            DemoChannelRateLimiter rateLimiter,
            DemoWhatsAppPhoneGuard phoneGuard,
            AgentChatService agentChatService,
            AgentRepository agentRepository,
            InboundMessageDedupService dedupService,
            @Value("${app.demo.whatsapp.max-turns-per-phone:60}") int maxTurnsPerPhone,
            @Value("${app.demo.whatsapp.max-pairs-per-phone:3}") int maxPairsPerPhone) {
        this(
                bot,
                demoSessionService,
                linkService,
                rateLimiter,
                phoneGuard,
                agentChatService,
                agentRepository,
                dedupService,
                maxTurnsPerPhone,
                maxPairsPerPhone,
                TenantAwareExecutors.newVirtualThreadPerTaskExecutor("demo-whatsapp-"));
    }

    /** Test seam: pass {@code Runnable::run} to process inline. */
    DemoWhatsAppWebhookController(
            DemoWhatsAppBot bot,
            DemoSessionService demoSessionService,
            DemoChannelLinkService linkService,
            DemoChannelRateLimiter rateLimiter,
            DemoWhatsAppPhoneGuard phoneGuard,
            AgentChatService agentChatService,
            AgentRepository agentRepository,
            InboundMessageDedupService dedupService,
            int maxTurnsPerPhone,
            int maxPairsPerPhone,
            Executor executor) {
        this.bot = bot;
        this.demoSessionService = demoSessionService;
        this.linkService = linkService;
        this.rateLimiter = rateLimiter;
        this.phoneGuard = phoneGuard;
        this.agentChatService = agentChatService;
        this.agentRepository = agentRepository;
        this.dedupService = dedupService;
        this.maxTurnsPerPhone = maxTurnsPerPhone;
        this.maxPairsPerPhone = maxPairsPerPhone;
        this.executor = executor;
    }

    /** Meta's one-time subscription handshake. */
    @GetMapping
    public ResponseEntity<String> verify(
            @RequestParam(name = "hub.mode", required = false) String mode,
            @RequestParam(name = "hub.verify_token", required = false) String verifyToken,
            @RequestParam(name = "hub.challenge", required = false) String challenge) {
        if (!"subscribe".equals(mode) || !bot.verifyToken(verifyToken) || challenge == null) {
            log.warn("Rejected a demo WhatsApp webhook verification handshake");
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        return ResponseEntity.ok().contentType(MediaType.TEXT_PLAIN).body(challenge);
    }

    @PostMapping
    public ResponseEntity<Void> inbound(
            @RequestBody(required = false) byte[] rawBody,
            @RequestHeader(value = "X-Hub-Signature-256", required = false)
                    String signatureHeader) {
        if (!bot.isConfigured()) {
            // 200, not 404: a stale subscription stops retrying, and the
            // response says nothing about whether this path exists.
            return ResponseEntity.ok().build();
        }
        if (!bot.verifySignature(rawBody, signatureHeader)) {
            log.warn("Rejected a demo WhatsApp inbound call with a missing or invalid signature");
            return ResponseEntity.status(403).build();
        }
        try {
            JsonNode root = MAPPER.readTree(rawBody);
            for (JsonNode entry : root.path("entry")) {
                for (JsonNode change : entry.path("changes")) {
                    JsonNode value = change.path("value");
                    String pnid = value.path("metadata").path("phone_number_id").asText(null);
                    if (pnid != null && !pnid.equals(bot.phoneNumberId())) {
                        continue; // not our number - never process another app/number's traffic
                    }
                    for (JsonNode message : value.path("messages")) {
                        String type = message.path("type").asText("");
                        String id = message.path("id").asText(null);
                        String from = message.path("from").asText(null);
                        String body = message.path("text").path("body").asText(null);
                        executor.execute(() -> safely(id, type, from, body));
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Demo WhatsApp inbound payload failed: {}", e.getMessage());
        }
        return ResponseEntity.ok().build();
    }

    private void safely(String messageId, String type, String from, String body) {
        try {
            handleMessage(messageId, type, from, body);
        } catch (RuntimeException e) {
            // Never propagate: a replay would double-spend the visitor's cap.
            log.warn("Demo WhatsApp message handling failed: {}", e.getMessage());
        }
    }

    // ------------------------------------------------------------------

    void handleMessage(String messageId, String type, String from, String text) {
        if (!"text".equals(type)
                || messageId == null
                || from == null
                || text == null
                || text.isBlank()) {
            return; // media, reactions, status callbacks - nothing to route
        }
        if (!dedupService.tryClaim(ChannelType.WHATSAPP, "demo:" + messageId)) {
            return; // Meta retry of a message already handled
        }
        String hash = bot.hashPhone(from);
        if (hash == null) {
            return;
        }
        String trimmed = text.trim();

        if (!bot.isAvailable()) {
            if (rateLimiter.tryAcquire("wa:" + hash)) {
                bot.sendText(
                        from,
                        "The VegaDuta sandbox WhatsApp number is paused right now. You can still"
                                + " try an agent in your browser, and creating an account is free.");
            }
            return;
        }

        Optional<DemoChannelLink> binding =
                linkService.findLiveBinding(DemoChannelType.WHATSAPP, hash);

        String code = DemoChannelLinkService.extractPairingCode(trimmed);
        if (code != null) {
            boolean handled = handlePairing(from, hash, code, binding.isPresent());
            if (handled) {
                return;
            }
        }

        if (trimmed.equalsIgnoreCase("stop")) {
            if (binding.isPresent()) {
                linkService.revokeById(binding.get().getId());
                bot.sendText(
                        from,
                        "Disconnected. Your sandbox is untouched - reconnect any time with a new"
                                + " code from the browser.");
            } else if (rateLimiter.tryAcquire("wa:" + hash)) {
                bot.sendText(from, "You're not connected to a sandbox right now.");
            }
            return;
        }

        if (binding.isEmpty()) {
            if (rateLimiter.tryAcquire("wa:" + hash)) {
                bot.sendText(from, helpText());
            }
            return;
        }

        if (!rateLimiter.tryAcquire("wa:" + hash)) {
            bot.sendText(from, "That's a lot at once - give me a few seconds and send that again.");
            return;
        }
        answer(
                from,
                hash,
                binding.get(),
                trimmed.length() > MAX_INBOUND_CHARS
                        ? trimmed.substring(0, MAX_INBOUND_CHARS)
                        : trimmed);
    }

    /**
     * @return true when the message was a pairing attempt and has been fully dealt with; false when
     *     the sender is already bound and the "code" matched nothing, so the text should be treated
     *     as ordinary chat.
     */
    private boolean handlePairing(String from, String hash, String code, boolean alreadyBound) {
        if (!rateLimiter.tryAcquire("wa-pair:" + hash)) {
            // Silent for an unbound sender (a rate-limited pairing attempt must
            // not become a reply loop); a bound sender's text is just chat.
            return !alreadyBound;
        }
        DemoChannelLinkService.RedeemResult result =
                linkService.redeem(
                        DemoChannelType.WHATSAPP, code, hash, null, demoSessionService::findById);
        switch (result.outcome()) {
            case LINKED -> {
                if (!phoneGuard.tryConsumePair(hash, maxPairsPerPhone)) {
                    linkService.revokeById(result.link().getId());
                    bot.sendText(
                            from,
                            "This phone has already connected to the maximum number of demo"
                                    + " sandboxes. Creating a free account gives you your own"
                                    + " workspace.");
                    return true;
                }
                log.info(
                        "Demo WhatsApp sender {} bound to sandbox {}",
                        DemoWhatsAppBot.mask(hash),
                        result.link().getDemoSessionId());
                String name = agentName(result.session());
                bot.sendText(
                        from,
                        "Connected. You're now chatting with "
                                + (name == null
                                        ? "the agent in your VegaDuta sandbox"
                                        : "\"" + name + "\", the agent in your VegaDuta sandbox")
                                + " - the same one open in your browser.\n\n"
                                + "This is a shared VegaDuta demo number. Your agent replies to you"
                                + " here until your sandbox expires (5 days) or its messages run"
                                + " out - it can only reply to what you send, it cannot message you"
                                + " first. Creating an account is free and lifts the limits. Send"
                                + " STOP to disconnect.");
                linkService.mirror(
                        result.link().getDemoSessionId(),
                        DemoChannelType.WHATSAPP,
                        DemoChannelMessage.Direction.OUT,
                        "Connected from WhatsApp.");
                return true;
            }
            case ALREADY_LINKED -> {
                bot.sendText(
                        from,
                        "You're already connected. Send me a message and your sandbox agent will"
                                + " answer.");
                return true;
            }
            case SESSION_ENDED -> {
                bot.sendText(
                        from,
                        "That sandbox has ended. Everything in it is still there - create a free"
                                + " account to carry on, or start a fresh sandbox from the website.");
                return true;
            }
            case UNKNOWN_CODE -> {
                if (alreadyBound) {
                    return false; // they were just chatting; answer it as chat
                }
                bot.sendText(
                        from,
                        "I couldn't match that code. Codes look like VD-7F3K and can only be used"
                                + " once - open your sandbox in the browser and tap \"Get code\" on the"
                                + " WhatsApp card for a fresh one.");
                return true;
            }
        }
        return true;
    }

    private void answer(String from, String hash, DemoChannelLink link, String text) {
        Optional<DemoSession> found = demoSessionService.findById(link.getDemoSessionId());
        if (found.isEmpty()) {
            linkService.revokeById(link.getId());
            bot.sendText(
                    from,
                    "That sandbox isn't available any more. Start a new one from the website - "
                            + "or create a free account and keep it.");
            return;
        }
        DemoSession session = found.get();
        if (session.isExpired(Instant.now())) {
            linkService.revokeById(link.getId());
            bot.sendText(
                    from,
                    "Your 5-day sandbox has ended. Everything you built is still here - "
                            + "create a free account to carry on where you left off.");
            return;
        }
        if (demoSessionService.isMessageCapReached(session)) {
            bot.sendText(
                    from,
                    "You've used all "
                            + demoSessionService.messageLimit()
                            + " messages in this sandbox. Create a free account to keep going - the"
                            + " free plan gives you your own workspace with a much higher daily"
                            + " allowance.");
            return;
        }
        if (!phoneGuard.tryConsumeTurn(hash, maxTurnsPerPhone)) {
            bot.sendText(
                    from,
                    "This number has reached the demo's total message allowance. "
                            + "Create a free account to keep chatting with your agent.");
            return;
        }

        // Counted before the call, as Telegram does: a turn that fails
        // upstream must not be free, or "make it fail" becomes a way to farm it.
        demoSessionService.recordMessage(session);
        linkService.recordChannelTurn(link.getId());
        linkService.mirror(
                session.getId(), DemoChannelType.WHATSAPP, DemoChannelMessage.Direction.IN, text);

        String reply = generateReply(session, text);
        bot.sendText(from, reply);
        linkService.mirror(
                session.getId(), DemoChannelType.WHATSAPP, DemoChannelMessage.Direction.OUT, reply);
    }

    private String generateReply(DemoSession session, String text) {
        Optional<UUID> agentId = demoSessionService.ensureSeededAgent(session, null);
        if (agentId.isEmpty()) {
            log.warn(
                    "Demo sandbox {} has no agent after a re-seed attempt - answering WhatsApp with"
                            + " a sample",
                    session.getId());
            return fallback();
        }
        try {
            String reply =
                    demoSessionService.inDemoTenantContext(
                            session,
                            () -> {
                                ChatTurnRequest chatTurn =
                                        ChatTurnRequest.builder(
                                                        agentId.get(),
                                                        "demo-whatsapp:" + session.getId(),
                                                        "demo:" + session.getId(),
                                                        text)
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
            return reply == null || reply.isBlank() ? fallback() : reply;
        } catch (RuntimeException e) {
            log.warn(
                    "Demo WhatsApp turn failed for sandbox {} - sending a sample instead",
                    session.getId(),
                    e);
            return fallback();
        }
    }

    /** Best effort, in the demo tenant's own schema; null means "don't name it". */
    private String agentName(DemoSession session) {
        try {
            if (session == null || session.getAgentId() == null) {
                return null;
            }
            return demoSessionService.inDemoTenantContext(
                    session,
                    () ->
                            agentRepository
                                    .findById(session.getAgentId())
                                    .map(Agent::getName)
                                    .orElse(null));
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Labelled as a sample in the body: a WhatsApp chat has no UI chrome to carry a badge. */
    private String fallback() {
        return "(Sample reply - the live model is busy right now, so this is an example of the"
                + " shape of answer this agent gives.)\n\n"
                + "On a real account this agent replies to your customers in their own language,"
                + " drafts messages and product descriptions, and summarises long enquiries."
                + " Give it a specific task and try again in a moment.";
    }

    private String helpText() {
        return "This is the VegaDuta sandbox WhatsApp number.\n\n"
                + "To connect: open your sandbox in a browser, tap \"Get code\" on the WhatsApp"
                + " card, and send me the code it shows (it looks like VD-7F3K). After that,"
                + " anything you send me is answered by YOUR agent. I can only reply to you - I"
                + " can't message you first.\n\n"
                + "Send STOP to disconnect. Creating an account is free.";
    }
}
