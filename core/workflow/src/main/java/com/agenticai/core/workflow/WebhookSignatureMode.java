package com.agenticai.core.workflow;

/**
 * How a WEBHOOK-channel trigger node verifies the sender beyond possession of
 * the URL token (V482). {@link #NONE} is the original #163 behavior - the URL
 * token alone fires the run. Every other mode adds an HMAC over the RAW
 * request body against a per-node secret, so a leaked URL alone can no longer
 * trigger the workflow:
 *
 * <ul>
 *   <li>{@link #GITHUB} - {@code X-Hub-Signature-256: sha256=<hex>},
 *       HMAC-SHA256(secret, rawBody)</li>
 *   <li>{@link #STRIPE} - {@code Stripe-Signature: t=<unix>,v1=<hex>[,v0=...]},
 *       HMAC-SHA256(secret, "t." + rawBody), and {@code t} must be within the
 *       replay tolerance of now</li>
 *   <li>{@link #SLACK} - {@code X-Slack-Signature: v0=<hex>} +
 *       {@code X-Slack-Request-Timestamp: <unix>}, HMAC-SHA256(secret,
 *       "v0:t:" + rawBody), same replay tolerance</li>
 *   <li>{@link #HMAC_SHA256} - generic: {@code X-Signature-256} carrying a hex
 *       digest, with an optional {@code "sha256="} prefix - for senders that
 *       sign but follow none of the named schemes (or a tunnel/front proxy
 *       that re-signs)</li>
 * </ul>
 *
 * <p>The trigger node stores this as a plain {@code String} (see {@link
 * WebhookAuthMode#fromNullableName} for the module-direction reason), so
 * every reader converts through {@link #fromNullableName} at the boundary;
 * unrecognized/blank values degrade to {@link #NONE} = "no verification
 * configured", the pre-V482 behavior.
 */
public enum WebhookSignatureMode {
    NONE,
    GITHUB,
    STRIPE,
    SLACK,
    HMAC_SHA256;

    public static WebhookSignatureMode fromNullableName(String raw) {
        if (raw == null || raw.isBlank()) {
            return NONE;
        }
        try {
            return valueOf(raw);
        } catch (IllegalArgumentException e) {
            return NONE;
        }
    }
}
