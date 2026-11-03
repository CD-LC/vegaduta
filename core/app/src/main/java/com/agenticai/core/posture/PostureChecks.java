package com.agenticai.core.posture;

import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.function.Predicate;
import org.springframework.boot.convert.DurationStyle;

/**
 * The governance settings that decide whether the lethal trifecta (private data + untrusted content + egress)
 * is open, with the value each must have under the {@code strict} profile ({@code application-strict.yml}).
 * One list, read by {@link PostureReport} at boot and pinned by {@code StrictPostureTest}: add a fail-open
 * knob here and the test forces it into the strict profile too.
 */
public final class PostureChecks {

    /**
     * One setting. {@code core} marks the audit-finding-#1 set; {@code absent} is what the code falls back to
     * when the key is not configured at all (only matters when no application.yml is loaded).
     */
    public record Check(String key, String strictValue, String absent, boolean core, Predicate<String> safe) {}

    private static Predicate<String> is(String expected) {
        return v -> v != null && v.trim().equalsIgnoreCase(expected);
    }

    private static Predicate<String> isOneOf(String a, String b) {
        return v -> v != null && (v.trim().equalsIgnoreCase(a) || v.trim().equalsIgnoreCase(b));
    }

    private static boolean positiveDuration(String v) {
        if (v == null || v.isBlank()) {
            return false;
        }
        try {
            Duration d = DurationStyle.detectAndParse(v.trim(), ChronoUnit.SECONDS);
            return !d.isZero() && !d.isNegative();
        } catch (RuntimeException e) {
            return false;
        }
    }

    public static final List<Check> ALL = List.of(
        new Check("app.agents.taint.mode", "enforce", "observe", true, is("enforce")),
        new Check("app.mcp.tool-pinning.mode", "enforce", "warn", true, is("enforce")),
        new Check("app.tools.classification.unclassified-action", "require-approval", "allow", true,
            isOneOf("require-approval", "deny")),
        new Check("app.tools.classification.confirmation-gate-exempt-tools", "", "channel_send_message", true,
            v -> v == null || v.isBlank()),
        new Check("app.tools.classification.trifecta-enforcement", "block", "warn", true,
            isOneOf("block", "require-approval")),
        new Check("app.approvals.expiry.ttl", "PT24H", "0", true, PostureChecks::positiveDuration),
        // Auxiliary: already safe in the base profile, but the `personal` profile or an env override can undo them.
        new Check("app.tools.classification.confirmation-gate", "true", "true", false, is("true")),
        new Check("app.tools.classification.trifecta-overrides-exempt", "true", "true", false, is("true")),
        new Check("app.tools.classification.trifecta-analyse-auto-select", "true", "false", false, is("true")),
        new Check("app.guardrails.fail-mode", "closed", "closed", false, is("closed")),
        new Check("app.skills.supply-chain.enforce", "true", "true", false, is("true")),
        new Check("app.tool-arg-policies.enabled", "true", "true", false, is("true")),
        new Check("app.agent-hooks.enabled", "true", "true", false, is("true")));

    private PostureChecks() {}
}
