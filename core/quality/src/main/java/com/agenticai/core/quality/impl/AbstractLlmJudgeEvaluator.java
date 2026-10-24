package com.agenticai.core.quality.impl;

import com.agenticai.core.prompts.PromptResolver;
import com.agenticai.core.prompts.PromptSlot;
import com.agenticai.core.quality.EvaluationContext;
import com.agenticai.core.quality.EvaluationResult;
import com.agenticai.core.quality.Evaluator;
import java.time.Duration;
import java.util.OptionalDouble;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Shared pipeline for every LLM-as-judge evaluator in this module
 * (helpfulness, accuracy, tone, relevance, safety, faithfulness) - subclasses
 * supply the {@link #getType()} and the judge prompt, plus optionally a
 * not-applicable rule and extra verdict parsing.
 *
 * <p>Obtains its judge {@link ChatClient} through {@link
 * JudgeChatClientFactory}, which resolves the tenant's own active BYOK
 * provider via {@code LlmGatewayService}/{@code ProviderCredentialResolver} -
 * the same pattern {@code AssistService#buildChatClient} and {@code
 * McpStdioSelfHealer#resolveChatClient} already use, rather than each
 * evaluator duplicating credential resolution.
 *
 * <p>Replaces five copy-pasted implementations whose every failure path
 * fabricated a neutral 0.5 "measurement" (no active provider, provider
 * error, even an unparseable verdict all came back as {@code score=0.5},
 * which a caller could not tell apart from a real mid-range evaluation).
 * The honest contract now is:
 * <ul>
 *   <li>Judge verdict obtained and parsed -> {@link EvaluationResult#of}
 *       with the judge's ACTUAL score (clamped to [0,1]).</li>
 *   <li>No active provider, provider/gateway failure, a blank completion, or
 *       a verdict with no parseable {@code SCORE:} line -> {@link
 *       EvaluationResult#unavailable} with the reason. Never a number.</li>
 *   <li>A subclass that decides the dimension does not apply to this turn
 *       ({@link #notApplicableReason} non-null, e.g. faithfulness with no
 *       retrieved context) -> {@link EvaluationResult#unavailable} with that
 *       reason, and the judge is never called (no spend).</li>
 * </ul>
 *
 * <p><b>Deterministic judging.</b> Every judge request carries its own
 * {@link ChatOptions} with {@code temperature 0.0} ({@link #JUDGE_OPTIONS}).
 * {@link JudgeChatClientFactory} already asks the gateway for temperature 0,
 * but that value lives in the provider client's DEFAULT options, which a
 * gateway memo, a provider factory that applies its own sampling defaults,
 * or a future change to the factory can silently replace - and a judge that
 * samples at the provider default (often 0.7-1.0) turns the same answer into
 * a different score on every run, which is exactly the noise the eval
 * reliability stats then misread as a regression. Setting it on the request
 * makes it a property of the judge itself. Only temperature is set: Spring
 * AI merges per-request options over the model's defaults field by field, so
 * the model id and max-tokens the factory chose are kept.
 */
abstract class AbstractLlmJudgeEvaluator implements Evaluator {

    // Verdicts are two short lines (SCORE/FEEDBACK); 1024 tokens is roomy.
    private static final int JUDGE_MAX_TOKENS = 1024;

    /**
     * Per-request judge options - temperature only, see the class javadoc.
     * Package-private so tests can assert the exact options object sent.
     */
    static final ChatOptions JUDGE_OPTIONS = ChatOptions.builder().temperature(0.0).build();

    private final Logger log = LoggerFactory.getLogger(getClass());

    /** The prompt registry the judge prompts come from; the shipped text until a resolver is wired. */
    protected final PromptSlot prompts = new PromptSlot();

    /** Wired by Spring (inherited by every judge evaluator); absent = the shipped prompts. */
    @Autowired(required = false)
    public void setPromptResolver(PromptResolver resolver) {
        prompts.use(resolver);
    }

    /** A placeholder value: what {@code String.format("%s", value)} printed, so a null prints as before. */
    protected static String v(Object value) {
        return String.valueOf(value);
    }

    private final JudgeChatClientFactory judgeChatClientFactory;

    protected AbstractLlmJudgeEvaluator(JudgeChatClientFactory judgeChatClientFactory) {
        this.judgeChatClientFactory = judgeChatClientFactory;
    }

    /** Builds this dimension's judge prompt from the turn under evaluation. */
    protected abstract String buildPrompt(EvaluationContext context);

    /**
     * Hook for dimensions that only apply to some turns. Non-null means "not
     * applicable here, for this reason": {@link #evaluate} returns it as an
     * UNAVAILABLE result without resolving a judge client or spending a call.
     * Default: always applicable.
     */
    protected String notApplicableReason(EvaluationContext context) {
        return null;
    }

    /**
     * Hook for extra verdict lines a dimension asks the judge for (e.g.
     * faithfulness's UNSUPPORTED claims). Receives the raw verdict and the
     * already-extracted FEEDBACK text; returns the feedback to store. Default:
     * the FEEDBACK line unchanged.
     */
    protected String enrichFeedback(String verdict, String feedback) {
        return feedback;
    }

    @Override
    public final EvaluationResult evaluate(EvaluationContext context) {
        String notApplicable = notApplicableReason(context);
        if (notApplicable != null) {
            return EvaluationResult.unavailable(getType(), notApplicable);
        }
        String verdict;
        try {
            ChatClient judgeClient = judgeChatClientFactory.buildJudgeClient(JUDGE_MAX_TOKENS);
            Duration timeout = JudgeCallGuard.orDefault(judgeChatClientFactory.callTimeout());
            verdict = JudgeCallGuard.callWithTimeout(
                () -> judgeClient.prompt(new Prompt(buildPrompt(context), JUDGE_OPTIONS)).call().content(), timeout);
        } catch (Exception e) {
            String why = judgeChatClientFactory.describeFailure(e);
            if (why == null || why.isBlank()) {
                why = JudgeCallGuard.describe(e);
            }
            log.warn("{} evaluation unavailable - no LLM judge verdict ({})", getType(), why, e);
            return EvaluationResult.judgeUnavailable(getType(), why);
        }

        if (verdict == null || verdict.isBlank()) {
            log.warn("{} evaluation unavailable - judge returned an empty completion", getType());
            return EvaluationResult.judgeUnavailable(getType(), "empty completion");
        }

        OptionalDouble score = parseScore(verdict);
        if (score.isEmpty()) {
            log.warn("{} evaluation unavailable - judge verdict had no parseable SCORE line: {}",
                getType(), truncateForLog(verdict));
            // A judge whose output cannot be read is a judge outage (the run reports it with the reason
            // and stops), not a per-case curiosity: every case would otherwise burn its calls to learn it.
            return EvaluationResult.judgeUnavailable(getType(),
                "judge returned a malformed verdict (no parseable SCORE line): " + JudgeCallGuard.sample(verdict));
        }
        return EvaluationResult.of(getType(), score.getAsDouble(), enrichFeedback(verdict, extractFeedback(verdict)));
    }

    /**
     * Extracts the {@code SCORE:} line. Empty - never a default number - when
     * the line is missing or not a finite number: an unparseable verdict is a
     * failed evaluation, not a 0.5 one.
     */
    private OptionalDouble parseScore(String response) {
        for (String line : response.split("\n")) {
            if (line.trim().startsWith("SCORE:")) {
                try {
                    double parsed = Double.parseDouble(line.trim().substring("SCORE:".length()).trim());
                    if (Double.isFinite(parsed)) {
                        // A slightly out-of-range but finite judge score (e.g.
                        // "1") clamps rather than discards the whole verdict.
                        return OptionalDouble.of(Math.max(0.0, Math.min(1.0, parsed)));
                    }
                } catch (NumberFormatException e) {
                    log.debug("Unparseable SCORE line in judge verdict: {}", line.trim());
                }
                return OptionalDouble.empty();
            }
        }
        return OptionalDouble.empty();
    }

    private String extractFeedback(String response) {
        for (String line : response.split("\n")) {
            if (line.trim().startsWith("FEEDBACK:")) {
                return line.trim().substring("FEEDBACK:".length()).trim();
            }
        }
        return "No feedback provided by judge";
    }

    private static String truncateForLog(String verdict) {
        if (verdict == null) {
            return "<null>";
        }
        return verdict.length() <= 200 ? verdict : verdict.substring(0, 200) + "...";
    }
}
