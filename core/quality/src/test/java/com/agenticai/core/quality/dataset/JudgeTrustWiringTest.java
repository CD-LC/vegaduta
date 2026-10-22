package com.agenticai.core.quality.dataset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.quality.calibration.JudgeCalibration.LabeledPair;
import com.agenticai.core.quality.calibration.JudgeCalibrationService;
import com.agenticai.core.quality.calibration.JudgeCalibrationService.CalibrationResponse;
import com.agenticai.core.quality.calibration.JudgeCalibrationSetService;
import com.agenticai.core.quality.calibration.JudgeCalibrationSetService.JudgeTrust;
import com.agenticai.core.quality.dataset.dto.EvalDatasetDtos.RunResponse;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.TenantLimits;
import com.agenticai.core.security.TenantPlan;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Wave E wiring: the calibration-set run dispatch on {@code EvalCredibilityController}, and the
 * additive {@code judgeCalibration} stamp on run responses and bundles. The point of most tests is
 * the FLAG-OFF guarantee: with the feature off, nothing about a run response or a bundle changes.
 */
class JudgeTrustWiringTest {

    private static final UUID AGENT = UUID.randomUUID();
    private static final UUID DATASET = UUID.randomUUID();
    private static final UUID SET = UUID.randomUUID();

    private EvalDatasetService datasetService;
    private EvalRunService runService;
    private JudgeCalibrationService calibration;
    private JudgeCalibrationSetService setService;
    private TenantLimits limits;

    @BeforeEach
    void setUp() {
        TenantContext.set(TenantContext.of("tenant-1", "tester", Set.of()));
        datasetService = mock(EvalDatasetService.class);
        runService = mock(EvalRunService.class);
        calibration = mock(JudgeCalibrationService.class);
        setService = mock(JudgeCalibrationSetService.class);
        limits = mock(TenantLimits.class);
        when(limits.plan("tenant-1")).thenReturn(TenantPlan.PREMIUM);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private EvalCredibilityController credibility() {
        return new EvalCredibilityController(
                datasetService,
                runService,
                mock(ResearchSeedService.class),
                calibration,
                setService,
                limits);
    }

    private static EvalRun runWithJudge(String judgeModel) {
        EvalRun run = new EvalRun(DATASET, AGENT, "tenant-1", "user", "ROLE_USER", 0.7, 1);
        if (judgeModel != null) {
            run.markRunning(judgeModel);
        }
        return run;
    }

    // ---- calibrate dispatch -------------------------------------------

    @Test
    void inlineLabelsStillUseTheStatelessPathAndNeverTouchTheSetService() {
        List<LabeledPair> pairs = List.of(new LabeledPair("p", "q", "a", "b", "A"));
        CalibrationResponse expected = new CalibrationResponse("J", "A", null, null, "m");
        when(calibration.calibrate(AGENT, pairs)).thenReturn(expected);

        CalibrationResponse body =
                credibility()
                        .calibrate(
                                AGENT,
                                new EvalCredibilityController.CalibrationRequest(pairs, null))
                        .getBody();

        assertEquals(expected, body);
        verify(setService, never()).calibrateSet(any(), any());
    }

    @Test
    void aCalibrationSetIdRunsTheStoredSet() {
        CalibrationResponse recorded =
                new CalibrationResponse("J", "A", null, null, "m")
                        .withRecord(SET, UUID.randomUUID(), java.time.Instant.now());
        when(setService.calibrateSet(AGENT, SET)).thenReturn(recorded);

        CalibrationResponse body =
                credibility()
                        .calibrate(
                                AGENT, new EvalCredibilityController.CalibrationRequest(null, SET))
                        .getBody();

        assertEquals(recorded, body);
        verify(calibration, never()).calibrate(any(), any());
    }

    @Test
    void anEmptyExamplesListAlongsideASetIdIsAccepted() {
        when(setService.calibrateSet(AGENT, SET))
                .thenReturn(new CalibrationResponse("J", "A", null, null, "m"));
        assertNotNull(
                credibility()
                        .calibrate(
                                AGENT,
                                new EvalCredibilityController.CalibrationRequest(List.of(), SET))
                        .getBody());
    }

    @Test
    void sendingBothInlineExamplesAndASetIsA400() {
        IllegalArgumentException e =
                assertThrows(
                        IllegalArgumentException.class,
                        () ->
                                credibility()
                                        .calibrate(
                                                AGENT,
                                                new EvalCredibilityController.CalibrationRequest(
                                                        List.of(
                                                                new LabeledPair(
                                                                        "p", "q", "a", "b", "A")),
                                                        SET)));
        assertTrue(e.getMessage().contains("not both"));
        verify(setService, never()).calibrateSet(any(), any());
        verify(calibration, never()).calibrate(any(), any());
    }

    @Test
    void theInlineResponseJsonIsUnchangedByTheNewOptionalFields() throws Exception {
        ObjectMapper mapper = com.agenticai.core.json.JsonMappers.withJavaTime();
        JsonNode inline = mapper.valueToTree(new CalibrationResponse("J", "A", null, null, "m"));
        assertFalse(inline.has("calibrationSetId"));
        assertFalse(inline.has("resultId"));
        assertFalse(inline.has("recordedAt"));
        assertTrue(inline.has("judgeModel"));

        JsonNode stored =
                mapper.valueToTree(
                        new CalibrationResponse("J", "A", null, null, "m")
                                .withRecord(
                                        SET,
                                        UUID.randomUUID(),
                                        java.time.Instant.parse("2026-10-05T00:00:00Z")));
        assertEquals(SET.toString(), stored.get("calibrationSetId").asString());
        assertTrue(stored.has("resultId"));
        assertTrue(stored.has("recordedAt"));
    }

    // ---- run response stamp -------------------------------------------

    private EvalDatasetController datasetController(JudgeCalibrationSetService trust) {
        EvalDatasetController controller =
                new EvalDatasetController(datasetService, runService, limits, false);
        controller.setJudgeTrust(trust);
        return controller;
    }

    private RunResponse listedRun(EvalDatasetController controller, EvalRun run) {
        when(runService.listRuns(DATASET, 25)).thenReturn(List.of(run));
        List<RunResponse> runs = controller.listRuns(AGENT, DATASET, 25).getBody();
        assertNotNull(runs);
        return runs.get(0);
    }

    @Test
    void flagOffRunResponsesCarryNoJudgeCalibrationAtAll() throws Exception {
        // The service is present but the feature is off: trustFor answers empty.
        when(setService.trustFor("OPENAI/judge")).thenReturn(Optional.empty());
        RunResponse withService =
                listedRun(datasetController(setService), runWithJudge("OPENAI/judge"));
        RunResponse withoutService =
                listedRun(datasetController(null), runWithJudge("OPENAI/judge"));

        assertNull(withService.judgeCalibration());
        assertNull(withoutService.judgeCalibration());
        JsonNode json = com.agenticai.core.json.JsonMappers.withJavaTime().valueToTree(withService);
        assertFalse(
                json.has("judgeCalibration"),
                "absent, not null, so the flag-off JSON is unchanged");
    }

    @Test
    void aRunThatUsedNoJudgeIsNotStamped() {
        // Queued run: judgeModel is still null.
        RunResponse r = listedRun(datasetController(setService), runWithJudge(null));
        assertNull(r.judgeCalibration());
        verify(setService).trustFor(null);
    }

    @Test
    void anUncalibratedJudgeIsStamped() throws Exception {
        when(setService.trustFor("OPENAI/judge"))
                .thenReturn(Optional.of(new JudgeTrust("UNCALIBRATED", null, null)));

        RunResponse r = listedRun(datasetController(setService), runWithJudge("OPENAI/judge"));

        assertEquals("UNCALIBRATED", r.judgeCalibration().status());
        assertNull(r.judgeCalibration().kappa());
        JsonNode json = com.agenticai.core.json.JsonMappers.withJavaTime().valueToTree(r);
        assertEquals("UNCALIBRATED", json.get("judgeCalibration").get("status").asString());
    }

    @Test
    void aLowAndAnOkJudgeCarryKappaAndN() {
        when(setService.trustFor("OPENAI/low"))
                .thenReturn(Optional.of(new JudgeTrust("LOW", 0.31, 24)));
        when(setService.trustFor("OPENAI/ok"))
                .thenReturn(Optional.of(new JudgeTrust("OK", 0.82, 30)));

        RunResponse low = listedRun(datasetController(setService), runWithJudge("OPENAI/low"));
        RunResponse ok = listedRun(datasetController(setService), runWithJudge("OPENAI/ok"));

        assertEquals("LOW", low.judgeCalibration().status());
        assertEquals(0.31, low.judgeCalibration().kappa());
        assertEquals(24, low.judgeCalibration().n());
        assertEquals("OK", ok.judgeCalibration().status());
        assertEquals(0.82, ok.judgeCalibration().kappa());
    }

    @Test
    void theStampNeverChangesPassFailFields() {
        when(setService.trustFor("OPENAI/judge"))
                .thenReturn(Optional.of(new JudgeTrust("LOW", 0.1, 20)));
        EvalRun run = runWithJudge("OPENAI/judge");
        RunResponse stamped = listedRun(datasetController(setService), run);
        RunResponse plain = listedRun(datasetController(null), run);

        assertEquals(plain.verdict(), stamped.verdict());
        assertEquals(plain.status(), stamped.status());
        assertEquals(plain.overallScore(), stamped.overallScore());
        assertEquals(plain.passThreshold(), stamped.passThreshold());
    }

    // ---- bundle --------------------------------------------------------

    private static EvalDataset dataset() {
        return new EvalDataset(AGENT, "ds", null, 0.7, false, "u");
    }

    @Test
    void theBundleOmitsJudgeCalibrationWhenThereIsNone() {
        EvalRunBundle.Bundle bundle =
                EvalRunBundle.build(dataset(), List.of(), runWithJudge("OPENAI/judge"), List.of());
        assertFalse(bundle.live().containsKey("judgeCalibration"));
        assertFalse(bundle.content().toString().contains("judgeCalibration"));
    }

    @Test
    void theBundleCarriesJudgeCalibrationInTheUnhashedLiveSectionSoTheDigestIsStable() {
        EvalDataset dataset = dataset();
        EvalRun run = runWithJudge("OPENAI/judge");
        EvalRunBundle.Bundle plain = EvalRunBundle.build(dataset, List.of(), run, List.of());
        EvalRunBundle.Bundle stamped =
                EvalRunBundle.build(
                        dataset, List.of(), run, List.of(), new JudgeTrust("LOW", 0.42, 22));

        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> trust =
                (java.util.Map<String, Object>) stamped.live().get("judgeCalibration");
        assertEquals("LOW", trust.get("status"));
        assertEquals(0.42, trust.get("kappa"));
        assertEquals(22, trust.get("n"));
        assertEquals(
                plain.sha256(),
                stamped.sha256(),
                "calibration status is mutable and must not move the digest");
        assertEquals(plain.content(), stamped.content());
    }

    @Test
    void runBundleEndpointStampsTheTrustFromTheRunsJudgeModel() {
        EvalDataset dataset = dataset();
        EvalRun run = runWithJudge("OPENAI/judge");
        UUID runId = UUID.randomUUID();
        when(datasetService.requireDataset(AGENT, DATASET)).thenReturn(dataset);
        when(datasetService.listCases(DATASET)).thenReturn(List.of());
        when(runService.requireRun(DATASET, runId)).thenReturn(run);
        when(runService.listCaseResults(runId)).thenReturn(List.of());
        when(setService.trustFor("OPENAI/judge"))
                .thenReturn(Optional.of(new JudgeTrust("OK", 0.9, 30)));

        EvalRunBundle.Bundle bundle = credibility().runBundle(AGENT, DATASET, runId).getBody();

        assertNotNull(bundle);
        assertTrue(bundle.live().containsKey("judgeCalibration"));

        // Feature off: trustFor is empty, the bundle is exactly the old one.
        when(setService.trustFor("OPENAI/judge")).thenReturn(Optional.empty());
        EvalRunBundle.Bundle off = credibility().runBundle(AGENT, DATASET, runId).getBody();
        assertNotNull(off);
        assertFalse(off.live().containsKey("judgeCalibration"));
    }
}
