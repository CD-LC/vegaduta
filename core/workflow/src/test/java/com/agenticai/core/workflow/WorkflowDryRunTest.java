package com.agenticai.core.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.agents.hosted.FreeTierChatClientService;
import com.agenticai.core.agents.tools.AgentToolProvider;
import com.agenticai.core.agents.tools.ToolCallLogRepository;
import com.agenticai.core.agents.turn.AgentChatService;
import com.agenticai.core.agents.turn.cache.LlmResponseCacheService;
import com.agenticai.core.agents.turn.quality.QueryComplexityClassifier;
import com.agenticai.core.approval.ApprovalEvaluation;
import com.agenticai.core.chat.gateway.LlmGatewayService;
import com.agenticai.core.devices.DeviceCommandService;
import com.agenticai.core.eventbus.WorkflowEventPublisher;
import com.agenticai.core.providers.ProviderCredentialResolver;
import com.agenticai.core.security.TenantLimits;
import com.agenticai.core.workflow.diagram.WorkflowDiagramPdfRenderer;
import com.agenticai.core.workflow.diagram.WorkflowDiagramService;
import com.agenticai.core.workflow.dto.NodeTraceEntry;
import com.agenticai.core.workflow.dto.WorkflowNodeDto;
import com.agenticai.core.workflow.dto.WorkflowRunRequest;
import com.agenticai.core.workflow.guidance.WorkflowInsightsService;
import com.agenticai.core.workflow.guidance.WorkflowLintService;
import com.agenticai.core.workflow.output.EmailOutputService;
import com.agenticai.core.workflow.output.GoogleDriveOutputService;
import com.agenticai.core.workflow.output.PdfOutputRenderer;
import com.agenticai.core.workflow.output.TeamsOutputService;
import com.agenticai.core.workflow.output.TelegramOutputService;
import com.agenticai.core.workflow.output.WhatsAppOutputService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.security.Principal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Dry run of sends (request {@code mode: "draft"} + {@code simulateSends: true}): the request is
 * turned into {@link RunMode#DRAFT_DRY_RUN} or refused, and under that mode the executor reports a
 * send instead of making it.
 */
class WorkflowDryRunTest {

    private static final UUID WF = UUID.randomUUID();
    private static final UUID RUN = UUID.randomUUID();

    private OrchestratorService orchestrator;
    private EmailOutputService email;
    private WhatsAppOutputService whatsApp;
    private TelegramOutputService telegram;
    private GoogleDriveOutputService drive;
    private WorkflowNodeExecutor executor;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        orchestrator = mock(OrchestratorService.class);
        email = mock(EmailOutputService.class);
        whatsApp = mock(WhatsAppOutputService.class);
        telegram = mock(TelegramOutputService.class);
        drive = mock(GoogleDriveOutputService.class);
        executor =
                NodeExecutorTestFactory.create(
                        orchestrator,
                        mock(AgentChatService.class),
                        mock(AgentService.class),
                        mock(ObjectProvider.class),
                        mock(WorkflowEventPublisher.class),
                        email,
                        telegram,
                        mock(TeamsOutputService.class),
                        whatsApp,
                        drive,
                        mock(PdfOutputRenderer.class),
                        mock(ToolCallLogRepository.class),
                        mock(DeviceCommandService.class),
                        mock(AgentToolProvider.class),
                        new ObjectMapper(),
                        mock(ObjectProvider.class),
                        mock(LlmResponseCacheService.class),
                        mock(QueryComplexityClassifier.class),
                        mock(FreeTierChatClientService.class),
                        mock(LlmGatewayService.class),
                        mock(ProviderCredentialResolver.class),
                        mock(TenantLimits.class),
                        mock(SubagentRunRepository.class),
                        mock(ObjectProvider.class),
                        mock(ObjectProvider.class),
                        mock(ObjectProvider.class),
                        mock(ObjectProvider.class));
    }

    @AfterEach
    void clearScope() {
        ExpressionScopeContext.clear();
    }

    private static WorkflowNodeDto node(String id, String type, Map<String, Object> config) {
        return new WorkflowNodeDto(id, type, Map.of(), Map.of("config", config, "label", id));
    }

    private static ExpressionScope dryRun() {
        return ExpressionScope.forMode(null, RunMode.DRAFT_DRY_RUN);
    }

    private WorkflowNodeExecutor.NodeExecutionResult run(
            WorkflowNodeDto node, String input, ExpressionScope scope) {
        ExpressionScopeContext.bind(scope);
        try {
            return executor.executeNode(WF.toString(), "wf", RUN.toString(), node, input, "tester");
        } finally {
            ExpressionScopeContext.clear();
        }
    }

    // --- the mode ---

    @Test
    void dryRunIsADraftThatSimulatesSendsAndSurvivesTheWireForm() {
        assertTrue(RunMode.DRAFT_DRY_RUN.honoursPins());
        assertTrue(RunMode.DRAFT_DRY_RUN.simulatesSends());
        assertFalse(RunMode.DRAFT.simulatesSends());
        assertFalse(RunMode.PRODUCTION.simulatesSends());
        assertFalse(RunMode.TEST.simulatesSends());
        assertEquals("draft_dry_run", RunMode.DRAFT_DRY_RUN.wire());
        assertEquals(RunMode.DRAFT_DRY_RUN, RunMode.fromWire("draft_dry_run"));
        assertThrows(
                IllegalArgumentException.class,
                () -> RunMode.fromRequest("draft_dry_run"),
                "a client asks with simulateSends, never by naming the mode");
        assertTrue(
                RunMode.DRAFT_DRY_RUN.name().length() <= 16,
                "workflow_run.run_mode is VARCHAR(16)");

        assertTrue(dryRun().simulatesSends());
        assertTrue(dryRun().honoursPins());
        assertFalse(ExpressionScope.forMode(null, RunMode.DRAFT).simulatesSends());
        ExpressionScope iteration =
                ExpressionScope.forMode(
                        ExpressionScope.iteration("a", 1, List.of("a", "b")),
                        RunMode.DRAFT_DRY_RUN);
        assertTrue(iteration.simulatesSends(), "loop bodies keep the mode");
        assertEquals("a", iteration.item());
    }

    // --- the executor ---

    @Test
    void aWhatsAppOutputReportsInsteadOfSending() {
        WorkflowNodeExecutor.NodeExecutionResult r =
                run(
                        node(
                                "wa",
                                "output",
                                Map.of("channel", "whatsapp", "whatsappTo", "+919999900000")),
                        "Your invoice is due",
                        dryRun());

        assertEquals("OK", r.status());
        assertEquals(
                "Your invoice is due", r.output(), "input passes through so later steps still run");
        assertEquals(
                "DRY RUN - would send a WhatsApp message to +919999900000: Your invoice is due",
                r.summary());
        assertEquals(r.summary(), r.logLines().get(0));
        verifyNoInteractions(whatsApp);
    }

    @Test
    void everyExternalChannelAndMutatingHttpIsSimulated() {
        assertTrue(
                run(
                                node(
                                        "m",
                                        "output",
                                        Map.of("channel", "email", "emailTo", "a@b.co")),
                                "hi",
                                dryRun())
                        .summary()
                        .startsWith("DRY RUN - would send an email to a@b.co: hi"));
        assertTrue(
                run(node("t", "output", Map.of("channel", "telegram")), "hi", dryRun())
                        .summary()
                        .startsWith(
                                "DRY RUN - would send a Telegram message to the chat that triggers the run"));
        assertTrue(
                run(
                                node(
                                        "d",
                                        "output",
                                        Map.of(
                                                "channel",
                                                "google_drive",
                                                "driveFileName",
                                                "r.txt")),
                                "hi",
                                dryRun())
                        .summary()
                        .startsWith(
                                "DRY RUN - would upload a file to Google Drive as r.txt in My Drive: hi"));
        WorkflowNodeExecutor.NodeExecutionResult post =
                run(
                        node(
                                "h",
                                "http",
                                Map.of(
                                        "method",
                                        "post",
                                        "url",
                                        "https://api.example.com/x",
                                        "bodyType",
                                        "json",
                                        "body",
                                        "{\"a\":1}")),
                        "in",
                        dryRun());
        assertEquals(
                "DRY RUN - would make a POST request to https://api.example.com/x: {\"a\":1}",
                post.summary());
        assertEquals("in", post.output());
        WorkflowNodeExecutor.NodeExecutionResult bulk =
                run(
                        node(
                                "b",
                                "bulk_email",
                                Map.of(
                                        "subject",
                                        "Hello",
                                        "body",
                                        "Body",
                                        "recipientsSource",
                                        "static",
                                        "staticRecipients",
                                        "a@b.co")),
                        "in",
                        dryRun());
        assertTrue(
                bulk.summary()
                        .startsWith(
                                "DRY RUN - would send a bulk email to a@b.co: Subject: Hello | Body"),
                bulk.summary());
        verifyNoInteractions(email, telegram, drive, whatsApp);
    }

    @Test
    void theDestinationShownIsTheResolvedOneAndThePayloadIsCappedAt160() {
        String longInput = "{\"phone\":\"+15550001111\",\"text\":\"" + "x".repeat(400) + "\"}";
        WorkflowNodeExecutor.NodeExecutionResult r =
                run(
                        node(
                                "wa",
                                "output",
                                Map.of("channel", "whatsapp", "whatsappTo", "{{ $json.phone }}")),
                        longInput,
                        dryRun());
        assertTrue(
                r.summary().startsWith("DRY RUN - would send a WhatsApp message to +15550001111: "),
                r.summary());
        String preview = r.summary().substring(r.summary().indexOf(": ") + 2);
        assertTrue(
                preview.length() <= WorkflowDryRun.PREVIEW_CHARS + 3,
                "payload preview is capped: " + preview.length());
    }

    @Test
    void nodesThatDoNotSendStillRunForReal() {
        WorkflowNodeExecutor.NodeExecutionResult code =
                run(node("c", "code", Map.of("operation", "uppercase")), "hello", dryRun());
        assertEquals("HELLO", code.output());
        WorkflowNodeExecutor.NodeExecutionResult log =
                run(node("o", "output", Map.of("channel", "log")), "hello", dryRun());
        assertFalse(log.summary().startsWith("DRY RUN"), log.summary());
        assertNull(
                WorkflowDryRun.simulateIfDryRun(
                        node("g", "http", Map.of("method", "GET", "url", "https://x.example")),
                        "in",
                        dryRun()));
        assertNull(
                WorkflowDryRun.simulateIfDryRun(
                        node("t", "tool", Map.of("toolName", "web_search")), "in", dryRun()),
                "a tool whose name does not say it sends cannot be judged from config");
    }

    @Test
    void sendingToolsSubWorkflowsCrmDeviceAndEventNodesAreSimulatedToo() {
        WorkflowNodeExecutor.NodeExecutionResult tool =
                run(
                        node(
                                "t",
                                "tool",
                                Map.of(
                                        "agentId",
                                        UUID.randomUUID().toString(),
                                        "toolName",
                                        "send_email",
                                        "argsJson",
                                        "{\"to\":\"a@b.co\"}")),
                        "in",
                        dryRun());
        assertEquals(
                "DRY RUN - would call the tool \"send_email\": {\"to\":\"a@b.co\"}",
                tool.summary());
        assertEquals("in", tool.output());

        String child = UUID.randomUUID().toString();
        WorkflowNodeExecutor.NodeExecutionResult sub =
                run(node("w", "workflow", Map.of("targetWorkflowId", child)), "in", dryRun());
        assertTrue(
                sub.summary()
                        .startsWith(
                                "DRY RUN - would start the workflow "
                                        + child
                                        + " as a real run: in"),
                sub.summary());

        assertTrue(
                run(
                                node("c", "crm_lead", Map.of("action", "stage", "email", "a@b.co")),
                                "in",
                                dryRun())
                        .summary()
                        .startsWith("DRY RUN - would write to the Leads CRM (stage): a@b.co"));
        assertTrue(
                run(
                                node(
                                        "d",
                                        "device_control",
                                        Map.of("deviceId", "dev-1", "payload", "on")),
                                "in",
                                dryRun())
                        .summary()
                        .startsWith("DRY RUN - would send a command to the device dev-1: on"));
        assertTrue(
                run(node("e", "output", Map.of("channel", "event")), "in", dryRun())
                        .summary()
                        .startsWith("DRY RUN - would publish a workflow event"));
    }

    @Test
    void stepsThatCannotBeSimulatedSayTheyRanForReal() {
        WorkflowNodeDto agent = node("a", "agent", Map.of("agentId", "x"));
        WorkflowNodeExecutor.NodeExecutionResult real =
                WorkflowNodeExecutor.NodeExecutionResult.ok("out", "done", null, List.of("line"));

        WorkflowNodeExecutor.NodeExecutionResult marked =
                WorkflowDryRun.markRealExecution(agent, dryRun(), real);
        assertEquals(List.of("line", WorkflowDryRun.RAN_FOR_REAL_LINE), marked.logLines());
        assertEquals("out", marked.output());
        assertEquals("done", marked.summary());
        assertEquals("OK", marked.status());

        assertTrue(
                WorkflowDryRun.runsForRealInDryRun(
                        node("t", "tool", Map.of("toolName", "web_search"))));
        assertTrue(WorkflowDryRun.runsForRealInDryRun(node("s", "supervisor", Map.of())));
        // Untouched: an ordinary draft run, a node with nothing to disclose, a null result.
        assertEquals(
                real,
                WorkflowDryRun.markRealExecution(
                        agent, ExpressionScope.forMode(null, RunMode.DRAFT), real));
        assertEquals(real, WorkflowDryRun.markRealExecution(agent, null, real));
        assertEquals(
                real,
                WorkflowDryRun.markRealExecution(
                        node("c", "code", Map.of("operation", "uppercase")), dryRun(), real));
        assertNull(WorkflowDryRun.markRealExecution(agent, dryRun(), null));
    }

    @Test
    void anOrdinaryDraftOrProductionRunStillSendsForReal() {
        when(whatsApp.send(anyString(), anyString(), anyString(), anyString())).thenReturn("Sent.");
        WorkflowNodeDto wa =
                node("wa", "output", Map.of("channel", "whatsapp", "whatsappTo", "+1555"));

        assertEquals(
                "Sent.", run(wa, "hi", ExpressionScope.forMode(null, RunMode.DRAFT)).summary());
        assertEquals("Sent.", run(wa, "hi", null).summary());

        verify(whatsApp, org.mockito.Mockito.times(2)).send("+1555", "hi", RUN.toString(), "wa");
    }

    @Test
    void approvalStillPausesInADryRun() {
        when(orchestrator.authorizeSensitiveNode(
                        anyString(), anyString(), any(), anyString(), any(), any()))
                .thenReturn(new ApprovalEvaluation(false, UUID.randomUUID()));
        assertEquals(
                WorkflowRunStatus.AWAITING_APPROVAL,
                run(node("ok", "approval", Map.of()), "hi", dryRun()).haltRun());
        // A send flagged requiresApproval waits for the human first; nothing is simulated (or sent)
        // before that.
        WorkflowNodeDto gatedSend =
                node(
                        "wa",
                        "output",
                        Map.of(
                                "channel",
                                "whatsapp",
                                "whatsappTo",
                                "+1555",
                                "requiresApproval",
                                true));
        assertEquals(WorkflowRunStatus.AWAITING_APPROVAL, run(gatedSend, "hi", dryRun()).haltRun());
        verifyNoInteractions(whatsApp);
    }

    // --- the trace flag ---

    @Test
    void traceEntriesCarryDryRunOnlyWhenSimulatedAndReadBackCleanly() throws Exception {
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        Instant now = Instant.parse("2026-10-03T09:00:00Z");
        NodeTraceEntry simulated =
                new NodeTraceEntry(
                        "wa",
                        "output",
                        "Remind",
                        "OK",
                        "DRY RUN - would send a WhatsApp message to +1555: hi",
                        now,
                        now);
        NodeTraceEntry real = new NodeTraceEntry("c", "code", "Shape", "OK", "done", now, now);

        JsonNode simulatedJson = mapper.readTree(mapper.writeValueAsString(simulated));
        assertTrue(simulatedJson.get("dryRun").asBoolean());
        assertFalse(
                mapper.readTree(mapper.writeValueAsString(real)).has("dryRun"),
                "ordinary entries are unchanged on the wire");

        NodeTraceEntry back =
                mapper.readValue(mapper.writeValueAsString(simulated), NodeTraceEntry.class);
        assertEquals(simulated, back);
        assertEquals(Boolean.TRUE, back.dryRun());
        assertNull(real.dryRun());
    }

    // --- the request ---

    private WorkflowController controller(
            WorkflowService service, WorkflowBackgroundRunService background) {
        return new WorkflowController(
                service,
                mock(WorkflowGraphGeneratorService.class),
                mock(WorkflowDiagramService.class),
                mock(WorkflowDiagramPdfRenderer.class),
                background,
                mock(WorkflowNodeTestService.class),
                mock(WorkflowLintService.class),
                mock(WorkflowInsightsService.class));
    }

    private static WorkflowRunRequest request(String mode, Boolean simulate, Boolean background) {
        return new WorkflowRunRequest("in", "by", null, null, background, mode, simulate);
    }

    @Test
    void simulateSendsWithDraftModeBecomesADryRunForBothRunPaths() {
        WorkflowService service = mock(WorkflowService.class);
        WorkflowBackgroundRunService background = mock(WorkflowBackgroundRunService.class);
        Principal ann = () -> "ann";
        WorkflowController controller = controller(service, background);
        controller.setSimulateSendsEnabled(
                true); // off by default until this image is the rollback target

        controller.run(WF, request("draft", true, null), null, ann);
        verify(service)
                .run(
                        eq(WF),
                        isNull(),
                        eq("in"),
                        eq("by"),
                        isNull(),
                        anyList(),
                        eq(RunMode.DRAFT_DRY_RUN));

        controller.run(WF, request("draft", true, true), null, ann);
        verify(background)
                .start(eq(WF), eq("in"), eq("by"), isNull(), anyList(), eq(RunMode.DRAFT_DRY_RUN));

        controller.run(WF, request("draft", false, null), null, ann);
        controller.run(WF, request("draft", null, null), null, ann);
        verify(service, org.mockito.Mockito.times(2))
                .run(eq(WF), isNull(), eq("in"), eq("by"), isNull(), anyList(), eq(RunMode.DRAFT));
    }

    @Test
    void simulateSendsIsRefusedNotIgnoredWhenItCannotBeHonoured() {
        WorkflowService service = mock(WorkflowService.class);
        WorkflowBackgroundRunService background = mock(WorkflowBackgroundRunService.class);
        Principal ann = () -> "ann";
        WorkflowController controller = controller(service, background);

        for (String mode : new String[] {null, "production"}) {
            ResponseStatusException ex =
                    assertThrows(
                            ResponseStatusException.class,
                            () -> controller.run(WF, request(mode, true, null), null, ann));
            assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        }

        // Default (no setter call): the flag ships off, so the opt-in is refused, never run for
        // real.
        ResponseStatusException off =
                assertThrows(
                        ResponseStatusException.class,
                        () -> controller.run(WF, request("draft", true, null), null, ann));
        assertEquals(HttpStatus.CONFLICT, off.getStatusCode());
        // The flag only governs the opt-in: an ordinary draft run is unaffected.
        controller.run(WF, request("draft", null, null), null, ann);
        verify(service)
                .run(eq(WF), isNull(), eq("in"), eq("by"), isNull(), anyList(), eq(RunMode.DRAFT));

        verify(service, never())
                .run(any(), any(), any(), any(), any(), anyList(), eq(RunMode.DRAFT_DRY_RUN));
        verify(service, never())
                .run(any(), any(), any(), any(), any(), anyList(), eq(RunMode.PRODUCTION));
        verifyNoInteractions(background);
    }
}
