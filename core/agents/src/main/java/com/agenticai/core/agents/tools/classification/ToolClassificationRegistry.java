package com.agenticai.core.agents.tools.classification;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The one place that answers <b>"what is this tool?"</b> - resolving a tool name to
 * a {@link ToolClassification}, or to an explicit blocked decision when nobody has
 * declared one.
 *
 * <h2>Why this service exists</h2>
 * Four features that this platform wants next -
 * an irreversible-action confirmation gate, taint propagation through
 * {@code GuardedToolCallback}, per-tool approval policy, and progressive tool
 * disclosure - all need the same three facts about a tool and would otherwise each
 * re-derive them from tool names, differently. Worse, the facts are currently
 * implicit: {@code ApprovalPolicyService} has a list of action-name globs,
 * {@code AgentToolProvider} knows which credential each tool needs, and nothing at
 * all knows whether a call can be safely repeated. This service is the declared,
 * typed, single answer, and every consumer is expected to consult it rather than
 * pattern-match names.
 *
 * <h2>Resolution order (and why it is this order)</h2>
 * <ol>
 *   <li><b>Exact stored row</b> in {@code tool_classification} for this tenant.
 *       A deliberate human or admin decision beats everything, including what the
 *       platform shipped, because the tenant knows their own MCP servers and
 *       custom tools and the platform does not.</li>
 *   <li><b>Longest matching wildcard stored row</b> ({@code mcp__github__*}).
 *       This is how a tenant classifies an MCP server's whole surface in one row
 *       instead of chasing every tool the server chooses to advertise.</li>
 *   <li><b>Compiled-in declaration</b> from {@link BuiltInToolClassifications} -
 *       exact first, then its own ordered patterns ({@code ask_agent_*}). This is
 *       what the platform ships for first-party tools, and it is last among the
 *       "allow" sources so that a stored override always wins.</li>
 *   <li><b>MCP tool annotations</b> (only when {@code app.tools.mcp-annotations.enabled}, only
 *       for {@code mcp__*} names whose server stated a read-only/destructive/open-world hint) -
 *       see {@link McpAnnotationClassifier}. Stored rows above still win, so this is
 *       a default for tools nobody classified, never an override.</li>
 *   <li><b>{@link ToolClassificationDecision.Blocked} with
 *       {@link ToolClassificationDecision.Reason#UNCLASSIFIED}.</b> Not a default
 *       classification, not {@code null}, not an empty {@code Optional}. An
 *       unclassified tool is blocked, and the type system makes that the only
 *       thing a caller can do with the result.</li>
 * </ol>
 *
 * <p>A stored row that <i>exists but is unusable</i> - quarantined, or carrying an
 * enum value this build does not know - short-circuits at step 1 or 2 and blocks;
 * it never falls through to step 3. Falling through would mean a typo in one column
 * silently reverting a tenant's deliberate decision to the platform default, which
 * is a security regression that leaves no trace. See
 * {@link ToolClassificationEntity#decide()}.
 *
 * <h2>Tenancy</h2>
 * {@code tool_classification} is a tenant-schema table, so every method here reads
 * and writes whichever tenant schema the calling thread is bound to. There is no
 * {@code tenantId} parameter anywhere in this API by design - the routing is the
 * {@code TenantContext}/schema mechanism every other tenant-scoped service in this
 * codebase uses, and accepting an explicit tenant id here would create a second,
 * unaudited way to read another tenant's classifications.
 *
 * <h2>What this service deliberately does not do yet</h2>
 * <ul>
 *   <li><b>No caching.</b> {@link #resolve(String)} hits the database. That is a
 *       real per-tool-call cost and the obvious optimisation, and it is left out of
 *       the first version on purpose: a stale entry in a cache over a security
 *       table means an enforcement decision made from a classification an admin
 *       already changed, and getting invalidation wrong is a worse failure than a
 *       query. {@link #resolveAll(Collection)} exists so a caller resolving a whole
 *       turn's tool set pays two queries rather than N, which is the cost that
 *       actually shows up in a trace.</li>
 *   <li><b>No enforcement.</b> Nothing here refuses a tool call. This service
 *       answers questions; {@code GuardedToolCallback} and the registration path
 *       are where the answers become decisions. Keeping the two apart is what makes
 *       it possible to run the classification in shadow mode - logging what would
 *       have been blocked - before enforcement is switched on, which the build
 *       agenda requires precisely because a wrong classification either blocks
 *       legitimate work or silently widens a gate.</li>
 *   <li><b>No HTTP surface.</b> There is no controller in this package. The console
 *       views this unblocks (a Classification column on the MCP catalog, a risk
 *       badge on an agent's tool list) need endpoints that this deliberately does
 *       not invent, so that the shape is decided together with the UI rather than
 *       guessed at here.</li>
 * </ul>
 */
@Service
public class ToolClassificationRegistry {

    private static final Logger log = LoggerFactory.getLogger(ToolClassificationRegistry.class);

    /**
     * Counter name for every resolution outcome.
     *
     * <p>Tagged with {@code outcome} ({@code classified}/{@code blocked}),
     * {@code reason} (the {@link ToolClassificationDecision.Reason} or
     * {@code none}) and {@code source} (where the answer came from) - and
     * deliberately <b>not</b> with the tool name or the tenant id. A per-tool or
     * per-tenant tag on a path that runs once per tool per turn is an unbounded
     * cardinality series, and the questions worth alerting on ("unclassified
     * resolutions are non-zero after enforcement went on") do not need either.
     * The tool name is in the log line and in the drift report, where cardinality
     * costs nothing.
     */
    static final String RESOLUTION_METRIC = "agent.tool.classification.resolutions";

    private final ToolClassificationRepository repository;
    private final MeterRegistry meterRegistry;

    public ToolClassificationRegistry(ToolClassificationRepository repository, MeterRegistry meterRegistry) {
        this.repository = repository;
        this.meterRegistry = meterRegistry;
    }

    /**
     * Optional (slim/test contexts construct the registry without it): classification derived from MCP
     * tool annotations, consulted only after stored rows and built-in declarations miss. Inert unless
     * {@code app.tools.mcp-annotations.enabled}. See {@link McpAnnotationClassifier}.
     */
    private volatile McpAnnotationClassifier mcpAnnotations;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setMcpAnnotations(McpAnnotationClassifier mcpAnnotations) {
        this.mcpAnnotations = mcpAnnotations;
    }

    /** Annotation-derived classification for an MCP tool name, or null (flag off, not MCP, or no risk hint stated). */
    private ToolClassification fromMcpAnnotations(String toolName) {
        McpAnnotationClassifier derived = mcpAnnotations;
        if (derived == null || !derived.isEnabled()) {
            return null;
        }
        try {
            String tenantId = com.agenticai.core.security.TenantContext.current()
                .map(com.agenticai.core.security.TenantContext::getTenantId).orElse(null);
            return derived.classify(tenantId, toolName).orElse(null);
        } catch (RuntimeException e) {
            // A lookup hiccup must never turn into a different decision than "unclassified".
            log.debug("MCP annotation lookup failed for '{}'", toolName, e);
            return null;
        }
    }

    // ---- resolution -------------------------------------------------------

    /**
     * Resolves one tool name, following the order documented on this class.
     *
     * <p>Never returns {@code null} and never throws for an unknown tool - an
     * unknown tool is a {@link ToolClassificationDecision.Blocked} result, which is
     * a normal, expected outcome that the caller must handle. A {@code null} or
     * blank name is also blocked rather than rejected, because it can only arrive
     * from a malformed {@code ToolDefinition} and blocking it is both the
     * fail-closed answer and the one that keeps the reason visible in the metric.
     */
    @Transactional(readOnly = true)
    public ToolClassificationDecision resolve(String toolName) {
        return resolve(toolName, true);
    }

    /**
     * {@link #resolve(String)} without the MCP-annotation fallback: only stored rows and compiled-in
     * declarations count. For callers that would RELAX a safety behaviour on a classification (the
     * replay-safety verdict): a server's own claim that a tool is read-only is untrusted data and must
     * never buy it a more permissive path than "unclassified" gets.
     */
    @Transactional(readOnly = true)
    public ToolClassificationDecision resolveIgnoringMcpAnnotations(String toolName) {
        return resolve(toolName, false);
    }

    private ToolClassificationDecision resolve(String toolName, boolean useMcpAnnotations) {
        if (toolName == null || toolName.isBlank()) {
            return recordOutcome(new ToolClassificationDecision.Blocked(
                String.valueOf(toolName),
                ToolClassificationDecision.Reason.UNCLASSIFIED,
                "A tool with no name cannot be classified and is therefore blocked."), "none");
        }
        String name = toolName.trim();

        Optional<ToolClassificationEntity> exact = repository.findByToolName(name);
        if (exact.isPresent()) {
            return recordOutcome(exact.get().decide(), "stored_exact");
        }

        for (ToolClassificationEntity pattern : repository.findPatternRows()) {
            if (BuiltInToolClassifications.matchesGlob(pattern.getToolName(), name)) {
                return recordOutcome(reKey(pattern.decide(), name), "stored_pattern");
            }
        }

        ToolClassification declared = BuiltInToolClassifications.find(name);
        if (declared != null) {
            return recordOutcome(new ToolClassificationDecision.Classified(declared), "built_in");
        }

        ToolClassification fromAnnotations = useMcpAnnotations ? fromMcpAnnotations(name) : null;
        if (fromAnnotations != null) {
            return recordOutcome(new ToolClassificationDecision.Classified(fromAnnotations), "mcp_annotation");
        }

        return recordOutcome(unclassified(name), "none");
    }

    /**
     * Convenience overload resolving the name Spring AI actually dispatches on.
     *
     * <p>Reads {@code getToolDefinition().name()} rather than accepting a string
     * from the caller, because the two can differ: an MCP tool's platform-visible
     * name is the namespaced {@code mcp__<server>__<tool>} form produced by
     * {@code NamespacedMcpToolCallback}, while the bare upstream name still exists
     * inside Spring AI's own callback. Classifying the bare name would leave the
     * name that is actually gated unclassified - i.e. blocked - which is safe but
     * baffling to debug. Taking the {@code ToolCallback} removes the choice.
     */
    @Transactional(readOnly = true)
    public ToolClassificationDecision resolve(ToolCallback tool) {
        Objects.requireNonNull(tool, "tool");
        return resolve(tool.getToolDefinition().name());
    }

    /**
     * Resolves a whole set of tool names in two queries instead of 2N.
     *
     * <p>Returns an insertion-ordered map with an entry for <b>every</b> requested
     * name, including the blocked ones. A caller that wants only the classified
     * ones has to filter explicitly, which is the intended friction: the natural
     * "give me the classifications" shape would silently omit the blocked tools,
     * and a caller iterating the result would then treat "absent" as "fine".
     *
     * <p>Duplicate names in the input collapse to one entry.
     */
    @Transactional(readOnly = true)
    public Map<String, ToolClassificationDecision> resolveAll(Collection<String> toolNames) {
        Map<String, ToolClassificationDecision> out = new LinkedHashMap<>();
        if (toolNames == null || toolNames.isEmpty()) {
            return out;
        }
        Set<String> names = new LinkedHashSet<>();
        for (String raw : toolNames) {
            if (raw != null && !raw.isBlank()) {
                names.add(raw.trim());
            }
        }
        if (names.isEmpty()) {
            return out;
        }

        Map<String, ToolClassificationEntity> exactRows = new LinkedHashMap<>();
        for (ToolClassificationEntity row : repository.findByToolNameIn(names)) {
            exactRows.put(row.getToolName(), row);
        }
        List<ToolClassificationEntity> patternRows = repository.findPatternRows();

        for (String name : names) {
            ToolClassificationEntity exact = exactRows.get(name);
            if (exact != null) {
                out.put(name, recordOutcome(exact.decide(), "stored_exact"));
                continue;
            }
            ToolClassificationDecision fromPattern = null;
            for (ToolClassificationEntity pattern : patternRows) {
                if (BuiltInToolClassifications.matchesGlob(pattern.getToolName(), name)) {
                    fromPattern = recordOutcome(reKey(pattern.decide(), name), "stored_pattern");
                    break;
                }
            }
            if (fromPattern != null) {
                out.put(name, fromPattern);
                continue;
            }
            ToolClassification declared = BuiltInToolClassifications.find(name);
            if (declared == null) {
                ToolClassification fromAnnotations = fromMcpAnnotations(name);
                if (fromAnnotations != null) {
                    out.put(name, recordOutcome(new ToolClassificationDecision.Classified(fromAnnotations), "mcp_annotation"));
                    continue;
                }
            }
            out.put(name, declared != null
                ? recordOutcome(new ToolClassificationDecision.Classified(declared), "built_in")
                : recordOutcome(unclassified(name), "none"));
        }
        return out;
    }

    /**
     * Resolves and unwraps, throwing {@link ToolNotClassifiedException} when the
     * tool is blocked.
     *
     * <p>For call sites that structurally cannot continue: registration-time
     * validation, a startup check that refuses to bind an unclassified tool, and
     * tests. Not for the request path - there, pattern-match on the decision so the
     * refusal can name the reason to the operator and to the model.
     */
    @Transactional(readOnly = true)
    public ToolClassification requireClassification(String toolName) {
        return resolve(toolName).classificationOrThrow();
    }

    /**
     * True when every supplied tool name resolves to a classification.
     *
     * <p>Exists so the eventual registration-time check ("refuse to start with an
     * unclassified tool bound") can be written as one call, and so the same check
     * can run in shadow mode today: call it, log the failures, enforce later. The
     * blocked names are available from {@link #resolveAll(Collection)} when the
     * caller needs to report them rather than just branch.
     */
    @Transactional(readOnly = true)
    public boolean allClassified(Collection<String> toolNames) {
        return resolveAll(toolNames).values().stream().allMatch(ToolClassificationDecision::isClassified);
    }

    // ---- declaration ------------------------------------------------------

    /**
     * Declares (or re-declares) a classification for a tool name, which may be a
     * wildcard pattern.
     *
     * <p>Always writes {@link ToolClassificationStatus#ACTIVE}. Declaring is not a
     * way to clear a quarantine - see {@link #quarantine(String, String)} for why
     * that has to be a separate, deliberate act by a human who looked. A caller
     * that re-declares a quarantined tool gets an active row, which is why the
     * quarantine API is the one an automated reconnect path must call and this one
     * is not.
     *
     * @param classification the values to store; its {@code toolName} is the key
     * @param source         provenance for the row itself - one of
     *                       {@code BUILT_IN_SEED}, {@code PLATFORM_ADMIN},
     *                       {@code TENANT_DECLARED}, {@code MCP_IMPORT}, matching
     *                       V224's CHECK constraint
     * @param notes          why this classification, shown next to the row in the
     *                       console; the question an auditor asks is "why is this
     *                       EGRESS", and a row with no answer is a row nobody can
     *                       defend
     * @param actor          the user or system identity making the declaration,
     *                       recorded as {@code created_by} on first write only
     */
    @Transactional
    public void declare(ToolClassification classification, String source, String notes, String actor) {
        Objects.requireNonNull(classification, "classification");
        Objects.requireNonNull(source, "source");
        repository.upsert(
            UUID.randomUUID(),
            classification.toolName(),
            classification.trust().name(),
            classification.sensitivity().name(),
            classification.determinism().name(),
            ToolClassificationStatus.ACTIVE.name(),
            classification.requiredScope(),
            classification.irreversible(),
            classification.needsToolContext(),
            source,
            notes,
            actor == null ? "system" : actor,
            Instant.now());
        log.info("Tool classification declared: {} -> {} (source={}, by={})",
            classification.toolName(), classification.riskLabel(), source, actor);
    }

    /**
     * Blocks a tool pending human review, leaving its classification intact.
     *
     * <p>The intended automated caller is the MCP reconnect path: when a server's
     * tool manifest changes after approval - a renamed tool, or a rewritten
     * description, which is prompt content and therefore a live injection vector -
     * the right response is to stop running the tool without discarding what was
     * already known about it.
     *
     * <p>A tool with no row at all is already blocked as
     * {@link ToolClassificationDecision.Reason#UNCLASSIFIED}, so this returning
     * {@code false} is not a failure to act; it is a report that there was nothing
     * to change. The caller should log it and move on rather than creating a row,
     * because creating one would require inventing a classification.
     *
     * @return true when a row was updated
     */
    @Transactional
    public boolean quarantine(String toolName, String reason) {
        int updated = repository.updateStatus(
            toolName, ToolClassificationStatus.QUARANTINED.name(), reason, Instant.now());
        if (updated > 0) {
            log.warn("Tool '{}' quarantined: {}", toolName, reason);
            return true;
        }
        log.warn("Tool '{}' has no classification row to quarantine - it is already blocked as UNCLASSIFIED", toolName);
        return false;
    }

    /**
     * Clears a quarantine, returning the row to {@link ToolClassificationStatus#ACTIVE}.
     *
     * <p>Deliberately separate from {@link #declare} so that the audit trail
     * distinguishes "somebody classified this tool" from "somebody decided this
     * tool is safe to run again", and so that no automated path can clear a
     * quarantine as a side effect of re-importing a manifest.
     *
     * @return true when a row was updated
     */
    @Transactional
    public boolean clearQuarantine(String toolName, String reason) {
        int updated = repository.updateStatus(
            toolName, ToolClassificationStatus.ACTIVE.name(), reason, Instant.now());
        if (updated > 0) {
            log.info("Quarantine cleared for tool '{}': {}", toolName, reason);
        }
        return updated > 0;
    }

    // ---- reporting --------------------------------------------------------

    /** Every stored row for this tenant, name-ordered, for the console's classification table. */
    @Transactional(readOnly = true)
    public List<ToolClassificationEntity> storedClassifications() {
        return repository.findAllByOrderByToolNameAsc();
    }

    /**
     * Compares what the platform <b>declares in code</b> against what this tenant
     * has <b>stored</b>, and reports every difference.
     *
     * <p>This is the report the build agenda requires before enforcement is turned
     * on, and it exists because the stated risk of this whole feature is migration
     * friction: a wrong classification either blocks legitimate work or silently
     * widens a gate, and both are invisible without a diff. It answers three
     * questions in one pass - which first-party tools this tenant has overridden
     * and how, which stored rows describe tools the platform no longer ships
     * (usually a renamed tool, whose old row is now dead weight that classifies
     * nothing), and which rows are unusable.
     *
     * <p>Wildcard rows on either side are skipped rather than diffed: a pattern and
     * an exact name are not comparable, and reporting {@code mcp__github__*} as
     * "missing from the built-in declarations" every time would be noise that
     * trains the reader to ignore the report.
     */
    @Transactional(readOnly = true)
    public List<ClassificationDrift> driftFromBuiltInDeclarations() {
        List<ClassificationDrift> drift = new ArrayList<>();
        Map<String, ToolClassification> declared = BuiltInToolClassifications.all();

        Map<String, ToolClassificationEntity> stored = new LinkedHashMap<>();
        for (ToolClassificationEntity row : repository.findAllByOrderByToolNameAsc()) {
            if (!row.isPattern()) {
                stored.put(row.getToolName(), row);
            }
        }

        for (Map.Entry<String, ToolClassification> entry : declared.entrySet()) {
            ToolClassificationEntity row = stored.get(entry.getKey());
            if (row == null) {
                drift.add(new ClassificationDrift(
                    entry.getKey(), DriftKind.NOT_STORED, entry.getValue(), null,
                    "Declared in code, no row for this tenant - resolution falls through to the built-in declaration."));
                continue;
            }
            ToolClassificationDecision decision = row.decide();
            if (!decision.isClassified()) {
                drift.add(new ClassificationDrift(
                    entry.getKey(), DriftKind.UNUSABLE_ROW, entry.getValue(), null, decision.describe()));
                continue;
            }
            ToolClassification storedClassification = decision.classificationOrThrow();
            if (!storedClassification.equals(entry.getValue())) {
                drift.add(new ClassificationDrift(
                    entry.getKey(), DriftKind.OVERRIDDEN, entry.getValue(), storedClassification,
                    "Stored row differs from the built-in declaration and wins."));
            }
        }

        for (Map.Entry<String, ToolClassificationEntity> entry : stored.entrySet()) {
            if (declared.containsKey(entry.getKey())) {
                continue;
            }
            ToolClassificationDecision decision = entry.getValue().decide();
            drift.add(new ClassificationDrift(
                entry.getKey(),
                DriftKind.UNKNOWN_TO_CODE,
                null,
                decision.isClassified() ? decision.classificationOrThrow() : null,
                "Stored row names no first-party tool - expected for MCP and custom-code tools, "
                    + "suspicious for anything else (usually a renamed tool's orphaned row)."));
        }
        return drift;
    }

    // ---- internals --------------------------------------------------------

    /**
     * Re-keys a wildcard row's decision to the concrete tool that was asked about,
     * so that every log line, span attribute and approval prompt downstream names a
     * real tool rather than the pattern that matched it.
     *
     * <p>{@link ToolClassificationDecision.Blocked} decisions are rebuilt with the
     * concrete name too, for the same reason: "mcp__github__* is quarantined" is
     * true but unhelpful when the operator is looking for why
     * {@code mcp__github__create_issue} stopped working.
     */
    private ToolClassificationDecision reKey(ToolClassificationDecision decision, String concreteToolName) {
        return switch (decision) {
            case ToolClassificationDecision.Classified classified ->
                new ToolClassificationDecision.Classified(
                    classified.classification().withToolName(concreteToolName));
            case ToolClassificationDecision.Blocked blocked ->
                new ToolClassificationDecision.Blocked(
                    concreteToolName,
                    blocked.reason(),
                    blocked.detail() + " (matched pattern '" + blocked.toolName() + "')");
        };
    }

    private ToolClassificationDecision unclassified(String toolName) {
        return new ToolClassificationDecision.Blocked(
            toolName,
            ToolClassificationDecision.Reason.UNCLASSIFIED,
            "Tool '" + toolName + "' has no classification (trust/sensitivity/determinism) and is blocked. "
                + "Classify it in tool_classification, or - for a first-party tool - declare it in "
                + "BuiltInToolClassifications.");
    }

    /**
     * Counts the outcome and, for a block, logs it.
     *
     * <p>Blocks are logged at WARN and classified resolutions are not logged at all:
     * the classified case runs once per tool per turn and would drown the log, while
     * a block is by definition a tool that did not run, which is the thing somebody
     * will be looking for. The message carries the tool name and the reason and no
     * arguments or output, so it is safe at any level.
     *
     * <p>2026-09-28: UNCLASSIFIED drops to DEBUG. The dispatch path now resolves
     * on every guarded tool call and deliberately still RUNS unclassified tools
     * ({@code AgentToolGovernance}), so a WARN saying "blocked" for each one would
     * be both wrong and the loudest line in the log. The resolution counter above
     * still carries every one of them.
     */
    private ToolClassificationDecision recordOutcome(ToolClassificationDecision decision, String source) {
        String outcome = decision.isClassified() ? "classified" : "blocked";
        String reason = decision instanceof ToolClassificationDecision.Blocked blocked
            ? blocked.reason().name()
            : "none";
        meterRegistry.counter(RESOLUTION_METRIC, "outcome", outcome, "reason", reason, "source", source).increment();
        if (decision instanceof ToolClassificationDecision.Blocked unclassified
            && unclassified.reason() == ToolClassificationDecision.Reason.UNCLASSIFIED) {
            log.debug("Tool classification: tool='{}' is unclassified source={}", decision.toolName(), source);
        } else if (!decision.isClassified()) {
            log.warn("Tool classification blocked: tool='{}' reason={} source={}",
                decision.toolName(), reason, source);
        }
        return decision;
    }

    /** How a stored row differs from what the platform declares in code. */
    public enum DriftKind {

        /** Declared in code, absent for this tenant. Benign - the code declaration is what resolves. */
        NOT_STORED,

        /** Both exist and disagree. The stored row wins; this is the line a reviewer must read before enforcement. */
        OVERRIDDEN,

        /** A row exists for a first-party tool but cannot be parsed or is quarantined, so the tool is blocked. */
        UNUSABLE_ROW,

        /**
         * A stored row whose tool name is not a first-party tool. Expected and
         * correct for MCP-discovered and custom-code tools; for anything else it
         * usually means a first-party tool was renamed and its old row now
         * classifies nothing.
         */
        UNKNOWN_TO_CODE
    }

    /**
     * One line of the drift report.
     *
     * @param toolName the tool name both sides are keyed by
     * @param kind     how they differ
     * @param declared the compiled-in declaration, or null when the code ships none
     * @param stored   the stored row's classification, or null when there is no row
     *                 or the row is unusable
     * @param detail   a human-readable explanation for the console and for logs
     */
    public record ClassificationDrift(
        String toolName,
        DriftKind kind,
        ToolClassification declared,
        ToolClassification stored,
        String detail
    ) {
    }
}
