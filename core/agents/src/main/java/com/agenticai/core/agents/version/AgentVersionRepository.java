package com.agenticai.core.agents.version;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Reads over {@code agent_version} (migration V212). Query-method only, no {@code @Query}: every
 * access here is a single-table lookup on an indexed column, and derived names keep the "scoped by
 * agentId, not just id" convention visible in the signature rather than buried in JPQL.
 *
 * <p>No custom bean name is declared, and none is needed: {@code AgentVersion*} simple names are
 * unique across {@code core/&#42;/src/main/java} (checked at authoring time), which is what
 * core/app's {@code UniqueBeanNamesTest} enforces. That test also notes a repository INTERFACE
 * cannot carry an explicit bean name - renaming would be the only fix - so uniqueness has to hold
 * by construction here.
 */
public interface AgentVersionRepository extends JpaRepository<AgentVersion, UUID> {

    /** The version list, newest first - served by {@code idx_agent_version_agent_id_created_at}. */
    List<AgentVersion> findByAgentIdOrderByCreatedAtDesc(UUID agentId);

    // Scoped by agentId as well as id, for the same reason
    // PromptVersionRepository#findByIdAndAgentId documents: schema-per-tenant
    // already makes a cross-TENANT id unreachable, and this additionally stops a
    // same-tenant CROSS-AGENT id typo from resolving - which matters more here
    // than for prompts, because the thing it would restore is a whole different
    // agent's provider, model, tool allow-list and permission scopes.
    Optional<AgentVersion> findByIdAndAgentId(UUID id, UUID agentId);

    /**
     * The most recent row for an agent - used both to assign the next {@code versionNumber} and to
     * compare against the config about to be written, so an edit that changed nothing appends no
     * duplicate row.
     */
    Optional<AgentVersion> findFirstByAgentIdOrderByVersionNumberDesc(UUID agentId);

    /**
     * The current holder of a label for this agent, if any. At most one row can match - migration
     * V212's partial unique index on {@code (agent_id, label)} is what makes that an invariant
     * rather than a hope.
     */
    Optional<AgentVersion> findByAgentIdAndLabel(UUID agentId, AgentVersionLabel label);

    /**
     * Numbered lookup for the diff endpoint ({@code .../versions/diff?from=&to=} takes ordinals,
     * like the workflow API).
     */
    Optional<AgentVersion> findByAgentIdAndVersionNumber(UUID agentId, int versionNumber);
}
