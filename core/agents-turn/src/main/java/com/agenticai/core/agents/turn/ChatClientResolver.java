package com.agenticai.core.agents.turn;

import com.agenticai.core.agents.core.Agent;
import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.agents.hosted.FreeTierChatClientService;
import com.agenticai.core.agents.turn.quality.QueryComplexityClassifier;
import com.agenticai.core.chat.gateway.GatewayCallSpec;
import com.agenticai.core.chat.gateway.LlmGatewayService;
import com.agenticai.core.providers.ProviderCredentialResolver;
import com.agenticai.core.providers.ProviderName;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.TenantLimits;
import com.agenticai.core.security.TenantPlan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;

/**
 * Resolves the {@code ChatClient} for one turn (hosted pool vs. BYOK, RL/cost/text-model routing),
 * extracted verbatim from {@link AgentChatService} (extract-only split).
 */
final class ChatClientResolver {

    private static final Logger log = LoggerFactory.getLogger(AgentChatService.class);

    private final AgentService agentService;
    private final ProviderCredentialResolver credentialResolver;
    private final LlmGatewayService llmGatewayService;
    private final FreeTierChatClientService freeTierChatClientService;
    private final TenantLimits tenantLimits;
    private final QueryComplexityClassifier queryComplexityClassifier;
    private volatile ChatHotpath hotpath =
            ChatHotpath.OFF; // set by AgentChatService#setChatHotpath; OFF = today's calls

    ChatClientResolver(
            AgentService agentService,
            ProviderCredentialResolver credentialResolver,
            LlmGatewayService llmGatewayService,
            FreeTierChatClientService freeTierChatClientService,
            TenantLimits tenantLimits,
            QueryComplexityClassifier queryComplexityClassifier) {
        this.agentService = agentService;
        this.credentialResolver = credentialResolver;
        this.llmGatewayService = llmGatewayService;
        this.freeTierChatClientService = freeTierChatClientService;
        this.tenantLimits = tenantLimits;
        this.queryComplexityClassifier = queryComplexityClassifier;
    }

    void useHotpath(ChatHotpath hotpath) {
        this.hotpath = hotpath;
    }

    /** L4: optional verifier-escalation router; null (default) leaves routing untouched. */
    private VerifierEscalationRouter escalationRouter;

    void useEscalationRouter(VerifierEscalationRouter escalationRouter) {
        this.escalationRouter = escalationRouter;
    }

    /**
     * "yes go ahead" (Subscription Plan &amp; GTM Strategy build) - the one branch point where a
     * {@code TenantPlan.FREE} tenant's chat call diverges from every paid tenant's: FREE routes
     * through {@link FreeTierChatClientService} (usage-quota check + platform-hosted pool pick, see
     * that class), completely bypassing {@link #credentialResolver}/{@link #chatClientRegistry} - a
     * FREE tenant never needs to have activated any BYOK provider at all, and {@code
     * agent.getProviderName()} is ignored for them (the Providers page isn't even reachable on Free
     * per the pricing plan). PREMIUM/UNLIMITED keep the original BYOK path, unchanged - UNLESS the
     * tenant has opted into {@link TenantLimits#usePlatformLlm} (issue report: "every non free
     * tenant should get an option to select use platform llm"), in which case a paid tenant also
     * routes through the same hosted pool, just with a higher plan-tiered usage cap than FREE - see
     * {@code FreeTierUsageLimiterService}'s plan-aware limits.
     *
     * <p>Only wired into {@link #chat} (the main conversational path) for now - {@link
     * #delegateCall} (agent-to-agent delegation) stays BYOK-only; FREE tenants are capped at 2
     * agents, so multi-agent delegation is a lower-priority gap, tracked separately rather than
     * silently ignored.
     */
    /**
     * Whether this tenant's turns run on the platform's own pooled/hosted models rather than the
     * tenant's own BYOK key - the same condition {@link #buildChatClientFor} branches on for its
     * free-tier client. RL MODEL_ROUTE routing only applies to BYOK turns (a platform-LLM tenant
     * doesn't choose its own model), so arm selection is skipped for these.
     *
     * <p>The rule itself no longer lives here. It was spelled out inline at nine sites across
     * agents/providers/workflow/app, which let a complimentary-grant tenant be hosted in one place
     * and BYOK in another; it is now {@link TenantLimits#usesPlatformPool(String, TenantPlan)}, and
     * this method is the thin local name for it.
     *
     * @param plan this tenant's already-resolved {@link TenantPlan} for the current turn (see
     *     {@code chat()}'s {@code currentTenantPlan}) - passed through rather than re-queried,
     *     since every caller in this class already has it on hand and a FREE tenant then answers
     *     with no database round trip.
     */
    boolean usesPlatformLlm(String tenantId, TenantPlan plan) {
        return hotpath.usesPlatformPool(tenantLimits, tenantId, plan);
    }

    /**
     * @param tenantId this turn's tenant (already resolved by the caller from {@link TenantContext}
     *     - not re-derived here) and {@code plan} its already-resolved {@link TenantPlan} (see
     *     {@code chat()}'s {@code currentTenantPlan}), passed in rather than re-queried via {@link
     *     TenantLimits#plan} on every call, since this method is only ever invoked once per turn,
     *     synchronously, from within {@code chat()}.
     */
    ChatClient buildChatClientFor(
            Agent agent,
            String userMessage,
            String rlModelOverride,
            String tenantId,
            TenantPlan plan,
            double effTemperature,
            double effTopP) {
        // Live bug (direct report): a non-FREE tenant whose AGENT was created
        // with providerName=PLATFORM_HOSTED (the per-agent "Use Platform LLM"
        // choice - see AgentService#create's isFreePlan branch, which persists
        // this same value for FREE tenants, but a PREMIUM/UNLIMITED tenant can
        // also explicitly choose it per-agent) hit "No active credential for
        // provider PLATFORM_HOSTED" - this branch only checked plan==FREE or
        // the TENANT-WIDE TenantLimits#usePlatformLlm toggle, missing the case
        // where usePlatformLlm is off but THIS agent still asked for
        // PLATFORM_HOSTED. PLATFORM_HOSTED is never a real BYOK provider (see
        // ProviderName's javadoc) - falling through to
        // credentialResolver.resolveActiveKey(PLATFORM_HOSTED) below was
        // always going to throw, since no such credential can ever exist.
        boolean hostedAgent =
                tenantId != null && agent.getProviderName() == ProviderName.PLATFORM_HOSTED;
        if (usesPlatformLlm(tenantId, plan)
                || (hostedAgent && tenantLimits.platformPoolAvailable())) {
            // Cost routing is a BYOK-only feature (see Agent#isCostRoutingEnabled's
            // javadoc) - a tenant routed through the platform's own pooled/
            // hosted models below doesn't own the cost of those calls, so
            // there is nothing to route between here.
            return freeTierChatClientService.buildClientForFreeTenant(
                    tenantId, plan, agent.getMaxTokens(), effTemperature, effTopP);
        }

        if (hostedAgent) {
            // The agent was created for the hosted pool (FREE-plan agents always are) but this
            // deployment has none (app.llm.default-source=byok, or auto without a configured pool):
            // run it on the tenant's first active provider with that provider's default model.
            // Throws NoActiveProviderException (400, says what to configure) when there is none.
            ProviderCredentialResolver.ActiveProvider any =
                    credentialResolver.resolveAnyActiveKey();
            return llmGatewayService.resolveClient(
                    tenantId,
                    new GatewayCallSpec(
                            any.providerName(),
                            any.apiKey(),
                            agentService.defaultModelFor(any.providerName()),
                            agent.getMaxTokens(),
                            effTemperature,
                            effTopP,
                            null,
                            providerOptions(any.providerName(), agent)),
                    plan);
        }

        // #1077 fast-follow (RouteLLM/NotDiamond-style routing) - swap in the
        // agent's configured "economy" provider/model for this turn only
        // when cost routing is on, an economy model is actually configured,
        // and QueryComplexityClassifier judges this turn simple enough to
        // trust to it. Every other case (routing off, no economy model set,
        // or a turn judged non-simple) falls through unchanged to the
        // agent's normal provider/model - identical to today's behavior
        // before this feature existed.
        ProviderName effectiveProvider = agent.getProviderName();
        // #1086 - agent.getModel() can be null for a small number of agents
        // persisted before #1088's create()-time fix; resolveEffectiveModel
        // self-heals that row (and every other reader) instead of handing
        // Spring AI's chat-options builder a null model, which is what
        // produced the opaque "value cannot be null" 400 - see that
        // method's javadoc.
        String effectiveModel = agentService.resolveEffectiveModel(agent);
        boolean routedToEconomy = false;

        if (rlModelOverride != null && !rlModelOverride.isBlank()) {
            // RL MODEL_ROUTE bandit picked this model for the turn - an explicit
            // learned-policy choice that takes precedence over the heuristic
            // cost-routing swap below (both are "use a different model for this
            // one turn"; only one can win, and a deliberately-enabled policy
            // beats the automatic classifier). Same provider as the agent - a
            // MODEL_ROUTE arm names a model, not a provider.
            effectiveModel = rlModelOverride;
        } else if (agent.getTextModelProvider() != null) {
            // Text model provider selection (Master Roadmap 2026 item #4) - explicit
            // per-agent text provider/model for chat completions. Takes precedence
            // over cost routing since it's an agent-level choice, not a heuristic.
            // If the selected provider's credential has been deactivated, gracefully
            // fall back to the default routing (log warning but complete the turn).
            try {
                com.agenticai.core.providers.ProviderName textProvider =
                        mapTextProviderToProviderName(agent.getTextModelProvider());
                hotpath.resolveActiveKey(credentialResolver, tenantId, textProvider);
                effectiveProvider = textProvider;
                effectiveModel = agent.getTextModel();
                log.debug(
                        "Text model routing: agent {} turn routed to {} provider (model {})",
                        agent.getId(),
                        effectiveProvider,
                        effectiveModel);
            } catch (Exception e) {
                // Provider credential lost or text provider unavailable - log and
                // fall through to default routing (cost routing or primary provider).
                // This is graceful degradation: the turn still completes, but via
                // the fallback chain instead of the no-longer-available provider.
                log.warn(
                        "Text model provider for agent {} unavailable ({}): falling back to default"
                                + " routing",
                        agent.getId(),
                        e.getMessage());
            }
        } else if (agent.isCostRoutingEnabled()
                && agent.getEconomyProviderName() != null
                && queryComplexityClassifier.isSimple(
                        userMessage, agent.getCostRoutingJevThreshold())) {
            effectiveProvider = agent.getEconomyProviderName();
            effectiveModel = agent.getEconomyModel();
            routedToEconomy = true;
            log.debug(
                    "Cost routing: agent {} turn routed to economy provider {} (model {})",
                    agent.getId(),
                    effectiveProvider,
                    effectiveModel);
        }

        String apiKey = hotpath.resolveActiveKey(credentialResolver, tenantId, effectiveProvider);
        ChatClient client =
                llmGatewayService.resolveClient(
                        tenantId,
                        new GatewayCallSpec(
                                effectiveProvider,
                                apiKey,
                                effectiveModel,
                                agent.getMaxTokens(),
                                effTemperature,
                                effTopP,
                                null,
                                providerOptions(effectiveProvider, agent)),
                        plan);
        // L4: remember how to reach the primary model so a buffered turn can escalate if the
        // verifier rejects this economy answer. Inactive router -> nothing registered.
        if (routedToEconomy && escalationRouter != null && escalationRouter.isActive()) {
            final String primaryModel = agentService.resolveEffectiveModel(agent);
            escalationRouter.registerCheap(
                    agent.getId(),
                    client,
                    () -> {
                        ProviderName primary = agent.getProviderName();
                        String primaryKey =
                                hotpath.resolveActiveKey(credentialResolver, tenantId, primary);
                        return llmGatewayService.resolveClient(
                                tenantId,
                                new GatewayCallSpec(
                                        primary,
                                        primaryKey,
                                        primaryModel,
                                        agent.getMaxTokens(),
                                        effTemperature,
                                        effTopP,
                                        null,
                                        providerOptions(primary, agent)),
                                plan);
                    });
        }
        return client;
    }

    /** Economy client for the agent (see below) resolved from the tenant context; never throws. */
    ChatClient economyClientFor(java.util.UUID agentId) {
        try {
            Agent agent = agentService.getEntity(agentId);
            String tenantId = TenantContext.current().map(TenantContext::getTenantId).orElse(null);
            TenantPlan plan = tenantId != null ? hotpath.plan(tenantLimits, tenantId) : null;
            return buildEconomyClientOrNull(agent, tenantId, plan);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * The agent's configured economy-model client for AUXILIARY calls (e.g. the swarm goal
     * verifier), or {@code null} when there is none to use: cost routing off, no economy model
     * configured, or a hosted-pool tenant (cost routing is BYOK-only, see {@link
     * #buildChatClientFor}). Never throws - callers fall back to the turn's own client on null.
     * Deterministic sampling, small output cap.
     */
    ChatClient buildEconomyClientOrNull(Agent agent, String tenantId, TenantPlan plan) {
        try {
            if (agent == null
                    || !agent.isCostRoutingEnabled()
                    || agent.getEconomyProviderName() == null
                    || agent.getEconomyModel() == null
                    || agent.getEconomyModel().isBlank()) {
                return null;
            }
            if (usesPlatformLlm(tenantId, plan)
                    || (tenantId != null && agent.getProviderName() == ProviderName.PLATFORM_HOSTED)
                    || agent.getEconomyProviderName() == ProviderName.PLATFORM_HOSTED) {
                return null;
            }
            ProviderName provider = agent.getEconomyProviderName();
            String apiKey = hotpath.resolveActiveKey(credentialResolver, tenantId, provider);
            return llmGatewayService.resolveClient(
                    tenantId,
                    new GatewayCallSpec(
                            provider,
                            apiKey,
                            agent.getEconomyModel(),
                            Math.min(Math.max(agent.getMaxTokens(), 256), 1024),
                            0.0,
                            1.0,
                            null,
                            java.util.Map.of()),
                    plan);
        } catch (Exception e) {
            log.warn(
                    "Economy client unavailable for auxiliary call (agent {}): {}",
                    agent == null ? null : agent.getId(),
                    e.getMessage());
            return null;
        }
    }

    /**
     * Per-call provider options: the xAI server-tool opt-in plus the agent's optional extra
     * sampling controls (migration V331). Empty when the agent sets none of them, so such agents
     * keep an identical spec (and stay eligible for the gateway client memo).
     */
    static java.util.Map<String, Object> providerOptions(
            com.agenticai.core.providers.ProviderName provider, Agent agent) {
        java.util.Map<String, Object> options =
                new java.util.HashMap<>(xaiServerToolOptions(provider, agent));
        com.agenticai.core.chat.SamplingOptions sampling = samplingOptions(agent);
        if (!sampling.isEmpty()) {
            options.put(com.agenticai.core.chat.SamplingOptions.OPTION_KEY, sampling);
        }
        return options;
    }

    static com.agenticai.core.chat.SamplingOptions samplingOptions(Agent agent) {
        return new com.agenticai.core.chat.SamplingOptions(
                agent.getTopK(),
                agent.getStopSequences(),
                agent.getFrequencyPenalty(),
                agent.getPresencePenalty(),
                agent.getSeed());
    }

    /**
     * xAI Agent Tools API (2026-09-23, migration V239) - turns the per-agent {@code
     * xaiServerToolsEnabled} opt-in into the {@code providerOptions} the GROK chat factory
     * understands ({@link com.agenticai.core.chat.XaiServerToolSupport#OPTION_TOOLS}). Empty for
     * every other provider and for GROK agents that haven't opted in, so the default {@code
     * AgentChatClientFactory#create} overload - which ignores the map - covers everything else
     * unchanged.
     */
    static java.util.Map<String, Object> xaiServerToolOptions(
            com.agenticai.core.providers.ProviderName provider, Agent agent) {
        if (provider == com.agenticai.core.providers.ProviderName.GROK
                && agent.isXaiServerToolsEnabled()) {
            return java.util.Map.of(
                    com.agenticai.core.chat.XaiServerToolSupport.OPTION_TOOLS,
                    com.agenticai.core.chat.XaiServerToolSupport.DEFAULT_SERVER_TOOLS);
        }
        return java.util.Map.of();
    }

    /**
     * Maps a {@link com.agenticai.core.providers.modality.TextModelProvider} enum to its
     * corresponding {@link ProviderName} for chat routing.
     *
     * @param textProvider the text model provider enum
     * @return the corresponding ProviderName
     * @throws IllegalArgumentException if the provider is not mapped
     */
    private static ProviderName mapTextProviderToProviderName(
            com.agenticai.core.providers.modality.TextModelProvider textProvider) {
        if (textProvider == null) {
            throw new IllegalArgumentException("Text model provider cannot be null");
        }

        return switch (textProvider) {
            case ANTHROPIC -> ProviderName.ANTHROPIC;
            case OPENAI -> ProviderName.OPENAI;
            case GEMINI -> ProviderName.GEMINI;
            case GROK -> ProviderName.GROK;
            case SARVAM -> ProviderName.SARVAM;
            case MISTRAL -> ProviderName.MISTRAL;
            case DEEPSEEK -> ProviderName.DEEPSEEK;
            case GROQ -> ProviderName.GROQ;
            case META -> ProviderName.META;
            case OLLAMA -> ProviderName.OLLAMA;
            case LOCAL_OPENAI -> ProviderName.LOCAL_OPENAI;
            case OPENROUTER -> ProviderName.OPENROUTER;
        };
    }
}
