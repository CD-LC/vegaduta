package com.agenticai.core.guardrails.procedures;

import com.agenticai.core.security.TenantContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * The deterministic half of the procedures layer - consulted by {@code GuardedToolCallback#call}
 * BEFORE the delegate tool runs, exactly like {@code TenantGuardrailRuleEvaluator} is consulted
 * after it. Every check here is data-only: no LLM calls, no I/O beyond the per-agent rule lookup
 * (itself a tenant-schema read on the turn's already-resolved schema).
 *
 * <p>{@code REQUIRE_TOOL_BEFORE} ordering is tracked per chat session in {@link #sessionCalls}: a
 * tool name joins the session's set only AFTER its delegate call actually ran (recorded by {@link
 * #recordCall} from the callback, never from inside {@link #check}, so a denied call can never
 * satisfy its own prerequisite). Session scope is the strongest ordering guarantee this seam can
 * give deterministically - a prerequisite called three turns ago still counts, which is the
 * documented semantic ("the agent has verified identity at some point in this conversation"), not a
 * bug.
 *
 * <p>Fail-open on lookup failure, matching {@code AgentToolProvider}'s own skills-resolution
 * posture: procedures are an enforcement enhancement and a broken rules read must never hard-stop a
 * turn - a WARN is logged and the call proceeds to the normal policy gate.
 *
 * <p>Multi-replica: the per-session set above is per JVM. With {@code
 * app.guardrails.procedures.session-calls-store=redis} every recorded call is also written to Redis
 * asynchronously ({@link ProcedureSessionCallsRedis}) and Redis is consulted only when a
 * REQUIRE_TOOL_BEFORE check would otherwise deny, so a turn that lands on another replica does not
 * wrongly lose its history. Default {@code memory} = today's behaviour.
 */
@Component
public class ProcedureGuardEvaluator {

    private static final Logger log = LoggerFactory.getLogger(ProcedureGuardEvaluator.class);

    /** Backstop so a long-lived deployment can't grow session history without bound. */
    private static final int MAX_TRACKED_SESSIONS = 10_000;

    private static final int MAX_TOOLS_PER_SESSION = 500;

    public enum Outcome {
        ALLOW,
        DENY,
        REQUIRE_APPROVAL
    }

    /**
     * @param procedureName / {@code ruleInstruction} name the violated rule so the model-facing
     *     explanation can cite the actual business rule, not a bare "denied".
     */
    public record Decision(Outcome outcome, String procedureName, String ruleInstruction) {

        static Decision allow() {
            return new Decision(Outcome.ALLOW, null, null);
        }

        static Decision deny(String procedureName, String instruction) {
            return new Decision(Outcome.DENY, procedureName, instruction);
        }

        static Decision requireApproval(String procedureName, String instruction) {
            return new Decision(Outcome.REQUIRE_APPROVAL, procedureName, instruction);
        }
    }

    private final ProcedureService procedureService;
    private final ObjectMapper objectMapper;

    /** sessionId -> names of tools that have actually executed in this session. */
    private final Map<String, Set<String>> sessionCalls = new ConcurrentHashMap<>();

    /**
     * {@code memory} (default) or {@code redis}; field-injected so a hand-built instance keeps the
     * default.
     */
    @Value("${app.guardrails.procedures.session-calls-store:memory}")
    private String store = "memory";

    private volatile ObjectProvider<StringRedisTemplate> redisProvider;
    private volatile ProcedureSessionCallsRedis sharedCalls;

    public ProcedureGuardEvaluator(ProcedureService procedureService, ObjectMapper objectMapper) {
        this.procedureService = procedureService;
        this.objectMapper = objectMapper;
    }

    /**
     * Setter-injected and optional so the constructor stays unchanged; only consulted when {@code
     * store=redis}.
     */
    @Autowired(required = false)
    void setRedisProvider(ObjectProvider<StringRedisTemplate> provider) {
        this.redisProvider = provider;
    }

    /** Test seam: use these shared calls regardless of the store property. */
    void useSharedCalls(ProcedureSessionCallsRedis shared) {
        this.sharedCalls = shared;
    }

    private ProcedureSessionCallsRedis shared() {
        ProcedureSessionCallsRedis ready = sharedCalls;
        if (ready != null) {
            return ready;
        }
        if (!"redis".equalsIgnoreCase(store == null ? "" : store.strip())) {
            return null;
        }
        ObjectProvider<StringRedisTemplate> provider = redisProvider;
        StringRedisTemplate template = provider == null ? null : provider.getIfAvailable();
        if (template == null) {
            return null;
        }
        synchronized (this) {
            if (sharedCalls == null) {
                sharedCalls = new ProcedureSessionCallsRedis(template);
            }
            return sharedCalls;
        }
    }

    private static String currentTenant() {
        return TenantContext.current()
                .map(TenantContext::getTenantId)
                .filter(t -> t != null && !t.isBlank())
                .orElse("_none");
    }

    /**
     * Redis fallback for a REQUIRE_TOOL_BEFORE check that local memory could not satisfy; adopts a
     * hit locally.
     */
    private boolean ranOnAnotherReplica(String sessionId, String prior) {
        ProcedureSessionCallsRedis redis = sessionId == null ? null : shared();
        if (redis == null || !redis.hasRun(currentTenant(), sessionId, prior)) {
            return false;
        }
        sessionCalls.computeIfAbsent(sessionId, k -> ConcurrentHashMap.newKeySet()).add(prior);
        return true;
    }

    /**
     * Evaluates every guard clause of every ACTIVE procedure attached to {@code agentId} against
     * this one proposed tool call. First match wins (rules are position-ordered, so an author
     * controls precedence by ordering their procedures).
     */
    public Decision check(UUID agentId, String sessionId, String toolName) {
        List<ProcedureService.ResolvedProcedure> procedures;
        try {
            procedures = procedureService.activeRulesForAgent(agentId);
        } catch (RuntimeException e) {
            log.warn(
                    "Could not load procedures for agent {} - proceeding to the normal policy gate: {}",
                    agentId,
                    e.getMessage());
            return Decision.allow();
        }
        for (ProcedureService.ResolvedProcedure resolved : procedures) {
            for (ProcedureRule rule : resolved.rules()) {
                Decision decision =
                        checkRule(resolved.procedure().getName(), rule, sessionId, toolName);
                if (decision.outcome() != Outcome.ALLOW) {
                    return decision;
                }
            }
        }
        return Decision.allow();
    }

    /**
     * Marks {@code toolName} as having genuinely executed in {@code sessionId} - called by {@code
     * GuardedToolCallback} only after {@code delegate.call} returned. A call that was denied, gated
     * for approval, or soft-stopped upstream never reaches this, so it can never count as a {@code
     * REQUIRE_TOOL_BEFORE} prerequisite.
     */
    public void recordCall(String sessionId, String toolName) {
        if (sessionId == null || toolName == null) {
            return;
        }
        if (sessionCalls.size() > MAX_TRACKED_SESSIONS) {
            // Crude but bounded: a full map means pathological session churn;
            // dropping all history degrades BEFORE rules to "must re-run the
            // prerequisite this session" - stricter, never looser.
            sessionCalls.clear();
        }
        Set<String> calls =
                sessionCalls.computeIfAbsent(sessionId, k -> ConcurrentHashMap.newKeySet());
        if (calls.size() < MAX_TOOLS_PER_SESSION) {
            calls.add(toolName);
        }
        ProcedureSessionCallsRedis redis = shared();
        if (redis != null) {
            redis.recordAsync(
                    currentTenant(),
                    sessionId,
                    toolName); // asynchronous: nothing waits on Redis here
        }
    }

    /** Test/console seam - drops one session's ordering history. */
    public void clearSession(String sessionId) {
        if (sessionId != null) {
            sessionCalls.remove(sessionId);
        }
    }

    private Decision checkRule(
            String procedureName, ProcedureRule rule, String sessionId, String toolName) {
        if (rule.getGuardKind() == null
                || rule.getGuardTool() == null
                || !rule.getGuardTool().equals(toolName)) {
            return Decision.allow();
        }
        return switch (rule.getGuardKind()) {
            case FORBID_TOOL -> Decision.deny(procedureName, rule.getInstruction());
            case REQUIRE_APPROVAL -> Decision.requireApproval(procedureName, rule.getInstruction());
            case REQUIRE_TOOL_BEFORE -> {
                String prior = priorTool(rule);
                if (prior == null) {
                    // Authoring validation requires "prior" - a row that still
                    // lacks it (hand-edited DB) fails closed on the gated tool,
                    // not open: the rule's intent is unambiguous.
                    yield Decision.deny(procedureName, rule.getInstruction());
                }
                Set<String> calls = sessionCalls.get(sessionId);
                boolean ran = calls != null && calls.contains(prior);
                if (!ran) {
                    ran = ranOnAnotherReplica(sessionId, prior);
                }
                yield ran ? Decision.allow() : Decision.deny(procedureName, rule.getInstruction());
            }
        };
    }

    private String priorTool(ProcedureRule rule) {
        if (rule.getGuardConfig() == null) {
            return null;
        }
        try {
            JsonNode prior = objectMapper.readTree(rule.getGuardConfig()).get("prior");
            return prior != null && !prior.asText().isBlank() ? prior.asText() : null;
        } catch (Exception e) {
            return null;
        }
    }
}
