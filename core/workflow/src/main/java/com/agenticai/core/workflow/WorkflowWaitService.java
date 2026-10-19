package com.agenticai.core.workflow;

import com.agenticai.core.security.TenantContext;
import com.agenticai.core.workflow.dto.NodeTraceEntry;
import com.agenticai.core.workflow.dto.WorkflowNodeDto;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * The Wait node's planning and registration half (the resume half is {@link WorkflowWaitResumeService}).
 *
 * <p><b>How a wait is durable.</b> {@link WorkflowExecutionEngine} parks the run with the existing PAUSED
 * primitive (resume envelope = the node AFTER the wait and the input it receives, so an operator "Resume" simply
 * skips the rest of the wait) and {@link #register} writes one {@code workflow_wait} row (V434). Nothing is held
 * in memory or on a timer thread: {@link WorkflowWaitWakeJob} sweeps due rows on the existing scheduler (a
 * deploy or crash loses nothing) and the signed resume URL ({@link WorkflowWaitResumeTokens}) resolves a
 * webhook-mode row early. Wake-up resolution is therefore the sweep interval (default 30 s), not the millisecond.
 *
 * <p>Modes: {@code duration} (config {@code duration} + {@code unit}), {@code until} (ISO-8601 timestamp, or a
 * local date-time / date read as UTC, or epoch seconds/millis) and {@code webhook} (resume when the signed URL is
 * called, with a {@code timeout} that defaults to the cap; {@code onTimeout} "fail" (default) or "continue").
 * Every wait is bounded by {@code app.workflow.wait-node.max-duration-days} (default 7): a longer one is refused
 * with a clear message, never silently clamped.
 */
@Service
public class WorkflowWaitService {

    /** How the wait ends. */
    public enum Mode {
        DURATION, UNTIL, WEBHOOK
    }

    /**
     * @param wakeAt        when the sweep wakes the run (a webhook-mode wait: its timeout deadline)
     * @param failOnTimeout webhook mode only: fail the run (true) or continue it with {@code timedOut} output
     * @param immediate     no park at all - the time already passed, or a dry run skips waiting
     */
    public record Plan(
        Mode mode, Instant wakeAt, boolean failOnTimeout, boolean immediate, String summary, List<String> logLines
    ) {
    }

    /** The editor-facing view of a run's wait; {@code resumeUrl} is only present for a live webhook-mode wait. */
    public record WaitView(String mode, String status, Instant wakeAt, String resumeUrl) {
    }

    static final long DEFAULT_MAX_DAYS = 7;

    private static final Pattern DURATION_SHORTHAND = Pattern.compile("^(\\d+(?:\\.\\d+)?)\\s*([a-zA-Z]*)$");

    private final WorkflowWaitRepository repository;
    private final WorkflowWaitResumeTokens tokens;
    private final boolean enabled;
    private final Duration maxWait;

    public WorkflowWaitService(
        WorkflowWaitRepository repository,
        WorkflowWaitResumeTokens tokens,
        @Value("${app.workflow.wait-node.enabled:false}") boolean enabled,
        @Value("${app.workflow.wait-node.max-duration-days:7}") long maxDurationDays
    ) {
        this.repository = repository;
        this.tokens = tokens;
        this.enabled = enabled;
        this.maxWait = Duration.ofDays(Math.max(1, maxDurationDays));
    }

    public boolean isEnabled() {
        return enabled;
    }

    public Duration maxWait() {
        return maxWait;
    }

    /**
     * Computes what this Wait node does now. {@code node} must already have its expressions resolved (see {@code
     * WorkflowNodeExecutor#resolveConfig}).
     *
     * @throws IllegalArgumentException a clear config problem: unknown mode, unparseable/negative value, a wait
     *     longer than the cap, or webhook mode on a deployment with no resume-URL base
     */
    public Plan plan(WorkflowNodeDto node, Instant now, RunMode runMode) {
        Plan plan = computePlan(node.config(), now, maxWait);
        if (plan.mode() == Mode.WEBHOOK && !plan.immediate() && !tokens.isConfigured()) {
            throw new IllegalArgumentException("Wait node '" + node.label() + "' (webhook mode) needs "
                + "app.omnichannel.public-base-url and the platform encryption key to build its resume URL.");
        }
        if (runMode != null && runMode.simulatesSends()) {
            return new Plan(plan.mode(), plan.wakeAt(), plan.failOnTimeout(), true,
                NodeTraceEntry.DRY_RUN_SUMMARY_PREFIX + "skipped waiting - " + lowerFirst(plan.summary()), List.of());
        }
        return plan;
    }

    /**
     * Pure planning: the mode, the wake instant and the capped/validated duration. Static and clock-injected so it
     * is unit-testable without Spring.
     */
    static Plan computePlan(Map<String, Object> config, Instant now, Duration cap) {
        Mode mode = parseMode(config);
        if (mode == Mode.DURATION) {
            Duration d = parseDuration(str(config.get("duration")), str(config.get("unit")), "minutes");
            requireWithinCap(d, cap, "wait");
            Instant wakeAt = now.plus(d);
            if (d.isZero()) {
                return new Plan(mode, now, false, true, "The wait duration is zero - continuing without waiting.", List.of());
            }
            return new Plan(mode, wakeAt, false, false, "Waiting " + humanize(d) + " (until " + wakeAt + ").",
                List.of("Durable wait: the run is parked and a scheduled sweep resumes it at or after " + wakeAt + "."));
        }
        if (mode == Mode.UNTIL) {
            Instant until = parseInstant(str(config.get("until")));
            if (!until.isAfter(now)) {
                return new Plan(mode, now, false, true,
                    "The 'until' time " + until + " has already passed - continuing without waiting.", List.of());
            }
            requireWithinCap(Duration.between(now, until), cap, "wait until " + until);
            return new Plan(mode, until, false, false, "Waiting until " + until + ".",
                List.of("Durable wait: the run is parked and a scheduled sweep resumes it at or after " + until + "."));
        }
        String timeoutRaw = str(config.get("timeout"));
        Duration timeout = timeoutRaw.isEmpty()
            ? cap : parseDuration(timeoutRaw, str(config.get("timeoutUnit")), "hours");
        requireWithinCap(timeout, cap, "timeout");
        boolean fail = !"continue".equalsIgnoreCase(str(config.get("onTimeout")));
        Instant deadline = now.plus(timeout);
        return new Plan(mode, deadline, fail, false,
            "Waiting for an external call to this run's resume URL; times out at " + deadline
                + (fail ? " (the run then fails)." : " (the run then continues)."),
            List.of("Webhook wait: POST to the resume URL ($run.waitUrl in an earlier node, or the run wait details for editors) resumes the run; "
                + "the posted JSON becomes this node's output."));
    }

    /**
     * Save-time lint for a Wait node's config, called by {@code OrchestratorService}. Expression values (anything
     * containing a double opening brace) are accepted unchecked; literal values are parsed and capped exactly as a run would.
     */
    static void validateConfig(Map<String, Object> config, String label, Duration cap, List<String> errors) {
        Mode mode;
        try {
            mode = parseMode(config);
        } catch (IllegalArgumentException e) {
            errors.add("Wait node '" + label + "': " + e.getMessage());
            return;
        }
        try {
            if (mode == Mode.DURATION) {
                String raw = str(config.get("duration"));
                if (raw.isEmpty()) {
                    errors.add("Wait node '" + label + "' has no duration configured.");
                } else if (!raw.contains("{{")) {
                    requireWithinCap(parseDuration(raw, str(config.get("unit")), "minutes"), cap, "wait");
                }
            } else if (mode == Mode.UNTIL) {
                String raw = str(config.get("until"));
                if (raw.isEmpty()) {
                    errors.add("Wait node '" + label + "' has no 'until' time configured.");
                } else if (!raw.contains("{{")) {
                    parseInstant(raw);
                }
            } else {
                String raw = str(config.get("timeout"));
                if (!raw.isEmpty() && !raw.contains("{{")) {
                    requireWithinCap(parseDuration(raw, str(config.get("timeoutUnit")), "hours"), cap, "timeout");
                }
            }
        } catch (IllegalArgumentException e) {
            errors.add("Wait node '" + label + "': " + e.getMessage());
        }
    }

    static Mode parseMode(Map<String, Object> config) {
        String raw = str(config.get("mode")).toLowerCase(Locale.ROOT);
        if (raw.isEmpty()) {
            return str(config.get("until")).isEmpty() ? Mode.DURATION : Mode.UNTIL;
        }
        return switch (raw) {
            case "duration" -> Mode.DURATION;
            case "until" -> Mode.UNTIL;
            case "webhook" -> Mode.WEBHOOK;
            default -> throw new IllegalArgumentException(
                "unknown wait mode '" + raw + "' - use duration, until or webhook.");
        };
    }

    /**
     * Parses {@code "90"} (in {@code unit}, default {@code defaultUnit}), {@code "90s"}, {@code "15m"}, {@code
     * "2 hours"}, {@code "1.5d"}, or an ISO-8601 duration ({@code PT30M}, {@code P1D}).
     */
    static Duration parseDuration(String raw, String unit, String defaultUnit) {
        String text = raw == null ? "" : raw.trim();
        if (text.isEmpty()) {
            throw new IllegalArgumentException("a duration is required.");
        }
        if (text.startsWith("P") || text.startsWith("p")) {
            try {
                Duration parsed = Duration.parse(text.toUpperCase(Locale.ROOT));
                if (parsed.isNegative()) {
                    throw new IllegalArgumentException("a duration cannot be negative.");
                }
                return parsed;
            } catch (DateTimeParseException e) {
                throw new IllegalArgumentException("'" + text + "' is not a valid ISO-8601 duration (e.g. PT30M).");
            }
        }
        Matcher m = DURATION_SHORTHAND.matcher(text);
        if (!m.matches()) {
            throw new IllegalArgumentException("'" + text + "' is not a valid duration - use e.g. 90, 15m, 2h, 1d or PT30M.");
        }
        String unitName = !m.group(2).isEmpty() ? m.group(2) : (unit == null || unit.isBlank() ? defaultUnit : unit);
        long unitMillis = unitMillis(unitName.trim().toLowerCase(Locale.ROOT));
        BigDecimal millis = new BigDecimal(m.group(1)).multiply(BigDecimal.valueOf(unitMillis));
        if (millis.compareTo(BigDecimal.valueOf(Long.MAX_VALUE / 4)) > 0) {
            throw new IllegalArgumentException("'" + text + "' is far too long a duration.");
        }
        return Duration.ofMillis(millis.longValue());
    }

    private static long unitMillis(String unit) {
        return switch (unit) {
            case "ms", "millisecond", "milliseconds" -> 1L;
            case "s", "sec", "secs", "second", "seconds" -> 1_000L;
            case "m", "min", "mins", "minute", "minutes" -> 60_000L;
            case "h", "hr", "hrs", "hour", "hours" -> 3_600_000L;
            case "d", "day", "days" -> 86_400_000L;
            case "w", "wk", "week", "weeks" -> 604_800_000L;
            default -> throw new IllegalArgumentException("unknown duration unit '" + unit
                + "' - use seconds, minutes, hours, days or weeks.");
        };
    }

    /**
     * ISO-8601 instant / offset / zoned date-time; a zone-less local date-time or a bare date is read as UTC; an
     * all-digit value is epoch seconds (or epoch millis from 12 digits up).
     */
    static Instant parseInstant(String raw) {
        String text = raw == null ? "" : raw.trim();
        if (text.isEmpty()) {
            throw new IllegalArgumentException("a timestamp is required.");
        }
        if (text.chars().allMatch(Character::isDigit) && text.length() <= 15) {
            long n = Long.parseLong(text);
            return text.length() >= 12 ? Instant.ofEpochMilli(n) : Instant.ofEpochSecond(n);
        }
        try {
            return Instant.parse(text);
        } catch (DateTimeParseException ignored) {
            // fall through to the other ISO shapes
        }
        try {
            return OffsetDateTime.parse(text).toInstant();
        } catch (DateTimeParseException ignored) {
            // fall through
        }
        try {
            return ZonedDateTime.parse(text).toInstant();
        } catch (DateTimeParseException ignored) {
            // fall through
        }
        try {
            return LocalDateTime.parse(text).toInstant(ZoneOffset.UTC);
        } catch (DateTimeParseException ignored) {
            // fall through
        }
        try {
            return LocalDate.parse(text).atStartOfDay().toInstant(ZoneOffset.UTC);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("'" + text + "' is not an ISO-8601 timestamp (e.g. 2026-10-05T09:00:00Z).");
        }
    }

    private static void requireWithinCap(Duration d, Duration cap, String what) {
        if (d.compareTo(cap) > 0) {
            throw new IllegalArgumentException("a " + what + " of " + humanize(d) + " exceeds the maximum of "
                + humanize(cap) + " (app.workflow.wait-node.max-duration-days).");
        }
    }

    static String humanize(Duration d) {
        long seconds = d.getSeconds();
        if (seconds < 1) {
            return d.toMillis() + " ms";
        }
        long days = seconds / 86_400;
        long hours = (seconds % 86_400) / 3_600;
        long minutes = (seconds % 3_600) / 60;
        long secs = seconds % 60;
        StringBuilder sb = new StringBuilder();
        if (days > 0) {
            sb.append(days).append("d ");
        }
        if (hours > 0) {
            sb.append(hours).append("h ");
        }
        if (minutes > 0) {
            sb.append(minutes).append("m ");
        }
        if (secs > 0) {
            sb.append(secs).append("s ");
        }
        return sb.toString().trim();
    }

    /**
     * Writes the durable wake-up row. Called by the engine just BEFORE it parks the run (see {@code
     * WorkflowExecutionEngine#finalizeWaiting}).
     */
    public void register(WorkflowRun run, WorkflowDefinition definition, WorkflowNodeDto node, String nextNodeId, Plan plan) {
        Instant now = Instant.now();
        repository.retireLiveForRun(run.getId(), WorkflowWait.Status.WAITING, WorkflowWait.Status.CANCELLED, now);
        repository.save(new WorkflowWait(UUID.randomUUID(), run.getId(), definition.getId(), node.id(), nextNodeId,
            plan.mode().name(), plan.wakeAt(), plan.failOnTimeout()));
    }

    /** What a workflow editor sees of a run's wait; the resume URL only while a webhook-mode wait is live. */
    public Optional<WaitView> describe(UUID runId) {
        return repository.findFirstByRunIdOrderByCreatedAtDesc(runId).map(w -> {
            String url = null;
            if (w.getStatus() == WorkflowWait.Status.WAITING && Mode.WEBHOOK.name().equals(w.getMode())) {
                url = tokens.resumeUrl(
                    TenantContext.current().map(TenantContext::getTenantId).orElse(null), runId, w.getNodeId());
            }
            return new WaitView(w.getMode(), w.getStatus().name(), w.getWakeAt(), url);
        });
    }

    private static String lowerFirst(String s) {
        return s == null || s.isEmpty() ? "" : Character.toLowerCase(s.charAt(0)) + s.substring(1);
    }

    private static String str(Object o) {
        return o == null ? "" : o.toString().trim();
    }
}
