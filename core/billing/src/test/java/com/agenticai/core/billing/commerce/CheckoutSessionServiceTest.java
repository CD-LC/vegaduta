package com.agenticai.core.billing.commerce;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.agenticai.core.billing.gateway.GatewayUpiCollectRequest;
import com.agenticai.core.billing.gateway.PaymentGatewayClient;
import com.agenticai.core.billing.gateway.PaymentGatewayException;
import com.agenticai.core.billing.gateway.PaymentGatewayRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

/**
 * DB-free contract for {@link CheckoutSessionService} - the repository,
 * gateway registry and event publisher are mocked, so what is pinned is
 * the ORDER of operations (row first, gateway second, READY third), the
 * validation boundaries, and the webhook reconciliation's decision table -
 * same posture as {@code SubscriptionChargedHandlerTest}. A fixed
 * {@link Clock} makes every timestamp assertion deterministic.
 */
class CheckoutSessionServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-09T10:00:00Z");
    private static final String TENANT = "tenant_acme";
    private static final UUID AGENT_ID = UUID.fromString("0f2c1b3a-4d5e-6f70-8192-a3b4c5d6e7f8");

    private CheckoutSessionRepository repository;
    private PaymentGatewayClient gateway;
    private ApplicationEventPublisher events;
    private CheckoutSessionService service;

    @BeforeEach
    void setUp() {
        repository = mock(CheckoutSessionRepository.class);
        gateway = mock(PaymentGatewayClient.class);
        when(gateway.gatewayName()).thenReturn("razorpay");
        events = mock(ApplicationEventPublisher.class);
        PaymentGatewayRegistry registry = new PaymentGatewayRegistry(List.of(gateway), "razorpay");
        service = new CheckoutSessionService(
            repository, registry, events, Clock.fixed(NOW, ZoneOffset.UTC), 10_000_000L, Duration.ofHours(24));
    }

    private static CheckoutSessionService.CreatePaymentLinkCommand command(long amountPaise, String description) {
        return new CheckoutSessionService.CreatePaymentLinkCommand(
            TENANT, AGENT_ID, "sess-1", null, amountPaise, description, "+919876543210", "Asha");
    }

    private static CheckoutSession row(UUID id, CheckoutSessionStatus status, long amountPaise, Instant expiresAt) {
        return new CheckoutSession(
            id, TENANT, AGENT_ID, "sess-1", "agent", "+919876543210", "Asha", "INR", amountPaise,
            "2x dosa", CheckoutSessionService.singleLineItem("2x dosa", amountPaise), status, "razorpay",
            "plink_1", "https://rzp.io/i/abc", null, null, null, NOW, NOW, expiresAt);
    }

    // ---- createPaymentLink -------------------------------------------------

    @Test
    void createInsertsAPendingRowBeforeCallingTheGatewayThenMarksItReady() {
        when(gateway.createUpiCollectRequest(eq(TENANT), eq(49_900L), eq("2x dosa"), eq("Asha"), eq("+919876543210"), isNull(), any(), any()))
            .thenReturn(new GatewayUpiCollectRequest("plink_1", "https://rzp.io/i/abc", "created"));
        ArgumentCaptor<CheckoutSession> inserted = ArgumentCaptor.forClass(CheckoutSession.class);
        when(repository.findById(any())).thenAnswer(inv ->
            Optional.of(row(inv.getArgument(0), CheckoutSessionStatus.READY_FOR_PAYMENT, 49_900L, NOW.plus(Duration.ofHours(24)))));

        CheckoutSession result = service.createPaymentLink(command(49_900L, "2x dosa"));

        // 1. The row exists BEFORE the gateway is called, as NOT_READY, and
        //    with everything the webhook path will later need.
        verify(repository).insert(inserted.capture());
        CheckoutSession pending = inserted.getValue();
        assertEquals(CheckoutSessionStatus.NOT_READY_FOR_PAYMENT, pending.status());
        assertEquals(TENANT, pending.tenantId());
        assertEquals(AGENT_ID, pending.agentId());
        assertEquals("agent", pending.channel(), "blank channel defaults to 'agent'");
        assertEquals("INR", pending.currency());
        assertEquals(49_900L, pending.amountPaise());
        assertEquals("razorpay", pending.paymentProvider());
        assertNull(pending.gatewayRefId(), "no gateway id until the link exists");
        assertTrue(pending.lineItemsJson().contains("\"unit_amount\":49900"), pending.lineItemsJson());

        // 2. The gateway link is stamped with this row's id so a dashboard
        //    record is always traceable back here.
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> notes = ArgumentCaptor.forClass(Map.class);
        verify(gateway).createUpiCollectRequest(eq(TENANT), eq(49_900L), eq("2x dosa"), eq("Asha"), eq("+919876543210"), isNull(), notes.capture(),
            eq(NOW.plus(Duration.ofHours(24))));
        assertEquals(pending.id().toString(), notes.getValue().get("checkout_session_id"));
        assertEquals(TENANT, notes.getValue().get("tenant_id"));
        assertEquals(AGENT_ID.toString(), notes.getValue().get("agent_id"));

        // 3. READY carries the gateway's id/url and the app-side expiry.
        verify(repository).markReadyForPayment(eq(pending.id()), eq("plink_1"), eq("https://rzp.io/i/abc"),
            eq(NOW.plus(Duration.ofHours(24))), eq(NOW));
        assertEquals(CheckoutSessionStatus.READY_FOR_PAYMENT, result.status());
        assertEquals("plink_1", result.gatewayRefId());
    }

    @Test
    void gatewayRefusalLeavesACanceledRowWithTheReasonAndRethrows() {
        when(gateway.createUpiCollectRequest(anyString(), anyLong(), anyString(), any(), any(), any(), any(), any()))
            .thenThrow(new PaymentGatewayException("Could not create Razorpay UPI payment link: 401", null));

        assertThrows(PaymentGatewayException.class, () -> service.createPaymentLink(command(49_900L, "2x dosa")));

        ArgumentCaptor<String> note = ArgumentCaptor.forClass(String.class);
        verify(repository).markCanceled(any(), note.capture(), eq(NOW));
        assertTrue(note.getValue().contains("401"), note.getValue());
        verify(repository, never()).markReadyForPayment(any(), any(), any(), any(), any());
        verifyNoInteractions(events);
    }

    @Test
    void aGatewayWithoutUpiSupportIsSurfacedAsAGatewayExceptionNotAnUnsupportedOperation() {
        // PaymentGatewayClient#createUpiCollectRequest's default throws
        // UnsupportedOperationException (PhonePe) - the tool expects one
        // exception type for "the gateway can't do this".
        when(gateway.createUpiCollectRequest(anyString(), anyLong(), anyString(), any(), any(), any(), any(), any()))
            .thenThrow(new UnsupportedOperationException("Gateway 'phonepe' does not support UPI collect requests"));

        PaymentGatewayException thrown = assertThrows(PaymentGatewayException.class,
            () -> service.createPaymentLink(command(49_900L, "2x dosa")));
        assertTrue(thrown.getMessage().contains("does not support UPI payment links"), thrown.getMessage());
        verify(repository).markCanceled(any(), anyString(), eq(NOW));
    }

    @Test
    void rejectsAmountsBelowOnePaiseFloorAboveCeilingAndBlankDescriptionsBeforeTouchingAnything() {
        assertThrows(IllegalArgumentException.class, () -> service.createPaymentLink(command(99L, "x")));
        assertThrows(IllegalArgumentException.class, () -> service.createPaymentLink(command(10_000_001L, "x")));
        assertThrows(IllegalArgumentException.class, () -> service.createPaymentLink(command(500L, "   ")));
        assertThrows(IllegalArgumentException.class, () -> service.createPaymentLink(command(500L, "a".repeat(501))));
        assertThrows(IllegalArgumentException.class, () -> service.createPaymentLink(
            new CheckoutSessionService.CreatePaymentLinkCommand("", AGENT_ID, "s", null, 500L, "ok", null, null)));

        // Money-state first: nothing is written and no link is minted for a
        // request that was never valid.
        verifyNoInteractions(repository, events);
        verify(gateway, never()).createUpiCollectRequest(anyString(), anyLong(), anyString(), any(), any(), any(), any(), any());
    }

    @Test
    void isPaymentGatewayAvailableReflectsWhetherTheDefaultGatewayIsRegistered() {
        assertTrue(service.isPaymentGatewayAvailable());

        CheckoutSessionService withoutGateway = new CheckoutSessionService(
            repository, new PaymentGatewayRegistry(List.of(), "razorpay"), events,
            Clock.fixed(NOW, ZoneOffset.UTC), 10_000_000L, Duration.ofHours(24));
        assertFalse(withoutGateway.isPaymentGatewayAvailable());
    }

    // ---- recordGatewayPayment (webhook) -----------------------------------

    @Test
    void unknownGatewayIdIsNotFoundAndWritesNothing() {
        when(repository.findByGatewayRefId("plink_unknown")).thenReturn(Optional.empty());

        CheckoutSessionService.Reconciliation outcome = service.recordGatewayPayment("plink_unknown", null);

        assertEquals(CheckoutSessionService.ReconciliationResult.NOT_FOUND, outcome.result());
        verify(repository, never()).markCompleted(any(), any(), any());
        verifyNoInteractions(events);
    }

    @Test
    void firstDeliveryCompletesTheRowWithThePaymentIdAndPublishesExactlyOneEvent() {
        UUID id = UUID.randomUUID();
        CheckoutSession ready = row(id, CheckoutSessionStatus.READY_FOR_PAYMENT, 49_900L, NOW.plus(Duration.ofHours(1)));
        CheckoutSession completed = new CheckoutSession(
            id, TENANT, AGENT_ID, "sess-1", "agent", "+919876543210", "Asha", "INR", 49_900L, "2x dosa",
            ready.lineItemsJson(), CheckoutSessionStatus.COMPLETED, "razorpay", "plink_1", ready.shortUrl(),
            NOW, "pay_9", null, NOW, NOW, ready.expiresAt());
        when(repository.findByGatewayRefId("plink_1")).thenReturn(Optional.of(ready));
        when(repository.markCompleted(id, "pay_9", NOW)).thenReturn(1);
        when(repository.findById(id)).thenReturn(Optional.of(completed));

        CheckoutSessionService.Reconciliation outcome = service.recordGatewayPayment(
            "plink_1", new PaidPaymentLookup.PaidPayment("pay_9", 49_900L, "upi"));

        assertEquals(CheckoutSessionService.ReconciliationResult.COMPLETED, outcome.result());
        assertEquals("pay_9", outcome.session().paymentId());
        ArgumentCaptor<Object> published = ArgumentCaptor.forClass(Object.class);
        verify(events).publishEvent(published.capture());
        assertTrue(published.getValue() instanceof CheckoutSessionCompletedEvent);
        assertEquals(id, ((CheckoutSessionCompletedEvent) published.getValue()).session().id());
    }

    @Test
    void redeliveredWebhookOnACompletedRowIsAnIdempotentNoOp() {
        UUID id = UUID.randomUUID();
        when(repository.findByGatewayRefId("plink_1"))
            .thenReturn(Optional.of(row(id, CheckoutSessionStatus.COMPLETED, 49_900L, NOW)));

        CheckoutSessionService.Reconciliation outcome = service.recordGatewayPayment("plink_1", null);

        assertEquals(CheckoutSessionService.ReconciliationResult.ALREADY_COMPLETED, outcome.result());
        verify(repository, never()).markCompleted(any(), any(), any());
        verifyNoInteractions(events);
    }

    @Test
    void losingTheCompletionRaceDoesNotPublishASecondEvent() {
        // Two deliveries of the same webhook interleave: both read READY,
        // the state-guarded UPDATE lets only one through (0 rows here).
        UUID id = UUID.randomUUID();
        CheckoutSession ready = row(id, CheckoutSessionStatus.READY_FOR_PAYMENT, 49_900L, NOW.plus(Duration.ofHours(1)));
        when(repository.findByGatewayRefId("plink_1")).thenReturn(Optional.of(ready));
        when(repository.markCompleted(eq(id), any(), eq(NOW))).thenReturn(0);
        when(repository.findById(id)).thenReturn(Optional.of(ready.withStatus(CheckoutSessionStatus.COMPLETED)));

        CheckoutSessionService.Reconciliation outcome = service.recordGatewayPayment("plink_1", null);

        assertEquals(CheckoutSessionService.ReconciliationResult.ALREADY_COMPLETED, outcome.result());
        verifyNoInteractions(events);
    }

    @Test
    void amountMismatchFlagsForReviewAndDoesNotComplete() {
        UUID id = UUID.randomUUID();
        CheckoutSession ready = row(id, CheckoutSessionStatus.READY_FOR_PAYMENT, 49_900L, NOW.plus(Duration.ofHours(1)));
        when(repository.findByGatewayRefId("plink_1")).thenReturn(Optional.of(ready));
        when(repository.findById(id)).thenReturn(Optional.of(ready));

        CheckoutSessionService.Reconciliation outcome = service.recordGatewayPayment(
            "plink_1", new PaidPaymentLookup.PaidPayment("pay_9", 40_000L, "upi"));

        assertEquals(CheckoutSessionService.ReconciliationResult.AMOUNT_MISMATCH, outcome.result());
        ArgumentCaptor<String> note = ArgumentCaptor.forClass(String.class);
        verify(repository).setReviewNote(eq(id), note.capture(), eq(NOW));
        assertTrue(note.getValue().contains("40000") && note.getValue().contains("49900"), note.getValue());
        verify(repository, never()).markCompleted(any(), any(), any());
        verifyNoInteractions(events);
    }

    @Test
    void unknownPaidAmountIsNotTreatedAsAMismatch() {
        // Lookup unavailable (no security profile / gateway unreachable):
        // the session still completes, just with a null payment id.
        UUID id = UUID.randomUUID();
        CheckoutSession ready = row(id, CheckoutSessionStatus.READY_FOR_PAYMENT, 49_900L, NOW.plus(Duration.ofHours(1)));
        when(repository.findByGatewayRefId("plink_1")).thenReturn(Optional.of(ready));
        when(repository.markCompleted(id, null, NOW)).thenReturn(1);
        when(repository.findById(id)).thenReturn(Optional.of(ready.withStatus(CheckoutSessionStatus.COMPLETED)));

        CheckoutSessionService.Reconciliation outcome = service.recordGatewayPayment(
            "plink_1", new PaidPaymentLookup.PaidPayment(null, null, null));

        assertEquals(CheckoutSessionService.ReconciliationResult.COMPLETED, outcome.result());
        verify(repository).markCompleted(id, null, NOW);
        verify(events).publishEvent(any(CheckoutSessionCompletedEvent.class));
    }

    @Test
    void aLateWebhookOnAnExpiredRowStillCompletesIt() {
        UUID id = UUID.randomUUID();
        CheckoutSession expired = row(id, CheckoutSessionStatus.EXPIRED, 49_900L, NOW.minus(Duration.ofHours(1)));
        when(repository.findByGatewayRefId("plink_1")).thenReturn(Optional.of(expired));
        when(repository.markCompleted(id, "pay_9", NOW)).thenReturn(1);
        when(repository.findById(id)).thenReturn(Optional.of(expired.withStatus(CheckoutSessionStatus.COMPLETED)));

        CheckoutSessionService.Reconciliation outcome = service.recordGatewayPayment(
            "plink_1", new PaidPaymentLookup.PaidPayment("pay_9", 49_900L, "upi"));

        assertEquals(CheckoutSessionService.ReconciliationResult.COMPLETED, outcome.result());
        verify(events).publishEvent(any(CheckoutSessionCompletedEvent.class));
    }

    @Test
    void aBrokenEventSubscriberDoesNotUnCompleteTheSession() {
        UUID id = UUID.randomUUID();
        CheckoutSession ready = row(id, CheckoutSessionStatus.READY_FOR_PAYMENT, 49_900L, NOW.plus(Duration.ofHours(1)));
        when(repository.findByGatewayRefId("plink_1")).thenReturn(Optional.of(ready));
        when(repository.markCompleted(eq(id), any(), eq(NOW))).thenReturn(1);
        when(repository.findById(id)).thenReturn(Optional.of(ready.withStatus(CheckoutSessionStatus.COMPLETED)));
        org.mockito.Mockito.doThrow(new RuntimeException("listener exploded")).when(events).publishEvent(any(Object.class));

        CheckoutSessionService.Reconciliation outcome = service.recordGatewayPayment("plink_1", null);

        assertEquals(CheckoutSessionService.ReconciliationResult.COMPLETED, outcome.result());
        assertEquals(CheckoutSessionStatus.COMPLETED, outcome.session().status());
    }

    // ---- reads / lazy expiry ---------------------------------------------

    @Test
    void readingAReadyRowPastItsExpiryMarksItExpired() {
        UUID id = UUID.randomUUID();
        CheckoutSession stale = row(id, CheckoutSessionStatus.READY_FOR_PAYMENT, 500L, NOW.minus(Duration.ofMinutes(1)));
        when(repository.findByIdAndTenantId(id, TENANT)).thenReturn(Optional.of(stale));
        when(repository.markExpired(id, NOW)).thenReturn(1);

        Optional<CheckoutSession> read = service.getForTenant(id, TENANT);

        assertEquals(CheckoutSessionStatus.EXPIRED, read.orElseThrow().status());
    }

    @Test
    void lazyExpiryLosesToAConcurrentWebhookCompletion() {
        UUID id = UUID.randomUUID();
        CheckoutSession stale = row(id, CheckoutSessionStatus.READY_FOR_PAYMENT, 500L, NOW.minus(Duration.ofMinutes(1)));
        when(repository.findByIdAndTenantId(id, TENANT)).thenReturn(Optional.of(stale));
        when(repository.markExpired(id, NOW)).thenReturn(0);
        when(repository.findById(id)).thenReturn(Optional.of(stale.withStatus(CheckoutSessionStatus.COMPLETED)));

        Optional<CheckoutSession> read = service.getForTenant(id, TENANT);

        assertEquals(CheckoutSessionStatus.COMPLETED, read.orElseThrow().status(),
            "the row's real state wins over a stale EXPIRED guess");
    }

    @Test
    void readsAreTenantScopedAndListIsBounded() {
        UUID id = UUID.randomUUID();
        when(repository.findByIdAndTenantId(id, "someone_else")).thenReturn(Optional.empty());
        assertTrue(service.getForTenant(id, "someone_else").isEmpty());

        when(repository.listByTenantId(eq(TENANT), anyInt())).thenReturn(List.of());
        service.listForTenant(TENANT, 5_000);
        verify(repository).listByTenantId(TENANT, CheckoutSessionService.MAX_LIST_LIMIT);
        service.listForTenant(TENANT, 0);
        verify(repository).listByTenantId(TENANT, 1);
    }
}
