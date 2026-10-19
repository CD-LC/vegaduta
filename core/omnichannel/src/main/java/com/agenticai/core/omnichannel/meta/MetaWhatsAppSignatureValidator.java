package com.agenticai.core.omnichannel.meta;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Verifies an inbound Meta webhook request via {@code X-Hub-Signature-256}
 * (HMAC-SHA256 over the raw request body, keyed by the tenant's own Meta App
 * Secret) - Meta's documented webhook security scheme, structurally
 * different from Twilio's {@code RequestValidator} (which signs the URL +
 * form params, not a raw JSON body) so this can't reuse {@code
 * TwilioSignatureValidator}. Hand-rolled HMAC via the JDK's {@code
 * javax.crypto} rather than a dependency - the same "no SDK for one HMAC
 * check" reasoning as {@link MetaGraphApiClient} skipping Meta's Business
 * SDK entirely.
 */
@Component
public class MetaWhatsAppSignatureValidator {

    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final String SIGNATURE_PREFIX = "sha256=";

    public void validateOrThrow(String rawBody, String signatureHeader, String appSecret) {
        if (!StringUtils.hasText(signatureHeader) || !signatureHeader.startsWith(SIGNATURE_PREFIX)) {
            throw new MetaWebhookSignatureException("Missing or malformed X-Hub-Signature-256 header - request did not come through Meta.");
        }
        String expected = signatureHeader.substring(SIGNATURE_PREFIX.length());
        String computed = hmacSha256Hex(rawBody, appSecret);
        // Constant-time comparison - a signature check is exactly the kind
        // of thing a naive String.equals() timing side-channel could leak
        // information from, however marginal.
        if (!MessageDigest.isEqual(
            expected.getBytes(StandardCharsets.UTF_8), computed.getBytes(StandardCharsets.UTF_8)
        )) {
            throw new MetaWebhookSignatureException(
                "X-Hub-Signature-256 did not match - either this request isn't really from Meta, or the wrong App Secret is configured."
            );
        }
    }

    private String hmacSha256Hex(String data, String key) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
            byte[] raw = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(raw.length * 2);
            for (byte b : raw) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new MetaWebhookSignatureException("Could not compute HMAC-SHA256: " + e.getMessage());
        }
    }
}
