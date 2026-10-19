package com.agenticai.core.agents.tools.classification;

import java.util.Objects;

/**
 * The result of asking {@link ToolClassificationRegistry} about a tool name:
 * either a {@link Classified} carrying a real {@link ToolClassification}, or a
 * {@link Blocked} carrying the reason it has none.
 *
 * <p><b>Why this type exists instead of {@code Optional<ToolClassification>} or
 * a nullable return.</b> Failing closed is the entire point of this registry, and
 * both of the obvious return types make failing closed optional:
 * <ul>
 *   <li>A {@code null} return is silently ignorable. The natural mistake -
 *       {@code if (classification != null && classification.isTaintSink())} -
 *       reads as defensive and means "an unclassified tool is not a sink", i.e.
 *       an unknown tool is treated as the safest possible tool. That is exactly
 *       backwards and it would look correct in review.</li>
 *   <li>An {@code Optional} is worse in practice rather than better, because
 *       {@code orElse(...)} and {@code map(...).orElse(false)} make the unsafe
 *       default <i>easy to write and short</i>. {@code opt.map(ToolClassification::isTaintSink).orElse(false)}
 *       is a one-liner that disables the gate.</li>
 * </ul>
 * A sealed two-case result makes the unclassified case a thing you have to name.
 * A caller that wants the classification must either pattern-match and handle
 * {@code Blocked}, or call {@link #classificationOrThrow()} and accept the
 * exception - and there is no third option that quietly yields a permissive
 * answer.
 *
 * <p><b>{@code BLOCKED} means blocked, not "allowed with no opinion".</b> The
 * contract for every consumer is that a {@code Blocked} decision denies the call.
 * An unclassified tool is not a low-risk tool; it is a tool nobody has looked at,
 * which is strictly more dangerous than one explicitly declared
 * {@link ToolSensitivity#EGRESS}, because at least the declared one is known.
 *
 * <p>The permitted subtypes are declared in this file, so the {@code permits}
 * clause is implicit - adding a third case means editing this file, which is the
 * intended friction.
 */
public sealed interface ToolClassificationDecision {

    /** Why a tool has no usable classification. Surfaced in logs, metrics and the console's "unclassified - blocked" state. */
    enum Reason {

        /**
         * No row in {@code tool_classification}, no matching glob row, and no
         * compiled-in declaration in {@link BuiltInToolClassifications}.
         *
         * <p>The expected case for a freshly discovered MCP tool, a marketplace
         * template's tool, or a tenant custom-code tool - and for a brand-new
         * first-party tool whose author has not yet added a declaration, which is
         * deliberate: shipping a tool without classifying it should break
         * loudly in staging, not quietly widen the gate in production.
         */
        UNCLASSIFIED,

        /**
         * A row exists and explicitly quarantines the tool
         * ({@code status = 'QUARANTINED'}).
         *
         * <p>Distinct from {@link #UNCLASSIFIED} because the operational response
         * differs and because conflating them loses information: quarantine is a
         * decision somebody made (an MCP server's tool manifest changed and needs
         * re-approval; a platform admin pulled a tool after an incident) and must
         * not be resolvable by "just classify it" - it needs the human who
         * quarantined it to clear it.
         */
        QUARANTINED,

        /**
         * A row exists but cannot be turned into a valid
         * {@link ToolClassification} - an enum value the current code does not
         * know (a row written by a newer deploy, or hand-edited SQL), or a
         * constraint the record's canonical constructor rejects.
         *
         * <p>Reported rather than thrown, and blocked rather than skipped. The
         * alternative - letting an unparseable row fall through to the
         * compiled-in default - would mean a typo in one column silently
         * reverting a tenant's deliberate override to whatever the platform
         * shipped, which is a security regression that leaves no trace.
         */
        INVALID_DECLARATION
    }

    /** The tool name that was asked about. Always present, on both outcomes, so a caller can log or report without re-threading it. */
    String toolName();

    /** True only for {@link Classified}. Named positively so the guard reads {@code if (!decision.isClassified()) deny();}. */
    boolean isClassified();

    /**
     * The classification, or a thrown {@link ToolNotClassifiedException} when the
     * decision is {@link Blocked}.
     *
     * <p>For call sites that are structurally unable to continue without a
     * classification (a registration-time validation, a startup check, a test).
     * Throwing is the correct behaviour there - it is the fail-closed outcome -
     * whereas on the request path a consumer should pattern-match instead and
     * turn a {@code Blocked} into a readable refusal the model and the operator
     * can both act on.
     */
    ToolClassification classificationOrThrow();

    /**
     * One-line, PII-free rendering for logs, metrics tags and span attributes:
     * the risk label for a classified tool, or {@code "unclassified - blocked
     * (REASON)"}.
     */
    String describe();

    /** A tool with a usable declaration. */
    record Classified(ToolClassification classification) implements ToolClassificationDecision {

        public Classified {
            Objects.requireNonNull(classification, "classification");
        }

        @Override
        public String toolName() {
            return classification.toolName();
        }

        @Override
        public boolean isClassified() {
            return true;
        }

        @Override
        public ToolClassification classificationOrThrow() {
            return classification;
        }

        @Override
        public String describe() {
            return classification.riskLabel();
        }
    }

    /**
     * A tool with no usable declaration. <b>Consumers must deny.</b>
     *
     * @param toolName the name that was asked about
     * @param reason   which of the three ways it failed
     * @param detail   a short human-readable explanation safe to show a tenant
     *                 admin in the console and safe to return to the model as a
     *                 refusal - it names the tool and the reason and nothing
     *                 else, never arguments or output
     */
    record Blocked(String toolName, Reason reason, String detail) implements ToolClassificationDecision {

        public Blocked {
            Objects.requireNonNull(toolName, "toolName");
            Objects.requireNonNull(reason, "reason");
            if (detail == null || detail.isBlank()) {
                detail = "Tool '" + toolName + "' has no usable classification (" + reason + ").";
            }
        }

        @Override
        public boolean isClassified() {
            return false;
        }

        @Override
        public ToolClassification classificationOrThrow() {
            throw new ToolNotClassifiedException(toolName, reason, detail);
        }

        @Override
        public String describe() {
            return "unclassified - blocked (" + reason + ")";
        }
    }
}
