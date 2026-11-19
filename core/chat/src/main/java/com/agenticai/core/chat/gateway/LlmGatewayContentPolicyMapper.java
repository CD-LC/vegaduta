package com.agenticai.core.chat.gateway;

/** Maps the policy entity to its API response, keeping the entity out of the controller. */
final class LlmGatewayContentPolicyMapper {

    private LlmGatewayContentPolicyMapper() {}

    static LlmGatewayContentPolicyController.ContentPolicyResponse toResponse(LlmGatewayContentPolicy policy) {
        return new LlmGatewayContentPolicyController.ContentPolicyResponse(
                policy.isPiiRedactionEnabled(),
                policy.isPiiRedactEmail(),
                policy.isPiiRedactPhone(),
                policy.isPiiRedactGovernmentId(),
                policy.isPiiRedactCreditCard(),
                policy.isInjectionScreeningEnabled(),
                policy.getInjectionScreeningAction().name());
    }
}
