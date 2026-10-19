package com.agenticai.core.quality.dataset;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read-only lookup of the few {@code agent}-table fields the judges need
 * ({@code name}, {@code system_prompt}, {@code model}) via a native query,
 * because this module cannot reference the {@code Agent} entity (see
 * {@link AgentTurnReplayer}'s javadoc for the module-boundary story).
 *
 * <p>Data-level rather than code-level coupling, and a deliberately tiny
 * surface: three stable columns that have existed since the original agent
 * migration, selected by primary key. The query is UNQUALIFIED so it
 * resolves against the tenant schema on the connection's search_path - the
 * same routing every tenant repository in this module already relies on -
 * which is also why callers must have {@code TenantContext} set (the run
 * worker does, from the run row).
 *
 * <p>{@code CAST(:id AS uuid)} with a string parameter instead of binding
 * the UUID object directly: pgjdbc's untyped-parameter handling for UUIDs
 * varies with {@code stringtype} settings, and a cast on our side is
 * unambiguous under all of them.
 */
@Component
public class AgentDefinitionLookup {

    @PersistenceContext
    private EntityManager entityManager;

    /** The judge-relevant slice of one agent row. */
    public record AgentDefinition(UUID agentId, String name, String systemPrompt, String model) {
    }

    /**
     * @return the agent's judge-relevant fields, or empty when no such row
     *     exists in the current tenant schema (agent deleted since the
     *     dataset was authored - the run surfaces this as a per-case/run
     *     error, mirroring agent_job's AGENT_NOT_FOUND posture)
     */
    @Transactional(readOnly = true)
    public Optional<AgentDefinition> find(UUID agentId) {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = entityManager
            .createNativeQuery("SELECT name, system_prompt, model FROM agent WHERE id = CAST(:id AS uuid)")
            .setParameter("id", agentId.toString())
            .getResultList();
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        Object[] row = rows.get(0);
        return Optional.of(new AgentDefinition(
            agentId, (String) row[0], (String) row[1], (String) row[2]));
    }

    /**
     * The agent's replay-relevant configuration for {@link AgentConfigSnapshot}
     * (V375): sampling parameters, a hash of the system prompt, and the
     * sorted tool allow-list. Same unqualified-native-query / tenant-schema
     * contract as {@link #find}. Empty when the agent row does not exist.
     */
    // REQUIRES_NEW: a failed SQL statement here must not poison (rollback-only / aborted) the caller's
    // trigger transaction - provenance is best-effort.
    @Transactional(readOnly = true, propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public Optional<AgentConfigSnapshot> findSnapshot(UUID agentId) {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = entityManager
            .createNativeQuery(
                "SELECT model, temperature, top_p, max_tokens, seed, system_prompt "
                    + "FROM agent WHERE id = CAST(:id AS uuid)")
            .setParameter("id", agentId.toString())
            .getResultList();
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        Object[] row = rows.get(0);
        @SuppressWarnings("unchecked")
        List<Object> toolRows = entityManager
            .createNativeQuery(
                "SELECT tool_name FROM agent_allowed_tool WHERE agent_id = CAST(:id AS uuid) ORDER BY tool_name")
            .setParameter("id", agentId.toString())
            .getResultList();
        List<String> tools = toolRows.stream().map(String::valueOf).sorted().toList();
        String prompt = row[5] == null ? "" : (String) row[5];
        return Optional.of(new AgentConfigSnapshot(
            (String) row[0],
            row[1] == null ? null : ((Number) row[1]).doubleValue(),
            row[2] == null ? null : ((Number) row[2]).doubleValue(),
            row[3] == null ? null : ((Number) row[3]).intValue(),
            row[4] == null ? null : ((Number) row[4]).intValue(),
            CanonicalJson.sha256Hex(prompt),
            tools));
    }
}
