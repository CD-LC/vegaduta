package com.agenticai.core.agents.core;

import com.agenticai.core.agents.dto.AgentResponse;
import com.agenticai.core.agents.dto.PromptVersionResponse;
import com.agenticai.core.sharing.PermissionLevel;
import java.util.List;
import java.util.UUID;

/**
 * System-prompt edits and their append-only history (Master-Roadmap-2026.md item #5,
 * prompt-template versioning), behind {@link AgentService#updateSystemPrompt}, {@link
 * AgentService#listPromptVersions} and {@link AgentService#revertToPromptVersion}. Not a Spring
 * bean: {@link AgentService} builds it, and the transaction boundaries stay on its methods.
 */
final class AgentPromptHistory {

    private final PromptVersionRepository promptVersionRepository;
    private final AgentAccessService access;
    private final AgentVersioningService versioning;

    AgentPromptHistory(
            PromptVersionRepository promptVersionRepository,
            AgentAccessService access,
            AgentVersioningService versioning) {
        this.promptVersionRepository = promptVersionRepository;
        this.access = access;
        this.versioning = versioning;
    }

    /**
     * Consumer-facing capability - "consumers should be able to edit the agent prompt". Same
     * edit-lock gate as {@code updateModel} (a locked FREE-tier agent past its 24h grace window
     * can't use this either - duplicate it instead). No catalog-style validation needed here (a
     * system prompt has no "valid values" list the way a provider/model pair does) - bean
     * validation on {@code AgentPromptUpdateRequest} already rejects a blank prompt before this
     * method is ever called.
     *
     * <p>The prompt about to be overwritten is snapshotted into {@code prompt_version} first (see
     * {@link #snapshotCurrentPrompt}), so every past state stays recoverable via {@link
     * #listPromptVersions}/{@link #revertToPromptVersion} even though this method itself still
     * behaves exactly as before (an in-place overwrite of {@code agent.systemPrompt}).
     */
    AgentResponse updateSystemPrompt(UUID agentId, String systemPrompt) {
        Agent agent = access.requireAccess(agentId, PermissionLevel.EDITOR);
        access.assertEditable(agent);
        snapshotCurrentPrompt(agent);
        agent.setSystemPrompt(systemPrompt);
        // Both histories are written, on purpose. prompt_version keeps serving the
        // existing prompt-only endpoints unchanged (nothing about V90 is being
        // migrated or deprecated), while agent_version records the same edit as
        // part of a whole-config snapshot so a config rollback carries the prompt
        // with it. Storing systemPrompt in two tables is the deliberate cost of
        // not rewriting rows a tenant can already see and revert to.
        return versioning.saveVersioned(agent, "Edited system prompt");
    }

    /**
     * Append-only history for one agent's system prompt, most recent first. Never includes the
     * agent's current live prompt as a row (that always lives on {@code agent.systemPrompt} itself,
     * see {@link PromptVersion}'s javadoc) - only prompts that have since been replaced.
     */
    List<PromptVersionResponse> listPromptVersions(UUID agentId) {
        // VIEWER: prompt history is config, same visibility bar as a plain read.
        access.requireAccess(agentId, PermissionLevel.VIEWER);
        return promptVersionRepository.findByAgentIdOrderByCreatedAtDesc(agentId).stream()
                .map(PromptVersionResponse::from)
                .toList();
    }

    /**
     * Restores a past prompt as the agent's current one. Same edit-lock gate as {@code
     * updateSystemPrompt}, and just as genuinely non-destructive: the prompt being replaced by this
     * revert is itself snapshotted first (via {@code snapshotCurrentPrompt}), so reverting never
     * loses data - a revert can always be undone by reverting again.
     */
    AgentResponse revertToPromptVersion(UUID agentId, UUID versionId) {
        Agent agent = access.requireAccess(agentId, PermissionLevel.EDITOR);
        access.assertEditable(agent);
        PromptVersion target =
                promptVersionRepository
                        .findByIdAndAgentId(versionId, agentId)
                        .orElseThrow(() -> new PromptVersionNotFoundException(versionId, agentId));
        snapshotCurrentPrompt(agent);
        agent.setSystemPrompt(target.getSystemPrompt());
        return versioning.saveVersioned(agent, "Reverted system prompt to an earlier version");
    }

    private void snapshotCurrentPrompt(Agent agent) {
        promptVersionRepository.save(
                PromptVersion.snapshot(agent.getId(), agent.getSystemPrompt()));
    }
}
