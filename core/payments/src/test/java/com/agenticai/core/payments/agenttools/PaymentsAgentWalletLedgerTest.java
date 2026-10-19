package com.agenticai.core.payments.agenttools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.agentspi.AgentWalletLedger;
import com.agenticai.core.payments.wallet.AgentWallet;
import com.agenticai.core.payments.wallet.AgentWalletService;
import com.agenticai.core.payments.wallet.WalletException;
import com.agenticai.core.payments.wallet.WalletLedgerEntry;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The kernel's wallet ledger over AgentWalletService: wallets become refs, entries their ids,
 * refusals keep their code.
 */
class PaymentsAgentWalletLedgerTest {

    private final AgentWalletService service = mock(AgentWalletService.class);
    private final AgentWalletLedger ledger = new PaymentsAgentWalletLedger(service);

    @Test
    void walletsAndEntriesAreTranslated() {
        UUID walletId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        AgentWallet wallet = mock(AgentWallet.class);
        when(wallet.getId()).thenReturn(walletId);
        when(wallet.getAgentId()).thenReturn(agentId);
        when(wallet.getCurrency()).thenReturn("INR");
        when(service.getOrCreate(agentId, "INR")).thenReturn(wallet);
        when(service.findById(walletId)).thenReturn(Optional.of(wallet));
        WalletLedgerEntry entry = mock(WalletLedgerEntry.class);
        when(entry.getId()).thenReturn(42L);
        when(service.debit(walletId, 500, "k", "x402", "r", "n")).thenReturn(entry);
        when(service.credit(walletId, 500, "k2", "x402", "r", "n")).thenReturn(entry);

        assertEquals(
                new AgentWalletLedger.WalletRef(walletId, agentId, "INR"),
                ledger.getOrCreate(agentId, "INR"));
        assertEquals(
                Optional.of(new AgentWalletLedger.WalletRef(walletId, agentId, "INR")),
                ledger.findById(walletId));
        assertEquals(42L, ledger.debit(walletId, 500, "k", "x402", "r", "n"));
        assertEquals(42L, ledger.credit(walletId, 500, "k2", "x402", "r", "n"));
    }

    @Test
    void aLedgerRefusalKeepsItsCode() {
        UUID walletId = UUID.randomUUID();
        WalletException refusal =
                new WalletException("INSUFFICIENT_FUNDS", "balance 1 cannot cover 500");
        when(service.debit(walletId, 500, "k", "x402", "r", "n")).thenThrow(refusal);

        AgentWalletLedger.WalletLedgerException e =
                assertThrows(
                        AgentWalletLedger.WalletLedgerException.class,
                        () -> ledger.debit(walletId, 500, "k", "x402", "r", "n"));

        assertEquals("INSUFFICIENT_FUNDS", e.getCode());
        assertEquals("balance 1 cannot cover 500", e.getMessage());
        assertEquals(402, e.httpStatus());
        assertEquals("INSUFFICIENT_FUNDS", e.errorCode());
        assertSame(refusal, e.getCause());
    }
}
