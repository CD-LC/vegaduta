package com.agenticai.core.agents.tools;

import static com.agenticai.core.agents.tools.AgentToolSelection.computeCached;
import static com.agenticai.core.agents.tools.AgentToolSelection.wrapStaticCallbacks;

import com.agenticai.core.agents.tools.AgentToolSelection.CachedMcpTools;
import com.agenticai.core.audit.IssueSeverity;
import com.agenticai.core.audit.IssueSource;
import com.agenticai.core.audit.PlatformIssueService;
import com.agenticai.core.mcp.McpRegistryService;
import com.agenticai.core.security.TenantContext;
import io.micrometer.core.instrument.MeterRegistry;
import io.modelcontextprotocol.client.McpSyncClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Tool-resolution group extracted from {@link AgentToolProvider} (extract-only, no behavior
 * change). Not a Spring bean: constructed by AgentToolProvider.
 */
final class AgentMcpToolResolver {

    private static final Logger log = LoggerFactory.getLogger(AgentMcpToolResolver.class);

    private final AgentToolGuard toolGuard;

    /**
     * 2026-08-19 MCP blackout sibling fix (commit 18fa5c7f's follow-up sweep): the single,
     * JVM-global key for {@link #staticMcpToolCache}. The static Spring AI {@code mcpSyncClients}
     * bean list this cache's loader iterates is a JVM-wide singleton shared by every tenant, so
     * keying the cache per tenant (as it used to be) let two tenants with cold/expired entries run
     * the loader concurrently against the same lazily-initialized stdio clients - exactly the
     * documented single-subscriber transport crash the compute()-serialization exists to prevent -
     * and multiplied discovery cost N-tenants-fold for a value with zero tenant-scoped inputs (the
     * per-tenant anyServerEnabled() gate runs BEFORE the cache; per-tenant guardAndLog wrapping
     * happens after it).
     */
    private static final String STATIC_MCP_CACHE_KEY = "__static__";

    /**
     * 2026-09-28 cross-tenant leak fix: the cached static tool list now depends on {@link
     * StaticMcpExposurePolicy}'s shared-stateful flag (stateless allowlist only vs. every static
     * server), so the two shapes get separate global keys - flipping the flag can never serve the
     * other shape's cached list, not even for one TTL window.
     */
    private static final String STATIC_MCP_CACHE_KEY_SHARED_STATEFUL = "__static__:shared-stateful";

    private final ObjectProvider<ToolCallbackProvider> mcpToolCallbackProvider;
    private final McpRegistryService mcpRegistryService;
    // #424 - servers registered/connected at runtime via the Tools UI (no
    // YAML edit, no restart), independent of mcpToolCallbackProvider above
    // (that one only ever reflects application-mcp.yml's static entries).
    // ObjectProvider for the same reason every other optional capability in
    // this class uses one: keeps this bean constructible even in test/slim
    // contexts where core/agents' DynamicMcpConnectionManager bean isn't
    // present.
    private final ObjectProvider<DynamicMcpConnectionManager> dynamicMcpConnectionManager;
    // MCP-DOCTOR follow-up (pentest M5, static-server namespacing gap): the aggregated
    // `mcpToolCallbackProvider` bean above has no per-tool server attribution - Spring AI's
    // SyncMcpToolCallbackProvider builds it with McpToolNamePrefixGenerator.noPrefix() and its
    // ToolCallback[] carries nothing beyond the bare tool name, so there is no way to recover
    // which of N configured static servers a given callback came from once discovery has run.
    // This is the same "mcpSyncClients" bean Spring AI's autoconfiguration already publishes
    // (one McpSyncClient per application-mcp.yml connection) - resolveStaticMcpTools rebuilds a
    // per-client SyncMcpToolCallbackProvider from it (same one-client pattern
    // DynamicMcpConnectionManager#connect already uses) purely to recover each tool's owning
    // connection name for NamespacedMcpToolCallback, not to change discovery semantics.
    // ObjectProvider for the same reason mcpToolCallbackProvider is one: keeps this bean
    // constructible when the "mcp" profile - and therefore this list - isn't present.
    private final ObjectProvider<List<McpSyncClient>> staticMcpSyncClients;
    private final ObjectProvider<PlatformIssueService> issueService;
    // 2026-08-19 MCP-blackout follow-up sweep: before this, the ONLY signal for a static MCP
    // discovery failure was a log.warn - no metric, no admin-portal issue - so tools silently
    // vanishing (the blackout's exact symptom) was human-discovered, never alerted. Same
    // meterRegistry.counter() pattern AgentJobExecution/FreeTierUsageLimiterService already use
    // in this module.
    private final MeterRegistry meterRegistry;
    private final Duration mcpDiscoveryCacheTtl;
    // #595 follow-up ("quick task is taking too much time to understand the
    // request") - root cause: resolveStaticMcpTools/resolveDynamicMcpTools
    // each do a LIVE, blocking round trip to every configured MCP server
    // (provider.getToolCallbacks() "blocks on McpSyncClient.listTools()
    // under the hood" - see that method's own javadoc) on every single call
    // to resolveToolsFor. Quick Task's preview fires this same full
    // multi-server discovery on every 500ms debounce settle while someone
    // is just typing a task description - each keystroke pause could mean
    // another live network round trip to Google/Brave/every dynamic MCP
    // server, several seconds of visible "AXON is understanding your
    // request…" for something that's supposed to be a lightweight probe.
    // A tenant's set of MCP servers - and therefore the raw tool list each
    // one advertises - does not change from one keystroke to the next, so
    // caching the raw (pre-guardAndLog) discovery result for a short TTL,
    // per tenant schema, removes the repeated network cost entirely for
    // both this preview path and real chat turns, at the cost of at most
    // mcpDiscoveryCacheTtl of staleness if a server's tool catalog changes
    // mid-window (the existing MCP registry/Tools UI ENABLE/DISABLE toggle
    // is a comparatively rare action, not a per-message one).
    private final Map<String, CachedMcpTools> staticMcpToolCache = new ConcurrentHashMap<>();
    private final Map<String, CachedMcpTools> dynamicMcpToolCache = new ConcurrentHashMap<>();

    /**
     * Which static servers may be offered at all - see {@link StaticMcpExposurePolicy}. A Supplier
     * because this class is not a bean and its constructor call site (AgentToolProvider) is fixed:
     * the policy bean is reached through the DynamicMcpConnectionManager bean this class already
     * holds, falling back to {@link StaticMcpExposurePolicy#FAIL_CLOSED} when neither is present.
     */
    private final Supplier<StaticMcpExposurePolicy> exposurePolicy;

    AgentMcpToolResolver(
            AgentToolGuard toolGuard,
            ObjectProvider<ToolCallbackProvider> mcpToolCallbackProvider,
            McpRegistryService mcpRegistryService,
            ObjectProvider<DynamicMcpConnectionManager> dynamicMcpConnectionManager,
            ObjectProvider<List<McpSyncClient>> staticMcpSyncClients,
            ObjectProvider<PlatformIssueService> issueService,
            MeterRegistry meterRegistry,
            Duration mcpDiscoveryCacheTtl) {
        this(
                toolGuard,
                mcpToolCallbackProvider,
                mcpRegistryService,
                dynamicMcpConnectionManager,
                staticMcpSyncClients,
                issueService,
                meterRegistry,
                mcpDiscoveryCacheTtl,
                () -> {
                    DynamicMcpConnectionManager manager =
                            dynamicMcpConnectionManager.getIfAvailable();
                    StaticMcpExposurePolicy policy =
                            manager == null ? null : manager.staticExposurePolicy();
                    return policy == null ? StaticMcpExposurePolicy.FAIL_CLOSED : policy;
                });
    }

    /** Package-private: lets tests inject the exposure policy directly. */
    AgentMcpToolResolver(
            AgentToolGuard toolGuard,
            ObjectProvider<ToolCallbackProvider> mcpToolCallbackProvider,
            McpRegistryService mcpRegistryService,
            ObjectProvider<DynamicMcpConnectionManager> dynamicMcpConnectionManager,
            ObjectProvider<List<McpSyncClient>> staticMcpSyncClients,
            ObjectProvider<PlatformIssueService> issueService,
            MeterRegistry meterRegistry,
            Duration mcpDiscoveryCacheTtl,
            Supplier<StaticMcpExposurePolicy> exposurePolicy) {
        this.exposurePolicy = exposurePolicy;
        this.toolGuard = toolGuard;
        this.mcpToolCallbackProvider = mcpToolCallbackProvider;
        this.mcpRegistryService = mcpRegistryService;
        this.dynamicMcpConnectionManager = dynamicMcpConnectionManager;
        this.staticMcpSyncClients = staticMcpSyncClients;
        this.issueService = issueService;
        this.meterRegistry = meterRegistry;
        this.mcpDiscoveryCacheTtl = mcpDiscoveryCacheTtl;
    }

    private ToolCallback guardAndLog(
            ToolCallback raw,
            UUID agentId,
            String sessionId,
            AtomicInteger stepCounter,
            String userMessage,
            ChatClient chatClient,
            boolean rigorous) {
        return toolGuard.guardAndLog(
                raw, agentId, sessionId, stepCounter, userMessage, chatClient, rigorous);
    }

    /**
     * Cache key for the DYNAMIC cache only - each tenant's dynamic MCP registry (per-tenant clients
     * in {@link DynamicMcpConnectionManager}) is independent; the static cache is globally keyed
     * instead, see {@link #STATIC_MCP_CACHE_KEY}.
     */
    private static String tenantCacheKey() {
        return TenantContext.current().map(TenantContext::getTenantId).orElse("__no-tenant__");
    }

    /**
     * 2026-08-19 MCP-blackout follow-up sweep: surfaces a static MCP server's discovery failure on
     * the admin Issues portal, mirroring {@link #recordToolCollisionIssue}'s fail-open posture
     * exactly - the less-severe name-collision case already got an issue row while the
     * blackout-class failure got only a WARN. {@code sourceId} is the server name so repeated
     * failures of the same server dedupe onto one recurring issue row instead of flooding the
     * portal.
     */
    private void recordDiscoveryFailureIssue(String serverName, Exception ex) {
        PlatformIssueService service = issueService.getIfAvailable();
        if (service == null) {
            return;
        }
        try {
            service.record(
                    IssueSource.TOOL_CALL,
                    serverName == null ? "static-mcp" : serverName,
                    IssueSeverity.WARNING,
                    "MCP tool discovery failed for static server '"
                            + (serverName == null ? "unknown" : serverName)
                            + "'",
                    "Tool discovery for this static MCP server failed, so its tools were unavailable to agents for this "
                            + "discovery window (other servers' tools were unaffected): "
                            + ex);
        } catch (RuntimeException recordingFailure) {
            log.warn(
                    "Failed to record platform issue for MCP discovery failure of '{}'",
                    serverName,
                    recordingFailure);
        }
    }

    List<ToolCallback> resolveMcpTools(
            UUID agentId,
            String sessionId,
            AtomicInteger stepCounter,
            String userMessage,
            ChatClient chatClient,
            boolean rigorous) {
        List<ToolCallback> tools = new ArrayList<>();
        tools.addAll(
                resolveStaticMcpTools(
                        agentId, sessionId, stepCounter, userMessage, chatClient, rigorous));
        tools.addAll(
                resolveDynamicMcpTools(
                        agentId, sessionId, stepCounter, userMessage, chatClient, rigorous));
        return sortedByName(tools);
    }

    /**
     * Cache hygiene (gap-analysis item 6): MCP discovery order is not stable across calls (server
     * connect order, cache refresh, a slow server answering last), and the tool list is part of the
     * provider's cached prompt prefix - a reordered list is a different prefix and a full cache
     * miss. Sorting by tool name (stable, null-safe) makes the order a pure function of the SET of
     * tools.
     */
    static List<ToolCallback> sortedByName(List<ToolCallback> tools) {
        List<ToolCallback> sorted = new ArrayList<>(tools);
        sorted.sort(java.util.Comparator.comparing(AgentMcpToolResolver::toolNameOrEmpty));
        return sorted;
    }

    private static String toolNameOrEmpty(ToolCallback callback) {
        try {
            String name = callback.getToolDefinition().name();
            return name == null ? "" : name;
        } catch (RuntimeException e) {
            return "";
        }
    }

    /**
     * The pre-#424 path: tools from servers wired in application-mcp.yml's static {@code
     * spring.ai.mcp.client.stdio.connections} map - restricted to the servers {@link
     * StaticMcpExposurePolicy} allows (stateless allowlist by default).
     */
    List<ToolCallback> resolveStaticMcpTools(
            UUID agentId,
            String sessionId,
            AtomicInteger stepCounter,
            String userMessage,
            ChatClient chatClient,
            boolean rigorous) {
        ToolCallbackProvider provider = mcpToolCallbackProvider.getIfAvailable();
        if (provider == null || !mcpRegistryService.anyServerEnabled()) {
            return List.of();
        }
        // getToolCallbacks() blocks on McpSyncClient.listTools() under the hood
        // (Spring AI's SyncMcpToolCallbackProvider); if an MCP server is slow,
        // unreachable, or still cold-starting its lazy-init handshake, this
        // throws (RuntimeException wrapping a TimeoutException) rather than
        // returning an empty list. Left unguarded, that exception propagates
        // all the way up through resolveToolsFor() and crashes the ENTIRE chat
        // request for every agent - not just ones that would have used the
        // slow MCP server's tools. application-mcp.yml already documents this
        // exact failure mode at startup (fixed there via lazy init + longer
        // request-timeout); this is the same class of bug surfacing at runtime
        // on first use instead, and needs the same "one flaky subprocess must
        // never take down more than its own feature" treatment: degrade to no
        // MCP tools this turn rather than fail the whole call.
        //
        // #595 follow-up ("quick task is taking too much time to understand
        // the request") - this live round trip is exactly what was making
        // every debounced preview call (and every real chat turn) slow;
        // cachedRawToolCallbacks skips it entirely within
        // mcpDiscoveryCacheTtl of the last successful discovery for this
        // tenant. See that field's javadoc for the staleness trade-off.
        //
        // 2026-09-28 cross-tenant leak fix: every static client is ONE process shared by all
        // tenants, so stateful ones (filesystem's shared root, memory's shared graph, the
        // browser servers' shared sessions) are dropped here unless they are on the stateless
        // allowlist or the platform shared-stateful flag is on - see StaticMcpExposurePolicy.
        // Filtered BEFORE discovery, so a hidden lazy client is never initialized/spawned.
        StaticMcpExposurePolicy policy = exposurePolicy.get();
        boolean sharedStateful = policy.sharedStatefulEnabled();
        List<McpSyncClient> clients =
                policy.exposedClients(
                        staticMcpSyncClients.getIfAvailable(List::of), sharedStateful);
        if (clients.isEmpty()) {
            return List.of();
        }
        String cacheKey =
                sharedStateful ? STATIC_MCP_CACHE_KEY_SHARED_STATEFUL : STATIC_MCP_CACHE_KEY;
        List<ToolCallback> rawCallbacks =
                computeCached(
                        staticMcpToolCache,
                        cacheKey,
                        mcpDiscoveryCacheTtl,
                        () -> {
                            try {
                                List<ToolCallback> discovered = new ArrayList<>();
                                for (McpSyncClient client : clients) {
                                    // One connection's own tool list, resolved separately per
                                    // client (same
                                    // pattern DynamicMcpConnectionManager#connect uses for its
                                    // single client) -
                                    // NOT provider.getToolCallbacks(), which aggregates every
                                    // static server into
                                    // one bare-named array with no way to recover which server a
                                    // given callback
                                    // came from. connection name lives on the client's own
                                    // clientInfo, set by
                                    // Spring AI's McpClientAutoConfiguration#mcpSyncClients as the
                                    // connection's
                                    // application-mcp.yml map key (see field javadoc above).
                                    String serverName =
                                            client.getClientInfo() == null
                                                    ? null
                                                    : client.getClientInfo().title();
                                    // Per-client isolation (2026-08-19 incident): this try used to
                                    // sit OUTSIDE
                                    // the loop, so ONE server failing lazy-init (puppeteer's cold
                                    // npx run blew
                                    // the 60s request-timeout) blanked the tools of every healthy
                                    // server too -
                                    // memory, sequential-thinking, filesystem AND playwright-vision
                                    // all
                                    // vanished from the agent edit page and every turn.
                                    // application-mcp.yml's
                                    // fetch/time incident note warned about exactly this coupling
                                    // at the
                                    // SyncMcpToolCallbackProvider level; isolating per client here
                                    // is the code
                                    // half of that fix. A failing server now loses only its own
                                    // tools until a
                                    // later cache-expiry retry finds it recovered.
                                    ToolCallback[] fetched;
                                    try {
                                        fetched = fetchStaticCallbacks(client, serverName);
                                    } catch (Exception ex) {
                                        log.warn(
                                                "MCP tool discovery failed for static server '{}' (agent {}) - continuing with the other servers' tools this turn",
                                                serverName,
                                                agentId,
                                                ex);
                                        // 2026-08-19 MCP-blackout follow-up sweep: make the failure
                                        // alertable
                                        // (bounded tags - server names come from the yml server
                                        // list, never
                                        // agentId/tenant) and visible on the admin Issues portal,
                                        // not just a
                                        // WARN line an operator has to already be watching for.
                                        meterRegistry
                                                .counter(
                                                        "mcp.discovery.failure",
                                                        "source",
                                                        "static",
                                                        "server",
                                                        serverName == null ? "unknown" : serverName)
                                                .increment();
                                        recordDiscoveryFailureIssue(serverName, ex);
                                        continue;
                                    }
                                    if (fetched == null) {
                                        continue;
                                    }
                                    discovered.addAll(wrapStaticCallbacks(fetched, serverName));
                                }
                                return discovered;
                            } catch (Exception ex) {
                                log.warn(
                                        "MCP tool discovery failed for agent {} - continuing without MCP tools this turn",
                                        agentId,
                                        ex);
                                meterRegistry
                                        .counter(
                                                "mcp.discovery.failure",
                                                "source",
                                                "static",
                                                "server",
                                                "__all__")
                                        .increment();
                                return List.of();
                            }
                        });
        return rawCallbacks.stream()
                .map(
                        callback ->
                                guardAndLog(
                                        callback,
                                        agentId,
                                        sessionId,
                                        stepCounter,
                                        userMessage,
                                        chatClient,
                                        rigorous))
                .toList();
    }

    /**
     * One static client's callbacks. With {@code app.tools.mcp-annotations.enabled} the discovery
     * also records each tool's MCP annotations (via the provider's own tool filter, so there is no
     * extra {@code tools/list} round trip) under the global scope - static servers are shared by
     * every tenant. With the flag off, or no classifier bean, this is exactly {@link
     * McpToolCallbacks#discover}.
     */
    private ToolCallback[] fetchStaticCallbacks(McpSyncClient client, String serverName) {
        DynamicMcpConnectionManager manager = dynamicMcpConnectionManager.getIfAvailable();
        com.agenticai.core.agents.tools.classification.McpAnnotationClassifier classifier =
                manager == null ? null : manager.annotationClassifier();
        return fetchStaticCallbacks(client, serverName, classifier);
    }

    /**
     * Seam for tests: discovery for one client with an explicit classifier. The flag-on branch
     * keeps the legacy constructor's {@code McpToolNamePrefixGenerator.noPrefix()} so tool names
     * are byte-identical to the flag-off path (the builder's default generator would rewrite '-' to
     * '_' and strip other characters, which would break stored classifications, allowedToolNames
     * and the captured hint key).
     */
    static ToolCallback[] fetchStaticCallbacks(
            McpSyncClient client,
            String serverName,
            com.agenticai.core.agents.tools.classification.McpAnnotationClassifier classifier) {
        if (classifier == null || !classifier.isEnabled()) {
            return McpToolCallbacks.discover(client);
        }
        List<io.modelcontextprotocol.spec.McpSchema.Tool> seen =
                new java.util.concurrent.CopyOnWriteArrayList<>();
        ToolCallback[] callbacks =
                SyncMcpToolCallbackProvider.builder()
                        .mcpClients(client)
                        .toolNamePrefixGenerator(
                                org.springframework.ai.mcp.McpToolNamePrefixGenerator.noPrefix())
                        .toolFilter(
                                (connectionInfo, tool) -> {
                                    seen.add(tool);
                                    return true;
                                })
                        .build()
                        .getToolCallbacks();
        McpAnnotationCapture.capture(
                classifier,
                com.agenticai.core.agents.tools.classification.McpAnnotationClassifier.GLOBAL_SCOPE,
                serverName,
                seen);
        return callbacks;
    }

    /**
     * #424 - "how client can configure and use it during runtime": tools from servers an admin
     * registered with their own command/args/API key through the Tools UI, connected live by {@link
     * DynamicMcpConnectionManager} - no YAML edit, no restart. Entirely independent of {@link
     * #resolveStaticMcpTools}'s gate (a tenant can have zero YAML-wired servers and still get tools
     * here, or both at once); {@code resolveDynamicToolCallbacks()} already guarantees the same
     * fail-open contract (empty, never throws) this class's other optional sources rely on.
     */
    List<ToolCallback> resolveDynamicMcpTools(
            UUID agentId,
            String sessionId,
            AtomicInteger stepCounter,
            String userMessage,
            ChatClient chatClient,
            boolean rigorous) {
        DynamicMcpConnectionManager manager = dynamicMcpConnectionManager.getIfAvailable();
        if (manager == null) {
            return List.of();
        }
        // #595 follow-up - same live-round-trip-on-every-call cost as
        // resolveStaticMcpTools above, now cached the same way (per tenant here,
        // unlike the static cache's global key - see computeCached's javadoc).
        List<ToolCallback> rawCallbacks =
                computeCached(
                        dynamicMcpToolCache,
                        tenantCacheKey(),
                        mcpDiscoveryCacheTtl,
                        manager::resolveDynamicToolCallbacks);
        return rawCallbacks.stream()
                .map(
                        callback ->
                                guardAndLog(
                                        callback,
                                        agentId,
                                        sessionId,
                                        stepCounter,
                                        userMessage,
                                        chatClient,
                                        rigorous))
                .toList();
    }

    void evictCache() {
        // The static entry is globally keyed (see STATIC_MCP_CACHE_KEY's javadoc) - a static
        // toggle by any tenant refreshes the shared entry for all, at worst one extra
        // 20s-cached rediscovery; the dynamic entry stays per-tenant, same TenantContext
        // requirement tenantCacheKey() always had.
        staticMcpToolCache.remove(STATIC_MCP_CACHE_KEY);
        staticMcpToolCache.remove(STATIC_MCP_CACHE_KEY_SHARED_STATEFUL);
        dynamicMcpToolCache.remove(tenantCacheKey());
    }
}
