package com.agenticai.core.agents.core;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/**
 * Immutable snapshot of the agent fields that the read-only projection getters of {@link
 * AgentService} expose ({@code isKnowledgeBaseEnabled}, {@code getAutonomyTier}, {@code
 * getAllowedToolNames}, ...).
 *
 * <p>Why a snapshot and not the entity: one chat turn asks for these a few dozen times (every tool
 * resolver, every wrapped tool). Each ask used to be its own transaction, which is a connection
 * checkout plus two {@code SET search_path} round trips on top of the query. A value object built
 * once, inside the loading transaction, can be shared across threads and cached without handing out
 * a detached JPA entity whose lazy state could fail later. Sets are copied and unmodifiable, like
 * the entity's own getters.
 *
 * <p>Reads that make an access decision ({@code requireAccess}, edits) never use this; they read
 * the row.
 */
record AgentProjection(
        ReasoningDepth reasoningDepth,
        AgentAutonomyTier autonomyTier,
        BigDecimal runBudgetUsd,
        boolean knowledgeBaseEnabled,
        boolean sarvamEnabled,
        boolean e2bEnabled,
        boolean xaiServerToolsEnabled,
        boolean codeWorkspaceEnabled,
        boolean shareMemoryWithTenant,
        boolean biometricConsentEnabled,
        boolean knowledgeWriteEnabled,
        Set<String> allowedToolNames,
        Set<UUID> allowedKnowledgeCollectionIds,
        Set<String> allowedCodeIntelRepoIds,
        Set<UUID> allowedKnowledgeWriteCollectionIds,
        Set<UUID> collaboratorAgentIds) {

    /**
     * Builds the snapshot from a loaded agent. {@code lookup} finds another agent by id: a hidden
     * A/B treatment clone runs under its CONTROL agent's run cap, which is the one field that needs
     * a second row.
     */
    static AgentProjection from(Agent agent, Function<UUID, Optional<Agent>> lookup) {
        BigDecimal runBudget = agent.getRunBudgetUsd();
        UUID controlId = agent.getExperimentCloneOf();
        if (controlId != null) {
            // A treatment clone is built from the config snapshot, which leaves this field out.
            runBudget = lookup.apply(controlId).map(Agent::getRunBudgetUsd).orElse(runBudget);
        }
        return new AgentProjection(
                agent.getReasoningDepth(),
                agent.getAutonomyTier(),
                runBudget,
                agent.isKnowledgeBaseEnabled(),
                agent.isSarvamEnabled(),
                agent.isE2bEnabled(),
                agent.isXaiServerToolsEnabled(),
                agent.isCodeWorkspaceEnabled(),
                agent.isShareMemoryWithTenant(),
                agent.isBiometricConsentEnabled(),
                agent.isKnowledgeWriteEnabled(),
                Set.copyOf(agent.getAllowedToolNames()),
                Set.copyOf(agent.getAllowedKnowledgeCollectionIds()),
                Set.copyOf(agent.getAllowedCodeIntelRepoIds()),
                Set.copyOf(agent.getAllowedKnowledgeWriteCollectionIds()),
                Set.copyOf(agent.getCollaboratorAgentIds()));
    }
}
