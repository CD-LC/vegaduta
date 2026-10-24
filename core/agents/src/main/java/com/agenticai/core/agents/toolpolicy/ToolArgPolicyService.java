package com.agenticai.core.agents.toolpolicy;

import com.agenticai.core.agents.core.Agent;
import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.agents.toolpolicy.ToolArgPolicyDtos.Limits;
import com.agenticai.core.agents.toolpolicy.ToolArgPolicyDtos.PoliciesResponse;
import com.agenticai.core.audit.AuditLogService;
import com.agenticai.core.audit.AuditOutcome;
import com.agenticai.core.guardrails.toolargs.ToolArgPolicyEvaluator;
import com.agenticai.core.guardrails.toolargs.ToolArgPolicySet;
import com.agenticai.core.guardrails.toolargs.ToolArgPolicySource;
import com.agenticai.core.json.JsonMappers;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.sharing.PermissionLevel;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Stores an agent's per-tool argument policies and serves them to the dispatch path.
 *
 * <p>Two faces: the REST side ({@link #get}, {@link #put}: access check, validation by {@link
 * ToolArgPolicySet#parse}, status codes decided here) and the {@link ToolArgPolicySource} side the guard chain
 * calls on every tool call. The hot side is answered from a small per-(tenant, agent) cache with a short TTL
 * ({@code app.tool-arg-policies.cache-ttl-seconds}), dropped on every PUT from this instance, so a normal
 * tool call costs a map read. Policies written through another instance take effect within one TTL.
 *
 * <p>Everything is behind {@code app.tool-arg-policies.enabled} (default false). With it off {@link
 * #isEnabled()} is false, the wrapper is never installed, nothing reads the table, and a PUT is a 409.
 */
@Service
public class ToolArgPolicyService implements ToolArgPolicySource {

    private static final Logger log = LoggerFactory.getLogger(ToolArgPolicyService.class);

    static final String AUDIT_ACTION = "tool.arg_policy.denied";
    static final String DENIED_METRIC = "agent.tool.arg_policy.denied";
    private static final int CACHE_MAX = 2_000;
    private static final ObjectMapper MAPPER = JsonMappers.plain();

    private record Cached(ToolArgPolicyEvaluator evaluator, long expiresAtMillis) {
    }

    private final AgentService agentService;
    private final AgentToolArgPolicyRepository repository;
    private final ObjectProvider<AuditLogService> auditLog;
    private final ObjectProvider<MeterRegistry> meterRegistry;
    private final boolean enabled;
    private final long cacheTtlMillis;
    private final ConcurrentHashMap<String, Cached> cache = new ConcurrentHashMap<>();

    public ToolArgPolicyService(
        AgentService agentService,
        AgentToolArgPolicyRepository repository,
        ObjectProvider<AuditLogService> auditLog,
        ObjectProvider<MeterRegistry> meterRegistry,
        @Value("${app.tool-arg-policies.enabled:true}") boolean enabled,
        @Value("${app.tool-arg-policies.cache-ttl-seconds:30}") long cacheTtlSeconds
    ) {
        this.agentService = agentService;
        this.repository = repository;
        this.auditLog = auditLog;
        this.meterRegistry = meterRegistry;
        this.enabled = enabled;
        this.cacheTtlMillis = Math.max(0L, cacheTtlSeconds) * 1000L;
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    // ------------------------------------------------------------------
    // REST side
    // ------------------------------------------------------------------

    /** Anyone who can view the agent can read its policies (404 otherwise, via requireAccess). */
    public PoliciesResponse get(UUID agentId) {
        agentService.requireAccess(agentId, PermissionLevel.VIEWER);
        Optional<AgentToolArgPolicy> row = enabled ? repository.findById(agentId) : Optional.empty();
        if (row.isEmpty()) {
            return new PoliciesResponse(enabled, ToolArgPolicySet.empty().toJsonNode(), limits(), null, null);
        }
        JsonNode doc;
        try {
            doc = MAPPER.readTree(row.get().getPoliciesJson());
        } catch (Exception e) {
            doc = ToolArgPolicySet.empty().toJsonNode();
        }
        return new PoliciesResponse(enabled, doc, limits(), row.get().getUpdatedBy(), row.get().getUpdatedAt());
    }

    /**
     * Validates and stores the document; an empty policy list clears the agent's policies.
     *
     * @throws IllegalArgumentException (400) with a precise message when the document is invalid
     * @throws ResponseStatusException 409 when the feature is switched off on this deployment
     */
    public PoliciesResponse put(UUID agentId, JsonNode body) {
        Agent agent = agentService.requireAccess(agentId, PermissionLevel.EDITOR);
        if (!enabled) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Tool argument policies are switched off on this deployment");
        }
        agentService.assertEditable(agent);
        if (body == null || !body.isObject()) {
            throw new IllegalArgumentException("The request body must be a JSON object with a 'policies' array.");
        }
        ToolArgPolicySet set = ToolArgPolicySet.parse(body);
        String subject = TenantContext.current().map(TenantContext::getSubject).orElse(null);
        if (set.isEmpty()) {
            repository.findById(agentId).ifPresent(repository::delete);
            invalidate(agentId);
            return new PoliciesResponse(true, set.toJsonNode(), limits(), subject, null);
        }
        String json = set.toJson();
        AgentToolArgPolicy row = repository.findById(agentId).orElse(null);
        if (row == null) {
            row = new AgentToolArgPolicy(agentId, json, subject);
        } else {
            row.replace(json, subject);
        }
        AgentToolArgPolicy saved = repository.save(row);
        invalidate(agentId);
        return new PoliciesResponse(true, set.toJsonNode(), limits(), saved.getUpdatedBy(), saved.getUpdatedAt());
    }

    static Limits limits() {
        return new Limits(
            ToolArgPolicySet.MAX_POLICIES, ToolArgPolicySet.MAX_RULES_PER_POLICY,
            ToolArgPolicySet.MAX_DOCUMENT_CHARS, ToolArgPolicySet.MAX_PATTERN_LENGTH,
            ToolArgPolicySet.MAX_ENUM_VALUES);
    }

    // ------------------------------------------------------------------
    // Dispatch side
    // ------------------------------------------------------------------

    @Override
    public ToolArgPolicyEvaluator evaluatorFor(UUID agentId) {
        if (!enabled || agentId == null) {
            return null;
        }
        String key = cacheKey(agentId);
        long now = System.currentTimeMillis();
        Cached cached = cache.get(key);
        if (cached != null && cached.expiresAtMillis() > now) {
            return cached.evaluator();
        }
        ToolArgPolicyEvaluator evaluator = load(agentId);
        if (cache.size() >= CACHE_MAX) {
            cache.clear();
        }
        cache.put(key, new Cached(evaluator, now + cacheTtlMillis));
        return evaluator;
    }

    /** Null when the agent has no (parseable) policies. */
    private ToolArgPolicyEvaluator load(UUID agentId) {
        Optional<AgentToolArgPolicy> row = repository.findById(agentId);
        if (row.isEmpty()) {
            return null;
        }
        try {
            ToolArgPolicySet set = ToolArgPolicySet.parse(row.get().getPoliciesJson());
            return set.isEmpty() ? null : new ToolArgPolicyEvaluator(set);
        } catch (IllegalArgumentException e) {
            // Stored before a limit was tightened, or edited by hand: cannot be enforced. Loud, not silent.
            log.error("Stored tool argument policies for agent {} no longer validate and are NOT enforced: {}",
                agentId, e.getMessage());
            count("agent.tool.arg_policy.unparseable", "none");
            return null;
        }
    }

    private void invalidate(UUID agentId) {
        cache.remove(cacheKey(agentId));
    }

    private static String cacheKey(UUID agentId) {
        return TenantContext.current().map(TenantContext::getTenantId).orElse("-") + "|" + agentId;
    }

    @Override
    public void onViolation(UUID agentId, String sessionId, String toolName, String reason) {
        count(DENIED_METRIC, toolName);
        AuditLogService audit = auditLog.getIfAvailable();
        if (audit == null) {
            return;
        }
        String target = toolName == null ? "" : (toolName.length() <= 128 ? toolName : toolName.substring(0, 128));
        String detail = "session=" + sessionId + "; " + (reason == null ? "" : reason);
        audit.record("agent:" + agentId, AUDIT_ACTION, "AGENT_TOOL", target, AuditOutcome.REJECTED,
            detail.length() <= 2000 ? detail : detail.substring(0, 2000));
    }

    private void count(String name, String tool) {
        MeterRegistry meters = meterRegistry.getIfAvailable();
        if (meters == null) {
            return;
        }
        try {
            meters.counter(name, "tool", tool == null ? "none" : tool).increment();
        } catch (RuntimeException e) {
            log.debug("Could not record metric {}", name, e);
        }
    }
}
