package com.agenticai.core.agents.tools;

import com.agenticai.core.mcp.McpConnectionPhase;
import com.agenticai.core.mcp.McpRegistryService;
import com.agenticai.core.mcp.dto.McpDynamicConfig;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpClientTransport;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;

/**
 * The bodies of {@link DynamicMcpConnectionManager#connect} (guards, transport, SDK handshake, tool
 * discovery and registration of one dynamic MCP connection, with the never-throws failure path),
 * of its local disconnect, and of its per-tenant usable-tool resolution.
 * A plain collaborator built by the manager (not a bean) and holding the manager's own live-client
 * tables, so behaviour is unchanged; split out only to keep each file reviewable. Logs under the
 * manager's logger so log routing and alerts keep matching.
 */
final class McpConnectionOpener {

    private static final Logger log = LoggerFactory.getLogger(DynamicMcpConnectionManager.class);

    private final DynamicMcpConnectionManager owner;
    private final McpRegistryService registryService;
    private final McpConnectionGuards guards;
    private final McpStdioSlots stdioSlots;
    private final int maxStdioConnectionsPerTenant;
    private final McpTransportFactory transports;
    private final Map<UUID, StderrTailBuffer> stderrTails;
    private final Map<String, Map<UUID, McpSyncClient>> liveClients;
    private final Map<String, Map<UUID, List<ToolCallback>>> liveToolCallbacks;
    private final McpToolCache toolCache;
    private final McpConnectionLifecycle lifecycle;
    private final java.util.function.Supplier<java.time.Duration> requestTimeout;

    McpConnectionOpener(
            DynamicMcpConnectionManager owner,
            McpRegistryService registryService,
            McpConnectionGuards guards,
            McpStdioSlots stdioSlots,
            int maxStdioConnectionsPerTenant,
            McpTransportFactory transports,
            Map<UUID, StderrTailBuffer> stderrTails,
            Map<String, Map<UUID, McpSyncClient>> liveClients,
            Map<String, Map<UUID, List<ToolCallback>>> liveToolCallbacks,
            McpToolCache toolCache,
            McpConnectionLifecycle lifecycle,
            java.util.function.Supplier<java.time.Duration> requestTimeout) {
        this.owner = owner;
        this.registryService = registryService;
        this.guards = guards;
        this.stdioSlots = stdioSlots;
        this.maxStdioConnectionsPerTenant = maxStdioConnectionsPerTenant;
        this.transports = transports;
        this.stderrTails = stderrTails;
        this.liveClients = liveClients;
        this.liveToolCallbacks = liveToolCallbacks;
        this.toolCache = toolCache;
        this.lifecycle = lifecycle;
        this.requestTimeout = requestTimeout;
    }

    /** Connects {@code registrationId} for {@code tenant}; never throws (see the manager). */
    void connect(String tenant, UUID registrationId) {
        // Everything below, including disconnect()/dynamicConfigFor()/markConnecting(), runs inside
        // this try/catch(Throwable): connect() never throws (McpDynamicConnector's contract), so a
        // registry DB failure ends as a markError call instead of a 500 from register()/toggle().
        McpDynamicConfig config = null;
        RemoteMcpPreProbe.ProbeResult probe = RemoteMcpPreProbe.ProbeResult.skipped();
        try {
            // Always start from a clean slate - reconnect (e.g. after an admin
            // fixes a bad API key and hits "try again") must never layer a new
            // client on top of a stale one.
            owner.disconnectLocal(registrationId);

            Optional<McpDynamicConfig> maybeConfig =
                    registryService.dynamicConfigFor(registrationId);
            if (maybeConfig.isEmpty()) {
                log.warn(
                        "connect({}) called but no dynamic config was found (not dynamic, or deleted) - skipping",
                        registrationId);
                return;
            }
            config = maybeConfig.get();

            // Decrypt-failure visibility (2026-08-05): persist what this fresh config resolved
            // (an empty env on a genuine decrypt failure); never blocks the connect.
            registryService.setCredentialsCorrupted(registrationId, config.credentialsCorrupted());

            // MCP-STDIO-SANDBOXING (2026-08-05) - the platform-staff approval
            // gate, checked FIRST, before any other guard: a PENDING/REJECTED
            // self-service STDIO registration must never reach a real
            // subprocess spawn, no matter what the launcher allowlist/SSRF
            // guard would otherwise allow. See McpReviewStatus's javadoc.
            if (guards.rejectIfReviewBlocked(registrationId, config)) {
                return;
            }

            // MCP-DOCTOR hardening - connect-time re-check of the save-time
            // guards (SSRF for remotes, launcher allowlist for stdio), plus
            // the per-tenant subprocess cap. Fail-closed, with a structured
            // diagnosis instead of ever attempting the connection.
            if (guards.rejectIfGuardBlocked(registrationId, config)) {
                return;
            }
            if (!config.isRemote() && stdioSlots.stdioCapReached(tenant, registrationId)) {
                registryService.markErrorDiagnosis(
                        registrationId,
                        "Connection not attempted: this workspace already has "
                                + maxStdioConnectionsPerTenant
                                + " running local MCP server(s) (the per-workspace limit).",
                        McpConnectionPhase.SPAWN,
                        "Disable another local (STDIO) MCP server first, or ask the operator to raise app.mcp.max-stdio-connections-per-tenant.");
                return;
            }

            registryService.markConnecting(registrationId);

            // Connection Doctor: for remotes, ONE best-effort HTTP probe before the SDK handshake,
            // purely so a failure has evidence (status, content type) - the SDK wraps every remote
            // failure in the same opaque message. Ignored on success; a probe failure still proceeds.
            if (config.isRemote()) {
                probe = RemoteMcpPreProbe.probe(config.url(), transports.remoteHeaders(config), config.type());
                // Pentest H2: rejectIfGuardBlocked's check above and the network probe
                // together can take several seconds - long enough for an attacker's DNS
                // record to flip from a public-looking address to a private/internal one
                // between "checked safe" and "actually connect". Re-resolve and re-check
                // immediately before the real SDK connect to shrink that window as much as
                // this code can (see McpRemoteUrlGuard#checkResolved's javadoc for why this
                // can't be reduced to zero without an IP-pinning hook the SDK doesn't expose).
                if (guards.rejectIfResolvedBlockedNow(registrationId, config)) {
                    return;
                }
            }

            // Two shapes of dynamic connection (both #424 + registry
            // integration): a local STDIO subprocess (command/args, the
            // original path) or a remote hosted endpoint (url, no subprocess -
            // registry servers whose only launch option is a hosted SSE /
            // streamable-HTTP URL). The rest of this method - initialize(),
            // listTools(), status tracking, the fail-safe catch - is identical
            // for both; only the transport differs.
            StderrTailBuffer stderrTail = new StderrTailBuffer();
            stderrTails.put(registrationId, stderrTail);
            McpClientTransport transport =
                    config.isRemote()
                            ? transports.buildRemoteTransport(config)
                            : transports.buildStdioTransport(
                                    owner.stdioParamsFor(config),
                                    config.name(),
                                    stderrTail,
                                    config.env() == null ? Map.of() : config.env());
            // tools/list_changed (2026-09-28): the SDK re-lists on the server's notification and
            // hands the new list here; the callbacks are rebuilt off-thread (see
            // onToolsListChanged). The holder is set right after build() - a notification can
            // only arrive after initialize(), which runs after that.
            java.util.concurrent.atomic.AtomicReference<McpSyncClient> clientRef =
                    new java.util.concurrent.atomic.AtomicReference<>();
            String changeServerName = config.name();
            McpSyncClient client =
                    McpClient.sync(transport)
                            .requestTimeout(requestTimeout.get())
                            .toolsChangeConsumer(
                                    tools -> {
                                        // The SDK hands over the new list, so re-record its
                                        // annotations without another round trip.
                                        McpAnnotationCapture.capture(
                                                owner.annotationClassifier(),
                                                tenant,
                                                changeServerName,
                                                tools);
                                        McpAppsCapture.capture(
                                                owner.mcpApps(),
                                                tenant,
                                                registrationId,
                                                changeServerName,
                                                tools);
                                        toolCache.scheduleToolsRefresh(
                                                tenant,
                                                registrationId,
                                                clientRef.get(),
                                                changeServerName);
                                    })
                            .build();
            clientRef.set(client);
            client.initialize();
            // Forces one real round-trip against the subprocess right now,
            // so "CONNECTED" means "actually answered", not just "process
            // spawned" - matching McpConnectionStatus's javadoc.
            var toolsResult = client.listTools();
            // Every advertised callback is wrapped with the discovery/result
            // caps (tool-description poisoning + output flooding defense) -
            // see SanitizedMcpToolCallback - and then namespaced under
            // mcp__<server>__<tool> (Pentest L4) so a dynamic server's tool
            // can never present under a bare name that shadows or merely
            // resembles a trusted built-in - see NamespacedMcpToolCallback's
            // javadoc, including its call-site compatibility note.
            // config is reassigned earlier in this method (declared null, set
            // once maybeConfig resolves) so it isn't effectively final for the
            // lambdas below - capture the name in a fresh local that is.
            String connectedServerName = config.name();
            // MCP annotations (flag-gated no-op otherwise): the listTools() result above already
            // carries them.
            McpAnnotationCapture.capture(
                    owner.annotationClassifier(),
                    tenant,
                    connectedServerName,
                    toolsResult == null ? null : toolsResult.tools());
            McpAppsCapture.capture(
                    owner.mcpApps(),
                    tenant,
                    registrationId,
                    connectedServerName,
                    toolsResult == null ? null : toolsResult.tools());
            List<ToolCallback> callbacks =
                    owner.routable(
                            McpToolCache.wrapDynamicCallbacks(
                                    owner.toolCallbackFetcher.apply(client), connectedServerName),
                            tenant,
                            registrationId);

            liveClients
                    .computeIfAbsent(tenant, t -> new ConcurrentHashMap<>())
                    .put(registrationId, client);
            liveToolCallbacks
                    .computeIfAbsent(tenant, t -> new ConcurrentHashMap<>())
                    .put(registrationId, callbacks);
            if (!config.isRemote()) {
                stdioSlots.markLive(tenant, registrationId);
            }
            registryService.markConnected(registrationId);
            // Bug fix (2026-08-06): the single place EVERY dynamic-enable path
            // funnels through (register/toggle/connectNow/review-approve/
            // startup-reconnect/health-check-reconnect/auto-retry all call
            // this method) - so one eviction here covers all of them, instead
            // of needing a matching call at every caller.
            owner.evictToolCache();
            log.info(
                    "MCP dynamic connection '{}' ({}) connected for tenant '{}' - {} tool(s) advertised",
                    config.name(),
                    registrationId,
                    tenant,
                    toolsResult == null ? 0 : callbacks.size());
        } catch (Throwable t) {
            // Deliberately Throwable, not Exception - see class javadoc on
            // the unverified StdioClientTransport constructor arity. A
            // NoSuchMethodError/NoClassDefFoundError from a wrong SDK-API
            // assumption is a real possibility here, not just a
            // hypothetical, and this method is called synchronously from
            // McpRegistryController.register()/toggle() mid-HTTP-request -
            // it must never propagate and take the request (or, worse,
            // other registrations) down with it. config may still be null
            // here (e.g. disconnect() or dynamicConfigFor() itself is what
            // failed) - every use below tolerates that.
            String name = config == null ? "unknown" : config.name();
            log.error(
                    "MCP dynamic connection '{}' ({}) failed to connect for tenant '{}'",
                    name,
                    registrationId,
                    tenant,
                    t);
            Map<UUID, McpSyncClient> tenantClients = liveClients.get(tenant);
            if (tenantClients != null) {
                tenantClients.remove(registrationId);
            }
            Map<UUID, List<ToolCallback>> tenantTools = liveToolCallbacks.get(tenant);
            if (tenantTools != null) {
                tenantTools.remove(registrationId);
            }
            // Redacted structured diagnosis + failure metric + self-heal suggestion, persisted
            // without ever throwing out of connect() - see McpConnectionLifecycle.
            lifecycle.recordConnectFailure(
                    registrationId, config, probe, stderrTails.get(registrationId), t);
        }
    }

    /** Drops this JVM's live client and tools of one registration; see the manager. */
    void disconnectLocal(String tenant, UUID registrationId) {
        Map<UUID, List<ToolCallback>> tenantTools = liveToolCallbacks.get(tenant);
        if (tenantTools != null) {
            McpAnnotationCapture.forget(
                    owner.annotationClassifier(), tenant, tenantTools.remove(registrationId));
        }
        McpAppsCapture.forget(owner.mcpApps(), tenant, registrationId);
        stdioSlots.release(tenant, registrationId);
        stderrTails.remove(registrationId);
        Map<UUID, McpSyncClient> tenantClients = liveClients.get(tenant);
        McpSyncClient client = tenantClients == null ? null : tenantClients.remove(registrationId);
        // Bug fix (2026-08-06): unconditional, even when there was no live
        // client to close (this is also called as a "clean slate" first step
        // inside #connect, which itself evicts again on success - a harmless
        // redundant cache-map removal, not a correctness issue) - a real
        // standalone disable MUST make this registration's tools disappear
        // from the next agent turn immediately, not after mcpDiscoveryCacheTtl.
        owner.evictToolCache();
        if (client == null) {
            return;
        }
        try {
            client.closeGracefully();
        } catch (Throwable t) {
            log.warn(
                    "Error closing MCP dynamic connection {} - evicting anyway", registrationId, t);
        }
    }

    /**
     * Body of {@link DynamicMcpConnectionManager#resolveDynamicToolCallbacks} ({@code edge=false},
     * the usable set) and {@link DynamicMcpConnectionManager#resolveDynamicToolCallbacksForEdge}
     * ({@code edge=true}, the edge-eligible set); fail-closed on a registry read failure.
     */
    List<ToolCallback> resolveUsable(String tenant, McpToolPinGate toolPins, boolean edge) {
        Map<UUID, List<ToolCallback>> tenantTools =
                liveToolCallbacks.getOrDefault(tenant, Map.of());
        if (tenantTools.isEmpty()) {
            return List.of();
        }
        // MCP-DOCTOR readiness gate: an enabled-but-unverified (or
        // last-test-FAILED) registration contributes ZERO tools to the agent
        // loop, even while a live client exists - only READY (test passed)
        // or ACKNOWLEDGED (explicit admin override) registrations pass. This
        // is the GATE half of warn -> test -> gate; see McpUsageStatus.
        // Deliberately fail-CLOSED: if the registry can't be read, no
        // dynamic tools this turn rather than unverified ones. The edge set is
        // usable AND tenant-admin-marked edge-eligible.
        Set<UUID> usable;
        try {
            usable =
                    edge
                            ? registryService.edgeEligibleDynamicRegistrationIds()
                            : registryService.usableDynamicRegistrationIds();
        } catch (Throwable t) {
            log.warn(
                    edge
                            ? "Could not read MCP edge-eligibility gate - offering NO dynamic MCP tools to this edge session (fail-closed)"
                            : "Could not read MCP usage-status gate - offering NO dynamic MCP tools this turn (fail-closed)",
                    t);
            return List.of();
        }
        return DynamicMcpConnectionManager.filterUsable(
                tenantTools, toolPins == null ? usable : toolPins.allowed(usable));
    }
}
