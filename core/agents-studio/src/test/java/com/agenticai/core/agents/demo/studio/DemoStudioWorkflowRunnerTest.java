package com.agenticai.core.agents.demo.studio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.demo.studio.DemoStudioWorkflowRunner.Definition;
import com.agenticai.core.agents.demo.studio.DemoStudioWorkflowRunner.Step;
import com.agenticai.core.tenancy.demo.DemoSession;
import com.agenticai.core.tenancy.demo.DemoSessionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The studio's sequential runner: shape validation, template rendering, and the three flow-control
 * behaviours the page demonstrates - a condition that stops or skips, an approval that genuinely
 * parks the run until {@link DemoStudioWorkflowRunner#decide} is called, and the message allowance
 * being charged per agent step.
 */
class DemoStudioWorkflowRunnerTest {

    private DemoStudioService studio;
    private DemoSessionService sessions;
    private DemoStudioRunRepository runs;
    private DemoStudioWorkflowRunner runner;
    private DemoStudioModerationService moderation;
    private DemoSession session;
    private final UUID agentId = UUID.randomUUID();
    private final UUID sessionId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        studio = mock(DemoStudioService.class);
        sessions = mock(DemoSessionService.class);
        runs = mock(DemoStudioRunRepository.class);
        session = mock(DemoSession.class);
        when(session.getId()).thenReturn(sessionId);
        when(session.getMessageCount()).thenReturn(0);
        when(sessions.messageLimit()).thenReturn(20);
        // JPA assigns ids on save; the mock must too, or run_started has no run id to report.
        when(runs.save(any(DemoStudioRun.class)))
                .thenAnswer(
                        inv -> {
                            DemoStudioRun run = inv.getArgument(0);
                            if (run.getId() == null) {
                                java.lang.reflect.Field id =
                                        DemoStudioRun.class.getDeclaredField("id");
                                id.setAccessible(true);
                                id.set(run, UUID.randomUUID());
                            }
                            return run;
                        });
        moderation = mock(DemoStudioModerationService.class);
        runner =
                new DemoStudioWorkflowRunner(
                        studio, sessions, runs, new ObjectMapper(), moderation);
    }

    private Step trigger() {
        return new Step("t", "trigger", "Input", Map.of());
    }

    private Step agent(String id, String instruction) {
        return new Step(
                id,
                "agent",
                "Agent " + id,
                Map.of("agentId", agentId.toString(), "instruction", instruction));
    }

    private DemoStudioWorkflow workflow(Definition def) {
        return new DemoStudioWorkflow(
                sessionId, "203.0.113.9", "wf", runner.serialize(def), java.time.Instant.now());
    }

    private List<Map<String, Object>> collect(DemoStudioWorkflow wf, String input)
            throws Exception {
        ObjectMapper om = new ObjectMapper();
        return runner.run(session, wf, input)
                .map(
                        line -> {
                            try {
                                return om.readValue(
                                        line,
                                        new com.fasterxml.jackson.core.type.TypeReference<
                                                Map<String, Object>>() {});
                            } catch (Exception e) {
                                throw new RuntimeException(e);
                            }
                        })
                .collectList()
                .block(Duration.ofSeconds(10));
    }

    // ---- validation -------------------------------------------------

    @Test
    void validateRejectsAWorkflowWithoutATriggerFirst() {
        Definition def = new Definition(List.of(agent("a", "{{input}}")));
        DemoStudioService.StudioException ex =
                assertThrows(
                        DemoStudioService.StudioException.class,
                        () -> runner.validate(session, def));
        assertEquals("invalid", ex.code());
    }

    @Test
    void validateRejectsAWorkflowWithNoAgentStep() {
        Definition def =
                new Definition(List.of(trigger(), new Step("o", "output", "Out", Map.of())));
        assertThrows(DemoStudioService.StudioException.class, () -> runner.validate(session, def));
    }

    @Test
    void validateRejectsAnAgentStepPointingAtAnAgentTheSessionCannotSee() {
        when(studio.getAgent(eq(session), any()))
                .thenThrow(new DemoStudioService.StudioException("not_found", 404, "no"));
        Definition def = new Definition(List.of(trigger(), agent("a", "{{input}}")));
        assertThrows(DemoStudioService.StudioException.class, () -> runner.validate(session, def));
    }

    @Test
    void validateCapsTheStepCount() {
        Step[] many = new Step[DemoStudioWorkflowRunner.MAX_STEPS + 1];
        many[0] = trigger();
        for (int i = 1; i < many.length; i++) {
            many[i] = agent("a" + i, "x");
        }
        assertThrows(
                DemoStudioService.StudioException.class,
                () -> runner.validate(session, new Definition(List.of(many))));
    }

    // ---- rendering --------------------------------------------------

    @Test
    void renderSubstitutesInputAndPreviousAndAppendsPreviousWhenUnreferenced() {
        assertEquals(
                "Hello world", DemoStudioWorkflowRunner.render("Hello {{input}}", "world", "prev"));
        assertEquals("Use prev", DemoStudioWorkflowRunner.render("Use {{previous}}", "in", "prev"));
        assertEquals(
                "Summarise this\n\n---\nprev",
                DemoStudioWorkflowRunner.render("Summarise this", "in", "prev"));
        assertEquals("prev", DemoStudioWorkflowRunner.render(null, "in", "prev"));
    }

    // ---- execution --------------------------------------------------

    @Test
    void agentStepsRunInOrderPassingOutputAlongAndChargeOneMessageEach() throws Exception {
        when(studio.runMissionBlocking(eq(session), eq(agentId), anyString(), anyString(), any()))
                .thenAnswer(inv -> "OUT[" + inv.getArgument(2, String.class) + "]");
        Definition def =
                new Definition(
                        List.of(
                                trigger(),
                                agent("a", "{{input}}"),
                                agent("b", "second {{previous}}"),
                                new Step("o", "output", "Out", Map.of())));
        List<Map<String, Object>> events = collect(workflow(def), "hello");

        List<String> types = events.stream().map(e -> (String) e.get("type")).toList();
        assertTrue(types.contains("run_started"));
        assertEquals(2, types.stream().filter("step_output"::equals).count());
        Map<String, Object> finished = events.get(events.size() - 1);
        assertEquals("run_finished", finished.get("type"));
        assertEquals("COMPLETED", finished.get("status"));
        assertEquals("OUT[second OUT[hello]]", finished.get("output"));
        assertEquals(2, finished.get("messagesUsed"));
        org.mockito.Mockito.verify(sessions, org.mockito.Mockito.times(2)).recordMessage(session);
    }

    @Test
    void conditionStopsTheRunWhenTheWordIsMissing() throws Exception {
        when(studio.runMissionBlocking(any(), any(), anyString(), anyString(), any()))
                .thenReturn("all calm here");
        Definition def =
                new Definition(
                        List.of(
                                trigger(),
                                agent("a", "{{input}}"),
                                new Step(
                                        "c",
                                        "condition",
                                        "Urgent?",
                                        Map.of("contains", "urgency: high", "onFalse", "stop")),
                                agent("b", "escalate {{previous}}")));
        List<Map<String, Object>> events = collect(workflow(def), "hi");
        Map<String, Object> finished = events.get(events.size() - 1);
        assertEquals("STOPPED", finished.get("status"));
        assertEquals(1, events.stream().filter(e -> "step_output".equals(e.get("type"))).count());
    }

    @Test
    void conditionSkipsTheNextStepWhenAskedTo() throws Exception {
        when(studio.runMissionBlocking(any(), any(), anyString(), anyString(), any()))
                .thenReturn("all calm here");
        Definition def =
                new Definition(
                        List.of(
                                trigger(),
                                agent("a", "{{input}}"),
                                new Step(
                                        "c",
                                        "condition",
                                        "Urgent?",
                                        Map.of("contains", "urgency: high", "onFalse", "skip")),
                                agent("b", "escalate {{previous}}"),
                                new Step("o", "output", "Out", Map.of())));
        List<Map<String, Object>> events = collect(workflow(def), "hi");
        assertTrue(
                events.stream()
                        .anyMatch(
                                e ->
                                        "step_skipped".equals(e.get("type"))
                                                && "b".equals(e.get("stepId"))));
        assertEquals("COMPLETED", events.get(events.size() - 1).get("status"));
        assertEquals("all calm here", events.get(events.size() - 1).get("output"));
    }

    @Test
    void approvalParksTheRunUntilAHumanDecides() throws Exception {
        when(studio.runMissionBlocking(any(), any(), anyString(), anyString(), any()))
                .thenReturn("draft");
        Definition def =
                new Definition(
                        List.of(
                                trigger(),
                                agent("a", "{{input}}"),
                                new Step(
                                        "ok",
                                        "approval",
                                        "Sign off",
                                        Map.of("message", "Publish?")),
                                new Step("o", "output", "Out", Map.of())));

        CompletableFuture<List<Map<String, Object>>> result =
                CompletableFuture.supplyAsync(
                        () -> {
                            try {
                                return collect(workflow(def), "topic");
                            } catch (Exception e) {
                                throw new RuntimeException(e);
                            }
                        });
        // The run must NOT finish on its own - it is waiting on a decision.
        Thread.sleep(700);
        assertFalse(result.isDone(), "run completed without a human decision");

        // Nothing is waiting under a random id; the real run id is in the events, so find it via
        // the pending map.
        assertFalse(runner.decide(UUID.randomUUID(), true));
        UUID runId = waitForPending();
        assertTrue(runner.decide(runId, false));

        List<Map<String, Object>> events = result.get(10, TimeUnit.SECONDS);
        assertTrue(events.stream().anyMatch(e -> "approval_required".equals(e.get("type"))));
        assertTrue(
                events.stream()
                        .anyMatch(
                                e ->
                                        "approval".equals(e.get("type"))
                                                && Boolean.FALSE.equals(e.get("approved"))));
        assertEquals("REJECTED", events.get(events.size() - 1).get("status"));
        // A second decision on the same run is a no-op, not an error.
        assertFalse(runner.decide(runId, true));
    }

    @Test
    void anAgentStepWithNoAllowanceLeftStopsTheRunWithoutCallingTheModel() throws Exception {
        when(session.getMessageCount()).thenReturn(20);
        Definition def = new Definition(List.of(trigger(), agent("a", "{{input}}")));
        List<Map<String, Object>> events = collect(workflow(def), "x");
        assertEquals("STOPPED", events.get(events.size() - 1).get("status"));
        org.mockito.Mockito.verify(studio, org.mockito.Mockito.never())
                .runMissionBlocking(any(), any(), anyString(), anyString(), any());
    }

    // ---- moderation -------------------------------------------------

    @Test
    @SuppressWarnings("unchecked")
    void moderateChecksTheNameEveryLabelAndEveryFreeTextConfigValueButNotAgentIds() {
        Step approval =
                new Step(
                        "p",
                        "approval",
                        "Manager sign-off",
                        Map.of("message", "Please approve the refund"));
        Step condition = new Step("c", "condition", "Refund?", Map.of("contains", "refund"));
        Definition def =
                new Definition(
                        List.of(trigger(), agent("a", "Summarise {{input}}"), condition, approval));

        runner.moderate(session, "Refund flow", def);

        org.mockito.ArgumentCaptor<Map<String, String>> captor =
                org.mockito.ArgumentCaptor.forClass(Map.class);
        org.mockito.Mockito.verify(moderation).check(eq(session), captor.capture());
        Map<String, String> texts = captor.getValue();
        assertEquals("Refund flow", texts.get("workflow name"));
        assertEquals("Manager sign-off", texts.get("step 4 label"));
        assertEquals("Please approve the refund", texts.get("step 4 message"));
        assertEquals("refund", texts.get("step 3 contains"));
        assertEquals("Summarise {{input}}", texts.get("step 2 instruction"));
        assertFalse(texts.values().contains(agentId.toString()), "agent ids are not prose");
    }

    @Test
    void aBlockedRunInputStopsBeforeAnythingIsPersistedOrSentToAModel() {
        org.mockito.Mockito.doThrow(
                        new DemoStudioService.StudioException("content_blocked", 422, "no"))
                .when(moderation)
                .check(eq(session), any(Map.class));
        Definition def = new Definition(List.of(trigger(), agent("a", "{{input}}")));

        DemoStudioService.StudioException ex =
                assertThrows(
                        DemoStudioService.StudioException.class,
                        () -> runner.run(session, workflow(def), "bad input"));

        assertEquals("content_blocked", ex.code());
        org.mockito.Mockito.verify(runs, org.mockito.Mockito.never())
                .save(any(DemoStudioRun.class));
        org.mockito.Mockito.verify(studio, org.mockito.Mockito.never())
                .runMissionBlocking(any(), any(), anyString(), anyString(), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void runInputIsModeratedUnderItsOwnLabel() throws Exception {
        when(studio.runMissionBlocking(eq(session), eq(agentId), anyString(), anyString(), any()))
                .thenReturn("ok");
        collect(
                workflow(new Definition(List.of(trigger(), agent("a", "{{input}}")))),
                "  hello there  ");
        org.mockito.Mockito.verify(moderation)
                .check(session, Map.of("workflow input", "hello there"));
    }

    private UUID waitForPending() throws Exception {
        java.lang.reflect.Field f =
                DemoStudioWorkflowRunner.class.getDeclaredField("pendingApprovals");
        f.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<UUID, ?> pending = (Map<UUID, ?>) f.get(runner);
        long deadline = System.currentTimeMillis() + 5000;
        while (pending.isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        assertFalse(pending.isEmpty(), "no run parked on approval");
        return pending.keySet().iterator().next();
    }
}
