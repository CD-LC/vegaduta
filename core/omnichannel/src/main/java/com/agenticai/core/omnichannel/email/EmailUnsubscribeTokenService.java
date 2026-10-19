package com.agenticai.core.omnichannel.email;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Locale;
import java.util.Optional;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Mints and verifies the stateless HMAC token behind the public unsubscribe
 * link. The unsubscribe endpoint is unauthenticated (a recipient has no
 * session), so the token itself carries the tenant + address and is what stops
 * anyone from suppressing arbitrary addresses in arbitrary tenants.
 *
 * <p>Key: {@code app.mail.unsubscribe-secret}, falling back to {@code
 * app.encryption.key} (identical on every core replica by deployment rule, so
 * a link minted by one replica verifies on another). With neither configured,
 * or with no {@code app.omnichannel.public-base-url} to build an absolute link
 * from, {@link #isConfigured()} is false and senders omit the link honestly
 * rather than emit a dead one.
 */
@Component
public class EmailUnsubscribeTokenService {

    public static final String PATH = "/api/public/email/unsubscribe";

    /** Verified token contents. */
    public record Claims(String tenantId, String email) {
    }

    private final byte[] key;
    private final String baseUrl;

    @Autowired
    public EmailUnsubscribeTokenService(
        @Value("${app.mail.unsubscribe-secret:}") String dedicatedSecret,
        @Value("${app.encryption.key:}") String encryptionKey,
        @Value("${app.omnichannel.public-base-url:}") String publicBaseUrl
    ) {
        this(dedicatedSecret != null && !dedicatedSecret.isBlank() ? dedicatedSecret : encryptionKey, publicBaseUrl);
    }

    public EmailUnsubscribeTokenService(String secret, String publicBaseUrl) {
        this.key = secret == null || secret.isBlank() ? null : ("email-unsub:" + secret).getBytes(StandardCharsets.UTF_8);
        String base = publicBaseUrl == null ? "" : publicBaseUrl.trim();
        this.baseUrl = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
    }

    public boolean isConfigured() {
        return key != null && !baseUrl.isEmpty();
    }

    /** Absolute unsubscribe URL, or null when {@link #isConfigured()} is false. */
    public String unsubscribeUrl(String tenantId, String email) {
        if (!isConfigured() || tenantId == null || email == null) {
            return null;
        }
        return baseUrl + PATH + "?t=" + createToken(tenantId, email);
    }

    public String createToken(String tenantId, String email) {
        String payload = b64(tenantId + "\n" + email.trim().toLowerCase(Locale.ROOT));
        return payload + "." + b64(hmac(payload));
    }

    public Optional<Claims> verify(String token) {
        if (key == null || token == null) {
            return Optional.empty();
        }
        int dot = token.indexOf('.');
        if (dot <= 0 || dot == token.length() - 1) {
            return Optional.empty();
        }
        String payload = token.substring(0, dot);
        try {
            byte[] presented = Base64.getUrlDecoder().decode(token.substring(dot + 1));
            if (!MessageDigest.isEqual(presented, hmac(payload))) {
                return Optional.empty();
            }
            String decoded = new String(Base64.getUrlDecoder().decode(payload), StandardCharsets.UTF_8);
            int nl = decoded.indexOf('\n');
            if (nl <= 0 || nl == decoded.length() - 1) {
                return Optional.empty();
            }
            return Optional.of(new Claims(decoded.substring(0, nl), decoded.substring(nl + 1)));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private byte[] hmac(String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }

    private static String b64(String s) {
        return b64(s.getBytes(StandardCharsets.UTF_8));
    }

    private static String b64(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
