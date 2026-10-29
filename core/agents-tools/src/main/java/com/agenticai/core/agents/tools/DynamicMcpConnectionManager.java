package com.agenticai.core.agents.tools;

import com.agenticai.core.agents.attestation.AgentAttestationService;
import com.agenticai.core.agents.tools.mcpregistry.McpOwnershipGate;
import com.agenticai.core.mcp.McpCatalogService;
import com.agenticai.core.mcp.McpDynamicConnector;
import com.agenticai.core.mcp.McpRegistryService;
import com.agenticai.core.mcp.McpRemoteUrlGuard;
import com.agenticai.core.mcp.McpStdioCommandGuard;
import com.agenticai.core.mcp.atlassian.AtlassianOAuthService;
import com.agenticai.core.mcp.dto.McpConnectionTestResult;
import com.agenticai.core.mcp.dto.McpDynamicConfig;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.tenancy.AllowUnlockedSchedule;
import com.agenticai.core.tenancy.TenantSchemaIterator;
import io.micrometer.core.instrument.MeterRegistry;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.spec.McpClientTransport;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The real, live-connection half of a dynamic MCP registration (#424) - implements {@link
 * McpDynamicConnector}, which {@code McpRegistryController} (core/mcp) calls through an {@code
 * ObjectProvider} so that module never needs a hard dependency on the MCP client SDK (see that
 * interface's javadoc). This is the one class in the whole feature that actually talks the MCP
 * client protocol - everything upstream of it (entity, DTOs, service, controller) only ever moves
 * configuration around.
 *
 * <p><b>Honest caveat on the raw SDK call this class makes:</b> this codebase pins {@code
 * spring-ai.version=1.1.7} (core/pom.xml), which transitively bundles a specific version of the
 * {@code io.modelcontextprotocol.sdk:mcp} Java SDK. A runtime log line elsewhere in this app
 * (logger {@code i.m.c.transport.StdioClientTransport}) confirms the class actually loaded at
 * runtime is {@code io.modelcontextprotocol.client.transport.StdioClientTransport} - so the package
 * used below is verified. What could NOT be verified in this build environment (no {@code mvn}, no
 * reachable {@code .m2} cache) is whether that class's constructor takes one argument ({@code
 * ServerParameters}) or two ({@code ServerParameters}, {@code ObjectMapper}) in this exact SDK
 * version - both shapes exist across different SDK releases. {@link #buildStdioTransport} resolves
 * this via reflection instead of a hardcoded compile-time call, specifically so a wrong guess about
 * arity can never fail the whole module's compilation - it just means the reflective lookup finds
 * nothing and {@link #connect} records a clear {@code ERROR} status instead. Recommend a local
 * {@code mvn -pl mcp,agents -am compile} to confirm and, if desired, replace the reflection with a
 * direct call once confirmed.
 *
 * <p>Every other SDK call here ({@code ServerParameters.builder(...)}, {@code
 * McpClient.sync(...).requestTimeout(...).build()}, {@code client.initialize()}, {@code
 * client.listTools()}, {@code SyncMcpToolCallbackProvider}) is used the same way across the
 * SDK/Spring AI doc versions checked during design, so those are called directly - the outer {@code
 * try/catch(Throwable)} in {@link #connect} is still the backstop if any of those assumptions turn
 * out wrong too.
 */
@Component
public class DynamicMcpConnectionManager implements McpDynamicConnector {

    private static final Logger log = LoggerFactory.getLogger(DynamicMcpConnectionManager.class);
    /**
     * The client-side wait for one request to a connected MCP server and for the "test connection" action
     * ({@code app.tools.mcp.*}). Setter-injected so hand-built managers keep the shipped defaults.
     */
    private volatile McpClientProperties clientProperties = new McpClientProperties();

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void useClientProperties(McpClientProperties properties) {
        this.clientProperties = properties;
    }

    Duration requestTimeout() {
        return clientProperties.requestTimeout();
    }

    Duration testTimeout() {
        return clientProperties.testTimeout();
    }

    // Master Roadmap 2026 Section9.3 ("connection health checks") - once a
    // registration hit CONNECTED, nothing re-verified it was still alive:
    // reconnectEnabledOnStartup only ran on app restart, and otherwise an
    // admin had to notice a broken tool and hit "try again" manually. Five
    // minutes is deliberately not aggressive - a subprocess dying is rare,
    // and each check is a real round-trip against a possibly-slow/hung
    // client (up to the request timeout, app.tools.mcp.request-timeout-seconds) - see application.yml's scheduling
    // pool-size comment for why this needed its own scheduler thread
    // separate from WorkflowScheduler's once-a-minute tick.
    private static final long HEALTH_CHECK_INTERVAL_MS = 5 * 60 * 1000L;

    // Bounded auto-retry with backoff (2026-08-05) - same 5-minute cadence as
    // the health check above; the ACTUAL delay between two attempts for one
    // registration is governed by McpServerRegistration#backoffFor (5m/15m/
    // 1h/6h steady-state), not by this constant - this is just how often the
    // job checks whether anything is DUE, same "cheap tick, real interval
    // lives in the data" shape healthCheckLiveConnections already uses.
    private static final long RETRY_CHECK_INTERVAL_MS = 5 * 60 * 1000L;

    /** Disk-hygiene cadence for {@link #cleanupOrphanedPackageCaches} - hourly is plenty. */
    private static final long CACHE_CLEANUP_INTERVAL_MS = 60 * 60 * 1000L;

    private final McpRegistryService registryService;
    private final TenantSchemaIterator tenantSchemaIterator;
    // MCP-DOCTOR hardening (2026-07-25) - connect/spawn-time halves of the
    // SSRF guard + launcher allowlist (McpRegistryService applies the same
    // guards at save time; re-checking here means a row that predates the
    // guards, or was written around the service, still can't connect).
    private final McpRemoteUrlGuard remoteUrlGuard;
    private final McpStdioCommandGuard stdioCommandGuard;
    // MCP-SELF-HEAL (2026-07-26): turns a failed STDIO connection's captured
    // stderr into a proposed, guard-validated correction - see its own
    // javadoc. Only ever consulted from test()'s catch block (the "Try
    // connecting again" flow); never applies anything itself.
    private final McpStdioSelfHealer selfHealer;
    // MCP-STDIO-SANDBOXING (2026-08-05) - required-env-var lookup for a
    // catalog-sourced STDIO registration, used only to NAME a specific
    // missing var in a timeout diagnosis (see missingRequiredEnvVars below).
    // Not involved in the review gate itself (McpRegistryService already
    // computed reviewStatus at register() time and persisted it - see
    // McpDynamicConfig#reviewStatus).
    private final McpCatalogService catalogService;
    // Per-tenant fork-bomb bound: max concurrent live STDIO subprocesses.
    private final int maxStdioConnectionsPerTenant;
    // MCP-STDIO-SANDBOXING (2026-08-05) - master switch + address/secret for
    // routing STDIO spawns through the mcp-stdio-runner sidecar instead of
    // spawning the real interpreter/package directly in this container. See
    // wrapForSidecar's javadoc.
    private final McpStdioSidecarProperties sidecarProperties;
    // Per-tenant STDIO subprocess bound (live registrations + in-flight /test spawns).
    private final McpStdioSlots stdioSlots;
    // Last stderr tail per registration attempt - evidence for the Connection
    // Doctor; only ever holds the CURRENT/most-recent attempt's bounded tail.
    private final Map<UUID, StderrTailBuffer> stderrTails = new ConcurrentHashMap<>();

    // #596 fix ("MCP servers are enabled but Quick Task never detects their
    // tools"): these were previously flat Map<UUID, ...> - a SINGLE
    // JVM-wide table shared by every tenant, keyed only by registration id.
    // Two bugs from that: (1) resolveDynamicToolCallbacks() returned every
    // tenant's live tools merged together (a real cross-tenant leak, the
    // same class of bug TenantSchemaIterator's javadoc calls out as
    // "found and fixed twice already in this project's history" - this
    // would have been a third), and (2) reconnectEnabledOnStartup() called
    // registryService.enabledDynamicRegistrations() directly from an
    // @EventListener(ApplicationReadyEvent) method - no HTTP request, no
    // JWT, no TenantContext set - so per TenantSchemaResolver's default
    // behavior that query only ever saw the (empty, for any real tenant)
    // public schema. A server an admin registers and enables through the
    // Tools UI DOES connect immediately (McpRegistryController.register()/
    // toggle() run on a real request thread with correct TenantContext), but
    // the live McpSyncClient/tool list are only ever held in this
    // in-process map - so the moment the app restarts, every tenant's
    // "enabled" servers show ENABLED in the DB (real, persisted) but have
    // zero live tools until reconnectEnabledOnStartup() rebuilds them - and
    // it never actually did that for a real tenant schema. Now keyed by
    // tenant schema first, registration id second, so each tenant only ever
    // sees its own live connections, and the startup reconnect explicitly
    // walks every real tenant schema via TenantSchemaIterator instead of
    // whatever schema (or lack of one) happens to be ambient at
    // ApplicationReadyEvent time.
    private final Map<String, Map<UUID, McpSyncClient>> liveClients = new ConcurrentHashMap<>();
    private final Map<String, Map<UUID, List<ToolCallback>>> liveToolCallbacks =
            new ConcurrentHashMap<>();

    // Bug fix (2026-08-06) - see #evictToolCache. ObjectProvider (deferred
    // lookup, same pattern AgentToolProvider itself already uses for ITS
    // ObjectProvider<DynamicMcpConnectionManager> dependency) specifically to
    // avoid an eager circular-bean-instantiation failure: AgentToolProvider
    // depends on this class, so this class cannot take a hard AgentToolProvider
    // constructor dependency back - both sides defer the lookup to first real
    // use instead of construction time.
    private final org.springframework.beans.factory.ObjectProvider<AgentToolProvider>
            agentToolProvider;

    // OAuth-backed remote connectors (2026-08-15) - core/agents already
    // hard-depends on core/mcp (see this class's own package-info reasoning
    // for the reverse ObjectProvider boundary McpDynamicConnector uses), so
    // this is a direct constructor dependency, not an ObjectProvider. See
    // remoteHeaders' oauthProvider branch.
    private final AtlassianOAuthService atlassianOAuthService;

    // 2026-08-19 MCP-blackout follow-up sweep: connection failures/losses previously had zero
    // metric signal (only log lines + the per-registration diagnosis row) - same micrometer
    // pattern AgentJobExecution already uses in this module.
    private final MeterRegistry meterRegistry;

    /**
     * KYA-style outbound attestation ({@link AgentAttestationService}) - ObjectProvider so slim
     * contexts and this class's unit tests construct without it. Minted PER REQUEST inside the
     * transport's {@code httpRequestCustomizer} (the JWT is short-lived; a connection outlives one
     * mint) - see {@link McpRemoteHeaders.PerRequestHeaders}.
     */
    private final org.springframework.beans.factory.ObjectProvider<AgentAttestationService>
            agentAttestation;

    // Extracted collaborators (plain objects built from this class's own dependencies, not beans).
    private final McpStdioParamsBuilder stdioParamsBuilder;
    private final McpPackageCacheJanitor packageCacheJanitor;
    // Seam collaborators (plain objects built here, not beans): see each class's javadoc.
    private final McpDiagnostics diagnostics;
    private final McpConnectionTester tester;
    private final McpConnectionGuards guards;
    private final McpConnectionLifecycle lifecycle;
    private final McpTransportFactory transports;
    private final McpToolCache toolCache;
    private final McpHealthMonitor healthMonitor;
    private final McpConnectionOpener connectionOpener;
    private volatile McpOwnershipGate ownership = McpOwnershipGate.NOOP;

    public DynamicMcpConnectionManager(
            McpRegistryService registryService,
            TenantSchemaIterator tenantSchemaIterator,
            McpRemoteUrlGuard remoteUrlGuard,
            McpStdioCommandGuard stdioCommandGuard,
            McpStdioSelfHealer selfHealer,
            McpCatalogService catalogService,
            @org.springframework.beans.factory.annotation.Value(
                            "${app.mcp.max-stdio-connections-per-tenant:5}")
                    int maxStdioConnectionsPerTenant,
            McpStdioSidecarProperties sidecarProperties,
            org.springframework.beans.factory.ObjectProvider<AgentToolProvider> agentToolProvider,
            AtlassianOAuthService atlassianOAuthService,
            MeterRegistry meterRegistry,
            org.springframework.beans.factory.ObjectProvider<AgentAttestationService>
                    agentAttestation,
            @org.springframework.beans.factory.annotation.Value("${app.mcp.package-cache-dir:}")
                    String packageCacheDir) {
        this.registryService = registryService;
        this.tenantSchemaIterator = tenantSchemaIterator;
        this.remoteUrlGuard = remoteUrlGuard;
        this.stdioCommandGuard = stdioCommandGuard;
        this.selfHealer = selfHealer;
        this.catalogService = catalogService;
        this.maxStdioConnectionsPerTenant = maxStdioConnectionsPerTenant;
        this.sidecarProperties = sidecarProperties;
        this.agentToolProvider = agentToolProvider;
        this.atlassianOAuthService = atlassianOAuthService;
        this.meterRegistry = meterRegistry;
        this.agentAttestation = agentAttestation;
        java.nio.file.Path base = McpStdioParamsBuilder.resolvePackageCacheDir(packageCacheDir);
        this.stdioParamsBuilder =
                new McpStdioParamsBuilder(stdioCommandGuard, sidecarProperties, base);
        this.packageCacheJanitor =
                new McpPackageCacheJanitor(registryService, tenantSchemaIterator, base);
        this.stdioSlots = new McpStdioSlots(maxStdioConnectionsPerTenant);
        this.guards = new McpConnectionGuards(registryService, remoteUrlGuard, stdioCommandGuard);
        this.diagnostics = new McpDiagnostics(catalogService);
        this.lifecycle =
                new McpConnectionLifecycle(registryService, meterRegistry, selfHealer, diagnostics);
        this.transports =
                new McpTransportFactory(
                        atlassianOAuthService,
                        agentAttestation,
                        () -> routing,
                        () -> providerTokens);
        this.tester =
                new McpConnectionTester(
                        registryService,
                        remoteUrlGuard,
                        stdioCommandGuard,
                        selfHealer,
                        stdioSlots,
                        lifecycle,
                        diagnostics,
                        () -> transports,
                        stdioParamsBuilder::stdioParamsFor,
                        DynamicMcpConnectionManager::tenantKey,
                        this::testTimeout);
        this.toolCache =
                new McpToolCache(
                        liveClients,
                        liveToolCallbacks,
                        client -> toolCallbackFetcher.apply(client),
                        this::routable,
                        this::evictToolCache);
        this.healthMonitor =
                new McpHealthMonitor(
                        tenantSchemaIterator,
                        registryService,
                        lifecycle,
                        () -> ownership,
                        liveClients,
                        this::connect,
                        this::disconnectLocal);
        this.connectionOpener =
                new McpConnectionOpener(
                        this,
                        registryService,
                        guards,
                        stdioSlots,
                        maxStdioConnectionsPerTenant,
                        transports,
                        stderrTails,
                        liveClients,
                        liveToolCallbacks,
                        toolCache,
                        lifecycle,
                        this::requestTimeout);
    }

    private volatile McpToolPinGate toolPins;
    private volatile McpRoutingHeaders routing = McpRoutingHeaders.OFF;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setRoutingHeaders(McpRoutingHeaders routing) {
        this.routing = routing;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setToolPinGate(McpToolPinGate gate) {
        this.toolPins = gate;
    }

    @org.springframework.beans.factory.annotation.Autowired(
            required = false) // replica-safe registry; NOOP unless app.mcp.registry-store=redis
    void setOwnershipGate(McpOwnershipGate gate) {
        this.ownership = gate;
        gate.attach(this, (t, id) -> liveClients.getOrDefault(t, Map.of()).containsKey(id));
        gate.attachToolCaller(this::callLiveTool);
    }

    /**
     * Owner side of a routed tool call (app.mcp.registry-route-tool-calls): runs {@code toolName}
     * (the namespaced name the model saw) on this JVM's live callback for the registration.
     */
    String callLiveTool(String tenant, UUID registrationId, String toolName, String argsJson) {
        return toolCache.callLiveTool(tenant, registrationId, toolName, argsJson);
    }

    // 2026-09-28 cross-tenant leak fix: carried here (setter, not constructor - the constructor
    // signature is shared with tests and callers) purely so AgentMcpToolResolver, which is not a
    // bean and already holds an ObjectProvider to this class, can reach the policy bean.
    private volatile StaticMcpExposurePolicy staticExposurePolicy;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setStaticExposurePolicy(StaticMcpExposurePolicy policy) {
        this.staticExposurePolicy = policy;
    }

    // Provider-registry OAuth (oauth:<id> registrations): setter-injected, optional - same
    // reasoning as staticExposurePolicy above (constructor signature is shared with tests).
    // Null = Atlassian-only behaviour.
    private volatile com.agenticai.core.mcp.oauth.McpOAuthTokenSource providerTokens;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setProviderTokenSource(com.agenticai.core.mcp.oauth.McpOAuthTokenSource source) {
        this.providerTokens = source;
    }

    /**
     * Null when no policy bean exists - callers fall back to {@link
     * StaticMcpExposurePolicy#FAIL_CLOSED}.
     */
    StaticMcpExposurePolicy staticExposurePolicy() {
        return staticExposurePolicy;
    }

    // MCP tool annotations (app.tools.mcp-annotations.enabled) - setter for the same reason as the
    // policy above; AgentMcpToolResolver reaches it through this bean for the static-server path.
    private volatile com.agenticai.core.agents.tools.classification.McpAnnotationClassifier
            annotationClassifier;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setAnnotationClassifier(
            com.agenticai.core.agents.tools.classification.McpAnnotationClassifier classifier) {
        this.annotationClassifier = classifier;
    }

    // MCP Apps (app.mcp.apps.enabled, default off): lazy provider (McpAppsService depends back on
    // this class) - the
    // bean only exists when the flag is on, so with it off mcpApps() is null and every capture call
    // is a no-op.
    private volatile org.springframework.beans.factory.ObjectProvider<
                    com.agenticai.core.agents.tools.mcpapps.McpAppsService>
            mcpAppsProvider;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setMcpApps(
            org.springframework.beans.factory.ObjectProvider<
                            com.agenticai.core.agents.tools.mcpapps.McpAppsService>
                    provider) {
        this.mcpAppsProvider = provider;
    }

    com.agenticai.core.agents.tools.mcpapps.McpAppsService mcpApps() {
        org.springframework.beans.factory.ObjectProvider<
                        com.agenticai.core.agents.tools.mcpapps.McpAppsService>
                p = mcpAppsProvider;
        return p == null ? null : p.getIfAvailable();
    }

    /**
     * The live, authenticated client of one of THIS tenant's connected registrations, or null. Used
     * only by MCP Apps to run {@code resources/read} over the same connection that served the tool;
     * keyed by tenant first, so another tenant's connection is unreachable by construction.
     */
    public McpSyncClient liveClient(String tenant, UUID registrationId) {
        return toolCache.liveClient(tenant, registrationId);
    }

    /**
     * Null when no classifier bean exists; a present one may still be disabled (check {@code
     * isEnabled()}).
     */
    com.agenticai.core.agents.tools.classification.McpAnnotationClassifier annotationClassifier() {
        return annotationClassifier;
    }

    /**
     * Bug fix (2026-08-06) - see {@link McpDynamicConnector#evictToolCache}'s javadoc for the full
     * root-cause story. Never throws: no {@code AgentToolProvider} bean available (a degraded/slim
     * context) is a no-op, not an error - this method exists purely to shorten staleness, never to
     * gate correctness.
     */
    @Override
    public void evictToolCache() {
        AgentToolProvider provider = agentToolProvider.getIfAvailable();
        if (provider != null) {
            provider.evictMcpToolCache();
        }
    }

    /**
     * Same "no ambient TenantContext during background/system work" rule every other tenant-scoped
     * helper in this class follows.
     */
    private static String tenantKey() {
        return TenantContext.current().map(TenantContext::getTenantId).orElse("__no-tenant__");
    }

    /** Invoked by {@link DynamicMcpSchedulers} on {@link ApplicationReadyEvent}. */
    public void reconnectEnabledOnStartup() {
        healthMonitor.reconnectEnabledOnStartup();
    }

    /** Periodic liveness re-check, scheduled by {@link DynamicMcpSchedulers}. */
    @AllowUnlockedSchedule(
            reason =
                    "Pings this JVM's own liveClients (subprocess/HTTP clients are per-JVM); see the javadoc above.")
    @Scheduled(fixedRate = HEALTH_CHECK_INTERVAL_MS)
    public void healthCheckLiveConnections() {
        healthMonitor.healthCheckLiveConnections();
    }

    /** Bounded auto-retry with backoff, scheduled by {@link DynamicMcpSchedulers}. */
    @AllowUnlockedSchedule(
            reason =
                    "Each replica rebuilds its own per-JVM client after an error; the Redis ownership lease (app.mcp.registry-store=redis) already restricts the retry counter to the coordinator.")
    @Scheduled(fixedRate = RETRY_CHECK_INTERVAL_MS)
    public void retryErroredConnections() {
        healthMonitor.retryErroredConnections();
    }

    @Override
    public void connect(UUID registrationId) {
        if (ownership.routeConnect(tenantKey(), registrationId)) {
            return;
        }
        connectionOpener.connect(tenantKey(), registrationId);
    }

    @Override
    public void disconnect(UUID registrationId) {
        ownership.broadcastDisconnect(tenantKey(), registrationId);
        disconnectLocal(registrationId);
    }

    void disconnectLocal(UUID registrationId) {
        connectionOpener.disconnectLocal(tenantKey(), registrationId);
    }

    /**
     * Discovery seam: one client's raw Spring AI callbacks (a live {@code tools/list} round trip).
     * A field, not a hard-coded call, purely so tests can stub discovery without a real server -
     * same testability posture as {@link #redactSecrets}/{@link #filterUsable}.
     */
    java.util.function.Function<McpSyncClient, ToolCallback[]> toolCallbackFetcher =
            client -> McpToolCallbacks.discover(client);

    /** See {@link McpToolCache#wrapDynamicCallbacks}. */
    static List<ToolCallback> wrapDynamicCallbacks(ToolCallback[] raw, String serverName) {
        return McpToolCache.wrapDynamicCallbacks(raw, serverName);
    }

    /**
     * Outermost wrap: lets a callback hand its call to the owner replica when routing is enabled
     * (pass-through otherwise).
     */
    List<ToolCallback> routable(List<ToolCallback> wrapped, String tenant, UUID registrationId) {
        if (toolPins != null) {
            toolPins.observe(tenant, registrationId, wrapped);
        }
        return wrapped.stream()
                .map(
                        cb ->
                                (ToolCallback)
                                        new RoutedMcpToolCallback(
                                                cb, tenant, registrationId, () -> ownership))
                .toList();
    }

    /** See {@link McpToolCache#onToolsListChanged}; package-private for tests. */
    void onToolsListChanged(
            String tenant, UUID registrationId, McpSyncClient client, String serverName) {
        toolCache.onToolsListChanged(tenant, registrationId, client, serverName);
    }

    /**
     * Every tool callback across all currently-live dynamic connections FOR THE CURRENT TENANT ONLY
     * (see the {@code liveClients}/{@code liveToolCallbacks} field javadoc for the #596
     * cross-tenant leak this closes) - what {@code AgentToolProvider} merges alongside the
     * statically YAML-wired tools. Always returns a (possibly empty) list, same fail-open contract
     * as {@code AgentToolProvider.resolveMcpTools}: no live dynamic connections for this tenant is
     * not an error, just nothing to add.
     */
    public List<ToolCallback> resolveDynamicToolCallbacks() {
        return connectionOpener.resolveUsable(tenantKey(), toolPins, false);
    }

    /**
     * Edge tool-access consent system (2026-08-06) - the admin/platform tier (layer 1 of 2) of
     * {@code EdgeToolExecutionController}'s resolution path. Identical shape to {@link
     * #resolveDynamicToolCallbacks} except the id set it intersects against is {@code
     * McpRegistryService#edgeEligibleDynamicRegistrationIds} (usable AND tenant-admin-marked
     * edge-eligible) instead of the plain usable set - so a registration that's perfectly fine for
     * a hosted turn still contributes ZERO tools here until an admin explicitly opts it in. Same
     * fail-closed contract on a registry read failure.
     */
    public List<ToolCallback> resolveDynamicToolCallbacksForEdge() {
        return connectionOpener.resolveUsable(tenantKey(), toolPins, true);
    }

    /**
     * Pure half of the readiness gate, split out for direct unit testing: only live callbacks whose
     * registration id is in {@code usable} are returned; everything else is skipped (debug-logged
     * so a "my server is enabled but the agent doesn't see its tools" report is diagnosable).
     */
    static List<ToolCallback> filterUsable(
            Map<UUID, List<ToolCallback>> liveByRegistration, Set<UUID> usable) {
        return McpToolCache.filterUsable(liveByRegistration, usable);
    }

    /** See {@link McpTransportFactory#buildRemoteTransport(McpDynamicConfig)}. */
    McpClientTransport buildRemoteTransport(McpDynamicConfig config) {
        return transports.buildRemoteTransport(config);
    }

    /** Test seam: see {@link McpTransportFactory#setClientBuilders}. */
    void setRemoteClientBuilders(
            java.util.function.Supplier<java.net.http.HttpClient.Builder> builders) {
        transports.setClientBuilders(builders);
    }

    /**
     * {@code app.mcp.max-response-bytes} -> the remote transports' heap ceiling (see {@link
     * McpTransportFactory#maxResponseBytes}). Setter-injected so the constructor keeps its arity,
     * the same seam this class uses for every other config-driven field on a manually-built
     * collaborator.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setMcpMaxResponseBytes(
            @org.springframework.beans.factory.annotation.Value(
                            "${app.mcp.max-response-bytes:33554432}")
                    long maxResponseBytes) {
        transports.setMaxResponseBytes(maxResponseBytes);
    }

    /** See {@link McpTransportFactory#remoteRequestHeaders}. */
    McpRemoteHeaders.PerRequestHeaders remoteRequestHeaders(McpDynamicConfig config) {
        return transports.remoteRequestHeaders(config);
    }

    McpClientTransport buildRemoteTransport(
            McpDynamicConfig config, McpRemoteHeaders.PerRequestHeaders requestHeaders) {
        return transports.buildRemoteTransport(config, requestHeaders);
    }

    /** See {@link McpTransportFactory#constructStdioTransport}. */
    McpClientTransport constructStdioTransport(ServerParameters params) {
        return transports.constructStdioTransport(params);
    }

    /** Reached reflectively by tests; see {@link McpTransportFactory#remoteHeaders}. */
    private Map<String, String> remoteHeaders(McpDynamicConfig config) {
        return transports.remoteHeaders(config);
    }

    ServerParameters stdioParamsFor(McpDynamicConfig config) {
        return stdioParamsBuilder.stdioParamsFor(config);
    }

    /** Scheduled by {@link DynamicMcpSchedulers}. */
    public void cleanupOrphanedPackageCaches() {
        packageCacheJanitor.cleanupOrphanedPackageCaches();
    }

    ServerParameters wrapForSidecar(
            String realCommand, List<String> realArgs, Map<String, String> realEnv) {
        return stdioParamsBuilder.wrapForSidecar(realCommand, realArgs, realEnv);
    }

    /**
     * MCP-DOCTOR: the real, bounded connection test behind {@code POST
     * /api/mcp/registrations/{id}/test}. Same guards, transport construction and diagnosis as
     * {@link #connect} but on a TRANSIENT client (shorter {@link McpConnectionTester#TEST_TIMEOUT},
     * always closed, never registered in {@code liveClients}), so testing never disturbs a live
     * connection. Outcome is persisted either way: pass -&gt; usageStatus READY, fail -&gt; FAILED
     * + structured diagnosis. Never throws.
     */
    @Override
    public McpConnectionTestResult test(UUID registrationId) {
        Optional<McpConnectionTestResult> routed = ownership.routeTest(tenantKey(), registrationId);
        if (routed.isPresent()) {
            return routed.get();
        }
        return tester.test(registrationId);
    }

    static List<String> redactSecretsIn(List<String> lines, Map<String, String> secretValues) {
        return McpSecretRedactor.redactSecretsIn(lines, secretValues);
    }

    static String redactSecrets(String message, Map<String, String> secretValues) {
        return McpSecretRedactor.redactSecrets(message, secretValues);
    }
}
