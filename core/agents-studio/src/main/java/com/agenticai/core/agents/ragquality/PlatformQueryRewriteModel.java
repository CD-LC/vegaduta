package com.agenticai.core.agents.ragquality;

import com.agenticai.core.chat.HostedLlmChatClientFactory;
import com.agenticai.core.chat.LlmPricingCatalog;
import com.agenticai.core.knowledge.rag.QueryRewriteModel;
import com.agenticai.core.providers.PlatformHostedLlmProviderService;
import com.agenticai.core.providers.PlatformHostedLlmProviderService.HostedProviderSelection;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.TenantLimits;
import com.agenticai.core.tenancy.PlatformFeatureBudgetService;
import com.agenticai.core.tenancy.PlatformFeatureBudgetService.BudgetDecision;
import java.math.BigDecimal;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Backs {@code core/knowledge}'s query rewriting ({@code app.knowledge.query-rewrite.enabled}) with
 * the platform's existing cheap-model path - the same one {@code ErrorExplanationService} uses: the
 * top-priority row of the platform-hosted LLM pool, gated by the Smart AI governor ({@link
 * PlatformFeatureBudgetService}) BEFORE the call and metered back with {@code recordSpend} after
 * it. There is no provider path of its own here: no new key, no per-tenant BYOK use (a tenant is
 * never billed for a retrieval nicety), and an exhausted budget, a disabled feature switch or an
 * empty pool simply return empty - which {@code QueryRewriter} turns into "search with the original
 * query".
 *
 * <p>The feature key {@value #FEATURE_KEY} is governed like any Smart AI feature: admin-tunable
 * {@code platform.smart-ai.feature.rag-query-rewrite.enabled / .daily-usd} (an unset key falls back
 * to the governor's default per-feature daily cap), plus the master switch and master cap.
 *
 * <p>Data flow: the end user's raw search query (which can reveal confidential content) is sent to
 * the platform-hosted LLM pool, i.e. to a provider the TENANT did not choose. So, unless {@code
 * app.knowledge.query-rewrite.allow-byok-tenants} is on, rewriting runs only for tenants whose chat
 * already runs on the platform pool ({@code TenantLimits#usesPlatformPool}); a tenant that brings
 * its own provider key never has its queries sent to the platform pool, and simply searches with
 * the original query.
 *
 * <p>The bean only exists when query rewriting is switched on, so a deployment that never enables
 * it wires nothing. Every collaborator is an {@link ObjectProvider} so a profile that lacks one
 * degrades to "no rewrite" instead of failing boot.
 */
@Component
@ConditionalOnProperty(
        prefix = "app.knowledge.query-rewrite",
        name = "enabled",
        havingValue = "true")
public class PlatformQueryRewriteModel implements QueryRewriteModel {

    /** Smart AI governor feature key. */
    public static final String FEATURE_KEY = "rag-query-rewrite";

    /**
     * Charged when the provider reported no usable token usage. Deliberately above a real small
     * call's cost so a metering blind spot over-counts against the cap rather than under-counting
     * past it.
     */
    static final BigDecimal FLAT_SPEND_ESTIMATE_USD = new BigDecimal("0.001");

    private static final double TEMPERATURE = 0.2;
    private static final double TOP_P = 1.0;

    private static final Logger log = LoggerFactory.getLogger(PlatformQueryRewriteModel.class);

    private final ObjectProvider<PlatformFeatureBudgetService> featureBudget;
    private final ObjectProvider<PlatformHostedLlmProviderService> hostedProviders;
    private final ObjectProvider<HostedLlmChatClientFactory> chatClientFactory;
    private final ObjectProvider<LlmPricingCatalog> pricingCatalog;
    private final ObjectProvider<TenantLimits> tenantLimits;
    private final boolean allowByokTenants;

    @Autowired
    public PlatformQueryRewriteModel(
            ObjectProvider<PlatformFeatureBudgetService> featureBudget,
            ObjectProvider<PlatformHostedLlmProviderService> hostedProviders,
            ObjectProvider<HostedLlmChatClientFactory> chatClientFactory,
            ObjectProvider<LlmPricingCatalog> pricingCatalog,
            ObjectProvider<TenantLimits> tenantLimits,
            @Value("${app.knowledge.query-rewrite.allow-byok-tenants:false}")
                    boolean allowByokTenants) {
        this.featureBudget = featureBudget;
        this.hostedProviders = hostedProviders;
        this.chatClientFactory = chatClientFactory;
        this.pricingCatalog = pricingCatalog;
        this.tenantLimits = tenantLimits;
        this.allowByokTenants = allowByokTenants;
    }

    /** Monthly hosted spend cap (V477); a capped tenant just skips the optional rewrite. */
    private volatile com.agenticai.core.agents.spendcap.SpendCapService spendCapService;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setSpendCapService(
            com.agenticai.core.agents.spendcap.SpendCapService spendCapService) {
        this.spendCapService = spendCapService;
    }

    /**
     * True when this tenant's queries may be sent to the platform-hosted pool (fails closed when it
     * cannot tell).
     */
    private boolean tenantMayUsePlatformPool() {
        if (allowByokTenants) {
            return true;
        }
        TenantLimits limits = tenantLimits.getIfAvailable();
        String tenantId = TenantContext.current().map(TenantContext::getTenantId).orElse(null);
        return limits != null && tenantId != null && limits.usesPlatformPool(tenantId);
    }

    @Override
    public Optional<String> complete(String systemPrompt, String userPrompt, int maxOutputTokens) {
        if (!tenantMayUsePlatformPool()) {
            log.debug(
                    "Query rewrite skipped - the tenant brings its own provider key"
                            + " (app.knowledge.query-rewrite.allow-byok-tenants=false)");
            return Optional.empty();
        }
        if (com.agenticai.core.security.ImpersonationContext.isActive()) {
            return Optional.empty();
        }
        com.agenticai.core.agents.spendcap.SpendCapService cap = spendCapService;
        if (cap != null
                && cap.isBlocked(
                        TenantContext.current().map(TenantContext::getTenantId).orElse(null))) {
            log.debug("Query rewrite skipped - the tenant's monthly hosted spend cap is reached");
            return Optional.empty();
        }
        PlatformFeatureBudgetService budget = featureBudget.getIfAvailable();
        PlatformHostedLlmProviderService providers = hostedProviders.getIfAvailable();
        HostedLlmChatClientFactory factory = chatClientFactory.getIfAvailable();
        if (budget == null || providers == null || factory == null) {
            log.debug("Query rewrite model unavailable - a platform LLM collaborator is not wired");
            return Optional.empty();
        }
        BudgetDecision decision = budget.checkBudget(FEATURE_KEY);
        if (!decision.allowed()) {
            log.debug("Query rewrite skipped ({})", decision.reason());
            return Optional.empty();
        }
        HostedProviderSelection selection = providers.resolveTopPriorityEnabled().orElse(null);
        if (selection == null) {
            log.debug("Query rewrite skipped - no enabled platform-hosted LLM pool row");
            return Optional.empty();
        }

        String model = selection.provider().getModel();
        ChatClient client =
                factory.create(
                        selection.provider(),
                        selection.decryptedApiKey(),
                        maxOutputTokens,
                        TEMPERATURE,
                        TOP_P);
        ChatResponse response =
                client.prompt().system(systemPrompt).user(userPrompt).call().chatResponse();
        if (response == null) {
            return Optional.empty();
        }

        // The call happened, so it is metered even if the output turns out unusable.
        Long promptTokens = null;
        Long completionTokens = null;
        if (response.getMetadata() != null && response.getMetadata().getUsage() != null) {
            var usage = response.getMetadata().getUsage();
            promptTokens =
                    usage.getPromptTokens() == null ? null : usage.getPromptTokens().longValue();
            completionTokens =
                    usage.getCompletionTokens() == null
                            ? null
                            : usage.getCompletionTokens().longValue();
        }
        LlmPricingCatalog pricing = pricingCatalog.getIfAvailable();
        BigDecimal cost =
                pricing == null
                        ? null
                        : pricing.estimateCostUsd(model, promptTokens, completionTokens);
        budget.recordSpend(FEATURE_KEY, cost != null ? cost : FLAT_SPEND_ESTIMATE_USD);

        if (response.getResult() == null || response.getResult().getOutput() == null) {
            return Optional.empty();
        }
        String text = response.getResult().getOutput().getText();
        return text == null || text.isBlank() ? Optional.empty() : Optional.of(text);
    }
}
