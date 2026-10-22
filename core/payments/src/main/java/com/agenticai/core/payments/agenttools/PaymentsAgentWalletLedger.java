package com.agenticai.core.payments.agenttools;

import com.agenticai.core.agentspi.AgentWalletLedger;
import com.agenticai.core.payments.wallet.AgentWallet;
import com.agenticai.core.payments.wallet.AgentWalletService;
import com.agenticai.core.payments.wallet.WalletException;
import com.agenticai.core.security.edition.ConditionalOnEditionFeature;
import com.agenticai.core.security.edition.EditionFeature;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/**
 * The kernel's {@link AgentWalletLedger} over {@link AgentWalletService} (ADR-0011 rule a). A thin
 * translation: wallets become {@link AgentWalletLedger.WalletRef}s, ledger entries their ids, and a
 * {@link WalletException} becomes a {@link AgentWalletLedger.WalletLedgerException} with the same
 * code, message and HTTP status. Transactions, locking and idempotency stay in {@code
 * AgentWalletService}.
 */
@Component
@ConditionalOnEditionFeature(EditionFeature.BILLING_PAYMENTS)
public class PaymentsAgentWalletLedger implements AgentWalletLedger {

    private final AgentWalletService walletService;

    public PaymentsAgentWalletLedger(AgentWalletService walletService) {
        this.walletService = walletService;
    }

    @Override
    public WalletRef getOrCreate(UUID agentId, String currency) {
        return translated(() -> ref(walletService.getOrCreate(agentId, currency)));
    }

    @Override
    public Optional<WalletRef> findById(UUID walletId) {
        return translated(
                () -> walletService.findById(walletId).map(PaymentsAgentWalletLedger::ref));
    }

    @Override
    public Long credit(
            UUID walletId,
            long amountMinor,
            String idempotencyKey,
            String referenceType,
            String referenceId,
            String note) {
        return translated(
                () ->
                        walletService
                                .credit(
                                        walletId,
                                        amountMinor,
                                        idempotencyKey,
                                        referenceType,
                                        referenceId,
                                        note)
                                .getId());
    }

    @Override
    public Long debit(
            UUID walletId,
            long amountMinor,
            String idempotencyKey,
            String referenceType,
            String referenceId,
            String note) {
        return translated(
                () ->
                        walletService
                                .debit(
                                        walletId,
                                        amountMinor,
                                        idempotencyKey,
                                        referenceType,
                                        referenceId,
                                        note)
                                .getId());
    }

    private static WalletRef ref(AgentWallet wallet) {
        return new WalletRef(wallet.getId(), wallet.getAgentId(), wallet.getCurrency());
    }

    private static <T> T translated(Supplier<T> call) {
        try {
            return call.get();
        } catch (WalletException e) {
            throw new WalletLedgerException(e.getCode(), e.getMessage(), e.httpStatus(), e);
        }
    }
}
