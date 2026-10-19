package com.agenticai.core.agents.lab;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import com.agenticai.core.agents.lab.AgentLabDtos.LabCellResult;
import com.agenticai.core.agents.lab.AgentLabDtos.LabRunResponse;
import com.agenticai.core.chat.UpstreamProviderException;
import com.agenticai.core.security.TenantLimitExceededException;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** {@link AgentLabRunService}: runs that stop before every cell has run. */
class AgentLabRunServiceStoppingEarlyTest extends AgentLabRunServiceTestSupport {

    // ---- stopping early ---------------------------------------------------------

    @Test
    void cancelBetweenCallsStopsTheRunAndKeepsWhatWasAnswered() {
        AtomicInteger calls = new AtomicInteger();
        when(completion.complete(any())).thenAnswer(call -> {
            if (calls.incrementAndGet() == 2) {
                // The user presses Cancel while the second call is in flight.
                service.cancel(agentId, store.only().run().runId(), USER);
            }
            return "ok";
        });

        LabRunResponse run = startAndRead(consistency(4, "JUDGE"));

        assertEquals("CANCELLED", run.status());
        assertTrue(run.cancelRequested());
        assertNull(run.errorMessage());
        assertNotNull(run.finishedAtMs());
        assertEquals(2, run.completedCalls());
        assertEquals(List.of("DONE", "DONE", "PENDING", "PENDING"),
            run.cells().stream().map(LabCellResult::status).toList());
        // The estimate stays: only a SUCCEEDED run rewrites it.
        assertEquals(10, run.plannedCalls());
        assertEquals("PENDING", run.similarity().status());
        verify(completion, times(2)).complete(any());
        verify(judge, never()).same(any(), any(), any(), any(), any(), any());
        assertTrue(store.locks.isEmpty());
    }

    @Test
    void cancelDuringTheComparisonsLeavesAPartialMatrix() {
        AtomicInteger comparisons = new AtomicInteger();
        when(judge.same(any(), any(), any(), any(), any(), any())).thenAnswer(call -> {
            if (comparisons.incrementAndGet() == 1) {
                service.cancel(agentId, store.only().run().runId(), USER);
            }
            return Boolean.TRUE;
        });

        LabRunResponse run = startAndRead(consistency(3, "JUDGE"));

        assertEquals("CANCELLED", run.status());
        assertEquals("PARTIAL", run.similarity().status());
        assertEquals(1.0, run.similarity().matrix().get(0).get(1), 0.0);
        assertNull(run.similarity().matrix().get(0).get(2));
        assertNull(run.similarity().matrix().get(1).get(2));
    }

    @Test
    void theDeadlineFailsTheRunWithTheTimeLimitSentenceAndKeepsPartialCells() {
        when(completion.complete(any())).thenAnswer(call -> {
            now.addAndGet(TimeUnit.MINUTES.toMillis(11));
            return "slow answer";
        });

        LabRunResponse run = startAndRead(consistency(4, "NONE"));

        assertEquals("FAILED", run.status());
        assertEquals("The 20-minute time limit was reached. Results so far are kept.", run.errorMessage());
        assertEquals(List.of("DONE", "DONE", "PENDING", "PENDING"),
            run.cells().stream().map(LabCellResult::status).toList());
        assertEquals(2, run.completedCalls());
        assertEquals(Long.valueOf(TimeUnit.MINUTES.toMillis(11)), run.cells().get(0).ms());
        verify(completion, times(2)).complete(any());
        assertTrue(store.locks.isEmpty());
    }

    @Test
    void aLimitFailureFailsTheRunAndMakesNoFurtherCalls() {
        AtomicInteger calls = new AtomicInteger();
        when(completion.complete(any())).thenAnswer(call -> {
            if (calls.incrementAndGet() == 2) {
                throw new TenantLimitExceededException("You have used this month's hosted model allowance");
            }
            return "ok";
        });

        LabRunResponse run = startAndRead(knockout(List.of("p"), List.of(
            cell("b0:i0", 0), cell("b0:i1", 0), cell("b0:i2", 0))));

        assertEquals("FAILED", run.status());
        // errorMessage is always a full sentence on the wire.
        assertEquals("You have used this month's hosted model allowance.", run.errorMessage());
        assertEquals("DONE", run.cells().get(0).status());
        assertEquals("PENDING", run.cells().get(1).status());
        assertEquals("PENDING", run.cells().get(2).status());
        // cell 0, its judge call, then the rejected cell 1
        assertEquals(3, run.completedCalls());
        verify(completion, times(2)).complete(any());
        verify(judge, times(1)).score(any(), any(), any(), any(), any(), any());
        assertTrue(store.locks.isEmpty());
    }

    @Test
    void aLimitFailureInTheJudgeFailsTheRunToo() {
        when(judge.score(any(), any(), any(), any(), any(), any()))
            .thenThrow(new TenantLimitExceededException("Budget exhausted."));

        LabRunResponse run = startAndRead(knockout(List.of("p"), List.of(cell("b0:i0", 0), cell("b0:i1", 0))));

        assertEquals("FAILED", run.status());
        assertEquals("Budget exhausted.", run.errorMessage());
        assertEquals("DONE", run.cells().get(0).status());
        assertEquals("PENDING", run.cells().get(0).scoreStatus());
        verify(completion, times(1)).complete(any());
    }

    @Test
    void threeFailedCallsInARowFailTheRun() {
        when(completion.complete(any()))
            .thenReturn("ok")
            .thenThrow(new UpstreamProviderException(502, "bad gateway", "The model did not answer"));

        LabRunResponse run = startAndRead(consistency(6, "NONE"));

        assertEquals("FAILED", run.status());
        assertEquals("Three calls in a row failed. The last error: The model did not answer.", run.errorMessage());
        assertEquals(List.of("DONE", "ERROR", "ERROR", "ERROR", "PENDING", "PENDING"),
            run.cells().stream().map(LabCellResult::status).toList());
        assertEquals(4, run.completedCalls());
        verify(completion, times(4)).complete(any());
    }

    @Test
    void anAnswerBetweenFailuresResetsTheStreak() {
        AtomicInteger calls = new AtomicInteger();
        when(completion.complete(any())).thenAnswer(call -> {
            int n = calls.incrementAndGet();
            if (n == 3) {
                return "ok";
            }
            throw new UpstreamProviderException(502, "bad gateway", "Try again.");
        });

        LabRunResponse run = startAndRead(consistency(5, "NONE"));

        assertEquals("SUCCEEDED", run.status());
        assertEquals(List.of("ERROR", "ERROR", "DONE", "ERROR", "ERROR"),
            run.cells().stream().map(LabCellResult::status).toList());
    }

    @Test
    void anUnexpectedExceptionInACellShowsAPlainSentenceNotItsInternals() {
        when(completion.complete(any())).thenThrow(new NullPointerException("Cannot invoke \"Foo.bar()\""));

        LabRunResponse run = startAndRead(consistency(1, "NONE"));

        assertEquals("SUCCEEDED", run.status());
        assertEquals("ERROR", run.cells().get(0).status());
        assertEquals("The model call failed. Try again.", run.cells().get(0).errorMessage());
    }

    @Test
    void aCellErrorMessageIsClampedToThreeHundredCharacters() {
        when(completion.complete(any()))
            .thenThrow(new UpstreamProviderException(502, "bad gateway", "x".repeat(900)));

        LabRunResponse run = startAndRead(consistency(1, "NONE"));

        assertEquals(300, run.cells().get(0).errorMessage().length());
    }

    @Test
    void aTenantStoppedMidRunFailsTheRunBeforeTheNextCall() {
        when(statusGuard.isUsable(TENANT)).thenReturn(true).thenReturn(false);

        LabRunResponse run = startAndRead(consistency(3, "NONE"));

        assertEquals("FAILED", run.status());
        assertEquals(AgentLabRunService.TENANT_STOPPED_MESSAGE, run.errorMessage());
        verify(completion, times(1)).complete(any());
    }
}
