package com.agenticai.core.agents.hooks;

import com.agenticai.core.agents.tools.AgentStepEventEmitter;
import com.agenticai.core.agents.tools.classification.ToolClassification;
import com.agenticai.core.agents.tools.classification.ToolClassificationDecision;
import com.agenticai.core.agents.tools.classification.ToolClassificationRegistry;
import com.agenticai.core.agents.tools.ToolSearchToolNames;
import com.agenticai.core.audit.AuditLogService;
import com.agenticai.core.mcp.McpRemoteUrlGuard;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.concurrent.TenantAwareExecutors;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The agent lifecycle hooks service: the single thing the chat and tool paths talk to.
 *
 * <p>With {@code app.agent-hooks.enabled=false} (the default) every entry point returns {@code
 * null} before doing any work - no database read, no allocation per tool - and the callers build
 * exactly the objects they built before this feature existed. That is what "flag off =
 * byte-identical" means here.
 *
 * <p>With it on:
 *
 * <ul>
 *   <li>{@link #toolHooks} resolves the hooks that apply to one tool and returns the adapters
 *       {@code AgentToolGuard} splices into that tool's wrapper chain (see {@link AgentToolHooks});
 *   <li>{@link #beginRun} resolves the model and run hooks of one chat turn (see {@link HookRun}).
 * </ul>
 *
 * <p>Classification facts (trust, sensitivity, irreversible) are only looked up for hooks that
 * match on them, and memoised per tenant and tool for a minute - the registry reads the database on
 * every resolve.
 */
@Component
public class AgentHookRuntime {

    private static final Logger log = LoggerFactory.getLogger(AgentHookRuntime.class);
    private static final long FACTS_TTL_MILLIS = 60_000L;
    private static final int FACTS_MAX = 2_000;

    /**
     * Deferred-tool dispatch plumbing; see {@code AgentToolGuard#approvalGateModeFor}. Never gated
     * by block/approval hooks.
     */
    private static final Set<String> DISPATCH_TOOLS =
            Set.of(ToolSearchToolNames.SEARCH_TOOLS, ToolSearchToolNames.CALL_TOOL);

    private record FactsEntry(HookMatcher.ToolFacts facts, long expiresAtMillis) {}

    private final boolean enabled;
    private final AgentHookConfigService config;
    private final ObjectProvider<ToolClassificationRegistry> registry;
    private final HookEngine engine;
    private final Map<AtomicInteger, HookRateLimiter.RunCounters> runCounters =
            Collections.synchronizedMap(new WeakHashMap<>());
    private final Map<String, FactsEntry> factsCache = new ConcurrentHashMap<>();

    @Autowired
    public AgentHookRuntime(
            AgentHookConfigService config,
            ObjectProvider<ToolClassificationRegistry> registry,
            ObjectProvider<AuditLogService> audit,
            ObjectProvider<MeterRegistry> meters,
            ObjectProvider<McpRemoteUrlGuard> urlGuard,
            @Value("${app.agent-hooks.enabled:true}") boolean enabled,
            @Value("${app.agent-hooks.latency-budget-ms:2000}") long latencyBudgetMillis,
            @Value("${app.agent-hooks.hard-cap-ms:5000}") long hardCapMillis,
            @Value("${app.agent-hooks.webhook.signing-secret:}") String signingSecret) {
        this.enabled = enabled;
        this.config = config;
        this.registry = registry;
        HookWebhookClient webhook =
                new HookWebhookClient(
                        url -> {
                            McpRemoteUrlGuard guard = urlGuard.getIfAvailable();
                            return guard == null
                                    ? Optional.of("no SSRF guard is available")
                                    : guard.checkResolved(url);
                        },
                        config.platformWebhookHosts(),
                        signingSecret);
        this.engine =
                new HookEngine(
                        webhook,
                        new HookRateLimiter(),
                        new HookDecisionSink(audit, meters),
                        latencyBudgetMillis,
                        hardCapMillis,
                        TenantAwareExecutors.newVirtualThreadPerTaskExecutor("agent-hook-runtime-"));
    }

    /** Wires the prompt registry for the hook wording ({@code message.hook.*}); absent = the shipped text. */
    @Autowired(required = false)
    public void setPromptResolver(com.agenticai.core.prompts.PromptResolver resolver) {
        engine.usePrompts(resolver);
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Resolves the hooks that apply to one tool and returns the chain adapters, or {@code null}
     * when the feature is off or none applies.
     *
     * @param stepCounter the turn's shared step counter - identifies the turn, so per-run limits
     *     count per turn
     * @param stepEvents the turn's emitter when the caller asked for step events, else {@code null}
     */
    public AgentToolHooks toolHooks(
            ToolCallback raw,
            UUID agentId,
            String sessionId,
            AtomicInteger stepCounter,
            AgentStepEventEmitter stepEvents) {
        if (!enabled) {
            return null;
        }
        try {
            HookSet set = config.effective(agentId);
            if (set.isEmpty()) {
                return null;
            }
            String tool = raw.getToolDefinition().name();
            HookMatcher.ToolFacts facts = null;
            boolean factsLoaded = false;
            List<EffectiveHook> gate = new ArrayList<>();
            List<EffectiveHook> pre = new ArrayList<>();
            List<EffectiveHook> after = new ArrayList<>();
            List<EffectiveHook> error = new ArrayList<>();
            for (EffectiveHook eh : set.hooks()) {
                HookDefinition def = eh.def();
                HookPoint point = def.point();
                if (!def.enabled() || !(point.isToolPoint() || point == HookPoint.ON_ERROR)) {
                    continue;
                }
                if (def.match().needsClassification() && !factsLoaded) {
                    facts = factsFor(tool);
                    factsLoaded = true;
                }
                if (!HookMatcher.matches(def, tool, facts)) {
                    continue;
                }
                switch (point) {
                    case BEFORE_TOOL -> {
                        if (def.action() == HookActionType.BLOCK
                                || def.action() == HookActionType.REQUIRE_APPROVAL) {
                            // search_tools / call_tool are dispatch plumbing: AgentToolGuard never
                            // gates them (a parked
                            // call could not be replayed) and the deferred target carries its own
                            // guard chain and
                            // hooks. A wildcard block / require_approval hook must not park or deny
                            // the dispatcher.
                            if (!DISPATCH_TOOLS.contains(tool)) {
                                gate.add(eh);
                            }
                        } else {
                            pre.add(eh);
                        }
                    }
                    case AFTER_TOOL -> after.add(eh);
                    case ON_ERROR -> error.add(eh);
                    default -> {
                        // model and run points are handled by HookRun
                    }
                }
            }
            AgentToolHooks hooks =
                    new AgentToolHooks(
                            engine,
                            HookCtx.capture(agentId, sessionId, stepEvents, set.webhookHosts()),
                            tool,
                            gate,
                            pre,
                            after,
                            error,
                            runCountersFor(stepCounter));
            return hooks.isEmpty() ? null : hooks;
        } catch (RuntimeException e) {
            // Resolution itself failed (not a hook). Building the chain without hooks would
            // silently drop the
            // workspace's governance, so say so loudly; the call still proceeds through the full
            // guard chain.
            log.error(
                    "Could not resolve agent hooks for a tool of agent {} - this tool runs without hooks",
                    agentId,
                    e);
            return null;
        }
    }

    /**
     * Resolves the model and run hooks of one chat turn, or {@code null} when the feature is off or
     * none is configured. Call on the thread that carries the request's tenant context.
     */
    public HookRun beginRun(UUID agentId, String sessionId, AgentStepEventEmitter stepEvents) {
        if (!enabled) {
            return null;
        }
        try {
            HookSet set = config.effective(agentId);
            if (set.isEmpty()) {
                return null;
            }
            List<EffectiveHook> before = new ArrayList<>();
            List<EffectiveHook> after = new ArrayList<>();
            List<EffectiveHook> error = new ArrayList<>();
            List<EffectiveHook> end = new ArrayList<>();
            for (EffectiveHook eh : set.hooks()) {
                HookDefinition def = eh.def();
                if (!def.enabled() || !HookMatcher.matches(def, null, null)) {
                    continue;
                }
                switch (def.point()) {
                    case BEFORE_MODEL -> before.add(eh);
                    case AFTER_MODEL -> after.add(eh);
                    case ON_ERROR -> error.add(eh);
                    case ON_RUN_END -> end.add(eh);
                    default -> {
                        // tool points are handled per tool
                    }
                }
            }
            if (before.isEmpty() && after.isEmpty() && error.isEmpty() && end.isEmpty()) {
                return null;
            }
            return new HookRun(
                    engine,
                    HookCtx.capture(agentId, sessionId, stepEvents, set.webhookHosts()),
                    before,
                    after,
                    error,
                    end);
        } catch (RuntimeException e) {
            log.error(
                    "Could not resolve agent hooks for a turn of agent {} - this turn runs without model hooks",
                    agentId,
                    e);
            return null;
        }
    }

    private HookRateLimiter.RunCounters runCountersFor(AtomicInteger stepCounter) {
        if (stepCounter == null) {
            return new HookRateLimiter.RunCounters();
        }
        synchronized (runCounters) {
            return runCounters.computeIfAbsent(stepCounter, k -> new HookRateLimiter.RunCounters());
        }
    }

    /**
     * Classification facts for a tool; {@link HookMatcher.ToolFacts#UNKNOWN} when the lookup failed
     * (matches conservatively).
     */
    private HookMatcher.ToolFacts factsFor(String tool) {
        String tenant = TenantContext.current().map(TenantContext::getTenantId).orElse("-");
        String key = tenant + "|" + tool;
        long now = System.currentTimeMillis();
        FactsEntry cached = factsCache.get(key);
        if (cached != null && cached.expiresAtMillis() > now) {
            return cached.facts();
        }
        ToolClassificationRegistry resolved = registry.getIfAvailable();
        if (resolved == null) {
            return HookMatcher.ToolFacts.UNKNOWN;
        }
        try {
            HookMatcher.ToolFacts facts;
            if (resolved.resolve(tool)
                    instanceof ToolClassificationDecision.Classified classified) {
                ToolClassification c = classified.classification();
                facts =
                        HookMatcher.ToolFacts.classified(
                                c.trust().name(), c.sensitivity().name(), c.irreversible());
            } else {
                facts = HookMatcher.ToolFacts.UNCLASSIFIED;
            }
            if (factsCache.size() >= FACTS_MAX) {
                factsCache.clear();
            }
            factsCache.put(key, new FactsEntry(facts, now + FACTS_TTL_MILLIS));
            return facts;
        } catch (RuntimeException e) {
            log.warn(
                    "Tool classification lookup failed for '{}' while matching hooks - matching conservatively",
                    tool,
                    e);
            return HookMatcher.ToolFacts.UNKNOWN;
        }
    }
}
