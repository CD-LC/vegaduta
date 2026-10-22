package com.agenticai.core.agents.tools.x402;

import com.agenticai.core.agentspi.AgentWalletLedger;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;

/**
 * The {@link AgentWalletLedger} the x402 seller side falls back to when the install carries no payments
 * module wiring (the {@code BILLING_PAYMENTS} edition feature is off, as on the personal edition). Every
 * call answers one explicit "payments are not available on this install" refusal, mapped to 404 like the
 * payments endpoints that are absent in that edition, instead of the application failing to start because
 * {@code X402SellerService} and {@code X402OfferController} could not find a ledger.
 *
 * <p>Not a bean: the x402 beans resolve the real ledger through {@link ObjectProvider} and use this one only
 * when the provider is empty.
 */
final class PaymentsDisabledAgentWalletLedger implements AgentWalletLedger {

    /** The code carried by every refusal. */
    static final String CODE = "PAYMENTS_DISABLED";

    private static final String MESSAGE = "payments are not available on this install";

    private static final PaymentsDisabledAgentWalletLedger INSTANCE = new PaymentsDisabledAgentWalletLedger();

    private PaymentsDisabledAgentWalletLedger() {}

    /** The real ledger when the payments module is wired, otherwise the refusing stand-in. */
    static AgentWalletLedger orDisabled(ObjectProvider<AgentWalletLedger> provider) {
        AgentWalletLedger ledger = provider == null ? null : provider.getIfAvailable();
        return ledger != null ? ledger : INSTANCE;
    }

    @Override
    public WalletRef getOrCreate(UUID agentId, String currency) {
        throw refusal();
    }

    @Override
    public Optional<WalletRef> findById(UUID walletId) {
        throw refusal();
    }

    @Override
    public Long credit(
            UUID walletId,
            long amountMinor,
            String idempotencyKey,
            String referenceType,
            String referenceId,
            String note) {
        throw refusal();
    }

    @Override
    public Long debit(
            UUID walletId,
            long amountMinor,
            String idempotencyKey,
            String referenceType,
            String referenceId,
            String note) {
        throw refusal();
    }

    private static WalletLedgerException refusal() {
        return new WalletLedgerException(CODE, MESSAGE, 404, null);
    }
}
