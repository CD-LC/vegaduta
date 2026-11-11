package com.agenticai.core.payments.wallet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * {@link AgentWallet} is a (agent, currency) balance mutated only inside the service's row lock via
 * {@code applyMovement}; {@link WalletLedgerEntry} is the append-only record of each movement,
 * built exclusively through {@code of(...)}. {@link WalletDtos}'s mappers are the API shape over
 * both.
 */
class AgentWalletAndLedgerTest {

    @Test
    void aWalletStartsActiveAndAppliesMovements() {
        AgentWallet w = new AgentWallet();
        assertEquals(AgentWallet.Status.ACTIVE, w.getStatus());
        UUID agent = UUID.randomUUID();
        w.setId(UUID.randomUUID());
        w.setAgentId(agent);
        w.setCurrency("INR");
        w.setCreatedBy("user-1");

        Instant at = Instant.parse("2026-10-05T12:00:00Z");
        w.applyMovement(50_000, at);
        assertEquals(50_000, w.getBalanceMinor());
        assertEquals(at, w.getUpdatedAt());
        w.applyMovement(-12_500, at);
        assertEquals(37_500, w.getBalanceMinor(), "a debit is a negative movement");

        w.setStatus(AgentWallet.Status.FROZEN);
        assertEquals(AgentWallet.Status.FROZEN, w.getStatus());
        assertEquals(agent, w.getAgentId());
        assertEquals("INR", w.getCurrency());
        assertEquals("user-1", w.getCreatedBy());
        assertNotNull(w.getCreatedAt());
        assertEquals(3, AgentWallet.Status.values().length);
    }

    @Test
    void aLedgerEntryIsBuiltOnlyThroughOf() {
        UUID wallet = UUID.randomUUID();
        WalletLedgerEntry e =
                WalletLedgerEntry.of(
                        wallet,
                        WalletLedgerEntry.EntryKind.DEBIT,
                        12_500,
                        37_500,
                        "capture-1",
                        "commerce_mandate",
                        "cart-9",
                        "cart capture",
                        "system");
        assertNull(e.getId(), "IDENTITY id stays null until persist");
        assertEquals(wallet, e.getWalletId());
        assertEquals(WalletLedgerEntry.EntryKind.DEBIT, e.getEntryKind());
        assertEquals(12_500, e.getAmountMinor());
        assertEquals(37_500, e.getBalanceAfter());
        assertEquals("capture-1", e.getIdempotencyKey());
        assertEquals("commerce_mandate", e.getReferenceType());
        assertEquals("cart-9", e.getReferenceId());
        assertEquals("cart capture", e.getNote());
        assertEquals("system", e.getCreatedBy());
        assertNotNull(e.getCreatedAt());
        assertEquals(2, WalletLedgerEntry.EntryKind.values().length);
    }

    @Test
    void dtoMappersProjectTheEntitiesVerbatim() {
        AgentWallet w = new AgentWallet();
        w.setId(UUID.randomUUID());
        w.setAgentId(UUID.randomUUID());
        w.setCurrency("INR");
        w.setCreatedBy("u");
        w.applyMovement(1_000, Instant.parse("2026-10-05T00:00:00Z"));
        WalletDtos.WalletResponse wr = WalletDtos.toResponse(w);
        assertEquals(w.getId(), wr.id());
        assertEquals(w.getAgentId(), wr.agentId());
        assertEquals("INR", wr.currency());
        assertEquals(1_000, wr.balanceMinor());
        assertEquals("ACTIVE", wr.status());

        WalletLedgerEntry e =
                WalletLedgerEntry.of(
                        w.getId(),
                        WalletLedgerEntry.EntryKind.CREDIT,
                        1_000,
                        1_000,
                        "credit-1",
                        "admin",
                        "adj-1",
                        "manual top-up",
                        "ops");
        WalletDtos.LedgerEntryResponse er = WalletDtos.toResponse(e);
        assertEquals(w.getId(), er.walletId());
        assertEquals("CREDIT", er.entryKind());
        assertEquals(1_000, er.amountMinor());
        assertEquals(1_000, er.balanceAfter());
        assertEquals("credit-1", er.idempotencyKey());
        assertEquals("admin", er.referenceType());
        assertEquals("adj-1", er.referenceId());
        assertEquals("manual top-up", er.note());
        assertEquals("ops", er.createdBy());
    }

    @Test
    void walletExceptionMapsItsCodesToHttpStatuses() {
        assertEquals(404, new WalletException("WALLET_NOT_FOUND", "x").httpStatus());
        assertEquals(409, new WalletException("WALLET_NOT_ACTIVE", "x").httpStatus());
        assertEquals(402, new WalletException("INSUFFICIENT_FUNDS", "x").httpStatus());
        assertEquals(400, new WalletException("BAD_AMOUNT", "x").httpStatus());
        assertEquals(400, new WalletException("SOME_FUTURE_CODE", "x").httpStatus());
        WalletException e = new WalletException("INSUFFICIENT_FUNDS", "need 100");
        assertEquals("INSUFFICIENT_FUNDS", e.getCode());
        assertEquals("INSUFFICIENT_FUNDS", e.errorCode());
        assertEquals("need 100", e.getMessage());
    }

    @Test
    void theRequestRecordsExposeTheirComponents() {
        UUID agent = UUID.randomUUID();
        WalletDtos.OpenWalletRequest open = new WalletDtos.OpenWalletRequest(agent, "INR");
        assertEquals(agent, open.agentId());
        assertEquals("INR", open.currency());
        WalletDtos.CreditRequest credit = new WalletDtos.CreditRequest(500, "k-1", "note");
        assertEquals(500, credit.amountMinor());
        assertEquals("k-1", credit.idempotencyKey());
        assertEquals("note", credit.note());
    }
}
