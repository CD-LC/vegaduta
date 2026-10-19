package com.agenticai.core.quality.eval;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;

/**
 * One golden case of the {@link EvalRunner}: the generation call, the judge call with its three-valued
 * verdict ({@code PASS} / {@code FAIL} / {@code ABSTAIN}), and the OTel span both run inside. Split out of the
 * runner, which keeps the run loop, the token-budget check and the report.
 */
final class EvalCaseRun {

    private static final Logger log = LoggerFactory.getLogger(EvalCaseRun.class);

    private static final String JUDGE_SYSTEM_PROMPT = """
        You are an independent grader for a prompt-regression test suite. You will be given: the system
        prompt an AI assistant was given, the one user message it received, the rubric describing what a
        PASSING response must do, and the assistant's actual response. Judge STRICTLY against the rubric
        only - do not grade on writing quality, tone, or anything the rubric doesn't mention.

        Respond with EXACTLY one line, no other text:
        PASS
        or
        FAIL: <one short sentence explaining specifically which part of the rubric was not met>
        or
        ABSTAIN: <one short sentence naming what the assistant declined to do, or what it asked for>

        Use ABSTAIN when the assistant did not attempt the task at all - it refused, said it was
        unable to do this, asked for information it was missing, or handed the task to a human.
        ABSTAIN takes precedence: answer ABSTAIN even when declining is exactly what the rubric
        asked for. Whether stopping was the RIGHT call is not your decision - report only what the
        assistant did, and the suite will grade it.
        """;

    /**
     * Runs one golden case's generation + judge calls inside a single OTel
     * span (see class javadoc's tracing section) tagged with the same
     * Langfuse-recognized OTel attributes {@code LlmMetricsAdvisor}
     * (core/chat) uses: {@code langfuse.session.id} (this {@code evalRunId} -
     * every case in one run shares it, so a whole run groups as one Langfuse
     * session), {@code langfuse.trace.tags} ({@code ["eval", "golden-dataset"]}),
     * and {@code langfuse.trace.metadata.*} for the case id/persona/model.
     * The resulting trace id is logged (MDC already carries {@code evalRunId}
     * for the whole run) so a failing case can be correlated to its Langfuse
     * trace from CI logs alone, and is carried on the returned {@link
     * EvalResult#traceId()} so {@link LangfuseEvalClient} can link the
     * dataset-run item and scores to this exact trace after the span ends.
     */
    static EvalResult runCase(ChatClient chatClient, ChatClient judgeClient,
                                      EvalCase evalCase, Tracer tracer, String evalRunId) {
        Span span = tracer.spanBuilder("eval.case").startSpan();
        String traceId = span.getSpanContext().getTraceId();
        try (Scope scope = span.makeCurrent()) {
            span.setAttribute("langfuse.session.id", evalRunId);
            span.setAttribute(AttributeKey.stringArrayKey("langfuse.trace.tags"), List.of("eval", "golden-dataset"));
            span.setAttribute("langfuse.trace.metadata.evalCaseId", evalCase.id());
            span.setAttribute("langfuse.trace.metadata.persona", evalCase.persona());
            span.setAttribute("langfuse.trace.metadata.category", evalCase.categoryOrDefault());
            log.info("[eval] running case {} (traceId={})", evalCase.id(), traceId);

            EvalResult result = doRunCase(chatClient, judgeClient, evalCase);
            span.setAttribute("langfuse.trace.metadata.passed", result.passed());
            // Three-way outcome alongside the boolean (2026-09) so a Langfuse
            // run comparison can tell a correct refusal from a correct answer
            // - both are passed=true, and conflating them is exactly what
            // EvalOutcome exists to stop. Feasibility goes on too, since the
            // outcome is only interpretable against what the right move was.
            span.setAttribute("langfuse.trace.metadata.outcome", result.outcome().name());
            span.setAttribute("langfuse.trace.metadata.feasibility", evalCase.feasibilityOrDefault().name());
            span.setAttribute("langfuse.trace.metadata.confidenceTargetedScore", result.confidenceTargetedScore());
            if (!result.passed()) {
                String reason = result.judgeReason() == null ? "case did not pass" : result.judgeReason();
                span.setStatus(StatusCode.ERROR, reason);
                // Also on the trace's metadata, not just the span status:
                // Langfuse surfaces trace metadata in the run-comparison
                // table, so a failed item explains itself without opening
                // the trace.
                span.setAttribute("langfuse.trace.metadata.judgeReason", reason);
            }
            return result.withTraceId(traceId);
        } finally {
            span.end();
        }
    }

    private static EvalResult doRunCase(ChatClient chatClient, ChatClient judgeClient, EvalCase evalCase) {
        try {
            ChatResponse generationResponse = chatClient.prompt()
                .system(evalCase.systemPrompt())
                .user(evalCase.userMessage())
                .call()
                .chatResponse();
            String answer = generationResponse == null ? null : generationResponse.getResult().getOutput().getText();

            if (answer == null || answer.isBlank()) {
                return EvalResult.error(evalCase, "assistant returned an empty response");
            }

            String judgeInput = "System prompt given to the assistant:\n" + evalCase.systemPrompt()
                + "\n\nUser's message:\n" + evalCase.userMessage()
                + "\n\nRubric for a passing response:\n" + evalCase.rubric()
                + "\n\nAssistant's actual response:\n" + answer;

            ChatResponse judgeResponse = judgeClient.prompt()
                .system(JUDGE_SYSTEM_PROMPT)
                .user(judgeInput)
                .call()
                .chatResponse();
            String verdict = judgeResponse == null ? null : judgeResponse.getResult().getOutput().getText();

            // Combined generation + judge token cost for this case - see
            // EvalResult's javadoc. Read defensively: totalTokens() (default
            // method) is documented as an interface convenience, not
            // guaranteed non-null by every provider, so fall back to 0
            // rather than NPE-ing a secondary check into a hard run failure.
            int totalTokens = usageTokens(generationResponse) + usageTokens(judgeResponse);

            if (verdict == null || verdict.isBlank()) {
                return EvalResult.error(evalCase, "judge returned an empty verdict");
            }
            String trimmed = verdict.trim();
            if (trimmed.regionMatches(true, 0, "PASS", 0, "PASS".length())) {
                return EvalResult.pass(evalCase, answer, totalTokens);
            }
            if (trimmed.regionMatches(true, 0, "FAIL", 0, "FAIL".length())) {
                return EvalResult.fail(evalCase, answer,
                    reasonAfterColon(trimmed, "FAIL", "judge flagged this response without a specific reason"),
                    totalTokens);
            }
            // Third verdict (2026-09) - the assistant declined, asked for
            // what it was missing, or escalated. NOT a pass/fail decision
            // here: whether stopping was correct depends on the case's
            // EvalTaskFeasibility, which EvalResult.abstained applies. See
            // EvalOutcome for why this is a first-class outcome rather than
            // a flavour of FAIL. Checked after PASS/FAIL purely for
            // diff-readability - the three prefixes are disjoint, so the
            // order carries no meaning.
            if (trimmed.regionMatches(true, 0, "ABSTAIN", 0, "ABSTAIN".length())) {
                return EvalResult.abstained(evalCase, answer,
                    reasonAfterColon(trimmed, "ABSTAIN", "assistant declined without a stated reason"),
                    totalTokens);
            }
            // Malformed verdict (didn't start with PASS, FAIL or ABSTAIN) -
            // treated as an error, not a silent pass, since unlike
            // LlmQualityJudgeService's sampled background signal, this suite
            // exists specifically to be trusted as a pass/fail gate.
            return EvalResult.error(evalCase, "judge returned an unparseable verdict: " + trimmed);
        } catch (Exception e) {
            log.warn("Eval case {} threw an exception", evalCase.id(), e);
            return EvalResult.error(evalCase, "exception during run: " + e.getMessage());
        }
    }

    /**
     * Pulls the explanation out of a {@code "<VERDICT>: <reason>"} judge line.
     * Extracted so the FAIL and ABSTAIN branches cannot drift apart; the
     * behaviour is what the FAIL branch did inline before ABSTAIN existed,
     * including the length guard, which is what stops a bare {@code "FAIL"}
     * with no colon from returning the verdict token itself as its own
     * reason. One deliberate tightening: a verdict whose reason is present
     * but all whitespace ({@code "FAIL:   "}) now also falls back rather
     * than persisting an empty-string reason into the report.
     *
     * @param verdictToken the leading token already matched ({@code "FAIL"},
     *     {@code "ABSTAIN"}) - only its length is used, to check there is
     *     anything after {@code "<token>:"} at all.
     * @param fallback returned when the judge gave the verdict but no reason.
     */
    // Package-private, not private, for the same reason formatReport is: it
    // has real branching logic that needs asserting and no network
    // dependency, so EvalRunnerAbstentionTest can cover it directly rather
    // than only through a billed live judge call.
    static String reasonAfterColon(String trimmed, String verdictToken, String fallback) {
        if (trimmed.length() <= verdictToken.length() + 1) {
            return fallback;
        }
        String reason = trimmed.substring(trimmed.indexOf(':') + 1).trim();
        return reason.isEmpty() ? fallback : reason;
    }

    /** Null-safe token-usage read - see {@link #doRunCase}'s comment on why this never throws. */
    private static int usageTokens(ChatResponse response) {
        if (response == null || response.getMetadata() == null) {
            return 0;
        }
        Usage usage = response.getMetadata().getUsage();
        if (usage == null || usage.getTotalTokens() == null) {
            return 0;
        }
        return usage.getTotalTokens();
    }

    private EvalCaseRun() {
    }
}
