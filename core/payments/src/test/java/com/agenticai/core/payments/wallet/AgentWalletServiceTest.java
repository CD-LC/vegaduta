package com.agenticai.core.payments.wallet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.audit.AuditLogService;
import com.agenticai.core.payments.wallet.WalletLedgerEntry.EntryKind;
import com.agenticai.core.security.TenantContext;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/** Idempotency + balance-correctness for the wallet's FOR UPDATE critical section. */
class AgentWalletServiceTest {

    private AgentWalletRepository walletRepository;
    private WalletLedgerRepository ledgerRepository;
    private AgentWalletService service;

    @BeforeEach
    void setUp() {
        walletRepository = mock(AgentWalletRepository.class);
        ledgerRepository = mock(WalletLedgerRepository.class);
        ObjectProvider<AuditLogService> audit = mock(ObjectProvider.class);
        when(audit.getIfAvailable()).thenReturn(null);
        service = new AgentWalletService(walletRepository, ledgerRepository, audit);
        TenantContext.set(TenantContext.of("acme", "op-1", Set.of()));
        when(ledgerRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private AgentWallet wallet(long balance) {
        AgentWallet w = new AgentWallet();
        w.setId(UUID.randomUUID());
        w.setAgentId(UUID.randomUUID());
        w.setCurrency("INR");
        w.setCreatedBy("op-1");
        w.applyMovement(balance, java.time.Instant.now());
        return w;
    }

    @Test
    void creditThenDebitTracksBalance() {
        AgentWallet w = wallet(0);
        when(walletRepository.findByIdForUpdate(w.getId())).thenReturn(Optional.of(w));
        when(ledgerRepository.findByIdempotencyKey(any())).thenReturn(Optional.empty());

        WalletLedgerEntry credit = service.credit(w.getId(), 10_000, "topup:1", "test", null, null);
        assertEquals(EntryKind.CREDIT, credit.getEntryKind());
        assertEquals(10_000, credit.getBalanceAfter());
        assertEquals(10_000, w.getBalanceMinor());

        WalletLedgerEntry debit = service.debit(w.getId(), 4_000, "spend:1", "test", null, null);
        assertEquals(EntryKind.DEBIT, debit.getEntryKind());
        assertEquals(6_000, debit.getBalanceAfter());
        assertEquals(6_000, w.getBalanceMinor());
    }

    @Test
    void debitBeyondBalanceFailsInsideLock() {
        AgentWallet w = wallet(500);
        when(walletRepository.findByIdForUpdate(w.getId())).thenReturn(Optional.of(w));
        when(ledgerRepository.findByIdempotencyKey(any())).thenReturn(Optional.empty());

        WalletException e = assertThrows(WalletException.class,
            () -> service.debit(w.getId(), 501, "spend:x", "test", null, null));
        assertEquals("INSUFFICIENT_FUNDS", e.getCode());
        assertEquals(500, w.getBalanceMinor()); // untouched
    }

    @Test
    void idempotentKeyFoldsOntoFirstMovement() {
        WalletLedgerEntry first = WalletLedgerEntry.of(
            UUID.randomUUID(), EntryKind.DEBIT, 100, 400, "x402:nonce1", "x402", "p1", null, "sys");
        when(ledgerRepository.findByIdempotencyKey("x402:nonce1")).thenReturn(Optional.of(first));
        // The lock read never even happens - the fold happens before it.
        WalletLedgerEntry replayed = service.debit(
            first.getWalletId(), 100, "x402:nonce1", "x402", "p1", null);
        assertSame(first, replayed);
    }

    @Test
    void frozenWalletRejectsMovement() {
        AgentWallet w = wallet(10_000);
        w.setStatus(AgentWallet.Status.FROZEN);
        when(walletRepository.findByIdForUpdate(w.getId())).thenReturn(Optional.of(w));
        when(ledgerRepository.findByIdempotencyKey(any())).thenReturn(Optional.empty());
        WalletException e = assertThrows(WalletException.class,
            () -> service.debit(w.getId(), 1, "spend:f", "test", null, null));
        assertEquals("WALLET_NOT_ACTIVE", e.getCode());
    }
}
