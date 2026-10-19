package com.agenticai.core.payments.wallet;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;

/** Request/response records for the wallet surface. */
public final class WalletDtos {

    private WalletDtos() {
    }

    public record OpenWalletRequest(
        @NotNull UUID agentId,
        @NotBlank @Size(min = 3, max = 3) String currency
    ) {
    }

    public record CreditRequest(
        @Positive long amountMinor,
        @NotBlank @Size(max = 160) String idempotencyKey,
        @Size(max = 512) String note
    ) {
    }

    public record WalletResponse(
        UUID id, UUID agentId, String currency, long balanceMinor,
        String status, Instant createdAt, Instant updatedAt
    ) {
    }

    public record LedgerEntryResponse(
        Long id, UUID walletId, String entryKind, long amountMinor, long balanceAfter,
        String idempotencyKey, String referenceType, String referenceId,
        String note, String createdBy, Instant createdAt
    ) {
    }

    static WalletResponse toResponse(AgentWallet w) {
        return new WalletResponse(
            w.getId(), w.getAgentId(), w.getCurrency(), w.getBalanceMinor(),
            w.getStatus().name(), w.getCreatedAt(), w.getUpdatedAt());
    }

    static LedgerEntryResponse toResponse(WalletLedgerEntry e) {
        return new LedgerEntryResponse(
            e.getId(), e.getWalletId(), e.getEntryKind().name(), e.getAmountMinor(),
            e.getBalanceAfter(), e.getIdempotencyKey(), e.getReferenceType(),
            e.getReferenceId(), e.getNote(), e.getCreatedBy(), e.getCreatedAt());
    }
}
