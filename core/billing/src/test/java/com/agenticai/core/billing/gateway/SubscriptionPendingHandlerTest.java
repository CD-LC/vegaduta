package com.agenticai.core.billing.gateway;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.agenticai.core.billing.email.BillingEmailService;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * B9-PR4 revenue-path contract - {@link SubscriptionPendingHandler}, the
 * sibling of {@link SubscriptionChargedHandler} for Razorpay's retry-engine
 * {@code subscription.pending} events. Same DB-free posture as that
 * handler's suite: JdbcTemplate mocked, so the pinned contract is the
 * decision logic plus the SQL shape - the {@code CASE WHEN past_due_since
 * IS NULL} clause ("first failure wins"), plus the D1/D5 dunning behavior:
 * the attempt counter riding on {@code subscription_status}
 * (PENDING -> PENDING:2 -> PENDING:3), which email each attempt sends, and
 * the {@link SubscriptionPaymentExhaustedEvent} published once the counter
 * reaches the configured max (3 here, matching the production default).
 */
class SubscriptionPendingHandlerTest {

    private static final String SUB_ID = "sub_42";
    private static final String TENANT_ID_STR = "3f0a2b1c-4d5e-6f70-8192-a3b4c5d6e7f8";
    private static final UUID TENANT_ID = UUID.fromString(TENANT_ID_STR);
    private static final int MAX_FAILED_ATTEMPTS = 3;

    private JdbcTemplate jdbcTemplate;
    private BillingEmailService emailService;
    private ApplicationEventPublisher eventPublisher;
    private SubscriptionPendingHandler handler;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        emailService = mock(BillingEmailService.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        handler = new SubscriptionPendingHandler(jdbcTemplate, emailService, eventPublisher, MAX_FAILED_ATTEMPTS);
    }

    private BillingEvent pendingEvent(String subscriptionId) {
        return new BillingEvent(BillingEventType.SUBSCRIPTION_PENDING, subscriptionId, "subscription.pending");
    }

    private void stubTenantLookup(String plan, String previousStatus) {
        when(jdbcTemplate.queryForObject(
            startsWith("SELECT id FROM control_plane.tenant"), eq(String.class), eq(SUB_ID)))
            .thenReturn(TENANT_ID_STR);
        // Detail lookup uses ?::uuid with the STRING id, not a UUID param.
        Map<String, Object> row = new HashMap<>();
        row.put("name", "Acme");
        row.put("plan", plan);
        row.put("subscription_status", previousStatus);
        when(jdbcTemplate.queryForMap(
            startsWith("SELECT name, plan, subscription_status FROM control_plane.tenant"), eq(TENANT_ID_STR)))
            .thenReturn(row);
    }

    @Test
    void pendingEventMarksStatusPendingWithoutResettingAnExistingPastDueClock() {
        stubTenantLookup("PREMIUM", "active");

        handler.handle(pendingEvent(SUB_ID));

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).update(sql.capture(), eq("PENDING"), any(Instant.class), eq(TENANT_ID));
        // The first-failure-wins guard lives entirely in this SQL - the
        // handler reads status only for the attempt counter, so if the CASE
        // guard were ever simplified away, every Razorpay charge retry would
        // re-stamp past_due_since and the dunning grace window would never
        // close in on a persistently failing card.
        assertTrue(sql.getValue().contains("CASE WHEN past_due_since IS NULL"),
            "past_due_since may only be set when currently NULL - first failure wins");
        assertTrue(sql.getValue().contains("ELSE past_due_since"),
            "an already-set past_due_since must be preserved verbatim");
    }

    @Test
    void firstFailureSendsPaymentFailedEmailAndDoesNotDowngrade() {
        stubTenantLookup("PREMIUM", "active");

        handler.handle(pendingEvent(SUB_ID));

        // D1 - retry-CTA email on the first failure, not the dunning escalation.
        verify(emailService).sendPaymentFailedEmail(eq(TENANT_ID), eq("Acme"), any());
        verify(emailService, never()).sendDunningEmail(any(), anyString(), org.mockito.ArgumentMatchers.anyInt());
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void repeatedFailureIncrementsTheCounterAndSendsTheDunningEscalation() {
        stubTenantLookup("PREMIUM", "PENDING");

        handler.handle(pendingEvent(SUB_ID));

        verify(jdbcTemplate).update(anyString(), eq("PENDING:2"), any(Instant.class), eq(TENANT_ID));
        verify(emailService).sendDunningEmail(TENANT_ID, "Acme", 2);
        verify(emailService, never()).sendPaymentFailedEmail(any(), anyString(), any());
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void reachingMaxFailedAttemptsPublishesTheExhaustedEvent() {
        stubTenantLookup("PREMIUM", "PENDING:2");

        handler.handle(pendingEvent(SUB_ID));

        verify(jdbcTemplate).update(anyString(), eq("PENDING:3"), any(Instant.class), eq(TENANT_ID));
        ArgumentCaptor<SubscriptionPaymentExhaustedEvent> published =
            ArgumentCaptor.forClass(SubscriptionPaymentExhaustedEvent.class);
        verify(eventPublisher).publishEvent(published.capture());
        assertEquals(TENANT_ID, published.getValue().tenantId());
        assertEquals(SUB_ID, published.getValue().gatewaySubscriptionId());
        assertEquals(3, published.getValue().failedAttempts());
    }

    @Test
    void freeTenantIsRecordedButNeverDunnedOrDowngraded() {
        // The signup-time trial subscription's lifecycle - PENDING is still
        // recorded, but there is no paid plan to email about or take away.
        stubTenantLookup("FREE", "PENDING:2");

        handler.handle(pendingEvent(SUB_ID));

        verify(jdbcTemplate).update(anyString(), eq("PENDING:3"), any(Instant.class), eq(TENANT_ID));
        verifyNoInteractions(emailService);
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void eventWithoutASubscriptionIdTouchesNothing() {
        handler.handle(pendingEvent(null));
        handler.handle(pendingEvent("   "));

        verifyNoInteractions(jdbcTemplate);
        verifyNoInteractions(emailService);
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void unknownSubscriptionIdIsSkippedWithoutAWrite() {
        when(jdbcTemplate.queryForObject(
            startsWith("SELECT id FROM control_plane.tenant"), eq(String.class), eq(SUB_ID)))
            .thenThrow(new EmptyResultDataAccessException(1));

        assertDoesNotThrow(() -> handler.handle(pendingEvent(SUB_ID)));

        verify(jdbcTemplate, never()).update(anyString(), any(), any(), any());
        verifyNoInteractions(emailService);
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void updateFailureIsSwallowedAndCountsForNothing() {
        // Same fail-open posture as the charged handler: a transient DB
        // error must not turn into a non-2xx that stampedes gateway retries.
        // And an attempt that was never recorded must not email or push the
        // tenant toward the downgrade threshold.
        stubTenantLookup("PREMIUM", "PENDING:2");
        when(jdbcTemplate.update(anyString(), any(), any(), any()))
            .thenThrow(new RuntimeException("db down"));

        assertDoesNotThrow(() -> handler.handle(pendingEvent(SUB_ID)));

        verifyNoInteractions(emailService);
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void previousFailedAttemptsParsesTheStatusCounter() {
        assertEquals(0, SubscriptionPendingHandler.previousFailedAttempts(null));
        assertEquals(0, SubscriptionPendingHandler.previousFailedAttempts("active"));
        assertEquals(0, SubscriptionPendingHandler.previousFailedAttempts("ACTIVE"));
        assertEquals(1, SubscriptionPendingHandler.previousFailedAttempts("PENDING"));
        assertEquals(2, SubscriptionPendingHandler.previousFailedAttempts("PENDING:2"));
        assertEquals(7, SubscriptionPendingHandler.previousFailedAttempts("PENDING:7"));
        assertEquals(1, SubscriptionPendingHandler.previousFailedAttempts("PENDING:garbage"));
    }
}
