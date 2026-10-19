package com.agenticai.core.quality.dataset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.quality.calibration.JudgeCalibration.LabeledPair;
import com.agenticai.core.quality.calibration.JudgeCalibrationService;
import com.agenticai.core.quality.calibration.JudgeCalibrationSetService;
import com.agenticai.core.quality.impl.JudgeChatClientFactory;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.TenantLimitExceededException;
import com.agenticai.core.security.TenantLimits;
import com.agenticai.core.security.TenantPlan;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationContext;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

/**
 * The V375 eval-credibility services and controller with collaborators
 * mocked: grader write validation, the research seed flow, the plan gate and
 * the calibration enabled gate / request validation.
 */
class EvalCredibilityServicesTest {

    private static final UUID AGENT_ID = UUID.randomUUID();
    private static final UUID DATASET_ID = UUID.randomUUID();
    private static final UUID CASE_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        TenantContext.set(TenantContext.of("tenant-1", "tester", Set.of()));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    // ---- setCaseGrader ------------------------------------------------

    private EvalDatasetService realDatasetService(
        EvalDatasetRepository datasets, EvalDatasetCaseRepository cases
    ) {
        @SuppressWarnings("unchecked")
        ObjectProvider<com.agenticai.core.chat.LlmCallLogService> logs = mock(ObjectProvider.class);
        return new EvalDatasetService(
            datasets, cases, mock(EvalRunRepository.class), logs, mock(ApplicationContext.class),
            "", "", "", "");
    }

    @Test
    void setCaseGraderRejectsABadSpecAndSavesNothing() {
        EvalDatasetRepository datasets = mock(EvalDatasetRepository.class);
        EvalDatasetCaseRepository cases = mock(EvalDatasetCaseRepository.class);
        EvalDataset dataset = new EvalDataset(AGENT_ID, "d", null, 0.7, false, "u");
        ReflectionTestUtils.setField(dataset, "id", DATASET_ID);
        EvalDatasetCase c = new EvalDatasetCase(DATASET_ID, "q", "a", null, 0);
        ReflectionTestUtils.setField(c, "id", CASE_ID);
        when(datasets.findById(DATASET_ID)).thenReturn(Optional.of(dataset));
        when(cases.findById(CASE_ID)).thenReturn(Optional.of(c));
        EvalDatasetService service = realDatasetService(datasets, cases);

        assertThrows(IllegalArgumentException.class, () ->
            service.setCaseGrader(AGENT_ID, DATASET_ID, CASE_ID, "{\"type\":\"regex\",\"pattern\":\"(\"}"));
        assertThrows(IllegalArgumentException.class, () ->
            service.setCaseGrader(AGENT_ID, DATASET_ID, CASE_ID, "{\"type\":\"nope\"}"));
        verify(cases, never()).save(any(EvalDatasetCase.class));
    }

    // ---- research seed ------------------------------------------------

    @Test
    void seedCreatesTheDatasetAndAGraderPerTask() {
        EvalDatasetService ds = mock(EvalDatasetService.class);
        EvalDataset created = new EvalDataset(AGENT_ID, "research", null, 0.7, true, "u");
        ReflectionTestUtils.setField(created, "id", DATASET_ID);
        when(ds.listDatasets(AGENT_ID)).thenReturn(List.of());
        when(ds.createDataset(eq(AGENT_ID), anyString(), any(), org.mockito.ArgumentMatchers.anyDouble(),
            org.mockito.ArgumentMatchers.anyBoolean())).thenReturn(created);
        when(ds.addCase(eq(AGENT_ID), eq(DATASET_ID), anyString(), any(), any(), anyInt()))
            .thenAnswer(inv -> {
                EvalDatasetCase c = new EvalDatasetCase(DATASET_ID, "q", "a", null, 0);
                ReflectionTestUtils.setField(c, "id", UUID.randomUUID());
                return c;
            });
        when(ds.requireDataset(AGENT_ID, DATASET_ID)).thenReturn(created);

        new ResearchSeedService(ds).seed(AGENT_ID);

        int tasks = ResearchSeedService.loadSeed().path("tasks").size();
        ArgumentCaptor<String> specs = ArgumentCaptor.forClass(String.class);
        verify(ds, org.mockito.Mockito.times(tasks))
            .setCaseGrader(eq(AGENT_ID), eq(DATASET_ID), any(UUID.class), specs.capture());
        specs.getAllValues().forEach(spec ->
            com.agenticai.core.quality.grader.CodeGrader.parse(spec)); // every bundled grader is valid
    }

    @Test
    void seedConflictsWhenTheDatasetAlreadyExists() {
        EvalDatasetService ds = mock(EvalDatasetService.class);
        String name = ResearchSeedService.loadSeed().path("name").asText();
        when(ds.listDatasets(AGENT_ID)).thenReturn(
            List.of(new EvalDataset(AGENT_ID, name, null, 0.7, true, "u")));

        assertThrows(EvalRunConflictException.class, () -> new ResearchSeedService(ds).seed(AGENT_ID));
        verify(ds, never()).createDataset(any(), anyString(), any(), org.mockito.ArgumentMatchers.anyDouble(),
            org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    void seedIsTransactionalSoAPartialDatasetRollsBack() throws Exception {
        assertTrue(ResearchSeedService.class.getMethod("seed", UUID.class)
            .isAnnotationPresent(org.springframework.transaction.annotation.Transactional.class));
    }

    // ---- controller plan gate ----------------------------------------

    private EvalCredibilityController controller(TenantPlan plan, JudgeCalibrationService calibration) {
        TenantLimits limits = mock(TenantLimits.class);
        when(limits.plan("tenant-1")).thenReturn(plan);
        return new EvalCredibilityController(
            mock(EvalDatasetService.class), mock(EvalRunService.class), mock(ResearchSeedService.class),
            calibration, mock(JudgeCalibrationSetService.class), limits);
    }

    @Test
    void freePlanIsRefusedOnEveryCredibilityEndpoint() {
        EvalCredibilityController c = controller(TenantPlan.FREE, mock(JudgeCalibrationService.class));

        assertThrows(TenantLimitExceededException.class, () -> c.seedResearch(AGENT_ID));
        assertThrows(TenantLimitExceededException.class, () ->
            c.setGrader(AGENT_ID, DATASET_ID, CASE_ID, new EvalCredibilityController.SetGraderRequest(null)));
        assertThrows(TenantLimitExceededException.class, () -> c.runBundle(AGENT_ID, DATASET_ID, UUID.randomUUID()));
        assertThrows(TenantLimitExceededException.class, () ->
            c.calibrate(AGENT_ID, new EvalCredibilityController.CalibrationRequest(List.of(), null)));
    }

    // ---- calibration gate + validation -------------------------------

    private static List<LabeledPair> examples(int n) {
        List<LabeledPair> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.add(new LabeledPair("p" + i, "q" + i, "good", "bad", "A"));
        }
        return out;
    }

    private JudgeCalibrationService calibration(boolean enabled, ChatClient client) {
        JudgeChatClientFactory factory = mock(JudgeChatClientFactory.class);
        when(factory.buildJudgeClient(anyInt())).thenReturn(client);
        when(factory.resolveJudgeModelIdOrNull()).thenReturn("OPENAI/judge");
        AgentDefinitionLookup lookup = mock(AgentDefinitionLookup.class);
        when(lookup.find(AGENT_ID)).thenReturn(Optional.empty());
        JudgeCalibrationService svc = new JudgeCalibrationService(factory, lookup);
        org.springframework.test.util.ReflectionTestUtils.setField(svc, "enabled", enabled);
        return svc;
    }

    @Test
    void calibrationIs404WhenDisabled() {
        ResponseStatusException e = assertThrows(ResponseStatusException.class,
            () -> calibration(false, mock(ChatClient.class)).calibrate(AGENT_ID, examples(30)));
        assertEquals(404, e.getStatusCode().value());
    }

    @Test
    void calibrationValidatesTheRequest() {
        JudgeCalibrationService svc = calibration(true, mock(ChatClient.class));
        assertThrows(IllegalArgumentException.class, () -> svc.calibrate(AGENT_ID, null));
        assertThrows(IllegalArgumentException.class, () -> svc.calibrate(AGENT_ID, List.of()));
        // fewer than the hard floor of examples
        assertThrows(IllegalArgumentException.class, () -> svc.calibrate(AGENT_ID, examples(1)));
        // over the cap
        assertThrows(IllegalArgumentException.class, () -> svc.calibrate(AGENT_ID, examples(61)));
        // over the synchronous judge-call cap (default 30 calls = 15 examples)
        IllegalArgumentException capped =
            assertThrows(IllegalArgumentException.class, () -> svc.calibrate(AGENT_ID, examples(16)));
        assertTrue(capped.getMessage().contains("max-judge-calls"));
        // bad label
        List<LabeledPair> bad = examples(12);
        bad.set(3, new LabeledPair("p", "q", "a", "b", "C"));
        assertThrows(IllegalArgumentException.class, () -> svc.calibrate(AGENT_ID, bad));
    }

    /** A judge client whose first {@code failures} calls throw and whose later calls answer "VERDICT: 1". */
    private static ChatClient flakyJudge(int failures) {
        ChatClient client = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec spec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec call = mock(ChatClient.CallResponseSpec.class);
        when(spec.call()).thenReturn(call);
        when(call.content()).thenReturn("VERDICT: 1");
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        when(client.prompt(any(org.springframework.ai.chat.prompt.Prompt.class))).thenAnswer(inv -> {
            if (calls.getAndIncrement() < failures) {
                throw new IllegalStateException("provider down");
            }
            return spec;
        });
        return client;
    }

    @Test
    void aPartialOutageIsReportedAsFailedJudgeCallsNotJustMissingVerdicts() {
        JudgeCalibrationService svc = calibration(true, flakyJudge(2));

        JudgeCalibrationService.CalibrationResponse r = svc.calibrate(AGENT_ID, examples(12));

        assertTrue(r.report().warnings().stream().anyMatch(w -> w.contains("2 judge call(s) failed")));
    }

    /**
     * Prod 2026-10-05 (bugs 1 + 11): the judge's provider refused its
     * credentials, calibration still made 40 calls, then explained the empty
     * result as "the human labels or the judge used only one answer position".
     */
    @Test
    void anUnusableJudgeFailsFastWithItsReasonBeforeAnyCalibrationCallIsSpent() {
        ChatClient client = mock(ChatClient.class);
        JudgeChatClientFactory factory = mock(JudgeChatClientFactory.class);
        when(factory.buildJudgeClient(anyInt())).thenReturn(client);
        when(factory.resolveJudgeModelIdOrNull()).thenReturn("GOOGLE_VERTEX/gemini-2.0-flash-001");
        when(factory.probeUnavailableReason()).thenReturn(Optional.of(
            "The AI provider rejected our credentials. Check the API key for this provider in Providers settings."));
        AgentDefinitionLookup lookup = mock(AgentDefinitionLookup.class);
        JudgeCalibrationService svc = new JudgeCalibrationService(factory, lookup);
        org.springframework.test.util.ReflectionTestUtils.setField(svc, "enabled", true);

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
            () -> svc.calibrate(AGENT_ID, examples(12)));

        assertEquals(400, e.getStatusCode().value());
        assertTrue(e.getReason().startsWith("Judge model unavailable (GOOGLE_VERTEX/gemini-2.0-flash-001): "
            + "The AI provider rejected our credentials."), e.getReason());
        org.mockito.Mockito.verify(client, org.mockito.Mockito.never())
            .prompt(any(org.springframework.ai.chat.prompt.Prompt.class));
    }

    @Test
    void aJudgeThatPassesThePreflightAndThenFailsEveryCallIsASpecificErrorNotAKappaReport() {
        JudgeCalibrationService svc = calibration(true, flakyJudge(Integer.MAX_VALUE));

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
            () -> svc.calibrate(AGENT_ID, examples(12)));

        assertEquals(400, e.getStatusCode().value());
        assertTrue(e.getReason().startsWith("Judge model OPENAI/judge returned no usable verdicts (3 of 3 judge calls "
            + "failed, stopped after 3 failures in a row: provider down)"), e.getReason());
    }
}
