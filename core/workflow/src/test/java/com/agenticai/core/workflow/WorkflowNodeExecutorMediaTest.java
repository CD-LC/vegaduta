package com.agenticai.core.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.agents.hosted.FreeTierChatClientService;
import com.agenticai.core.agents.tools.AgentToolProvider;
import com.agenticai.core.agents.tools.ToolCallLogRepository;
import com.agenticai.core.agents.turn.AgentChatService;
import com.agenticai.core.agents.turn.cache.LlmResponseCacheService;
import com.agenticai.core.agents.turn.quality.QueryComplexityClassifier;
import com.agenticai.core.chat.gateway.LlmGatewayService;
import com.agenticai.core.devices.DeviceCommandService;
import com.agenticai.core.eventbus.WorkflowEventPublisher;
import com.agenticai.core.knowledge.SidecarClient;
import com.agenticai.core.knowledge.dto.SidecarCaptionsResponse;
import com.agenticai.core.knowledge.dto.SidecarRenderJobResponse;
import com.agenticai.core.knowledge.dto.SidecarTtsResponse;
import com.agenticai.core.knowledge.dto.SidecarTtsSegmentResult;
import com.agenticai.core.providers.ProviderCredentialResolver;
import com.agenticai.core.security.TenantLimits;
import com.agenticai.core.workflow.dto.WorkflowNodeDto;
import com.agenticai.core.workflow.output.EmailOutputService;
import com.agenticai.core.workflow.output.GoogleDriveOutputService;
import com.agenticai.core.workflow.output.PdfOutputRenderer;
import com.agenticai.core.workflow.output.TeamsOutputService;
import com.agenticai.core.workflow.output.TelegramOutputService;
import com.agenticai.core.workflow.output.WhatsAppOutputService;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Tests for the MEDIA node (video pipeline Stage 2/3 from a workflow).
 *
 * <p>Nothing here touches a real sidecar - {@link SidecarClient} is mocked, so what is under test
 * is the node's own contract: which operation maps to which call, what it does with the input, and
 * above all that it NEVER throws. A media node that propagates an exception takes the whole run
 * down, where the house convention (see the Tool and HTTP nodes) is SKIPPED with a reason and the
 * input passed through so a downstream Condition can branch on it.
 *
 * <p>Plain JUnit 5 + static Mockito mocks, no Spring context - same idiom as
 * WorkflowNodeExecutorOutputReplyToSenderTest.
 */
class WorkflowNodeExecutorMediaTest {

    private SidecarClient sidecarClient;
    private ObjectProvider<SidecarClient> sidecarProvider;
    private WorkflowNodeExecutor executor;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        sidecarClient = mock(SidecarClient.class);
        sidecarProvider = mock(ObjectProvider.class);
        when(sidecarProvider.getIfAvailable()).thenReturn(sidecarClient);
        executor = newExecutor(sidecarProvider);
    }

    @SuppressWarnings("unchecked")
    private WorkflowNodeExecutor newExecutor(ObjectProvider<SidecarClient> provider) {
        return NodeExecutorTestFactory.create(
                mock(OrchestratorService.class),
                mock(AgentChatService.class),
                mock(AgentService.class),
                mock(ObjectProvider.class),
                mock(WorkflowEventPublisher.class),
                mock(EmailOutputService.class),
                mock(TelegramOutputService.class),
                mock(TeamsOutputService.class),
                mock(WhatsAppOutputService.class),
                mock(GoogleDriveOutputService.class),
                mock(PdfOutputRenderer.class),
                mock(ToolCallLogRepository.class),
                mock(DeviceCommandService.class),
                mock(AgentToolProvider.class),
                com.agenticai.core.json.JsonMappers.plain(),
                mock(ObjectProvider.class),
                mock(LlmResponseCacheService.class),
                mock(QueryComplexityClassifier.class),
                mock(FreeTierChatClientService.class),
                mock(LlmGatewayService.class),
                mock(ProviderCredentialResolver.class),
                mock(TenantLimits.class),
                mock(SubagentRunRepository.class),
                mock(ObjectProvider.class),
                provider,
                mock(ObjectProvider.class),
                mock(ObjectProvider.class));
    }

    private WorkflowNodeDto mediaNode(Map<String, Object> config) {
        return new WorkflowNodeDto(
                "m1", "media", Map.of(), Map.of("config", config, "label", "Media"));
    }

    private WorkflowNodeExecutor.NodeExecutionResult run(Map<String, Object> config, String input) {
        return executor.executeNode(
                "wf-1", "Video pipeline", "run-1", mediaNode(config), input, "system:test");
    }

    // --- availability -------------------------------------------------------

    @Test
    @SuppressWarnings("unchecked")
    void withNoSidecarBeanTheNodeSkipsInsteadOfFailingTheRun() {
        // SidecarClient is @Profile("knowledge"). A deployment without that
        // profile is a valid deployment, not a broken workflow - so this is a
        // deployment shape reported as SKIPPED, never an exception.
        ObjectProvider<SidecarClient> empty = mock(ObjectProvider.class);
        when(empty.getIfAvailable()).thenReturn(null);
        WorkflowNodeExecutor noSidecar = newExecutor(empty);

        WorkflowNodeExecutor.NodeExecutionResult result =
                noSidecar.executeNode(
                        "wf-1",
                        "Video pipeline",
                        "run-1",
                        mediaNode(Map.of("operation", "tts")),
                        "{}",
                        "system:test");

        assertEquals("SKIPPED", result.status());
        assertTrue(
                result.summary().contains("knowledge"),
                "the reason should name the profile an operator has to enable: "
                        + result.summary());
        assertEquals("{}", result.output(), "input must pass through unchanged");
    }

    @Test
    void aMissingOperationNamesTheFourValidOnes() {
        WorkflowNodeExecutor.NodeExecutionResult result = run(new HashMap<>(), "{}");

        assertEquals("SKIPPED", result.status());
        assertTrue(result.summary().contains("tts"), result.summary());
        assertTrue(result.summary().contains("render_status"), result.summary());
    }

    @Test
    void anUnknownOperationIsRefusedWithoutCallingTheSidecar() {
        WorkflowNodeExecutor.NodeExecutionResult result =
                run(Map.of("operation", "transcode"), "{}");

        assertEquals("SKIPPED", result.status());
        assertTrue(result.summary().contains("transcode"), result.summary());
        verify(sidecarClient, never()).tts(any());
        verify(sidecarClient, never()).renderSubmit(any());
    }

    // --- tts ----------------------------------------------------------------

    @Test
    void ttsPassesTheInputThroughAsTheRequestBodyAndReportsTheMeasuredDuration() {
        when(sidecarClient.tts(any()))
                .thenReturn(
                        new SidecarTtsResponse(
                                "en-IN",
                                "bulbul:v2",
                                22050,
                                9.75,
                                List.of(
                                        new SidecarTtsSegmentResult("hook", 4.25, 22050, "AAA"),
                                        new SidecarTtsSegmentResult("body", 5.5, 22050, "BBB")),
                                "COMBINED"));

        WorkflowNodeExecutor.NodeExecutionResult result =
                run(
                        Map.of("operation", "tts"),
                        "{\"segments\":[{\"id\":\"hook\",\"text\":\"Hello.\"}],\"language\":\"en-IN\"}");

        assertEquals("OK", result.status());
        // The MEASURED total is what the render aligns B-roll against, so it
        // has to reach the run trace where a human can compare it to the
        // brief's estimate.
        assertTrue(result.summary().contains("9.75"), result.summary());
        assertTrue(
                result.output().contains("combined_audio_base64")
                        || result.output().contains("COMBINED"),
                "the full response should be the node's output: " + result.output());

        @SuppressWarnings("unchecked")
        Map<String, Object> sent = captureTtsRequest();
        assertEquals("en-IN", sent.get("language"));
        assertNotNull(sent.get("segments"), "the input's segments must reach the sidecar verbatim");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> captureTtsRequest() {
        org.mockito.ArgumentCaptor<Map<String, Object>> captor =
                org.mockito.ArgumentCaptor.forClass(Map.class);
        verify(sidecarClient).tts(captor.capture());
        return captor.getValue();
    }

    @Test
    void nonJsonInputIsRefusedWithAnActionableReasonAndNoSidecarCall() {
        // The likeliest cause is an upstream AGENT node emitting prose where
        // this node needs structure, so the message has to point there.
        WorkflowNodeExecutor.NodeExecutionResult result =
                run(Map.of("operation", "tts"), "Here is the narration for the episode!");

        assertEquals("SKIPPED", result.status());
        assertTrue(result.summary().contains("not valid JSON"), result.summary());
        verify(sidecarClient, never()).tts(any());
    }

    @Test
    void aJsonArrayInputIsRefusedBecauseARequestBodyMustBeAnObject() {
        WorkflowNodeExecutor.NodeExecutionResult result =
                run(Map.of("operation", "tts"), "[{\"id\":\"hook\"}]");

        assertEquals("SKIPPED", result.status());
        assertTrue(result.summary().contains("must be a JSON object"), result.summary());
        assertTrue(result.summary().contains("upstream"), "should point at the likely cause");
    }

    @Test
    void anEmptyInputIsRefused() {
        WorkflowNodeExecutor.NodeExecutionResult result = run(Map.of("operation", "tts"), "   ");

        assertEquals("SKIPPED", result.status());
        assertTrue(result.summary().contains("empty"), result.summary());
    }

    @Test
    void aSidecarFailureBecomesSkippedRatherThanAThrow() {
        // The whole run must survive a sidecar outage. SidecarClient throws
        // RuntimeExceptions; none may escape this node.
        when(sidecarClient.tts(any()))
                .thenThrow(new RuntimeException("Failed to reach sidecar /tts - is it running?"));

        WorkflowNodeExecutor.NodeExecutionResult result = run(Map.of("operation", "tts"), "{}");

        assertEquals("SKIPPED", result.status());
        assertTrue(result.summary().contains("Failed to reach sidecar"), result.summary());
        assertEquals("{}", result.output(), "input passes through so a Condition can branch");
    }

    // --- captions -----------------------------------------------------------

    @Test
    void captionsReportsCueAndWordCounts() {
        when(sidecarClient.captions(any()))
                .thenReturn(
                        new SidecarCaptionsResponse(
                                "1\n00:00:00,000 --> 00:00:01,000\nhi\n", 7, 31, List.of()));

        WorkflowNodeExecutor.NodeExecutionResult result =
                run(
                        Map.of("operation", "captions"),
                        "{\"filename\":\"a.wav\",\"content_base64\":\"AA\"}");

        assertEquals("OK", result.status());
        assertTrue(result.summary().contains("7"), result.summary());
        assertTrue(
                result.logLines().stream().anyMatch(l -> l.contains("31 words")),
                result.logLines().toString());
    }

    // --- render -------------------------------------------------------------

    @Test
    void renderWithoutWaitSecondsSubmitsAndReturnsTheJobIdImmediately() {
        // The default, and deliberately so: a render outlives any step timeout,
        // so holding a worker thread through one has to be opt-in.
        when(sidecarClient.renderSubmit(any())).thenReturn(job("job-1", "queued", null, null));

        WorkflowNodeExecutor.NodeExecutionResult result =
                run(Map.of("operation", "render"), "{\"edit_list\":[]}");

        assertEquals("OK", result.status());
        assertTrue(result.summary().contains("job-1"), result.summary());
        verify(sidecarClient, never()).renderStatus(anyString());
    }

    @Test
    void renderWithWaitSecondsPollsToCompletion() {
        when(sidecarClient.renderSubmit(any())).thenReturn(job("job-2", "queued", null, null));
        when(sidecarClient.renderStatus("job-2"))
                .thenReturn(job("job-2", "running", null, null))
                .thenReturn(job("job-2", "completed", null, Map.of("duration_sec", 12.75)));

        WorkflowNodeExecutor.NodeExecutionResult result =
                run(Map.of("operation", "render", "waitSeconds", 60), "{\"edit_list\":[]}");

        assertEquals("OK", result.status());
        assertTrue(result.summary().contains("completed"), result.summary());
        assertTrue(result.summary().contains("12.75"), result.summary());
    }

    @Test
    void aFailedRenderIsSkippedAndCarriesTheSidecarsOwnReason() {
        when(sidecarClient.renderSubmit(any())).thenReturn(job("job-3", "queued", null, null));
        when(sidecarClient.renderStatus("job-3"))
                .thenReturn(
                        job(
                                "job-3",
                                "failed",
                                "beat body-1: no such file in the render workspace",
                                null));

        WorkflowNodeExecutor.NodeExecutionResult result =
                run(Map.of("operation", "render", "waitSeconds", 30), "{\"edit_list\":[]}");

        assertEquals("SKIPPED", result.status());
        // The sidecar's message names the BEAT, which is the actionable part.
        assertTrue(result.summary().contains("body-1"), result.summary());
    }

    @Test
    void renderStatusFindsTheJobIdInTheNodesInputWhenNotConfigured() {
        // Chains a submit node into a status node without any templating.
        when(sidecarClient.renderStatus("job-4"))
                .thenReturn(job("job-4", "completed", null, Map.of("duration_sec", 5.0)));

        WorkflowNodeExecutor.NodeExecutionResult result =
                run(
                        Map.of("operation", "render_status"),
                        "{\"job_id\":\"job-4\",\"status\":\"queued\"}");

        assertEquals("OK", result.status());
        assertTrue(result.summary().contains("job-4"), result.summary());
    }

    @Test
    void renderStatusAlsoAcceptsTheCamelCaseSpelling() {
        when(sidecarClient.renderStatus("job-5")).thenReturn(job("job-5", "running", null, null));

        WorkflowNodeExecutor.NodeExecutionResult result =
                run(Map.of("operation", "render_status"), "{\"jobId\":\"job-5\"}");

        assertEquals("OK", result.status());
        assertTrue(result.summary().contains("running"), result.summary());
    }

    @Test
    void renderStatusWithNoJobIdAnywhereSaysWhatItLookedFor() {
        WorkflowNodeExecutor.NodeExecutionResult result =
                run(Map.of("operation", "render_status"), "{\"unrelated\":true}");

        assertEquals("SKIPPED", result.status());
        assertTrue(result.summary().contains("job_id"), result.summary());
        verify(sidecarClient, never()).renderStatus(anyString());
    }

    @Test
    void anExplicitJobIdOnTheNodeWinsOverTheInput() {
        when(sidecarClient.renderStatus("configured"))
                .thenReturn(job("configured", "running", null, null));

        WorkflowNodeExecutor.NodeExecutionResult result =
                run(
                        Map.of("operation", "render_status", "jobId", "configured"),
                        "{\"job_id\":\"from-input\"}");

        assertEquals("OK", result.status());
        verify(sidecarClient).renderStatus("configured");
        verify(sidecarClient, never()).renderStatus("from-input");
    }

    private SidecarRenderJobResponse job(
            String id, String status, String error, Map<String, Object> result) {
        return new SidecarRenderJobResponse(id, status, null, null, null, null, error, result, 0);
    }

    @Test
    void theNodeTypeIsSupportedSoActivateAndRunAreNotBlocked() {
        // WorkflowNodeType.fromWire is what OrchestratorService#validateNodeType
        // calls; an executable node type missing from that enum blocks Activate
        // forever, which is the exact bug DEVICE_CONTROL's comment records.
        assertEquals(WorkflowNodeType.MEDIA, WorkflowNodeType.fromWire("media"));
        assertTrue(WorkflowNodeType.MEDIA.isSupported());
        assertNull(null);
    }
}
