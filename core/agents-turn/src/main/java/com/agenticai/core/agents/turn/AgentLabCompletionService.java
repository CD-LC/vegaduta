package com.agenticai.core.agents.turn;

import com.agenticai.core.agents.budget.PerUserBudgetService;
import com.agenticai.core.agents.core.Agent;
import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.agents.core.TenantUnavailableException;
import com.agenticai.core.agents.hosted.FreeTierChatClientService;
import com.agenticai.core.agents.hosted.FreeTierProviderPoolEmptyException;
import com.agenticai.core.agents.hosted.HostedChainLink;
import com.agenticai.core.chat.LlmMetricsAdvisor;
import com.agenticai.core.chat.ProviderErrorTranslator;
import com.agenticai.core.chat.SamplingOptions;
import com.agenticai.core.chat.UpstreamProviderException;
import com.agenticai.core.chat.gateway.GatewayCallSpec;
import com.agenticai.core.chat.gateway.LlmGatewayContentPolicyViolationException;
import com.agenticai.core.chat.gateway.LlmGatewayLimitExceededException;
import com.agenticai.core.chat.gateway.LlmGatewayService;
import com.agenticai.core.providers.ProviderCredentialResolver;
import com.agenticai.core.providers.ProviderName;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.TenantLimitExceededException;
import com.agenticai.core.security.TenantLimits;
import com.agenticai.core.security.TenantPlan;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Service;

/**
 * The Agent Lab's one model call: a single tool-less completion on an agent's
 * own model with a caller-supplied system prompt and message list, which
 * never reads or writes session memory.
 *
 * <p>No other path offers this. Every chat entry point loads the prompt from
 * the stored agent and persists the turn ({@code AgentChatService#chat},
 * {@code chatTextOnly}, {@code chatWithParams}), and {@code AssistService}
 * answers on "whichever provider is active", not on the agent's model. Prompt
 * Knockout needs the same question answered under edited prompts and the
 * Consistency check needs k unpersisted samples, so both run here.
 *
 * <p><b>What this deliberately is not.</b> It is not a chat turn: no tools,
 * no memory or knowledge retrieval, no skills block, no inbound moderation or
 * per-agent content guardrails (those live only in {@code
 * AgentChatService#chat}), no response cache, no RL model route, no cost
 * routing and no BYOK fallback provider. Client choice mirrors chat's in two
 * respects only - hosted pool versus BYOK, and the agent's explicit
 * text-model provider - so a lab answer comes from the model a real turn
 * would normally use. The lab UI states this scope.
 *
 * <p><b>What still applies.</b> The client is built once per {@link
 * #complete} call, never once per run, because the spend gates fire at build
 * time: the BYOK budget and rate limit inside {@code
 * LlmGatewayService#resolveClient}, and the hosted pool's token quota and
 * per-user dollar budget inside {@code
 * FreeTierChatClientService#buildChainForFreeTenant}. Gateway governance
 * advisors ride on both kinds of client, and every call is written to {@code
 * llm_call_log} against the agent under the caller's attribution session id.
 *
 * <p>Lives in this package, not in {@code lab}, because it needs {@link
 * AgentService#getEntity}, {@link ChatClientResolver#samplingOptions} and
 * {@link HostedFailoverPolicy}, all package-private.
 */
@Service
public class AgentLabCompletionService {

    private static final Logger log = LoggerFactory.getLogger(AgentLabCompletionService.class);

    /** One earlier message of the conversation; {@code role} is {@code user} or {@code assistant}. */
    public record LabTurnMessage(String role, String content) {}

    /**
     * @param systemPrompt null = the agent's stored prompt; blank = no system message at all
     * @param history oldest first, may be empty or null
     * @param temperature null = the agent's own
     * @param maxTokens null = the agent's own
     * @param subjectKey the human requester, metered against the hosted pool's per-user budget
     * @param attributionSessionId written to {@code llm_call_log}, e.g. {@code lab:<runId>:<n>}
     */
    public record LabCompletionRequest(
        UUID agentId,
        String systemPrompt,
        List<LabTurnMessage> history,
        String userMessage,
        Double temperature,
        Integer maxTokens,
        String subjectKey,
        String attributionSessionId
    ) {}

    private final AgentService agentService;
    private final TenantLimits tenantLimits;
    private final FreeTierChatClientService freeTierChatClientService;
    private final ProviderCredentialResolver credentialResolver;
    private final LlmGatewayService llmGatewayService;
    private final ProviderErrorTranslator providerErrorTranslator;

    public AgentLabCompletionService(
        AgentService agentService,
        TenantLimits tenantLimits,
        FreeTierChatClientService freeTierChatClientService,
        ProviderCredentialResolver credentialResolver,
        LlmGatewayService llmGatewayService,
        ProviderErrorTranslator providerErrorTranslator
    ) {
        this.agentService = agentService;
        this.tenantLimits = tenantLimits;
        this.freeTierChatClientService = freeTierChatClientService;
        this.credentialResolver = credentialResolver;
        this.llmGatewayService = llmGatewayService;
        this.providerErrorTranslator = providerErrorTranslator;
    }

    /**
     * Whether {@code error} says the tenant (or this user) has run out of
     * something - plan, quota, budget, rate limit, the hosted pool itself, or
     * the right to run at all. These are never translated into a provider
     * error and never swallowed: each has its own status in {@code
     * GlobalExceptionHandler}, and a lab run that hits one stops instead of
     * burning the rest of its calls against the same wall.
     */
    public static boolean isLimitFailure(Throwable error) {
        // PerUserBudgetExhaustedException is a TenantLimitExceededException.
        return error instanceof TenantLimitExceededException
            || error instanceof LlmGatewayLimitExceededException
            || error instanceof FreeTierProviderPoolEmptyException
            || error instanceof TenantUnavailableException;
    }

    /**
     * Runs one completion. Requires {@link TenantContext} on the calling
     * thread (the lab worker restores it from the request's snapshot).
     *
     * @throws IllegalStateException when no tenant is bound to the thread
     * @throws UpstreamProviderException when the provider call itself failed
     */
    public String complete(LabCompletionRequest request) {
        Agent agent = agentService.getEntity(request.agentId());
        String tenantId = TenantContext.current().map(TenantContext::getTenantId)
            .orElseThrow(() -> new IllegalStateException("A lab completion needs a tenant context on the calling thread."));
        TenantPlan plan = tenantLimits.plan(tenantId);

        String systemPrompt = request.systemPrompt() == null ? agent.getSystemPrompt() : request.systemPrompt();
        List<LabTurnMessage> history = request.history() == null ? List.of() : request.history();
        int maxTokens = request.maxTokens() == null ? agent.getMaxTokens() : request.maxTokens();
        double temperature = request.temperature() == null ? agent.getTemperature() : request.temperature();
        SamplingOptions sampling = ChatClientResolver.samplingOptions(agent);

        // Same condition as ChatClientResolver#buildChatClientFor: the tenant
        // runs on the platform pool, or this agent asked for it by name.
        if (tenantLimits.usesPlatformPool(tenantId, plan) || agent.getProviderName() == ProviderName.PLATFORM_HOSTED) {
            return completeOnHostedPool(
                agent, tenantId, plan, systemPrompt, history, request, maxTokens, temperature, sampling);
        }
        return completeOnByok(agent, tenantId, systemPrompt, history, request, maxTokens, temperature, sampling);
    }

    /**
     * Walks the hosted pool's fallback chain in order and returns the first
     * answer. The blocking twin of {@link HostedChainFailover#attemptHostedChain},
     * with the same two rules: a limit or a content-policy verdict is never
     * retried on another provider, and advancing is gated on {@link
     * HostedFailoverPolicy#isRetryable}. It leaves out provider-health
     * recording and the invalid-model self-heal, which belong to the chat
     * path's own traffic.
     *
     * <p>The chain's reservations are made once, at build time, and a failed
     * call does not hand them back (the tracking advisors reconcile only on
     * success or cancel), so every exit that is not an answer releases them
     * here. The guards every link shares make that a single release.
     */
    private String completeOnHostedPool(
        Agent agent, String tenantId, TenantPlan plan, String systemPrompt, List<LabTurnMessage> history,
        LabCompletionRequest request, int maxTokens, double temperature, SamplingOptions sampling
    ) {
        String subjectKey = request.subjectKey();
        List<HostedChainLink> chain = freeTierChatClientService.buildChainForFreeTenant(
            tenantId, plan, maxTokens, temperature, agent.getTopP(), subjectKey,
            PerUserBudgetService.Modality.TEXT, sampling);
        HostedChainLink first = chain.get(0);
        for (int i = 0; i < chain.size(); i++) {
            HostedChainLink link = chain.get(i);
            try {
                return invoke(
                    link.client(), systemPrompt, history, request.userMessage(), request.agentId(),
                    request.attributionSessionId());
            } catch (RuntimeException e) {
                if (isLimitFailure(e) || e instanceof LlmGatewayContentPolicyViolationException) {
                    releaseReservations(tenantId, plan, subjectKey, first);
                    throw e;
                }
                UpstreamProviderException translated = providerErrorTranslator.translate(e);
                boolean last = i == chain.size() - 1;
                if (last || !HostedFailoverPolicy.isRetryable(translated)) {
                    releaseReservations(tenantId, plan, subjectKey, first);
                    throw translated;
                }
                log.warn(
                    "Lab completion: hosted pool provider '{}' (model {}, #{} of {}) failed for agent {} ({})"
                        + " - trying the next provider in the chain",
                    link.label(), link.model(), i + 1, chain.size(), request.agentId(), translated.getMessage());
            }
        }
        // Unreachable: buildChainForFreeTenant never returns an empty chain
        // (it throws FreeTierProviderPoolEmptyException), and the last link
        // either returns or throws above.
        throw new IllegalStateException("The hosted pool chain ended without an answer or an error.");
    }

    private void releaseReservations(String tenantId, TenantPlan plan, String subjectKey, HostedChainLink first) {
        freeTierChatClientService.releaseReservations(
            tenantId, plan, subjectKey, PerUserBudgetService.Modality.TEXT,
            first.reservedTokens(), first.reservedMicroCents(), first.guards());
    }

    /**
     * BYOK: the agent's provider and model, or its explicit text-model pair
     * when one is set and its credential is still active - the one routing
     * rule of {@link ChatClientResolver#buildChatClientFor} a lab answer has
     * to share with a real turn to be about the same model.
     */
    private String completeOnByok(
        Agent agent, String tenantId, String systemPrompt, List<LabTurnMessage> history,
        LabCompletionRequest request, int maxTokens, double temperature, SamplingOptions sampling
    ) {
        ProviderName provider = agent.getProviderName();
        String model = agentService.resolveEffectiveModel(agent);
        String apiKey = null;
        if (agent.getTextModelProvider() != null) {
            try {
                // ChatClientResolver's own mapper is private. All of its cases
                // are name-identical, which AgentLabCompletionServiceTest
                // asserts for every TextModelProvider value.
                ProviderName textProvider = ProviderName.valueOf(agent.getTextModelProvider().name());
                apiKey = credentialResolver.resolveActiveKey(textProvider);
                provider = textProvider;
                model = agent.getTextModel();
            } catch (RuntimeException e) {
                // Same graceful degradation as chat: the text provider's
                // credential is gone, so the call runs on the primary pair.
                log.warn("Text model provider for agent {} unavailable ({}): lab completion uses the agent's primary model",
                    agent.getId(), e.getMessage());
                apiKey = null;
            }
        }
        if (apiKey == null) {
            apiKey = credentialResolver.resolveActiveKey(provider);
        }
        // Chat's spec without the xAI server-tool options: this call stays tool-less.
        Map<String, Object> providerOptions =
            sampling.isEmpty() ? Map.of() : Map.of(SamplingOptions.OPTION_KEY, sampling);
        ChatClient client = llmGatewayService.resolveClient(tenantId, new GatewayCallSpec(
            provider, apiKey, model, maxTokens, temperature, agent.getTopP(), null, providerOptions));
        try {
            return invoke(
                client, systemPrompt, history, request.userMessage(), request.agentId(),
                request.attributionSessionId());
        } catch (RuntimeException e) {
            if (isLimitFailure(e) || e instanceof LlmGatewayContentPolicyViolationException) {
                throw e;
            }
            throw providerErrorTranslator.translate(e, provider);
        }
    }

    /**
     * The call itself. Spring AI assembles the prompt as system message, then
     * {@code messages(...)}, then the user text, and renders neither text as
     * a template when no params are given ({@code
     * DefaultChatClientUtils#toChatClientRequest}, 1.1.7) - so history lands
     * between the prompt and the new question, and braces in either pass
     * through untouched. Package-private so tests can replace it.
     */
    String invoke(
        ChatClient client, String systemPrompt, List<LabTurnMessage> history, String userMessage,
        UUID agentId, String sessionId
    ) {
        ChatClient.ChatClientRequestSpec spec = client.prompt();
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            spec = spec.system(systemPrompt);
        }
        if (history != null && !history.isEmpty()) {
            List<Message> messages = new ArrayList<>(history.size());
            for (LabTurnMessage turn : history) {
                messages.add("assistant".equalsIgnoreCase(turn.role())
                    ? new AssistantMessage(turn.content())
                    : new UserMessage(turn.content()));
            }
            spec = spec.messages(messages);
        }
        String content = spec.user(userMessage)
            .advisors(LlmMetricsAdvisor.attributeTo(agentId, sessionId))
            .call()
            .content();
        return content == null ? "" : content;
    }
}
