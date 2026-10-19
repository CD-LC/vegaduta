package com.agenticai.core.workflow;

import com.agenticai.core.providers.ProviderName;

public record WorkflowIntelligenceSettings(
    boolean stepCachingEnabled,
    boolean costRoutingEnabled,
    ProviderName economyProviderName,
    String economyModel
) {
    public static WorkflowIntelligenceSettings from(WorkflowDefinition def) {
        return new WorkflowIntelligenceSettings(
            def.isStepCachingEnabled(),
            def.isCostRoutingEnabled(),
            def.getEconomyProviderName(),
            def.getEconomyModel()
        );
    }

    public static WorkflowIntelligenceSettings defaults() {
        return new WorkflowIntelligenceSettings(false, false, null, null);
    }
}
