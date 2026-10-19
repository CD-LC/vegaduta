package com.agenticai.core.guardrails.dto;

import com.agenticai.core.guardrails.GuardrailRule;
import com.agenticai.core.guardrails.GuardrailRuleAction;
import com.agenticai.core.guardrails.GuardrailRuleType;
import java.time.Instant;
import java.util.UUID;

public record GuardrailRuleResponse(
    UUID id,
    String tenantId,
    GuardrailRuleType ruleType,
    String name,
    String description,
    String pattern,
    GuardrailRuleAction action,
    boolean enabled,
    Instant createdAt,
    Instant updatedAt
) {

    public static GuardrailRuleResponse from(GuardrailRule rule) {
        return new GuardrailRuleResponse(
            rule.getId(),
            rule.getTenantId(),
            rule.getRuleType(),
            rule.getName(),
            rule.getDescription(),
            rule.getPattern(),
            rule.getAction(),
            rule.isEnabled(),
            rule.getCreatedAt(),
            rule.getUpdatedAt()
        );
    }
}
