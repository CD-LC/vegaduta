package com.agenticai.core.agents.job.sentinel;

import com.agenticai.core.json.JsonMappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The sentinel verdict contract - what a sentinel-mode scheduled run is asked
 * to end its answer with, and what {@link AgentSentinelSweep} parses back out.
 *
 * <p>A sentinel is a WATCHER, not a doer: it observes on a cadence and reports
 * upward, so its output is a verdict, not a deliverable. The contract is a
 * fenced block - the same shape the web console's generative-UI renderer uses
 * for {@code ```json_ui} - because a fenced block survives the model also
 * writing ordinary prose around it, and because a miss is a clean signal
 * rather than a half-parsed document.
 *
 * <pre>
 * ```sentinel
 * {"verdict": "quiet" | "notify" | "propose",
 *  "title": "...", "body": "...", "severity": "INFO|WARNING|ERROR",
 *  "proposal": {"summary": "...", "goal": "..."}}
 * ```
 * </pre>
 *
 * <ul>
 *   <li>{@code quiet} - nothing changed worth a human's attention. The common
 *       case; a sentinel that talks every tick is an alarm nobody reads.</li>
 *   <li>{@code notify} - write a notification to the schedule's owner.</li>
 *   <li>{@code propose} - park a follow-up run behind a human approval
 *       ({@code agent.proposal} approval request). {@code proposal.goal} is the
 *       FULL instruction for that run; the verdict's own {@code title}/{@code
 *       body} explain to the human why it is being asked. A propose verdict
 *       without a usable proposal is downgraded to notify - the signal still
 *       reaches a human, just without the one-tap action.</li>
 * </ul>
 *
 * <p>Parsing is deliberately strict at the edges a parser can afford and
 * forgiving everywhere else: the LAST {@code ```sentinel} block wins (a model
 * that emitted an early draft and then revised itself gets its final word
 * evaluated, not its first), field lengths are clamped rather than rejected,
 * and any other malformed shape yields {@link Optional#empty()} - which the
 * sweep records as {@code NO_VERDICT} and otherwise ignores, because a
 * sentinel that cannot even state its verdict is not worth escalating.
 */
public record SentinelVerdict(Verdict verdict, String title, String body, Severity severity, Proposal proposal) {

    public enum Verdict { QUIET, NOTIFY, PROPOSE }

    public enum Severity { INFO, WARNING, ERROR }

    /** The follow-up run a {@code propose} verdict asks a human to ratify. */
    public record Proposal(String summary, String goal) {}

    /**
     * Appended to a sentinel schedule's rendered goal at enqueue (see {@code
     * AgentScheduleService#renderGoal}) so the contract the sweep parses is
     * the contract the model was actually shown - the two must never drift
     * apart, which is why the text lives here next to the parser.
     */
    public static final String GOAL_CONTRACT = """

        [Sentinel mode] This is a scheduled watch run: observe and report, do not act \
        on the world yourself (no sends, writes, or changes - a human ratifies actions).
        End your reply with exactly one verdict block:

        ```sentinel
        {"verdict": "quiet" | "notify" | "propose",
         "title": "one line for the notification", "body": "what you found, for a human reader",
         "severity": "INFO" | "WARNING" | "ERROR",
         "proposal": {"summary": "the action you recommend", "goal": "full instructions for that run"}}
        ```

        - "quiet" when nothing worth a human's attention changed - the expected verdict; when in \
        doubt, stay quiet.
        - "notify" to tell the schedule's owner what you found. Omit "proposal".
        - "propose" when a follow-up agent run could fix or pursue what you found - a human must \
        approve it before anything runs; "proposal.goal" is that run's complete instruction. \
        Never take the action in this run.
        """;

    private static final Pattern FENCE =
        Pattern.compile("```sentinel\\s*\\n([\\s\\S]*?)```", Pattern.CASE_INSENSITIVE);

    // Clamps, not validation failures: a long title is a style slip, not a reason
    // to throw away a signal the model did produce.
    private static final int MAX_TITLE = 200;
    private static final int MAX_BODY = 2000;
    private static final int MAX_SUMMARY = 500;
    private static final int MAX_GOAL = 4000;

    private static final ObjectMapper MAPPER = JsonMappers.plain();

    /**
     * Extracts the verdict from a finished job's result text. Empty when no
     * {@code ```sentinel} block exists or none parses - the caller records
     * {@code NO_VERDICT} and moves on.
     */
    public static Optional<SentinelVerdict> extract(String resultText) {
        if (resultText == null || resultText.isBlank()) {
            return Optional.empty();
        }
        Matcher matcher = FENCE.matcher(resultText);
        String last = null;
        while (matcher.find()) {
            last = matcher.group(1);
        }
        if (last == null) {
            return Optional.empty();
        }
        try {
            JsonNode node = MAPPER.readTree(last.trim());
            Verdict verdict = parseVerdict(node.path("verdict").asText(""));
            if (verdict == null) {
                return Optional.empty();
            }
            Severity severity = parseSeverity(node.path("severity").asText(""));
            Proposal proposal = parseProposal(node.path("proposal"));
            // A propose without a usable proposal is downgraded to notify: the
            // signal still reaches the human, only the one-tap action is lost.
            if (verdict == Verdict.PROPOSE && proposal == null) {
                verdict = Verdict.NOTIFY;
            }
            return Optional.of(new SentinelVerdict(
                verdict,
                clamp(node.path("title").asText(null), MAX_TITLE),
                clamp(node.path("body").asText(null), MAX_BODY),
                severity, proposal));
        } catch (Exception e) {
            // Malformed JSON in the block is the same answer as no block:
            // NO_VERDICT upstream, never an escalation for a parse slip.
            return Optional.empty();
        }
    }

    private static Verdict parseVerdict(String raw) {
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "quiet" -> Verdict.QUIET;
            case "notify" -> Verdict.NOTIFY;
            case "propose" -> Verdict.PROPOSE;
            default -> null;
        };
    }

    private static Severity parseSeverity(String raw) {
        return switch (raw.trim().toUpperCase(Locale.ROOT)) {
            case "WARNING" -> Severity.WARNING;
            case "ERROR" -> Severity.ERROR;
            default -> Severity.INFO;
        };
    }

    private static Proposal parseProposal(JsonNode node) {
        if (!node.isObject()) {
            return null;
        }
        String goal = clamp(node.path("goal").asText(null), MAX_GOAL);
        if (goal == null) {
            return null;
        }
        String summary = clamp(node.path("summary").asText(null), MAX_SUMMARY);
        return new Proposal(summary, goal);
    }

    private static String clamp(String s, int max) {
        if (s == null || s.isBlank()) {
            return null;
        }
        String trimmed = s.trim();
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
    }
}
