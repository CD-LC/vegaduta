package com.agenticai.core.quality.dataset;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read-only lookup of an agent's HEAD {@code agent_version} row via a native
 * query - the same module-boundary posture as {@link AgentDefinitionLookup}
 * (this module cannot reference the agents module's {@code AgentVersion}
 * entity; see {@link AgentTurnReplayer}'s javadoc for the boundary story).
 *
 * <p>"Head" means the newest snapshot by {@code version_number}, which under
 * V212's convention is the agent's LIVE configuration: {@code AgentService}
 * appends a version row after every config save. That is exactly the identity
 * an eval run needs - a run replays whatever the agent is configured as at
 * trigger time, and the head row is the persisted name of that configuration.
 *
 * <p>The eval gate relies on this binding in the other direction too: a run
 * stamped with version V can satisfy a promote of any version whose
 * {@code config_json} is byte-identical to V's, because the snapshot codec's
 * canonical output makes identical configs serialize identically (see
 * {@code AgentVersionSnapshotService}'s dedup note).
 */
@Component
public class AgentHeadVersionLookup {

    @PersistenceContext
    private EntityManager entityManager;

    /**
     * @return the id of the agent's newest {@code agent_version} row, or empty
     *     when the agent has none (pre-V212 agent with no recorded save, or a
     *     snapshot write that failed open - see {@code
     *     AgentVersionSnapshotService}). Empty is an honest "unversioned"
     *     answer; callers store {@code null} rather than fabricating a binding.
     */
    @Transactional(readOnly = true)
    public Optional<UUID> findHeadVersionId(UUID agentId) {
        @SuppressWarnings("unchecked")
        List<Object> rows = entityManager
            .createNativeQuery(
                "SELECT id FROM agent_version WHERE agent_id = CAST(:id AS uuid) "
                    + "ORDER BY version_number DESC, created_at DESC LIMIT 1")
            .setParameter("id", agentId.toString())
            .getResultList();
        if (rows.isEmpty() || rows.get(0) == null) {
            return Optional.empty();
        }
        return Optional.of((UUID) rows.get(0));
    }
}
