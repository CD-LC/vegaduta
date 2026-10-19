package com.agenticai.core.payments;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * {@link MandateEnforcer} contract tests - every refusal mode §E1 requires,
 * plus the ledger-lock cap path. Repositories/signing/cache are Mockito
 * mocks; no gateway or DB involved (unit-only, same convention as the
 * commerce tool tests in core/agents).
 */
class MandateEnforcerTest {

    private MandateSigningService signingService;
    private MandateRevocationCache revocationCache;
    private MandateLedgerRepository ledgerRepository;
    private PaymentMandateRepository mandateRepository;
    private MandateEnforcer enforcer;

    private static final Instant NOW = Instant.parse("2026-09-26T10:00:00Z");

    @BeforeEach
    void setUp() {
        signingService = mock(MandateSigningService.class);
        revocationCache = mock(MandateRevocationCache.class);
        ledgerRepository = mock(MandateLedgerRepository.class);
        mandateRepository = mock(PaymentMandateRepository.class);
        when(mandateRepository.lockForCapUpdate(any()))
            .thenAnswer(inv -> Optional.of(inv.getArgument(0).toString()));
        enforcer = new MandateEnforcer(signingService, revocationCache, ledgerRepository, mandateRepository);
    }

    private PaymentMandate mandate() {
        PaymentMandate m = new PaymentMandate();
        m.setId(UUID.randomUUID());
        m.setAgentId(UUID.randomUUID());
        m.setGrantedByUserId("user-1");
        m.setScope(MandateScope.COLLECT);
        m.setMaxPerTxnPaise(50_000);
        m.setMaxDailyPaise(200_000);
        m.setMaxMonthlyPaise(1_000_000);
        m.setAllowedVpas("[\"shop@okhdfcbank\"]");
        m.setApprovalPolicy(MandateApprovalPolicy.ALWAYS);
        m.setValidFrom(NOW.minusSeconds(3600));
        m.setValidUntil(NOW.plusSeconds(3600));
        m.setStatus(MandateStatus.ACTIVE);
        m.setCanonicalJson("{}");
        m.setSignature(new byte[64]);
        m.setKeyId("psk_test");
        return m;
    }

    private void goodSignature(PaymentMandate m) {
        when(signingService.verify(m)).thenReturn(true);
    }

    @Test
    void badSignatureFailsBeforeAnythingElse() {
        PaymentMandate m = mandate();
        when(signingService.verify(m)).thenReturn(false);

        MandateViolationException e = assertThrows(MandateViolationException.class,
            () -> enforcer.assertMandateUsable(m, NOW));
        assertEquals("MANDATE_SIGNATURE_INVALID", e.getCode());
        // A forged/tampered mandate must never reach the ledger.
        verify(ledgerRepository, never()).findLatestForUpdate(any());
    }

    @Test
    void revokedMandateFailsViaDbStatus() {
        PaymentMandate m = mandate();
        m.setStatus(MandateStatus.REVOKED);

        MandateViolationException e = assertThrows(MandateViolationException.class,
            () -> enforcer.assertMandateUsable(m, NOW));
        assertEquals("MANDATE_REVOKED", e.getCode());
    }

    @Test
    void revokedTombstoneKillsAnInFlightCallEvenWithStaleDbRead() {
        // The row still reads ACTIVE (replica lag / already-loaded entity)
        // but the Redis tombstone says revoked - tombstone wins.
        PaymentMandate m = mandate();
        when(revocationCache.isRevoked(m.getId())).thenReturn(true);

        MandateViolationException e = assertThrows(MandateViolationException.class,
            () -> enforcer.assertMandateUsable(m, NOW));
        assertEquals("MANDATE_REVOKED", e.getCode());
        verify(signingService, never()).verify(any());
    }

    @Test
    void expiredWindowFails() {
        PaymentMandate m = mandate();
        m.setValidUntil(NOW.minusSeconds(1));
        goodSignature(m);

        MandateViolationException e = assertThrows(MandateViolationException.class,
            () -> enforcer.assertMandateUsable(m, NOW));
        assertEquals("MANDATE_EXPIRED", e.getCode());
    }

    @Test
    void notYetValidFails() {
        PaymentMandate m = mandate();
        m.setValidFrom(NOW.plusSeconds(60));
        goodSignature(m);

        assertThrows(MandateViolationException.class, () -> enforcer.assertMandateUsable(m, NOW));
    }

    @Test
    void suspendedFails() {
        PaymentMandate m = mandate();
        m.setStatus(MandateStatus.SUSPENDED);
        goodSignature(m);

        MandateViolationException e = assertThrows(MandateViolationException.class,
            () -> enforcer.assertMandateUsable(m, NOW));
        assertEquals("MANDATE_SUSPENDED", e.getCode());
    }

    @Test
    void overPerTransactionCapFails() {
        PaymentMandate m = mandate();
        MandateViolationException e = assertThrows(MandateViolationException.class, () ->
            enforcer.assertTransactionShape(m, PaymentTransactionType.PAYMENT_LINK, 50_001, null, null));
        assertEquals("CAP_PER_TXN", e.getCode());
    }

    @Test
    void collectScopeRejectsPayouts() {
        PaymentMandate m = mandate(); // COLLECT scope
        MandateViolationException e = assertThrows(MandateViolationException.class, () ->
            enforcer.assertTransactionShape(m, PaymentTransactionType.PAYOUT, 100, null, null));
        assertEquals("MANDATE_SCOPE", e.getCode());
    }

    @Test
    void vpaOffAllowListFails() {
        PaymentMandate m = mandate();
        MandateViolationException e = assertThrows(MandateViolationException.class, () ->
            enforcer.assertTransactionShape(m, PaymentTransactionType.UPI_COLLECT, 100, "other@okicici", null));
        assertEquals("VPA_NOT_ALLOWED", e.getCode());
    }

    @Test
    void vpaOnAllowListPasses() {
        PaymentMandate m = mandate();
        assertDoesNotThrow(() ->
            enforcer.assertTransactionShape(m, PaymentTransactionType.UPI_COLLECT, 100, "shop@okhdfcbank", null));
    }

    @Test
    void paymentLinkSkipsVpaCheckButUpiCollectRequiresIt() {
        PaymentMandate m = mandate(); // restricted VPAs
        // A link has no payee VPA at creation - allowed.
        assertDoesNotThrow(() ->
            enforcer.assertTransactionShape(m, PaymentTransactionType.PAYMENT_LINK, 100, null, null));
        // A VPA-targeted collect with no VPA under a restricted mandate is refused.
        assertThrows(MandateViolationException.class, () ->
            enforcer.assertTransactionShape(m, PaymentTransactionType.UPI_COLLECT, 100, null, null));
    }

    @Test
    void merchantCategoryOffAllowListFails() {
        PaymentMandate m = mandate();
        m.setAllowedMerchantCategories("[\"grocery\"]");
        MandateViolationException e = assertThrows(MandateViolationException.class, () ->
            enforcer.assertTransactionShape(m, PaymentTransactionType.PAYMENT_LINK, 100, null, "electronics"));
        assertEquals("CATEGORY_NOT_ALLOWED", e.getCode());
    }

    @Test
    void dailyCapUsesLockedLatestLedgerRow() {
        PaymentMandate m = mandate(); // daily cap 200_000
        LocalDate today = NOW.atOffset(ZoneOffset.UTC).toLocalDate();
        MandateLedgerEntry latest = new MandateLedgerEntry(
            m.getId(), UUID.randomUUID(), MandateLedgerEntry.KIND_CHARGE, 150_000,
            today, 150_000, today.toString().substring(0, 7), 150_000);
        when(ledgerRepository.findLatestForUpdate(m.getId())).thenReturn(Optional.of(latest));

        // 60_000 more takes the day to 210_000 - over the 200_000 cap.
        MandateViolationException e = assertThrows(MandateViolationException.class, () ->
            enforcer.reserveCaps(m, UUID.randomUUID(), 60_000, NOW));
        assertEquals("CAP_DAILY", e.getCode());
        verify(ledgerRepository).findLatestForUpdate(m.getId());
        verify(ledgerRepository, never()).save(any());
    }

    @Test
    void monthlyCapEnforcedIndependently() {
        PaymentMandate m = mandate(); // monthly cap 1_000_000
        LocalDate today = NOW.atOffset(ZoneOffset.UTC).toLocalDate();
        MandateLedgerEntry latest = new MandateLedgerEntry(
            m.getId(), UUID.randomUUID(), MandateLedgerEntry.KIND_CHARGE, 10_000,
            today, 10_000, today.toString().substring(0, 7), 990_000);
        when(ledgerRepository.findLatestForUpdate(m.getId())).thenReturn(Optional.of(latest));

        assertThrows(MandateViolationException.class, () ->
            enforcer.reserveCaps(m, UUID.randomUUID(), 20_000, NOW));
    }

    @Test
    void withinCapsAppendsChargeCarryingNewRunningTotals() {
        PaymentMandate m = mandate();
        LocalDate today = NOW.atOffset(ZoneOffset.UTC).toLocalDate();
        String month = today.toString().substring(0, 7);
        MandateLedgerEntry latest = new MandateLedgerEntry(
            m.getId(), UUID.randomUUID(), MandateLedgerEntry.KIND_CHARGE, 10_000,
            today, 10_000, month, 10_000);
        when(ledgerRepository.findLatestForUpdate(m.getId())).thenReturn(Optional.of(latest));

        UUID txnId = UUID.randomUUID();
        enforcer.reserveCaps(m, txnId, 25_000, NOW);

        ArgumentCaptor<MandateLedgerEntry> captor = ArgumentCaptor.forClass(MandateLedgerEntry.class);
        verify(ledgerRepository).save(captor.capture());
        MandateLedgerEntry charge = captor.getValue();
        assertEquals(MandateLedgerEntry.KIND_CHARGE, charge.getEntryKind());
        assertEquals(35_000, charge.getDailySpendPaise());
        assertEquals(35_000, charge.getMonthlySpendPaise());
        assertEquals(txnId, charge.getTransactionId());
    }

    @Test
    void dayRolloverResetsDailyRunningTotal() {
        PaymentMandate m = mandate();
        LocalDate yesterday = NOW.atOffset(ZoneOffset.UTC).toLocalDate().minusDays(1);
        String lastMonth = yesterday.toString().substring(0, 7);
        MandateLedgerEntry latest = new MandateLedgerEntry(
            m.getId(), UUID.randomUUID(), MandateLedgerEntry.KIND_CHARGE, 190_000,
            yesterday, 190_000, lastMonth, 190_000);
        when(ledgerRepository.findLatestForUpdate(m.getId())).thenReturn(Optional.of(latest));

        // Yesterday's spend must not block today - but it still counts for the month.
        assertDoesNotThrow(() -> enforcer.reserveCaps(m, UUID.randomUUID(), 50_000, NOW));
    }

    @Test
    void reversalReleasesThisTransactionsChargeNotTheLatestRowsAmount() {
        PaymentMandate m = mandate();
        LocalDate today = NOW.atOffset(ZoneOffset.UTC).toLocalDate();
        String month = today.toString().substring(0, 7);
        UUID txnId = UUID.randomUUID();
        // Latest row is a DIFFERENT transaction's charge of 90_000.
        MandateLedgerEntry latest = new MandateLedgerEntry(
            m.getId(), UUID.randomUUID(), MandateLedgerEntry.KIND_CHARGE, 90_000,
            today, 90_000, month, 90_000);
        MandateLedgerEntry ownCharge = new MandateLedgerEntry(
            m.getId(), txnId, MandateLedgerEntry.KIND_CHARGE, 10_000,
            today, 10_000, month, 10_000);
        when(ledgerRepository.findLatestForUpdate(m.getId())).thenReturn(Optional.of(latest));
        when(ledgerRepository.findByTransactionIdAndEntryKind(txnId, MandateLedgerEntry.KIND_CHARGE))
            .thenReturn(Optional.of(ownCharge));

        enforcer.releaseCaps(m.getId(), txnId, NOW);

        ArgumentCaptor<MandateLedgerEntry> captor = ArgumentCaptor.forClass(MandateLedgerEntry.class);
        verify(ledgerRepository).saveAndFlush(captor.capture());
        MandateLedgerEntry reversal = captor.getValue();
        assertEquals(MandateLedgerEntry.KIND_REVERSAL, reversal.getEntryKind());
        assertEquals(10_000, reversal.getAmountPaise()); // own charge, not the 90_000 latest
        assertEquals(80_000, reversal.getDailySpendPaise());
    }

    /**
     * JAVA-001: the mandate ROW is locked before the ledger is read. Locking
     * only the latest ledger row let two first charges (no row to lock) both
     * read 0 and together exceed the daily cap.
     */
    @Test
    void reserveCapsLocksTheMandateRowBeforeReadingTheLedger() {
        PaymentMandate m = mandate();
        when(ledgerRepository.findLatestForUpdate(m.getId())).thenReturn(Optional.empty());

        enforcer.reserveCaps(m, UUID.randomUUID(), 10_000, NOW);

        org.mockito.InOrder order = org.mockito.Mockito.inOrder(mandateRepository, ledgerRepository);
        order.verify(mandateRepository).lockForCapUpdate(m.getId());
        order.verify(ledgerRepository).findLatestForUpdate(m.getId());
        order.verify(ledgerRepository).save(any(MandateLedgerEntry.class));
    }

    @Test
    void reserveCapsRefusesWhenTheMandateRowIsGone() {
        PaymentMandate m = mandate();
        when(mandateRepository.lockForCapUpdate(m.getId())).thenReturn(Optional.empty());

        MandateViolationException e = assertThrows(MandateViolationException.class,
            () -> enforcer.reserveCaps(m, UUID.randomUUID(), 10_000, NOW));
        assertEquals("MANDATE_NOT_FOUND", e.getCode());
        verify(ledgerRepository, never()).save(any());
    }

    @Test
    void releaseCapsLocksTheMandateRowBeforeReadingTheLedger() {
        UUID mandateId = UUID.randomUUID();
        UUID txnId = UUID.randomUUID();

        enforcer.releaseCaps(mandateId, txnId, NOW);

        org.mockito.InOrder order = org.mockito.Mockito.inOrder(mandateRepository, ledgerRepository);
        order.verify(mandateRepository).lockForCapUpdate(mandateId);
        order.verify(ledgerRepository).findLatestForUpdate(mandateId);
    }
}
