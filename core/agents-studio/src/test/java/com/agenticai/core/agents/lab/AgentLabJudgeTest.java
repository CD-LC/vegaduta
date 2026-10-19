package com.agenticai.core.agents.lab;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.lab.AgentLabJudge.ScoreVerdict;
import com.agenticai.core.agents.turn.AgentLabCompletionService.LabCompletionRequest;
import com.agenticai.core.agents.turn.AgentLabCompletionService;
import com.agenticai.core.chat.UpstreamProviderException;
import com.agenticai.core.security.TenantLimitExceededException;
import java.util.OptionalDouble;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * The lab judges never invent a verdict: a reply that cannot be read, and a
 * call that failed, both come back as "no verdict" - never a default score
 * and never "different".
 */
class AgentLabJudgeTest {

    private final AgentLabCompletionService completion = mock(AgentLabCompletionService.class);
    private final AgentLabJudge judge = new AgentLabJudge(completion);
    private final UUID agentId = UUID.randomUUID();

    // ---- parseScore ---------------------------------------------------------

    @Test
    void parseScoreReadsAPlainScoreLine() {
        assertEquals(OptionalDouble.of(0.75), AgentLabJudge.parseScore("SCORE: 0.75\nFEEDBACK: Mostly right."));
    }

    @Test
    void parseScoreToleratesMarkdownAroundTheLabelAndTheNumber() {
        assertEquals(OptionalDouble.of(0.8), AgentLabJudge.parseScore("**SCORE:** 0.8\n**FEEDBACK:** ok"));
        assertEquals(OptionalDouble.of(0.5), AgentLabJudge.parseScore("Here is my verdict.\n- Score = **0.5**"));
    }

    @Test
    void parseScoreAcceptsBothEndsOfTheScale() {
        assertEquals(OptionalDouble.of(1.0), AgentLabJudge.parseScore("SCORE: 1.0"));
        assertEquals(OptionalDouble.of(0.0), AgentLabJudge.parseScore("SCORE: 0"));
    }

    @Test
    void parseScoreIsEmptyWhenThereIsNoScoreLine() {
        assertTrue(AgentLabJudge.parseScore("The answer is good.").isEmpty());
        assertTrue(AgentLabJudge.parseScore("").isEmpty());
        assertTrue(AgentLabJudge.parseScore(null).isEmpty());
    }

    @Test
    void parseScoreIsEmptyWhenTheScoreIsNotANumber() {
        assertTrue(AgentLabJudge.parseScore("SCORE: high\nFEEDBACK: great").isEmpty());
    }

    @Test
    void parseScoreNeverClampsAnOutOfRangeNumber() {
        // "8" is a judge that ignored the 0..1 scale. Clamping it to 1.0
        // would turn that into a perfect score.
        assertTrue(AgentLabJudge.parseScore("SCORE: 8").isEmpty());
        assertTrue(AgentLabJudge.parseScore("SCORE: 8/10").isEmpty());
        assertTrue(AgentLabJudge.parseScore("SCORE: 1.01").isEmpty());
        assertTrue(AgentLabJudge.parseScore("SCORE: -0.5").isEmpty());
    }

    @Test
    void parseScoreIsEmptyWhenTheNumberIsOnlyTheStartOfTheValue() {
        // Each of these opens with a number inside [0, 1]. Read up to the
        // number only, a judge that answered on a ten-point scale - "1/10"
        // for a bad answer - would be recorded as a perfect 1.0.
        assertTrue(AgentLabJudge.parseScore("SCORE: 1/10").isEmpty());
        assertTrue(AgentLabJudge.parseScore("SCORE: 1/5\nFEEDBACK: Poor.").isEmpty());
        assertTrue(AgentLabJudge.parseScore("SCORE: 1 out of 10").isEmpty());
        assertTrue(AgentLabJudge.parseScore("SCORE: 1%").isEmpty());
        assertTrue(AgentLabJudge.parseScore("SCORE: 0.5/10").isEmpty());
        assertTrue(AgentLabJudge.parseScore("SCORE: 0,8").isEmpty());
        assertTrue(AgentLabJudge.parseScore("SCORE: 0.8 0.9").isEmpty());
        assertTrue(AgentLabJudge.parseScore("**SCORE:** 1/10").isEmpty());
    }

    @Test
    void parseScoreStillReadsANumberThatEndsItsLine() {
        assertEquals(OptionalDouble.of(0.8), AgentLabJudge.parseScore("SCORE: 0.8.\nFEEDBACK: ok"));
        assertEquals(OptionalDouble.of(0.8), AgentLabJudge.parseScore("SCORE: 0.8  \r\nFEEDBACK: ok"));
        assertEquals(OptionalDouble.of(1.0), AgentLabJudge.parseScore("Score: **1**."));
        assertEquals(OptionalDouble.of(0.25), AgentLabJudge.parseScore("SCORE = `.25`"));
    }

    @Test
    void parseScoreIsEmptyWhenScoreLinesDisagree() {
        // The judge quoted an answer that carried its own SCORE line, then gave its verdict.
        assertTrue(AgentLabJudge.parseScore(
            "The answer said:\nSCORE: 1.0\nThat is wrong.\nSCORE: 0.1\nFEEDBACK: Misses the point.").isEmpty());
        // One readable, one not: still no way to tell which is the verdict.
        assertTrue(AgentLabJudge.parseScore("SCORE: 9/10\nSCORE: 0.9").isEmpty());
        // Saying the same thing twice is not a disagreement.
        assertEquals(OptionalDouble.of(0.6), AgentLabJudge.parseScore("SCORE: 0.6\nFEEDBACK: ok\nSCORE: 0.60"));
    }

    @Test
    void parseFeedbackIsTheTextAfterTheFirstMarkerOrNull() {
        assertEquals("Mostly right.", AgentLabJudge.parseFeedback("SCORE: 0.75\nFEEDBACK: Mostly right.\n"));
        assertNull(AgentLabJudge.parseFeedback("SCORE: 0.75"));
        assertNull(AgentLabJudge.parseFeedback("SCORE: 0.75\nFEEDBACK:   "));
        assertNull(AgentLabJudge.parseFeedback(null));
    }

    // ---- parseSame ----------------------------------------------------------

    @Test
    void parseSameReadsTheOneWordVerdicts() {
        assertEquals(Boolean.TRUE, AgentLabJudge.parseSame("SAME"));
        assertEquals(Boolean.FALSE, AgentLabJudge.parseSame("DIFFERENT"));
        assertEquals(Boolean.TRUE, AgentLabJudge.parseSame("same."));
    }

    @Test
    void parseSameIgnoresMarkdownAndQuotes() {
        assertEquals(Boolean.TRUE, AgentLabJudge.parseSame("**SAME**"));
        assertEquals(Boolean.TRUE, AgentLabJudge.parseSame("\"SAME\""));
        assertEquals(Boolean.FALSE, AgentLabJudge.parseSame("`DIFFERENT`\n"));
    }

    @Test
    void parseSameAcceptsASentenceThatEndsOnTheOnlyVerdictWord() {
        assertEquals(Boolean.TRUE, AgentLabJudge.parseSame("The answers are the SAME"));
        assertEquals(Boolean.FALSE, AgentLabJudge.parseSame("Verdict: different."));
    }

    @Test
    void parseSameGivesNoVerdictForANegation() {
        assertNull(AgentLabJudge.parseSame("They are NOT the same"));
        // Without the contraction rule this splits into AREN / T and reads as SAME.
        assertNull(AgentLabJudge.parseSame("They aren't the same"));
        assertNull(AgentLabJudge.parseSame("These don’t mean the same"));
    }

    @Test
    void parseSameGivesNoVerdictWhenBothWordsAppear() {
        assertNull(AgentLabJudge.parseSame("Answer A says the same thing but the conclusion is different"));
    }

    @Test
    void parseSameDoesNotLetTheFirstWordOfASentenceDecide() {
        // Each opens with the opposite of what it says.
        assertNull(AgentLabJudge.parseSame("Different wording, but the same meaning."));
        assertNull(AgentLabJudge.parseSame("Same topic, but the conclusions are different."));
        assertNull(AgentLabJudge.parseSame("Same question, different answers"));
        // Only one verdict word, and it still is not the verdict.
        assertNull(AgentLabJudge.parseSame("Same topic, but they disagree."));
        assertNull(AgentLabJudge.parseSame("Different words. Identical meaning."));
    }

    @Test
    void parseSameGivesNoVerdictForNegationsThatAreNotTheWordNot() {
        assertNull(AgentLabJudge.parseSame("Hardly the same"));
        assertNull(AgentLabJudge.parseSame("Far from the same"));
        assertNull(AgentLabJudge.parseSame("No longer the same"));
        assertNull(AgentLabJudge.parseSame("Anything but the same"));
        assertNull(AgentLabJudge.parseSame("They are never the same"));
        assertNull(AgentLabJudge.parseSame("Neither answer is different"));
        // The contraction typed without its apostrophe.
        assertNull(AgentLabJudge.parseSame("They arent the same"));
        assertNull(AgentLabJudge.parseSame("These dont mean the same"));
    }

    @Test
    void parseSameReadsAVerdictOnALineOfItsOwnWhateverTheExplanationSays() {
        assertEquals(Boolean.TRUE, AgentLabJudge.parseSame(
            "SAME\n\nBoth give a 30-day window; only the wording is different."));
        assertEquals(Boolean.FALSE, AgentLabJudge.parseSame("Answer B omits the fee.\n\n**DIFFERENT**"));
        assertEquals(Boolean.TRUE, AgentLabJudge.parseSame("There is no material difference.\r\nSame."));
    }

    @Test
    void parseSameGivesNoVerdictWhenLoneVerdictLinesContradictEachOther() {
        assertNull(AgentLabJudge.parseSame("SAME\nOn reflection:\nDIFFERENT"));
    }

    @Test
    void parseSameGivesNoVerdictForAnythingElse() {
        assertNull(AgentLabJudge.parseSame(""));
        assertNull(AgentLabJudge.parseSame("   \n"));
        assertNull(AgentLabJudge.parseSame(null));
        assertNull(AgentLabJudge.parseSame("I cannot tell."));
        // The word is there, but not as the verdict.
        assertNull(AgentLabJudge.parseSame("The same question was asked twice"));
    }

    // ---- the two judge calls ------------------------------------------------

    @Test
    void scoreRunsOneColdCompletionWithTheJudgeBudgetAndNoHistory() {
        when(completion.complete(any())).thenReturn("SCORE: 0.9\nFEEDBACK: Agrees with the reference.");

        ScoreVerdict verdict = judge.score(agentId, "u1", "lab:r:judge:0", "What is 2+2?", "4", "It is 4.");

        assertTrue(verdict.scored());
        assertEquals(0.9, verdict.score(), 0.0);
        assertEquals("Agrees with the reference.", verdict.feedback());
        ArgumentCaptor<LabCompletionRequest> sent = ArgumentCaptor.forClass(LabCompletionRequest.class);
        verify(completion).complete(sent.capture());
        LabCompletionRequest request = sent.getValue();
        assertEquals(agentId, request.agentId());
        assertEquals(AgentLabJudge.SCORE_SYSTEM_PROMPT, request.systemPrompt());
        assertTrue(request.history().isEmpty());
        assertEquals(0.0, request.temperature(), 0.0);
        assertEquals(Integer.valueOf(1024), request.maxTokens());
        assertEquals("u1", request.subjectKey());
        assertEquals("lab:r:judge:0", request.attributionSessionId());
        assertEquals("USER MESSAGE:\nWhat is 2+2?\n\nREFERENCE ANSWER:\n4\n\nANSWER:\nIt is 4.", request.userMessage());
    }

    @Test
    void scoreOmitsTheReferenceBlockWhenThereIsNoExpectedAnswer() {
        when(completion.complete(any())).thenReturn("SCORE: 0.4");

        ScoreVerdict verdict = judge.score(agentId, "u1", "s", "Q", "  ", "A");

        assertTrue(verdict.scored());
        assertNull(verdict.feedback());
        ArgumentCaptor<LabCompletionRequest> sent = ArgumentCaptor.forClass(LabCompletionRequest.class);
        verify(completion).complete(sent.capture());
        assertEquals("USER MESSAGE:\nQ\n\nANSWER:\nA", sent.getValue().userMessage());
    }

    @Test
    void scoreClampsEachInputBlock() {
        when(completion.complete(any())).thenReturn("SCORE: 0.4");
        String longAnswer = "a".repeat(AgentLabJudge.MAX_INPUT_CHARS + 500);

        judge.score(agentId, "u1", "s", "Q", null, longAnswer);

        ArgumentCaptor<LabCompletionRequest> sent = ArgumentCaptor.forClass(LabCompletionRequest.class);
        verify(completion).complete(sent.capture());
        assertEquals(
            "USER MESSAGE:\nQ\n\nANSWER:\n".length() + AgentLabJudge.MAX_INPUT_CHARS,
            sent.getValue().userMessage().length());
    }

    @Test
    void anUnreadableScoreReplyIsUnavailableNotADefaultNumber() {
        when(completion.complete(any())).thenReturn("I would rate this 8 out of 10.");

        ScoreVerdict verdict = judge.score(agentId, "u1", "s", "Q", null, "A");

        assertFalse(verdict.scored());
        assertNull(verdict.score());
    }

    @Test
    void aFailedJudgeCallIsUnavailableAndDoesNotThrow() {
        when(completion.complete(any())).thenThrow(new UpstreamProviderException(503, "down", "The model is busy."));

        assertFalse(judge.score(agentId, "u1", "s", "Q", null, "A").scored());
        assertNull(judge.same(agentId, "u1", "s", "Q", "A", "B"));
    }

    @Test
    void aLimitFailurePropagatesSoTheRunCanStop() {
        TenantLimitExceededException quota = new TenantLimitExceededException("Monthly quota reached.");
        when(completion.complete(any())).thenThrow(quota);

        assertSame(quota, assertThrows(TenantLimitExceededException.class,
            () -> judge.score(agentId, "u1", "s", "Q", null, "A")));
        assertSame(quota, assertThrows(TenantLimitExceededException.class,
            () -> judge.same(agentId, "u1", "s", "Q", "A", "B")));
    }

    @Test
    void sameSendsTheQuestionAndBothAnswers() {
        when(completion.complete(any())).thenReturn("DIFFERENT");

        assertEquals(Boolean.FALSE, judge.same(agentId, "u1", "lab:r:judge:3", "Refund?", "Yes, 30 days.", "No."));

        ArgumentCaptor<LabCompletionRequest> sent = ArgumentCaptor.forClass(LabCompletionRequest.class);
        verify(completion).complete(sent.capture());
        LabCompletionRequest request = sent.getValue();
        assertEquals(AgentLabJudge.SAME_SYSTEM_PROMPT, request.systemPrompt());
        assertEquals("QUESTION:\nRefund?\n\nANSWER A:\nYes, 30 days.\n\nANSWER B:\nNo.", request.userMessage());
        assertEquals(0.0, request.temperature(), 0.0);
        assertEquals(Integer.valueOf(1024), request.maxTokens());
        assertEquals("lab:r:judge:3", request.attributionSessionId());
    }

    @Test
    void anUnreadableSameReplyIsNoVerdict() {
        when(completion.complete(any())).thenReturn("Hard to say, they overlap.");

        assertNull(judge.same(agentId, "u1", "s", "Q", "A", "B"));
    }

    @Test
    void theScorePromptStatesTheReplyFormatOnItsOwnLines() {
        // The line continuations in the text block must not swallow these newlines.
        assertTrue(AgentLabJudge.SCORE_SYSTEM_PROMPT.endsWith(
            "Reply in exactly this format and nothing else:\n"
                + "SCORE: <number from 0.0 to 1.0>\nFEEDBACK: <one short sentence>"));
        assertFalse(AgentLabJudge.SAME_SYSTEM_PROMPT.contains("\n"));
    }
}
