package com.agenticai.core.payments;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.approval.ApprovalRequest;
import com.agenticai.core.approval.HumanApprovalGateService;
import com.agenticai.core.audit.AuditLogService;
import com.agenticai.core.payments.adapter.UpiAgentProtocolAdapter;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/**
 * {@link PaymentToolService} contract tests: idempotency replay, the per-mandate approval policy
 * (ALWAYS / ABOVE_THRESHOLD / NEVER), and the no-mandate refusal. Everything is Mockito-mocked - no
 * DB, no gateway, no live Razorpay call (the adapter is only ever checked for presence).
 */
class PaymentToolServiceTest {

    private PaymentMandateRepository mandateRepository;
    private PaymentTransactionRepository transactionRepository;
    private MandateEnforcer enforcer;
    private HumanApprovalGateService gateService;
    private PaymentExecutionService executionService;
    private ObjectProvider<UpiAgentProtocolAdapter> adapterProvider;
    private PaymentToolService service;

    private static final UUID AGENT = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-09-26T10:00:00Z");

    @BeforeEach
    void setUp() {
        mandateRepository = mock(PaymentMandateRepository.class);
        transactionRepository = mock(PaymentTransactionRepository.class);
        enforcer = mock(MandateEnforcer.class);
        gateService = mock(HumanApprovalGateService.class);
        executionService = mock(PaymentExecutionService.class);
        adapterProvider = mock(ObjectProvider.class);
        when(adapterProvider.getIfAvailable()).thenReturn(mock(UpiAgentProtocolAdapter.class));
        AuditLogService audit = mock(AuditLogService.class);
        service =
                new PaymentToolService(
                        mandateRepository,
                        transactionRepository,
                        enforcer,
                        gateService,
                        executionService,
                        adapterProvider,
                        audit,
                        mock(org.springframework.transaction.PlatformTransactionManager.class),
                        86400);
        // Every save returns its argument so the service can re-read it.
        when(transactionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private PaymentMandate mandate(MandateApprovalPolicy policy, Long thresholdPaise) {
        PaymentMandate m = new PaymentMandate();
        m.setId(UUID.randomUUID());
        m.setAgentId(AGENT);
        m.setGrantedByUserId("user-1");
        m.setScope(MandateScope.COLLECT);
        m.setMaxPerTxnPaise(1_000_000);
        m.setMaxDailyPaise(5_000_000);
        m.setMaxMonthlyPaise(50_000_000);
        m.setApprovalPolicy(policy);
        m.setApprovalThresholdPaise(thresholdPaise);
        m.setValidFrom(NOW.minusSeconds(3600));
        m.setValidUntil(NOW.plusSeconds(86_400));
        m.setStatus(MandateStatus.ACTIVE);
        m.setCanonicalJson("{}");
        m.setSignature(new byte[64]);
        m.setKeyId("psk_test");
        return m;
    }

    private void withMandate(PaymentMandate m) {
        when(mandateRepository.findFirstByAgentIdAndStatusOrderByCreatedAtDesc(
                        AGENT, MandateStatus.ACTIVE))
                .thenReturn(Optional.of(m));
    }

    private void returnsSavedRow() {
        // findById must hand back the row the service just saved - track it.
        final PaymentTransaction[] saved = new PaymentTransaction[1];
        when(transactionRepository.save(any()))
                .thenAnswer(
                        inv -> {
                            saved[0] = inv.getArgument(0);
                            return saved[0];
                        });
        when(transactionRepository.findById(any()))
                .thenAnswer(
                        inv ->
                                Optional.ofNullable(
                                        saved[0] != null
                                                        && saved[0].getId()
                                                                .equals(inv.getArgument(0))
                                                ? saved[0]
                                                : null));
    }

    @Test
    void noMandateIsRefusedWithNoMandateCode() {
        when(mandateRepository.findFirstByAgentIdAndStatusOrderByCreatedAtDesc(
                        AGENT, MandateStatus.ACTIVE))
                .thenReturn(Optional.empty());

        MandateViolationException e =
                assertThrows(
                        MandateViolationException.class,
                        () ->
                                service.createPaymentLink(
                                        AGENT, "s1", 10_000, "test", null, null, null));
        assertEquals("NO_MANDATE", e.getCode());
        verify(transactionRepository, never()).save(any());
        verify(executionService, never()).execute(any());
    }

    @Test
    void alwaysPolicyParksAtPendingApproval() {
        PaymentMandate m = mandate(MandateApprovalPolicy.ALWAYS, null);
        withMandate(m);
        returnsSavedRow();
        ApprovalRequest req = mock(ApprovalRequest.class);
        when(req.getId()).thenReturn(UUID.randomUUID());
        when(gateService.recordToolCallForApproval(anyString(), anyString(), anyString(), any()))
                .thenReturn(req);

        var outcome =
                service.createPaymentLink(AGENT, "s1", 10_000, "invoice #7", null, null, null);

        assertEquals(PaymentTransactionStatus.PENDING_APPROVAL, outcome.status());
        assertEquals(req.getId(), outcome.approvalId());
        // The gateway must NOT have been touched - approval precedes execution.
        verify(executionService, never()).execute(any());
        // But the cap WAS reserved - a pending payment still counts.
        verify(enforcer).reserveCaps(any(), any(), org.mockito.ArgumentMatchers.eq(10_000L), any());
    }

    @Test
    void aboveThresholdGatesOnlyLargeAmounts() {
        PaymentMandate m = mandate(MandateApprovalPolicy.ABOVE_THRESHOLD, 50_000L);
        withMandate(m);
        returnsSavedRow();
        ApprovalRequest req = mock(ApprovalRequest.class);
        when(req.getId()).thenReturn(UUID.randomUUID());
        when(gateService.recordToolCallForApproval(anyString(), anyString(), anyString(), any()))
                .thenReturn(req);

        // Below threshold: executes straight away.
        var low = service.createPaymentLink(AGENT, "s1", 10_000, "small", null, null, null);
        assertEquals(
                PaymentTransactionStatus.INITIATED, low.status()); // still INITIATED pre-execution
        verify(gateService, never())
                .recordToolCallForApproval(anyString(), anyString(), anyString(), any());
        verify(executionService).execute(low.transactionId());
    }

    @Test
    void aboveThresholdBlocksLargeAmountsForApproval() {
        PaymentMandate m = mandate(MandateApprovalPolicy.ABOVE_THRESHOLD, 50_000L);
        withMandate(m);
        returnsSavedRow();
        ApprovalRequest req = mock(ApprovalRequest.class);
        when(req.getId()).thenReturn(UUID.randomUUID());
        when(gateService.recordToolCallForApproval(anyString(), anyString(), anyString(), any()))
                .thenReturn(req);

        var high = service.createPaymentLink(AGENT, "s1", 60_000, "big", null, null, null);

        assertEquals(PaymentTransactionStatus.PENDING_APPROVAL, high.status());
        verify(gateService)
                .recordToolCallForApproval(
                        org.mockito.ArgumentMatchers.eq(PaymentToolService.APPROVAL_ACTION_TYPE),
                        anyString(),
                        anyString(),
                        any());
        verify(executionService, never()).execute(any());
    }

    @Test
    void neverPolicyExecutesUnderTheMandatesOwnAuthority() {
        PaymentMandate m = mandate(MandateApprovalPolicy.NEVER, null);
        withMandate(m);
        returnsSavedRow();

        var outcome =
                service.requestUpiCollect(AGENT, "s1", 25_000, "shop@okhdfcbank", "restock", null);

        assertEquals(PaymentTransactionStatus.INITIATED, outcome.status());
        verify(gateService, never())
                .recordToolCallForApproval(anyString(), anyString(), anyString(), any());
        verify(executionService).execute(outcome.transactionId());
        verify(enforcer)
                .assertTransactionShape(
                        any(),
                        org.mockito.ArgumentMatchers.eq(PaymentTransactionType.UPI_COLLECT),
                        org.mockito.ArgumentMatchers.eq(25_000L),
                        org.mockito.ArgumentMatchers.eq("shop@okhdfcbank"),
                        any());
    }

    @Test
    void idempotentReplayReturnsTheSameTransactionAndTouchesNothing() {
        PaymentMandate m = mandate(MandateApprovalPolicy.NEVER, null);
        withMandate(m);

        PaymentTransaction existing =
                new PaymentTransaction(
                        m.getId(),
                        AGENT,
                        "s1",
                        PaymentTransactionType.PAYMENT_LINK,
                        10_000,
                        null,
                        "same call",
                        null,
                        "upi:any",
                        Instant.now().plusSeconds(3600));
        // Whatever key the service would derive, we answer it with the row.
        when(transactionRepository.findByIdempotencyKey(anyString()))
                .thenReturn(Optional.of(existing));

        var outcome = service.createPaymentLink(AGENT, "s1", 10_000, "same call", null, null, null);

        assertTrue(outcome.replayed());
        assertEquals(existing.getId(), outcome.transactionId());
        verify(transactionRepository, never()).save(any());
        verify(enforcer, never())
                .reserveCaps(any(), any(), org.mockito.ArgumentMatchers.anyLong(), any());
        verify(executionService, never()).execute(any());
    }

    @Test
    void checkPaymentStatusHidesOtherAgentsTransactions() {
        PaymentTransaction foreign =
                new PaymentTransaction(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        "s9",
                        PaymentTransactionType.PAYMENT_LINK,
                        5_000,
                        null,
                        "not yours",
                        null,
                        "k",
                        Instant.now());
        when(transactionRepository.findById(foreign.getId())).thenReturn(Optional.of(foreign));

        var result = service.checkPaymentStatus(AGENT, foreign.getId());

        assertNotNull(result.get("error"));
    }

    @Test
    void checkPaymentStatusReportsOwnTransaction() {
        PaymentTransaction mine =
                new PaymentTransaction(
                        UUID.randomUUID(),
                        AGENT,
                        "s1",
                        PaymentTransactionType.PAYMENT_LINK,
                        5_000,
                        null,
                        "mine",
                        null,
                        "k",
                        Instant.now());
        when(transactionRepository.findById(mine.getId())).thenReturn(Optional.of(mine));

        var result = service.checkPaymentStatus(AGENT, mine.getId());

        assertEquals(mine.getId().toString(), result.get("transactionId"));
        assertEquals("INITIATED", result.get("status"));
        assertEquals(false, result.get("paid"));
    }

    @Test
    void noAdapterMeansUnavailableRatherThanHalfWorking() {
        when(adapterProvider.getIfAvailable()).thenReturn(null);
        MandateViolationException e =
                assertThrows(
                        MandateViolationException.class,
                        () ->
                                service.createPaymentLink(
                                        AGENT, "s1", 10_000, "x", null, null, null));
        assertEquals("PAYMENTS_UNAVAILABLE", e.getCode());
    }
}
