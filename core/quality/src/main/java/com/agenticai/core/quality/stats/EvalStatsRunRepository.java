package com.agenticai.core.quality.stats;

import com.agenticai.core.quality.dataset.EvalRun;
import com.agenticai.core.quality.dataset.EvalRunStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

/**
 * Read-only, agent-scoped view over {@code eval_run} for the reliability
 * statistics surface.
 *
 * <p><strong>Why a second repository over the same entity.</strong> The query
 * this package needs - "the most recent SUCCEEDED runs of this dataset" - does
 * not exist on {@code EvalRunRepository}, whose dataset query carries no status
 * filter (an aborted or orphaned run must never contribute a rollout, so the
 * filter cannot be applied in Java after a truncating page fetch without
 * silently shortening the window). Adding it there would mean editing a file
 * owned by the
 * eval-dataset workstream for this package's convenience; Spring Data is
 * perfectly happy with several repository interfaces over one entity, so a
 * narrow read-side interface here keeps the change purely additive. Same
 * reasoning {@code EvalDatasetController} records for copying the plan gate
 * rather than hoisting it out of {@code QualityController}: four lines beat the
 * coupling.
 *
 * <p>Extends the bare {@link org.springframework.data.repository.Repository}
 * marker rather than {@code JpaRepository} on purpose - this surface is
 * read-only and must not expose {@code save}/{@code delete} on eval history,
 * which is append-only by design ({@code EvalRunCaseResult}'s javadoc).
 *
 * <p>{@code eval_run} lives in the TENANT schema (no {@code schema=} on its
 * {@code @Table}, unlike {@code AgentQualityRecord} which is explicitly
 * control-plane), so every query here resolves against the calling thread's
 * tenant via {@code SchemaMultiTenantConnectionProvider}. That is the desired
 * isolation: one tenant can never see another's runs through this surface, and
 * no explicit tenant predicate is needed or wanted.
 */
@Repository
public interface EvalStatsRunRepository extends org.springframework.data.repository.Repository<EvalRun, UUID> {

    /**
     * Runs for one dataset in a given status, newest first.
     *
     * <p>Ordered by {@code createdAt} descending so the caller takes the most
     * RECENT window of runs: reliability should describe the agent as it is
     * today, and a window reaching back eight months would silently average over
     * every prompt change since. The status filter is in the query rather than
     * applied afterwards so that a page of {@code n} rows is always {@code n}
     * usable runs.
     */
    List<EvalRun> findByDatasetIdAndStatusOrderByCreatedAtDesc(
        UUID datasetId, EvalRunStatus status, Pageable pageable);

    /** One run by id - used by the compare endpoint, which is addressed by run ids. */
    Optional<EvalRun> findById(UUID id);
}
