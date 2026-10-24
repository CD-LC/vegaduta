package com.agenticai.core.agents.turn;

import static com.agenticai.core.agents.turn.ChatClientResolver.providerOptions;
import static com.agenticai.core.agents.turn.ChatPromptDirectives.delegationDirectiveBlock;
import static com.agenticai.core.agents.turn.ChatPromptDirectives.withSkillsBlock;

import com.agenticai.core.agents.core.Agent;
import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.agents.crew.HandoffChain;
import com.agenticai.core.agents.crew.HandoffStatus;
import com.agenticai.core.agents.crew.SpecialistCandidate;
import com.agenticai.core.agents.crew.SpecialistHandoffService;
import com.agenticai.core.agents.skills.SkillPromptBlock;
import com.agenticai.core.agents.tools.AgentStepEventEmitter;
import com.agenticai.core.agents.tools.AgentToolPort;
import com.agenticai.core.agents.turn.quality.LlmQualityJudgeService;
import com.agenticai.core.chat.LlmMetricsAdvisor;
import com.agenticai.core.chat.ProviderErrorTranslator;
import com.agenticai.core.chat.gateway.GatewayCallSpec;
import com.agenticai.core.chat.gateway.LlmGatewayService;
import com.agenticai.core.providers.ProviderCredentialResolver;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.sharing.PermissionLevel;
import com.agenticai.core.sharing.ResourceAccessService;
import com.agenticai.core.sharing.ResourceType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.ToolCallback;

/**
 * "Crew"-style chat-time multi-agent handoff (one hop of a {@link HandoffChain}), moved out of
 * {@link AgentChatService} so that file stays under its size baseline. Behaviour is main's {@code
 * delegateToSpecialist} verbatim; only its collaborators are now this class's own fields.
 */
final class ChatSpecialistHandoff {

    private static final Logger log = LoggerFactory.getLogger(AgentChatService.class);

    private final AgentService agentService;
    private final ResourceAccessService resourceAccessService;
    private final ProviderCredentialResolver credentialResolver;
    private final LlmGatewayService llmGatewayService;
    private final AgentToolPort agentToolProvider;
    private final LlmQualityJudgeService llmQualityJudgeService;
    private final ProviderErrorTranslator providerErrorTranslator;
    private final ChatTurnExecutor turns;
    private final Function<String, String> experimentVariants;
    private final ChatPromptDirectives.DirectiveGates directiveGates;
    private final SubAgentTurn subTurn;

    /**
     * Null = specialist handoff unavailable (slim test/module context); set by AgentChatService's
     * setter.
     */
    private volatile SpecialistHandoffService specialistHandoffService;

    ChatSpecialistHandoff(
            AgentService agentService,
            ResourceAccessService resourceAccessService,
            ProviderCredentialResolver credentialResolver,
            LlmGatewayService llmGatewayService,
            AgentToolPort agentToolProvider,
            LlmQualityJudgeService llmQualityJudgeService,
            ProviderErrorTranslator providerErrorTranslator,
            ChatTurnExecutor turns,
            Function<String, String> experimentVariants,
            ChatPromptDirectives.DirectiveGates directiveGates,
            SubAgentTurn subTurn) {
        this.subTurn = subTurn;
        this.agentService = agentService;
        this.resourceAccessService = resourceAccessService;
        this.credentialResolver = credentialResolver;
        this.llmGatewayService = llmGatewayService;
        this.agentToolProvider = agentToolProvider;
        this.llmQualityJudgeService = llmQualityJudgeService;
        this.providerErrorTranslator = providerErrorTranslator;
        this.turns = turns;
        this.experimentVariants = experimentVariants;
        this.directiveGates = directiveGates;
    }

    void setHandoffService(SpecialistHandoffService service) {
        this.specialistHandoffService = service;
    }

    /**
     * "Crew"-style chat-time multi-agent handoff - runs ONE hop of a {@link HandoffChain}, called
     * by {@code DelegateToSpecialistToolCallback}. Resolves its target dynamically by
     * role/capability tag via {@link SpecialistHandoffService#findSpecialist} instead of a
     * pre-wired {@code collaboratorAgentIds} entry, and - unlike {@link
     * ChatDelegateCall#delegateCall}, which is structurally capped at depth 1 - allows the chain to
     * continue through further specialists up to {@link HandoffChain#MAX_DEPTH} hops, guarded by
     * {@link HandoffChain#isAtMaxDepth()} and {@link HandoffChain#wouldCycle}.
     *
     * <p>Otherwise deliberately mirrors {@link ChatDelegateCall#delegateCall}'s shape as closely as
     * possible - same fresh-throwaway-session isolation (no history, no memory), same RUNNER access
     * check on the target agent, same BYOK-only posture, same provider-fallback wrapping, same
     * quality-judge sampling - because it is the same kind of operation (one bounded question-in,
     * answer-out exchange with another agent) with a different target-resolution and depth-cap
     * strategy.
     *
     * <p>Every dispatch decision (refused for depth, refused for a cycle, refused because no
     * specialist matched, or actually run) is recorded to the durable {@code agent_handoff_audit}
     * trail via {@link #specialistHandoffService}, in addition to the existing ephemeral step-event
     * {@code handoff} trace this method reuses unchanged for live-trace parity with {@link
     * ChatDelegateCall#delegateCall}.
     *
     * @param chain this turn's handoff state BEFORE this hop - i.e. the delegating agent's own
     *     position in the chain. This method computes {@code chain.next(targetAgentId)} itself once
     *     a target is resolved.
     * @return the specialist's answer, or a short, model-readable refusal message when the hop is
     *     blocked before dispatch (depth/cycle/no-match) - never throws for those cases, since a
     *     refusal is a normal tool result the calling model should be able to read and react to,
     *     not a turn-ending error.
     */
    String delegateToSpecialist(
            HandoffChain chain, String capabilityTag, String subTask, String contextSummary) {
        SpecialistHandoffService handoff = specialistHandoffService;
        if (handoff == null) {
            return "Specialist delegation is not available right now.";
        }
        if (chain.isAtMaxDepth()) {
            handoff.recordHandoffRefusal(
                    chain,
                    null,
                    capabilityTag,
                    subTask,
                    HandoffStatus.DEPTH_EXCEEDED,
                    "chain already at max depth " + HandoffChain.MAX_DEPTH);
            return "Maximum handoff depth ("
                    + HandoffChain.MAX_DEPTH
                    + ") already reached for this chain ("
                    + chain.describeVisited()
                    + ") - cannot delegate further.";
        }
        Optional<SpecialistCandidate> candidateOpt = handoff.findSpecialist(capabilityTag);
        if (candidateOpt.isEmpty()) {
            handoff.recordHandoffRefusal(
                    chain,
                    null,
                    capabilityTag,
                    subTask,
                    HandoffStatus.NO_SPECIALIST_FOUND,
                    "no agent_specialist row for this tag");
            return "No specialist is registered for capability '" + capabilityTag + "'.";
        }
        SpecialistCandidate candidate = candidateOpt.get();
        UUID targetAgentId = candidate.agentId();
        if (chain.wouldCycle(targetAgentId)) {
            handoff.recordHandoffRefusal(
                    chain,
                    targetAgentId,
                    capabilityTag,
                    subTask,
                    HandoffStatus.CYCLE_DETECTED,
                    "agent already visited in this chain (" + chain.describeVisited() + ")");
            return "Delegating to '"
                    + candidate.agentName()
                    + "' would hand this sub-task back to an agent already in"
                    + " this chain ("
                    + chain.describeVisited()
                    + ") - refusing to avoid a loop.";
        }

        HandoffChain nextChain = chain.next(targetAgentId);
        UUID auditId = handoff.recordHandoffStart(chain, targetAgentId, capabilityTag, subTask);
        String delegatedSessionId =
                "crew:" + chain.turnSessionId() + ":" + nextChain.depth() + ":" + UUID.randomUUID();

        Agent agent;
        try {
            agent = agentService.getEntity(targetAgentId);
        } catch (RuntimeException e) {
            handoff.recordHandoffOutcome(
                    auditId, HandoffStatus.FAILED, "specialist agent could not be resolved");
            return "{\"error\": \"specialist agent could not be resolved\"}";
        }
        // Same RUNNER check delegateCall applies to the collaborator it is about to call -
        // reuses whatever actor originated this in-flight turn, see that method's own long
        // comment on why no special-casing is needed for the various caller shapes.
        resourceAccessService.requireAccess(
                ResourceType.AGENT, targetAgentId, agent.getCreatedBy(), PermissionLevel.RUNNER);
        AgentStepEventEmitter.current()
                .ifPresent(emitter -> emitter.handoff(emitter.runAgentId(), targetAgentId));

        String tenantId = TenantContext.current().map(TenantContext::getTenantId).orElse(null);
        String apiKey = credentialResolver.resolveActiveKey(agent.getProviderName());
        ChatClient chatClient =
                llmGatewayService.resolveClient(
                        tenantId,
                        new GatewayCallSpec(
                                agent.getProviderName(),
                                apiKey,
                                agentService.resolveEffectiveModel(agent),
                                agent.getMaxTokens(),
                                agent.getTemperature(),
                                agent.getTopP(),
                                null,
                                providerOptions(agent.getProviderName(), agent)));
        // resolveToolsForSpecialistHandoff (not the normal resolveToolsFor) is what lets the
        // chain continue: it offers delegate_to_specialist again, bound to nextChain, only while
        // nextChain is still under MAX_DEPTH - see that method's javadoc.
        List<ToolCallback> tools =
                agentToolProvider.resolveToolsForSpecialistHandoff(
                        targetAgentId, delegatedSessionId, subTask, chatClient, nextChain);
        String variants = experimentVariants.apply(tenantId);

        // The delegate's own overrides (agent, tenant, platform) for every text below, not the built-in text.
        ResolvedDirectives promptsForDelegate = directiveGates.directivesForAgent().apply(targetAgentId);
        String delegatePrompt =
                withSkillsBlock(
                        withSkillsBlock(
                                agent.getSystemPrompt(),
                                agentToolProvider.proceduresPromptBlock(targetAgentId)),
                        SkillPromptBlock.withHeader(
                                agentToolProvider.skillsPromptBlock(targetAgentId, tools),
                                promptsForDelegate.text(SkillPromptBlock.HEADER_KEY),
                                false));
        String basePrompt =
                (contextSummary == null || contextSummary.isBlank())
                        ? delegatePrompt
                        : delegatePrompt
                                + "\n\n"
                                + promptsForDelegate.textOrShipped(
                                        "delegation.context_header.specialist",
                                        java.util.Map.of("context", contextSummary));
        // Same 2026-10-01 audit fix as ChatDelegateCall: the specialist resolves
        // its own tools (MCP included) but previously got none of the standing
        // directives. Appended last in the system text, matching the outer
        // path's ordering; the anyMcp probe is a cache hit on the discovery
        // resolveToolsForSpecialistHandoff just did.
        String directiveBlock =
                delegationDirectiveBlock(
                        directiveGates,
                        tools,
                        directiveGates.mcpUntrusted().getAsBoolean()
                                && agentToolProvider.anyMcpToolsOfferedFor(
                                        targetAgentId, delegatedSessionId, subTask, chatClient),
                        // The target's own overrides (agent, tenant, platform), not the built-in text.
                        promptsForDelegate);
        String systemPrompt =
                directiveBlock == null ? basePrompt : basePrompt + "\n\n" + directiveBlock;

        try {
            // Screening, hooks, journal, guardrails and the continuation policy run in the sub-turn, the same stages
            // a buffered turn has (see SubAgentTurn); it also keeps the provider fallback this call always had.
            String result =
                    subTurn.run(
                            new SubAgentTurn.Request(
                                    "specialist",
                                    agent,
                                    targetAgentId,
                                    delegatedSessionId,
                                    subTask,
                                    systemPrompt,
                                    chatClient,
                                    tools,
                                    variants,
                                    tenantId,
                                    promptsForDelegate.textOrShipped("delegation.empty_answer.specialist")));
            handoff.recordHandoffOutcome(auditId, HandoffStatus.SUCCESS, null);
            return result;
        } catch (RuntimeException e) {
            log.warn(
                    "Specialist handoff to agent {} (capability '{}') failed",
                    targetAgentId,
                    capabilityTag,
                    e);
            handoff.recordHandoffOutcome(auditId, HandoffStatus.FAILED, e.getMessage());
            // Already translated (or deliberately left alone) by the sub-turn.
            throw e;
        }
    }
}
