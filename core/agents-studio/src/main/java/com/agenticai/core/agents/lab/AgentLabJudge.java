package com.agenticai.core.agents.lab;

import com.agenticai.core.agents.turn.AgentLabCompletionService.LabCompletionRequest;
import com.agenticai.core.agents.turn.AgentLabCompletionService;
import com.agenticai.core.prompts.PromptResolver;
import com.agenticai.core.prompts.PromptSlot;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The lab's two LLM judges: a 0..1 score for one answer (Prompt Knockout)
 * and a same-meaning verdict for a pair of answers (Consistency check).
 *
 * <p>Both run on the agent's own model through {@link
 * AgentLabCompletionService}, not through core/quality's {@code EvalEngine}:
 * that judge is BYOK-only (a hosted-pool tenant would get no verdict at all)
 * and its spend is not attributed to a run. The cost of that choice is that
 * lab scores are not comparable with the Quality page's, and the model
 * grades its own answers.
 *
 * <p><b>No verdict is never a verdict.</b> A reply that cannot be parsed, an
 * empty reply and a failed call all come back as "unavailable" / {@code
 * null} - never a default score, never a clamp, never "different". The same
 * rule as {@code AbstractLlmJudgeEvaluator}, and the reason the Consistency
 * check can report "inconclusive" instead of a disagreement it never saw.
 * Only a limit-type failure (quota, budget, rate limit, tenant stopped)
 * propagates, so the run stops instead of spending on.
 */
@Component
public class AgentLabJudge {

    private static final Logger log = LoggerFactory.getLogger(AgentLabJudge.class);

    /**
     * Same budget as the quality judges ({@code JUDGE_MAX_TOKENS}). Thinking
     * models in the hosted pool draw their reasoning from this budget and can
     * return an empty completion at a few hundred tokens.
     */
    static final int JUDGE_MAX_TOKENS = 1024;

    static final double JUDGE_TEMPERATURE = 0.0;

    /** Per input block. A cell answer is already clamped to this by the run service. */
    static final int MAX_INPUT_CHARS = 6000;

    private static final int MAX_FEEDBACK_CHARS = 500;

    /** Registry keys of the two judge prompts (default text: {@code prompts/en/lab.judge.*.md}). */
    static final String SCORE_KEY = "lab.judge.score";

    static final String SAME_KEY = "lab.judge.same";

    /**
     * The shipped wording of the two keys, for tests only: a judge call resolves them through the
     * registry so a tenant or agent override applies.
     */
    static final String SCORE_SYSTEM_PROMPT = PromptResolver.defaultsOnly().catalog().defaultText(SCORE_KEY);

    static final String SAME_SYSTEM_PROMPT = PromptResolver.defaultsOnly().catalog().defaultText(SAME_KEY);

    private final PromptSlot prompts = new PromptSlot();

    /** The prompt registry, so a tenant or agent override of the lab judge prompts applies; absent = shipped text. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setPromptResolver(PromptResolver resolver) {
        prompts.use(resolver);
    }

    /** A line that claims to carry the score; group 1 is everything after the {@code :} or {@code =}. */
    private static final Pattern SCORE_LINE = Pattern.compile("(?im)^\\W*SCORE\\W*[:=](.*)$");

    /**
     * What a score line's value must be, in full: one number, optionally
     * wrapped in markdown emphasis, optionally closed by a full stop - and
     * nothing after it. The end anchor is the point: "1/10", "1 out of 10",
     * "1%", "0.5/10" and "0,8" all start with a number inside [0, 1], and a
     * pattern that stops at the number reads a judge that ignored the scale
     * as a score on it.
     */
    private static final Pattern SCORE_VALUE =
        Pattern.compile("[\\s*_`]*([0-9]*\\.?[0-9]+)[\\s*_`]*\\.?[\\s*_`]*");

    /**
     * Words that turn a verdict word into its opposite or into no verdict at
     * all ("hardly the same", "far from the same", "no longer the same",
     * "anything but the same"), plus the contractions a model may write
     * without the apostrophe. The apostrophe forms are folded into NOT before
     * tokenising.
     */
    private static final Set<String> NEGATIONS = Set.of(
        "NOT", "NO", "NEVER", "NEITHER", "NOR", "NONE", "CANNOT",
        "HARDLY", "BARELY", "SCARCELY", "FAR", "ANYTHING", "LONGER", "UNLIKE", "WITHOUT",
        "ISNT", "ARENT", "WASNT", "WERENT", "DONT", "DOESNT", "DIDNT", "CANT", "WONT");

    private static final String SAME = "SAME";
    private static final String DIFFERENT = "DIFFERENT";

    private static final Pattern FEEDBACK_MARKER = Pattern.compile("(?i)FEEDBACK\\s*:");

    /** @param score null unless {@code scored}; {@code feedback} may be null either way */
    public record ScoreVerdict(boolean scored, Double score, String feedback) {

        static ScoreVerdict unavailable() {
            return new ScoreVerdict(false, null, null);
        }
    }

    private final AgentLabCompletionService completionService;

    public AgentLabJudge(AgentLabCompletionService completionService) {
        this.completionService = completionService;
    }

    /**
     * Grades {@code answer} to {@code userMessage}, against {@code expected}
     * when one is given. One model call.
     *
     * @param sessionId the {@code llm_call_log} attribution, {@code lab:<runId>:judge:<n>}
     */
    public ScoreVerdict score(
        UUID agentId, String subject, String sessionId, String userMessage, String expected, String answer
    ) {
        StringBuilder user = new StringBuilder("USER MESSAGE:\n").append(clamp(userMessage));
        if (expected != null && !expected.isBlank()) {
            user.append("\n\nREFERENCE ANSWER:\n").append(clamp(expected));
        }
        user.append("\n\nANSWER:\n").append(clamp(answer));

        String reply = ask(agentId, subject, sessionId, prompts.text(SCORE_KEY, agentId), user.toString());
        if (reply == null) {
            return ScoreVerdict.unavailable();
        }
        OptionalDouble score = parseScore(reply);
        if (score.isEmpty()) {
            log.warn("Lab score judge returned no usable SCORE line ({}): {}", sessionId, truncateForLog(reply));
            return ScoreVerdict.unavailable();
        }
        return new ScoreVerdict(true, score.getAsDouble(), parseFeedback(reply));
    }

    /**
     * Whether two answers to {@code question} mean the same thing. One model call.
     *
     * @return true / false, or null when there is no verdict
     */
    public Boolean same(
        UUID agentId, String subject, String sessionId, String question, String answerA, String answerB
    ) {
        String user = "QUESTION:\n" + clamp(question)
            + "\n\nANSWER A:\n" + clamp(answerA)
            + "\n\nANSWER B:\n" + clamp(answerB);
        String reply = ask(agentId, subject, sessionId, prompts.text(SAME_KEY, agentId), user);
        if (reply == null) {
            return null;
        }
        Boolean verdict = parseSame(reply);
        if (verdict == null) {
            log.warn("Lab same-meaning judge returned no usable verdict ({}): {}", sessionId, truncateForLog(reply));
        }
        return verdict;
    }

    /** The judge's reply, or null when the call failed for a reason that is not a limit. */
    private String ask(UUID agentId, String subject, String sessionId, String systemPrompt, String userText) {
        try {
            return completionService.complete(new LabCompletionRequest(
                agentId, systemPrompt, List.of(), userText, JUDGE_TEMPERATURE, JUDGE_MAX_TOKENS, subject, sessionId));
        } catch (RuntimeException e) {
            if (AgentLabCompletionService.isLimitFailure(e)) {
                throw e;
            }
            log.warn("Lab judge call failed ({}) - no verdict for this comparison", sessionId, e);
            return null;
        }
    }

    /**
     * The score, when every {@code SCORE:} line in the reply holds the same
     * finite number in [0, 1] and nothing else. Anything else is empty:
     *
     * <ul>
     *   <li>a number above 1 such as "8" - clamping it to 1.0 would turn a
     *       judge that ignored the scale into a perfect score;</li>
     *   <li>a number followed by more - "1/10", "1 out of 10", "1%",
     *       "0,8": each opens with a number that is in range, and reading
     *       "1/10" as 1.0 is the same inversion the no-clamp rule exists to
     *       prevent;</li>
     *   <li>two score lines that disagree, or one readable and one not: a
     *       judge that quotes an answer containing its own "SCORE:" line
     *       must not be graded by the quoted line.</li>
     * </ul>
     */
    static OptionalDouble parseScore(String reply) {
        if (reply == null) {
            return OptionalDouble.empty();
        }
        Double score = null;
        Matcher line = SCORE_LINE.matcher(reply);
        while (line.find()) {
            Matcher value = SCORE_VALUE.matcher(line.group(1));
            if (!value.matches()) {
                return OptionalDouble.empty();
            }
            double parsed;
            try {
                parsed = Double.parseDouble(value.group(1));
            } catch (NumberFormatException e) {
                return OptionalDouble.empty();
            }
            if (!Double.isFinite(parsed) || parsed < 0.0 || parsed > 1.0) {
                return OptionalDouble.empty();
            }
            if (score != null && score != parsed) {
                return OptionalDouble.empty();
            }
            score = parsed;
        }
        return score == null ? OptionalDouble.empty() : OptionalDouble.of(score);
    }

    /** The text after the first {@code FEEDBACK:}, or null when there is none. */
    static String parseFeedback(String reply) {
        if (reply == null) {
            return null;
        }
        Matcher matcher = FEEDBACK_MARKER.matcher(reply);
        if (!matcher.find()) {
            return null;
        }
        String feedback = reply.substring(matcher.end()).trim();
        if (feedback.isEmpty()) {
            return null;
        }
        return feedback.length() <= MAX_FEEDBACK_CHARS ? feedback : feedback.substring(0, MAX_FEEDBACK_CHARS - 1) + "…";
    }

    /**
     * Reads a SAME / DIFFERENT verdict. Two shapes are a verdict:
     *
     * <ol>
     *   <li><b>The word on a line of its own</b> - what the prompt asks for,
     *       with or without markdown, quotes or a full stop, and with or
     *       without an explanation on other lines. If several lines are a
     *       lone verdict word they must all be the same word.</li>
     *   <li><b>A sentence that ends on it</b> ("The answers are the SAME",
     *       "Verdict: different.") - only when the whole reply names exactly
     *       one of the two words, as its last word, with no negation
     *       anywhere.</li>
     * </ol>
     *
     * <p>Everything else is no verdict. In particular the first word of a
     * sentence decides nothing: "Different wording, but the same meaning"
     * and "Same topic, but the conclusions are different" each open with the
     * opposite of what they say.
     *
     * <p>The contracted "n't" is folded into NOT first: without that, "They
     * aren't the same" would split into AREN / T and read as SAME.
     */
    static Boolean parseSame(String reply) {
        if (reply == null) {
            return null;
        }
        List<String> tokens = new ArrayList<>();
        boolean loneSame = false;
        boolean loneDifferent = false;
        for (String line : reply.split("\\R")) {
            String normalised = line.toUpperCase(Locale.ROOT)
                .replaceAll("N['’]T\\b", " NOT")
                .replaceAll("[^\\p{L}]+", " ")
                .trim();
            if (normalised.isEmpty()) {
                continue;
            }
            if (SAME.equals(normalised)) {
                loneSame = true;
            } else if (DIFFERENT.equals(normalised)) {
                loneDifferent = true;
            }
            tokens.addAll(List.of(normalised.split(" ")));
        }
        if (loneSame || loneDifferent) {
            return loneSame && loneDifferent ? null : Boolean.valueOf(loneSame);
        }
        if (tokens.isEmpty() || tokens.stream().anyMatch(NEGATIONS::contains)) {
            return null;
        }
        boolean hasSame = tokens.contains(SAME);
        boolean hasDifferent = tokens.contains(DIFFERENT);
        if (hasSame == hasDifferent) {
            return null;
        }
        String last = tokens.get(tokens.size() - 1);
        if (hasSame && SAME.equals(last)) {
            return Boolean.TRUE;
        }
        if (hasDifferent && DIFFERENT.equals(last)) {
            return Boolean.FALSE;
        }
        return null;
    }

    private static String clamp(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= MAX_INPUT_CHARS ? text : text.substring(0, MAX_INPUT_CHARS);
    }

    private static String truncateForLog(String reply) {
        return reply.length() <= 200 ? reply : reply.substring(0, 200) + "...";
    }
}
