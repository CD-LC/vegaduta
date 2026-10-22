package com.agenticai.core.agents.tools;

import com.agenticai.core.agents.tools.classification.ToolClassification;
import com.agenticai.core.agents.tools.classification.ToolClassificationDecision;
import com.agenticai.core.agents.tools.classification.ToolClassificationRegistry;
import com.agenticai.core.audit.AuditLogService;
import com.agenticai.core.audit.AuditOutcome;
import com.agenticai.core.guardrails.taint.TaintGate;
import com.agenticai.core.guardrails.taint.TaintMode;
import com.agenticai.core.guardrails.taint.ToolTaintClassifier;
import com.agenticai.core.guardrails.taint.ToolTaintProfile;
import com.agenticai.core.guardrails.taint.TurnTaint;
import com.agenticai.core.json.JsonMappers;
import com.agenticai.core.security.TenantContext;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.URI;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Wires the untrusted-content taint layer ({@code app.agents.taint.mode}, see {@link TaintMode})
 * into the tool chain: owns the per-turn {@link TurnTaint}, answers "does this tool produce tainted
 * output / is this call a side effect" from the tool-classification registry, and builds the {@link
 * TaintGate} that {@code GuardedToolCallback} consults.
 *
 * <h2>Classification rules (conservative)</h2>
 *
 * <ul>
 *   <li>Classified tool: it is a taint <i>source</i> when its trust tier is {@code
 *       UNTRUSTED_EXTERNAL} (web fetch, third-party MCP, retrieval over user-supplied documents,
 *       inbound channel reads, delegated agents) and a <i>sink</i> when its sensitivity is a write,
 *       credentialed or egress capability. MCP tools carry their {@code readOnlyHint} here when
 *       {@code app.tools.mcp-annotations.enabled} is on: a read-only MCP tool is not a sink.
 *   <li>Unclassified tool: a <i>sink</i> (unknown means side-effecting). It is a source only when
 *       it is an MCP tool ({@code mcp__} prefix), because an unvetted third-party server is exactly
 *       the untrusted case; other unknown first-party tools are not assumed to carry foreign
 *       content, or every unclassified read would taint every turn.
 *   <li>{@code http_request} / {@code fetch_web_page} / {@code browser_navigate} are classified
 *       read-only by name, but the call itself can write or leak: a non-GET/HEAD/OPTIONS method is
 *       a sink, and when {@code app.agents.taint.http-get-allow-hosts} is set a GET to any other
 *       host is a sink too (a query string is an exfiltration channel). Residual: {@code
 *       deep_research}, {@code verify_citations} and {@code web_search} take a query, not a URL, so
 *       the host list cannot cover them; with the list unset (the default, also in {@code
 *       personal}) no GET is a sink.
 * </ul>
 *
 * <p><b>Default off.</b> Without the {@code personal} profile (or an explicit setting) the layer is
 * inert: no gate is built, no classification is read, nothing is audited. {@code observe} is
 * opt-in; it does the classification work on every guarded tool call and writes one audit row per
 * tool per tainted turn.
 *
 * <p>Verdicts are memoised per tenant and tool for 60 seconds (the registry read is a database
 * lookup), so the steady-state cost per tool call is one map read. Nothing here crosses a thread
 * boundary with a thread-local: the turn state is a plain object captured by the tool wrappers.
 */
@Component
public class AgentTaintPolicy implements ToolTaintClassifier {

    private static final Logger log = LoggerFactory.getLogger(AgentTaintPolicy.class);

    static final String MCP_PREFIX = "mcp__";
    private static final Set<String> READ_METHODS = Set.of("GET", "HEAD", "OPTIONS");

    /**
     * Read tools whose {@code url} argument is a data channel: a query string can carry context
     * out.
     */
    private static final Set<String> HTTP_TOOLS =
            Set.of("http_request", "fetch_web_page", "browser_navigate");

    private static final long CACHE_TTL_MILLIS = 60_000L;
    private static final int CACHE_MAX = 2_000;
    private static final int AUDIT_TARGET_MAX = 128;
    private static final ObjectMapper JSON = JsonMappers.plain();

    private record Cached(ToolTaintProfile profile, long expiresAtMillis) {}

    private final TaintMode mode;
    private final Set<String> getAllowHosts;
    private final ObjectProvider<ToolClassificationRegistry> registry;
    private final ObjectProvider<MeterRegistry> meters;
    private final ObjectProvider<AuditLogService> audit;
    private final ConcurrentHashMap<String, Cached> cache = new ConcurrentHashMap<>();

    /**
     * Per-turn state keyed by the turn's shared step counter (identity, weakly held so it dies with
     * the turn) - the same hand-off {@code AgentToolGuard#turnIdFor} uses, so no signature on the
     * ~45 resolve helpers changes.
     */
    private static final Map<AtomicInteger, TurnTaint> TURNS =
            Collections.synchronizedMap(new WeakHashMap<>());

    public AgentTaintPolicy(
            @Value("${app.agents.taint.mode:off}") String mode,
            @Value("${app.agents.taint.http-get-allow-hosts:}") String getAllowHosts,
            ObjectProvider<ToolClassificationRegistry> registry,
            ObjectProvider<MeterRegistry> meters,
            ObjectProvider<AuditLogService> audit) {
        this.mode = TaintMode.parse(mode);
        this.getAllowHosts = parseHosts(getAllowHosts);
        this.registry = registry;
        this.meters = meters;
        this.audit = audit;
    }

    public TaintMode mode() {
        return mode;
    }

    /** The turn's taint state (created on first use), or null when the layer is off. */
    public TurnTaint turnTaint(AtomicInteger turnKey) {
        if (mode == TaintMode.OFF || turnKey == null) {
            return null;
        }
        return TURNS.computeIfAbsent(turnKey, k -> new TurnTaint());
    }

    /**
     * Marks the turn tainted before any tool ran, for content that entered through a channel other
     * than a tool result (for example an inbound message from a non-owner sender). No-op when off.
     */
    public void seed(AtomicInteger turnKey, String source) {
        TurnTaint turn = turnTaint(turnKey);
        if (turn != null) {
            turn.mark(source);
        }
    }

    /**
     * The gate for one tool of one turn, or null when the layer is off (the chain is unchanged).
     */
    public TaintGate gateFor(AtomicInteger turnKey, UUID agentId, String sessionId) {
        TurnTaint turn = turnTaint(turnKey);
        if (turn == null) {
            return null;
        }
        return new TaintGate(
                mode, turn, this, meters.getIfAvailable(), this::audit, agentId, sessionId);
    }

    @Override
    public ToolTaintProfile profile(String toolName, String toolInput) {
        ToolTaintProfile base = baseProfile(toolName);
        if (!base.sink() && toolName != null && HTTP_TOOLS.contains(toolName)) {
            return refineHttp(base, toolName, toolInput);
        }
        return base;
    }

    private ToolTaintProfile baseProfile(String toolName) {
        String tenant = TenantContext.current().map(TenantContext::getTenantId).orElse("-");
        String key = tenant + "|" + toolName;
        long now = System.currentTimeMillis();
        Cached cached = cache.get(key);
        if (cached != null && cached.expiresAtMillis() > now) {
            return cached.profile();
        }
        ToolTaintProfile resolved = resolve(toolName);
        if (cache.size() >= CACHE_MAX) {
            cache.clear();
        }
        cache.put(key, new Cached(resolved, now + CACHE_TTL_MILLIS));
        return resolved;
    }

    private ToolTaintProfile resolve(String toolName) {
        boolean mcp = toolName != null && toolName.startsWith(MCP_PREFIX);
        ToolClassificationRegistry resolver = registry.getIfAvailable();
        if (resolver == null) {
            return new ToolTaintProfile(mcp, true, "unclassified");
        }
        ToolClassificationDecision decision = resolver.resolve(toolName);
        if (decision instanceof ToolClassificationDecision.Classified classified) {
            ToolClassification c = classified.classification();
            return new ToolTaintProfile(c.producesTaintedOutput(), c.isTaintSink(), c.riskLabel());
        }
        return new ToolTaintProfile(mcp, true, "unclassified");
    }

    /** Argument-aware tightening for the two generic HTTP tools; never loosens. */
    private ToolTaintProfile refineHttp(ToolTaintProfile base, String toolName, String toolInput) {
        if (toolInput == null || toolInput.isBlank()) {
            return base;
        }
        try {
            JsonNode args = JSON.readTree(toolInput);
            String method = args.path("method").asString("GET").trim().toUpperCase(Locale.ROOT);
            if (!method.isEmpty() && !READ_METHODS.contains(method)) {
                return new ToolTaintProfile(
                        base.producesTaint(), true, base.label() + "+" + method);
            }
            if (!getAllowHosts.isEmpty() && !hostAllowed(args.path("url").asString(""))) {
                return new ToolTaintProfile(
                        base.producesTaint(), true, base.label() + "+host_not_allowlisted");
            }
            return base;
        } catch (Exception e) {
            // Unparseable arguments on a tool that sends requests: fail closed.
            return new ToolTaintProfile(
                    base.producesTaint(), true, base.label() + "+unparseable_args");
        }
    }

    private boolean hostAllowed(String url) {
        try {
            String host = URI.create(url.trim()).getHost();
            if (host == null) {
                return false;
            }
            String h = host.toLowerCase(Locale.ROOT);
            for (String allowed : getAllowHosts) {
                if (h.equals(allowed) || h.endsWith("." + allowed)) {
                    return true;
                }
            }
            return false;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static Set<String> parseHosts(String raw) {
        if (raw == null || raw.isBlank()) {
            return Set.of();
        }
        Set<String> out = new java.util.HashSet<>();
        for (String h : raw.split(",")) {
            if (!h.isBlank()) {
                out.add(h.trim().toLowerCase(Locale.ROOT));
            }
        }
        return Set.copyOf(out);
    }

    private void audit(String action, AuditOutcome outcome, String toolName, String detail) {
        AuditLogService service = audit.getIfAvailable();
        if (service == null) {
            return;
        }
        try {
            // audit_log_entry.target_id is varchar(128); an MCP tool name can exceed it, and a
            // failed
            // insert would silently lose exactly the evidence this layer exists to keep.
            String target =
                    toolName != null && toolName.length() > AUDIT_TARGET_MAX
                            ? toolName.substring(0, AUDIT_TARGET_MAX)
                            : toolName;
            service.record("agent-taint", action, "tool", target, outcome, detail);
        } catch (RuntimeException e) {
            log.warn("Could not record taint audit entry for tool '{}'", toolName, e);
        }
    }
}
