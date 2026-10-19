package com.agenticai.core.workflow;

import com.agenticai.core.agents.hosted.FreeTierChatClientService;
import com.agenticai.core.chat.gateway.GatewayCallSpec;
import com.agenticai.core.chat.gateway.LlmGatewayService;
import com.agenticai.core.providers.ProviderCredentialResolver;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.TenantLimits;
import com.agenticai.core.security.TenantPlan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

/** Cost routing: the economy-model call an Agent node makes for a clearly simple query. */
@Component
class EconomyModelCaller {

    private static final Logger log = LoggerFactory.getLogger(EconomyModelCaller.class);

    private final FreeTierChatClientService freeTierChatClientService;
    private final LlmGatewayService llmGatewayService;
    private final ProviderCredentialResolver credentialResolver;
    private final TenantLimits tenantLimits;

    EconomyModelCaller(
            FreeTierChatClientService freeTierChatClientService,
            LlmGatewayService llmGatewayService,
            ProviderCredentialResolver credentialResolver,
            TenantLimits tenantLimits) {
        this.freeTierChatClientService = freeTierChatClientService;
        this.llmGatewayService = llmGatewayService;
        this.credentialResolver = credentialResolver;
        this.tenantLimits = tenantLimits;
    }

    /**
     * Calls the economy model configured on this workflow's {@link WorkflowIntelligenceSettings}
     * for a simple query that {@link QueryComplexityClassifier#isSimple} has already classified as
     * safe to downgrade. FREE-plan tenants route through the platform-hosted pool (same as every
     * other FREE turn); BYOK tenants resolve the tenant's active key for the configured economy
     * provider and call through the gateway seam.
     *
     * <p>Any exception is caught and logged - the caller {@link AgentNodeHandler} would then
     * return an empty reply for this step rather than failing the whole run, consistent with the
     * engine's existing fail-open posture for optional/enrichment paths.
     */
    String callEconomyModel(String input, WorkflowIntelligenceSettings settings) {
        String tenantId = TenantContext.current().map(TenantContext::getTenantId).orElse(null);
        try {
            TenantPlan plan = tenantId != null ? tenantLimits.plan(tenantId) : TenantPlan.FREE;
            ChatClient chatClient;
            if (tenantLimits.usesPlatformPool(tenantId, plan)) {
                chatClient =
                        freeTierChatClientService.buildClientForFreeTenant(
                                tenantId, plan, 1024, 0.5, 1.0);
            } else {
                String apiKey = credentialResolver.resolveActiveKey(settings.economyProviderName());
                chatClient =
                        llmGatewayService.resolveClient(
                                tenantId,
                                new GatewayCallSpec(
                                        settings.economyProviderName(),
                                        apiKey,
                                        settings.economyModel(),
                                        1024,
                                        0.5,
                                        1.0));
            }
            return chatClient.prompt().user(input).call().content();
        } catch (RuntimeException e) {
            log.warn(
                    "Economy model call failed for workflow step (provider={}, model={}) - returning empty: {}",
                    settings.economyProviderName(),
                    settings.economyModel(),
                    e.getMessage());
            return "";
        }
    }
}
