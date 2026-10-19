package com.agenticai.core.workflow;

/**
 * The one seam the Dify-style AI nodes ({@code question_classifier}, {@code parameter_extractor}) call a model
 * through: a single, stateless, non-streaming completion. Exists so {@link WorkflowAiNodeHandler} depends on a tiny
 * interface and the unit tests can substitute a fake model; production uses {@link WorkflowAiModelClient}, which
 * routes through the SAME provider/budget/cost path every other workflow LLM helper uses (the platform pool via
 * {@code FreeTierChatClientService}, BYOK via {@code LlmGatewayService}) - there is no second provider path.
 */
interface WorkflowAiModel {

    /**
     * One completion. Implementations throw a RuntimeException on provider failure; quota / budget exhaustion
     * surfaces as {@code TenantLimitExceededException} or {@code LlmGatewayLimitExceededException}, which callers
     * must let propagate (a run must not quietly continue past an exhausted budget).
     *
     * @return the model's reply text; never null (blank when the provider returned nothing)
     */
    String complete(Request request);

    /**
     * @param system      system prompt (static text, never carries untrusted data)
     * @param user        user message (carries the fenced untrusted text)
     * @param maxTokens   completion ceiling
     * @param temperature sampling temperature; the AI nodes always pass 0.0
     * @param provider    BYOK provider override (a {@code ProviderName}), or null/blank for the tenant's active one;
     *                    ignored for platform-pool tenants
     * @param model       model id override, or null/blank for the provider's default; ignored for platform-pool tenants
     */
    record Request(String system, String user, int maxTokens, double temperature, String provider, String model) {
    }
}
