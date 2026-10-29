package com.agenticai.core.quality.dataset.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agenticai.core.quality.dataset.dto.EvalDatasetDtos.CaseResponse;
import com.agenticai.core.quality.dataset.dto.EvalDatasetDtos.CaseResultResponse;
import com.agenticai.core.quality.dataset.dto.EvalDatasetDtos.DatasetDetailResponse;
import com.agenticai.core.quality.dataset.dto.EvalDatasetDtos.DatasetResponse;
import com.agenticai.core.quality.dataset.dto.EvalDatasetDtos.DimensionScore;
import com.agenticai.core.quality.dataset.dto.EvalDatasetDtos.ImportTurnCaseRequest;
import com.agenticai.core.quality.dataset.dto.EvalDatasetDtos.JudgeCalibrationInfo;
import com.agenticai.core.quality.dataset.dto.EvalDatasetDtos.ReplayedToolCallResponse;
import com.agenticai.core.quality.dataset.dto.EvalDatasetDtos.RunDetailResponse;
import com.agenticai.core.quality.dataset.dto.EvalDatasetDtos.RunResponse;
import com.agenticai.core.quality.dataset.dto.EvalDatasetDtos.TriggerRunRequest;
import com.agenticai.core.quality.dataset.dto.EvalDatasetDtos.UpsertCaseRequest;
import com.agenticai.core.quality.dataset.dto.EvalDatasetDtos.UpsertDatasetRequest;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The dataset REST surface is a bag of records whose only behaviour is the
 * back-compatible constructor overloads - every older request shape must keep
 * building with the new fields defaulted, never reinterpreting them.
 */
class EvalDatasetDtosTest {

    private static final UUID ID = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-10-01T00:00:00Z");

    @Test
    void theLegacyDatasetRequestShapesDefaultTheNewFields() {
        UpsertDatasetRequest oldest =
            new UpsertDatasetRequest("golden", "desc", 0.8, true);
        assertNull(oldest.autoApproveTools());
        assertNull(oldest.mode());

        UpsertDatasetRequest middle =
            new UpsertDatasetRequest("golden", "desc", 0.8, true, false);
        assertEquals(false, middle.autoApproveTools());
        assertNull(middle.mode(), "a request that predates named modes pins none");

        UpsertDatasetRequest current =
            new UpsertDatasetRequest("golden", "desc", 0.8, true, true, "FULL");
        assertEquals("FULL", current.mode());
        assertEquals("golden", current.name());
        assertEquals(0.8, current.passThreshold());
    }

    @Test
    void theLegacyRunTriggerShapeNamesNoMode() {
        TriggerRunRequest legacy =
            new TriggerRunRequest("judge-1", true, 3, BigDecimal.TEN);

        assertEquals("judge-1", legacy.judgeModel());
        assertEquals(true, legacy.strictJudge());
        assertEquals(3, legacy.repeats());
        assertEquals(0, BigDecimal.TEN.compareTo(legacy.budgetUsd()));
        assertNull(legacy.mode());

        TriggerRunRequest current =
            new TriggerRunRequest(null, null, null, null, "QUICK");
        assertEquals("QUICK", current.mode());
        assertNull(current.judgeModel());
    }

    @Test
    void caseAndImportPayloadsCarryTheirFieldsVerbatim() {
        UpsertCaseRequest caseRequest =
            new UpsertCaseRequest("hello", "hi there", List.of("search"), 2);
        assertEquals("hello", caseRequest.userMessage());
        assertEquals(List.of("search"), caseRequest.expectedTools());
        assertEquals(2, caseRequest.position());

        ImportTurnCaseRequest importRequest = new ImportTurnCaseRequest(
            "session-1", "turn-2", "q", "a", "expected", List.of("t"));
        assertEquals("session-1", importRequest.sessionId());
        assertEquals("turn-2", importRequest.turnRef());
        assertEquals("expected", importRequest.expectedOutput());
    }

    @Test
    void theDatasetResponseCarriesBothThePinAndTheEffectiveMode() {
        DatasetResponse dataset = new DatasetResponse(
            ID, UUID.randomUUID(), "golden", "desc", 0.7,
            null, false, "QUICK", "FULL", 12, "hash", "creator", NOW, NOW);

        assertEquals("QUICK", dataset.mode());
        assertEquals("FULL", dataset.effectiveMode());
        assertEquals(12, dataset.caseCount());
        assertEquals("hash", dataset.contentHash());

        DatasetDetailResponse detail = new DatasetDetailResponse(dataset, List.of());
        assertEquals(dataset, detail.dataset());
        assertTrue(detail.cases().isEmpty());
    }

    @Test
    void aCaseResponseRoundTripsItsSnapshot() {
        CaseResponse response = new CaseResponse(
            ID, "user msg", "expected", List.of("a"), 3, NOW, NOW);

        assertEquals(ID, response.id());
        assertEquals("user msg", response.userMessage());
        assertEquals(3, response.position());
        assertEquals(NOW, response.createdAt());
    }

    @Test
    void theRunResponseCarriesTheDiffFieldsAndCalibrationAsNullableHonesty() {
        RunResponse run = new RunResponse(
            ID, ID, ID, ID, "SUCCEEDED", "PASSED", 0.7, 0.9, 10, 9, 0, 1,
            "judge-1", "hash", null, false, "FULL", false, 1,
            BigDecimal.ONE, BigDecimal.ONE, null, "tester", false,
            NOW, NOW, NOW, null, null, null, null);

        assertNull(run.baselineRunId(), "no measured predecessor is absence, not zero");
        assertNull(run.baselineScore());
        assertNull(run.scoreDelta());
        assertNull(run.judgeCalibration(), "flag-off responses carry no calibration block");
        assertEquals("SUCCEEDED", run.status());
        assertEquals("PASSED", run.verdict());
        assertEquals(0.9, run.overallScore());
        assertEquals("FULL", run.mode());
    }

    @Test
    void theRunDetailDefaultsAnEmptySimulatedToolsList() {
        RunResponse run = new RunResponse(
            ID, ID, ID, ID, "RUNNING", null, 0.7, null, 10, 0, 0, 0,
            null, null, null, false, "QUICK", false, 1,
            BigDecimal.ONE, null, null, "tester", false,
            NOW, NOW, null, null, null, null, null);

        RunDetailResponse legacy = new RunDetailResponse(run, List.of());
        assertTrue(legacy.simulatedTools().isEmpty(),
            "the pre-simulation shape still builds with no tools reported");

        RunDetailResponse detail = new RunDetailResponse(run, List.of(), List.of("send_email"));
        assertEquals(List.of("send_email"), detail.simulatedTools());
    }

    @Test
    void toolCallAndScoreRecordsHoldTheirFields() {
        ReplayedToolCallResponse legacy = new ReplayedToolCallResponse("tool", "in", "out");
        assertNull(legacy.status(), "older results carry no recorded call status");

        ReplayedToolCallResponse current =
            new ReplayedToolCallResponse("tool", "in", "out", "SIMULATED");
        assertEquals("SIMULATED", current.status());

        DimensionScore score = new DimensionScore(0.8, "SCORED", "close enough");
        assertEquals(0.8, score.score());
        assertEquals("close enough", score.feedback());

        JudgeCalibrationInfo calibration = new JudgeCalibrationInfo("OK", 0.7, 40);
        assertEquals("OK", calibration.status());
        assertEquals(40, calibration.n());
    }

    @Test
    void theCaseResultResponseCarriesItsFullSnapshot() {
        CaseResultResponse result = new CaseResultResponse(
            ID, ID, 1, "q", "a", List.of("t"), "response",
            List.of(new ReplayedToolCallResponse("t", "i", "o")),
            Map.of("accuracy", new DimensionScore(0.9, "SCORED", null)),
            0.9, true, true, "SCORED", null, NOW, "trace-1", "https://trace");

        assertEquals(0.9, result.overallScore());
        assertEquals(true, result.expectedToolsSatisfied());
        assertEquals(true, result.passed());
        assertEquals("SCORED", result.status());
        assertEquals("trace-1", result.traceId());
        assertEquals("https://trace", result.langfuseTraceUrl());
        assertEquals(1, result.toolCalls().size());
        assertEquals(1, result.scores().size());
    }
}
