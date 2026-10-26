package com.agenticai.core.agents.tools;

import com.agenticai.core.agents.tools.classification.BuiltInToolClassifications;
import com.agenticai.core.agents.tools.search.CallToolToolCallback;
import com.agenticai.core.agents.tools.search.SearchCatalogScreen;
import com.agenticai.core.agents.tools.search.SearchToolsToolCallback;
import com.agenticai.core.agents.tools.search.ToolActivationRegistry;
import com.agenticai.core.agents.tools.search.ToolSearchService;
import com.agenticai.core.agents.tools.search.ToolSearchState;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.concurrent.TenantAwareExecutors;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;

/**
 * The deferral policy behind {@code app.tools.search.*}: when an agent resolves more tools than the
 * threshold, most of them are withheld from the model and reachable through {@code search_tools} /
 * {@code call_tool} instead, so the model neither pays for every schema on every turn nor loses
 * accuracy choosing among hundreds.
 *
 * <p><b>Off, or at or under the threshold, it is the identity:</b> {@link #apply} returns the very
 * list it was given and does nothing else - same instance, no allocation, no registry access.
 *
 * <p>When active, the model is offered exactly: {@code search_tools}, {@code call_tool}, the
 * "always-on" tools (a configurable list of exact names or {@code prefix*} patterns - standing
 * capabilities such as memory and skills, which a keyword search cannot be expected to surface),
 * and any tools this session already searched for on an earlier turn (bounded by {@code
 * max-carry-over}). Every other tool is deferred. Deferred tools are the same already-wrapped
 * callbacks, so see {@link CallToolToolCallback} for why that keeps every guard in force.
 *
 * <p>Only the LLM-facing resolution applies this (the caller gates on that); discovery probes and
 * exact-name lookups (edge execution, approval replay, workflow nodes) keep the full list.
 *
 * <p>Thread-safe: configuration fields are volatile and per-call state lives in the returned {@link
 * ToolSearchState}.
 */
final class ToolSearchDeferral {

    private static final Logger log = LoggerFactory.getLogger(ToolSearchDeferral.class);

    /**
     * Virtual threads for the background catalog screen; the executor carries the calling thread's
     * tenant onto the worker (AGENTS.md #12), which {@code sanitizeToolOutput} reads.
     */
    private static final ExecutorService CATALOG_EXECUTOR =
            TenantAwareExecutors.newVirtualThreadPerTaskExecutor("tool-catalog-screen-");

    /**
     * Standing capabilities that must stay visible: the memory and knowledge tools {@code
     * AgentToolSelection.ALWAYS_RELEVANT_TOOL_NAMES} already treats as always relevant, plus the
     * skill tools (the skills prompt block is only rendered when {@code load_skill} is on the list,
     * so deferring it would hide every skill) and the delegation tools ({@code ask_agent_*}, {@code
     * delegate_to_specialist}), which collaborator and crew agents depend on and whose prompts
     * never mention search_tools, and {@code restore_tool_result} (a context-compaction placeholder
     * points at it by name, so it must never sit behind search_tools). Mirrored as the default of
     * {@code app.tools.search.always-on}.
     */
    static final String DEFAULT_ALWAYS_ON =
            "remember_fact,remember_shared_fact,recall_related_facts,recall_shared_graph_facts,"
                    + "list_knowledge_collections,search_knowledge_base,search_code,request_human,load_skill,read_skill_file,"
                    + "run_skill_script,propose_skill,improve_skill,ask_agent_*,delegate_to_specialist,run_subtasks,create_workflow,restore_tool_result,generate_document,"
                    + "local_shell,local_shell_read,workspace_list,workspace_read,workspace_search,workspace_write,workspace_edit,vegaduta_setup_guide,vegaduta_api_guide,local_desktop_*,run_desktop_recipe";

    static final int DEFAULT_THRESHOLD = 25;
    static final int DEFAULT_MAX_CARRY_OVER = 6;

    /**
     * Wraps a raw search/dispatch callback in the same guard chain every other tool gets. {@code
     * countsAgainstTurnSteps} is true for {@code search_tools} (it spends one of the turn's steps)
     * and false for {@code call_tool}, whose target is already counted by its own wrapper -
     * counting both would halve the effective step budget of every deferred call.
     */
    @FunctionalInterface
    interface Wrapper {
        ToolCallback wrap(ToolCallback raw, boolean countsAgainstTurnSteps);
    }

    private final ToolSearchService service = new ToolSearchService();
    private volatile ToolActivationRegistry registry = new ToolActivationRegistry();

    /**
     * The one-shot content screen run over every deferred tool's catalog text (name, description,
     * schema) as the catalog is built; null = descriptions enter the index unscreened and search
     * results keep going through the per-call screen like every other tool output.
     */
    private volatile SearchCatalogScreen catalogScreen;

    private volatile boolean enabled = false;
    private volatile int threshold = DEFAULT_THRESHOLD;
    private volatile List<String> alwaysOn = parseNames(DEFAULT_ALWAYS_ON);
    private volatile int maxCarryOver = DEFAULT_MAX_CARRY_OVER;

    void configure(boolean enabled, int threshold, String alwaysOnCsv, int maxCarryOver) {
        this.threshold = threshold < 0 ? DEFAULT_THRESHOLD : threshold;
        this.alwaysOn = parseNames(alwaysOnCsv);
        this.maxCarryOver = Math.max(0, maxCarryOver);
        // Last: a reader that sees enabled=true must also see the values above.
        this.enabled = enabled;
    }

    boolean isEnabled() {
        return enabled;
    }

    /**
     * Swaps the activation carry-over registry ({@code ToolResolverConfiguration} picks a
     * replica-safe store when Redis is available). Null keeps the in-memory default.
     */
    void activations(ToolActivationRegistry registry) {
        if (registry != null) {
            this.registry = registry;
        }
    }

    /**
     * Wires the catalog-entry screen ({@link SearchCatalogScreen}) from the {@code
     * GuardrailService} the per-call output screen uses; null = no screen, and search results stay
     * unmarked.
     */
    void withCatalogScreen(com.agenticai.core.guardrails.GuardrailService guardrailService) {
        this.catalogScreen =
                guardrailService == null ? null : new SearchCatalogScreen(guardrailService);
    }

    /**
     * @param allowed the agent's resolved, allow-list-filtered tools (already guard-wrapped)
     * @return {@code allowed} itself (same instance) when deferral does not apply; otherwise the
     *     tools to offer the model: {@code search_tools}, {@code call_tool}, then the tools that
     *     stay exposed, in their original relative order
     */
    List<ToolCallback> apply(
            List<ToolCallback> allowed, UUID agentId, String sessionId, Wrapper wrapper) {
        return apply(allowed, agentId, sessionId, wrapper, null);
    }

    /**
     * As {@link #apply(List, UUID, String, Wrapper)}, with the turn's {@code chatClient} for the
     * catalog screen's semantic layer; null runs the pattern layer only.
     */
    List<ToolCallback> apply(
            List<ToolCallback> allowed,
            UUID agentId,
            String sessionId,
            Wrapper wrapper,
            org.springframework.ai.chat.client.ChatClient chatClient) {
        if (!enabled || allowed == null || allowed.size() <= threshold) {
            return allowed;
        }
        String tenantId = TenantContext.current().map(TenantContext::getTenantId).orElse(null);
        String key = ToolActivationRegistry.key(tenantId, agentId, sessionId);
        int carry = maxCarryOver;
        Set<String> carried = carry > 0 ? Set.copyOf(registry.recall(key, carry)) : Set.of();
        List<String> keepVisible = alwaysOn;

        SearchCatalogScreen screen = catalogScreen;
        List<ToolCallback> exposedInOrder = new ArrayList<>();
        Map<String, ToolCallback> exposed = new LinkedHashMap<>();
        Map<String, ToolCallback> deferred = new LinkedHashMap<>();
        List<ToolCallback> toScreen = screen == null ? null : new ArrayList<>();
        for (ToolCallback tool : allowed) {
            String name = tool.getToolDefinition().name();
            if (name == null
                    || name.isBlank()
                    || isAlwaysOn(keepVisible, name)
                    || carried.contains(name)) {
                exposedInOrder.add(tool);
                if (name != null) {
                    exposed.putIfAbsent(name, tool);
                }
            } else if (toScreen != null) {
                toScreen.add(tool);
            } else {
                deferred.putIfAbsent(name, tool);
            }
        }

        CompletableFuture<Map<String, ToolCallback>> catalog;
        if (toScreen == null) {
            catalog =
                    CompletableFuture.completedFuture(
                            Collections.unmodifiableMap(new LinkedHashMap<>(deferred)));
        } else {
            // The catalog screen runs on a background virtual thread, off the reply's critical
            // path: deferred tools never reach the model's prompt, so the turn starts on the
            // model call immediately and only a search_tools/call_tool call joins the screening
            // (usually long finished by then - or a turn that never searches pays nothing).
            // Screening is per-tool semantic model calls; serialising them on one thread keeps the
            // screen's own per-text cache coherent and, on a single-model local provider, costs no
            // more wall-clock than a fan-out would.
            catalog =
                    CompletableFuture.supplyAsync(
                                    () -> screenCatalog(toScreen, screen, chatClient, agentId),
                                    CATALOG_EXECUTOR)
                            .exceptionally(
                                    e -> {
                                        log.warn(
                                                "Catalog screen failed wholesale; deferred catalog"
                                                        + " empty this turn",
                                                e);
                                        return Map.of();
                                    });
        }
        boolean anythingDeferred = toScreen == null ? !deferred.isEmpty() : !toScreen.isEmpty();
        if (!anythingDeferred) {
            // Everything is standing capability: nothing to search for, so offer the list
            // untouched.
            return allowed;
        }

        ToolSearchState state =
                new ToolSearchState(
                        exposed, catalog, service, registry, key, carry, screen != null);
        List<ToolCallback> offered = new ArrayList<>(exposedInOrder.size() + 2);
        offered.add(wrapper.wrap(new SearchToolsToolCallback(state), true));
        offered.add(wrapper.wrap(new CallToolToolCallback(state, agentId, sessionId), false));
        offered.addAll(exposedInOrder);
        return offered;
    }

    /**
     * Screens each deferrable tool into its catalog entry. A description/schema the screen
     * withholds is replaced in the catalog entry itself (the tool stays searchable by name); a
     * withheld NAME has no safe listing, so the tool leaves this turn's catalog entirely.
     * Platform-authored text needs no judge: the semantic layer's model call exists to catch
     * hostile text authored OUTSIDE this codebase (MCP servers, tenant/custom tools) - running it
     * on a built-in's own description costs one judge call per deferred tool per cold turn
     * (measured: ~1.3 s each on a local model, a 2-minute "gathering context" stall on a 59-tool
     * catalog) and can flag platform text as injected while adding nothing. Built-ins still take
     * the pattern layer - {@code screened()} with a null chatClient.
     */
    private Map<String, ToolCallback> screenCatalog(
            List<ToolCallback> deferrable,
            SearchCatalogScreen screen,
            org.springframework.ai.chat.client.ChatClient chatClient,
            UUID agentId) {
        Map<String, ToolCallback> screenedMap = new LinkedHashMap<>();
        for (ToolCallback tool : deferrable) {
            String name = tool.getToolDefinition().name();
            boolean platformAuthored = BuiltInToolClassifications.find(name) != null;
            ToolCallback entry =
                    screen.screened(tool, platformAuthored ? null : chatClient, agentId);
            if (entry != null) {
                screenedMap.putIfAbsent(name, entry);
            }
        }
        return Collections.unmodifiableMap(screenedMap);
    }

    static boolean isAlwaysOn(List<String> patterns, String name) {
        for (String pattern : patterns) {
            if (pattern.endsWith("*")
                    ? name.startsWith(pattern.substring(0, pattern.length() - 1))
                    : pattern.equals(name)) {
                return true;
            }
        }
        return false;
    }

    static List<String> parseNames(String csv) {
        List<String> names = new ArrayList<>();
        if (csv != null) {
            for (String part : csv.split(",")) {
                String trimmed = part.trim();
                if (!trimmed.isEmpty()) {
                    names.add(trimmed);
                }
            }
        }
        return List.copyOf(names);
    }
}
