package com.agenticai.core.workflow;

import com.agenticai.core.agents.hosted.FreeTierChatClientService;
import com.agenticai.core.chat.gateway.GatewayCallSpec;
import com.agenticai.core.chat.gateway.LlmGatewayService;
import com.agenticai.core.chat.structured.StructuredOutput;
import com.agenticai.core.providers.ProviderCredentialResolver;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.TenantLimits;
import com.agenticai.core.security.TenantPlan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Infers sensible {@code maxStepsPerRun} and {@code stepTimeoutSeconds} limits for a workflow from
 * its name and description via a single, cheap LLM call - same "one tiny completion, no session, no
 * tools, no persisted state" shape as {@code GenerationParamsInferenceService} (which does the same
 * for an agent's temperature/topP/maxTokens).
 *
 * <p>Never throws to the caller. Any provider error, empty response, or unparseable JSON falls back
 * to {@link #FALLBACK_ON_FAILURE} ({@code maxStepsPerRun=50, stepTimeoutSeconds=30}), so callers
 * can always rely on getting usable limits even when the inference call itself fails.
 *
 * <p>Clamping is applied after parse: {@code maxStepsPerRun} is constrained to [5, 200] and {@code
 * stepTimeoutSeconds} to [10, 300] so a model that returns a wildly extreme value can never produce
 * unusable limits (same spirit as {@code GenerationParamsInferenceService}'s own token-count
 * clamping).
 */
@Service
public class WorkflowExecutionParamsInferenceService {

    private static final Logger log =
            LoggerFactory.getLogger(WorkflowExecutionParamsInferenceService.class);

    private static final int MIN_MAX_STEPS = 5;
    private static final int MAX_MAX_STEPS = 200;
    private static final int MIN_STEP_TIMEOUT = 10;
    private static final int MAX_STEP_TIMEOUT = 300;

    // Small token budget - the entire response is a two-field JSON object.
    private static final int INFERENCE_MAX_TOKENS = 256;

    private static final InferredLimits FALLBACK_ON_FAILURE = new InferredLimits(50, 30);

    private static final String SYSTEM_PROMPT =
            """
        You configure execution limits for an AI workflow based on its name and
        description. Reply with ONLY a JSON object (no markdown fences, no
        explanation) with exactly these two fields:
        {"maxStepsPerRun": <integer>, "stepTimeoutSeconds": <integer>}

        Guidance:
        - Linear flows (fetch → transform → output): 5-15 steps, 10-30 s each.
        - Branching flows (conditions, approval gates, routing): 15-40 steps, 20-45 s each.
        - Loop or parallel flows (fan-out, per-item processing, multi-agent):
          40-100 steps, 30-60 s each.
        - Complex orchestration (nested loops, multi-branch + parallel):
          up to 200 steps, up to 120 s each.
        Stay within [5, 200] for maxStepsPerRun and [10, 300] for stepTimeoutSeconds.
        """;

    private final ProviderCredentialResolver credentialResolver;
    private final LlmGatewayService llmGatewayService;
    private final TenantLimits tenantLimits;
    private final FreeTierChatClientService freeTierChatClientService;
    private final ObjectMapper objectMapper;
    private final String defaultModel;

    public WorkflowExecutionParamsInferenceService(
            ProviderCredentialResolver credentialResolver,
            LlmGatewayService llmGatewayService,
            TenantLimits tenantLimits,
            FreeTierChatClientService freeTierChatClientService,
            ObjectMapper objectMapper,
            @Value("${app.agents.default-model}") String defaultModel) {
        this.credentialResolver = credentialResolver;
        this.llmGatewayService = llmGatewayService;
        this.tenantLimits = tenantLimits;
        this.freeTierChatClientService = freeTierChatClientService;
        this.objectMapper = objectMapper;
        this.defaultModel = defaultModel;
    }

    /**
     * Infers {@link InferredLimits} for the described workflow. Never throws - returns {@link
     * #FALLBACK_ON_FAILURE} on any error.
     *
     * @param workflowName display name of the workflow (may be blank)
     * @param workflowDescription plain-language description (may be blank)
     */
    public InferredLimits infer(String workflowName, String workflowDescription) {
        try {
            ChatClient chatClient = buildChatClient();
            String user =
                    "Workflow name: "
                            + (workflowName == null ? "" : workflowName)
                            + "\nWorkflow description: "
                            + (workflowDescription == null ? "" : workflowDescription);
            String raw = chatClient.prompt().system(SYSTEM_PROMPT).user(user).call().content();
            return parse(raw);
        } catch (Exception e) {
            log.warn(
                    "Workflow execution-params inference failed - falling back to conservative defaults",
                    e);
            return FALLBACK_ON_FAILURE;
        }
    }

    private InferredLimits parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return FALLBACK_ON_FAILURE;
        }
        // Markdown fences / prose around the JSON despite the prompt - tolerated
        // by the shared StructuredOutput locator, same as every other "ask an
        // LLM for JSON" call site in this codebase.
        JsonNode node = StructuredOutput.readTree(objectMapper, raw).orElse(null);
        if (node == null) {
            log.warn(
                    "Could not parse workflow execution-params inference response as JSON: {}",
                    raw);
            return FALLBACK_ON_FAILURE;
        }
        int maxStepsPerRun =
                clamp(node.path("maxStepsPerRun").asInt(50), MIN_MAX_STEPS, MAX_MAX_STEPS);
        int stepTimeoutSeconds =
                clamp(
                        node.path("stepTimeoutSeconds").asInt(30),
                        MIN_STEP_TIMEOUT,
                        MAX_STEP_TIMEOUT);
        return new InferredLimits(maxStepsPerRun, stepTimeoutSeconds);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    /**
     * FREE-plan tenants, tenants that have opted into the platform LLM, and tenants inside a live
     * complimentary plan grant are routed through {@link FreeTierChatClientService} - the decision
     * itself is {@code TenantLimits#usesPlatformPool}, not restated here (same reasoning as {@code
     * GenerationParamsInferenceService#buildChatClient} - a FREE tenant has no BYOK provider to
     * resolve). Paid BYOK tenants use whichever provider is currently active via {@link
     * ProviderCredentialResolver#resolveAnyActiveKey} - the same resolver {@code
     * WorkflowGraphGeneratorService#buildChatClient} already uses for the same "platform utility
     * call, not a specific agent's own call" rationale.
     */
    private ChatClient buildChatClient() {
        String tenantId = TenantContext.current().map(TenantContext::getTenantId).orElse(null);
        TenantPlan plan = tenantId != null ? tenantLimits.plan(tenantId) : null;
        if (tenantLimits.usesPlatformPool(tenantId, plan)) {
            return freeTierChatClientService.buildClientForFreeTenant(
                    tenantId, plan, INFERENCE_MAX_TOKENS, 0.2, 1.0);
        }
        ProviderCredentialResolver.ActiveProvider active = credentialResolver.resolveAnyActiveKey();
        return llmGatewayService.resolveClient(
                tenantId,
                new GatewayCallSpec(
                        active.providerName(),
                        active.apiKey(),
                        defaultModel,
                        INFERENCE_MAX_TOKENS,
                        0.2,
                        1.0));
    }

    /**
     * Inferred execution limits for a workflow run.
     *
     * @param maxStepsPerRun maximum number of node executions allowed per run before the engine
     *     aborts with a limit error; clamped to [5, 200]
     * @param stepTimeoutSeconds maximum wall-clock seconds any single node execution may take
     *     before it is timed out; clamped to [10, 300]
     */
    public record InferredLimits(int maxStepsPerRun, int stepTimeoutSeconds) {}
}
