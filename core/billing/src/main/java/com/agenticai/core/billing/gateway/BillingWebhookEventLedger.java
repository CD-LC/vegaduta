package com.agenticai.core.billing.gateway;

import com.agenticai.core.security.edition.ConditionalOnEditionFeature;
import com.agenticai.core.security.edition.EditionFeature;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Delivery-id dedupe for inbound billing webhooks (the "event-id dedupe" half of the
 * carried-forward Razorpay follow-up). Every verified delivery is claimed in {@code
 * control_plane.billing_webhook_event} (V250) BEFORE the consumer's processing runs; a claim that
 * loses the unique-index race is a redelivery and is skipped instead of re-applied.
 *
 * <p><b>Why per-consumer keys:</b> two ingestion paths process the same Razorpay event stream -
 * {@code /webhook/razorpay} ({@code RazorpayWebhookService}, tenant plan state) and {@code
 * /api/billing/webhook/{gateway}} ({@link BillingEventDispatcher}: dunning, checkout-session
 * completion). If the dashboard ever delivers to both, both deliveries carry the SAME {@code
 * x-razorpay-event-id}; keying the claim on {@code (gateway, consumer, event id)} keeps each path's
 * dedupe independent so neither can suppress the other.
 *
 * <p><b>Gateways without a delivery id</b> (PhonePe sends none): the claim key falls back to {@code
 * "sha256:<hex of raw body>"}, which still dedupes the byte-identical replays a retry produces.
 *
 * <p><b>Fail-open, deliberately:</b> every handler behind both consumers is written row-level
 * idempotent already (state-guarded UPDATEs, COMPLETED sessions never re-complete, the pending
 * handler's attempt counter), so a ledger outage degrades to pre-dedupe behaviour rather than
 * dropping real money events - the caller releases the claim on failure so the gateway's own retry
 * re-claims cleanly (see {@link #release}).
 *
 * <p>JdbcTemplate, not JPA - same posture as {@link SubscriptionPendingHandler}: this class is used
 * from core/tenancy as well and must not drag entity scanning or a module cycle in with it.
 */
@Service
@ConditionalOnEditionFeature(EditionFeature.BILLING_PAYMENTS)
public class BillingWebhookEventLedger {

    private static final Logger log = LoggerFactory.getLogger(BillingWebhookEventLedger.class);

    /**
     * Outcome of {@link #claim}: NEW = first delivery, process it; DUPLICATE = already claimed,
     * skip.
     */
    public enum Claim {
        NEW,
        DUPLICATE
    }

    private final JdbcTemplate jdbcTemplate;

    public BillingWebhookEventLedger(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Records a verified delivery as RECEIVED and reports whether the caller is the first to see
     * it. Only call AFTER signature verification - the ledger is a record of authentic deliveries,
     * not a request log.
     *
     * @param consumer short stable tag for the ingestion path (e.g. {@code "tenant-state"}, {@code
     *     "billing-dispatch"}) - part of the dedupe key, see class javadoc
     * @param eventIdHeader the gateway's delivery-id header value (Razorpay: {@code
     *     x-razorpay-event-id}); null/blank falls back to a body hash
     * @return NEW when the row was inserted by this call, DUPLICATE when the (gateway, consumer,
     *     event id) key was already claimed. On a ledger failure this returns NEW (fail-open - see
     *     class javadoc) so a dedupe outage never drops a payment event.
     */
    public Claim claim(
            String gateway,
            String consumer,
            String eventIdHeader,
            String eventType,
            String rawBody) {
        String eventKey = eventKey(eventIdHeader, rawBody);
        try {
            int inserted =
                    jdbcTemplate.update(
                            "INSERT INTO control_plane.billing_webhook_event "
                                    + "(id, gateway, consumer, gateway_event_id, event_type, payload) "
                                    + "VALUES (?, ?, ?, ?, ?, ?::jsonb) ON CONFLICT DO NOTHING",
                            UUID.randomUUID(),
                            gateway,
                            consumer,
                            eventKey,
                            eventType,
                            rawBody);
            if (inserted == 0) {
                log.info(
                        "Billing webhook duplicate: gateway={} consumer={} event={} type={} - skipping reprocessing",
                        gateway,
                        consumer,
                        eventKey,
                        eventType);
                return Claim.DUPLICATE;
            }
            return Claim.NEW;
        } catch (DataAccessException e) {
            // Fail-open (class javadoc): the row-level idempotency every
            // handler already has is the safety net; losing the event
            // entirely is the worse failure.
            log.error(
                    "Webhook event ledger claim failed for gateway={} consumer={} event={} - processing without dedupe",
                    gateway,
                    consumer,
                    eventKey,
                    e);
            return Claim.NEW;
        }
    }

    /** Marks a claimed delivery PROCESSED. Best-effort: failures are logged, never thrown. */
    public void markProcessed(
            String gateway, String consumer, String eventIdHeader, String rawBody) {
        markDone(gateway, consumer, eventIdHeader, rawBody, "PROCESSED", null);
    }

    /** Marks a claimed delivery SKIPPED (verified, but nothing to apply). */
    public void markSkipped(String gateway, String consumer, String eventIdHeader, String rawBody) {
        markDone(gateway, consumer, eventIdHeader, rawBody, "SKIPPED", null);
    }

    private void markDone(
            String gateway,
            String consumer,
            String eventIdHeader,
            String rawBody,
            String status,
            String error) {
        try {
            jdbcTemplate.update(
                    "UPDATE control_plane.billing_webhook_event SET processing_status = ?, error = ?, processed_at = ? "
                            + "WHERE gateway = ? AND consumer = ? AND gateway_event_id = ?",
                    status,
                    error,
                    Instant.now(),
                    gateway,
                    consumer,
                    eventKey(eventIdHeader, rawBody));
        } catch (DataAccessException e) {
            log.warn(
                    "Webhook event ledger status update failed ({} -> {}) - ledger row left as-is",
                    status,
                    gateway,
                    e);
        }
    }

    /**
     * Releases a claim after a processing failure so the gateway's own redelivery re-claims and
     * retries the work (a FAILED-kept row would dedupe that retry away and lose the event for
     * good). Best-effort: a failed release leaves the row, which then dedupes retries - the
     * conservative direction (no double-processing) for a money path.
     */
    public void release(
            String gateway, String consumer, String eventIdHeader, String rawBody, String error) {
        try {
            jdbcTemplate.update(
                    "DELETE FROM control_plane.billing_webhook_event "
                            + "WHERE gateway = ? AND consumer = ? AND gateway_event_id = ?",
                    gateway,
                    consumer,
                    eventKey(eventIdHeader, rawBody));
        } catch (DataAccessException e) {
            log.warn(
                    "Webhook event ledger release failed for gateway={} consumer={} - later redeliveries will dedupe",
                    gateway,
                    consumer,
                    e);
        }
    }

    /**
     * The dedupe key: the gateway's delivery id when it sent one, else {@code "sha256:<hex>"} over
     * the raw body (a redelivery is byte-identical, so the hash still suppresses it).
     */
    static String eventKey(String eventIdHeader, String rawBody) {
        if (eventIdHeader != null && !eventIdHeader.isBlank()) {
            return eventIdHeader.trim();
        }
        return "sha256:" + HexFormat.of().formatHex(sha256(rawBody == null ? "" : rawBody));
    }

    private static byte[] sha256(String s) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
