package com.agenticai.core.agents.tools.classification;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Turns the safety hints an MCP server publishes on its tools ({@link McpToolHints}) into a
 * {@link ToolClassification} for the dynamic {@code mcp__<server>__<tool>} name, so a tool the
 * platform never declared in code is judged by the gates that already exist (the irreversible-action
 * confirmation gate, the taint gate and the lethal-trifecta detector) instead of being allowed
 * unseen.
 *
 * <h2>Where it sits in resolution</h2>
 * {@link ToolClassificationRegistry} consults this class only AFTER a stored exact row, a stored
 * wildcard row and the compiled-in declarations have all missed. The per-tenant
 * {@code tool_classification} table (V224) is the admin/tenant override table: a tenant that
 * disagrees with a server's hints writes an exact or {@code mcp__server__*} row and that row wins -
 * nothing here needs a second override mechanism. A tool the server did not annotate (or annotated
 * only with a title) resolves exactly as it did before this class existed: UNCLASSIFIED, allowed and
 * counted.
 *
 * <h2>Hints can only tighten</h2>
 * The MCP spec says annotations from a server that is not trusted must themselves be treated as
 * untrusted. Every mapping below therefore moves a tool from "unjudged and allowed" to "judged and
 * at least as constrained": the trust tier is always {@code UNTRUSTED_EXTERNAL} (a server's output is
 * third-party data whatever it claims), a read-only claim never reaches a tier the gates relax for,
 * and a hostile server that lies "destructive" can only buy itself an approval prompt.
 * The one place a classification could RELAX behaviour is the replay-safety verdict
 * ({@code AgentToolGovernance#replaySafeVerdict}); it resolves through
 * {@code ToolClassificationRegistry#resolveIgnoringMcpAnnotations}, so an annotation-derived
 * classification is never consulted there and a server claiming "read-only" cannot get a mutating tool
 * replayed.
 *
 * <h2>Known limits</h2>
 * An {@code openWorldHint=true} tool that also claims {@code readOnlyHint=true} is classified a READ
 * (the {@code fetch_web_page} precedent), so it is neither a taint sink nor an egress leg of the lethal
 * trifecta; and a read of the server's data supplies the private-read and untrusted-input legs by
 * itself, which can over-flag an agent that holds only such tools. Both are accepted for the first
 * flag-on rollout and are overridable per tool or per server with a {@code tool_classification} row.
 *
 * <h2>Mapping</h2>
 * <pre>
 * readOnlyHint=true                 -> READ_TENANT, SIDE_EFFECT, not irreversible
 *                                      (a read of the server's data: disclosure, not a mutation or a
 *                                      taint sink; an openWorldHint=true read is still a READ, the same
 *                                      precedent as fetch_web_page)
 * otherwise (any risk hint given):
 *   destructive  = destructiveHint != false      (spec default is true for a non-read-only tool)
 *   openWorld    = openWorldHint == true         (only an explicit claim elevates; the spec default of
 *                                                 true is NOT assumed, to keep "unannotated unchanged")
 *   sensitivity  = openWorld ? EGRESS : WRITE_TENANT
 *   irreversible = destructive
 *   determinism  = DURABLE, except idempotentHint=true on a non-destructive, closed-world write ->
 *                  SIDE_EFFECT (a retry cannot double the effect)
 * </pre>
 * {@code destructive + (WRITE_TENANT|EGRESS)} satisfies {@link ToolClassification#requiresHumanConfirmation()},
 * so the existing confirmation gate prompts for it with no new approval path.
 *
 * <h2>State</h2>
 * Hints are captured at discovery time (connect and {@code tools/list_changed} for tenant-registered
 * servers, static discovery for operator-wired ones) into an in-memory map keyed by tenant scope and
 * the final namespaced tool name. It is deliberately not persisted: it is re-populated on every
 * (re)connect, so a hint can never outlive the connection that stated it, and no migration is needed.
 * Static servers are one process shared by every tenant, so they are recorded under
 * {@link #GLOBAL_SCOPE} and consulted after the tenant's own scope.
 *
 * <p>Flag: {@code app.tools.mcp-annotations.enabled}, default {@code false}. Off, nothing is captured
 * and {@link #classify} always answers empty - behaviour is identical to before this class existed.
 */
@Component
public class McpAnnotationClassifier {

    /** Scope under which operator-wired (static) MCP servers' hints are recorded; shared by all tenants. */
    public static final String GLOBAL_SCOPE = "*";

    /** Platform-visible prefix of every MCP tool name ({@code NamespacedMcpToolCallback}). */
    static final String MCP_TOOL_PREFIX = "mcp__";

    /** Upper bound on remembered tools so a server flapping its tool list cannot grow the map without limit. */
    static final int MAX_ENTRIES = 20_000;

    private final boolean enabled;
    private final ConcurrentHashMap<String, McpToolHints> hintsByScopedName = new ConcurrentHashMap<>();

    public McpAnnotationClassifier(@Value("${app.tools.mcp-annotations.enabled:false}") boolean enabled) {
        this.enabled = enabled;
    }

    /** True when MCP annotations are being captured and mapped. */
    public boolean isEnabled() {
        return enabled;
    }

    private static String key(String scope, String toolName) {
        return scope + '\u0000' + toolName;
    }

    /**
     * Replaces everything recorded for one server with its freshly discovered tool list.
     *
     * @param scope           the tenant id, or {@link #GLOBAL_SCOPE} for a static server
     * @param serverNamePrefix the {@code mcp__<server>__} prefix every tool of this server carries; used to drop
     *                        hints of tools the server no longer advertises
     * @param hintsByToolName final (namespaced) tool name to its hints; tools without a risk hint are not stored
     */
    public void replaceServer(String scope, String serverNamePrefix, Map<String, McpToolHints> hintsByToolName) {
        if (!enabled || scope == null || serverNamePrefix == null) {
            return;
        }
        String keyPrefix = key(scope, serverNamePrefix);
        hintsByScopedName.keySet().removeIf(k -> k.startsWith(keyPrefix));
        if (hintsByToolName == null) {
            return;
        }
        for (Map.Entry<String, McpToolHints> entry : hintsByToolName.entrySet()) {
            McpToolHints hints = entry.getValue();
            if (entry.getKey() == null || hints == null || !hints.hasRiskHint()) {
                continue;
            }
            if (hintsByScopedName.size() >= MAX_ENTRIES) {
                return;
            }
            hintsByScopedName.put(key(scope, entry.getKey()), hints);
        }
    }

    /** Drops every hint recorded for one server. */
    public void forgetServer(String scope, String serverNamePrefix) {
        replaceServer(scope, serverNamePrefix, null);
    }

    /**
     * Drops the hints of exactly these final tool names (a tenant disconnect/disable: the disconnect path
     * knows the tools it is dropping, not the sanitised server segment, so it names them directly).
     */
    public void forgetTools(String scope, java.util.Collection<String> toolNames) {
        if (!enabled || scope == null || toolNames == null) {
            return;
        }
        for (String toolName : toolNames) {
            if (toolName != null) {
                hintsByScopedName.remove(key(scope, toolName));
            }
        }
    }

    /**
     * The classification derived from the recorded hints for {@code toolName}, or empty when the flag is
     * off, the name is not an MCP tool, or the server stated no risk hint for it.
     *
     * @param tenantId the calling tenant (may be null: only the global scope is then consulted)
     */
    public Optional<ToolClassification> classify(String tenantId, String toolName) {
        if (!enabled || toolName == null || !toolName.startsWith(MCP_TOOL_PREFIX)) {
            return Optional.empty();
        }
        McpToolHints hints = tenantId == null ? null : hintsByScopedName.get(key(tenantId, toolName));
        if (hints == null) {
            hints = hintsByScopedName.get(key(GLOBAL_SCOPE, toolName));
        }
        return map(toolName, hints);
    }

    /** The pure hint-to-classification mapping documented on the class. Empty for "no risk hint". */
    public static Optional<ToolClassification> map(String toolName, McpToolHints hints) {
        if (toolName == null || toolName.isBlank() || hints == null || !hints.hasRiskHint()) {
            return Optional.empty();
        }
        if (Boolean.TRUE.equals(hints.readOnly())) {
            return Optional.of(new ToolClassification(
                toolName, ToolTrustTier.UNTRUSTED_EXTERNAL, ToolSensitivity.READ_TENANT,
                ToolDeterminism.SIDE_EFFECT, null, false, true));
        }
        boolean destructive = !Boolean.FALSE.equals(hints.destructive());
        boolean openWorld = Boolean.TRUE.equals(hints.openWorld());
        ToolSensitivity sensitivity = openWorld ? ToolSensitivity.EGRESS : ToolSensitivity.WRITE_TENANT;
        boolean retrySafe = Boolean.TRUE.equals(hints.idempotent()) && !destructive && !openWorld;
        ToolDeterminism determinism = retrySafe ? ToolDeterminism.SIDE_EFFECT : ToolDeterminism.DURABLE;
        return Optional.of(new ToolClassification(
            toolName, ToolTrustTier.UNTRUSTED_EXTERNAL, sensitivity, determinism, null, destructive, true));
    }
}
