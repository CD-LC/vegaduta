package com.agenticai.core.omnichannel.slack;

import com.agenticai.core.security.edition.ConditionalOnEditionFeature;
import com.agenticai.core.security.edition.EditionFeature;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Verifies an inbound Slack request via Slack's own signing scheme - structurally different from
 * both Twilio's and Meta's, so it gets its own validator rather than reusing either: {@code
 * X-Slack-Signature} is {@code "v0=" + HMAC-SHA256(signing_secret, "v0:" + timestamp + ":" +
 * raw_body)}, with the timestamp taken from the separate {@code X-Slack-Request- Timestamp} header
 * and folded INTO the signed string itself (Meta and Twilio each sign only the body/params, no
 * timestamp component).
 *
 * <p>The timestamp is also independently checked for staleness (rejecting anything more than 5
 * minutes old) - Slack's own documented replay-attack mitigation, since a signature alone never
 * expires on its own.
 */
@Component
@ConditionalOnEditionFeature(EditionFeature.SOCIAL_CHANNELS)
public class SlackSignatureValidator {

    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final String SIGNATURE_PREFIX = "v0=";
    private static final long MAX_CLOCK_SKEW_SECONDS = 300;

    public void validateOrThrow(
            String rawBody, String timestampHeader, String signatureHeader, String signingSecret) {
        if (!StringUtils.hasText(timestampHeader)
                || !StringUtils.hasText(signatureHeader)
                || !signatureHeader.startsWith(SIGNATURE_PREFIX)) {
            throw new SlackWebhookSignatureException(
                    "Missing or malformed Slack signature headers - request did not come through Slack.");
        }

        long timestamp;
        try {
            timestamp = Long.parseLong(timestampHeader);
        } catch (NumberFormatException e) {
            throw new SlackWebhookSignatureException(
                    "X-Slack-Request-Timestamp was not a valid number.");
        }
        if (Math.abs(Instant.now().getEpochSecond() - timestamp) > MAX_CLOCK_SKEW_SECONDS) {
            throw new SlackWebhookSignatureException(
                    "X-Slack-Request-Timestamp is too old - possible replay attempt.");
        }

        String baseString = "v0:" + timestampHeader + ":" + rawBody;
        String expected = signatureHeader.substring(SIGNATURE_PREFIX.length());
        String computed = hmacSha256Hex(baseString, signingSecret);
        if (!MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                computed.getBytes(StandardCharsets.UTF_8))) {
            throw new SlackWebhookSignatureException(
                    "X-Slack-Signature did not match - either this request isn't really from Slack, or the wrong Signing Secret is configured.");
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
            throw new SlackWebhookSignatureException(
                    "Could not compute HMAC-SHA256: " + e.getMessage());
        }
    }
}
