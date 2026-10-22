package com.agenticai.core.agents.core;

import com.agenticai.core.agents.hosted.FreeTierChatClientService;
import com.agenticai.core.chat.structured.StructuredOutput;
import com.agenticai.core.providers.ProviderCredentialResolver;
import com.agenticai.core.providers.ProviderName;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.TenantLimits;
import com.agenticai.core.security.TenantPlan;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Backs Simple-mode generation tuning (see {@link TuningMode}'s javadoc): one small, non-streaming
 * LLM call that reads an agent's system prompt and proposes temperature/topP/maxTokens for it, so a
 * customer never has to understand what those three sliders mean. Same "one tiny completion, no
 * session, no tools, no persisted agent" shape as {@code
 * com.agenticai.core.agents.assist.AssistService}, and deliberately reuses that class's exact
 * FREE-tier-aware client-building branch (a FREE tenant has no BYOK provider to resolve - see that
 * class's javadoc) rather than the agent's own configured provider, since this is an internal
 * platform utility call, not a message the agent itself sends or receives.
 */
@Service
public class GenerationParamsInferenceService {

    private static final Logger log =
            LoggerFactory.getLogger(GenerationParamsInferenceService.class);

    // Conservative floor/ceiling so a malformed or wildly-extreme model
    // response never produces an unusable agent - same spirit as
    // AgentGenerationParamsUpdateRequest's own 0.0-1.0 validation on
    // temperature/topP, applied here in code since this path skips that
    // DTO's Bean Validation (nothing here is a customer-submitted request
    // body).
    private static final int MIN_MAX_TOKENS = 128;
    private static final int MAX_MAX_TOKENS = 8192;
    private static final InferredParams FALLBACK_ON_FAILURE = new InferredParams(1024, 0.7, 1.0);

    private final ProviderCredentialResolver credentialResolver;
    private final com.agenticai.core.chat.ChatClientRegistry chatClientRegistry;
    private final com.agenticai.core.chat.gateway.LlmGatewayService llmGatewayService;
    private final TenantLimits tenantLimits;
    private final FreeTierChatClientService freeTierChatClientService;
    private final ObjectMapper objectMapper;
    private final int defaultMaxTokens;

    public GenerationParamsInferenceService(
            ProviderCredentialResolver credentialResolver,
            com.agenticai.core.chat.ChatClientRegistry chatClientRegistry,
            com.agenticai.core.chat.gateway.LlmGatewayService llmGatewayService,
            TenantLimits tenantLimits,
            FreeTierChatClientService freeTierChatClientService,
            ObjectMapper objectMapper,
            @Value("${app.agents.default-max-tokens:1024}") int defaultMaxTokens) {
        this.credentialResolver = credentialResolver;
        this.chatClientRegistry = chatClientRegistry;
        this.llmGatewayService = llmGatewayService;
        this.tenantLimits = tenantLimits;
        this.freeTierChatClientService = freeTierChatClientService;
        this.objectMapper = objectMapper;
        this.defaultMaxTokens = defaultMaxTokens;
    }

    /**
     * Infers temperature/topP/maxTokens from a system prompt's apparent purpose (creative vs
     * deterministic, short-answer vs long-form, etc). Never throws - a provider error or an
     * unparseable response falls back to a conservative fixed default, so switching an agent into
     * Simple mode always leaves it in a usable state even when inference itself fails.
     *
     * <p>Bug fix (direct feedback: "claude-sonnet-4-5 is not a valid model ID" 400 thrown from here
     * even though the tenant never chose that model) - {@code buildChatClient} used to send a
     * single hardcoded {@code app.agents.default-model} string to WHICHEVER provider {@link
     * ProviderCredentialResolver#resolveAnyActiveKey} happened to pick (tie- broken by
     * earliest-activated, per that method's own javadoc) - "claude-sonnet-4-5" is a raw
     * Anthropic-style slug that most other providers/gateways (OpenRouter, Grok, Gemini, a
     * self-hosted OpenAI- compatible endpoint...) simply reject with exactly this error, because a
     * global default model string can never be valid across every provider a tenant might have
     * BYOK-activated. Fixed by requiring the caller to pass the SAME provider/model the agent
     * itself already runs on (already validated for compatibility at agent create/update time - see
     * {@code AgentModelNotSupportedException}) instead of guessing.
     */
    public InferredParams infer(
            String systemPrompt, ProviderName agentProviderName, String agentModel) {
        try {
            ChatClient chatClient = buildChatClient(agentProviderName, agentModel);
            String system =
                    """
                You configure generation parameters for an AI agent based on
                its system prompt. Reply with ONLY a JSON object (no markdown
                fences, no explanation) with exactly these three fields:
                {"temperature": <0.0-1.0>, "topP": <0.0-1.0>, "maxTokens": <integer>}

                Guidance:
                - temperature: lower (0.0-0.3) for factual/precise/deterministic
                  tasks (support, data extraction, code, math); higher
                  (0.6-1.0) for creative/conversational/brainstorming tasks.
                - topP: 1.0 is fine for almost every case; only narrow it
                  (0.8-0.95) alongside a higher temperature for creative tasks
                  that still need some coherence.
                - maxTokens: short for quick factual replies (256-512), medium
                  for typical conversation (1024), high for long-form drafting,
                  summarization, or report-writing tasks (2048-4096).
                """;
            String user = "Agent system prompt:\n" + systemPrompt;
            String raw = chatClient.prompt().system(system).user(user).call().content();
            return parse(raw);
        } catch (Exception e) {
            log.warn(
                    "Generation-params inference failed - falling back to conservative defaults",
                    e);
            return FALLBACK_ON_FAILURE;
        }
    }

    private InferredParams parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return FALLBACK_ON_FAILURE;
        }
        // Markdown fences / prose around the JSON despite the prompt - tolerated
        // by the shared StructuredOutput locator, same as every other "ask an
        // LLM for JSON" call site in this codebase.
        JsonNode node = StructuredOutput.readTree(objectMapper, raw).orElse(null);
        if (node == null) {
            log.warn("Could not parse generation-params inference response as JSON: {}", raw);
            return FALLBACK_ON_FAILURE;
        }
        double temperature = clamp(node.path("temperature").asDouble(0.7), 0.0, 1.0);
        double topP = clamp(node.path("topP").asDouble(1.0), 0.0, 1.0);
        int maxTokens =
                (int)
                        clamp(
                                node.path("maxTokens").asInt(defaultMaxTokens),
                                MIN_MAX_TOKENS,
                                MAX_MAX_TOKENS);
        return new InferredParams(maxTokens, temperature, topP);
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    /**
     * Mirrors {@code AgentChatService#buildChatClientFor}'s exact routing split by asking the same
     * {@code TenantLimits#usesPlatformPool} it does (FREE tenant, a paid tenant opted into {@code
     * TenantLimits#usePlatformLlm}, or a live complimentary grant -> the hosted pool, which
     * resolves its own valid model internally and never needs one passed in; everyone else -> BYOK
     * using THIS agent's own already-validated provider/model) so this internal utility call can
     * never hand a provider a model ID that provider doesn't recognize - see {@link #infer}'s
     * javadoc for the bug this replaced.
     */
    private ChatClient buildChatClient(ProviderName agentProviderName, String agentModel) {
        String tenantId = TenantContext.current().map(TenantContext::getTenantId).orElse(null);
        TenantPlan plan = tenantId != null ? tenantLimits.plan(tenantId) : null;
        if (tenantLimits.usesPlatformPool(tenantId, plan)) {
            return freeTierChatClientService.buildClientForFreeTenant(
                    tenantId, plan, defaultMaxTokens, 0.3, 1.0);
        }
        String apiKey = credentialResolver.resolveActiveKey(agentProviderName);
        // Tool-free utility call with a fixed reply shape: ask OpenAI/Gemini/Vertex to enforce it
        // natively (other providers ignore the option and rely on the prompt + tolerant parse).
        return llmGatewayService.resolveClient(
                tenantId,
                new com.agenticai.core.chat.gateway.GatewayCallSpec(
                        agentProviderName,
                        apiKey,
                        agentModel,
                        defaultMaxTokens,
                        0.3,
                        1.0,
                        null,
                        com.agenticai.core.chat.structured.StructuredSchemaSpec.options(
                                "generation_params", PARAMS_SCHEMA)));
    }

    private static final String PARAMS_SCHEMA =
            "{\"type\":\"object\",\"properties\":{"
                    + "\"temperature\":{\"type\":\"number\"},\"topP\":{\"type\":\"number\"},\"maxTokens\":{\"type\":\"integer\"}},"
                    + "\"required\":[\"temperature\",\"topP\",\"maxTokens\"],\"additionalProperties\":false}";

    public record InferredParams(int maxTokens, double temperature, double topP) {}
}
