package com.agenticai.core.agents.tools.classification;

import com.agenticai.core.agents.tools.AgentToolGovernance;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only HTTP surface over the tool classification registry.
 *
 * <p><b>Why this exists separately from the registry.</b> {@link ToolClassificationRegistry} is the
 * in-process decision API: the agent runtime asks it "may this tool run" and gets a sealed {@link
 * ToolClassificationDecision} back. That is the wrong shape for a console: a human does not want
 * one verdict, they want the whole table at once so they can see which of their tools read
 * untrusted content, which hold credentials, and which nobody has described yet. This controller is
 * that projection and nothing more - it makes no decisions and changes no state.
 *
 * <p><b>Why the response merges two sources.</b> A classification can come from {@link
 * BuiltInToolClassifications} (compiled into the platform, covering the first-party tools) or from
 * the tenant's own {@code tool_classification} rows (declared for MCP-discovered and
 * tenant-authored tools). The console needs one list, and a stored row must win over a built-in
 * declaration of the same tool - otherwise an admin who deliberately reclassified something would
 * keep seeing the platform default and reasonably conclude their change was lost. Built-in
 * <i>patterns</i> are deliberately excluded: a pattern such as {@code mcp_*} is a fallback rule,
 * not a tool that exists, and listing it as a row would invent a tool the tenant does not have.
 *
 * <p><b>Why {@code enforced} is hard-coded false, and why that is the honest answer.</b> The
 * registry's own contract is explicit that nothing in it refuses a tool call today - it resolves
 * and records, and the dispatch path ({@code AgentToolProvider} / {@code GuardedToolCallback}) has
 * not been wired to consult it. Reporting {@code enforced: true} would tell an operator that
 * unclassified tools are being blocked when they are in fact running normally, which is precisely
 * the wrong direction for a safety signal to be wrong in. This field exists so the console can say
 * "recording, not yet refusing" out loud. <b>When the dispatch path starts consulting the registry,
 * this must change with it</b> - a stale {@code false} is merely pessimistic, but a stale {@code
 * true} is a lie about a control that is not there.
 *
 * <p><b>2026-09-28: flipped, and made a live answer rather than a constant.</b> The dispatch path
 * now consults the registry through {@code AgentToolGovernance} (wired into {@code AgentToolGuard},
 * i.e. every guarded tool call): QUARANTINED and INVALID_DECLARATION tools are refused. {@code
 * enforced} is read from {@link AgentToolGovernance#isEnforcing()}, which only turns true once a
 * tool guard actually holds the governance bean - so the field cannot claim a control that the
 * running build has not wired. Note what it does NOT mean: UNCLASSIFIED tools still run (counted,
 * not refused), and the irreversible-action confirmation gate is behind its own flag - see {@code
 * AgentToolGovernance}'s javadoc.
 */
@RestController
@RequestMapping("/api/tools/classifications")
public class ToolClassificationController {

    private final ToolClassificationRegistry registry;
    private final ObjectProvider<AgentToolGovernance> governance;

    /** Test seam: no governance bean, so {@code enforced} is reported false. */
    public ToolClassificationController(ToolClassificationRegistry registry) {
        this(registry, null);
    }

    @Autowired
    public ToolClassificationController(
            ToolClassificationRegistry registry, ObjectProvider<AgentToolGovernance> governance) {
        this.registry = registry;
        this.governance = governance;
    }

    /** Whether the tool dispatch path is actually consulting this registry right now. */
    boolean enforcementActive() {
        AgentToolGovernance resolved = governance != null ? governance.getIfAvailable() : null;
        return resolved != null && resolved.isEnforcing();
    }

    /**
     * Returns every classification this tenant can see, most-specific source first, along with
     * whether enforcement is actually switched on.
     *
     * <p>Rows are returned unsorted by risk on purpose - the console owns that ordering (it has the
     * severity model for the three axes) and duplicating the ranking here would let the two drift
     * apart silently.
     */
    @GetMapping
    public ResponseEntity<ToolClassificationListResponse> list() {
        // Built-ins first so a stored row of the same name overwrites it below.
        // LinkedHashMap keeps a stable, reviewable order for an unchanged tenant.
        Map<String, ToolClassificationRow> byName = new LinkedHashMap<>();

        BuiltInToolClassifications.all()
                .forEach(
                        (toolName, classification) ->
                                byName.put(
                                        toolName,
                                        ToolClassificationRow.fromBuiltIn(classification)));

        for (ToolClassificationEntity stored : registry.storedClassifications()) {
            // Patterns are rules, not tools. Listing `mcp_*` as a row would show
            // the tenant a tool that does not exist.
            if (stored.isPattern()) {
                continue;
            }
            byName.put(stored.getToolName(), ToolClassificationRow.fromStored(stored));
        }

        return ResponseEntity.ok(
                new ToolClassificationListResponse(
                        enforcementActive(), new ArrayList<>(byName.values())));
    }

    /**
     * Envelope matching the console's {@code ToolClassificationListResponse}. {@code enforced} is
     * nullable in the client type for cores that predate the field; this core always sends it,
     * because "we do not know" and "we are not enforcing" are different statements and only one of
     * them is true here.
     */
    public record ToolClassificationListResponse(
            boolean enforced, List<ToolClassificationRow> tools) {}

    /**
     * One row of the classification table, flattened for the console.
     *
     * <p>The three axes are serialized as their enum names rather than as objects: the console owns
     * the human-readable label, meaning and severity for each tier (it has to, to render them in
     * two themes), so sending prose from here would create a second source of truth for copy that
     * would then disagree with the UI.
     *
     * <p>{@code blocked} is computed from the stored row's own {@link
     * ToolClassificationEntity#decide()} rather than re-derived here, so the console's notion of
     * "refused" is the same verdict the runtime would reach. A built-in declaration is by
     * construction complete and active, so it is never blocked.
     */
    public record ToolClassificationRow(
            String toolName,
            String displayName,
            String origin,
            String serverId,
            String serverName,
            String trust,
            String sensitivity,
            String determinism,
            boolean irreversible,
            String requiredScope,
            boolean needsToolContext,
            boolean blocked,
            String blockedReason,
            Instant classifiedAt,
            String classifiedBy) {

        static ToolClassificationRow fromBuiltIn(ToolClassification c) {
            return new ToolClassificationRow(
                    c.toolName(),
                    null,
                    "BUILT_IN",
                    null,
                    null,
                    c.trust().name(),
                    c.sensitivity().name(),
                    c.determinism().name(),
                    c.irreversible(),
                    c.requiredScope(),
                    c.needsToolContext(),
                    false,
                    null,
                    null,
                    BuiltInToolClassifications.DECLARED_BY);
        }

        static ToolClassificationRow fromStored(ToolClassificationEntity e) {
            ToolClassificationDecision decision = e.decide();
            boolean blocked = !decision.isClassified();

            // Only a Blocked decision carries a reason; describe() on a
            // Classified decision is a summary, not a refusal, and surfacing it
            // as blockedReason would read as an error in the console.
            String blockedReason =
                    (decision instanceof ToolClassificationDecision.Blocked b)
                            ? b.describe()
                            : null;

            return new ToolClassificationRow(
                    e.getToolName(),
                    null,
                    originOf(e.getSource()),
                    null,
                    null,
                    e.getTrust(),
                    e.getSensitivity(),
                    e.getDeterminism(),
                    e.isIrreversible(),
                    e.getRequiredScope(),
                    e.isNeedsToolContext(),
                    blocked,
                    blockedReason,
                    e.getUpdatedAt() != null ? e.getUpdatedAt() : e.getCreatedAt(),
                    e.getCreatedBy());
        }

        /**
         * Maps the registry's provenance vocabulary onto the console's {@code ORIGIN_LABELS} keys.
         * An unrecognised source is passed through rather than defaulted, because the console
         * already falls through to the raw string and a wrong-but-pretty label is worse than an
         * unfamiliar one.
         */
        private static String originOf(String source) {
            if (source == null) {
                return null;
            }
            return switch (source) {
                case "BUILT_IN_SEED" -> "BUILT_IN";
                case "MCP_IMPORT" -> "MCP_SERVER";
                case "TENANT_DECLARED" -> "USER_SCRIPT";
                default -> source;
            };
        }
    }
}
