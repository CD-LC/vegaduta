package com.agenticai.core.guardrails.taint;

import com.agenticai.core.audit.AuditOutcome;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The per-turn untrusted-content gate, consulted by {@link
 * com.agenticai.core.guardrails.GuardedToolCallback} on every tool call of one turn:
 *
 * <ol>
 *   <li>{@link #check} before the tool runs - if the turn is already tainted and the tool is a
 *       sink, the call needs human approval ({@link TaintMode#ENFORCE}) or is merely recorded as
 *       "would have needed it" ({@link TaintMode#OBSERVE});
 *   <li>{@link #afterCall} on the tool's final output - if the tool is a taint source, the turn
 *       becomes tainted and (enforce only) the output is spotlighted for the model.
 * </ol>
 *
 * Everything here is in-process and allocation-light; no I/O except the optional audit sink, and
 * that only on the rare decision that matters. A classifier failure degrades to the conservative
 * (side-effecting) reading, never to an exception out of the tool path.
 *
 * <p>Metrics: {@value #MARKED_METER} (a source's output tainted the turn), {@value
 * #TAINTED_TURNS_METER} (a turn went clean to tainted), {@value #SINK_METER} (a sink was attempted
 * on a tainted turn, tag {@code action} = {@code would_block} or {@code approval_required}).
 */
public final class TaintGate {

    private static final Logger log = LoggerFactory.getLogger(TaintGate.class);

    public static final String MARKED_METER = "agent.taint.marked";
    public static final String TAINTED_TURNS_METER = "agent.taint.tainted_turns";
    public static final String SINK_METER = "agent.taint.sink_calls";

    /** Outcome of {@link #check}. */
    public record Decision(boolean requiresApproval, String reason) {
        public static final Decision NONE = new Decision(false, null);
    }

    private final TaintMode mode;
    private final TurnTaint turn;
    private final ToolTaintClassifier classifier;
    private final MeterRegistry meters;
    private final TaintAuditSink audit;
    private final UUID agentId;
    private final String sessionId;

    public TaintGate(
        TaintMode mode,
        TurnTaint turn,
        ToolTaintClassifier classifier,
        MeterRegistry meters,
        TaintAuditSink audit,
        UUID agentId,
        String sessionId
    ) {
        this.mode = mode == null ? TaintMode.OBSERVE : mode;
        this.turn = turn;
        this.classifier = classifier;
        this.meters = meters;
        this.audit = audit;
        this.agentId = agentId;
        this.sessionId = sessionId;
    }

    /** Where the banner wording of spotlighted output comes from; null = the shipped text. */
    private volatile com.agenticai.core.prompts.PromptResolver prompts;

    /** Words the spotlight banner through the prompt registry (agent, tenant and platform overrides). */
    public TaintGate withPrompts(com.agenticai.core.prompts.PromptResolver resolver) {
        this.prompts = resolver;
        return this;
    }

    public TurnTaint turn() {
        return turn;
    }

    public Decision check(String toolName, String toolInput) {
        if (mode == TaintMode.OFF || turn == null || !turn.isTainted()) {
            return Decision.NONE;
        }
        ToolTaintProfile profile = profile(toolName, toolInput);
        if (!profile.sink()) {
            return Decision.NONE;
        }
        List<String> sources = turn.sources();
        boolean enforce = mode == TaintMode.ENFORCE;
        count(SINK_METER, "tool", toolName, "action", enforce ? "approval_required" : "would_block");
        String detail = "tool=" + toolName + " class=" + profile.label() + " taintedBy=" + sources;
        if (enforce) {
            log.info("Taint gate: '{}' needs approval, turn tainted by {} (agent {}, session {})",
                toolName, sources, agentId, sessionId);
            emit("tool.taint.approval_required", AuditOutcome.PENDING_APPROVAL, toolName, detail);
            return new Decision(true, "untrusted content from " + String.join(", ", sources)
                + " entered this conversation before this action was requested");
        }
        // Observe is evidence gathering, not a log stream: one WARN and one audit row per tool per
        // turn (the meter above still counts every call).
        if (turn.firstReport(toolName)) {
            log.warn("Taint gate (observe): '{}' WOULD need approval, turn tainted by {} (agent {}, session {})",
                toolName, sources, agentId, sessionId);
            emit("tool.taint.would_block", AuditOutcome.WARNING, toolName, detail);
        }
        return Decision.NONE;
    }

    /**
     * @param output the tool's final, sanitized output (what the model would receive)
     * @return the output to hand to the model: spotlighted in enforce mode when the tool is a taint
     *     source, otherwise unchanged
     */
    public String afterCall(String toolName, String toolInput, String output) {
        if (mode == TaintMode.OFF || turn == null) {
            return output;
        }
        ToolTaintProfile profile = profile(toolName, toolInput);
        if (!profile.producesTaint()) {
            return output;
        }
        count(MARKED_METER, "tool", toolName);
        if (turn.mark(toolName)) {
            count(TAINTED_TURNS_METER, "mode", mode.name().toLowerCase(Locale.ROOT));
            log.debug("Turn tainted by '{}' (agent {}, session {}, mode {})", toolName, agentId, sessionId, mode);
        }
        return mode == TaintMode.ENFORCE ? Spotlight.wrap(toolName, output, prompts, agentId) : output;
    }

    private ToolTaintProfile profile(String toolName, String toolInput) {
        if (classifier == null) {
            return ToolTaintProfile.UNKNOWN;
        }
        try {
            ToolTaintProfile profile = classifier.profile(toolName, toolInput);
            return profile != null ? profile : ToolTaintProfile.UNKNOWN;
        } catch (RuntimeException e) {
            log.warn("Taint classification failed for tool '{}' - treating it as side-effecting", toolName, e);
            return ToolTaintProfile.UNKNOWN;
        }
    }

    private void count(String name, String... tags) {
        if (meters == null) {
            return;
        }
        try {
            meters.counter(name, tags).increment();
        } catch (RuntimeException e) {
            log.debug("Could not record {}", name, e);
        }
    }

    private void emit(String action, AuditOutcome outcome, String toolName, String detail) {
        if (audit == null) {
            return;
        }
        try {
            audit.emit(action, outcome, toolName, detail);
        } catch (RuntimeException e) {
            log.warn("Could not audit taint decision for tool '{}'", toolName, e);
        }
    }
}
