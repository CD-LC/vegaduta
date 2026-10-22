package com.agenticai.core.quality.trajectory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.quality.dataset.EvalCaseResultStatus;
import com.agenticai.core.quality.dataset.EvalDataset;
import com.agenticai.core.quality.dataset.EvalDatasetCase;
import com.agenticai.core.quality.dataset.EvalDatasetNotFoundException;
import com.agenticai.core.quality.dataset.EvalDatasetService;
import com.agenticai.core.quality.dataset.EvalRun;
import com.agenticai.core.quality.dataset.EvalRunCaseResult;
import com.agenticai.core.quality.dataset.EvalRunService;
import com.agenticai.core.quality.trajectory.TrajectoryEvalDtos.ActualTrajectory;
import com.agenticai.core.quality.trajectory.TrajectoryEvalDtos.CaseTrajectory;
import com.agenticai.core.quality.trajectory.TrajectoryEvalDtos.RunTrajectories;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class TrajectoryEvalServiceTest {

    private static final ObjectMapper MAPPER = com.agenticai.core.json.JsonMappers.plain();
    private static final UUID AGENT_ID = UUID.randomUUID();
    private static final UUID DATASET_ID = UUID.randomUUID();
    private static final UUID CASE_ID = UUID.randomUUID();
    private static final UUID RUN_ID = UUID.randomUUID();

    private EvalDatasetService datasetService;
    private EvalRunService runService;
    private EvalDataset dataset;
    private EvalDatasetCase datasetCase;
    private final EvalRun run = mock(EvalRun.class);

    @BeforeEach
    void setUp() {
        datasetService = mock(EvalDatasetService.class);
        runService = mock(EvalRunService.class);
        dataset = mock(EvalDataset.class);
        when(dataset.getContentHash()).thenReturn("hash-1");
        when(datasetService.requireDataset(AGENT_ID, DATASET_ID)).thenReturn(dataset);
        datasetCase = new EvalDatasetCase(DATASET_ID, "refund my order", null, null, 0);
        ReflectionTestUtils.setField(datasetCase, "id", CASE_ID);
        when(datasetService.listCases(DATASET_ID)).thenReturn(List.of(datasetCase));
    }

    private TrajectoryEvalService service(boolean enabled) {
        return new TrajectoryEvalService(datasetService, runService, enabled);
    }

    private static JsonNode json(String s) throws Exception {
        return MAPPER.readTree(s);
    }

    // ------------------------------------------------------------------ flag

    @Test
    void withTheFlagOffConfigSaysSoAndEverythingElseIs404() throws Exception {
        TrajectoryEvalService off = service(false);
        assertFalse(off.config().enabled());
        assertTrue(off.config().modes().contains("in_order"));

        for (Runnable call :
                new Runnable[] {
                    () -> off.getCase(AGENT_ID, DATASET_ID, CASE_ID),
                    () -> {
                        try {
                            off.setCase(AGENT_ID, DATASET_ID, CASE_ID, json("{\"steps\":[]}"));
                        } catch (ResponseStatusException e) {
                            throw e;
                        } catch (Exception e) {
                            throw new IllegalStateException(e);
                        }
                    },
                    () -> off.runTrajectories(AGENT_ID, DATASET_ID, RUN_ID),
                    () -> off.actual(AGENT_ID, DATASET_ID, RUN_ID, UUID.randomUUID())
                }) {
            ResponseStatusException e = assertThrows(ResponseStatusException.class, call::run);
            assertEquals(HttpStatus.NOT_FOUND, e.getStatusCode());
        }
        verify(datasetService, never()).setCaseGrader(any(), any(), any(), any());
    }

    // ------------------------------------------------------------------ the case's expected
    // trajectory

    @Test
    void getReportsNoTrajectoryForAPlainCase() {
        CaseTrajectory c = service(true).getCase(AGENT_ID, DATASET_ID, CASE_ID);
        assertNull(c.trajectory());
        assertFalse(c.hasOtherGrader());
        assertEquals("hash-1", c.datasetContentHash());
    }

    @Test
    void getReportsTheStoredTrajectoryAndFlagsAnotherKindOfGrader() {
        datasetCase.applyGraderSpec(
                "{\"type\":\"trajectory\",\"mode\":\"exact\",\"minScore\":1.0,\"steps\":[{\"tool\":\"a\"}]}");
        CaseTrajectory c = service(true).getCase(AGENT_ID, DATASET_ID, CASE_ID);
        assertEquals("exact", c.trajectory().get("mode").asString());
        assertFalse(c.hasOtherGrader());

        datasetCase.applyGraderSpec("{\"type\":\"exact\",\"value\":\"42\"}");
        CaseTrajectory other = service(true).getCase(AGENT_ID, DATASET_ID, CASE_ID);
        assertNull(other.trajectory());
        assertTrue(other.hasOtherGrader());
    }

    @Test
    void anUnknownCaseIs404() {
        assertThrows(
                EvalDatasetNotFoundException.class,
                () -> service(true).getCase(AGENT_ID, DATASET_ID, UUID.randomUUID()));
    }

    @Test
    void setValidatesAddsTheTypeAndStoresTheCanonicalSpec() throws Exception {
        when(datasetService.setCaseGrader(eq(AGENT_ID), eq(DATASET_ID), eq(CASE_ID), anyString()))
                .thenAnswer(
                        inv -> {
                            datasetCase.applyGraderSpec(inv.getArgument(3));
                            return datasetCase;
                        });

        CaseTrajectory saved =
                service(true)
                        .setCase(
                                AGENT_ID,
                                DATASET_ID,
                                CASE_ID,
                                json(
                                        "{\"mode\":\"any_order\",\"steps\":[{\"tool\":\"search\",\"args\":{\"q\":{\"regex\":\"x\"}}}]}"));

        org.mockito.ArgumentCaptor<String> spec = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(datasetService)
                .setCaseGrader(eq(AGENT_ID), eq(DATASET_ID), eq(CASE_ID), spec.capture());
        assertTrue(spec.getValue().contains("\"type\":\"trajectory\""), spec.getValue());
        assertTrue(
                spec.getValue().contains("\"minScore\":1.0"),
                "defaults are made explicit: " + spec.getValue());
        assertEquals("any_order", saved.trajectory().get("mode").asString());
    }

    @Test
    void setRejectsAnInvalidSpecWithoutWriting() throws Exception {
        IllegalArgumentException bad =
                assertThrows(
                        IllegalArgumentException.class,
                        () ->
                                service(true)
                                        .setCase(
                                                AGENT_ID,
                                                DATASET_ID,
                                                CASE_ID,
                                                json("{\"mode\":\"sideways\",\"steps\":[]}")));
        assertTrue(bad.getMessage().contains("'mode'"), bad.getMessage());
        assertThrows(
                IllegalArgumentException.class,
                () -> service(true).setCase(AGENT_ID, DATASET_ID, CASE_ID, json("[1]")));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        service(true)
                                .setCase(
                                        AGENT_ID,
                                        DATASET_ID,
                                        CASE_ID,
                                        json("{\"type\":\"regex\",\"steps\":[]}")));
        verify(datasetService, never()).setCaseGrader(any(), any(), any(), any());
    }

    @Test
    void setRefusesToReplaceAResponseGrader() throws Exception {
        datasetCase.applyGraderSpec("{\"type\":\"exact\",\"value\":\"42\"}");
        ResponseStatusException e =
                assertThrows(
                        ResponseStatusException.class,
                        () ->
                                service(true)
                                        .setCase(
                                                AGENT_ID,
                                                DATASET_ID,
                                                CASE_ID,
                                                json("{\"steps\":[]}")));
        assertEquals(HttpStatus.CONFLICT, e.getStatusCode());
        verify(datasetService, never()).setCaseGrader(any(), any(), any(), any());
    }

    @Test
    void clearingRemovesOnlyATrajectoryNeverAResponseGrader() {
        datasetCase.applyGraderSpec("{\"type\":\"exact\",\"value\":\"42\"}");
        CaseTrajectory untouched = service(true).setCase(AGENT_ID, DATASET_ID, CASE_ID, null);
        assertTrue(untouched.hasOtherGrader());
        verify(datasetService, never()).setCaseGrader(any(), any(), any(), any());

        datasetCase.applyGraderSpec("{\"type\":\"trajectory\",\"steps\":[]}");
        when(datasetService.setCaseGrader(AGENT_ID, DATASET_ID, CASE_ID, null))
                .thenAnswer(
                        inv -> {
                            datasetCase.applyGraderSpec(null);
                            return datasetCase;
                        });
        CaseTrajectory cleared =
                service(true).setCase(AGENT_ID, DATASET_ID, CASE_ID, MAPPER.nullNode());
        assertNull(cleared.trajectory());
        verify(datasetService).setCaseGrader(AGENT_ID, DATASET_ID, CASE_ID, null);
    }

    // ------------------------------------------------------------------ views of a run

    private static EvalRunCaseResult result(
            UUID id, int position, String scoresJson, String toolCallsJson) {
        EvalRunCaseResult r = mock(EvalRunCaseResult.class);
        when(r.getId()).thenReturn(id);
        when(r.getCaseId()).thenReturn(CASE_ID);
        when(r.getPosition()).thenReturn(position);
        when(r.getUserMessage()).thenReturn("case " + position);
        when(r.getStatus()).thenReturn(EvalCaseResultStatus.SCORED);
        when(r.getPassed()).thenReturn(Boolean.FALSE);
        when(r.getOverallScore()).thenReturn(0.75);
        when(r.getScoresJson()).thenReturn(scoresJson);
        when(r.getToolCallsJson()).thenReturn(toolCallsJson);
        return r;
    }

    @Test
    void runTrajectoriesListsOnlyTrajectoryGradedCasesWithTheirStoredReport() {
        UUID graded = UUID.randomUUID();
        UUID plain = UUID.randomUUID();
        String withTrajectory =
                "{\"ACCURACY\":{\"score\":0.9},\"TRAJECTORY\":{\"score\":0.5,\"status\":\"EVALUATED\","
                        + "\"report\":{\"mode\":\"in_order\",\"passed\":false,\"expected\":[],\"actual\":[]}}}";
        EvalRunCaseResult a = result(graded, 0, withTrajectory, null);
        EvalRunCaseResult b = result(plain, 1, "{\"ACCURACY\":{\"score\":0.9}}", null);
        when(runService.requireRun(DATASET_ID, RUN_ID)).thenReturn(run);
        when(runService.listCaseResults(RUN_ID)).thenReturn(List.of(a, b));

        RunTrajectories view = service(true).runTrajectories(AGENT_ID, DATASET_ID, RUN_ID);

        assertEquals(1, view.cases().size());
        assertEquals(graded, view.cases().get(0).resultId());
        assertEquals(0.5, view.cases().get(0).trajectoryScore(), 1e-9);
        assertEquals(0.75, view.cases().get(0).overallScore(), 1e-9);
        assertEquals("in_order", view.cases().get(0).report().get("mode").asString());
        assertEquals("SCORED", view.cases().get(0).status());
    }

    @Test
    void actualExtractsTheRecordedTrajectoryAndSuggestsASpecFromItsToolNames() {
        UUID id = UUID.randomUUID();
        String calls =
                "[{\"toolName\":\"search\",\"input\":\"{\\\"q\\\":\\\"x\\\"}\",\"output\":\"hits\"},"
                        + "{\"toolName\":\"send\",\"input\":\"nope\",\"output\":\"This tool call ('send') was not executed: no\"}]";
        when(runService.requireRun(DATASET_ID, RUN_ID)).thenReturn(run);
        List<EvalRunCaseResult> stored = List.of(result(id, 0, "{}", calls));
        when(runService.listCaseResults(RUN_ID)).thenReturn(stored);

        ActualTrajectory actual = service(true).actual(AGENT_ID, DATASET_ID, RUN_ID, id);

        assertEquals(2, actual.steps().size());
        assertEquals("search", actual.steps().get(0).tool());
        assertEquals("x", actual.steps().get(0).args().get("q").asString());
        assertTrue(actual.steps().get(0).ok());
        assertNull(actual.steps().get(1).args());
        assertFalse(actual.steps().get(1).ok());
        // The suggestion is a valid spec of the tool names, ready to save.
        TrajectorySpec suggested = TrajectorySpec.parse(actual.suggestedSpec());
        assertEquals(2, suggested.stepCount());
        assertNotNull(actual.suggestedSpec().get("steps").get(1).get("tool"));
    }

    @Test
    void anUnknownResultIs404() {
        when(runService.requireRun(DATASET_ID, RUN_ID)).thenReturn(run);
        when(runService.listCaseResults(RUN_ID)).thenReturn(List.of());
        ResponseStatusException e =
                assertThrows(
                        ResponseStatusException.class,
                        () ->
                                service(true)
                                        .actual(AGENT_ID, DATASET_ID, RUN_ID, UUID.randomUUID()));
        assertEquals(HttpStatus.NOT_FOUND, e.getStatusCode());
    }
}
