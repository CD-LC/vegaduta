package com.agenticai.core.agents.demo.studio;

import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.agents.demo.studio.DemoStudioService.StudioException;
import com.agenticai.core.agents.tools.AgentToolPort;
import com.agenticai.core.agents.tools.ToolDescriptor;
import com.agenticai.core.mcp.McpConnectDispatcher;
import com.agenticai.core.mcp.McpDynamicConnector;
import com.agenticai.core.mcp.McpRegistryService;
import com.agenticai.core.mcp.McpServerType;
import com.agenticai.core.mcp.dto.McpServerRegistrationRequest;
import com.agenticai.core.mcp.dto.McpServerRegistrationResponse;
import com.agenticai.core.mcp.registry.OfficialMcpRegistryClient;
import com.agenticai.core.mcp.registry.RegistrySearchResponse;
import com.agenticai.core.mcp.registry.RegistrySearchResult;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.concurrent.TenantAwareExecutors;
import com.agenticai.core.tenancy.demo.DemoSession;
import com.agenticai.core.tenancy.demo.DemoSessionService;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * MCP connectors for /try Studio agents: search the public MCP registry, plug a remote server into
 * one agent, watch its tools appear in the trace.
 *
 * <p><b>What an anonymous visitor may and may not do.</b>
 *
 * <ul>
 *   <li>REMOTE servers only. STDIO servers spawn a subprocess on our infrastructure; they are never
 *       offered here, and a request naming one is refused.
 *   <li>Only servers that need NO credential. A visitor has no vault, and asking a stranger to
 *       paste an API key into a demo page is the wrong habit to teach.
 *   <li>The URL is never taken from the request. The client names a registry entry; the URL is
 *       re-read from the registry server-side, so a crafted request cannot point the platform at an
 *       arbitrary host. The registration then passes {@code McpRemoteUrlGuard} like any other.
 * </ul>
 *
 * <p><b>Binding to one agent.</b> MCP servers are tenant-wide and every enabled server's tools are
 * offered to every agent whose allow-list is empty (see {@code AgentMcpToolResolver}). Studio
 * agents always carry an explicit allow-list, and each server is registered as {@code
 * demo-<agent8>-<slug>} so its namespaced tool names ({@code mcp__demo-<agent8>-<slug>__<tool>})
 * can only be added to the agent whose id prefix they carry.
 *
 * <p>Connecting is OFF by default ({@code app.demo.studio.mcp-connectors-enabled}; the maintainer
 * switched it off 2026-09-26 after connects failed) because it registers a third-party endpoint in
 * the shared tenant; searching the registry is read-only public data and is always on.
 */
@Service
public class DemoStudioConnectorService {

    private static final Logger log = LoggerFactory.getLogger(DemoStudioConnectorService.class);

    /** Server-name prefix that ties a registration to one studio agent. */
    static final String SERVER_PREFIX = "demo-";

    private static final long CONNECT_WAIT_MS = 20_000;
    private static final long POLL_MS = 500;

    /**
     * Hard ceiling on the whole connect request: tool discovery also walks the platform's static
     * MCP servers, which can stall for a minute each.
     */
    private static final long CONNECT_HARD_CAP_SECONDS = 30;

    private final ExecutorService DISCOVERY = TenantAwareExecutors.newVirtualThreadPerTaskExecutor("demo-studio-connector-service-");

    private final ObjectProvider<OfficialMcpRegistryClient> registry;
    private final ObjectProvider<McpRegistryService> registrations;
    private final ObjectProvider<McpDynamicConnector> connector;
    private final ObjectProvider<McpConnectDispatcher> dispatcher;
    private final DemoStudioService studio;
    private final DemoSessionService demoSessionService;
    private final AgentService agentService;
    private final AgentToolPort toolProvider;
    private final boolean enabled;
    private final int maxPerAgent;
    private final DemoStudioModerationService moderation;

    public DemoStudioConnectorService(
            ObjectProvider<OfficialMcpRegistryClient> registry,
            ObjectProvider<McpRegistryService> registrations,
            ObjectProvider<McpDynamicConnector> connector,
            ObjectProvider<McpConnectDispatcher> dispatcher,
            DemoStudioService studio,
            DemoSessionService demoSessionService,
            AgentService agentService,
            AgentToolPort toolProvider,
            @Value("${app.demo.studio.mcp-connectors-enabled:false}") boolean enabled,
            @Value("${app.demo.studio.max-connectors-per-agent:3}") int maxPerAgent,
            DemoStudioModerationService moderation) {
        this.registry = registry;
        this.registrations = registrations;
        this.connector = connector;
        this.dispatcher = dispatcher;
        this.studio = studio;
        this.demoSessionService = demoSessionService;
        this.agentService = agentService;
        this.toolProvider = toolProvider;
        this.enabled = enabled;
        this.maxPerAgent = maxPerAgent;
        this.moderation = moderation;
    }

    public record RegistryHit(String name, String title, String description, String host) {}

    public record RegistryPage(
            List<RegistryHit> results,
            String nextCursor,
            boolean unavailable,
            boolean canConnect) {}

    public record ConnectorView(String server, String title, String host, List<String> tools) {}

    /**
     * Read-only public registry search, narrowed to what an anonymous sandbox can safely connect.
     */
    public RegistryPage search(DemoSession session, String query, String cursor) {
        moderation.check(session, java.util.Map.of("registry search", query == null ? "" : query));
        OfficialMcpRegistryClient client = registry.getIfAvailable();
        if (client == null) {
            return new RegistryPage(List.of(), null, true, enabled);
        }
        RegistrySearchResponse response = client.search(query == null ? "" : query.strip(), cursor);
        List<RegistryHit> hits = new ArrayList<>();
        for (RegistrySearchResult r : response.results()) {
            if (connectable(r)) {
                hits.add(
                        new RegistryHit(
                                r.name(),
                                r.displayName() == null || r.displayName().isBlank()
                                        ? r.name()
                                        : r.displayName(),
                                r.description(),
                                hostOf(r.url())));
            }
        }
        return new RegistryPage(hits, response.nextCursor(), response.unavailable(), enabled);
    }

    private static boolean connectable(RegistrySearchResult r) {
        if (r == null
                || r.type() == McpServerType.STDIO
                || !r.runnable()
                || r.url() == null
                || r.url().isBlank()) {
            return false;
        }
        if (!r.url().toLowerCase(Locale.ROOT).startsWith("https://")) {
            return false;
        }
        if (r.status() != null && !"active".equalsIgnoreCase(r.status())) {
            return false;
        }
        return r.envVars() == null
                || r.envVars().stream()
                        .noneMatch(
                                e ->
                                        e.required()
                                                && (e.defaultValue() == null
                                                        || e.defaultValue().isBlank()));
    }

    private static String hostOf(String url) {
        try {
            return java.net.URI.create(url).getHost();
        } catch (RuntimeException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------

    public List<ConnectorView> list(DemoSession session, UUID agentId) {
        DemoStudioService.Owned owned = studio.requireVisible(session, agentId);
        McpRegistryService service = registrations.getIfAvailable();
        if (service == null) {
            return List.of();
        }
        String prefix = serverPrefix(agentId);
        Set<String> allowed =
                owned.agent().getAllowedToolNames() == null
                        ? Set.of()
                        : owned.agent().getAllowedToolNames();
        return demoSessionService.inDemoTenantContext(
                session,
                () -> {
                    List<ConnectorView> out = new ArrayList<>();
                    for (McpServerRegistrationResponse r : service.listAll()) {
                        if (r.name().startsWith(prefix)) {
                            String toolPrefix = toolPrefix(r.name());
                            List<String> tools =
                                    allowed.stream()
                                            .filter(t -> t.startsWith(toolPrefix))
                                            .sorted()
                                            .toList();
                            out.add(
                                    new ConnectorView(
                                            r.name(), r.description(), hostOf(r.url()), tools));
                        }
                    }
                    return out;
                });
    }

    public ConnectorView connect(DemoSession session, UUID agentId, String registryName) {
        if (!enabled) {
            throw new StudioException(
                    "locked",
                    403,
                    "Connecting a live MCP server isn't switched on in this sandbox yet. You can browse the registry here, and connect from your own workspace.");
        }
        OfficialMcpRegistryClient client = registry.getIfAvailable();
        McpRegistryService service = registrations.getIfAvailable();
        McpDynamicConnector dynamic = connector.getIfAvailable();
        McpConnectDispatcher dispatch = dispatcher.getIfAvailable();
        if (client == null || service == null || dynamic == null || dispatch == null) {
            throw new StudioException(
                    "unavailable", 503, "MCP connectors aren't available in this environment.");
        }
        DemoStudioService.Owned owned = studio.requireVisible(session, agentId);
        if (registryName == null || registryName.isBlank() || registryName.length() > 200) {
            throw new StudioException("invalid", 400, "Pick a server from the search results.");
        }
        RegistrySearchResult found =
                client.search(registryName.strip(), null).results().stream()
                        .filter(r -> registryName.strip().equals(r.name()) && connectable(r))
                        .findFirst()
                        .orElseThrow(
                                () ->
                                        new StudioException(
                                                "invalid",
                                                400,
                                                "That server can't be connected from the sandbox - it needs a credential or isn't a hosted server."));

        String server = serverPrefix(agentId) + slug(found.name());
        String title =
                found.displayName() == null || found.displayName().isBlank()
                        ? found.name()
                        : found.displayName();

        return demoSessionService.inDemoTenantContextAs(
                session,
                owned.creator().getId(),
                () -> {
                    long already =
                            service.listAll().stream()
                                    .filter(r -> r.name().startsWith(serverPrefix(agentId)))
                                    .count();
                    boolean sameServer =
                            service.listAll().stream().anyMatch(r -> r.name().equals(server));
                    if (!sameServer && already >= maxPerAgent) {
                        throw new StudioException(
                                "connector_cap",
                                429,
                                "Each sandbox agent can hold "
                                        + maxPerAgent
                                        + " connectors. Remove one to add another.");
                    }
                    McpServerRegistrationResponse saved;
                    try {
                        saved =
                                service.register(
                                        new McpServerRegistrationRequest(
                                                server,
                                                found.type(),
                                                title,
                                                true,
                                                null,
                                                null,
                                                null,
                                                found.url()));
                    } catch (RuntimeException e) {
                        log.info(
                                "Studio MCP registration refused for {}: {}",
                                server,
                                e.getMessage());
                        throw new StudioException(
                                "locked", 403, "That server was refused: " + e.getMessage());
                    }
                    dispatch.dispatch(dynamic, saved.id());

                    String toolPrefix = toolPrefix(server);
                    List<String> tools = discoverWithinCap(agentId, toolPrefix);
                    if (tools.isEmpty()) {
                        safeRemove(dynamic, service, saved.id());
                        throw new StudioException(
                                "unavailable",
                                502,
                                "We couldn't reach that server just now, or it exposed no tools. Try another.");
                    }
                    Set<String> allowed =
                            new LinkedHashSet<>(
                                    owned.agent().getAllowedToolNames() == null
                                            ? Set.of()
                                            : owned.agent().getAllowedToolNames());
                    if (allowed.isEmpty()) {
                        allowed.addAll(
                                DemoStudioService.TOOL_CATALOG.stream()
                                        .map(DemoStudioService.ToolOption::name)
                                        .toList());
                    }
                    allowed.remove(DemoStudioService.NO_TOOLS_SENTINEL);
                    allowed.addAll(tools);
                    agentService.setAllowedToolNames(agentId, allowed);
                    return new ConnectorView(server, title, hostOf(found.url()), tools);
                });
    }

    /**
     * Runs {@link #waitForTools} on its own thread (carrying the caller's tenant context) and gives
     * up after a hard cap, so a slow or hung discovery can never hold an anonymous visitor's HTTP
     * request - or a server thread - open.
     */
    private List<String> discoverWithinCap(UUID agentId, String toolPrefix) {
        java.util.Optional<TenantContext> ctx = TenantContext.current();
        CompletableFuture<List<String>> future =
                CompletableFuture.supplyAsync(
                        () -> {
                            ctx.ifPresent(TenantContext::set);
                            try {
                                return waitForTools(agentId, toolPrefix);
                            } finally {
                                TenantContext.clear();
                            }
                        },
                        DISCOVERY);
        try {
            return future.get(CONNECT_HARD_CAP_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            log.info(
                    "Studio connector discovery for agent {} hit the {} s cap",
                    agentId,
                    CONNECT_HARD_CAP_SECONDS);
            return List.of();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            return List.of();
        } catch (java.util.concurrent.ExecutionException e) {
            log.warn("Studio connector discovery failed for agent {}", agentId, e.getCause());
            return List.of();
        }
    }

    private List<String> waitForTools(UUID agentId, String toolPrefix) {
        long deadline = System.currentTimeMillis() + CONNECT_WAIT_MS;
        while (System.currentTimeMillis() < deadline) {
            try {
                List<String> names =
                        toolProvider.listAvailableToolsFor(agentId).stream()
                                .map(ToolDescriptor::name)
                                .filter(n -> n.startsWith(toolPrefix))
                                .sorted()
                                .toList();
                if (!names.isEmpty()) {
                    return names;
                }
                Thread.sleep(POLL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return List.of();
            } catch (RuntimeException e) {
                log.debug(
                        "Tool discovery while connecting a studio connector failed - retrying", e);
            }
        }
        return List.of();
    }

    public void disconnect(DemoSession session, UUID agentId, String server) {
        DemoStudioService.Owned owned = studio.requireVisible(session, agentId);
        McpRegistryService service = registrations.getIfAvailable();
        McpDynamicConnector dynamic = connector.getIfAvailable();
        if (service == null || server == null || !server.startsWith(serverPrefix(agentId))) {
            throw new StudioException(
                    "not_found", 404, "That connector isn't attached to this agent.");
        }
        demoSessionService.inDemoTenantContextAs(
                session,
                owned.creator().getId(),
                () -> {
                    McpServerRegistrationResponse reg =
                            service.listAll().stream()
                                    .filter(r -> r.name().equals(server))
                                    .findFirst()
                                    .orElseThrow(
                                            () ->
                                                    new StudioException(
                                                            "not_found",
                                                            404,
                                                            "That connector isn't attached to this agent."));
                    safeRemove(dynamic, service, reg.id());
                    String toolPrefix = toolPrefix(server);
                    Set<String> allowed =
                            new LinkedHashSet<>(
                                    owned.agent().getAllowedToolNames() == null
                                            ? Set.of()
                                            : owned.agent().getAllowedToolNames());
                    if (allowed.removeIf(t -> t.startsWith(toolPrefix)) && !allowed.isEmpty()) {
                        agentService.setAllowedToolNames(agentId, allowed);
                    } else if (allowed.isEmpty()) {
                        agentService.setAllowedToolNames(
                                agentId, Set.of(DemoStudioService.NO_TOOLS_SENTINEL));
                    }
                    return null;
                });
    }

    /**
     * Agent delete / sandbox retire: drop every registration this agent owns. Best effort, runs in
     * the caller's tenant context.
     */
    public void deleteAllFor(UUID agentId) {
        McpRegistryService service = registrations.getIfAvailable();
        if (service == null) {
            return;
        }
        try {
            McpDynamicConnector dynamic = connector.getIfAvailable();
            String prefix = serverPrefix(agentId);
            for (McpServerRegistrationResponse r : service.listAll()) {
                if (r.name().startsWith(prefix)) {
                    safeRemove(dynamic, service, r.id());
                }
            }
        } catch (RuntimeException e) {
            log.warn("Could not clean the MCP registrations of studio agent {}", agentId, e);
        }
    }

    private void safeRemove(McpDynamicConnector dynamic, McpRegistryService service, UUID id) {
        try {
            if (dynamic != null) {
                dynamic.disconnect(id);
            }
        } catch (RuntimeException e) {
            log.debug("Disconnect of studio MCP registration {} failed - deleting anyway", id, e);
        }
        service.delete(id);
    }

    // ------------------------------------------------------------------

    static String serverPrefix(UUID agentId) {
        return SERVER_PREFIX + agentId.toString().substring(0, 8) + "-";
    }

    /**
     * Mirrors NamespacedMcpToolCallback: {@code mcp__<sanitised server>__}. The server names
     * generated here are already [a-z0-9-].
     */
    static String toolPrefix(String serverName) {
        return "mcp__" + serverName + "__";
    }

    /**
     * True for a tool name that belongs to one of THIS agent's connectors - the only MCP names a
     * studio allow-list may hold.
     */
    static boolean isConnectorTool(UUID agentId, String toolName) {
        return toolName != null && toolName.startsWith("mcp__" + serverPrefix(agentId));
    }

    static String slug(String registryName) {
        String s =
                registryName
                        .toLowerCase(Locale.ROOT)
                        .replaceAll("[^a-z0-9]+", "-")
                        .replaceAll("^-+|-+$", "");
        if (s.isEmpty()) {
            s = "server";
        }
        return s.length() > 22 ? s.substring(s.length() - 22).replaceAll("^-+", "") : s;
    }

    /**
     * DISCOVERY is instance-owned (was static) so it is shut down with the bean (JAVA-026). At the
     * end of the class so ArchUnit's frozen cycle baseline (which records call-site line numbers)
     * is not shifted.
     */
    @jakarta.annotation.PreDestroy
    void shutdownExecutor() {
        DISCOVERY.shutdown();
    }
}
