package com.agenticai.core.billing.gateway;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.agenticai.core.billing.email.BillingEmailService;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * B9-PR4 revenue-path contract - {@link SubscriptionChargedHandler}, the
 * dispatcher-side consumer of {@code subscription.charged}. DB-free: the
 * JdbcTemplate is mocked, so what's pinned is the handler's decision logic
 * (when it writes, when it emails, when it bails) plus the SHAPE of its SQL
 * - the state guard in the UPDATE's WHERE clause is where this handler's
 * idempotency actually lives, since (unlike RazorpayWebhookService) it has
 * no in-memory entity state to check first.
 *
 * <p>Honest limitation, pinned rather than papered over: the DB write is
 * idempotent (the WHERE clause skips an already-ACTIVE row) but the receipt
 * EMAIL is not - a redelivered charged event re-sends the receipt (see
 * {@link #redeliveredChargedEventStillSendsAnotherReceiptEmail}). If that
 * test starts failing because dedup was added, that's an improvement -
 * update the pin, don't restore the double-send.
 */
class SubscriptionChargedHandlerTest {

    private static final String SUB_ID = "sub_42";
    private static final String TENANT_ID_STR = "3f0a2b1c-4d5e-6f70-8192-a3b4c5d6e7f8";
    private static final UUID TENANT_ID = UUID.fromString(TENANT_ID_STR);

    private JdbcTemplate jdbcTemplate;
    private BillingEmailService emailService;
    private SubscriptionChargedHandler handler;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        emailService = mock(BillingEmailService.class);
        handler = new SubscriptionChargedHandler(jdbcTemplate, emailService);
    }

    private BillingEvent chargedEvent(String subscriptionId) {
        return new BillingEvent(BillingEventType.SUBSCRIPTION_CHARGED, subscriptionId, "subscription.charged");
    }

    /** The full happy-path stubbing: tenant found, update applies, name resolves. */
    private void stubHealthyTenant(int rowsUpdated) {
        when(jdbcTemplate.queryForObject(
            startsWith("SELECT id FROM control_plane.tenant"), eq(String.class), eq(SUB_ID)))
            .thenReturn(TENANT_ID_STR);
        when(jdbcTemplate.update(anyString(), any(), any(), any(), any())).thenReturn(rowsUpdated);
        when(jdbcTemplate.queryForObject(
            startsWith("SELECT name FROM control_plane.tenant"), eq(String.class), eq(TENANT_ID)))
            .thenReturn("Acme");
    }

    @Test
    void chargedEventActivatesTheSubscriptionAndSendsExactlyOneReceipt() {
        stubHealthyTenant(1);

        handler.handle(chargedEvent(SUB_ID));

        // The UPDATE must carry a subscription_status guard in its WHERE
        // clause - that clause IS this handler's idempotency mechanism
        // (there is no pre-read of current state), so its presence is a
        // contract, not an implementation detail.
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).update(sql.capture(), eq("ACTIVE"), eq(TENANT_ID), eq("PENDING"), eq("ACTIVE"));
        assertTrue(sql.getValue().contains("SET subscription_status"),
            "UPDATE must target subscription_status");
        assertTrue(sql.getValue().contains("WHERE") && sql.getValue().contains("subscription_status"),
            "WHERE clause must state-guard on subscription_status - it is the only idempotency check");

        verify(emailService).sendReceiptEmail(TENANT_ID, "Acme");
    }

    @Test
    void redeliveredChargedEventStillSendsAnotherReceiptEmail() {
        // updated == 0 means the row was already ACTIVE (a Razorpay
        // redelivery). The DB write is a no-op - correct - but today the
        // receipt email is sent AGAIN. Pinned as actual behavior; see class
        // javadoc for why this is a documented gap, not an endorsement.
        stubHealthyTenant(0);

        handler.handle(chargedEvent(SUB_ID));

        verify(emailService).sendReceiptEmail(TENANT_ID, "Acme");
    }

    @Test
    void eventWithoutASubscriptionIdTouchesNothing() {
        handler.handle(chargedEvent(null));
        handler.handle(chargedEvent("  "));

        // No id -> no way to attribute money to a tenant -> must not guess.
        verifyNoInteractions(jdbcTemplate, emailService);
    }

    @Test
    void unknownSubscriptionIdIsSkippedWithoutWritesOrEmail() {
        // Razorpay replaying an event for a subscription this platform no
        // longer tracks (handler javadoc calls this out as expected) - the
        // lookup failing must end the story, not cascade.
        when(jdbcTemplate.queryForObject(
            startsWith("SELECT id FROM control_plane.tenant"), eq(String.class), eq(SUB_ID)))
            .thenThrow(new EmptyResultDataAccessException(1));

        assertDoesNotThrow(() -> handler.handle(chargedEvent(SUB_ID)));

        verify(jdbcTemplate, never()).update(anyString(), any(), any(), any(), any());
        verifyNoInteractions(emailService);
    }

    @Test
    void updateFailureAbortsBeforeAnyEmailIsSent() {
        // Money-state first: if ACTIVE was never recorded, sending a receipt
        // would tell the customer something the database doesn't believe.
        when(jdbcTemplate.queryForObject(
            startsWith("SELECT id FROM control_plane.tenant"), eq(String.class), eq(SUB_ID)))
            .thenReturn(TENANT_ID_STR);
        when(jdbcTemplate.update(anyString(), any(), any(), any(), any()))
            .thenThrow(new RuntimeException("db down"));

        assertDoesNotThrow(() -> handler.handle(chargedEvent(SUB_ID)));

        verifyNoInteractions(emailService);
    }

    @Test
    void emailFailureDoesNotPropagateOutOfTheHandler() {
        // The subscription is already ACTIVE by the time email runs - a mail
        // outage must not bubble up and make the webhook look undelivered
        // (Razorpay would retry, and the retry would re-send email anyway).
        stubHealthyTenant(1);
        doThrow(new RuntimeException("smtp down")).when(emailService).sendReceiptEmail(any(), anyString());

        assertDoesNotThrow(() -> handler.handle(chargedEvent(SUB_ID)));
    }
}
