package com.agenticai.core.quality.impl;

import com.agenticai.core.quality.EvaluationContext;
import com.agenticai.core.quality.EvaluationType;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * RAG faithfulness (groundedness): are the claims in the agent's answer
 * supported by the documents that were actually retrieved for this turn?
 *
 * <p>Why a separate dimension from {@link AccuracyEvaluator}: ACCURACY asks
 * the judge whether the answer is TRUE, using the judge model's own world
 * knowledge (plus a reference answer offline). For a RAG agent that is the
 * wrong question - the tenant's documents are the source of truth, they are
 * frequently private or newer than any model's training data, and an answer
 * can be "accurate" by the judge's lights while being invented rather than
 * drawn from what was retrieved (or contradict a tenant policy the judge has
 * never seen). Faithfulness asks only "is each claim supported by the
 * RETRIEVED CONTEXT?", which is the property a RAG pipeline owner can act on
 * (retrieval misses vs. model hallucinating past its context).
 *
 * <p>The judge is asked to decompose the answer into its factual claims and
 * check each against the context, then score the supported fraction. It
 * returns the unsupported claims on an {@code UNSUPPORTED:} line, which is
 * appended to the stored feedback so a reviewer sees WHICH sentences were
 * ungrounded, not just a number. Pure conversational filler ("Happy to
 * help!") and restatements of the question are not claims.
 *
 * <p><b>Not applicable without context.</b> When {@link
 * EvaluationContext#hasRetrievedContext()} is false - no retrieval happened,
 * or the caller did not capture it (every online path today) - this returns
 * an explicit UNAVAILABLE "not applicable" result WITHOUT calling the judge.
 * Scoring faithfulness against nothing would either be vacuous (1.0 -
 * "nothing contradicted") or punitive (0.0 - "nothing supported"), and both
 * would be fabricated measurements polluting the aggregate. UNAVAILABLE
 * results carry no score and are excluded from {@code EvalEngine}'s
 * aggregate, so enabling this dimension costs nothing on non-RAG turns.
 *
 * <p>Context is bounded before it reaches the judge ({@value
 * #MAX_CONTEXT_CHARS} chars total, each chunk at most {@value
 * #MAX_CHUNK_CHARS}), keeping chunks in retrieval order and truncating the
 * tail - the top-ranked chunks are the ones most likely to have been used.
 * The judge is told when context was truncated so it does not mark a claim
 * unsupported merely because its source was cut.
 *
 * <p>Premium feature only (see {@link EvaluationType#FAITHFULNESS}).
 */
@Component
public class FaithfulnessEvaluator extends AbstractLlmJudgeEvaluator {

    static final int MAX_CONTEXT_CHARS = 24_000;
    static final int MAX_CHUNK_CHARS = 6_000;
    static final String NOT_APPLICABLE =
        "Not applicable - no retrieved context was supplied for this turn, so there is nothing to judge groundedness against";

    public FaithfulnessEvaluator(JudgeChatClientFactory judgeChatClientFactory) {
        super(judgeChatClientFactory);
    }

    @Override
    public EvaluationType getType() {
        return EvaluationType.FAITHFULNESS;
    }

    @Override
    protected String notApplicableReason(EvaluationContext context) {
        return context.hasRetrievedContext() ? null : NOT_APPLICABLE;
    }

    @Override
    protected String buildPrompt(EvaluationContext context) {
        return String.format(
            """
            You are a strict fact-checker evaluating whether an AI agent's answer is FAITHFUL to the
            documents that were retrieved for it. Judge ONLY against the RETRIEVED CONTEXT below -
            not against your own knowledge. A claim that is true in the world but absent from the
            context is UNSUPPORTED. A claim that contradicts the context is UNSUPPORTED.

            USER QUESTION:
            %s

            RETRIEVED CONTEXT (numbered chunks, in retrieval order):
            %s

            AGENT ANSWER:
            %s

            Instructions:
            1. Break the AGENT ANSWER into its individual factual claims. Ignore greetings, filler,
               hedges, and restatements of the question - they are not claims.
            2. For each claim, decide whether it is directly stated in, or clearly inferable from,
               the RETRIEVED CONTEXT.
            3. SCORE = (number of supported claims) / (total number of claims). If the answer makes
               no factual claims at all (e.g. it only says it could not find the information),
               SCORE is 1.0.

            Respond in this exact format:
            SCORE: <number from 0.0 to 1.0>
            UNSUPPORTED: <the unsupported claims, separated by " | ", or NONE>
            FEEDBACK: <one or two sentences summarising how well the answer is grounded>
            """,
            context.userMessage(),
            renderContext(context.retrievedContext()),
            context.agentResponse()
        );
    }

    /**
     * Appends the judge's {@code UNSUPPORTED:} line to the feedback so the
     * stored result names the ungrounded claims. A missing line or {@code
     * NONE} leaves the feedback as-is.
     */
    @Override
    protected String enrichFeedback(String verdict, String feedback) {
        for (String line : verdict.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("UNSUPPORTED:")) {
                String claims = trimmed.substring("UNSUPPORTED:".length()).trim();
                if (claims.isEmpty() || claims.equalsIgnoreCase("NONE")) {
                    return feedback;
                }
                return feedback + " Unsupported claims: " + claims;
            }
        }
        return feedback;
    }

    /** Numbers the non-blank chunks and bounds the total, noting any truncation for the judge. */
    static String renderContext(List<String> chunks) {
        List<String> nonBlank = new ArrayList<>();
        for (String chunk : chunks) {
            if (chunk != null && !chunk.isBlank()) {
                nonBlank.add(chunk.strip());
            }
        }
        StringBuilder out = new StringBuilder();
        int omitted = 0;
        for (int i = 0; i < nonBlank.size(); i++) {
            String chunk = nonBlank.get(i);
            boolean cut = chunk.length() > MAX_CHUNK_CHARS;
            if (cut) {
                chunk = chunk.substring(0, MAX_CHUNK_CHARS) + " [...chunk truncated]";
            }
            String entry = "[" + (i + 1) + "] " + chunk + "\n";
            if (out.length() > 0 && out.length() + entry.length() > MAX_CONTEXT_CHARS) {
                omitted = nonBlank.size() - i;
                break;
            }
            out.append(entry);
        }
        if (omitted > 0) {
            out.append("[").append(omitted)
                .append(" further chunk(s) omitted for length - do not mark a claim unsupported only because its source may be in an omitted chunk; "
                    + "judge it UNSUPPORTED only if it contradicts the context shown]\n");
        }
        return out.toString();
    }
}
