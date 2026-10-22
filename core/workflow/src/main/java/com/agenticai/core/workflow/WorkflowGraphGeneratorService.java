package com.agenticai.core.workflow;

import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.agents.core.ReasoningDepth;
import com.agenticai.core.agents.dto.AgentResponse;
import com.agenticai.core.agents.hosted.FreeTierChatClientService;
import com.agenticai.core.agents.turn.quality.AnswerConsistencyChecker;
import com.agenticai.core.agents.turn.quality.ReflexionService;
import com.agenticai.core.agents.turn.quality.TaskPlanningService;
import com.agenticai.core.approval.ApprovalPolicyService;
import com.agenticai.core.chat.ChatClientRegistry;
import com.agenticai.core.chat.gateway.GatewayCallSpec;
import com.agenticai.core.chat.gateway.LlmGatewayService;
import com.agenticai.core.chat.structured.StructuredOutput;
import com.agenticai.core.omnichannel.WhatsAppDispatchService;
import com.agenticai.core.providers.ProviderCredentialResolver;
import com.agenticai.core.providers.ProviderModelCatalogEmptyException;
import com.agenticai.core.providers.ProviderModelCatalogService;
import com.agenticai.core.providers.ProviderModelResponse;
import com.agenticai.core.providers.ProviderName;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.TenantLimits;
import com.agenticai.core.security.TenantPlan;
import com.agenticai.core.workflow.dto.GenerateGraphResponse;
import com.agenticai.core.workflow.dto.RefGraphResponse;
import com.agenticai.core.workflow.dto.RefHint;
import com.agenticai.core.workflow.dto.WorkflowGraphDto;
import com.agenticai.core.workflow.guidance.WorkflowSendApprovalGuard;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.MissingNode;

/**
 * AI workflow generator (#392 - direct feedback: "What does this workflow does, instead of that
 * customer should be able to type what he wants and automatically it should get [built] with the
 * help of AI using available tools, mcp, condition, output, Human in loop approval everything").
 * Turns one free-text instruction into a real {@link WorkflowGraphDto} - trigger, agent, knowledge,
 * condition, approval, and output nodes wired together - rather than just drafting descriptive text
 * the way {@code AssistService} (the plain "Ask AI" helper) does for every other field in the
 * console.
 *
 * <p>Reuses the exact same LLM plumbing as {@code AssistService} ({@link
 * ProviderCredentialResolver}/{@link ChatClientRegistry} - "whichever provider is active", not a
 * specific agent's own provider) and the exact same governance gate every other path into a run
 * already goes through: {@link OrchestratorService#validate} runs on the generated graph before it
 * is ever handed back to the canvas, and a structurally invalid result surfaces as the identical
 * {@link WorkflowValidationException} (400 + {@code errors}) the Validate Workflow button and
 * Save/Activate already produce - no separate, weaker check invented for AI-generated graphs.
 *
 * <p>Deliberately does not persist anything - the canvas applies the returned graph to its own
 * in-memory nodes/edges (same as loading a saved workflow), and the user still has to click Save
 * Draft/Activate themselves, consistent with this feature never silently overwriting or
 * auto-committing a user's workflow.
 */
@Service
public class WorkflowGraphGeneratorService {

    private static final Logger log = LoggerFactory.getLogger(WorkflowGraphGeneratorService.class);

    private final ProviderCredentialResolver credentialResolver;
    private final ChatClientRegistry chatClientRegistry;
    private final LlmGatewayService llmGatewayService;
    private final ProviderModelCatalogService providerModelCatalogService;
    private final AgentService agentService;
    private final OrchestratorService orchestratorService;
    private final ObjectMapper objectMapper;
    private final TenantLimits tenantLimits;
    private final FreeTierChatClientService freeTierChatClientService;
    private final AnswerConsistencyChecker answerConsistencyChecker;
    private final ReflexionService reflexionService;
    private final TaskPlanningService taskPlanningService;
    private final String defaultModel;
    private final int generatorMaxTokens;
    private final boolean enforceApprovalBeforeSend;
    private final ObjectProvider<WhatsAppDispatchService> whatsAppDispatch;
    private final ObjectProvider<ApprovalPolicyService> approvalPolicy;
    private WorkflowExpressionResolver expressions;

    public WorkflowGraphGeneratorService(
            ProviderCredentialResolver credentialResolver,
            ChatClientRegistry chatClientRegistry,
            LlmGatewayService llmGatewayService,
            ProviderModelCatalogService providerModelCatalogService,
            AgentService agentService,
            OrchestratorService orchestratorService,
            ObjectMapper objectMapper,
            TenantLimits tenantLimits,
            FreeTierChatClientService freeTierChatClientService,
            AnswerConsistencyChecker answerConsistencyChecker,
            ReflexionService reflexionService,
            TaskPlanningService taskPlanningService,
            @Value("${app.agents.default-model}")
                    String defaultModel, // Deliberately its OWN config key, not
            // app.agents.default-max-tokens
            // (1024) - that default is tuned for a conversational chat reply,
            // not a full JSON workflow graph. A real production bug: at 1024
            // tokens, anything beyond a trivial 1-2 node workflow got its JSON
            // output cut off mid-object ("Unexpected end-of-input in field
            // name"/"within/between Object entries" from Jackson - the exact
            // shape of a truncated completion, not malformed JSON), which
            // parseGraph then correctly rejected but with no way to tell the
            // difference from a genuinely broken response. 4096 gives real
            // headroom for a multi-node graph (each node carries a label + a
            // config object, plus edges and notes) while still being far below
            // any provider's actual output ceiling.
            @Value("${app.workflow.generator.max-tokens:4096}")
                    int generatorMaxTokens, // On (the safe value) = WorkflowSendApprovalGuard
            // runs on every generated graph, so a
            // node that messages someone is never handed back without an approval step in front of
            // it - enforced in code after generation, not left to the prompt. Off restores the
            // pre-guard behaviour (the model decides).
            @Value("${app.workflow.generator.enforce-approval-before-send:true}")
                    boolean enforceApprovalBeforeSend,
            ObjectProvider<WhatsAppDispatchService> whatsAppDispatch,
            ObjectProvider<ApprovalPolicyService> approvalPolicy) {
        this.credentialResolver = credentialResolver;
        this.chatClientRegistry = chatClientRegistry;
        this.llmGatewayService = llmGatewayService;
        this.providerModelCatalogService = providerModelCatalogService;
        this.agentService = agentService;
        this.orchestratorService = orchestratorService;
        this.objectMapper = objectMapper;
        this.tenantLimits = tenantLimits;
        this.freeTierChatClientService = freeTierChatClientService;
        this.answerConsistencyChecker = answerConsistencyChecker;
        this.reflexionService = reflexionService;
        this.taskPlanningService = taskPlanningService;
        this.defaultModel = defaultModel;
        this.generatorMaxTokens = generatorMaxTokens;
        this.enforceApprovalBeforeSend = enforceApprovalBeforeSend;
        this.whatsAppDispatch = whatsAppDispatch;
        this.approvalPolicy = approvalPolicy;
    }

    public GenerateGraphResponse generate(String instruction) {
        return generate(instruction, ReasoningDepth.FAST);
    }

    /**
     * Generates a workflow graph from a free-text instruction, applying additional deliberation
     * passes controlled by {@code depth}:
     *
     * <ul>
     *   <li>{@link ReasoningDepth#FAST} - one LLM call, no extra passes (same behaviour as the
     *       single-arg overload above).
     *   <li>{@link ReasoningDepth#PLANNED} - one cheap upfront planning call via {@link
     *       TaskPlanningService} prepends a step-list to the effective instruction before the
     *       graph-generation call so the model has a structured breakdown to follow.
     *   <li>{@link ReasoningDepth#CAREFUL} - after the initial graph is produced, {@link
     *       AnswerConsistencyChecker} checks the raw JSON for internal inconsistency relative to
     *       the instruction and optionally revises it once. Any parse failure in the revision keeps
     *       the last good graph (fail-open).
     *   <li>{@link ReasoningDepth#RIGOROUS} - CAREFUL's consistency pass plus a {@link
     *       ReflexionService} critique-and-revise pass that asks whether the graph actually
     *       satisfies the instruction. Again, any parse failure in the second revision keeps the
     *       last good graph.
     * </ul>
     *
     * <p>The final graph always goes through {@link OrchestratorService#validate} before being
     * returned, regardless of {@code depth}.
     */
    public GenerateGraphResponse generate(String instruction, ReasoningDepth depth) {
        return generate(instruction, depth, null);
    }

    /**
     * The canvas entry point. Same generation as the two-arg overload, plus:
     *
     * <ul>
     *   <li><b>edit mode</b> - a non-empty {@code currentGraph} is handed to the model with the
     *       instruction as a change request, so unrelated nodes and their ids survive;
     *   <li><b>approval before send</b> - {@link WorkflowSendApprovalGuard} runs on the parsed
     *       graph (flag {@code app.workflow.generator.enforce-approval-before-send}), BEFORE
     *       validation so the graph handed back is the one that was validated;
     *   <li>a schedule suggestion, a requirements checklist and a deterministic explanation.
     * </ul>
     *
     * @throws IllegalArgumentException (400) when {@code currentGraph} is too large to edit in one
     *     request
     */
    public GenerateGraphResponse generate(
            String instruction, ReasoningDepth depth, WorkflowGraphDto currentGraph) {
        List<AgentResponse> agents = agentService.listAll();

        String editSuffix = null;
        WorkflowEditRedaction.Redacted redacted = WorkflowEditRedaction.Redacted.NONE;
        if (WorkflowGeneratorSupport.isEditable(currentGraph)) {
            // Literal credentials a user typed into a header / body / tool args never leave the
            // platform: they are swapped for placeholders here and put back after the reply.
            redacted = WorkflowEditRedaction.redact(objectMapper, serializeBareGraph(currentGraph));
            String currentJson = redacted.json();
            if (currentJson.isEmpty()
                    || currentJson.length()
                            > WorkflowGeneratorSupport.maxEditGraphChars(generatorMaxTokens)) {
                throw new IllegalArgumentException(
                        "This workflow is too large for the AI to change in one request - edit it on the canvas, "
                                + "or start over to generate a fresh draft.");
            }
            editSuffix =
                    WorkflowGeneratorSupport.editSuffix(currentJson)
                            + (redacted.secrets().isEmpty()
                                    ? ""
                                    : WorkflowEditRedaction.PROMPT_NOTE);
        }

        String system =
                buildSystemPrompt(agents)
                        + WorkflowGeneratorSupport.directAddendum(enforceApprovalBeforeSend);
        GenerationRun run = run(instruction, depth, system, editSuffix);
        WorkflowGraphDto generated =
                WorkflowEditRedaction.restore(objectMapper, run.graph(), redacted);

        WorkflowSendApprovalGuard.Result guarded =
                enforceApprovalBeforeSend
                        ? WorkflowSendApprovalGuard.enforce(generated)
                        : new WorkflowSendApprovalGuard.Result(generated, List.of(), List.of());
        WorkflowGraphDto graph = guarded.graph();

        // Same governance gate every other path into a run already goes
        // through - a WorkflowValidationException here propagates straight
        // out to WorkflowController's caller and is handled by the exact
        // same @ExceptionHandler the Validate Workflow button's failures
        // already use (GlobalExceptionHandler#handleWorkflowValidation).
        orchestratorService.validate(graph);

        // A reasoning-pass revision re-emits only the graph, so the side fields (schedule, needed
        // secrets) are read from whichever reply still carries them: the final one, else the first.
        JsonNode last = readRoot(run.rawJson());
        JsonNode first = readRoot(run.firstRawJson());

        List<String> notes =
                new ArrayList<>(
                        WorkflowGeneratorSupport.strings(
                                pick(last, first, "notes"),
                                "notes",
                                WorkflowGeneratorSupport.MAX_NOTES));
        if (agents.isEmpty()) {
            notes.add(
                    "You don't have any agents yet, so this workflow skips the Agent step - create one on the Agents "
                            + "page, then add an Agent node to this graph and pick it there.");
        }
        notes.addAll(guarded.notes());

        var schedule = WorkflowGeneratorSupport.parseSchedule(pick(last, first, "schedule"), notes);
        var requirements =
                WorkflowGeneratorSupport.requirements(
                        graph,
                        WorkflowGeneratorSupport.strings(
                                pick(last, first, "secretsNeeded"),
                                "secretsNeeded",
                                WorkflowGeneratorSupport.MAX_LIST_ITEMS),
                        WorkflowGeneratorSupport.strings(
                                pick(last, first, "knowledgeNeeded"),
                                "knowledgeNeeded",
                                WorkflowGeneratorSupport.MAX_LIST_ITEMS),
                        whatsappConnected(graph),
                        approvalPauses(graph));

        return new GenerateGraphResponse(
                graph,
                List.copyOf(notes),
                editSuffix != null ? "edit" : "create",
                WorkflowGeneratorSupport.explanation(graph),
                schedule,
                requirements,
                WorkflowGeneratorSupport.insertedApprovals(guarded));
    }

    private JsonNode readRoot(String rawJson) {
        try {
            return StructuredOutput.readTree(objectMapper, rawJson).orElse(null);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static JsonNode pick(JsonNode last, JsonNode first, String field) {
        JsonNode value = last == null ? null : last.get(field);
        boolean usable = value != null && !value.isNull() && !(value.isArray() && value.isEmpty());
        return usable ? last : first;
    }

    /**
     * Null when unknown (omnichannel absent, lookup failed) or when the graph sends no WhatsApp at
     * all.
     */
    private Boolean whatsappConnected(WorkflowGraphDto graph) {
        boolean sendsWhatsApp =
                graph.nodes().stream()
                        .anyMatch(
                                n ->
                                        "output".equalsIgnoreCase(n.type())
                                                && "whatsapp"
                                                        .equalsIgnoreCase(
                                                                String.valueOf(
                                                                        n.config()
                                                                                .get("channel"))));
        WhatsAppDispatchService dispatch =
                sendsWhatsApp && whatsAppDispatch != null
                        ? whatsAppDispatch.getIfAvailable()
                        : null;
        if (dispatch == null) {
            return null;
        }
        try {
            return dispatch.isAnyProviderConnected();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * Null when unknown or when the graph has no Approval node; false = this workspace
     * auto-approves them.
     */
    private Boolean approvalPauses(WorkflowGraphDto graph) {
        boolean hasApproval =
                graph.nodes().stream().anyMatch(n -> "approval".equalsIgnoreCase(n.type()));
        ApprovalPolicyService policy =
                hasApproval && approvalPolicy != null ? approvalPolicy.getIfAvailable() : null;
        if (policy == null) {
            return null;
        }
        try {
            return policy.requiresApproval("workflow.node.approval");
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * Brief planner entry point (Earned Autonomy): the same generation, reasoning-depth passes and
     * fence-stripping parse as {@link #generate(String, ReasoningDepth)}, but the graph references
     * the caller's own short refs ({@code a1}, {@code k1}) instead of real ids - the Brief's
     * agents/knowledge do not exist yet, they are created at apply time. Consequently NO {@link
     * OrchestratorService#validate} runs here: the ref strings are not UUIDs, so the Brief
     * validator maps them to placeholder ids and runs that same gate itself. Additive: {@code
     * generate} and its callers are untouched.
     *
     * <p>The model may also list {@code secretsNeeded} (UPPER_SNAKE vault names), {@code mcpNeeded}
     * (free-text search phrases for the MCP registry) and {@code knowledgeNeeded} (what documents
     * the workflow would need) - returned verbatim for the planner to reconcile against its
     * skeleton; empty when the model omitted them.
     */
    public RefGraphResponse generateWithRefs(
            String instruction,
            ReasoningDepth depth,
            List<RefHint> agentRefs,
            List<RefHint> knowledgeRefs) {
        StringBuilder agentList = new StringBuilder();
        for (RefHint hint : agentRefs) {
            agentList
                    .append("- id=\"")
                    .append(hint.ref())
                    .append("\" name=\"")
                    .append(hint.name())
                    .append("\"");
            if (hint.description() != null && !hint.description().isBlank()) {
                agentList.append(" role=\"").append(hint.description()).append("\"");
            }
            agentList.append('\n');
        }
        StringBuilder knowledgeList = new StringBuilder();
        for (RefHint hint : knowledgeRefs) {
            knowledgeList
                    .append("- ref=\"")
                    .append(hint.ref())
                    .append("\" name=\"")
                    .append(hint.name())
                    .append("\"\n");
        }
        String system =
                WorkflowGeneratorPrompts.buildSystemPrompt(
                                agentList.length() == 0 ? "(none)" : agentList.toString(),
                                expressionsWired())
                        + WorkflowGeneratorPrompts.refModeAddendum(
                                knowledgeList.length() == 0 ? "(none)" : knowledgeList.toString());
        GenerationRun run = run(instruction, depth, system, null);
        // Deliberately NOT passed through WorkflowSendApprovalGuard: approval placement for a Brief
        // is owned by the trust ladder (TrustGraphMaterializer adds eaMaterialised approval nodes
        // at apply time and strips them again as trust is earned). A permanent, untagged
        // auto-approval node here would double-gate at UNDER_WATCH and could never be removed at
        // TRUSTED / AUTONOMOUS.
        return new RefGraphResponse(
                run.graph(),
                List.of(),
                stringArray(run.rawJson(), "secretsNeeded"),
                stringArray(run.rawJson(), "mcpNeeded"),
                stringArray(run.rawJson(), "knowledgeNeeded"));
    }

    private List<String> stringArray(String rawJson, String field) {
        try {
            var node =
                    StructuredOutput.readTree(objectMapper, rawJson)
                            .map(n -> n.path(field))
                            .orElse(MissingNode.getInstance());
            List<String> out = new ArrayList<>();
            if (node.isArray()) {
                node.forEach(
                        n -> {
                            if (n.isString() && !n.asString().isBlank()) {
                                out.add(n.asString().trim());
                            }
                        });
            }
            return out;
        } catch (Exception e) {
            return List.of();
        }
    }

    /**
     * The graph plus the raw JSON text it was parsed from (the last revision that parsed) and the
     * model's first reply, which is the only one guaranteed to carry the side fields.
     */
    private record GenerationRun(WorkflowGraphDto graph, String rawJson, String firstRawJson) {}

    /**
     * Wire shape of the model's reply as far as the graph goes; side fields are read off the tree.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record GeneratedEnvelope(WorkflowGraphDto graph, List<String> notes) {}

    /**
     * @param userSuffix appended to the user message of the generation call only (edit mode's
     *     current graph). When set, the CAREFUL / RIGOROUS revision passes are skipped: they judge
     *     a graph against the bare instruction, which for an edit is only a change request, so a
     *     "revision" could drop every node the request did not mention.
     */
    private GenerationRun run(
            String instruction, ReasoningDepth depth, String system, String userSuffix) {
        // Low temperature, same rationale as AssistService: this should
        // produce a workflow that matches the instruction, not a creative
        // reinterpretation of it. topP=1.0 for the same reason as
        // AssistService's own call - no nucleus narrowing for this internal
        // one-off helper. See buildChatClient's javadoc for the FREE-tier
        // hosted-pool branch (same gap/fix as AssistService had).
        ChatClient chatClient = buildChatClient();

        String effectiveInstruction = instruction;

        // PLANNED: one upfront planning call breaks the instruction into an
        // ordered step list; the plan is prepended to the effective
        // instruction so the graph-generation call can follow it. Null/blank
        // plan (model judged the request too simple, or planning failed)
        // means we proceed with the original instruction unchanged.
        if (depth == ReasoningDepth.PLANNED) {
            String plan = taskPlanningService.planIfNeeded(chatClient, instruction);
            if (plan != null && !plan.isBlank()) {
                effectiveInstruction =
                        "Follow this plan:\n" + plan + "\n\nOriginal instruction: " + instruction;
            }
        }

        boolean editing = userSuffix != null;
        String userMessage = editing ? effectiveInstruction + userSuffix : effectiveInstruction;
        String rawResponse = chatClient.prompt().system(system).user(userMessage).call().content();
        WorkflowGraphDto graph = parseGraph(rawResponse);
        String lastRaw = rawResponse;

        // CAREFUL / RIGOROUS: self-consistency check on the raw JSON graph.
        // The checker is passed the raw LLM response (which is already the
        // JSON answer) as its "original answer". If the revision output
        // contains "{" we try to parse it as a new graph; any parse failure
        // keeps the last good graph (fail-open, same discipline as every
        // other verifier in this codebase).
        if (!editing && (depth == ReasoningDepth.CAREFUL || depth == ReasoningDepth.RIGOROUS)) {
            String revised =
                    answerConsistencyChecker.checkAndRevise(chatClient, instruction, rawResponse);
            if (revised != null && revised.contains("{")) {
                try {
                    graph = parseGraph(revised);
                    lastRaw = revised;
                } catch (Exception e) {
                    log.debug(
                            "Consistency-checker revision not parseable as graph - keeping prior result");
                }
            }
        }

        // RIGOROUS: Reflexion critique on the current graph JSON. Serialise
        // the graph back to the same {"graph":..., "notes":[]} shape
        // parseGraph expects so a successful revision can be round-tripped.
        // If the revision differs and contains "{" we try to parse it; any
        // parse failure keeps the last good graph.
        if (!editing && depth == ReasoningDepth.RIGOROUS) {
            String graphJson = serializeGraphJson(graph);
            String revised =
                    reflexionService.critiqueAndMaybeRevise(chatClient, instruction, graphJson);
            if (revised != null && !revised.equals(graphJson) && revised.contains("{")) {
                try {
                    graph = parseGraph(revised);
                    lastRaw = revised;
                } catch (Exception e) {
                    log.debug("Reflexion revision not parseable as graph - keeping prior result");
                }
            }
        }

        return new GenerationRun(graph, lastRaw, rawResponse);
    }

    /**
     * Serialises {@code graph} back to the {@code {"graph":..., "notes":[]}} envelope that {@link
     * #parseGraph} expects, so the graph can be passed as a text answer to the
     * consistency/Reflexion checkers and - if they revise it - round-tripped back through {@link
     * #parseGraph}. Returns an empty string on any serialisation error; callers treat that as
     * "nothing to diff against" and skip the revision.
     */
    private String serializeGraphJson(WorkflowGraphDto graph) {
        try {
            return objectMapper.writeValueAsString(new GeneratedEnvelope(graph, List.of()));
        } catch (Exception e) {
            log.warn("Failed to serialise graph for reasoning pass - skipping revision", e);
            return "";
        }
    }

    /** {@code {"nodes":[...],"edges":[...]}} for the edit-mode user message; empty on any error. */
    private String serializeBareGraph(WorkflowGraphDto graph) {
        try {
            return objectMapper.writeValueAsString(graph);
        } catch (Exception e) {
            log.warn("Failed to serialise the current graph for an edit request", e);
            return "";
        }
    }

    /**
     * Routes through {@link FreeTierChatClientService} whenever the tenant is on the platform pool,
     * and through {@link #credentialResolver} only for a tenant that made an explicit BYOK choice -
     * the decision is {@link TenantLimits#usesPlatformPool}, exactly as {@code
     * AssistService#buildChatClient} already asks it. The platform pool is the DEFAULT for every
     * tenant (2026-09-29): checking {@code plan == FREE} here sent every non-FREE pool tenant -
     * including every tenant under free mode, whose effective plan reads UNLIMITED - to {@code
     * resolveAnyActiveKey()} and surfaced {@code NoActiveProviderException} ("No active LLM
     * provider - activate one on the Providers page first.") on Build-with-AI for the exact tenants
     * the pool exists to serve.
     *
     * <p>{@code defaultModel} is an Anthropic model id - {@link #credentialResolver}'s resolved
     * provider is not always Anthropic, so the raw string can't just be passed through (reproduced
     * as a 404 "model not found" when the active provider was Gemini). {@link #resolveModelFor}
     * looks up a real model for whichever provider actually resolved instead, same fix and same
     * rationale as {@code AssistService#buildChatClient}.
     */
    private ChatClient buildChatClient() {
        String tenantId = TenantContext.current().map(TenantContext::getTenantId).orElse(null);
        TenantPlan plan = tenantId != null ? tenantLimits.plan(tenantId) : null;
        if (tenantLimits.usesPlatformPool(tenantId, plan)) {
            return freeTierChatClientService.buildClientForFreeTenant(
                    tenantId, plan, generatorMaxTokens, 0.2, 1.0);
        }
        ProviderCredentialResolver.ActiveProvider active = credentialResolver.resolveAnyActiveKey();
        String model = resolveModelFor(active.providerName());
        return llmGatewayService.resolveClient(
                tenantId,
                new GatewayCallSpec(
                        active.providerName(),
                        active.apiKey(),
                        model,
                        generatorMaxTokens,
                        0.2,
                        1.0));
    }

    /**
     * Picks a real model id for {@code provider} from {@link ProviderModelCatalogService} (first,
     * alphabetically-ordered entry - any supported model works for this one-off graph-generation
     * call) instead of always sending the Anthropic-only {@link #defaultModel}. Falls back to
     * {@link #defaultModel} only if the catalog is unexpectedly empty for that provider.
     */
    private String resolveModelFor(ProviderName provider) {
        List<ProviderModelResponse> models = providerModelCatalogService.listModels(provider);
        if (!models.isEmpty()) {
            return models.get(0).modelId();
        }
        if (provider == ProviderName.ANTHROPIC) {
            return defaultModel;
        }
        throw new ProviderModelCatalogEmptyException(provider);
    }

    private WorkflowGraphDto parseGraph(String rawResponse) {
        try {
            // The model is instructed to return {"graph": {...}, "notes": [...]}
            // (plus optional side fields such as "schedule", which are read off
            // the JSON tree, not bound here). Fences / prose around the JSON are
            // tolerated by StructuredOutput.
            GeneratedEnvelope parsed =
                    StructuredOutput.parse(objectMapper, rawResponse, GeneratedEnvelope.class);
            if (parsed.graph() == null) {
                throw new WorkflowValidationException(
                        List.of(
                                "The AI generator didn't return a usable graph - try rephrasing your instruction."));
            }
            return parsed.graph();
        } catch (WorkflowValidationException e) {
            throw e;
        } catch (Exception e) {
            log.warn("AI workflow generator returned unparseable output: {}", e.getMessage());
            throw new WorkflowValidationException(
                    List.of(
                            "The AI generator's response couldn't be understood as a workflow - try rephrasing your instruction, "
                                    + "or build it manually with the palette on the left."));
        }
    }

    private String buildSystemPrompt(List<AgentResponse> agents) {
        StringBuilder agentList = new StringBuilder();
        if (agents.isEmpty()) {
            agentList.append(
                    "(none - this tenant has no agents yet, so do not add an Agent node unless the "
                            + "instruction is impossible to satisfy without one; if you must, leave agentId as an empty string "
                            + "and explain in notes that the user needs to create an agent and pick it manually.)");
        } else {
            for (AgentResponse agent : agents) {
                agentList
                        .append("- id=\"")
                        .append(agent.id())
                        .append("\" name=\"")
                        .append(agent.name())
                        .append("\"\n");
            }
        }
        return WorkflowGeneratorPrompts.buildSystemPrompt(agentList.toString(), expressionsWired());
    }

    /**
     * True only when the runtime resolver is wired into this deployment and its kill switch ({@code
     * app.workflow.expressions.enabled}) is on, so the prompt never teaches syntax the executor
     * would leave as literal text. Field-injected and optional so hand-built instances (tests)
     * simply get the legacy prompt.
     */
    private boolean expressionsWired() {
        return expressions != null && expressions.isEnabled();
    }

    @Autowired(required = false)
    void setExpressions(WorkflowExpressionResolver expressions) {
        this.expressions = expressions;
    }
}
