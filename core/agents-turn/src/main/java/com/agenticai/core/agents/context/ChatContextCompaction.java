package com.agenticai.core.agents.context;

import com.agenticai.core.agents.context.ContextCompactionEngine.Params;
import com.agenticai.core.agents.context.ContextCompactionEngine.Summarizer;
import com.agenticai.core.agents.core.Agent;
import com.agenticai.core.agents.hosted.HostedChainLink;
import com.agenticai.core.agents.tools.AgentStepEventEmitter;
import com.agenticai.core.agents.tools.ToolCallLogService;
import com.agenticai.core.agents.tools.context.RestoreToolResultToolCallback;
import com.agenticai.core.providers.ProviderModelCatalogService;
import com.agenticai.core.providers.ProviderModelResponse;
import com.agenticai.core.prompts.PromptContext;
import com.agenticai.core.prompts.PromptResolver;
import com.agenticai.core.providers.ProviderName;
import com.agenticai.core.security.TenantContext;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.UnaryOperator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Context engineering for long-running agents: keeps a chat run's prompt inside the model's context
 * window instead of letting a deep-research, browser or coding run die when it fills. Off by
 * default ({@code app.context.compaction.enabled=false}); while off, {@link #openRun} returns null,
 * no tool is offered and no directive is sent, so prompts are byte-identical to a build without
 * this feature.
 *
 * <p>What it does when on (see {@link ContextCompactionEngine} and {@link ContextBudgetPlanner}):
 *
 * <ol>
 *   <li><b>Tool-result clearing</b> - above {@code clear-at} of the window, tool results older than
 *       the newest {@code keep-recent-tool-results} become small restorable placeholders. Results
 *       the model has not seen yet are never cleared. The original is still in {@code
 *       tool_call_log}; the model can fetch it with {@code restore_tool_result} or call the tool
 *       again.
 *   <li><b>Auto-compact</b> - still above {@code compact-at}, the oldest prior turns are replaced
 *       by one summary message. Only the PROMPT VIEW changes: session history, the step journal and
 *       {@code tool_call_log} are never touched.
 * </ol>
 *
 * <p>Wiring: {@code AgentChatService} opens one {@link CompactionRun} per turn, runs {@link
 * CompactionRun#prepareMessages} over the assembled history and attaches the run to the client so
 * the tool-loop manager applies it after every tool round; {@code AgentToolProvider} offers the
 * restore tool through {@link #restoreToolsFor}. All collaborators are {@link ObjectProvider}s so
 * slim test contexts construct this with nothing.
 *
 * <p>Not covered, on purpose: delegated inner calls (agent-to-agent, specialist handoff) and
 * workflow agent nodes that bypass {@code AgentChatService#chat}'s main path do not get a run, so
 * for them nothing changes (and no directive is sent). The semantic/exact response caches are keyed
 * on the user message and unaffected.
 */
@Service
public class ChatContextCompaction {

    private static final Logger log = LoggerFactory.getLogger(ChatContextCompaction.class);

    private final ObjectProvider<LlmCompactionSummarizer> summarizer;
    private final ObjectProvider<ProviderModelCatalogService> catalog;
    private final ObjectProvider<ToolCallLogService> toolCallLogs;
    private final ObjectProvider<MeterRegistry> meterRegistry;
    private final ContextCompactionEngine engine = new ContextCompactionEngine();

    private final boolean enabled;
    private final double clearAt;
    private final double compactAt;
    private final int keepRecentToolResults;
    private final boolean restoreToolEnabled;
    private final int keepRecentMessages;
    private final int defaultWindowTokens;
    private final int summaryMaxTokens;
    private final int maxSummariesPerRun;

    /**
     * {@code app.context.compaction.*}: the token estimates, the window name rules and the size of a restored
     * result. A hand-built instance keeps the shipped defaults, which is also what Spring supplies when the
     * properties bean is absent.
     */
    private volatile ContextCompactionProperties tuning = ContextCompactionProperties.shipped();

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setTuning(ContextCompactionProperties tuning) {
        this.tuning = tuning == null ? ContextCompactionProperties.shipped() : tuning;
    }

    public ChatContextCompaction(
            ObjectProvider<LlmCompactionSummarizer> summarizer,
            ObjectProvider<ProviderModelCatalogService> catalog,
            ObjectProvider<ToolCallLogService> toolCallLogs,
            ObjectProvider<MeterRegistry> meterRegistry,
            @Value("${app.context.compaction.enabled:true}") boolean enabled,
            @Value("${app.context.compaction.clear-at:" + ContextCompactionProperties.DEFAULT_CLEAR_AT + "}")
                    double clearAt,
            @Value("${app.context.compaction.compact-at:" + ContextCompactionProperties.DEFAULT_COMPACT_AT + "}")
                    double compactAt,
            @Value("${app.context.compaction.keep-recent-tool-results:" + ContextCompactionProperties.DEFAULT_KEEP_RECENT_TOOL_RESULTS + "}")
                    int keepRecentToolResults,
            @Value("${app.context.compaction.restore-tool-enabled:true}")
                    boolean restoreToolEnabled,
            @Value("${app.context.compaction.keep-recent-messages:6}") int keepRecentMessages,
            @Value("${app.context.compaction.default-window-tokens:" + ContextCompactionProperties.DEFAULT_WINDOW_TOKENS + "}")
                    int defaultWindowTokens,
            @Value("${app.context.compaction.summary-max-tokens:" + ContextCompactionProperties.DEFAULT_SUMMARY_TOKENS + "}")
                    int summaryMaxTokens,
            @Value("${app.context.compaction.max-summaries-per-run:3}") int maxSummariesPerRun) {
        this.summarizer = summarizer;
        this.catalog = catalog;
        this.toolCallLogs = toolCallLogs;
        this.meterRegistry = meterRegistry;
        this.enabled = enabled;
        this.clearAt = clearAt;
        this.compactAt = compactAt;
        this.keepRecentToolResults = keepRecentToolResults;
        this.restoreToolEnabled = restoreToolEnabled;
        this.keepRecentMessages = keepRecentMessages;
        this.defaultWindowTokens =
                defaultWindowTokens > 0
                        ? defaultWindowTokens
                        : ModelContextWindows.DEFAULT_WINDOW_TOKENS;
        this.summaryMaxTokens = Math.max(summaryMaxTokens, 64);
        this.maxSummariesPerRun = maxSummariesPerRun;
    }

    /**
     * Where the wording of the summary and of the cleared-result note comes from; the shipped text
     * until Spring supplies the prompt registry.
     */
    private volatile PromptResolver prompts = PromptResolver.defaultsOnly();

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setPromptResolver(PromptResolver prompts) {
        this.prompts = prompts == null ? PromptResolver.defaultsOnly() : prompts;
    }

    /** The wording for one run of {@code agentId}: its, its tenant's or the platform's override, else the shipped one. */
    private ContextCompactionEngine.Texts textsFor(UUID agentId) {
        try {
            return ContextCompactionEngine.Texts.build(prompts, PromptContext.forAgent(agentId));
        } catch (RuntimeException e) {
            return ContextCompactionEngine.Texts.shipped();
        }
    }

    /** The master switch. */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * {@code restore_tool_result} for one run, or an empty list when compaction or the restore tool
     * is off (or the log service is absent). The caller wraps it with the usual guard chain.
     */
    public List<ToolCallback> restoreToolsFor(UUID agentId, String sessionId) {
        if (!enabled || !restoreToolEnabled || agentId == null || sessionId == null) {
            return List.of();
        }
        ToolCallLogService logs = toolCallLogs.getIfAvailable();
        if (logs == null) {
            return List.of();
        }
        return List.of(
                new RestoreToolResultToolCallback(
                        logs, agentId, sessionId, tuning.getRestoreResultMaxChars()));
    }

    /**
     * Opens this turn's compaction run, or returns null when the feature is off.
     *
     * @param provider the agent's provider (null for the hosted pool) - used only to look the
     *     window up
     * @param model this turn's model id, or null
     * @param tools this turn's final tool list: sizes the tool-schema overhead and decides whether
     *     placeholders point at {@code restore_tool_result}
     * @param stepEvents the turn's emitter; null or disabled = no {@code context_compacted} events
     */
    public CompactionRun openRun(
            ProviderName provider,
            String model,
            UUID agentId,
            String sessionId,
            List<ToolCallback> tools,
            AgentStepEventEmitter stepEvents) {
        if (!enabled) {
            return null;
        }
        int window = resolveWindow(provider, model);
        boolean restoreOffered =
                tools != null
                        && tools.stream()
                                .anyMatch(
                                        t ->
                                                RestoreToolResultToolCallback.NAME.equals(
                                                        t.getToolDefinition().name()));
        Params params =
                new Params(
                        window,
                        clearAt,
                        compactAt,
                        keepRecentToolResults,
                        keepRecentMessages,
                        summaryMaxTokens,
                        restoreOffered,
                        tuning,
                        textsFor(agentId));
        return new CompactionRun(
                engine,
                params,
                model,
                defaultWindowTokens,
                CompactionRun.toolSchemaTokens(tools),
                summarizerFor(agentId, sessionId),
                stepEvents,
                maxSummariesPerRun,
                meterRegistry == null ? null : meterRegistry.getIfAvailable());
    }

    /**
     * The chat path's one-line entry point: resolves the model this turn will actually call (the
     * hosted chain's first link, an RL override, else the agent's effective model) and opens the
     * run. Never fails the turn - any problem means no run, so the turn proceeds exactly as it
     * would with the feature off.
     *
     * @param effectiveModel the agent's configured model resolver ({@code
     *     AgentService#resolveEffectiveModel})
     */
    public CompactionRun openRunFor(
            Agent agent,
            String rlModelOverride,
            List<HostedChainLink> hostedChain,
            UUID agentId,
            String sessionId,
            List<ToolCallback> tools,
            AgentStepEventEmitter stepEvents,
            Function<Agent, String> effectiveModel) {
        if (!enabled) {
            return null;
        }
        try {
            String model =
                    hostedChain != null
                            ? hostedChain.get(0).model()
                            : rlModelOverride != null
                                    ? rlModelOverride
                                    : effectiveModel.apply(agent);
            return openRun(
                    hostedChain != null ? null : agent.getProviderName(),
                    model,
                    agentId,
                    sessionId,
                    tools,
                    stepEvents);
        } catch (RuntimeException e) {
            log.warn(
                    "Could not open context compaction for agent {} - this turn runs without it: {}",
                    agentId,
                    e.toString());
            return null;
        }
    }

    /**
     * {@code restore_tool_result} wrapped by the caller's guard chain, ready to add to a tool list;
     * empty while compaction or the restore tool is off. A name already in {@code alreadyResolved}
     * (an MCP server exposing its own tool of that name) is dropped, because a duplicate tool name
     * is fatal in Spring AI.
     */
    public List<ToolCallback> offeredRestoreTools(
            UUID agentId,
            String sessionId,
            List<ToolCallback> alreadyResolved,
            UnaryOperator<ToolCallback> guard) {
        List<ToolCallback> offered = new ArrayList<>();
        for (ToolCallback tool : restoreToolsFor(agentId, sessionId)) {
            String name = tool.getToolDefinition().name();
            boolean taken =
                    alreadyResolved.stream()
                            .anyMatch(t -> name.equals(t.getToolDefinition().name()));
            if (!taken) {
                offered.add(guard.apply(tool));
            }
        }
        return offered;
    }

    /** Catalog window when known (BYOK), else the name-based floor, else the configured default. */
    int resolveWindow(ProviderName provider, String model) {
        Integer catalogWindow = null;
        if (provider != null && model != null) {
            try {
                ProviderModelCatalogService service = catalog.getIfAvailable();
                if (service != null) {
                    Optional<ProviderModelResponse> found = service.findModel(provider, model);
                    catalogWindow = found.map(ProviderModelResponse::contextWindow).orElse(null);
                }
            } catch (RuntimeException e) {
                log.debug(
                        "Could not read the context window of {}/{} from the catalog: {}",
                        provider,
                        model,
                        e.toString());
            }
        }
        return ModelContextWindows.resolve(
                catalogWindow, model, defaultWindowTokens, tuning.getWindowRules());
    }

    private Summarizer summarizerFor(UUID agentId, String sessionId) {
        LlmCompactionSummarizer llm = summarizer.getIfAvailable();
        if (llm == null) {
            return null;
        }
        // Captured here, on the request thread: the tool loop that calls this later has no
        // TenantContext.
        Optional<TenantContext> tenant = TenantContext.current();
        return (existing, turns) -> llm.summarize(tenant, agentId, sessionId, existing, turns);
    }
}
