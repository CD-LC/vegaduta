package com.agenticai.core.agents.turn;

import static com.agenticai.core.agents.turn.ChatClientResolver.providerOptions;
import static com.agenticai.core.agents.turn.ChatPromptDirectives.delegationDirectiveBlock;
import static com.agenticai.core.agents.turn.ChatPromptDirectives.withSkillsBlock;

import com.agenticai.core.agents.core.Agent;
import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.agents.tools.AgentStepEventEmitter;
import com.agenticai.core.agents.tools.AgentToolPort;
import com.agenticai.core.agents.turn.quality.LlmQualityJudgeService;
import com.agenticai.core.chat.LlmMetricsAdvisor;
import com.agenticai.core.chat.ProviderErrorTranslator;
import com.agenticai.core.chat.gateway.GatewayCallSpec;
import com.agenticai.core.chat.gateway.LlmGatewayService;
import com.agenticai.core.guardrails.GuardrailViolationException;
import com.agenticai.core.guardrails.ToolStepLimitExceededException;
import com.agenticai.core.providers.ProviderCredentialResolver;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.sharing.PermissionLevel;
import com.agenticai.core.sharing.ResourceAccessService;
import com.agenticai.core.sharing.ResourceType;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.ToolCallback;

/**
 * Agent-to-agent delegated call (one throwaway question-in / answer-out turn), extracted verbatim
 * from {@link AgentChatService} (extract-only split).
 */
final class ChatDelegateCall {

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

    ChatDelegateCall(
            AgentService agentService,
            ResourceAccessService resourceAccessService,
            ProviderCredentialResolver credentialResolver,
            LlmGatewayService llmGatewayService,
            AgentToolPort agentToolProvider,
            LlmQualityJudgeService llmQualityJudgeService,
            ProviderErrorTranslator providerErrorTranslator,
            ChatTurnExecutor turns,
            Function<String, String> experimentVariants,
            ChatPromptDirectives.DirectiveGates directiveGates) {
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

    /**
     * Runs ONE turn for a
     * collaborator agent that another agent just delegated a question to, called by {@code
     * DelegateToAgentToolCallback}. Deliberately NOT a call to {@link #chat}: a delegated call is a
     * single question-in, answer-out exchange, not a saved conversation - it uses a fresh,
     * throwaway session id, never touches {@code sessionMemoryService} at all (nothing is
     * persisted, nothing is replayed into it later), and never recalls the delegating agent's own
     * long-term memories or history. Synchronous ({@code .call()}, not {@code .stream()}) since the
     * caller is a {@code ToolCallback} - it has to return a plain {@code String}, the same
     * constraint every other tool in this codebase has.
     *
     * <p>Tools for the collaborator are resolved via {@code
     * AgentToolProvider#resolveToolsForDelegatedCall}, NOT the normal {@code resolveToolsFor} -
     * that's the entire depth-1 delegation cap: a collaborator invoked this way is never itself
     * offered a delegation tool, so agent A delegating to B delegating back to A (or to anyone
     * else) is structurally impossible, not just discouraged. See that method's javadoc.
     *
     * <p>Deliberately stays BYOK-only, unlike {@link #chat}'s {@link #buildChatClientFor} branch -
     * a FREE-plan tenant is capped at 2 agents (see the Subscription Plan &amp; GTM Strategy doc),
     * so a delegation-worthy multi-agent setup essentially doesn't exist on Free today. Revisit
     * this if/when a FREE-tier multi-agent workflow becomes a real use case.
     *
     * <p>Gap 3 (Reliability/Governance/SDLC build plan) - {@code contextSummary} is an OPTIONAL,
     * model-authored 1-2 sentence note the delegating agent can fill in when the sub-task depends
     * on something established earlier in its own conversation. It is never the raw transcript -
     * {@code DelegateToAgentToolCallback}'s tool description explicitly asks the model for a short
     * summary, not history - so this stays a deliberately small amount of forwarded context, not a
     * backdoor around the "collaborator sees only the exact question sent" boundary. When present,
     * it's prepended to the specialist's system prompt as its own clearly-labeled block (same
     * "don't merge distinct provenances together" discipline {@link #buildMemoryContextBlock}
     * already applies to private vs. shared memory), so the collaborator can tell "context the
     * delegator forwarded" apart from its own system prompt.
     */
    public String delegateCall(UUID agentId, String question, String contextSummary) {
        return delegateCall(agentId, question, contextSummary, "delegated:" + UUID.randomUUID());
    }

    /**
     * {@link #delegateCall(UUID, String, String)} with a caller-chosen session id. Exists for
     * {@code DelegateToTeamToolCallback}'s swarm fan-out: every branch of one swarm run shares a
     * {@code delegated:swarm:<runId>:} session-id prefix, which is the entire shared-run trace -
     * tool-call logs, the quality judge's samples and {@link LlmMetricsAdvisor}'s metrics all
     * already key on session id, so a team run is reconstructable from existing telemetry by prefix
     * alone. All the isolation guarantees above apply unchanged: the id is still throwaway, still
     * carries no history and no memory.
     */
    public String delegateCall(
            UUID agentId, String question, String contextSummary, String delegatedSessionId) {
        return delegateCall(agentId, question, contextSummary, delegatedSessionId, null);
    }

    /**
     * {@link #delegateCall(UUID, String, String, String)} shaped by {@code options}: its system
     * addendum is appended last (after the standing directives) and its tool filter narrows the
     * delegate's resolved tools. Used by {@code run_subtasks}, whose workers are the calling agent
     * itself minus delegation, scheduling, approval and shell-write tools. Null = unchanged.
     */
    public String delegateCall(
            UUID agentId,
            String question,
            String contextSummary,
            String delegatedSessionId,
            DelegateOptions options) {
        Agent agent = agentService.getEntity(agentId);
        // Cross-user resource sharing retrofit (Round 2) - RUNNER check on the
        // COLLABORATOR (target) agent before delegating to it. Deliberately
        // reuses whatever TenantContext is already active for this in-flight
        // turn rather than requiring a "real end-user" identity: this method
        // runs deep inside an agent's own execution loop (called from
        // DelegateToAgentToolCallback, itself invoked mid-tool-call by
        // whichever ChatClient the DELEGATING agent's own turn built), not a
        // fresh per-request entry point, so "the current actor" is exactly
        // whoever/whatever originated the outer turn - a real web/API user
        // (their Keycloak subject), an omnichannel channel's synthetic
        // per-message identity (see #chat's own comment above), or,
        // conceivably, no context at all for some future non-request caller.
        //
        // No special-casing needed for any of those cases:
        // ResourceAccessService#requireAccess already checks createdBy == null
        // FIRST, before it ever looks at TenantContext - so every
        // pre-existing/legacy collaborator (the overwhelmingly common case,
        // since collaboratorAgentIds predates this feature) is completely
        // unaffected regardless of what "the current actor" even is. The only
        // case this can actually deny is a genuinely NEW, private collaborator
        // reached by a turn with no identifiable/authorized actor on it -
        // which is precisely the right outcome (404, same anti-enumeration
        // shape as everywhere else in this feature), not an edge case worth an
        // ad hoc bypass: there is no meaningful "platform itself orchestrating"
        // reading of this specific call path (unlike e.g. a cron-fired
        // workflow trigger) - a delegation tool call only ever exists because
        // SOME turn, initiated by SOME actor, is in progress.
        resourceAccessService.requireAccess(
                ResourceType.AGENT, agentId, agent.getCreatedBy(), PermissionLevel.RUNNER);
        // Step-event vocabulary (2026-09-09): announce the handoff on the OUTER
        // turn's side channel, if that turn asked for step events. This method
        // has no emitter parameter and cannot get one without widening
        // DelegateToAgentToolCallback's constructor through AgentToolProvider,
        // so it reads the tool-scoped binding StepEventToolCallback's
        // emitter.toolCall() established on this very thread - see
        // AgentStepEventEmitter#current for why that binding is safe and why
        // it is the only workable carrier. Empty (no-op) for every turn that
        // did not opt in, and for every non-turn caller.
        AgentStepEventEmitter.current()
                .ifPresent(emitter -> emitter.handoff(emitter.runAgentId(), agentId));
        String tenantId = TenantContext.current().map(TenantContext::getTenantId).orElse(null);
        String apiKey = credentialResolver.resolveActiveKey(agent.getProviderName());
        // #1086 - same null-model self-heal as buildChatClientFor above.
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
        List<ToolCallback> tools =
                DelegateOptions.filter(
                        options,
                        agentToolProvider.resolveToolsForDelegatedCall(
                                agentId, delegatedSessionId, question, chatClient));
        String experimentVariants = this.experimentVariants.apply(tenantId);

        // Skills block sits between the agent's own prompt and the forwarded context so the
        // stable part stays a prefix - a delegated specialist is exactly who benefits from skills.
        String delegatePrompt =
                withSkillsBlock(
                        withSkillsBlock(
                                agent.getSystemPrompt(),
                                agentToolProvider.proceduresPromptBlock(agentId)),
                        agentToolProvider.skillsPromptBlock(agentId, tools));
        String basePrompt =
                (contextSummary == null || contextSummary.isBlank())
                        ? delegatePrompt
                        : delegatePrompt
                                + "\n\nContext forwarded by the delegating agent (a short summary, not the full "
                                + "conversation - use it only if relevant to the question below):\n"
                                + contextSummary;
        // 2026-10-01 audit fix: the collaborator resolves its OWN tools (MCP
        // included) but previously got none of the standing directives - the
        // fabricated-URL/false-claim/untrusted-data rails applied to outer
        // turns only. Appended last in the system text, after the agent prompt
        // and forwarded context, matching the outer path's ordering (agent
        // prompt first, directives second). The anyMcp probe is a cache hit
        // on the discovery resolveToolsForDelegatedCall just did, and is
        // skipped entirely when the kill-switch is off.
        String directiveBlock =
                delegationDirectiveBlock(
                        directiveGates,
                        tools,
                        directiveGates.mcpUntrusted().getAsBoolean()
                                && agentToolProvider.anyMcpToolsOfferedFor(
                                        agentId, delegatedSessionId, question, chatClient));
        String systemPrompt =
                DelegateOptions.append(
                        options,
                        directiveBlock == null ? basePrompt : basePrompt + "\n\n" + directiveBlock);

        try {
            // #848 (LLM gateway seam) - delegated (agent-to-agent) calls
            // never had provider fallback before this either; see
            // callWithProviderFallback's javadoc.
            String answer =
                    turns.callWithProviderFallback(
                            tenantId,
                            agent,
                            chatClient,
                            client ->
                                    client.prompt()
                                            .system(systemPrompt)
                                            .user(question)
                                            .toolCallbacks(tools)
                                            .advisors(
                                                    a -> {
                                                        // #1102 - see the same fix in
                                                        // buildChatClientFor's callers above for
                                                        // why
                                                        // a null experimentVariants (no active
                                                        // experiment - the common case)
                                                        // can't be passed straight through to
                                                        // Spring AI's AdvisorSpec#param.
                                                        a.param(
                                                                        LlmMetricsAdvisor
                                                                                .AGENT_ID_PARAM,
                                                                        agentId)
                                                                .param(
                                                                        LlmMetricsAdvisor
                                                                                .SESSION_ID_PARAM,
                                                                        delegatedSessionId);
                                                        if (experimentVariants != null) {
                                                            a.param(
                                                                    LlmMetricsAdvisor
                                                                            .EXPERIMENT_VARIANTS_PARAM,
                                                                    experimentVariants);
                                                        }
                                                    })
                                            .call()
                                            .content());
            String result =
                    (answer == null || answer.isBlank())
                            ? "The collaborator agent returned an empty answer."
                            : answer;
            // Gap 2 - delegated calls are sampled too, same as any other turn.
            llmQualityJudgeService.sampleAsync(
                    chatClient, agentId, delegatedSessionId, question, result);
            return result;
        } catch (RuntimeException e) {
            log.warn("Delegated call to collaborator agent {} failed", agentId, e);
            throw (e instanceof ToolStepLimitExceededException
                            || e instanceof GuardrailViolationException)
                    ? e
                    : providerErrorTranslator.translate(e, agent.getProviderName());
        }
    }
}
