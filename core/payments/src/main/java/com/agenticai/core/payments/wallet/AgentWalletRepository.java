package com.agenticai.core.payments.wallet;

import com.agenticai.core.security.edition.ConditionalOnEditionFeature;
import com.agenticai.core.security.edition.EditionFeature;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
@ConditionalOnEditionFeature(EditionFeature.BILLING_PAYMENTS)
public interface AgentWalletRepository extends JpaRepository<AgentWallet, UUID> {

    Optional<AgentWallet> findByAgentIdAndCurrency(UUID agentId, String currency);

    List<AgentWallet> findByAgentIdOrderByCreatedAtDesc(UUID agentId);

    /**
     * The serialization point for every balance movement - same FOR UPDATE
     * idiom as {@code mandate_ledger}'s cap row. Call only inside a
     * transaction.
     */
    @Query(value = "SELECT * FROM agent_wallet WHERE id = :id FOR UPDATE", nativeQuery = true)
    Optional<AgentWallet> findByIdForUpdate(@Param("id") UUID id);
}
