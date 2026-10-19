package com.agenticai.core.agents.lab;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.lab.AgentLabDtos.LabCellRequest;
import com.agenticai.core.agents.lab.AgentLabDtos.LabCellResult;
import com.agenticai.core.agents.lab.AgentLabDtos.LabRunRequest;
import com.agenticai.core.agents.lab.AgentLabDtos.LabRunResponse;
import com.agenticai.core.agents.lab.AgentLabDtos.LabSimilarity;
import com.agenticai.core.agents.lab.AgentLabJudge.ScoreVerdict;
import com.agenticai.core.agents.turn.AgentLabCompletionService.LabCompletionRequest;
import com.agenticai.core.chat.UpstreamProviderException;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** {@link AgentLabRunService}: runs that complete, per mode. */
class AgentLabRunServiceHappyPathTest extends AgentLabRunServiceTestSupport {

    // ---- happy paths ------------------------------------------------------------

    @Test
    void knockoutRunsEveryCellUnderItsPromptAndScoresEachAnswer() {
        List<LabCellRequest> cells = List.of(
            new LabCellRequest("b0:i0", 0, "Q0", null, "reference 0"),
            new LabCellRequest("s1:i0", 1, "Q0", null, "reference 0"),
            new LabCellRequest("b0:i1", 0, "Q1", null, null),
            new LabCellRequest("s1:i1", 1, "Q1", null, null));

        LabRunResponse queued = service.start(agentId, knockout(List.of("baseline", "without section 1"), cells), USER);
        bind();
        LabRunResponse run = service.get(agentId, queued.runId(), USER);

        assertEquals("QUEUED", queued.status());
        assertEquals(8, queued.plannedCalls());
        assertEquals(0, queued.completedCalls());
        assertEquals(4, queued.cells().size());
        assertEquals("PENDING", queued.cells().get(0).status());
        assertEquals("PENDING", queued.cells().get(0).scoreStatus());

        assertEquals("SUCCEEDED", run.status());
        assertEquals("KNOCKOUT", run.kind());
        assertEquals(agentId.toString(), run.agentId());
        assertEquals(8, run.completedCalls());
        assertEquals(run.completedCalls(), run.plannedCalls());
        assertNull(run.errorMessage());
        assertNull(run.similarity());
        assertNotNull(run.finishedAtMs());
        assertFalse(run.cancelRequested());
        assertEquals(List.of("b0:i0", "s1:i0", "b0:i1", "s1:i1"), run.cells().stream().map(LabCellResult::key).toList());
        for (LabCellResult result : run.cells()) {
            assertEquals("DONE", result.status());
            assertEquals("SCORED", result.scoreStatus());
            assertEquals(0.75, result.score(), 0.0);
            assertEquals("Fine.", result.scoreFeedback());
            assertFalse(result.answerTruncated());
            assertNull(result.errorMessage());
            assertNotNull(result.ms());
        }
        assertEquals("answer to lab:" + run.runId() + ":2", run.cells().get(2).answer());

        ArgumentCaptor<LabCompletionRequest> sent = ArgumentCaptor.forClass(LabCompletionRequest.class);
        verify(completion, times(4)).complete(sent.capture());
        List<LabCompletionRequest> calls = sent.getAllValues();
        assertEquals(List.of("baseline", "without section 1", "baseline", "without section 1"),
            calls.stream().map(LabCompletionRequest::systemPrompt).toList());
        assertEquals(List.of("Q0", "Q0", "Q1", "Q1"), calls.stream().map(LabCompletionRequest::userMessage).toList());
        for (LabCompletionRequest call : calls) {
            assertEquals(agentId, call.agentId());
            assertEquals(USER, call.subjectKey());
            // null = the agent's own temperature and max tokens
            assertNull(call.temperature());
            assertNull(call.maxTokens());
        }
        verify(judge).score(agentId, USER, "lab:" + run.runId() + ":judge:0", "Q0", "reference 0",
            "answer to lab:" + run.runId() + ":0");
        verify(judge).score(agentId, USER, "lab:" + run.runId() + ":judge:3", "Q1", null,
            "answer to lab:" + run.runId() + ":3");
        verify(judge, never()).same(any(), any(), any(), any(), any(), any());
        // The lock is free again, so the user can start the next run.
        assertTrue(store.locks.isEmpty());
    }

    @Test
    void aSucceededRunReportsTheCallsItReallyMadeNotTheEstimate() {
        when(completion.complete(any())).thenAnswer(call -> {
            if (request(call).attributionSessionId().endsWith(":1")) {
                throw new UpstreamProviderException(502, "bad gateway", "The model did not answer. Try again.");
            }
            return "ok";
        });

        LabRunResponse run = startAndRead(knockout(List.of("p"), List.of(
            cell("b0:i0", 0), cell("b0:i1", 0), cell("b0:i2", 0))));

        assertEquals("SUCCEEDED", run.status());
        // 3 cell calls + 2 judge calls: the failed cell is never judged.
        assertEquals(5, run.completedCalls());
        assertEquals(5, run.plannedCalls());
        LabCellResult failed = run.cells().get(1);
        assertEquals("ERROR", failed.status());
        assertEquals("The model did not answer. Try again.", failed.errorMessage());
        assertEquals("UNAVAILABLE", failed.scoreStatus());
        assertNull(failed.score());
        assertNull(failed.answer());
        assertEquals("SCORED", run.cells().get(2).scoreStatus());
    }

    @Test
    void anUnavailableJudgeVerdictLeavesTheScoreNullAndTheRunGoesOn() {
        when(judge.score(any(), any(), any(), any(), any(), any()))
            .thenReturn(new ScoreVerdict(false, null, null))
            .thenReturn(new ScoreVerdict(true, 1.0, null));

        LabRunResponse run = startAndRead(knockout(List.of("p"), List.of(cell("b0:i0", 0), cell("b0:i1", 0))));

        assertEquals("SUCCEEDED", run.status());
        assertEquals("UNAVAILABLE", run.cells().get(0).scoreStatus());
        assertNull(run.cells().get(0).score());
        assertEquals("DONE", run.cells().get(0).status());
        assertEquals("SCORED", run.cells().get(1).scoreStatus());
        assertEquals(1.0, run.cells().get(1).score(), 0.0);
    }

    @Test
    void anEmptyCompletionIsAFailedCellThatIsNeitherJudgedNorCompared() {
        when(completion.complete(any())).thenAnswer(call -> {
            String session = request(call).attributionSessionId();
            return session.endsWith(":0") ? "" : session.endsWith(":1") ? "  \n" : session.endsWith(":2") ? null : "ok";
        });

        LabRunResponse run = startAndRead(
            new LabRunRequest("CONSISTENCY", List.of(), cells(3), "JUDGE", "JUDGE"));

        // Empty string, whitespace and null alike - and three in a row trip the three-errors rule.
        assertEquals("FAILED", run.status());
        assertEquals("Three calls in a row failed. The last error: The model returned an empty answer.",
            run.errorMessage());
        for (LabCellResult cell : run.cells()) {
            assertEquals("ERROR", cell.status());
            assertNull(cell.answer());
            assertEquals("The model returned an empty answer.", cell.errorMessage());
            assertEquals("UNAVAILABLE", cell.scoreStatus());
            assertNull(cell.score());
        }
        assertEquals(3, run.completedCalls());
        verify(judge, never()).score(any(), any(), any(), any(), any(), any());
        verify(judge, never()).same(any(), any(), any(), any(), any(), any());
    }

    @Test
    void emptyAnswersAreLeftOutOfTheComparisonAndNeverReadAsConsistent() {
        when(completion.complete(any())).thenAnswer(call ->
            request(call).attributionSessionId().endsWith(":1") ? "" : "Thirty days.");

        LabRunResponse run = startAndRead(consistency(3, "JUDGE"));

        assertEquals("SUCCEEDED", run.status());
        assertEquals(List.of("DONE", "ERROR", "DONE"), run.cells().stream().map(LabCellResult::status).toList());
        assertEquals(List.of("k0", "k2"), run.similarity().keys());
        // 3 cell calls + the one pair of real answers
        assertEquals(4, run.completedCalls());
        verify(judge, times(1)).same(any(), any(), any(), any(), any(), any());
    }

    @Test
    void anEmptyAnswerDoesNotResetTheStreakOfFailedCalls() {
        AtomicInteger calls = new AtomicInteger();
        when(completion.complete(any())).thenAnswer(call -> {
            int n = calls.incrementAndGet();
            if (n == 2) {
                return "";
            }
            throw new UpstreamProviderException(502, "bad gateway", "Try again.");
        });

        LabRunResponse run = startAndRead(consistency(5, "NONE"));

        assertEquals("FAILED", run.status());
        assertEquals(List.of("ERROR", "ERROR", "ERROR", "PENDING", "PENDING"),
            run.cells().stream().map(LabCellResult::status).toList());
        verify(completion, times(3)).complete(any());
    }

    @Test
    void aLongAnswerIsClampedAndFlagged() {
        when(completion.complete(any())).thenReturn("a".repeat(6001));

        LabRunResponse run = startAndRead(consistency(1, "NONE"));

        assertEquals(6000, run.cells().get(0).answer().length());
        assertTrue(run.cells().get(0).answerTruncated());
        assertEquals("NONE", run.cells().get(0).scoreStatus());
    }

    @Test
    void consistencyUsesTheStoredPromptAndBuildsASymmetricMatrix() {
        // k0~k1 same, k0~k2 different, k1~k2 no verdict
        when(judge.same(any(), any(), any(), any(), any(), any()))
            .thenReturn(Boolean.TRUE).thenReturn(Boolean.FALSE).thenReturn(null);

        LabRunResponse run = startAndRead(consistency(3, "JUDGE"));

        assertEquals("SUCCEEDED", run.status());
        assertEquals(6, run.completedCalls());
        assertEquals(6, run.plannedCalls());
        LabSimilarity similarity = run.similarity();
        assertEquals("JUDGE", similarity.method());
        assertEquals("DONE", similarity.status());
        assertEquals(List.of("k0", "k1", "k2"), similarity.keys());
        List<List<Double>> m = similarity.matrix();
        assertEquals(3, m.size());
        for (int i = 0; i < 3; i++) {
            assertEquals(1.0, m.get(i).get(i), 0.0);
            for (int j = 0; j < 3; j++) {
                assertEquals(m.get(i).get(j), m.get(j).get(i), "matrix[" + i + "][" + j + "]");
            }
        }
        assertEquals(1.0, m.get(0).get(1), 0.0);
        assertEquals(0.0, m.get(0).get(2), 0.0);
        // No verdict is null - never recorded as "different".
        assertNull(m.get(1).get(2));

        ArgumentCaptor<LabCompletionRequest> sent = ArgumentCaptor.forClass(LabCompletionRequest.class);
        verify(completion, times(3)).complete(sent.capture());
        for (LabCompletionRequest call : sent.getAllValues()) {
            assertNull(call.systemPrompt(), "promptIndex null means the agent's stored prompt");
        }
        verify(judge).same(agentId, USER, "lab:" + run.runId() + ":judge:0", "What is the refund window?",
            "answer to lab:" + run.runId() + ":0", "answer to lab:" + run.runId() + ":1");
        verify(judge, times(3)).same(any(), any(), any(), any(), any(), any());
        verify(judge, never()).score(any(), any(), any(), any(), any(), any());
    }

    @Test
    void onlyAnsweredCellsAreCompared() {
        when(completion.complete(any())).thenAnswer(call -> {
            if (request(call).attributionSessionId().endsWith(":1")) {
                throw new UpstreamProviderException(502, "bad gateway", "Try again.");
            }
            return "ok";
        });

        LabRunResponse run = startAndRead(consistency(3, "JUDGE"));

        assertEquals("SUCCEEDED", run.status());
        assertEquals(List.of("k0", "k2"), run.similarity().keys());
        assertEquals(List.of(List.of(1.0, 1.0), List.of(1.0, 1.0)), run.similarity().matrix());
        // 3 cell calls + the one pair that exists
        assertEquals(4, run.completedCalls());
    }

    @Test
    void aSingleAnswerHasNothingToCompare() {
        LabRunResponse run = startAndRead(consistency(1, "JUDGE"));

        assertEquals("SUCCEEDED", run.status());
        assertEquals("DONE", run.similarity().status());
        assertEquals(List.of(List.of(1.0)), run.similarity().matrix());
        verify(judge, never()).same(any(), any(), any(), any(), any(), any());
    }
}
