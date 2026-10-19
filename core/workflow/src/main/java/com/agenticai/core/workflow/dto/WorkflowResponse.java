package com.agenticai.core.workflow.dto;

import com.agenticai.core.agents.core.ReasoningDepth;
import com.agenticai.core.agents.core.TuningMode;
import com.agenticai.core.providers.ProviderName;
import com.agenticai.core.workflow.WorkflowDefinition;
import com.agenticai.core.workflow.WorkflowStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public record WorkflowResponse(
    UUID id,
    String name,
    String description,
    WorkflowStatus status,
    WorkflowGraphDto graph,
    Instant createdAt,
    Instant updatedAt,
    // Subscription Plan & GTM Strategy doc, Section 4 - same server-computed
    // edit-lock flag as AgentResponse.editable; see that field's javadoc.
    boolean editable,
    ReasoningDepth reasoningDepth,
    TuningMode tuningMode,
    boolean stepCachingEnabled,
    boolean costRoutingEnabled,
    ProviderName economyProviderName,
    String economyModel,
    int maxStepsPerRun,
    int stepTimeoutSeconds,
    int maxRetryAttempts,
    // Caller's effective share level on this workflow: "EDITOR" (owner, admin or EDITOR grant),
    // "RUNNER" or "VIEWER". The canvas uses it to hide controls the server would refuse (a draft
    // run needs EDITOR). Display hint only - every endpoint still enforces its own level.
    String accessLevel
) {

    private static final Logger log = LoggerFactory.getLogger(WorkflowResponse.class);

    /** Internal/non-request callers (no tenant plan to resolve) - always editable. */
    public static WorkflowResponse from(WorkflowDefinition entity, ObjectMapper objectMapper) {
        return from(entity, objectMapper, true);
    }

    public static WorkflowResponse from(WorkflowDefinition entity, ObjectMapper objectMapper, boolean editable) {
        return from(entity, objectMapper, editable, "EDITOR");
    }

    public static WorkflowResponse from(WorkflowDefinition entity, ObjectMapper objectMapper, boolean editable, String accessLevel) {
        WorkflowGraphDto graph;
        try {
            graph = objectMapper.readValue(entity.getGraphJson(), WorkflowGraphDto.class);
        } catch (Exception e) {
            // An empty graph is the only DTO we can build, but in the editor it is
            // indistinguishable from a workflow the user genuinely emptied - and a save from
            // that state overwrites the stored graphJson for real. Log so the corrupt row is
            // findable before a user unknowingly destroys it.
            log.warn("Workflow {} ('{}') has unparseable graphJson - returning an EMPTY graph; "
                + "the editor will show no nodes and a save from there will overwrite it",
                entity.getId(), entity.getName(), e);
            graph = new WorkflowGraphDto(java.util.List.of(), java.util.List.of());
        }
        return new WorkflowResponse(
            entity.getId(),
            entity.getName(),
            entity.getDescription(),
            entity.getStatus(),
            graph,
            entity.getCreatedAt(),
            entity.getUpdatedAt(),
            editable,
            entity.getReasoningDepth(),
            entity.getTuningMode(),
            entity.isStepCachingEnabled(),
            entity.isCostRoutingEnabled(),
            entity.getEconomyProviderName(),
            entity.getEconomyModel(),
            entity.getMaxStepsPerRun(),
            entity.getStepTimeoutSeconds(),
            entity.getMaxRetryAttempts(),
            accessLevel
        );
    }
}
