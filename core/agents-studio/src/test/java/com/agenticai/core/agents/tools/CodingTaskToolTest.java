package com.agenticai.core.agents.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.coding.CodingTaskDtos.Budget;
import com.agenticai.core.agents.coding.CodingTaskDtos.DiffResponse;
import com.agenticai.core.agents.coding.CodingTaskDtos.Review;
import com.agenticai.core.agents.coding.CodingTaskDtos.TaskView;
import com.agenticai.core.agents.coding.CodingTaskDtos.TestRun;
import com.agenticai.core.agents.coding.CodingTaskDtos.Usage;
import com.agenticai.core.agents.coding.CodingTaskService;
import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.agents.job.AgentJobSession;
import com.agenticai.core.agents.tools.classification.BuiltInToolClassifications;
import com.agenticai.core.agents.tools.classification.ToolClassification;
import com.agenticai.core.agents.tools.classification.ToolClassificationDecision;
import com.agenticai.core.agents.tools.classification.ToolClassificationRegistry;
import com.agenticai.core.agents.tools.classification.ToolSensitivity;
import com.agenticai.core.agents.tools.coding.CodingTaskToolCallback;
import com.agenticai.core.agents.tools.coding.CodingTaskToolCallbackFactory;
import com.agenticai.core.guardrails.ToolDispatchGate;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.TenantLimitExceededException;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The agent-facing side of coding tasks: how the tool is classified and gated (human confirmation
 * that no setting or autonomy tier can switch off), when it is offered, and what it can and cannot
 * do (start a task and read it - never approve it).
 */
class CodingTaskToolTest {

    private static final ObjectMapper MAPPER = com.agenticai.core.json.JsonMappers.plain();
    private final UUID agentId = UUID.randomUUID();
    private final CodingTaskService service = mock(CodingTaskService.class);

    @BeforeEach
    void setUp() {
        TenantContext.set(TenantContext.of("tenantA", "user1", Set.of("USER")));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    // -------------------------------------------------------------------------------
    // classification and gate

    @Test
    void codingTaskIsClassifiedEgressIrreversibleAndRequiresHumanConfirmation() {
        ToolClassification c = BuiltInToolClassifications.all().get("coding_task");
        assertThat(c).isNotNull();
        assertThat(c.sensitivity()).isEqualTo(ToolSensitivity.EGRESS);
        assertThat(c.irreversible()).isTrue();
        assertThat(c.requiresHumanConfirmation()).isTrue();
        assertThat(c.producesTaintedOutput()).isTrue();
        assertThat(c.isTaintSink()).isTrue();
        assertThat(AgentToolGuard.ALWAYS_CONFIRM_TOOLS).contains("coding_task");
    }

    @Test
    void noGateSettingLetsTheToolRunWithoutAConfirmation() {
        ToolClassificationRegistry registry = mock(ToolClassificationRegistry.class);
        when(registry.resolve("coding_task"))
                .thenReturn(
                        new ToolClassificationDecision.Classified(
                                BuiltInToolClassifications.all().get("coding_task")));
        MeterRegistry meters = new SimpleMeterRegistry();

        // confirmation-gate switched OFF and the tool on the exempt list: still asks.
        AgentToolGovernance loosest =
                new AgentToolGovernance(
                        providerOf(registry),
                        providerOf(meters),
                        providerOf(null),
                        false,
                        "coding_task,run_code");
        assertThat(loosest.decide(registry, "coding_task").outcome())
                .isEqualTo(ToolDispatchGate.Outcome.REQUIRE_APPROVAL);

        // gate on, exempt list naming it: still asks.
        AgentToolGovernance exempt =
                new AgentToolGovernance(
                        providerOf(registry),
                        providerOf(meters),
                        providerOf(null),
                        true,
                        "coding_task");
        assertThat(exempt.decide(registry, "coding_task").outcome())
                .isEqualTo(ToolDispatchGate.Outcome.REQUIRE_APPROVAL);
        // With the exempt list used for an ordinary tool the same config would have let it through
        // (control).
        when(registry.resolve("run_code"))
                .thenReturn(
                        new ToolClassificationDecision.Classified(
                                BuiltInToolClassifications.all().get("run_code")));
        AgentToolGovernance ordinary =
                new AgentToolGovernance(
                        providerOf(registry),
                        providerOf(meters),
                        providerOf(null),
                        true,
                        "run_code");
        assertThat(ordinary.decide(registry, "run_code").outcome())
                .isEqualTo(ToolDispatchGate.Outcome.ALLOW);
    }

    @Test
    void aFullyAutonomousAgentStillAsksForThisTool() {
        // NEVER_GATE degrades to POLICY_DEFAULT for the always-confirm tools (the same rule
        // a2a_call_agent has).
        AgentToolGuard guard = mock(AgentToolGuard.class, org.mockito.Mockito.CALLS_REAL_METHODS);
        assertThat(
                        guard.approvalGateModeFor(
                                com.agenticai.core.agents.core.AgentAutonomyTier.FULLY_AUTONOMOUS,
                                "coding_task"))
                .isNotEqualTo(com.agenticai.core.guardrails.ApprovalGateMode.NEVER_GATE);
        assertThat(
                        guard.approvalGateModeFor(
                                com.agenticai.core.agents.core.AgentAutonomyTier.FULLY_AUTONOMOUS,
                                "some_other_tool"))
                .isEqualTo(com.agenticai.core.guardrails.ApprovalGateMode.NEVER_GATE);
    }

    // ------------------------------------------------------------------------------- when it is
    // offered

    private AgentCodingTaskToolResolver resolver(AgentService agentService, CodingTaskService svc) {
        AgentToolGuard toolGuard = mock(AgentToolGuard.class);
        when(toolGuard.guardAndLog(any(), any(), any(), any(), any(), any(), anyBoolean()))
                .thenAnswer(inv -> inv.getArgument(0));
        AgentCodingTaskToolResolver resolver =
                new AgentCodingTaskToolResolver(toolGuard, agentService);
        resolver.setFactory(providerOf(new CodingTaskToolCallbackFactory(providerOf(svc))));
        return resolver;
    }

    private List<String> names(List<ToolCallback> tools) {
        return tools.stream().map(t -> t.getToolDefinition().name()).toList();
    }

    @Test
    void offeredOnlyWhenTheFlagIsOnTheAgentOptedInAndTheTurnIsNotAJob() {
        AgentService agentService = mock(AgentService.class);
        when(agentService.getAllowedToolNames(any())).thenReturn(Set.of("coding_task"));
        when(service.isEnabled()).thenReturn(true);
        AgentCodingTaskToolResolver on = resolver(agentService, service);
        assertThat(
                        names(
                                on.resolveCodingTaskTools(
                                        agentId, "s1", new AtomicInteger(), null, null, false)))
                .containsExactly("coding_task");

        // An unattended job has nobody to approve the diff.
        String jobSession = AgentJobSession.sessionIdFor(UUID.randomUUID());
        assertThat(
                        on.resolveCodingTaskTools(
                                agentId, jobSession, new AtomicInteger(), null, null, false))
                .isEmpty();

        // Empty allow-list means "unrestricted", which is deliberately not an opt-in.
        when(agentService.getAllowedToolNames(any())).thenReturn(Set.of());
        assertThat(on.resolveCodingTaskTools(agentId, "s1", new AtomicInteger(), null, null, false))
                .isEmpty();
        when(agentService.getAllowedToolNames(any())).thenReturn(Set.of("run_code"));
        assertThat(on.resolveCodingTaskTools(agentId, "s1", new AtomicInteger(), null, null, false))
                .isEmpty();

        // The admin picker lists it whenever the flag allows.
        assertThat(
                        names(
                                on.resolveCodingTaskToolsForDiscovery(
                                        agentId, "probe", new AtomicInteger(), null, null, false)))
                .containsExactly("coding_task");
    }

    @Test
    void neverOfferedWhenTheFlagIsOffOrTheServiceIsAbsent() {
        AgentService agentService = mock(AgentService.class);
        when(agentService.getAllowedToolNames(any())).thenReturn(Set.of("coding_task"));
        when(service.isEnabled()).thenReturn(false);
        AgentCodingTaskToolResolver off = resolver(agentService, service);
        assertThat(
                        off.resolveCodingTaskTools(
                                agentId, "s1", new AtomicInteger(), null, null, false))
                .isEmpty();
        assertThat(
                        off.resolveCodingTaskToolsForDiscovery(
                                agentId, "p", new AtomicInteger(), null, null, false))
                .isEmpty();

        AgentCodingTaskToolResolver absent = resolver(agentService, null);
        assertThat(
                        absent.resolveCodingTaskTools(
                                agentId, "s1", new AtomicInteger(), null, null, false))
                .isEmpty();

        AgentCodingTaskToolResolver neverWired =
                new AgentCodingTaskToolResolver(mock(AgentToolGuard.class), agentService);
        assertThat(
                        neverWired.resolveCodingTaskTools(
                                agentId, "s1", new AtomicInteger(), null, null, false))
                .isEmpty();
    }

    // ------------------------------------------------------------------------------- the callback

    private TaskView view(String status, String outcome, boolean diff, boolean testFiles) {
        return new TaskView(
                "11111111-1111-1111-1111-111111111111",
                agentId.toString(),
                status,
                outcome,
                status,
                "Add validation",
                "mvn -q test",
                "GIT",
                "https://github.com/acme/widgets",
                "e2b",
                new Budget(20, 900, 150000, 3),
                new Usage(3, 900, 5000),
                List.of(),
                List.of(new TestRun(0, 1, "FAILED"), new TestRun(1, 0, "OK")),
                new Review(true, "ok"),
                diff,
                diff ? "hash" : null,
                diff ? 2 : 0,
                testFiles,
                "Tests pass.",
                false,
                1L,
                2L);
    }

    @Test
    void definitionExposesOnlyTheThreeInputsAndNoWayToDecide() throws Exception {
        CodingTaskToolCallback tool = new CodingTaskToolCallback(service, agentId);
        assertThat(tool.getToolDefinition().name()).isEqualTo("coding_task");
        JsonNode schema = MAPPER.readTree(tool.getToolDefinition().inputSchema());
        List<String> props = new java.util.ArrayList<>();
        schema.get("properties").propertyNames().forEach(props::add);
        assertThat(props).containsExactlyInAnyOrder("instruction", "repoUrl", "testCommand");
        assertThat(tool.getToolDefinition().description().toLowerCase()).contains("cannot approve");
        for (String forbidden :
                List.of(
                        "approve",
                        "skip",
                        "auto",
                        "confirm",
                        "agentId",
                        "tenant",
                        "budget",
                        "maxSteps")) {
            assertThat(props).noneMatch(p -> p.toLowerCase().contains(forbidden.toLowerCase()));
        }
    }

    @Test
    void startsATaskWaitsAndReturnsTheDiffWithApprovalRequiredButNeverDecides() throws Exception {
        when(service.start(any(), anyString())).thenReturn(view("QUEUED", null, false, false));
        when(service.toolWaitSeconds()).thenReturn(5);
        when(service.awaitForTool(anyString(), anyString(), anyInt()))
                .thenReturn(view("AWAITING_APPROVAL", null, true, true));
        when(service.diff(anyString(), anyString()))
                .thenReturn(new DiffResponse("t", "diff --git a/x b/x\n+y\n", "hash", false));

        String out =
                new CodingTaskToolCallback(service, agentId)
                        .call(
                                "{\"instruction\":\"Add validation\",\"repoUrl\":\"https://github.com/acme/widgets\",\"testCommand\":\"mvn -q test\"}");
        JsonNode json = MAPPER.readTree(out);
        assertThat(json.get("status").asString()).isEqualTo("AWAITING_APPROVAL");
        assertThat(json.get("approvalRequired").asBoolean()).isTrue();
        assertThat(json.get("diff").asString()).contains("+y");
        assertThat(json.get("testFilesTouched").asBoolean()).isTrue();
        assertThat(json.get("testRuns")).hasSize(2);
        assertThat(json.get("note").asString()).contains("cannot approve");

        org.mockito.ArgumentCaptor<CodingTaskService.StartInput> input =
                org.mockito.ArgumentCaptor.forClass(CodingTaskService.StartInput.class);
        verify(service).start(input.capture(), org.mockito.ArgumentMatchers.eq("user1"));
        assertThat(input.getValue().agentId()).isEqualTo(agentId);
        assertThat(input.getValue().archive()).isNull();
        // The model cannot raise the budget: the tool never passes one.
        assertThat(input.getValue().maxSteps()).isNull();
        assertThat(input.getValue().maxWallSeconds()).isNull();
        assertThat(input.getValue().maxTokens()).isNull();
        verify(service, never()).approve(any(), any(), any());
        verify(service, never()).reject(any(), any());
        verify(service, never()).rollback(any(), any());
    }

    @Test
    void aTaskStillRunningComesBackAsAnIdToReport() throws Exception {
        when(service.start(any(), anyString())).thenReturn(view("QUEUED", null, false, false));
        when(service.toolWaitSeconds()).thenReturn(5);
        when(service.awaitForTool(anyString(), anyString(), anyInt()))
                .thenReturn(view("RUNNING", null, false, false));
        JsonNode json =
                MAPPER.readTree(
                        new CodingTaskToolCallback(service, agentId)
                                .call(
                                        "{\"instruction\":\"x\",\"repoUrl\":\"https://github.com/a/b\",\"testCommand\":\"npm test\"}"));
        assertThat(json.get("status").asString()).isEqualTo("RUNNING");
        assertThat(json.has("diff")).isFalse();
        assertThat(json.get("note").asString()).contains("Still running");
    }

    @Test
    void failuresComeBackAsShortTextNotExceptions() {
        CodingTaskToolCallback tool = new CodingTaskToolCallback(service, agentId);
        org.mockito.Mockito.doThrow(
                        new IllegalArgumentException(
                                "Only these test runners are allowed: go, mvn."))
                .when(service)
                .start(any(), anyString());
        assertThat(
                        tool.call(
                                "{\"instruction\":\"x\",\"repoUrl\":\"https://github.com/a/b\",\"testCommand\":\"rm -rf /\"}"))
                .startsWith("Could not start the coding task:")
                .contains("test runners");

        org.mockito.Mockito.doThrow(
                        new TenantLimitExceededException("Coding tasks are a paid-plan feature."))
                .when(service)
                .start(any(), anyString());
        assertThat(
                        tool.call(
                                "{\"instruction\":\"x\",\"repoUrl\":\"https://github.com/a/b\",\"testCommand\":\"npm test\"}"))
                .contains("paid-plan");

        org.mockito.Mockito.doThrow(
                        new ResponseStatusException(
                                HttpStatus.CONFLICT, "A coding task is already in progress."))
                .when(service)
                .start(any(), anyString());
        assertThat(
                        tool.call(
                                "{\"instruction\":\"x\",\"repoUrl\":\"https://github.com/a/b\",\"testCommand\":\"npm test\"}"))
                .contains("already in progress");

        org.mockito.Mockito.doThrow(new IllegalStateException("redis exploded: secret"))
                .when(service)
                .start(any(), anyString());
        assertThat(
                        tool.call(
                                "{\"instruction\":\"x\",\"repoUrl\":\"https://github.com/a/b\",\"testCommand\":\"npm test\"}"))
                .contains("internal error")
                .doesNotContain("secret");

        assertThat(tool.call("not json")).contains("not valid JSON");
        assertThat(tool.call(null)).contains("required");
        assertThat(tool.call("[1,2]")).contains("JSON object");
    }

    @Test
    void refusesWithoutASignedInUser() {
        TenantContext.clear();
        assertThat(
                        new CodingTaskToolCallback(service, agentId)
                                .call(
                                        "{\"instruction\":\"x\",\"repoUrl\":\"https://github.com/a/b\",\"testCommand\":\"npm test\"}"))
                .contains("no signed-in user");
        verify(service, never()).start(any(), any());
    }

    @Test
    void theConfirmationCardNamesTheRepoTheCommandAndTheDataLeavingTheWorkspace() {
        String card =
                new CodingTaskToolCallback(service, agentId)
                        .describeForConfirmation(
                                "{\"instruction\":\"Add validation\",\"repoUrl\":\"https://github.com/acme/widgets\",\"testCommand\":\"mvn -q test\"}");
        assertThat(card)
                .contains("https://github.com/acme/widgets")
                .contains("mvn -q test")
                .contains("Add validation")
                .contains("approve the diff");
        assertThat(new CodingTaskToolCallback(service, agentId).describeForConfirmation("garbage"))
                .isNull();
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> providerOf(T value) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }
}
