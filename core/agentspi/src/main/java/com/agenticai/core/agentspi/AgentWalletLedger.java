package com.agenticai.core.agentspi;

import com.agenticai.core.security.http.HttpStatusMapped;
import java.util.Optional;
import java.util.UUID;

/**
 * An agent's internal wallet ledger, as the x402 seller side ({@code tools.x402}) needs it: resolve
 * a settlement wallet, and move funds between agent wallets idempotently. Kernel-owned (ADR-0011
 * rule a); implemented over the payments module's {@code AgentWalletService} by {@code
 * payments.agenttools.PaymentsAgentWalletLedger}. Every call runs in the caller's tenant schema.
 */
public interface AgentWalletLedger {

    /** The parts of a wallet the kernel reads. */
    record WalletRef(UUID id, UUID agentId, String currency) {}

    /** The agent's wallet in {@code currency}, created on first use. */
    WalletRef getOrCreate(UUID agentId, String currency);

    Optional<WalletRef> findById(UUID walletId);

    /**
     * Credits funds in. Idempotent on {@code idempotencyKey}.
     *
     * @return the ledger entry id
     * @throws WalletLedgerException with the ledger's failure code
     */
    Long credit(
            UUID walletId,
            long amountMinor,
            String idempotencyKey,
            String referenceType,
            String referenceId,
            String note);

    /**
     * Spends funds. Idempotent on {@code idempotencyKey}.
     *
     * @return the ledger entry id
     * @throws WalletLedgerException {@code INSUFFICIENT_FUNDS} when the balance cannot cover it, or
     *     another ledger code
     */
    Long debit(
            UUID walletId,
            long amountMinor,
            String idempotencyKey,
            String referenceType,
            String referenceId,
            String note);

    /**
     * A ledger refusal, carrying the ledger's own code (e.g. {@code INSUFFICIENT_FUNDS}, {@code
     * WALLET_NOT_ACTIVE}) and the HTTP status the ledger maps it to, so an endpoint that lets it
     * escape answers exactly as it would for the ledger's own exception.
     */
    class WalletLedgerException extends RuntimeException implements HttpStatusMapped {

        private final String code;
        private final int httpStatus;

        public WalletLedgerException(String code, String message, int httpStatus, Throwable cause) {
            super(message, cause);
            this.code = code;
            this.httpStatus = httpStatus;
        }

        public String getCode() {
            return code;
        }

        @Override
        public int httpStatus() {
            return httpStatus;
        }

        @Override
        public String errorCode() {
            return code;
        }
    }
}
