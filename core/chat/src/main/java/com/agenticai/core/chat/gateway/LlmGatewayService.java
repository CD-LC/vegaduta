package com.agenticai.core.chat.gateway;

import com.agenticai.core.chat.ChatClientRegistry;
import com.agenticai.core.providers.ProviderName;
import com.agenticai.core.tenancy.AiModelFeature;
import com.agenticai.core.tenancy.AiModelPreference;
import com.agenticai.core.tenancy.TenantAiModelPreferenceService;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.stereotype.Service;

/**
 * #848 (LLM gateway seam, in-house) - the one seam every outbound LLM call in this platform now
 * funnels through, per the companion research doc's recommendation ({@code
 * Agentic-AI-Platform-Gateway-MultiAgent-OCR-IVR-Research.md}, section 2: "build this in-house as a
 * thin internal layer, not adopt an external product... introduce an LlmGatewayService that wraps
 * every outbound call with (a) a pre-call guardrail/policy check, (b) the existing fallback logic
 * from #829 generalized to run through this one seam instead of scattered per-factory, (c)
 * token-bucket rate limiting per tenant"). Same architectural move already made for the
 * platform/tenant kill switch - {@code AgentChatService.chat()} as the universal chokepoint for
 * that; this is the equivalent chokepoint for the "build a ChatClient" side.
 *
 * <p><b>Deliberately still built on Spring AI underneath</b> ({@link ChatClientRegistry} and its
 * per-provider {@code AgentChatClientFactory} implementations), not a hand-rolled HTTP client per
 * provider. This platform's provider surface already spans five real wire protocols (Anthropic
 * Messages API, two different OpenAI-compatible reimplementations for OpenRouter/Grok, Google's
 * GenAI SDK for Gemini, Sarvam's own API) - reimplementing all five by hand blind (this environment
 * currently has no working build/compile loop to verify against) would trade a working, tested
 * integration layer for an unverified one, for a decoupling benefit this gateway already delivers a
 * different way: every caller in this codebase now depends on {@link LlmGatewayService}/{@link
 * GatewayCallSpec}, not on Spring AI's {@code ChatModel}/{@code ChatClient} builder types directly,
 * so swapping what's underneath THIS class later (a hand-rolled client, a different SDK, a
 * self-hosted LiteLLM/APISIX proxy) is a one-file change, not an N-call-site one. That is the
 * actual point of a "seam" - it decouples callers from the implementation, it doesn't require
 * deleting the implementation.
 *
 * <p>Composes, in order, on every {@link #resolveClient}: {@link
 * LlmGatewayBudgetService#assertWithinBudget} (cost/usage governance), {@link
 * LlmGatewayRateLimiterService#assertWithinRateLimit} (pre-call screening/rate limiting), then
 * delegates to {@link ChatClientRegistry} (routing/resolution) - the same three concerns the
 * research doc listed as "what a gateway is responsible for," minus post-call moderation (already
 * covered by {@code GuardrailService}/{@code ToolCallOutputValidator} at the tool-output layer) and
 * semantic pre-call prompt screening (an honest gap, not built here - {@code GuardrailService}
 * today only sanitizes TOOL output, not the outbound user prompt itself; flagged as a real
 * follow-up, not silently skipped).
 */
@Service
public class LlmGatewayService {

    private static final Logger log = LoggerFactory.getLogger(LlmGatewayService.class);

    private final ChatClientRegistry chatClientRegistry;
    private final LlmGatewayBudgetService budgetService;
    private final LlmGatewayRateLimiterService rateLimiterService;
    private final TenantAiModelPreferenceService aiModelPreferenceService;
    private final List<GatewayGovernanceAdvisor> governanceAdvisors;

    public LlmGatewayService(
            ChatClientRegistry chatClientRegistry,
            LlmGatewayBudgetService budgetService,
            LlmGatewayRateLimiterService rateLimiterService,
            TenantAiModelPreferenceService aiModelPreferenceService,
            // Every bean implementing the marker in the current context - an
            // empty list in slim contexts (tests, a module that doesn't carry a
            // governance impl) simply attaches nothing.
            List<GatewayGovernanceAdvisor> governanceAdvisors) {
        this.chatClientRegistry = chatClientRegistry;
        this.budgetService = budgetService;
        this.rateLimiterService = rateLimiterService;
        this.aiModelPreferenceService = aiModelPreferenceService;
        this.governanceAdvisors = governanceAdvisors;
    }

    /**
     * Optional client reuse seam (see {@link GatewayClientMemo}); null = build a client per call,
     * as always.
     */
    private volatile GatewayClientMemo clientMemo;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setClientMemo(GatewayClientMemo clientMemo) {
        this.clientMemo = clientMemo;
    }

    /**
     * Resolves a governed {@link ChatClient} for one call - the drop-in replacement for every
     * direct {@code chatClientRegistry.createClient(...)} call site this codebase had before #848
     * (see {@code AgentChatService}, {@code AssistService}, {@code
     * GenerationParamsInferenceService}, {@code WorkflowGraphGeneratorService}).
     *
     * @param tenantId the calling tenant, for budget/rate-limit scoping - null for an
     *     internal/system call with no tenant context (both governance checks skip cleanly rather
     *     than erroring on null; the AI Model Control override lookup below is skipped the same
     *     way).
     * @throws LlmGatewayLimitExceededException if this tenant is over its monthly spend cap or
     *     per-minute call rate - rejected before any network call to the provider is made.
     */
    public ChatClient resolveClient(String tenantId, GatewayCallSpec spec) {
        return resolveClient(tenantId, spec, null);
    }

    /**
     * As {@link #resolveClient(String, GatewayCallSpec)} for a caller that already resolved the
     * tenant's effective plan for this turn: both governance checks use {@code knownPlan} instead
     * of each reading it again (two plan lookups per client before). {@code null} reads the plan,
     * as before.
     */
    public ChatClient resolveClient(
            String tenantId,
            GatewayCallSpec spec,
            com.agenticai.core.security.TenantPlan knownPlan) {
        com.agenticai.core.security.ImpersonationContext.assertNotImpersonating("llm-call");
        if (knownPlan == null) {
            budgetService.assertWithinBudget(tenantId);
            rateLimiterService.assertWithinRateLimit(tenantId);
        } else {
            budgetService.assertWithinBudget(tenantId, knownPlan);
            rateLimiterService.assertWithinRateLimit(tenantId, knownPlan);
        }
        String model = resolveEffectiveModel(tenantId, spec);
        GatewayClientMemo memo = clientMemo;
        return memo == null
                ? buildClient(spec, model)
                : memo.get(tenantId, spec, model, () -> buildClient(spec, model));
    }

    private ChatClient buildClient(GatewayCallSpec spec, String model) {
        ChatClient client =
                chatClientRegistry.createClient(
                        spec.providerName(),
                        spec.apiKey(),
                        model,
                        spec.maxTokens(),
                        spec.temperature(),
                        spec.topP(),
                        spec.providerOptions());
        if (governanceAdvisors.isEmpty()) {
            return client;
        }
        // mutate().defaultAdvisors(...) APPENDS to the provider factory's own
        // defaults (same seam FreeTierChatClientService uses) - governance
        // rides every resolved client without the factory knowing it exists.
        return client.mutate().defaultAdvisors(governanceAdvisors.toArray(Advisor[]::new)).build();
    }

    /**
     * "AI Model Control" settings page wiring - the actual routing decision point a tenant's saved
     * model preference takes effect at. Looks up {@link TenantAiModelPreferenceService#resolve} for
     * {@code spec.featureKey()} (falling back to {@link AiModelFeature#DEFAULT} - see that enum's
     * javadoc for why that is every existing call site today) and, ONLY if a preference is stored
     * AND its {@code providerName} matches {@code spec.providerName()}, substitutes the
     * preference's {@code model} for {@code spec.model()}.
     *
     * <p>The provider-match requirement is deliberate, not a limitation to fix later: {@code
     * spec.apiKey()} was already resolved by the caller (e.g. {@code
     * AgentChatService#credentialResolver.resolveActiveKey}) for {@code spec.providerName()}
     * specifically - a stored preference naming a DIFFERENT provider would need a different tenant
     * credential this gateway has no way to fetch (that resolution lives in each caller's own
     * module, several of which are outside this change's scope), and silently keeping the caller's
     * original apiKey paired with a different provider's model would produce confusing upstream
     * auth failures rather than a working override. A same-provider model swap (e.g. "use
     * claude-opus instead of claude-sonnet for this feature") needs no new credential and always
     * works; a cross-provider override is simply not-yet-applicable and falls back to {@code
     * spec.model()} unchanged, logged at debug for visibility.
     */
    private String resolveEffectiveModel(String tenantId, GatewayCallSpec spec) {
        Optional<AiModelPreference> preference =
                aiModelPreferenceService.resolve(tenantId, spec.featureKey());
        if (preference.isEmpty()) {
            return spec.model();
        }
        AiModelPreference stored = preference.get();
        ProviderName storedProvider;
        try {
            storedProvider = ProviderName.valueOf(stored.providerName());
        } catch (IllegalArgumentException e) {
            log.warn(
                    "Tenant '{}' AI Model Control override for feature '{}' names unknown"
                            + " providerName '{}' - ignoring it",
                    tenantId,
                    spec.featureKey(),
                    stored.providerName());
            return spec.model();
        }
        if (storedProvider != spec.providerName()) {
            log.debug(
                    "Tenant '{}' AI Model Control override for feature '{}' targets provider {} but"
                        + " this call resolved provider {} (different BYOK credential required) -"
                        + " not applicable to this call, using {}",
                    tenantId,
                    spec.featureKey(),
                    storedProvider,
                    spec.providerName(),
                    spec.model());
            return spec.model();
        }
        log.debug(
                "Tenant '{}' AI Model Control override applied for feature '{}': model {} -> {}",
                tenantId,
                spec.featureKey(),
                spec.model(),
                stored.model());
        return stored.model();
    }
}
