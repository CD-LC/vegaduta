package com.agenticai.core.agents.tools.mcpapps;

import static com.agenticai.core.agents.tools.mcpapps.McpAppsRequestGuard.mentionsUiUri;

import com.agenticai.core.agents.tools.AgentStepEventRedactor;
import com.agenticai.core.agents.tools.DynamicMcpConnectionManager;
import com.agenticai.core.agents.tools.McpAppStepHook;
import com.agenticai.core.json.JsonMappers;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.concurrent.TenantAwareExecutors;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Host side of MCP Apps ({@code ui://} resources attached to MCP tools). The whole feature is
 * behind {@code app.mcp.apps.enabled} (default false): with the flag off this bean does not exist,
 * nothing is recorded, no step event changes, and no endpoint is mounted.
 *
 * <h2>Trust model</h2>
 *
 * The HTML an MCP server returns is untrusted code. This class never tries to sanitise it; it
 * decides (a) whether it may be fetched at all, (b) how big/what type it may be, and (c) which CSP
 * it must be rendered under. Rendering happens only in the browser inside an origin-less sandboxed
 * {@code srcdoc} iframe. See {@code docs/plans/MCP-APPS-SECURITY-NOTES.md}.
 *
 * <h2>Fetch rules</h2>
 *
 * <ul>
 *   <li>Only {@code ui://} URIs, and only a URI that a tool of THAT connection declared in its own
 *       {@code _meta.ui.resourceUri} - the browser never supplies a URI.
 *   <li>Only through the live, already-authenticated {@link McpSyncClient} of a registration this
 *       tenant connected (looked up by tenant + registration id - another tenant's connection is
 *       unreachable). No live client means refuse; there is no fallback transport.
 *   <li>Text contents only, mime exactly {@code text/html;profile=mcp-app}, at most {@link
 *       McpAppsConfig#maxResourceBytes()} UTF-8 bytes, the response URI must equal the one asked.
 *   <li>Bounded by {@link McpAppsConfig#fetchTimeout()} and a per-tenant concurrency cap; cached
 *       briefly in memory keyed by (registration, uri).
 * </ul>
 *
 * <h2>Sessions</h2>
 *
 * When a tool with an app returns, a short-lived in-memory "app session" is minted that binds
 * (tenant, user, agent, registration, tool, uri, redacted input, sanitised result). Its random id
 * is the only handle the browser holds; every later call (view, tools/call) re-checks tenant AND
 * user against it. In memory means: the replica that served the chat turn is the one that serves
 * the view (the same replica that owns the live MCP client); another replica answers 404 and the
 * web falls back to the plain tool-result row.
 */
@Component
@ConditionalOnProperty(name = "app.mcp.apps.enabled", havingValue = "true")
public class McpAppsService {

    private static final Logger log = LoggerFactory.getLogger(McpAppsService.class);
    private static final ObjectMapper JSON = JsonMappers.plain();
    private static final SecureRandom RANDOM = new SecureRandom();

    /**
     * The only mime type an MCP App resource may carry (after whitespace/quote/case normalisation).
     */
    public static final String APP_MIME = "text/html;profile=mcp-app";

    private static final int MAX_CACHE_ENTRIES = 64;

    /**
     * One tool of a connected server as the registry needs it. {@code declaration} is null for
     * tools without an app.
     */
    public record ToolEntry(
            String rawName, String finalName, McpAppUiMeta.Declaration declaration) {}

    /** What a connected server exposes, recorded at (re)discovery. */
    record ServerApps(
            String serverName,
            Map<String, String> rawToFinal,
            Map<String, McpAppUiMeta.Declaration> declByFinal) {}

    record AppToolRef(
            UUID registrationId,
            String serverName,
            String rawTool,
            String finalTool,
            McpAppUiMeta.Declaration declaration) {}

    record Session(
            String appId,
            String tenant,
            String subject,
            UUID agentId,
            UUID registrationId,
            String serverName,
            String rawTool,
            String finalTool,
            String uri,
            String inputJson,
            String result,
            boolean resultTruncated,
            Instant created,
            Instant expires) {}

    record CachedResource(String html, String mimeType, Instant expires) {}

    /** The payload the browser renders. {@code html} is untrusted. */
    public record AppView(
            String appId,
            String serverName,
            String tool,
            String uri,
            String mimeType,
            String html,
            String csp,
            String toolInput,
            String toolResult,
            boolean toolResultTruncated) {}

    /** A vetted tools/call: what the controller may now execute through the guarded tool chain. */
    public record ToolCallAuth(
            String appId,
            UUID agentId,
            String finalToolName,
            String argumentsJson,
            String logSessionId,
            String subject) {}

    /**
     * Resolves the live authenticated client of (tenant, registration); empty/null = not connected.
     */
    @FunctionalInterface
    public interface Connections {
        McpSyncClient liveClient(String tenant, UUID registrationId);
    }

    private final McpAppsConfig config;
    private final Connections connections;
    private final Map<String, Map<UUID, ServerApps>> registry = new ConcurrentHashMap<>();
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final Map<String, CachedResource> cache = new ConcurrentHashMap<>();
    private final Map<String, Semaphore> tenantFetchSlots = new ConcurrentHashMap<>();
    private final ExecutorService fetchPool;
    private final McpAppsRequestGuard requestGuard = new McpAppsRequestGuard();

    @Autowired
    public McpAppsService(
            @Value("${app.mcp.apps.max-resource-bytes:524288}") int maxResourceBytes,
            @Value("${app.mcp.apps.fetch-timeout-ms:5000}") long fetchTimeoutMs,
            @Value("${app.mcp.apps.cache-ttl-seconds:60}") long cacheTtlSeconds,
            @Value("${app.mcp.apps.allowed-domains:}") String allowedDomains,
            @Value("${app.mcp.apps.allow-https-images:true}") boolean httpsImages,
            @Value("${app.mcp.apps.max-result-chars:65536}") int maxResultChars,
            @Value("${app.mcp.apps.session-ttl-seconds:900}") long sessionTtlSeconds,
            @Value("${app.mcp.apps.max-sessions:500}") int maxSessions,
            @Value("${app.mcp.apps.calls-per-minute:30}") int callsPerMinute,
            @Value("${app.mcp.apps.max-concurrent-fetches-per-tenant:4}") int maxConcurrentFetches,
            ObjectProvider<DynamicMcpConnectionManager> manager) {
        this(
                new McpAppsConfig(
                        maxResourceBytes,
                        Duration.ofMillis(fetchTimeoutMs),
                        Duration.ofSeconds(cacheTtlSeconds),
                        allowedDomains == null
                                ? List.of()
                                : java.util.Arrays.asList(allowedDomains.split(",")),
                        httpsImages,
                        maxResultChars,
                        Duration.ofSeconds(sessionTtlSeconds),
                        maxSessions,
                        callsPerMinute,
                        maxConcurrentFetches,
                        0),
                (tenant, id) -> {
                    DynamicMcpConnectionManager m = manager.getIfAvailable();
                    return m == null ? null : m.liveClient(tenant, id);
                });
    }

    public McpAppsService(McpAppsConfig config, Connections connections) {
        this.config = config;
        this.connections = connections;
        this.fetchPool =
                TenantAwareExecutors.newThreadPool(
                        0,
                        16,
                        30,
                        TimeUnit.SECONDS,
                        new SynchronousQueue<>(),
                        TenantAwareExecutors.threadFactory("mcp-app-fetch", true));
    }

    McpAppsConfig config() {
        return config;
    }

    // ------------------------------------------------------------------ registry (from discovery)

    /**
     * Records what a connected server's tool list declares. Replaces any earlier record for the
     * registration.
     */
    public void recordServer(
            String tenant, UUID registrationId, String serverName, List<ToolEntry> tools) {
        if (tenant == null || registrationId == null || tools == null) {
            return;
        }
        Map<String, String> rawToFinal = new LinkedHashMap<>();
        Map<String, McpAppUiMeta.Declaration> decl = new LinkedHashMap<>();
        for (ToolEntry t : tools) {
            if (t == null || t.rawName() == null || t.finalName() == null) {
                continue;
            }
            rawToFinal.put(t.rawName(), t.finalName());
            if (t.declaration() != null) {
                decl.put(t.finalName(), t.declaration());
            }
        }
        registry.computeIfAbsent(tenant, k -> new ConcurrentHashMap<>())
                .put(
                        registrationId,
                        new ServerApps(serverName, Map.copyOf(rawToFinal), Map.copyOf(decl)));
    }

    /**
     * Drops everything held for a registration (disconnect / delete): registry row, cached HTML,
     * live sessions.
     */
    public void forgetServer(String tenant, UUID registrationId) {
        if (tenant == null || registrationId == null) {
            return;
        }
        Map<UUID, ServerApps> servers = registry.get(tenant);
        if (servers != null) {
            servers.remove(registrationId);
        }
        cache.keySet().removeIf(k -> k.startsWith(registrationId + "|"));
        sessions.values()
                .removeIf(
                        s ->
                                s.tenant().equals(tenant)
                                        && s.registrationId().equals(registrationId));
    }

    /**
     * The app a platform-visible tool name maps to for this tenant; empty if none or ambiguous
     * across servers.
     */
    Optional<AppToolRef> appToolFor(String tenant, String finalToolName) {
        Map<UUID, ServerApps> servers = registry.get(tenant);
        if (servers == null || finalToolName == null) {
            return Optional.empty();
        }
        AppToolRef found = null;
        for (Map.Entry<UUID, ServerApps> e : servers.entrySet()) {
            ServerApps sa = e.getValue();
            if (!sa.rawToFinal().containsValue(finalToolName)) {
                continue;
            }
            if (found != null) {
                return Optional
                        .empty(); // two servers claim this name: never guess which one a result
                // came from
            }
            McpAppUiMeta.Declaration d = sa.declByFinal().get(finalToolName);
            String raw =
                    sa.rawToFinal().entrySet().stream()
                            .filter(x -> x.getValue().equals(finalToolName))
                            .map(Map.Entry::getKey)
                            .findFirst()
                            .orElse(null);
            found = new AppToolRef(e.getKey(), sa.serverName(), raw, finalToolName, d);
        }
        return found == null || found.declaration() == null ? Optional.empty() : Optional.of(found);
    }

    // ------------------------------------------------------------------ step-event hook

    /**
     * The hook {@code StepEventToolCallback} calls for a turn. Captured on the request thread (the
     * tenant, user, agent and session are fixed here, never re-read from a reactive thread). Null
     * when there is no tenant context.
     */
    public McpAppStepHook hookFor(TenantContext ctx, UUID agentId, String sessionId) {
        if (ctx == null
                || ctx.getTenantId() == null
                || ctx.getSubject() == null
                || agentId == null) {
            return null;
        }
        String tenant = ctx.getTenantId();
        String subject = ctx.getSubject();
        return (toolName, argsJson, output) -> {
            try {
                Optional<AppToolRef> ref = appToolFor(tenant, toolName);
                if (ref.isEmpty()) {
                    return null;
                }
                AppToolRef r = ref.get();
                String text = output == null ? "" : output;
                boolean truncated = text.length() > config.maxResultChars();
                if (truncated) {
                    text = text.substring(0, config.maxResultChars());
                }
                // Credential-shaped values and secret-named argument fields never reach third-party
                // HTML.
                String input =
                        argsJson == null || argsJson.isBlank()
                                ? "{}"
                                : AgentStepEventRedactor.redactPayload(argsJson);
                Instant now = Instant.now();
                String appId = newId();
                sessions.put(
                        appId,
                        new Session(
                                appId,
                                tenant,
                                subject,
                                agentId,
                                r.registrationId(),
                                r.serverName(),
                                r.rawTool(),
                                r.finalTool(),
                                r.declaration().resourceUri(),
                                input,
                                text,
                                truncated,
                                now,
                                now.plus(config.sessionTtl())));
                purgeSessions(now);
                Map<String, Object> pointer = new LinkedHashMap<>();
                pointer.put("appId", appId);
                pointer.put("server", r.serverName());
                pointer.put("tool", r.rawTool());
                pointer.put("uri", r.declaration().resourceUri());
                return pointer;
            } catch (RuntimeException e) {
                log.debug("MCP app pointer skipped for tool {}", toolName, e);
                return null;
            }
        };
    }

    private void purgeSessions(Instant now) {
        sessions.values().removeIf(s -> s.expires().isBefore(now));
        int over = sessions.size() - config.maxSessions();
        if (over > 0) {
            sessions.values().stream()
                    .sorted(Comparator.comparing(Session::created))
                    .limit(over)
                    .forEach(s -> sessions.remove(s.appId()));
        }
    }

    private static String newId() {
        byte[] b = new byte[18];
        RANDOM.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    // ------------------------------------------------------------------ view (resources/read)

    /** Everything the browser needs to render one app. Tenant AND user must match the session's. */
    public AppView view(String appId) {
        Session s = requireSession(appId);
        rateLimit(
                "view|" + s.tenant() + "|" + s.subject(),
                Math.max(config.callsPerMinute() * 2, 10));
        ServerApps sa = serverFor(s);
        McpAppUiMeta.Declaration decl = sa.declByFinal().get(s.finalTool());
        if (decl == null || !decl.resourceUri().equals(s.uri())) {
            throw new McpAppsException(
                    McpAppsException.Code.NOT_FOUND, "App is no longer available.");
        }
        CachedResource res = fetchResource(s.tenant(), s.registrationId(), s.uri(), sa);
        String csp = McpAppCsp.build(decl.csp(), config.allowedDomains(), config.httpsImages());
        return new AppView(
                s.appId(),
                s.serverName(),
                s.rawTool(),
                s.uri(),
                res.mimeType(),
                res.html(),
                csp,
                s.inputJson(),
                s.result(),
                s.resultTruncated());
    }

    /**
     * resources/read through the SAME authenticated connection that served the tool.
     * Package-private so the refusal paths are unit-testable without a session.
     */
    CachedResource fetchResource(String tenant, UUID registrationId, String uri, ServerApps sa) {
        if (!McpAppUiMeta.isUiUri(uri)) {
            throw new McpAppsException(
                    McpAppsException.Code.REFUSED, "Only ui:// resources are supported.");
        }
        boolean declared =
                sa.declByFinal().values().stream().anyMatch(d -> d.resourceUri().equals(uri));
        if (!declared) {
            throw new McpAppsException(
                    McpAppsException.Code.REFUSED,
                    "That resource was not declared by a tool of this server.");
        }
        Instant now = Instant.now();
        String cacheKey = registrationId + "|" + uri;
        CachedResource hit = cache.get(cacheKey);
        if (hit != null && hit.expires().isAfter(now)) {
            return hit;
        }
        McpSyncClient client = connections.liveClient(tenant, registrationId);
        if (client == null) {
            throw new McpAppsException(
                    McpAppsException.Code.REFUSED,
                    "The server is not connected for this workspace.");
        }
        Semaphore slots =
                tenantFetchSlots.computeIfAbsent(
                        tenant, k -> new Semaphore(config.maxConcurrentFetchesPerTenant()));
        if (!slots.tryAcquire()) {
            throw new McpAppsException(
                    McpAppsException.Code.RATE_LIMITED,
                    "Too many app loads in progress. Try again shortly.");
        }
        try {
            McpSchema.ReadResourceResult result = readWithTimeout(client, uri);
            CachedResource parsed = validate(result, uri, now);
            if (config.cacheTtl().isZero()) {
                return parsed;
            }
            if (cache.size() >= MAX_CACHE_ENTRIES) {
                cache.values().removeIf(c -> c.expires().isBefore(now));
                if (cache.size() >= MAX_CACHE_ENTRIES) {
                    cache.clear();
                }
            }
            cache.put(cacheKey, parsed);
            return parsed;
        } finally {
            slots.release();
        }
    }

    private McpSchema.ReadResourceResult readWithTimeout(McpSyncClient client, String uri) {
        Future<McpSchema.ReadResourceResult> f;
        try {
            f = fetchPool.submit(() -> client.readResource(new McpSchema.ReadResourceRequest(uri)));
        } catch (RejectedExecutionException e) {
            throw new McpAppsException(
                    McpAppsException.Code.UNAVAILABLE,
                    "The app loader is busy. Try again shortly.");
        }
        try {
            return f.get(config.fetchTimeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            f.cancel(true);
            throw new McpAppsException(
                    McpAppsException.Code.UNAVAILABLE,
                    "The server took too long to return the app.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            f.cancel(true);
            throw new McpAppsException(
                    McpAppsException.Code.UNAVAILABLE, "Interrupted while loading the app.");
        } catch (ExecutionException e) {
            log.debug("MCP app resources/read failed for {}", uri, e.getCause());
            throw new McpAppsException(
                    McpAppsException.Code.UNAVAILABLE, "The server could not return the app.");
        }
    }

    CachedResource validate(McpSchema.ReadResourceResult result, String uri, Instant now) {
        if (result == null || result.contents() == null || result.contents().isEmpty()) {
            throw new McpAppsException(
                    McpAppsException.Code.UNAVAILABLE, "The server returned no app content.");
        }
        for (McpSchema.ResourceContents c : result.contents()) {
            if (c == null || !uri.equals(c.uri())) {
                continue;
            }
            if (!(c instanceof McpSchema.TextResourceContents text)) {
                throw new McpAppsException(
                        McpAppsException.Code.REFUSED, "Binary app content is not supported.");
            }
            if (!isAppMime(text.mimeType())) {
                throw new McpAppsException(
                        McpAppsException.Code.REFUSED,
                        "The resource is not an MCP App (unsupported mime type).");
            }
            String html = text.text();
            if (html == null || html.isEmpty()) {
                throw new McpAppsException(McpAppsException.Code.UNAVAILABLE, "The app is empty.");
            }
            // Cheap upper bound first (a UTF-8 byte is never fewer than one char), then the exact
            // count.
            if (html.length() > config.maxResourceBytes()
                    || html.getBytes(StandardCharsets.UTF_8).length > config.maxResourceBytes()) {
                throw new McpAppsException(
                        McpAppsException.Code.TOO_LARGE, "The app is too large to display.");
            }
            return new CachedResource(html, APP_MIME, now.plus(config.cacheTtl()));
        }
        throw new McpAppsException(
                McpAppsException.Code.UNAVAILABLE, "The server did not return the requested app.");
    }

    /**
     * {@code text/html;profile=mcp-app}, tolerating case, spaces and a quoted profile value;
     * nothing else.
     */
    static boolean isAppMime(String mime) {
        if (mime == null) {
            return false;
        }
        String n = mime.toLowerCase(Locale.ROOT).replaceAll("\\s+", "").replace("\"", "");
        return APP_MIME.equals(n);
    }

    // ------------------------------------------------------------------ tools/call from the app

    /**
     * Vets a tools/call the (untrusted) app asked for. Does NOT execute anything: the controller
     * runs the call through the platform's guarded tool chain (approvals, argument policies,
     * classification, audit) exactly as a model-issued call would be. Refuses: other tenants/users,
     * tools of any other server, ambiguous names, arguments mentioning {@code ui://} (recursion),
     * oversize/non-object arguments, and callers over the rate limit.
     */
    public ToolCallAuth authorizeToolCall(String appId, String toolName, String argumentsJson) {
        Session s = requireSession(appId);
        if (toolName == null || toolName.isBlank() || toolName.length() > 200) {
            throw new McpAppsException(
                    McpAppsException.Code.BAD_REQUEST, "A tool name is required.");
        }
        String args = argumentsJson == null || argumentsJson.isBlank() ? "{}" : argumentsJson;
        if (args.length() > config.maxArgsChars()) {
            throw new McpAppsException(McpAppsException.Code.TOO_LARGE, "Arguments are too large.");
        }
        JsonNode node;
        try {
            node = JSON.readTree(args);
        } catch (JsonProcessingException e) {
            throw new McpAppsException(
                    McpAppsException.Code.BAD_REQUEST, "Arguments must be a JSON object.");
        }
        if (node == null || !node.isObject()) {
            throw new McpAppsException(
                    McpAppsException.Code.BAD_REQUEST, "Arguments must be a JSON object.");
        }
        if (mentionsUiUri(node, 0)) {
            throw new McpAppsException(
                    McpAppsException.Code.REFUSED,
                    "ui:// references are not allowed in tool arguments.");
        }
        ServerApps sa = serverFor(s);
        String finalName = sa.rawToFinal().get(toolName);
        if (finalName == null) {
            // Includes every tool of every OTHER server and every platform built-in.
            throw new McpAppsException(
                    McpAppsException.Code.REFUSED, "An app may only call tools of its own server.");
        }
        Map<UUID, ServerApps> all = registry.getOrDefault(s.tenant(), Map.of());
        for (Map.Entry<UUID, ServerApps> e : all.entrySet()) {
            if (!e.getKey().equals(s.registrationId())
                    && e.getValue().rawToFinal().containsValue(finalName)) {
                throw new McpAppsException(
                        McpAppsException.Code.REFUSED,
                        "That tool name is ambiguous across servers.");
            }
        }
        rateLimit("call|" + s.tenant() + "|" + s.subject(), config.callsPerMinute());
        return new ToolCallAuth(
                s.appId(), s.agentId(), finalName, args, "mcpapp:" + s.appId(), s.subject());
    }

    // ------------------------------------------------------------------ shared checks

    private Session requireSession(String appId) {
        Optional<TenantContext> ctx = TenantContext.current();
        if (ctx.isEmpty() || appId == null) {
            throw new McpAppsException(McpAppsException.Code.NOT_FOUND, "App not found.");
        }
        Session s = sessions.get(appId);
        if (s == null
                || s.expires().isBefore(Instant.now())
                || !s.tenant().equals(ctx.get().getTenantId())
                || !s.subject().equals(ctx.get().getSubject())) {
            // One message for every cause: never confirm that another tenant's or user's id exists.
            throw new McpAppsException(McpAppsException.Code.NOT_FOUND, "App not found.");
        }
        return s;
    }

    private ServerApps serverFor(Session s) {
        ServerApps sa = registry.getOrDefault(s.tenant(), Map.of()).get(s.registrationId());
        if (sa == null) {
            throw new McpAppsException(
                    McpAppsException.Code.NOT_FOUND, "The server is no longer connected.");
        }
        return sa;
    }

    private void rateLimit(String key, int perMinute) {
        requestGuard.rateLimit(key, perMinute);
    }

    /** For tests: how many live sessions are held. */
    int sessionCount() {
        return sessions.size();
    }
}
