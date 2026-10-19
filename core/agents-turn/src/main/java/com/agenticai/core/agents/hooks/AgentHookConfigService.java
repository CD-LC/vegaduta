package com.agenticai.core.agents.hooks;

import com.agenticai.core.agents.core.Agent;
import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.agents.hooks.AgentHookDtos.AgentHooksResponse;
import com.agenticai.core.agents.hooks.AgentHookDtos.Schema;
import com.agenticai.core.agents.hooks.AgentHookDtos.TenantHooksResponse;
import com.agenticai.core.agents.tools.classification.ToolSensitivity;
import com.agenticai.core.agents.tools.classification.ToolTrustTier;
import com.agenticai.core.audit.AuditLogService;
import com.agenticai.core.audit.AuditOutcome;
import com.agenticai.core.json.JsonMappers;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.sharing.PermissionLevel;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Stores, validates and serves hook configuration, and hands the runtime the {@link HookSet} in force for an agent.
 *
 * <p><b>Access.</b> Reading an agent's hooks needs VIEWER on the agent; saving them needs EDITOR on the agent
 * <i>and</i> a governance authority ({@code PERM_MANAGE_GUARDRAILS}, a tenant admin, or a platform admin): a hook
 * can send data to a third party and can refuse tool calls workspace-wide, which is more than editing a prompt.
 * The workspace defaults endpoint is readable and writable only
 * with that same authority; the per-agent read shows other readers the inherited and own hooks with webhook URLs cut to
 * their host (a URL can carry a token). Identity comes from {@link TenantContext}, never from a request body.
 *
 * <p><b>Flag.</b> With {@code app.agent-hooks.enabled} off, reads work (and say {@code enabled:false}), saves are a
 * 409, and {@link #effective} returns nothing - the runtime never reads this table.
 *
 * <p><b>Cache.</b> {@link #effective} is called once per tool per turn, so the merged set is cached per tenant and
 * agent for {@code app.agent-hooks.cache-ttl-ms} (default 15s) and invalidated on every save here. With a second
 * core replica another replica sees a save after at most the TTL. When a reload fails the last good (expired)
 * entry is kept rather than dropping hooks on a database blip.
 */
@Service
public class AgentHookConfigService {

    private static final Logger log = LoggerFactory.getLogger(AgentHookConfigService.class);
    private static final ObjectMapper MAPPER = JsonMappers.plain();
    private static final int MAX_WEBHOOK_HOSTS = 20;
    private static final Pattern HOST_PATTERN =
        Pattern.compile("(\\*\\.)?[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?)+");
    private static final int MAX_CACHE_ENTRIES = 2_000;

    private record Cached(HookSet set, long expiresAtMillis) {
    }

    private final AgentHookConfigRepository repository;
    private final ObjectProvider<AgentService> agentService;
    private final AuditLogService auditLogService;
    private final boolean enabled;
    private final List<String> platformHosts;
    private final long cacheTtlMillis;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    public AgentHookConfigService(
        AgentHookConfigRepository repository,
        ObjectProvider<AgentService> agentService,
        AuditLogService auditLogService,
        @Value("${app.agent-hooks.enabled:false}") boolean enabled,
        @Value("${app.agent-hooks.webhook.allowed-hosts:}") String platformAllowedHosts,
        @Value("${app.agent-hooks.cache-ttl-ms:15000}") long cacheTtlMillis
    ) {
        this.repository = repository;
        this.agentService = agentService;
        this.auditLogService = auditLogService;
        this.enabled = enabled;
        this.platformHosts = parseHostCsv(platformAllowedHosts);
        this.cacheTtlMillis = Math.max(0L, cacheTtlMillis);
    }

    public boolean isEnabled() {
        return enabled;
    }

    /** Lazy (ObjectProvider): AgentService sits near the tool provider in the bean graph, and this must not close a cycle. */
    private Agent requireAccess(UUID agentId, PermissionLevel level) {
        return agentService.getObject().requireAccess(agentId, level);
    }

    /** The operator's webhook allow-list (deployment config). */
    public List<String> platformWebhookHosts() {
        return platformHosts;
    }

    // -------------------------------------------------------------- agent scope

    public AgentHooksResponse getAgentHooks(UUID agentId) {
        requireAccess(agentId, PermissionLevel.VIEWER);
        return agentView(agentId);
    }

    @Transactional
    public AgentHooksResponse putAgentHooks(UUID agentId, JsonNode body) {
        // VIEWER first: an agent the caller cannot see answers 404 before any 403/409 reveals anything about it.
        requireAccess(agentId, PermissionLevel.VIEWER);
        requireEnabled();
        requireManageAuthority();
        Agent agent = requireAccess(agentId, PermissionLevel.EDITOR);
        // The same FREE-tier edit lock every other agent mutator applies (a 423 the web card already handles).
        agentService.getObject().assertEditable(agent);
        JsonNode hooksNode = requireHooksField(body);
        List<String> tenantHosts = tenantHosts(repository.findById(AgentHookConfig.TENANT_KEY).orElse(null));
        List<HookDefinition> hooks = HookSchema.parse(hooksNode, urlCheck(tenantHosts));
        AgentHookConfig row = repository.findById(AgentHookConfig.keyFor(agentId)).orElseGet(() -> AgentHookConfig.forAgent(agentId));
        row.update(HookSchema.toJson(hooks), null, subject());
        repository.save(row);
        cache.remove(cacheKey(agentId));
        audit("agent.hooks.update", "agent", agentId.toString(), "hooks=" + hooks.size());
        return agentView(agentId);
    }

    private AgentHooksResponse agentView(UUID agentId) {
        AgentHookConfig tenantRow = repository.findById(AgentHookConfig.TENANT_KEY).orElse(null);
        AgentHookConfig agentRow = repository.findById(AgentHookConfig.keyFor(agentId)).orElse(null);
        List<HookDefinition> own = readHooks(agentRow, "agent " + agentId);
        List<HookDefinition> inherited = readHooks(tenantRow, "tenant");
        List<String> hosts = new ArrayList<>(platformHosts);
        hosts.addAll(tenantHosts(tenantRow));
        List<Map<String, Object>> ownMaps = HookSchema.toMaps(own);
        List<Map<String, Object>> inheritedMaps = HookSchema.toMaps(inherited);
        if (!hasManageAuthority()) {
            // A webhook URL can carry a token in its path or query. Anyone with VIEWER on the agent may read this
            // response, but only the governance authority may change hooks, so everyone else sees the host only.
            ownMaps = redactWebhookUrls(ownMaps);
            inheritedMaps = redactWebhookUrls(inheritedMaps);
        }
        return new AgentHooksResponse(
            enabled,
            ownMaps,
            inheritedMaps,
            List.copyOf(hosts),
            agentRow == null ? null : agentRow.getUpdatedAt(),
            schema());
    }

    // ------------------------------------------------------------- tenant scope

    public TenantHooksResponse getTenantHooks() {
        // The full definitions (webhook URLs, allow-lists, policy wording) are governance configuration.
        requireManageAuthority();
        return tenantView();
    }

    @Transactional
    public TenantHooksResponse putTenantHooks(JsonNode body) {
        requireEnabled();
        requireManageAuthority();
        JsonNode hooksNode = requireHooksField(body);
        AgentHookConfig row = repository.findById(AgentHookConfig.TENANT_KEY).orElseGet(AgentHookConfig::forTenant);
        List<String> hosts = tenantHosts(row);
        String settingsJson = null;
        if (body.has("webhookHosts") && !body.get("webhookHosts").isNull()) {
            hosts = parseHostArray(body.get("webhookHosts"));
            ObjectNode settings = MAPPER.createObjectNode();
            ArrayNode arr = settings.putArray("webhookHosts");
            hosts.forEach(arr::add);
            settingsJson = settings.toString();
        }
        List<HookDefinition> hooks = HookSchema.parse(hooksNode, urlCheck(hosts));
        row.update(HookSchema.toJson(hooks), settingsJson, subject());
        repository.save(row);
        invalidateTenant();
        audit("agent.hooks.defaults.update", "tenant", currentTenant(), "hooks=" + hooks.size() + " webhookHosts=" + hosts.size());
        return tenantView();
    }

    private TenantHooksResponse tenantView() {
        AgentHookConfig row = repository.findById(AgentHookConfig.TENANT_KEY).orElse(null);
        return new TenantHooksResponse(
            enabled,
            HookSchema.toMaps(readHooks(row, "tenant")),
            tenantHosts(row),
            platformHosts,
            row == null ? null : row.getUpdatedAt(),
            schema());
    }

    // ------------------------------------------------------------------ runtime

    /**
     * The hooks in force for an agent in the calling thread's tenant: workspace defaults then the agent's own.
     * Never throws; {@link HookSet#EMPTY} when the feature is off, there is no tenant context, or nothing is
     * configured (and nothing is cached from before).
     */
    public HookSet effective(UUID agentId) {
        if (!enabled || agentId == null) {
            return HookSet.EMPTY;
        }
        if (TenantContext.current().map(TenantContext::getTenantId).orElse(null) == null) {
            return HookSet.EMPTY;
        }
        String key = cacheKey(agentId);
        long now = System.currentTimeMillis();
        Cached cached = cache.get(key);
        if (cached != null && cached.expiresAtMillis() > now) {
            return cached.set();
        }
        try {
            AgentHookConfig tenantRow = repository.findById(AgentHookConfig.TENANT_KEY).orElse(null);
            AgentHookConfig agentRow = repository.findById(AgentHookConfig.keyFor(agentId)).orElse(null);
            List<EffectiveHook> all = new ArrayList<>();
            for (HookDefinition d : readHooks(tenantRow, "tenant")) {
                all.add(new EffectiveHook(EffectiveHook.SCOPE_TENANT, d));
            }
            for (HookDefinition d : readHooks(agentRow, "agent " + agentId)) {
                all.add(new EffectiveHook(EffectiveHook.SCOPE_AGENT, d));
            }
            HookSet set = all.isEmpty() ? HookSet.EMPTY : new HookSet(all, tenantHosts(tenantRow));
            if (cache.size() >= MAX_CACHE_ENTRIES) {
                cache.clear();
            }
            cache.put(key, new Cached(set, now + cacheTtlMillis));
            return set;
        } catch (RuntimeException e) {
            log.error("Could not load agent hooks for agent {} - using the last known set", agentId, e);
            return cached != null ? cached.set() : HookSet.EMPTY;
        }
    }

    // ------------------------------------------------------------------ helpers

    private List<HookDefinition> readHooks(AgentHookConfig row, String what) {
        if (row == null) {
            return List.of();
        }
        try {
            return HookSchema.parseStored(row.getHooksJson());
        } catch (RuntimeException e) {
            // Cannot happen through the API (writes are validated); a hand-edited row is skipped, loudly.
            log.error("Stored hooks for {} are invalid and were ignored: {}", what, e.getMessage());
            return List.of();
        }
    }

    private static List<String> tenantHosts(AgentHookConfig tenantRow) {
        if (tenantRow == null || tenantRow.getSettingsJson() == null || tenantRow.getSettingsJson().isBlank()) {
            return List.of();
        }
        try {
            JsonNode node = MAPPER.readTree(tenantRow.getSettingsJson()).get("webhookHosts");
            if (node == null || !node.isArray()) {
                return List.of();
            }
            List<String> out = new ArrayList<>();
            node.forEach(n -> {
                if (n.isTextual() && !n.asText().isBlank()) {
                    out.add(n.asText().trim().toLowerCase(Locale.ROOT));
                }
            });
            return List.copyOf(out);
        } catch (Exception e) {
            log.error("Stored hook settings are not valid JSON and were ignored", e);
            return List.of();
        }
    }

    /** Save-time webhook URL check: the merged operator + workspace allow-list. */
    private java.util.function.Function<String, String> urlCheck(List<String> tenantHosts) {
        List<String> merged = new ArrayList<>(platformHosts);
        merged.addAll(tenantHosts);
        return url -> HookWebhookClient.allowListProblem(url, merged);
    }

    static List<String> parseHostArray(JsonNode node) {
        if (!node.isArray()) {
            throw new IllegalArgumentException("webhookHosts must be an array of host names");
        }
        if (node.size() > MAX_WEBHOOK_HOSTS) {
            throw new IllegalArgumentException("at most " + MAX_WEBHOOK_HOSTS + " webhook hosts are allowed");
        }
        List<String> out = new ArrayList<>();
        for (JsonNode n : node) {
            if (!n.isTextual()) {
                throw new IllegalArgumentException("webhookHosts: every entry must be a string");
            }
            String host = n.asText().trim().toLowerCase(Locale.ROOT);
            if (host.length() > 253 || !HOST_PATTERN.matcher(host).matches()) {
                throw new IllegalArgumentException("webhookHosts: '" + n.asText() + "' is not a host name "
                    + "(use example.com or *.example.com - no scheme, port or path)");
            }
            if (!out.contains(host)) {
                out.add(host);
            }
        }
        return List.copyOf(out);
    }

    static List<String> parseHostCsv(String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String part : csv.split(",")) {
            String h = part.trim().toLowerCase(Locale.ROOT);
            if (!h.isEmpty() && !out.contains(h)) {
                out.add(h);
            }
        }
        return List.copyOf(out);
    }

    private static JsonNode requireHooksField(JsonNode body) {
        // The field must be PRESENT: a missing body or a misspelt field is a 400, so a client typo can never
        // silently clear a workspace's governance hooks.
        if (body == null || !body.isObject() || !body.has("hooks")) {
            throw new IllegalArgumentException("Request body must contain \"hooks\" (an array; use [] to clear)");
        }
        if (!body.get("hooks").isArray()) {
            throw new IllegalArgumentException("hooks must be an array");
        }
        return body.get("hooks");
    }

    private void requireEnabled() {
        if (!enabled) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Agent hooks are switched off on this deployment");
        }
    }

    private static boolean hasManageAuthority() {
        return TenantContext.current()
            .map(c -> c.hasRole("PERM_MANAGE_GUARDRAILS") || c.hasRole("ROLE_tenant-admin") || c.hasRole("ROLE_platform-admin"))
            .orElse(false);
    }

    /** Copies of the hook maps whose {@code params.url} is cut down to {@code https://host/...} (no path, query or fragment). */
    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> redactWebhookUrls(List<Map<String, Object>> hooks) {
        List<Map<String, Object>> out = new ArrayList<>(hooks.size());
        for (Map<String, Object> hook : hooks) {
            Object params = hook.get("params");
            if (params instanceof Map<?, ?> p && p.get("url") instanceof String url) {
                Map<String, Object> copy = new LinkedHashMap<>(hook);
                Map<String, Object> paramsCopy = new LinkedHashMap<>((Map<String, Object>) p);
                paramsCopy.put("url", hostOnly(url));
                copy.put("params", paramsCopy);
                out.add(copy);
            } else {
                out.add(hook);
            }
        }
        return out;
    }

    private static String hostOnly(String url) {
        try {
            String host = new java.net.URI(url.trim()).getHost();
            return host == null || host.isBlank() ? "(hidden)" : "https://" + host + "/...";
        } catch (java.net.URISyntaxException e) {
            return "(hidden)";
        }
    }

    private static void requireManageAuthority() {
        if (!hasManageAuthority()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Changing agent hooks needs the guardrails permission");
        }
    }

    private static String subject() {
        return TenantContext.current().map(TenantContext::getSubject).orElse(null);
    }

    private static String currentTenant() {
        return TenantContext.current().map(TenantContext::getTenantId).orElse("-");
    }

    private static String cacheKey(UUID agentId) {
        return currentTenant() + "|" + agentId;
    }

    private void invalidateTenant() {
        String prefix = currentTenant() + "|";
        cache.keySet().removeIf(k -> k.startsWith(prefix));
    }

    private void audit(String action, String targetType, String targetId, String detail) {
        try {
            auditLogService.record(subject(), action, targetType, targetId, AuditOutcome.SUCCESS, detail);
        } catch (RuntimeException e) {
            log.warn("Could not write the audit entry for {}", action, e);
        }
    }

    /** The server's own rules, for clients that build the editor from them. */
    static Schema schema() {
        List<String> points = Arrays.stream(HookPoint.values()).map(Enum::name).toList();
        Map<String, List<String>> byPoint = new LinkedHashMap<>();
        for (HookPoint p : HookPoint.values()) {
            List<String> actions = new ArrayList<>();
            for (HookActionType a : HookActionType.values()) {
                if (HookActionType.allowedAt(p).contains(a)) {
                    actions.add(a.wire());
                }
            }
            byPoint.put(p.name(), List.copyOf(actions));
        }
        List<String> trust = new ArrayList<>(EnumSet.allOf(ToolTrustTier.class).stream().map(Enum::name).toList());
        trust.add(HookMatch.UNCLASSIFIED);
        List<String> sensitivity = new ArrayList<>(EnumSet.allOf(ToolSensitivity.class).stream().map(Enum::name).toList());
        sensitivity.add(HookMatch.UNCLASSIFIED);
        return new Schema(
            HookSchema.MAX_HOOKS,
            HookSchema.DEFAULT_TIMEOUT_MS,
            HookSchema.MIN_TIMEOUT_MS,
            HookSchema.MAX_TIMEOUT_MS,
            points,
            byPoint,
            List.copyOf(new TreeSet<>(HookRedactor.builtinNames())),
            List.copyOf(trust),
            List.copyOf(sensitivity));
    }
}
