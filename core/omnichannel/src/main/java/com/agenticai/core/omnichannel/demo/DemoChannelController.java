package com.agenticai.core.omnichannel.demo;

import com.agenticai.core.tenancy.demo.DemoChannelLink;
import com.agenticai.core.tenancy.demo.DemoChannelLinkService;
import com.agenticai.core.tenancy.demo.DemoChannelRateLimiter;
import com.agenticai.core.tenancy.demo.DemoChannelType;
import com.agenticai.core.tenancy.demo.DemoSession;
import com.agenticai.core.tenancy.demo.DemoSessionService;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/demo/channels} - the browser half of "take your sandbox agent
 * into a real messenger". Unauthenticated by necessity, identified only by the
 * same opaque {@code X-Demo-Token} the rest of {@code /api/demo/**} uses, and
 * every method here acts on the caller's OWN resolved session - no session id
 * is ever accepted off the wire.
 *
 * <h2>Telegram: a real connection</h2>
 * The visitor gets a single-use pairing code and a {@code t.me} deep link.
 * They press Start in Telegram, {@link DemoTelegramWebhookController} binds
 * that chat to this sandbox, and from then on the bot's replies come from
 * their own agent through the ordinary {@code AgentChatService} path. Nothing
 * about that flow is simulated. It hides itself completely when the platform
 * has no demo bot configured - see {@link DemoTelegramBot}.
 *
 * <h2>WhatsApp: a preview, and labelled as one</h2>
 * This was evaluated properly and deliberately NOT built as a connection.
 * Two paths exist and both fail for an anonymous visitor:
 * <ol>
 *   <li><b>Meta Cloud API / Twilio (the official paths).</b> Sending a
 *       business-initiated WhatsApp message to a number that has not messaged
 *       you first requires an approved template on a verified business
 *       sender. This platform holds no platform-level WhatsApp sender at all -
 *       {@code MetaWhatsAppCredential} and the Twilio credentials are
 *       per-tenant, BYOK, and provisioned after registration. There is
 *       nothing to send from. Standing up a verified business sender for
 *       throwaway 5-day sandboxes is also not something a business
 *       verification is meant to cover.</li>
 *   <li><b>Collecting the visitor's phone number to message them.</b> Would
 *       need (1) above to exist, and collects personal data from an anonymous
 *       user for no benefit they can verify. Not built; no phone number is
 *       requested anywhere in this flow.</li>
 * </ol>
 * So the WhatsApp affordance renders the visitor's REAL agent reply - the same
 * {@code /api/demo/chat} turn, the same model, the same workspace - inside a
 * WhatsApp-styled frame, with the UI stating plainly that the reply is real
 * and the delivery is a preview. This endpoint records only that they tried
 * it, so the funnel can compare demand for the two channels honestly. If a
 * platform-level WhatsApp sender is ever provisioned, this is the seam where a
 * real one-message send would slot in.
 *
 * <h2>Error posture</h2>
 * Inherited from {@code DemoSessionController}: 404 for an unknown token so a
 * stale {@code localStorage} entry resets the page cleanly, and otherwise
 * always a 200 with a renderable body. No stack traces, no status codes, no
 * platform vocabulary ever reaches the visitor.
 */
@RestController
@RequestMapping("/api/demo/channels")
public class DemoChannelController {

    private static final Logger log = LoggerFactory.getLogger(DemoChannelController.class);

    private static final String DEMO_TOKEN_HEADER = "X-Demo-Token";

    private final DemoSessionService demoSessionService;
    private final DemoChannelLinkService linkService;
    private final DemoChannelRateLimiter rateLimiter;
    private final DemoTelegramBot telegramBot;
    private final DemoWhatsAppBot whatsAppBot;

    public DemoChannelController(
        DemoSessionService demoSessionService,
        DemoChannelLinkService linkService,
        DemoChannelRateLimiter rateLimiter,
        DemoTelegramBot telegramBot,
        DemoWhatsAppBot whatsAppBot
    ) {
        this.demoSessionService = demoSessionService;
        this.linkService = linkService;
        this.rateLimiter = rateLimiter;
        this.telegramBot = telegramBot;
        this.whatsAppBot = whatsAppBot;
    }

    // ------------------------------------------------------------------
    // Wire shapes. Flat and nullable-tolerant on purpose - web/ runs with
    // `strict: false`, so a discriminated union would not narrow there.
    // ------------------------------------------------------------------

    public record ChannelMessageView(String direction, String text, Instant at) {
    }

    /**
     * @param available false hides the whole card. The page must render
     *     perfectly with Telegram absent - that is the default state of any
     *     deployment that has not configured a bot.
     * @param state one of NONE / PENDING / LINKED / REVOKED.
     * @param pairingCode null once redeemed; codes are single-use.
     * @param messages the mirror of what was said over Telegram since the
     *     {@code since} the caller passed, so a laptop tab shows a
     *     conversation happening on a phone.
     */
    public record TelegramStatus(
        boolean available,
        String botUsername,
        String state,
        String pairingCode,
        String deepLink,
        String peerLabel,
        Instant linkedAt,
        int messageCount,
        List<ChannelMessageView> messages
    ) {
        static TelegramStatus unavailable() {
            return new TelegramStatus(false, null, "NONE", null, null, null, null, 0, List.of());
        }
    }

    /**
     * @param mode {@code "PREVIEW"} (the original no-transport preview - its
     *     meaning is unchanged) or {@code "LIVE"} when a platform WhatsApp
     *     Business number is configured and switched on, in which case the
     *     {@code available/state/pairingCode/deepLink/businessNumberMasked}
     *     fields describe a REAL wa.me pairing (see {@link DemoWhatsAppBot}).
     *     The UI switches its labelling on this field, and mislabelling is the
     *     one failure mode this feature must not have.
     * @param state NONE / PENDING / LINKED / REVOKED for the LIVE binding;
     *     always NONE in PREVIEW mode.
     */
    public record WhatsAppStatus(
        String mode,
        boolean used,
        int messageCount,
        boolean available,
        String state,
        String pairingCode,
        String deepLink,
        String businessNumberMasked
    ) {
        /** The old, no-transport preview shape: {@code mode=PREVIEW}, no live pairing fields. */
        static WhatsAppStatus preview(boolean used, int messageCount) {
            return new WhatsAppStatus("PREVIEW", used, messageCount, false, "NONE", null, null, null);
        }
    }

    public record ChannelsStatus(TelegramStatus telegram, WhatsAppStatus whatsapp) {
    }

    // ------------------------------------------------------------------

    /**
     * Status + the Telegram mirror. Polled by {@code /try} while a pairing is
     * outstanding or a chat is live.
     *
     * @param since ISO-8601 instant, exclusive; the caller passes back the
     *     newest {@code at} it has already rendered. Unparseable or absent
     *     means "everything you have", which is bounded by the mirror's own
     *     retention.
     */
    @GetMapping
    public ResponseEntity<?> status(
        @RequestHeader(value = DEMO_TOKEN_HEADER, required = false) String demoToken,
        @RequestParam(value = "since", required = false) String since
    ) {
        Optional<DemoSession> found = demoSessionService.resolve(demoToken);
        if (found.isEmpty()) {
            return notFound();
        }
        try {
            return ResponseEntity.ok(buildStatus(found.get(), parseInstant(since)));
        } catch (RuntimeException e) {
            log.warn("Demo channel status failed for a sandbox - degrading to 'no channels'", e);
            // Degraded but renderable: the page hides both cards and stays
            // completely usable. Never an error surface.
            return ResponseEntity.ok(new ChannelsStatus(TelegramStatus.unavailable(), WhatsAppStatus.preview(false, 0)));
        }
    }

    /**
     * Issues (or returns the existing) Telegram pairing code for this sandbox.
     * Idempotent - a reload, or React StrictMode firing the effect twice, must
     * not invalidate the code already on the visitor's phone screen.
     */
    @PostMapping("/telegram/pair")
    public ResponseEntity<?> pairTelegram(
        @RequestHeader(value = DEMO_TOKEN_HEADER, required = false) String demoToken
    ) {
        Optional<DemoSession> found = demoSessionService.resolve(demoToken);
        if (found.isEmpty()) {
            return notFound();
        }
        DemoSession session = found.get();
        if (session.isExpired(Instant.now())) {
            return ResponseEntity.ok(new ChannelsStatus(TelegramStatus.unavailable(), whatsappStatus(session)));
        }
        if (!telegramBot.isAvailable()) {
            return ResponseEntity.ok(new ChannelsStatus(TelegramStatus.unavailable(), whatsappStatus(session)));
        }
        if (!rateLimiter.tryAcquire("pair:" + session.getId())) {
            // Nothing dangerous happened - just stop minting. Returning the
            // current state means the page shows whatever code it already has
            // instead of an error.
            return ResponseEntity.ok(buildStatus(session, null));
        }
        try {
            linkService.issuePairingCode(session, DemoChannelType.TELEGRAM);
        } catch (RuntimeException e) {
            log.warn("Could not issue a demo Telegram pairing code", e);
            return ResponseEntity.ok(new ChannelsStatus(TelegramStatus.unavailable(), whatsappStatus(session)));
        }
        return ResponseEntity.ok(buildStatus(session, null));
    }

    /** Visitor-initiated disconnect. Same effect as sending {@code /stop} to the bot. */
    @DeleteMapping("/telegram")
    public ResponseEntity<?> unlinkTelegram(
        @RequestHeader(value = DEMO_TOKEN_HEADER, required = false) String demoToken
    ) {
        Optional<DemoSession> found = demoSessionService.resolve(demoToken);
        if (found.isEmpty()) {
            return notFound();
        }
        try {
            linkService.revoke(found.get(), DemoChannelType.TELEGRAM);
        } catch (RuntimeException e) {
            log.warn("Could not revoke a demo Telegram link", e);
        }
        return ResponseEntity.ok(buildStatus(found.get(), null));
    }

    /**
     * Issues (or returns the existing) single-use WhatsApp pairing code for
     * this sandbox. Idempotent, like the Telegram one. Declines (returns the
     * status, which then says PREVIEW / not available) when no platform
     * WhatsApp number is configured.
     */
    @PostMapping("/whatsapp/pair")
    public ResponseEntity<?> pairWhatsApp(
        @RequestHeader(value = DEMO_TOKEN_HEADER, required = false) String demoToken
    ) {
        Optional<DemoSession> found = demoSessionService.resolve(demoToken);
        if (found.isEmpty()) {
            return notFound();
        }
        DemoSession session = found.get();
        if (session.isExpired(Instant.now()) || !whatsAppBot.isAvailable()) {
            return ResponseEntity.ok(buildStatus(session, null));
        }
        if (!rateLimiter.tryAcquire("wa-issue:" + session.getId())) {
            return ResponseEntity.ok(buildStatus(session, null));
        }
        try {
            linkService.issuePairingCode(session, DemoChannelType.WHATSAPP);
        } catch (RuntimeException e) {
            log.warn("Could not issue a demo WhatsApp pairing code", e);
        }
        return ResponseEntity.ok(buildStatus(session, null));
    }

    /** Visitor-initiated disconnect. Same effect as sending STOP to the number. */
    @DeleteMapping("/whatsapp")
    public ResponseEntity<?> unlinkWhatsApp(
        @RequestHeader(value = DEMO_TOKEN_HEADER, required = false) String demoToken
    ) {
        Optional<DemoSession> found = demoSessionService.resolve(demoToken);
        if (found.isEmpty()) {
            return notFound();
        }
        try {
            linkService.revoke(found.get(), DemoChannelType.WHATSAPP);
        } catch (RuntimeException e) {
            log.warn("Could not revoke a demo WhatsApp link", e);
        }
        return ResponseEntity.ok(buildStatus(found.get(), null));
    }

    /**
     * Records that this visitor used the WhatsApp preview.
     *
     * <p>Analytics only - it sends nothing, contacts nothing, and collects no
     * phone number. The reply the visitor sees in the WhatsApp frame comes
     * from the ordinary {@code POST /api/demo/chat} turn, which is why this
     * endpoint deliberately does NOT run a second one: duplicating that
     * controller's cap handling and fallback logic here would be two places to
     * get the "never show an error" contract wrong.
     */
    @PostMapping("/whatsapp/preview")
    public ResponseEntity<?> recordWhatsAppPreview(
        @RequestHeader(value = DEMO_TOKEN_HEADER, required = false) String demoToken
    ) {
        Optional<DemoSession> found = demoSessionService.resolve(demoToken);
        if (found.isEmpty()) {
            return notFound();
        }
        DemoSession session = found.get();
        try {
            if (rateLimiter.tryAcquire("wa-preview:" + session.getId())) {
                linkService.recordPreviewUse(session, DemoChannelType.WHATSAPP_PREVIEW);
            }
        } catch (RuntimeException e) {
            log.warn("Could not record a demo WhatsApp-preview use", e);
        }
        return ResponseEntity.ok(buildStatus(session, null));
    }

    // ------------------------------------------------------------------

    private ChannelsStatus buildStatus(DemoSession session, Instant since) {
        return new ChannelsStatus(telegramStatus(session, since), whatsappStatus(session));
    }

    private TelegramStatus telegramStatus(DemoSession session, Instant since) {
        if (!telegramBot.isAvailable()) {
            return TelegramStatus.unavailable();
        }
        Optional<DemoChannelLink> link = linkService.linkFor(session, DemoChannelType.TELEGRAM);
        if (link.isEmpty()) {
            return new TelegramStatus(
                true, telegramBot.botUsername(), "NONE", null, null, null, null, 0, List.of()
            );
        }
        DemoChannelLink l = link.get();
        List<ChannelMessageView> messages = l.isLinked()
            ? linkService.messagesSince(session, DemoChannelType.TELEGRAM, since).stream()
                .map(m -> new ChannelMessageView(m.getDirection().name(), m.getBody(), m.getCreatedAt()))
                .toList()
            : List.of();
        return new TelegramStatus(
            true,
            telegramBot.botUsername(),
            l.getState().name(),
            l.getPairingCode(),
            telegramBot.deepLinkFor(l.getPairingCode()),
            l.getPeerLabel(),
            l.getLinkedAt(),
            l.getMessageCount(),
            messages
        );
    }

    private WhatsAppStatus whatsappStatus(DemoSession session) {
        if (whatsAppBot.isAvailable()) {
            Optional<DemoChannelLink> live = linkService.linkFor(session, DemoChannelType.WHATSAPP);
            String code = live.map(DemoChannelLink::getPairingCode).orElse(null);
            return new WhatsAppStatus(
                "LIVE",
                live.map(DemoChannelLink::isLinked).orElse(false),
                live.map(DemoChannelLink::getMessageCount).orElse(0),
                true,
                live.map(l -> l.getState().name()).orElse("NONE"),
                code,
                whatsAppBot.deepLinkFor(code),
                whatsAppBot.businessNumberMasked()
            );
        }
        Optional<DemoChannelLink> link = linkService.linkFor(session, DemoChannelType.WHATSAPP_PREVIEW);
        return WhatsAppStatus.preview(
            link.map(DemoChannelLink::isLinked).orElse(false),
            link.map(DemoChannelLink::getMessageCount).orElse(0)
        );
    }

    private static Instant parseInstant(String raw) {
        if (raw == null || raw.isBlank() || raw.length() > 40) {
            return null;
        }
        try {
            return Instant.parse(raw.trim());
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /** Identical wording and status to {@code DemoSessionController}'s, so the page has one reset path. */
    private static ResponseEntity<?> notFound() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
            "error", "This sandbox isn't available any more. You can start a new one."
        ));
    }
}
