package com.agenticai.core.workflow;

import com.agenticai.core.agents.budget.PerUserBudgetService;
import com.agenticai.core.agents.hosted.FreeTierChatClientService;
import com.agenticai.core.chat.gateway.GatewayCallSpec;
import com.agenticai.core.chat.gateway.LlmGatewayService;
import com.agenticai.core.providers.ProviderCredentialResolver;
import com.agenticai.core.providers.ProviderModelCatalogService;
import com.agenticai.core.providers.ProviderName;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.TenantLimits;
import com.agenticai.core.security.TenantPlan;
import java.util.Locale;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Production {@link WorkflowAiModel}: one stateless completion routed through the platform's existing LLM seams, with
 * no new provider path and no bypass of cost accounting, quotas or budgets. The routing decision is exactly the one
 * {@code WorkflowGraphGeneratorService#buildChatClient} and {@code WorkflowNodeExecutor#callEconomyModel} already make:
 *
 * <ul>
 *   <li><b>Platform pool</b> ({@link TenantLimits#usesPlatformPool}: FREE-plan tenants, tenants that opted into the
 *       platform LLM, complimentary grants): {@link FreeTierChatClientService#buildClientChainForFreeTenant} - which
 *       checks and reserves the tenant's monthly token quota, applies the per-user dollar budget when the caller has a
 *       subject, and attaches the token-tracking and metrics advisors. An exhausted quota/budget throws there, before
 *       any provider is engaged, and propagates (a workflow run does not continue past it).
 *   <li><b>BYOK</b> (everyone else): the tenant's own key through {@link LlmGatewayService#resolveClient}, which runs the
 *       gateway budget and rate-limit checks, honours the tenant's AI Model Control override and attaches governance.
 * </ul>
 *
 * Every client either path returns carries {@code LlmMetricsAdvisor}, so each call lands in {@code llm_call_log} with its
 * tokens and estimated cost - the same accounting an Agent node's turns get. Temperature is whatever the caller asks
 * for (the AI nodes ask for 0). A per-node {@code provider}/{@code model} override applies to BYOK tenants only; a
 * platform-pool tenant's model is chosen by the pool, so an override there is ignored rather than honoured against
 * credentials the tenant does not hold.
 */
@Service
class WorkflowAiModelClient implements WorkflowAiModel {

    private final TenantLimits tenantLimits;
    private final FreeTierChatClientService freeTierChatClientService;
    private final ProviderCredentialResolver credentialResolver;
    private final LlmGatewayService llmGatewayService;
    private final ProviderModelCatalogService providerModelCatalogService;
    private final String defaultModel;

    WorkflowAiModelClient(
        TenantLimits tenantLimits,
        FreeTierChatClientService freeTierChatClientService,
        ProviderCredentialResolver credentialResolver,
        LlmGatewayService llmGatewayService,
        ProviderModelCatalogService providerModelCatalogService,
        @Value("${app.agents.default-model:claude-sonnet-4-5}") String defaultModel
    ) {
        this.tenantLimits = tenantLimits;
        this.freeTierChatClientService = freeTierChatClientService;
        this.credentialResolver = credentialResolver;
        this.llmGatewayService = llmGatewayService;
        this.providerModelCatalogService = providerModelCatalogService;
        this.defaultModel = defaultModel;
    }

    @Override
    public String complete(Request request) {
        ChatClient client = buildClient(request);
        String content = client.prompt().system(request.system()).user(request.user()).call().content();
        return content == null ? "" : content;
    }

    private ChatClient buildClient(Request request) {
        TenantContext context = TenantContext.current().orElse(null);
        String tenantId = context == null ? null : context.getTenantId();
        String subject = context == null ? null : context.getSubject();
        TenantPlan plan = tenantId != null ? tenantLimits.plan(tenantId) : null;
        if (tenantLimits.usesPlatformPool(tenantId, plan)) {
            // subject == null (webhook / system run) skips the per-user dollar budget only; the tenant quota is
            // always checked. Head of the fallback chain, like every other single-call internal helper.
            return freeTierChatClientService.buildClientChainForFreeTenant(
                tenantId, plan, request.maxTokens(), request.temperature(), 1.0,
                subject, subject == null ? null : PerUserBudgetService.Modality.TEXT).get(0);
        }
        ProviderName provider;
        String apiKey;
        if (request.provider() != null && !request.provider().isBlank()) {
            provider = ProviderName.valueOf(request.provider().strip().toUpperCase(Locale.ROOT));
            apiKey = credentialResolver.resolveActiveKey(provider);
        } else {
            ProviderCredentialResolver.ActiveProvider active = credentialResolver.resolveAnyActiveKey();
            provider = active.providerName();
            apiKey = active.apiKey();
        }
        String model = request.model() != null && !request.model().isBlank()
            ? request.model().strip()
            : modelFor(provider);
        return llmGatewayService.resolveClient(tenantId, new GatewayCallSpec(
            provider, apiKey, model, request.maxTokens(), request.temperature(), 1.0));
    }

    /**
     * The provider's catalog default; the configured default model id is an Anthropic id, so it is only a last
     * resort for Anthropic itself - sending it to another provider would 404 (the bug WorkflowGraphGeneratorService
     * documents for the same lookup).
     */
    private String modelFor(ProviderName provider) {
        return providerModelCatalogService.defaultModelFor(provider).orElseGet(() -> {
            if (provider == ProviderName.ANTHROPIC) {
                return defaultModel;
            }
            throw new IllegalStateException("No model is available for provider " + provider
                + " - pick one in this node's model setting.");
        });
    }
}
