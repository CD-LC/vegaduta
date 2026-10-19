package com.agenticai.core.agents.tools;

import com.agenticai.core.tenancy.PlatformSettingsService;
import io.modelcontextprotocol.client.McpSyncClient;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Which of application-mcp.yml's STATIC (JVM-wide) MCP servers agents may be offered.
 *
 * <p><b>Why this exists (2026-09-28, cross-tenant leak):</b> every static STDIO connection in
 * application-mcp.yml is ONE subprocess shared by every tenant in the container. For a stateless
 * server (sequential-thinking: pure function of its input) that is harmless. For a stateful one it
 * is a cross-tenant data channel: {@code filesystem} reads/writes one shared {@code ./mcp-data}
 * root, {@code memory} keeps one shared knowledge graph, and the browser servers ({@code
 * puppeteer}, {@code playwright-vision}) keep one shared browser with shared tabs, cookies and
 * sessions. Tenant A's agent could read what tenant B's agent wrote. {@link
 * AgentMcpToolResolver#resolveStaticMcpTools} previously served all of them to every tenant whose
 * own registry had any server enabled.
 *
 * <p><b>Policy (owner decision: hide the stateful ones):</b> a static server is exposed only if its
 * connection name is on the explicit stateless allowlist ({@code app.mcp.static.stateless-servers},
 * default {@code [sequential-thinking]}) - an ALLOWlist, so a newly added static server defaults to
 * hidden rather than silently shared - OR the platform flag {@value #SHARED_STATEFUL_ENABLED_KEY}
 * is {@code true} (admin-settable at runtime through {@link PlatformSettingsService}; the {@code
 * app.mcp.static.shared-stateful-enabled} property, default {@code false}, is the fallback when no
 * row exists). The flag exists only for single-tenant/self-hosted deployments where "shared by
 * every tenant" means "shared with yourself". Tenants that want filesystem/memory/browser tools
 * register their own isolated copy through the Tools UI (the dynamic, per-tenant path - unaffected
 * by this class).
 *
 * <p>A hidden static client is filtered out BEFORE discovery, so with application-mcp.yml's {@code
 * initialized: false} its subprocess is never even spawned.
 */
@Component
public class StaticMcpExposurePolicy {

    private static final Logger log = LoggerFactory.getLogger(StaticMcpExposurePolicy.class);

    /**
     * Admin-settable platform flag (platform_setting row; upserted by the admin settings API, no
     * seed migration needed).
     */
    public static final String SHARED_STATEFUL_ENABLED_KEY =
            "platform.mcp.static.shared-stateful-enabled";

    static final List<String> DEFAULT_STATELESS_SERVERS = List.of("sequential-thinking");

    /**
     * Same short-TTL posture FreeModeService takes: this is read on every chat turn,
     * PlatformSettingsService never caches.
     */
    private static final Duration FLAG_CACHE_TTL = Duration.ofSeconds(10);

    /**
     * What {@link AgentMcpToolResolver} uses when no policy bean is reachable (slim/test contexts):
     * default stateless allowlist, shared-stateful OFF. Fail-closed - never wider than production
     * default.
     */
    static final StaticMcpExposurePolicy FAIL_CLOSED =
            new StaticMcpExposurePolicy(DEFAULT_STATELESS_SERVERS, () -> false);

    private final Set<String> statelessServers;
    private final BooleanSupplier sharedStatefulFlag;
    private final ObjectProvider<List<McpSyncClient>> staticMcpSyncClients;

    @Autowired
    public StaticMcpExposurePolicy(
            ObjectProvider<PlatformSettingsService> platformSettings,
            ObjectProvider<List<McpSyncClient>> staticMcpSyncClients,
            @Value("${app.mcp.static.shared-stateful-enabled:false}")
                    boolean sharedStatefulFallback,
            // Comma-separated STRING, not a YAML list: @Value cannot bind an indexed YAML list.
            @Value("${app.mcp.static.stateless-servers:sequential-thinking}")
                    String statelessServers) {
        this.statelessServers =
                normalize(
                        statelessServers == null
                                ? List.of()
                                : List.of(statelessServers.split(",")));
        this.sharedStatefulFlag =
                new CachedFlag(platformSettings, sharedStatefulFallback, Clock.systemUTC());
        this.staticMcpSyncClients = staticMcpSyncClients;
    }

    /** Test/fallback constructor - no settings lookup, no startup logging. */
    StaticMcpExposurePolicy(List<String> statelessServers, BooleanSupplier sharedStatefulFlag) {
        this.statelessServers = normalize(statelessServers);
        this.sharedStatefulFlag = sharedStatefulFlag;
        this.staticMcpSyncClients = null;
    }

    private static Set<String> normalize(List<String> names) {
        Set<String> out = new LinkedHashSet<>();
        if (names != null) {
            for (String name : names) {
                if (name != null && !name.isBlank()) {
                    out.add(name.trim());
                }
            }
        }
        return Set.copyOf(out);
    }

    /**
     * True when {@value #SHARED_STATEFUL_ENABLED_KEY} (or its property fallback) opens every static
     * server to every tenant.
     */
    boolean sharedStatefulEnabled() {
        return sharedStatefulFlag.getAsBoolean();
    }

    /**
     * Whether a static server with this connection name (the application-mcp.yml map key - Spring
     * AI puts it on the client's {@code clientInfo().title()}) may be offered to agents. A null or
     * unknown name is treated as stateful: hidden unless the shared-stateful flag is on.
     */
    boolean isExposed(String serverName) {
        if (serverName != null && statelessServers.contains(serverName)) {
            return true;
        }
        return sharedStatefulEnabled();
    }

    /**
     * Resolves exposure once per call (one flag read), for filtering a whole client list
     * consistently.
     */
    List<McpSyncClient> exposedClients(List<McpSyncClient> clients) {
        return exposedClients(clients, sharedStatefulEnabled());
    }

    /**
     * Same, with the flag value the caller already read (so a cache key and the filter can never
     * disagree).
     */
    List<McpSyncClient> exposedClients(List<McpSyncClient> clients, boolean sharedStateful) {
        if (clients == null || clients.isEmpty()) {
            return List.of();
        }
        if (sharedStateful) {
            return clients;
        }
        List<McpSyncClient> exposed = new ArrayList<>();
        for (McpSyncClient client : clients) {
            String name = serverNameOf(client);
            if (name != null && statelessServers.contains(name)) {
                exposed.add(client);
            }
        }
        return exposed;
    }

    static String serverNameOf(McpSyncClient client) {
        return client == null || client.getClientInfo() == null
                ? null
                : client.getClientInfo().title();
    }

    /** Names of the given clients that {@link #exposedClients} would drop right now. */
    List<String> hiddenServerNames(List<McpSyncClient> clients) {
        if (clients == null || sharedStatefulEnabled()) {
            return List.of();
        }
        List<String> hidden = new ArrayList<>();
        for (McpSyncClient client : clients) {
            String name = serverNameOf(client);
            if (name == null || !statelessServers.contains(name)) {
                hidden.add(name == null ? "unknown" : name);
            }
        }
        return hidden;
    }

    /**
     * Operator visibility: say once, at startup, which static servers tenants will NOT see and why.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void logExposureAtStartup() {
        if (staticMcpSyncClients == null) {
            return;
        }
        try {
            List<McpSyncClient> clients = staticMcpSyncClients.getIfAvailable(List::of);
            if (clients.isEmpty()) {
                return;
            }
            if (sharedStatefulEnabled()) {
                log.warn(
                        "{}=true - ALL {} static MCP server(s) are shared across every tenant, including stateful ones "
                                + "(filesystem/memory/browser state is visible cross-tenant). Only safe for single-tenant deployments.",
                        SHARED_STATEFUL_ENABLED_KEY,
                        clients.size());
                return;
            }
            List<String> hidden = hiddenServerNames(clients);
            if (!hidden.isEmpty()) {
                log.info(
                        "Static MCP servers hidden from agents (stateful - one process shared by all tenants; set {}=true "
                                + "to expose on a single-tenant deployment, or register a per-tenant copy via the Tools UI): {}. "
                                + "Exposed stateless allowlist: {}",
                        SHARED_STATEFUL_ENABLED_KEY,
                        hidden,
                        statelessServers);
            }
        } catch (RuntimeException ex) {
            log.warn("Could not log static MCP server exposure at startup", ex);
        }
    }

    /**
     * {@link PlatformSettingsService#getBoolean} with a short TTL and fail-to-fallback on any read
     * error.
     */
    static final class CachedFlag implements BooleanSupplier {
        private final ObjectProvider<PlatformSettingsService> settings;
        private final boolean fallback;
        private final Clock clock;
        private volatile boolean value;
        private volatile Instant expiresAt = Instant.MIN;

        CachedFlag(
                ObjectProvider<PlatformSettingsService> settings, boolean fallback, Clock clock) {
            this.settings = settings;
            this.fallback = fallback;
            this.clock = clock;
            this.value = fallback;
        }

        @Override
        public boolean getAsBoolean() {
            Instant now = clock.instant();
            if (now.isBefore(expiresAt)) {
                return value;
            }
            boolean fresh = fallback;
            PlatformSettingsService service = settings == null ? null : settings.getIfAvailable();
            if (service != null) {
                try {
                    fresh = service.getBoolean(SHARED_STATEFUL_ENABLED_KEY, fallback);
                } catch (RuntimeException ex) {
                    log.warn(
                            "Could not read platform setting {} - using fallback {}",
                            SHARED_STATEFUL_ENABLED_KEY,
                            fallback,
                            ex);
                }
            }
            value = fresh;
            expiresAt = now.plus(FLAG_CACHE_TTL);
            return fresh;
        }
    }
}
