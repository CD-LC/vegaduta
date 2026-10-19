package com.agenticai.core.omnichannel.demo;

import com.agenticai.core.tenancy.PlatformSettingsService;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * The SHARED, PLATFORM-owned Meta WhatsApp Business number behind the /try Studio "Deploy" WhatsApp
 * card - one number, every unregistered visitor's sandbox behind it (design "A": wa.me
 * click-to-chat prefilled with a single-use pairing code).
 *
 * <h2>What this is NOT</h2>
 *
 * Not an unofficial "link your own device" QR session (ToS ban risk, exposes the visitor's
 * contacts) - deliberately not built. And not a way to message a visitor first: the visitor
 * messages THIS number, which opens Meta's 24-hour customer-service window, and every reply here is
 * inside it. Nothing here sends a template or a proactive message, and no copy may promise one.
 *
 * <h2>Absent unless configured</h2>
 *
 * All of {@code app.demo.whatsapp.{phone-number-id, access-token, app-secret, business-number-e164,
 * verify-token}} are blank by default. Until they are all set, {@link #isAvailable()} is false, the
 * {@code /try} card hides, the pairing endpoint declines and the webhook is a no-op 200. On top of
 * that {@code demo.whatsapp.enabled} is a live platform-settings kill switch, the same mechanism as
 * {@code demo.telegram.enabled}.
 *
 * <h2>The visitor's number is never stored</h2>
 *
 * {@link #hashPhone} is a keyed HMAC-SHA256 (key = {@code id-hash-key}, or the app secret when
 * unset), so a database leak does not yield a dictionary-attackable phone list. The hash is the
 * {@code external_id} in {@code demo_channel_link}, the rate-limiter bucket and the per-phone
 * lifetime counter key. The raw number is used only in-memory for the one reply send.
 */
@Component
public class DemoWhatsAppBot {

    private static final Logger log = LoggerFactory.getLogger(DemoWhatsAppBot.class);

    /** Under {@code /webhook/**}, which {@code SecurityConfig} permits unauthenticated. */
    public static final String WEBHOOK_PATH = "/webhook/demo/whatsapp";

    private static final String SIGNATURE_PREFIX = "sha256=";
    private static final String HASH_DOMAIN = "vegaduta-demo-whatsapp-phone:v1:";

    private final DemoWhatsAppCloudApi metaClient;
    private final PlatformSettingsService platformSettings;
    private final String phoneNumberId;
    private final String accessToken;
    private final String appSecret;
    private final String businessDigits;
    private final String verifyToken;
    private final String idHashKey;

    public DemoWhatsAppBot(
            DemoWhatsAppCloudApi metaClient,
            PlatformSettingsService platformSettings,
            @Value("${app.demo.whatsapp.phone-number-id:}") String phoneNumberId,
            @Value("${app.demo.whatsapp.access-token:}") String accessToken,
            @Value("${app.demo.whatsapp.app-secret:}") String appSecret,
            @Value("${app.demo.whatsapp.business-number-e164:}") String businessNumberE164,
            @Value("${app.demo.whatsapp.verify-token:}") String verifyToken,
            @Value("${app.demo.whatsapp.id-hash-key:}") String idHashKey) {
        this.metaClient = metaClient;
        this.platformSettings = platformSettings;
        this.phoneNumberId = trim(phoneNumberId);
        this.accessToken = trim(accessToken);
        this.appSecret = trim(appSecret);
        this.businessDigits = digitsOnly(businessNumberE164);
        this.verifyToken = trim(verifyToken);
        this.idHashKey = StringUtils.hasText(idHashKey) ? idHashKey.trim() : this.appSecret;
    }

    /** All five settings present. What the webhook needs to do anything at all. */
    public boolean isConfigured() {
        return StringUtils.hasText(phoneNumberId)
                && StringUtils.hasText(accessToken)
                && StringUtils.hasText(appSecret)
                && StringUtils.hasText(businessDigits)
                && StringUtils.hasText(verifyToken);
    }

    /** Configured AND not pulled by the live {@code demo.whatsapp.enabled} kill switch. */
    public boolean isAvailable() {
        return isConfigured() && platformSettings.getBoolean("demo.whatsapp.enabled", true);
    }

    public String phoneNumberId() {
        return phoneNumberId;
    }

    /**
     * {@code https://wa.me/<E164 digits>?text=<url-encoded code>} - one tap/scan from /try into a
     * real WhatsApp chat.
     */
    public String deepLinkFor(String pairingCode) {
        if (!isAvailable() || pairingCode == null || pairingCode.isBlank()) {
            return null;
        }
        return "https://wa.me/"
                + businessDigits
                + "?text="
                + URLEncoder.encode(pairingCode.trim(), StandardCharsets.UTF_8);
    }

    /**
     * A display form of the platform number that does not print it whole, e.g. {@code +91 •••••
     * ••123}. Null when unset.
     */
    public String businessNumberMasked() {
        if (!StringUtils.hasText(businessDigits) || businessDigits.length() < 6) {
            return null;
        }
        return "+"
                + businessDigits.substring(0, 2)
                + " ••••• ••"
                + businessDigits.substring(businessDigits.length() - 3);
    }

    /** Constant-time compare of Meta's GET-handshake {@code hub.verify_token}. */
    public boolean verifyToken(String presented) {
        if (presented == null || !StringUtils.hasText(verifyToken)) {
            return false;
        }
        return MessageDigest.isEqual(
                presented.getBytes(StandardCharsets.UTF_8),
                verifyToken.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * {@code X-Hub-Signature-256} = {@code sha256=} + hex HMAC-SHA256 of the RAW request body under
     * the platform Meta app secret, compared in constant time. Takes the raw bytes (not a
     * re-decoded String) so the digest is over exactly what Meta signed.
     */
    public boolean verifySignature(byte[] rawBody, String signatureHeader) {
        if (rawBody == null
                || !StringUtils.hasText(appSecret)
                || signatureHeader == null
                || !signatureHeader.startsWith(SIGNATURE_PREFIX)) {
            return false;
        }
        String presented =
                signatureHeader
                        .substring(SIGNATURE_PREFIX.length())
                        .trim()
                        .toLowerCase(java.util.Locale.ROOT);
        String computed = HexFormat.of().formatHex(hmac(appSecret, rawBody));
        return MessageDigest.isEqual(
                presented.getBytes(StandardCharsets.UTF_8),
                computed.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Keyed hash of a sender's number (any formatting) - the only form in which it is ever stored
     * or bucketed.
     */
    public String hashPhone(String phone) {
        String digits = digitsOnly(phone);
        if (digits.isEmpty()) {
            return null;
        }
        return HexFormat.of()
                .formatHex(
                        hmac(idHashKey, (HASH_DOMAIN + digits).getBytes(StandardCharsets.UTF_8)));
    }

    /** Best-effort reply inside the 24h window. Never throws; the number is masked in logs. */
    public boolean sendText(String toE164, String body) {
        if (!isConfigured() || !StringUtils.hasText(toE164) || !StringUtils.hasText(body)) {
            return false;
        }
        try {
            metaClient.sendTextMessage(phoneNumberId, accessToken, toE164, truncate(body));
            return true;
        } catch (RuntimeException e) {
            log.warn("Demo WhatsApp send to {} failed: {}", mask(toE164), e.getMessage());
            return false;
        }
    }

    /** Last four only - safe for logs. */
    public static String mask(String value) {
        return value == null || value.length() < 4
                ? "***"
                : "***" + value.substring(value.length() - 4);
    }

    // ------------------------------------------------------------------

    private static byte[] hmac(String key, byte[] data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return mac.doFinal(data);
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }

    private static String truncate(String text) {
        return text.length() <= 4000 ? text : text.substring(0, 3997) + "...";
    }

    private static String digitsOnly(String raw) {
        return raw == null ? "" : raw.replaceAll("[^0-9]", "");
    }

    private static String trim(String raw) {
        return raw == null ? "" : raw.trim();
    }
}
