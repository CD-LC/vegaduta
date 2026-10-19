package com.agenticai.core.quality.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.agenticai.core.quality.EvaluationContext;
import com.agenticai.core.quality.EvaluationResult;
import com.agenticai.core.quality.EvaluationStatus;
import com.agenticai.core.quality.EvaluationType;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.Prompt;

/**
 * {@link FaithfulnessEvaluator}: judges the answer against the RETRIEVED
 * context only, surfaces the unsupported claims, and - the property that keeps
 * it honest - reports not-applicable without spending a judge call when a turn
 * had no retrieved context, instead of inventing a groundedness score.
 */
class FaithfulnessEvaluatorTest {

    private static EvaluationContext contextWith(List<String> retrieved) {
        return new EvaluationContext(
            UUID.randomUUID(), "rag-agent", "Answer from the handbook only.",
            "How many days of annual leave do I get?",
            "You get 24 days of annual leave, plus your birthday off.",
            List.of(), 0, 0, "test-model", null, retrieved
        );
    }

    private record Judge(JudgeChatClientFactory factory, ChatClient client) {
    }

    private static Judge judgeReturning(String verdict) {
        JudgeChatClientFactory factory = mock(JudgeChatClientFactory.class);
        ChatClient chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        when(chatClient.prompt(any(Prompt.class)).call().content()).thenReturn(verdict);
        when(factory.buildJudgeClient(anyInt())).thenReturn(chatClient);
        return new Judge(factory, chatClient);
    }

    @Test
    void noRetrievedContextIsNotApplicableAndNeverCallsTheJudge() {
        JudgeChatClientFactory factory = mock(JudgeChatClientFactory.class);
        FaithfulnessEvaluator evaluator = new FaithfulnessEvaluator(factory);

        for (List<String> none : java.util.Arrays.asList(null, List.<String>of(), List.of("  ", ""))) {
            EvaluationResult result = evaluator.evaluate(contextWith(none));
            assertEquals(EvaluationStatus.UNAVAILABLE, result.status());
            assertNull(result.score(), "Faithfulness against nothing is not a measurement");
            assertTrue(result.feedback().startsWith("Not applicable"));
        }
        // No client resolved, no tokens spent, on every non-RAG turn.
        verifyNoInteractions(factory);
    }

    @Test
    void onlineNineArgContextHasNoRetrievedContext() {
        EvaluationContext online = new EvaluationContext(
            UUID.randomUUID(), "a", "s", "q", "r", List.of(), 0, 0, "m");
        assertFalse(online.hasRetrievedContext());
        assertNull(online.retrievedContext());
        assertNull(online.expectedOutput());
    }

    @Test
    void groundedVerdictIsReportedWithTheJudgesScore() {
        Judge judge = judgeReturning("SCORE: 1.0\nUNSUPPORTED: NONE\nFEEDBACK: Every claim is in chunk 1.");

        EvaluationResult result = new FaithfulnessEvaluator(judge.factory())
            .evaluate(contextWith(List.of("Employees receive 24 days of annual leave.", "Birthdays are a paid day off.")));

        assertEquals(EvaluationType.FAITHFULNESS, result.type());
        assertEquals(EvaluationStatus.EVALUATED, result.status());
        assertEquals(1.0, result.score());
        assertEquals("Every claim is in chunk 1.", result.feedback());
    }

    @Test
    void unsupportedClaimsAreSurfacedInTheFeedback() {
        Judge judge = judgeReturning(
            "SCORE: 0.5\nUNSUPPORTED: plus your birthday off\nFEEDBACK: Half the answer is not in the context.");

        EvaluationResult result = new FaithfulnessEvaluator(judge.factory())
            .evaluate(contextWith(List.of("Employees receive 24 days of annual leave.")));

        assertEquals(0.5, result.score());
        assertEquals("Half the answer is not in the context. Unsupported claims: plus your birthday off", result.feedback());
    }

    @Test
    void promptCarriesTheNumberedContextAndIsSentAtTemperatureZero() {
        Judge judge = judgeReturning("SCORE: 1.0\nUNSUPPORTED: NONE\nFEEDBACK: ok");

        new FaithfulnessEvaluator(judge.factory())
            .evaluate(contextWith(List.of("Employees receive 24 days of annual leave.", "Birthdays are a paid day off.")));

        ArgumentCaptor<Prompt> prompt = ArgumentCaptor.forClass(Prompt.class);
        verify(judge.client(), org.mockito.Mockito.atLeastOnce()).prompt(prompt.capture());
        Prompt sent = prompt.getAllValues().get(prompt.getAllValues().size() - 1);
        String text = sent.getContents();
        assertTrue(text.contains("[1] Employees receive 24 days of annual leave."));
        assertTrue(text.contains("[2] Birthdays are a paid day off."));
        assertTrue(text.contains("You get 24 days of annual leave"));
        assertEquals(0.0, sent.getOptions().getTemperature());
    }

    @Test
    void oversizedContextIsBoundedAndTheJudgeIsToldAboutTheOmission() {
        List<String> chunks = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            chunks.add("x".repeat(FaithfulnessEvaluator.MAX_CHUNK_CHARS + 100));
        }

        String rendered = FaithfulnessEvaluator.renderContext(chunks);

        assertTrue(rendered.length() < FaithfulnessEvaluator.MAX_CONTEXT_CHARS + 500);
        assertTrue(rendered.contains("[...chunk truncated]"));
        assertTrue(rendered.contains("further chunk(s) omitted for length"));
        assertTrue(rendered.startsWith("[1] "));
    }
}
