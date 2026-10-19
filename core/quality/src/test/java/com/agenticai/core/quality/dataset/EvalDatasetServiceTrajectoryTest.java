package com.agenticai.core.quality.dataset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationContext;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * A trajectory spec shares the case's {@code grader_spec} column. Flag off: {@code setCaseGrader} answers exactly
 * as it always did for an unknown grader type. Flag on: the spec is fully validated at write time.
 */
class EvalDatasetServiceTrajectoryTest {

    private static final UUID AGENT_ID = UUID.randomUUID();
    private static final UUID DATASET_ID = UUID.randomUUID();
    private static final UUID CASE_ID = UUID.randomUUID();
    private static final String SPEC = "{\"type\":\"trajectory\",\"mode\":\"in_order\",\"steps\":[{\"tool\":\"search\"}]}";

    private EvalDatasetCaseRepository cases;
    private EvalDatasetCase datasetCase;
    private EvalDatasetService service;

    @BeforeEach
    void setUp() {
        EvalDatasetRepository datasets = mock(EvalDatasetRepository.class);
        cases = mock(EvalDatasetCaseRepository.class);
        EvalDataset dataset = new EvalDataset(AGENT_ID, "d", null, 0.7, true, "u");
        ReflectionTestUtils.setField(dataset, "id", DATASET_ID);
        datasetCase = new EvalDatasetCase(DATASET_ID, "q", "a", null, 0);
        ReflectionTestUtils.setField(datasetCase, "id", CASE_ID);
        when(datasets.findById(DATASET_ID)).thenReturn(Optional.of(dataset));
        when(cases.findById(CASE_ID)).thenReturn(Optional.of(datasetCase));
        when(cases.save(any(EvalDatasetCase.class))).thenAnswer(inv -> inv.getArgument(0));
        @SuppressWarnings("unchecked")
        ObjectProvider<com.agenticai.core.chat.LlmCallLogService> logs = mock(ObjectProvider.class);
        service = new EvalDatasetService(
            datasets, cases, mock(EvalRunRepository.class), logs, mock(ApplicationContext.class), "", "", "", "");
    }

    @Test
    void withTheFlagOffATrajectorySpecIsRejectedLikeAnyUnknownGraderType() {
        service.trajectoryEvalEnabled = false;

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
            () -> service.setCaseGrader(AGENT_ID, DATASET_ID, CASE_ID, SPEC));

        assertEquals("Unknown grader type 'trajectory' - use regex, exact or json_schema.", e.getMessage());
        verify(cases, never()).save(any(EvalDatasetCase.class));
    }

    @Test
    void withTheFlagOnAValidTrajectorySpecIsStored() {
        service.trajectoryEvalEnabled = true;

        EvalDatasetCase saved = service.setCaseGrader(AGENT_ID, DATASET_ID, CASE_ID, SPEC);

        assertEquals(SPEC, saved.getGraderSpec());
    }

    @Test
    void withTheFlagOnAnInvalidTrajectorySpecIsRejectedWithAPreciseReason() {
        service.trajectoryEvalEnabled = true;

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () ->
            service.setCaseGrader(AGENT_ID, DATASET_ID, CASE_ID,
                "{\"type\":\"trajectory\",\"mode\":\"sideways\",\"steps\":[]}"));

        assertTrue(e.getMessage().contains("'mode'"), e.getMessage());
        verify(cases, never()).save(any(EvalDatasetCase.class));
    }

    @Test
    void codeGradersAreUnaffectedByTheFlag() {
        service.trajectoryEvalEnabled = true;
        String exact = "{\"type\":\"exact\",\"value\":\"42\"}";
        assertEquals(exact, service.setCaseGrader(AGENT_ID, DATASET_ID, CASE_ID, exact).getGraderSpec());
        assertThrows(IllegalArgumentException.class,
            () -> service.setCaseGrader(AGENT_ID, DATASET_ID, CASE_ID, "{\"type\":\"nope\"}"));
    }
}
