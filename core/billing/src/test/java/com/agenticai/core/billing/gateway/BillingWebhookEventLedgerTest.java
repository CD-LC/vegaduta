package com.agenticai.core.billing.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * V250 - {@link BillingWebhookEventLedger}'s claim semantics: first delivery
 * inserts (NEW), a redelivery loses the unique-index race (DUPLICATE), a
 * gateway without a delivery-id header dedupes on the body hash, and a
 * ledger outage fails OPEN so money events are never dropped by the dedupe
 * layer itself.
 */
class BillingWebhookEventLedgerTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final BillingWebhookEventLedger ledger = new BillingWebhookEventLedger(jdbc);

    @Test
    void firstDeliveryClaimsNewViaOnConflictInsert() {
        when(jdbc.update(contains("ON CONFLICT DO NOTHING"), any(), any(), any(), any(), any(), any()))
            .thenReturn(1);

        assertEquals(BillingWebhookEventLedger.Claim.NEW,
            ledger.claim("razorpay", "billing-dispatch", "evt_1", "subscription.charged", "{}"));
    }

    @Test
    void aRedeliveryLosesTheInsertRaceAndReportsDuplicate() {
        when(jdbc.update(contains("ON CONFLICT DO NOTHING"), any(), any(), any(), any(), any(), any()))
            .thenReturn(0);

        assertEquals(BillingWebhookEventLedger.Claim.DUPLICATE,
            ledger.claim("razorpay", "billing-dispatch", "evt_1", "subscription.charged", "{}"));
    }

    @Test
    void theDedupeKeyIsScopedPerGatewayConsumerAndEventId() {
        when(jdbc.update(anyString(), any(), any(), any(), any(), any(), any())).thenReturn(1);

        ledger.claim("razorpay", "tenant-state", "evt_9", "subscription.halted", "{}");

        // The WHERE-equivalent columns are bound as insert parameters; the
        // unique index is (gateway, consumer, gateway_event_id), so all
        // three must be distinct args, in that order, after the id.
        ArgumentCaptor<Object> args = ArgumentCaptor.forClass(Object.class);
        verify(jdbc).update(anyString(), args.capture(), args.capture(), args.capture(), args.capture(), args.capture(), args.capture());
        var values = args.getAllValues();
        assertEquals("razorpay", values.get(1));
        assertEquals("tenant-state", values.get(2));
        assertEquals("evt_9", values.get(3));
    }

    @Test
    void aGatewayWithoutDeliveryIdFallsBackToTheBodyHash() {
        when(jdbc.update(anyString(), any(), any(), any(), any(), any(), any())).thenReturn(1);

        ledger.claim("phonepe", "billing-dispatch", null, null, "{\"a\":1}");

        ArgumentCaptor<Object> key = ArgumentCaptor.forClass(Object.class);
        verify(jdbc).update(anyString(), any(), any(), any(), key.capture(), any(), any());
        assertTrue(String.valueOf(key.getValue()).startsWith("sha256:"),
            "headerless deliveries must dedupe on the raw-body hash");

        // Same body, same hash - a PhonePe retry is byte-identical.
        assertEquals(BillingWebhookEventLedger.eventKey(null, "{\"a\":1}"),
            BillingWebhookEventLedger.eventKey(null, "{\"a\":1}"));
        assertTrue(BillingWebhookEventLedger.eventKey(null, "{\"a\":1}")
            .equals(BillingWebhookEventLedger.eventKey("  ", "{\"a\":1}")),
            "a blank header is the same as no header");
    }

    @Test
    void aLedgerOutageFailsOpenSoTheEventStillProcesses() {
        when(jdbc.update(anyString(), any(), any(), any(), any(), any(), any()))
            .thenThrow(new DataAccessResourceFailureException("connection refused"));

        // Every handler behind the consumers is row-level idempotent, so
        // processing a delivery twice is safe; dropping it is not.
        assertEquals(BillingWebhookEventLedger.Claim.NEW,
            ledger.claim("razorpay", "billing-dispatch", "evt_2", "subscription.charged", "{}"));
    }

    @Test
    void releaseDeletesTheClaimSoARedeliveryRetries() {
        ledger.release("razorpay", "tenant-state", "evt_3", "{}", "boom");

        verify(jdbc).update(contains("DELETE FROM control_plane.billing_webhook_event"),
            eq("razorpay"), eq("tenant-state"), eq("evt_3"));
    }
}
