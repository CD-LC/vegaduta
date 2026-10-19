package com.agenticai.core.omnichannel.demo;

import com.agenticai.core.security.startup.BackgroundStartupTask;
import com.agenticai.core.tenancy.PlatformSettingsService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * The SHARED platform demo bot on Telegram - one bot, serving every unregistered visitor's sandbox
 * at once.
 *
 * <h2>Why Telegram is the channel an anonymous visitor can actually have</h2>
 *
 * A Telegram bot is free, needs no business verification, and - decisively - ONE bot can hold a
 * separate conversation with an unlimited number of people. That is what makes a shared platform
 * bot possible: the visitor does not create anything, connect anything, or hand over anything. They
 * tap a link, press Start, and their sandbox agent answers them on their own phone. Every other
 * messenger this platform supports fails at least one of those tests for an anonymous visitor - see
 * {@link DemoChannelController}'s javadoc for the WhatsApp reasoning specifically.
 *
 * <h2>Absent unless configured</h2>
 *
 * {@code app.demo.telegram.bot-token} is blank by default and there is no fallback: with no token,
 * {@link #isAvailable()} returns false, the {@code /try} page never renders the Telegram card, the
 * pairing endpoint says so politely, and the webhook is a no-op 200. Same "feature absent unless
 * configured" posture as Embedded Signup. The token is read from the environment, never written to
 * a log, never returned by any endpoint, and never rendered in the UI.
 *
 * <h2>The webhook secret is derived, not another env var to forget</h2>
 *
 * Telegram's own inbound auth is a {@code secret_token} echoed back in {@code
 * X-Telegram-Bot-Api-Secret-Token}. The per-tenant BYOK path ({@code TelegramCredentialService})
 * generates a random one and persists it - it can, it has a row to put it in. This bot has no row
 * and no tenant, so a random per-boot secret would break every replica that did not generate it and
 * would silently un-register the webhook on restart. Deriving it from the bot token instead gives a
 * value that is stable across restarts and replicas, unguessable without the token, and impossible
 * to leave misconfigured. {@code app.demo.telegram.webhook-secret} overrides it if an operator
 * wants to rotate independently of the token.
 */
@Component
public class DemoTelegramBot {

    private static final Logger log = LoggerFactory.getLogger(DemoTelegramBot.class);

    /**
     * Domain separator so the derived webhook secret can never coincide with any other digest of
     * the token.
     */
    private static final String SECRET_DERIVATION_SALT = ":vegaduta-demo-telegram-webhook:v1";

    /**
     * Path this bot's updates arrive on. Under {@code /webhook/**}, which {@code SecurityConfig}
     * permits unauthenticated.
     */
    public static final String WEBHOOK_PATH = "/webhook/demo/telegram";

    private final DemoTelegramBotApi apiClient;
    private final PlatformSettingsService platformSettings;
    private final String botToken;
    private final String configuredUsername;
    private final String configuredSecret;
    private final String webhookBaseUrl;
    private final boolean autoRegisterWebhook;

    /**
     * Resolved once at startup from {@code getMe} when not configured explicitly. Volatile because
     * it is written on the {@link ApplicationReadyEvent} thread and read by request threads.
     */
    private volatile String resolvedUsername;

    /**
     * Cold-start lane: when {@code app.startup.background-warmup.enabled=true}, {@link
     * #initialise()} (two Telegram HTTP calls) runs on a background thread so a slow Telegram API
     * cannot delay readiness - an {@code ApplicationReadyEvent} listener runs BEFORE readiness
     * flips, despite this class's older "never delays startup" note. Field-injected so the
     * constructor is unchanged; false (the default) keeps the old synchronous behaviour exactly.
     */
    @Value("${app.startup.background-warmup.enabled:false}")
    private boolean backgroundWarmup;

    public DemoTelegramBot(
            DemoTelegramBotApi apiClient,
            PlatformSettingsService platformSettings,
            @Value("${app.demo.telegram.bot-token:}") String botToken,
            @Value("${app.demo.telegram.bot-username:}") String configuredUsername,
            @Value("${app.demo.telegram.webhook-secret:}") String configuredSecret,
            // Falls back to the per-tenant Telegram base URL, because it is the
            // same backend behind the same public hostname - one fewer thing to
            // set, and one fewer way to have inbound silently not arrive.
            @Value("${app.demo.telegram.webhook-base-url:${app.telegram.webhook-base-url:}}")
                    String webhookBaseUrl,
            @Value("${app.demo.telegram.auto-register-webhook:true}") boolean autoRegisterWebhook) {
        this.apiClient = apiClient;
        this.platformSettings = platformSettings;
        this.botToken = botToken == null ? "" : botToken.trim();
        this.configuredUsername = normalizeUsername(configuredUsername);
        this.configuredSecret = configuredSecret == null ? "" : configuredSecret.trim();
        this.webhookBaseUrl = webhookBaseUrl == null ? "" : webhookBaseUrl.trim();
        this.autoRegisterWebhook = autoRegisterWebhook;
        this.resolvedUsername = this.configuredUsername;
    }

    /**
     * True when this deployment can actually offer the affordance. Checked by both the
     * browser-facing endpoint (so the card hides) and the webhook (so a stray call is a no-op) -
     * never assume one implies the other.
     *
     * <p>The platform setting is a live kill switch, matching {@code demo.enabled}: an operator can
     * pull the Telegram bridge without a redeploy if the shared bot starts attracting the wrong
     * kind of traffic.
     */
    public boolean isAvailable() {
        return StringUtils.hasText(botToken)
                && StringUtils.hasText(resolvedUsername)
                && platformSettings.getBoolean("demo.telegram.enabled", true);
    }

    /**
     * Configured even if {@code getMe} has not resolved a username yet - what the webhook needs to
     * answer at all.
     */
    public boolean isConfigured() {
        return StringUtils.hasText(botToken);
    }

    public String botUsername() {
        return resolvedUsername;
    }

    /**
     * {@code https://t.me/<bot>?start=VD-7F3K} - one tap from the {@code /try} page into a real
     * Telegram chat.
     */
    public String deepLinkFor(String pairingCode) {
        if (!isAvailable() || pairingCode == null || pairingCode.isBlank()) {
            return null;
        }
        return "https://t.me/" + resolvedUsername + "?start=" + pairingCode;
    }

    /**
     * Constant-time comparison of Telegram's echoed secret token. Constant time because this is the
     * ONLY thing standing between an anonymous internet caller and the ability to inject fabricated
     * "inbound messages" into other people's sandboxes.
     */
    public boolean verifyWebhookSecret(String presented) {
        if (presented == null) {
            return false;
        }
        byte[] expected = webhookSecret().getBytes(StandardCharsets.UTF_8);
        byte[] actual = presented.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expected, actual);
    }

    /**
     * Best-effort outbound. Never throws: a send failure must not turn into a 500 on Telegram's
     * webhook call.
     */
    public boolean sendMessage(String chatId, String text) {
        if (!isConfigured() || chatId == null || text == null || text.isBlank()) {
            return false;
        }
        try {
            apiClient.sendMessage(botToken, chatId, truncateForTelegram(text));
            return true;
        } catch (RuntimeException e) {
            // Message, not stack trace, and the chat id is masked - this line
            // can fire once per inbound message on a bad day.
            log.warn("Demo Telegram send to chat {} failed: {}", mask(chatId), e.getMessage());
            return false;
        }
    }

    /** Native "typing…" while the model works. Pure UX, already swallowed by the client. */
    public void sendTyping(String chatId) {
        if (isConfigured() && chatId != null) {
            apiClient.sendTypingIndicator(botToken, chatId);
        }
    }

    /**
     * Resolves the bot's username and points Telegram at this backend.
     *
     * <p>Runs at {@link ApplicationReadyEvent} rather than in the constructor so a slow or
     * unreachable Telegram API can never delay or fail startup - this is a demo affordance, not a
     * boot dependency. Every failure below leaves the feature simply unavailable, with the rest of
     * the platform untouched.
     *
     * <p>No-ops entirely when the token is unset, which is the default - so nothing here fires in
     * tests or on a deployment that has not opted in.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void initialise() {
        BackgroundStartupTask.run(backgroundWarmup, "demo-telegram-init", this::initialiseNow);
    }

    /** The original body of {@link #initialise()}, unchanged. */
    void initialiseNow() {
        if (!isConfigured()) {
            log.debug(
                    "Demo Telegram bot not configured (app.demo.telegram.bot-token unset) - the affordance stays hidden");
            return;
        }
        if (!StringUtils.hasText(resolvedUsername)) {
            try {
                resolvedUsername = normalizeUsername(apiClient.fetchBotUsername(botToken));
                log.info("Demo Telegram bot resolved as @{}", resolvedUsername);
            } catch (RuntimeException e) {
                log.warn(
                        "Could not reach Telegram to resolve the demo bot's username - "
                                + "the Telegram affordance stays hidden. Set app.demo.telegram.bot-username to skip this call. ({})",
                        e.getMessage());
                return;
            }
        }
        if (!autoRegisterWebhook) {
            log.info(
                    "Demo Telegram webhook auto-registration disabled - register {} manually",
                    WEBHOOK_PATH);
            return;
        }
        if (!StringUtils.hasText(webhookBaseUrl)) {
            log.warn(
                    "Demo Telegram bot @{} is configured but no public webhook base URL is set "
                            + "(app.demo.telegram.webhook-base-url or app.telegram.webhook-base-url) - "
                            + "visitors will get a pairing code that can never be redeemed, so the affordance stays hidden",
                    resolvedUsername);
            // Deliberately blanks the username rather than leaving a
            // half-working feature: isAvailable() now returns false, and the
            // /try page shows nothing instead of a dead-end code.
            resolvedUsername = null;
            return;
        }
        try {
            apiClient.setWebhook(botToken, webhookBaseUrl + WEBHOOK_PATH, webhookSecret());
            log.info(
                    "Demo Telegram webhook registered for @{} at {}{}",
                    resolvedUsername,
                    webhookBaseUrl,
                    WEBHOOK_PATH);
        } catch (RuntimeException e) {
            log.warn("Could not register the demo Telegram webhook: {}", e.getMessage());
        }
    }

    // ------------------------------------------------------------------

    private String webhookSecret() {
        if (StringUtils.hasText(configuredSecret)) {
            return configuredSecret;
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of()
                    .formatHex(
                            digest.digest(
                                    (botToken + SECRET_DERIVATION_SALT)
                                            .getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /**
     * Telegram rejects a sendMessage body over 4096 UTF-16 units outright, so clamp rather than
     * lose the reply.
     */
    private static String truncateForTelegram(String text) {
        return text.length() <= 4000 ? text : text.substring(0, 3997) + "…";
    }

    private static String normalizeUsername(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        if (trimmed.startsWith("@")) {
            trimmed = trimmed.substring(1);
        }
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String mask(String value) {
        return value == null || value.length() < 4
                ? "***"
                : "***" + value.substring(value.length() - 4);
    }
}
