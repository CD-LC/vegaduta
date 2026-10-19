package com.agenticai.core.agents.version;

import java.time.Instant;
import java.util.UUID;

/**
 * One entry in {@code GET /api/agents/{agentId}/versions}, and the full body of
 * {@code GET /api/agents/{agentId}/versions/{versionId}}.
 *
 * <p>{@code config} is the difference between the two, and it is deliberate: the
 * LIST omits it, the single GET includes it. A version list is a changelog - name,
 * model, what changed, who, when, which label - and shipping N complete
 * configuration bundles to render that would be several kilobytes of JSON per row
 * that no list view reads. A caller who wants the actual configuration asks for
 * one version by id and gets the decoded bundle, not the raw stored string, so the
 * stored document's exact serialization stays an implementation detail rather than
 * a public contract.
 *
 * @param config the decoded configuration bundle, or {@code null} on list rows.
 */
public record AgentVersionResponse(
    UUID id,
    UUID agentId,
    int versionNumber,
    String agentName,
    String providerName,
    String model,
    String changeSummary,
    AgentVersionLabel label,
    String createdBy,
    Instant createdAt,
    AgentConfigSnapshot config
) {

    /** List row - identical to {@link #full} minus the configuration bundle. */
    public static AgentVersionResponse summary(AgentVersion version) {
        return build(version, null);
    }

    public static AgentVersionResponse full(AgentVersion version, AgentConfigSnapshot config) {
        return build(version, config);
    }

    private static AgentVersionResponse build(AgentVersion version, AgentConfigSnapshot config) {
        return new AgentVersionResponse(
            version.getId(),
            version.getAgentId(),
            version.getVersionNumber(),
            version.getAgentName(),
            version.getProviderName(),
            version.getModel(),
            version.getChangeSummary(),
            version.getLabel(),
            version.getCreatedBy(),
            version.getCreatedAt(),
            config
        );
    }
}
