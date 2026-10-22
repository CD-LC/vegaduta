package com.agenticai.core.omnichannel.demo;

import com.agenticai.core.agents.turn.AgentChatService;
import com.agenticai.core.agents.turn.ChatTurnRequest;
import com.agenticai.core.agents.turn.TurnContext;
import com.agenticai.core.omnichannel.session.ChannelType;
import com.agenticai.core.omnichannel.session.InboundMessageDedupService;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.tenancy.demo.DemoChannelLink;
import com.agenticai.core.tenancy.demo.DemoChannelLinkService;
import com.agenticai.core.tenancy.demo.DemoChannelMessage;
import com.agenticai.core.tenancy.demo.DemoChannelRateLimiter;
import com.agenticai.core.tenancy.demo.DemoChannelType;
import com.agenticai.core.tenancy.demo.DemoSession;
import com.agenticai.core.tenancy.demo.DemoSessionService;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/**
 * Inbound webhook for the SHARED platform demo bot - {@code POST /webhook/demo/telegram}.
 *
 * <h2>How this differs from {@code TelegramWebhookController}, and why it is a separate controller
 * rather than a branch inside it</h2>
 *
 * The per-tenant controller's very first act is {@code
 * tenantResolutionService.resolveTenantSchema(TELEGRAM, botUserId)} - it resolves a TENANT from the
 * BOT. That is structurally the wrong shape here: there is exactly one demo bot and thousands of
 * sandboxes behind it, so the resolution has to run from the CHAT, through a single-use pairing
 * code, to a {@code demo_session}. Everything downstream differs for the same reason - no {@code
 * ChannelSession}, no {@code ChannelDefaultAgent}, no admin pairing-approval gate (there is no
 * admin), no workflow triggers, no human-approval gate on outbound (there is nobody to approve).
 * Folding two genuinely different resolution models into one controller with an {@code if (isDemo)}
 * would have made both harder to reason about, and this one is reachable by anyone on the internet.
 *
 * <h2>Security posture</h2>
 *
 * <ol>
 *   <li>Telegram's own {@code secret_token}, verified constant-time by {@link
 *       DemoTelegramBot#verifyWebhookSecret} BEFORE anything is parsed or looked up. Without it,
 *       this endpoint would let anyone POST fabricated "messages" into strangers' sandboxes.
 *   <li>Dedup on {@code update_id} - Telegram replays pending updates after any downtime, and a
 *       replayed turn would otherwise spend a second message from the visitor's cap.
 *   <li>Per-chat rate limiting, then the sandbox-wide message cap. The cap is the one that actually
 *       bounds cost; see {@link DemoChannelRateLimiter}.
 *   <li>Nothing here can reach a registered tenant. The only resolution path is {@code
 *       demo_channel_link -> demo_session -> demo tenant schema}, and a demo tenant is FREE-plan
 *       with no Keycloak user attached.
 * </ol>
 *
 * <h2>Always 200, always a sentence</h2>
 *
 * Same contract as {@code DemoChatController}: this is a shop window. Every failure - model down,
 * pool empty, sandbox expired, seeding never ran - turns into a plain, honest message in the
 * visitor's chat, never a silence and never a stack trace. The HTTP response is 200 in all of those
 * cases so Telegram does not retry a turn that has already been answered; the only non-200 is the
 * rejected-secret 403.
 */
@RestController
@RequestMapping(DemoTelegramBot.WEBHOOK_PATH)
public class DemoTelegramWebhookController {

    private static final Logger log = LoggerFactory.getLogger(DemoTelegramWebhookController.class);

    /**
     * Shorter than {@code DemoChatController}'s 45s: Telegram treats a slow webhook as a failure
     * and starts replaying updates, so the ceiling here has to leave room to send the reply and ack
     * inside Telegram's patience. A turn that overruns becomes the same honest fallback the page
     * shows.
     */
    private static final Duration TURN_TIMEOUT = Duration.ofSeconds(30);

    private static final int MAX_INBOUND_CHARS = 2000;

    private final DemoTelegramBot bot;
    private final DemoSessionService demoSessionService;
    private final DemoChannelLinkService linkService;
    private final DemoChannelRateLimiter rateLimiter;
    private final AgentChatService agentChatService;
    private final InboundMessageDedupService dedupService;

    public DemoTelegramWebhookController(
            DemoTelegramBot bot,
            DemoSessionService demoSessionService,
            DemoChannelLinkService linkService,
            DemoChannelRateLimiter rateLimiter,
            AgentChatService agentChatService,
            InboundMessageDedupService dedupService) {
        this.bot = bot;
        this.demoSessionService = demoSessionService;
        this.linkService = linkService;
        this.rateLimiter = rateLimiter;
        this.agentChatService = agentChatService;
        this.dedupService = dedupService;
    }

    @PostMapping
    public ResponseEntity<Void> inbound(
            @RequestBody JsonNode update,
            @RequestHeader(value = "X-Telegram-Bot-Api-Secret-Token", required = false)
                    String secretHeader) {
        if (!bot.isConfigured()) {
            // No demo bot on this deployment. 200 rather than 404 so a stale
            // webhook registration pointing here stops retrying instead of
            // hammering us, and so the response says nothing about whether
            // this path exists.
            return ResponseEntity.ok().build();
        }
        if (!bot.verifyWebhookSecret(secretHeader)) {
            log.warn(
                    "Rejected a demo Telegram inbound call with a missing or invalid secret token");
            return ResponseEntity.status(403).build();
        }
        try {
            String updateId = update.path("update_id").asString(null);
            if (!dedupService.tryClaim(ChannelType.TELEGRAM, "demo:" + updateId)) {
                return ResponseEntity.ok().build();
            }
            handleUpdate(update);
        } catch (RuntimeException e) {
            // Never a 500 back to Telegram: it would replay the update and
            // the visitor would be answered twice (or, worse, charged twice
            // against their cap).
            log.warn("Demo Telegram inbound update failed: {}", e.getMessage());
        }
        return ResponseEntity.ok().build();
    }

    // ------------------------------------------------------------------

    private void handleUpdate(JsonNode update) {
        JsonNode message = update.path("message");
        if (message.isMissingNode()) {
            return; // edited_message, callback_query, channel_post - nothing to route
        }
        String chatId = message.path("chat").path("id").asString(null);
        String text = message.path("text").asString(null);
        if (chatId == null || text == null || text.isBlank()) {
            // Photos, stickers, voice notes. Answered once (rate-limited) so
            // the visitor is not left wondering, then ignored.
            if (chatId != null && rateLimiter.tryAcquire(chatId)) {
                bot.sendMessage(
                        chatId,
                        "I can only read text in this sandbox. Send me something to write and I'll"
                                + " have a go.");
            }
            return;
        }
        String trimmed = text.trim();
        String peerLabel = peerLabel(message);

        if (!bot.isAvailable()) {
            // Configured (we got past the secret check) but switched off by
            // the platform kill switch, or its username never resolved. Say
            // so plainly rather than going quiet.
            if (rateLimiter.tryAcquire(chatId)) {
                bot.sendMessage(
                        chatId,
                        "The VegaDuta sandbox bot is paused right now. You can still try an agent"
                                + " in your browser, and creating an account is free.");
            }
            return;
        }

        if (trimmed.equalsIgnoreCase("/stop")
                || trimmed.equalsIgnoreCase("/stop@" + bot.botUsername())) {
            handleStop(chatId);
            return;
        }
        if (trimmed.startsWith("/start")) {
            handleStart(chatId, startPayload(trimmed), peerLabel);
            return;
        }
        if (trimmed.startsWith("/help")) {
            bot.sendMessage(chatId, helpText());
            return;
        }

        Optional<DemoChannelLink> binding =
                linkService.findLiveBinding(DemoChannelType.TELEGRAM, chatId);
        if (binding.isEmpty()) {
            // Two very different unbound cases, answered differently on
            // purpose. A bare code is the fallback for anyone whose deep link
            // did not carry the /start payload through (some desktop clients
            // drop it). Anything else is somebody saying hello, and telling
            // them "I couldn't match that code" would be nonsense.
            if (DemoChannelLinkService.looksLikePairingCode(trimmed)) {
                handleStart(chatId, trimmed, peerLabel);
            } else if (rateLimiter.tryAcquire(chatId)) {
                bot.sendMessage(chatId, helpText());
            }
            return;
        }

        if (!rateLimiter.tryAcquire(chatId)) {
            bot.sendMessage(
                    chatId, "That's a lot at once - give me a few seconds and send that again.");
            return;
        }
        answer(
                chatId,
                binding.get(),
                trimmed.length() > MAX_INBOUND_CHARS
                        ? trimmed.substring(0, MAX_INBOUND_CHARS)
                        : trimmed);
    }

    /**
     * {@code /start VD-7F3K} from the deep link, {@code /start} on its own, or a bare code typed by
     * hand.
     */
    private void handleStart(String chatId, String payload, String peerLabel) {
        if (!rateLimiter.tryAcquire(chatId)) {
            return; // silent: a rate-limited pairing attempt should not become a reply loop
        }
        if (payload == null || payload.isBlank()) {
            Optional<DemoChannelLink> existing =
                    linkService.findLiveBinding(DemoChannelType.TELEGRAM, chatId);
            bot.sendMessage(
                    chatId,
                    existing.isPresent()
                            ? "You're already connected to your sandbox. Just send me a message -"
                                    + " try \"write a polite reply to a customer asking if we deliver"
                                    + " on Sundays\"."
                            : helpText());
            return;
        }

        DemoChannelLinkService.RedeemResult result =
                linkService.redeem(
                        DemoChannelType.TELEGRAM,
                        payload,
                        chatId,
                        peerLabel,
                        demoSessionService::findById);
        switch (result.outcome()) {
            case LINKED -> {
                log.info(
                        "Demo Telegram chat bound to sandbox {}", result.link().getDemoSessionId());
                bot.sendMessage(
                        chatId,
                        "Connected. You're talking to the agent in your VegaDuta sandbox - the same"
                                + " one open in your browser.\n\n"
                                + "Ask it to write something real: a customer reply, a product"
                                + " description, a price note. It answers in English or any Indian"
                                + " language.\n\n"
                                + "This sandbox lasts 5 days and has a limited number of messages."
                                + " Creating an account is free and lifts both. Send /stop to"
                                + " disconnect.");
                linkService.mirror(
                        result.link().getDemoSessionId(),
                        DemoChannelType.TELEGRAM,
                        DemoChannelMessage.Direction.OUT,
                        "Connected from Telegram.");
            }
            case ALREADY_LINKED ->
                    bot.sendMessage(
                            chatId,
                            "You're already connected. Send me a message and your sandbox agent"
                                    + " will answer.");
            case SESSION_ENDED ->
                    bot.sendMessage(
                            chatId,
                            "That sandbox has ended. Everything in it is still there - create a"
                                    + " free account to carry on, or start a fresh sandbox from the"
                                    + " website.");
            case UNKNOWN_CODE ->
                    bot.sendMessage(
                            chatId,
                            "I couldn't match that code. Codes look like VD-7F3K and can only be"
                                    + " used once - open your sandbox in the browser and tap \"Connect"
                                    + " Telegram\" for a fresh one.");
        }
    }

    private void handleStop(String chatId) {
        Optional<DemoChannelLink> binding =
                linkService.findLiveBinding(DemoChannelType.TELEGRAM, chatId);
        if (binding.isEmpty()) {
            bot.sendMessage(chatId, "You're not connected to a sandbox right now.");
            return;
        }
        linkService.revokeById(binding.get().getId());
        bot.sendMessage(
                chatId,
                "Disconnected. Your sandbox is untouched - reconnect any time with a new code "
                        + "from the browser.");
    }

    /**
     * One turn: same {@code AgentChatService} path the browser sandbox and every registered
     * tenant's channels use, run inside the demo tenant's own schema. There is no separate demo
     * engine anywhere in this flow.
     */
    private void answer(String chatId, DemoChannelLink link, String text) {
        Optional<DemoSession> found = demoSessionService.findById(link.getDemoSessionId());
        if (found.isEmpty()) {
            linkService.revokeById(link.getId());
            bot.sendMessage(
                    chatId,
                    "That sandbox isn't available any more. Start a new one from the website - "
                            + "or create a free account and keep it.");
            return;
        }
        DemoSession session = found.get();
        Instant now = Instant.now();

        if (session.isExpired(now)) {
            linkService.revokeById(link.getId());
            bot.sendMessage(
                    chatId,
                    "Your 5-day sandbox has ended. Everything you built is still here - "
                            + "create a free account to carry on where you left off.");
            return;
        }
        if (demoSessionService.isMessageCapReached(session)) {
            bot.sendMessage(
                    chatId,
                    "You've used all "
                            + demoSessionService.messageLimit()
                            + " messages in this sandbox. Create a free account to keep going -"
                            + " it's free, and the free plan gives you your own workspace with a"
                            + " much higher daily allowance.");
            return;
        }

        // Counted before the call, exactly as DemoChatController does and for
        // the same reason: a turn that fails upstream must not be free, or
        // "make the upstream fail" becomes the way to farm the bot.
        demoSessionService.recordMessage(session);
        linkService.recordChannelTurn(link.getId());
        linkService.mirror(
                session.getId(), DemoChannelType.TELEGRAM, DemoChannelMessage.Direction.IN, text);

        bot.sendTyping(chatId);

        String reply = generateReply(session, text);
        bot.sendMessage(chatId, reply);
        linkService.mirror(
                session.getId(), DemoChannelType.TELEGRAM, DemoChannelMessage.Direction.OUT, reply);
    }

    private String generateReply(DemoSession session, String text) {
        Optional<UUID> agentId = demoSessionService.ensureSeededAgent(session, null);
        if (agentId.isEmpty()) {
            log.warn(
                    "Demo sandbox {} has no agent after a re-seed attempt - answering Telegram with"
                            + " a sample",
                    session.getId());
            return fallback();
        }
        try {
            String reply =
                    demoSessionService.inDemoTenantContext(
                            session,
                            () -> {
                                // Session id is derived from the sandbox so a Telegram
                                // conversation keeps its own memory thread, separate from
                                // whatever the visitor is typing on the page.
                                ChatTurnRequest chatTurn =
                                        ChatTurnRequest.builder(
                                                        agentId.get(),
                                                        "demo-telegram:" + session.getId(),
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
                    "Demo Telegram turn failed for sandbox {} - sending a sample instead",
                    session.getId(),
                    e);
            return fallback();
        }
    }

    /**
     * Labelled as a sample in the message body itself, because a Telegram chat has no UI chrome to
     * carry the badge the {@code /try} page shows. Passing a canned line off as the model's work
     * would be exactly the fabricated demo this whole feature exists to replace.
     */
    private String fallback() {
        return "(Sample reply - the live model is busy right now, so this is an example of the"
                + " shape of answer this agent gives.)\n\n"
                + "On a real account this agent replies to your customers in their own language,"
                + " drafts messages and product descriptions, and summarises long enquiries."
                + " Give it a specific task and try again in a moment.";
    }

    private String helpText() {
        return "This is the VegaDuta sandbox bot.\n\n"
                + "To connect: open your sandbox in a browser, tap \"Connect Telegram\", and"
                + " send me the code it shows (it looks like VD-7F3K). After that, anything you"
                + " send me is answered by YOUR agent.\n\n"
                + "/stop disconnects. Creating an account is free.";
    }

    /**
     * {@code "/start VD-7F3K"} &rarr; {@code "VD-7F3K"}; {@code "/start"} &rarr; null. Also
     * tolerates {@code /start@BotName}.
     */
    private static String startPayload(String text) {
        int space = text.indexOf(' ');
        if (space < 0) {
            return null;
        }
        String payload = text.substring(space + 1).trim();
        return payload.isEmpty() ? null : payload;
    }

    /**
     * Display-only label for the {@code /try} page's "Connected as …". A Telegram first name
     * or @username and nothing else - deliberately not the numeric user id, not a phone number
     * (Telegram never gives bots one anyway), and never used to resolve anything.
     */
    private static String peerLabel(JsonNode message) {
        JsonNode from = message.path("from");
        String username = from.path("username").asString(null);
        if (username != null && !username.isBlank()) {
            return "@" + username;
        }
        String firstName = from.path("first_name").asString(null);
        return firstName == null || firstName.isBlank() ? null : firstName;
    }
}
