package com.agenticai.core.agents.tools;

import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.audit.IssueSeverity;
import com.agenticai.core.audit.IssueSource;
import com.agenticai.core.audit.PlatformIssueService;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Merges what every {@link ToolSource} contributes into the one tool list a chat call is given.
 * Extracted from {@code AgentToolProvider.resolveToolsFor} (behaviour unchanged); the stages, in
 * order:
 *
 * <ol>
 *   <li>collect the {@link ToolSource.Kind#MCP} and {@link ToolSource.Kind#BUILT_IN} candidates,
 *       each source in {@code @Order};
 *   <li>merge, a built-in winning a name collision against an MCP tool (reported on the Issues
 *       portal);
 *   <li>filter by the agent's allow-list and normalise schemas;
 *   <li>append the {@link ToolSource.Kind#POST_ALLOW_LIST} sources (skills, compaction restore),
 *       which are deliberately not subject to the allow-list;
 *   <li>unless the caller wants the untrimmed candidates: optional on-demand loading, then the
 *       relevance selector, then the hard per-turn cap.
 * </ol>
 */
@Component
final class ToolResolver {

    private static final Logger log = LoggerFactory.getLogger(ToolResolver.class);

    private final List<ToolSource> mcpSources;
    private final List<ToolSource> builtInSources;
    private final List<ToolSource> postAllowListSources;
    private final AgentService agentService;
    private final ToolRelevanceSelector toolRelevanceSelector;
    private final AgentToolGuard toolGuard;
    private final AgentIntegrationToolResolver integrationToolResolver;
    private final ObjectProvider<PlatformIssueService> issueService;
    private final ToolSearchDeferral toolSearchDeferral;
    // Defense-in-depth backstop behind ToolRelevanceSelector - see
    // AgentToolSelection#capToHardLimit.
    private final int hardCapPerTurn;
    // Prompt-registry overrides of tool and parameter descriptions (tool.description.*,
    // tool.template.*, tool.param.*).
    private final ToolDescriptionResolver toolDescriptions;

    /**
     * As the wired constructor, with the shipped tool descriptions only (hand-built resolvers,
     * tests).
     */
    ToolResolver(
            List<ToolSource> sources,
            AgentService agentService,
            ToolRelevanceSelector toolRelevanceSelector,
            AgentToolGuard toolGuard,
            AgentIntegrationToolResolver integrationToolResolver,
            ObjectProvider<PlatformIssueService> issueService,
            ToolSearchDeferral toolSearchDeferral,
            int hardCapPerTurn) {
        this(
                sources,
                agentService,
                toolRelevanceSelector,
                toolGuard,
                integrationToolResolver,
                issueService,
                toolSearchDeferral,
                ToolDescriptionResolver.defaultsOnly(),
                hardCapPerTurn);
    }

    @Autowired
    ToolResolver(
            List<ToolSource> sources,
            AgentService agentService,
            ToolRelevanceSelector toolRelevanceSelector,
            AgentToolGuard toolGuard,
            AgentIntegrationToolResolver integrationToolResolver,
            ObjectProvider<PlatformIssueService> issueService,
            ToolSearchDeferral toolSearchDeferral,
            ToolDescriptionResolver toolDescriptions,
            @Value("${app.tools.hard-cap-per-turn:40}") int hardCapPerTurn) {
        this.toolDescriptions = toolDescriptions;
        this.mcpSources = ofKind(sources, ToolSource.Kind.MCP);
        this.builtInSources = ofKind(sources, ToolSource.Kind.BUILT_IN);
        this.postAllowListSources = ofKind(sources, ToolSource.Kind.POST_ALLOW_LIST);
        this.agentService = agentService;
        this.toolRelevanceSelector = toolRelevanceSelector;
        this.toolGuard = toolGuard;
        this.integrationToolResolver = integrationToolResolver;
        this.issueService = issueService;
        this.toolSearchDeferral = toolSearchDeferral;
        this.hardCapPerTurn = hardCapPerTurn;
    }

    private static List<ToolSource> ofKind(List<ToolSource> sources, ToolSource.Kind kind) {
        return sources.stream().filter(s -> s.kind() == kind).toList();
    }

    /** The MCP-sourced candidates alone (pre allow-list), for {@code anyMcpToolsOfferedFor}. */
    List<ToolCallback> mcpCandidates(ToolResolutionContext ctx) {
        return collect(mcpSources, ctx);
    }

    ResolvedToolList resolve(ToolResolutionContext ctx, boolean applyRelevanceSelection) {
        return resolve(ctx, applyRelevanceSelection, false);
    }

    /**
     * As above; {@code stableToolSet} (a turn on a model served from the person's own machine, see
     * {@code TurnModelTraits#stableToolSet}) skips the per-message relevance trimming so the model
     * sees the same tools on every turn and its server's prompt cache holds. The hard cap and
     * on-demand tool loading still apply.
     */
    ResolvedToolList resolve(
            ToolResolutionContext ctx, boolean applyRelevanceSelection, boolean stableToolSet) {
        // Bound for the whole building of the list, so a tool that renders its own description
        // template when it is constructed (ToolPromptTexts.current()) answers for this agent.
        return toolDescriptions.within(
                ctx.agentId(), () -> resolveList(ctx, applyRelevanceSelection, stableToolSet));
    }

    private ResolvedToolList resolveList(
            ToolResolutionContext ctx, boolean applyRelevanceSelection, boolean stableToolSet) {
        UUID agentId = ctx.agentId();
        // Captured separately (not just folded into `tools`) so ToolRelevanceSelector can tell
        // "one step of an MCP server's multi-step chain" apart from a built-in tool that happens
        // to share an underscore-delimited name prefix - see ToolRelevanceSelector#mcpFamily.
        List<ToolCallback> mcpTools = collect(mcpSources, ctx);
        Set<String> mcpToolNames =
                mcpTools.stream()
                        .map(t -> t.getToolDefinition().name())
                        .collect(Collectors.toSet());
        List<ToolCallback> builtinTools = collect(builtInSources, ctx);
        List<ToolCallback> tools =
                AgentToolSelection.mergeFavoringBuiltins(
                        builtinTools,
                        mcpTools,
                        agentId,
                        (name, message) -> recordToolCollisionIssue(name, agentId, message));
        // See SchemaTypeArrayNormalizer's javadoc - a nullable-typed ("type": ["integer","null"])
        // tool parameter anywhere in this turn's catalog otherwise crashes the WHOLE Gemini
        // request at schema-conversion time, not just a call to that one tool.
        List<ToolCallback> allowed =
                new ArrayList<>(
                        SchemaTypeArrayNormalizer.normalizeAll(
                                filterByAllowedTools(agentId, tools)));
        // Skill tools and restore_tool_result join AFTER the allow-list filter on purpose: they are
        // context infrastructure, not capabilities an admin picks per agent.
        for (ToolSource source : postAllowListSources) {
            ToolResolutionContext withResolved = ctx.withResolved(List.copyOf(allowed));
            if (source.applies(withResolved)) {
                List<ToolCallback> contributed = source.contribute(withResolved);
                // A later source wins over an earlier tool of the same name.
                java.util.Set<String> names = new java.util.HashSet<>();
                for (ToolCallback c : contributed) {
                    names.add(c.getToolDefinition().name());
                }
                allowed.removeIf(t -> names.contains(t.getToolDefinition().name()));
                allowed.addAll(contributed);
            }
        }
        // The registry's wording of each built-in tool for this agent. Last in the list-building,
        // so the skill and restore tools above are covered too; a no-op for tools without keys.
        allowed = new ArrayList<>(toolDescriptions.apply(allowed, agentId));
        boolean anyMcp = !mcpTools.isEmpty();
        // Reachability signal for the scratchpad prompt directive, taken at the last point the
        // whole candidate set is visible: deferral below may move these tools behind search_tools,
        // so "on the final list" is not the same question as "reachable this turn". Recorded
        // whether or not deferral is even on - a direct offer is covered by the directive's own
        // offers() check either way.
        boolean scratchpadOffered =
                allowed.stream()
                        .anyMatch(t -> "scratchpad_write".equals(t.getToolDefinition().name()));
        if (!applyRelevanceSelection) {
            // The caller wants the untrimmed candidate set, not the per-turn LLM-facing cap.
            return new ResolvedToolList(allowed, anyMcp, scratchpadOffered);
        }
        // On-demand tool loading (default OFF). Only a real model call applies it: a null
        // chatClient marks a discovery/preview probe, which must keep seeing the real tools. When
        // it does not apply, apply() returns `allowed` itself and this block changes nothing.
        // forceToolDeferral is the serving row's own switch (V674): a slow local model defers its
        // tools even with the global flag off.
        if (ctx.chatClient() != null
                && (toolSearchDeferral.isEnabled() || ctx.forceToolDeferral())) {
            List<ToolCallback> deferred =
                    toolSearchDeferral.apply(
                            allowed,
                            agentId,
                            ctx.sessionId(),
                            (raw, countsAgainstTurnSteps) ->
                                    toolGuard.guardAndLog(
                                            raw,
                                            agentId,
                                            ctx.sessionId(),
                                            countsAgainstTurnSteps
                                                    ? ctx.stepCounter()
                                                    : new AtomicInteger(0),
                                            ctx.userMessage(),
                                            ctx.chatClient(),
                                            false),
                            ctx.chatClient(),
                            ctx.forceToolDeferral());
            if (deferred != allowed) {
                // search_tools / call_tool are added here, after the pass above.
                return new ResolvedToolList(
                        AgentToolSelection.capToHardLimit(
                                toolDescriptions.apply(deferred, agentId),
                                hardCapPerTurn,
                                mcpToolNames,
                                agentId),
                        anyMcp,
                        scratchpadOffered);
            }
        }
        List<ToolCallback> selected =
                stableToolSet
                        ? allowed
                        : toolRelevanceSelector.select(
                                ctx.userMessage(), allowed, alwaysRelevant(ctx), mcpToolNames);
        return new ResolvedToolList(
                AgentToolSelection.capToHardLimit(selected, hardCapPerTurn, mcpToolNames, agentId),
                anyMcp,
                scratchpadOffered);
    }

    /**
     * The names selection never trims: the standing capabilities, plus - on the continuation turn
     * after an approval - the tools this conversation already used. A continuation's goal matches
     * the tool it is waiting on, but the NEXT step often needs one the goal never names (a lookup
     * made earlier, a follow-up write), and a tool that vanishes mid-task makes the model narrate
     * what it would have done instead of doing it.
     */
    private static Set<String> alwaysRelevant(ToolResolutionContext ctx) {
        if (ctx.stickyTools().isEmpty()) {
            return AgentToolSelection.ALWAYS_RELEVANT_TOOL_NAMES;
        }
        Set<String> names = new java.util.HashSet<>(AgentToolSelection.ALWAYS_RELEVANT_TOOL_NAMES);
        names.addAll(ctx.stickyTools());
        return names;
    }

    private static List<ToolCallback> collect(List<ToolSource> sources, ToolResolutionContext ctx) {
        List<ToolCallback> out = new ArrayList<>();
        for (ToolSource source : sources) {
            if (source.applies(ctx)) {
                out.addAll(source.contribute(ctx));
            }
        }
        return out;
    }

    /**
     * Opt-in allow-list filter. Only applied when the agent has at least one entry in {@code
     * allowedToolNames}; an agent with none keeps every normally-resolved tool. See {@code
     * Agent.allowedToolNames}'s javadoc for why this is opt-in rather than a strict default-deny.
     */
    private List<ToolCallback> filterByAllowedTools(UUID agentId, List<ToolCallback> tools) {
        Set<String> allowed = agentService.getAllowedToolNames(agentId);
        if (allowed.isEmpty()) {
            return tools;
        }
        // The "Deep research" capability switch counts as an allow for exactly its two tool names.
        return AgentIntegrationToolResolver.filterAllowed(
                allowed, tools, () -> integrationToolResolver.researchCapabilityEnabled(agentId));
    }

    /**
     * Surfaces a dropped-MCP-tool collision on the admin Issues portal, not just the server log -
     * same {@code TOOL_CALL} source + best-effort posture as {@link
     * LoggingToolCallback#recordIssue}. {@code sourceId} is the tool NAME (not the agent id) so
     * repeated collisions on the same name dedupe onto one recurring issue row.
     */
    private void recordToolCollisionIssue(String toolName, UUID agentId, String message) {
        PlatformIssueService service = issueService.getIfAvailable();
        if (service == null) {
            return;
        }
        try {
            service.record(
                    IssueSource.TOOL_CALL,
                    toolName,
                    IssueSeverity.WARNING,
                    "MCP tool name collision: '" + toolName + "' (agent " + agentId + ")",
                    message);
        } catch (RuntimeException recordingFailure) {
            log.warn(
                    "Failed to record platform issue for tool name collision '{}'",
                    toolName,
                    recordingFailure);
        }
    }
}
