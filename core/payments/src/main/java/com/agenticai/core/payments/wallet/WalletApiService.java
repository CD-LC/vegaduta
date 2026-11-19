package com.agenticai.core.payments.wallet;

import com.agenticai.core.payments.wallet.WalletDtos.LedgerEntryResponse;
import com.agenticai.core.payments.wallet.WalletDtos.WalletResponse;
import com.agenticai.core.security.edition.ConditionalOnEditionFeature;
import com.agenticai.core.security.edition.EditionFeature;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Service facade for {@link WalletController}: wraps {@link AgentWalletService} and maps wallet /
 * ledger entities to response DTOs so the controller never handles JPA entities. Transaction
 * boundaries stay on {@link AgentWalletService}'s own methods.
 */
@Service
@ConditionalOnEditionFeature(EditionFeature.BILLING_PAYMENTS)
public class WalletApiService {

    private final AgentWalletService walletService;

    public WalletApiService(AgentWalletService walletService) {
        this.walletService = walletService;
    }

    public WalletResponse open(UUID agentId, String currency) {
        return WalletDtos.toResponse(walletService.getOrCreate(agentId, currency));
    }

    public List<WalletResponse> listForAgent(UUID agentId) {
        return walletService.listForAgent(agentId).stream().map(WalletDtos::toResponse).toList();
    }

    public Optional<WalletResponse> find(UUID walletId) {
        return walletService.findById(walletId).map(WalletDtos::toResponse);
    }

    public boolean exists(UUID walletId) {
        return walletService.findById(walletId).isPresent();
    }

    public LedgerEntryResponse credit(
            UUID walletId, long amountMinor, String idempotencyKey, String note) {
        return WalletDtos.toResponse(
                walletService.credit(
                        walletId, amountMinor, idempotencyKey, "operator_topup", null, note));
    }

    public List<LedgerEntryResponse> ledger(UUID walletId, int limit, long offset) {
        return walletService.ledger(walletId, limit, offset).stream()
                .map(WalletDtos::toResponse)
                .toList();
    }

    public long ledgerCount(UUID walletId) {
        return walletService.ledgerCount(walletId);
    }
}
