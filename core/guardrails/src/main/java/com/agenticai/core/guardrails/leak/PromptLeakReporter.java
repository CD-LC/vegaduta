package com.agenticai.core.guardrails.leak;

import com.agenticai.core.audit.AuditLogService;
import com.agenticai.core.audit.AuditOutcome;
import com.agenticai.core.security.logging.LogSafe;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Metrics;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Counts, logs and audits a detection. Only rule ids, lengths and the action leave this class:
 * never prompt text, reply text or the canary token. Best effort: a failing audit write is logged
 * and swallowed so a detection can never break a turn.
 */
public class PromptLeakReporter {

    /** Counter, tagged {@code rule}, {@code mode}, {@code action}. */
    public static final String METRIC = "guardrail.prompt_leak.detected";

    public static final String AUDIT_ACTION = "guardrail.prompt_leak.detected";

    private static final Logger log = LoggerFactory.getLogger(PromptLeakReporter.class);

    private final MeterRegistry registry;
    private final AuditLogService audit;

    public PromptLeakReporter(MeterRegistry registry, AuditLogService audit) {
        this.registry = registry != null ? registry : Metrics.globalRegistry;
        this.audit = audit;
    }

    public void report(UUID agentId, String sessionId, PromptLeakMode mode, LeakDetector.Verdict verdict) {
        String rules = String.join(",", verdict.rules());
        String modeTag = mode.name().toLowerCase(java.util.Locale.ROOT);
        String actionTag = verdict.action().name().toLowerCase(java.util.Locale.ROOT);
        try {
            for (String rule : verdict.rules()) {
                registry.counter(METRIC, "rule", rule, "mode", modeTag, "action", actionTag).increment();
            }
        } catch (RuntimeException e) {
            log.warn("Prompt-leak metric failed: {}", e.toString());
        }
        String detail =
                "rules="
                        + rules
                        + " matchedChars="
                        + verdict.matchedChars()
                        + " promptChars="
                        + verdict.promptLength()
                        + " outputCharsAtDetection="
                        + verdict.outputLength()
                        + " mode="
                        + modeTag
                        + " action="
                        + actionTag;
        log.warn(
                "System-prompt leak detected: agent={}, session={}, {}",
                LogSafe.clean(agentId),
                LogSafe.clean(sessionId),
                detail);
        if (audit == null) {
            return;
        }
        try {
            audit.record(null, AUDIT_ACTION, "prompt_leak", sessionId, AuditOutcome.WARNING, detail);
        } catch (RuntimeException e) {
            log.warn("Prompt-leak audit write failed for session {}: {}", LogSafe.clean(sessionId), e.toString());
        }
    }
}
