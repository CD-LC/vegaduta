package com.agenticai.core.workflow.dto;

import com.agenticai.core.providers.ProviderName;

/**
 * PATCH /api/workflows/{workflowId}/cost-routing - configures an economy-tier
 * provider/model to use for workflow steps that don't need a premium model
 * (mirrors {@code AgentCostRoutingUpdateRequest} from the agents module).
 * {@code economyProviderName} and {@code economyModel} are only meaningful
 * when {@code enabled} is true; both are cleared when the economy provider is
 * null, consistent with the "both-together-or-neither" invariant on
 * {@link com.agenticai.core.workflow.WorkflowDefinition#setCostRouting}.
 */
public record WorkflowCostRoutingUpdateRequest(
    boolean enabled,
    ProviderName economyProviderName,
    String economyModel
) {
    public WorkflowCostRoutingUpdateRequest {
        if (economyModel != null) {
            economyModel = economyModel.trim();
        }
    }
}
