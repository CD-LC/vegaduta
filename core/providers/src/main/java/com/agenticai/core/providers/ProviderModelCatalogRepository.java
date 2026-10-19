package com.agenticai.core.providers;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

public interface ProviderModelCatalogRepository extends JpaRepository<ProviderModelCatalogEntry, Long> {

    List<ProviderModelCatalogEntry> findAllByProviderNameOrderByModelIdAsc(ProviderName providerName);

    long countByProviderName(ProviderName providerName);

    /**
     * Chat file upload gating (Stage 4) - looks up one agent's currently-
     * resolved model to read its {@code supports*Input} flags, without
     * fetching the whole per-provider list the dropdown uses. See {@link
     * ProviderModelCatalogService#findModel}.
     */
    Optional<ProviderModelCatalogEntry> findByProviderNameAndModelId(ProviderName providerName, String modelId);

    /**
     * Every row the capability-summary generation job still owes a blurb to
     * - a brand-new row (never generated) or one a provider refresh just
     * re-inserted (refreshes are replace-not-merge, see {@link
     * #deleteAllByProviderName} below, so a re-seeded model's summary column
     * comes back null even if an older row for the same model id had one).
     * {@code ProviderModelResponse.from()} covers the gap with a
     * deterministic template in the meantime - this list is what the
     * generation job iterates to backfill the real thing.
     */
    List<ProviderModelCatalogEntry> findAllByCapabilitySummaryIsNull();

    /** Refreshes are replace-not-merge (a model removed upstream should disappear from the dropdown too). */
    @Modifying
    @Transactional
    @Query("DELETE FROM ProviderModelCatalogEntry e WHERE e.providerName = :providerName")
    void deleteAllByProviderName(ProviderName providerName);
}
