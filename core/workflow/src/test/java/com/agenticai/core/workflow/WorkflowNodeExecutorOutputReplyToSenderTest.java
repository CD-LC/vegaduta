package com.agenticai.core.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
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
import com.agenticai.core.omnichannel.trigger.InboundTriggerContext;
import com.agenticai.core.omnichannel.trigger.WorkflowTriggerChannel;
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
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Reply-to-sender for the WhatsApp and Telegram Output nodes: with no fixed destination the node
 * must send to whoever triggered this run via an inbound message on that channel (the {@link
 * WorkflowTriggerContextHolder} value set by {@code WorkflowTriggerPortImpl}), while an explicit
 * destination on the node always wins and a trigger context from a different channel is never used
 * as a destination.
 */
class WorkflowNodeExecutorOutputReplyToSenderTest {

    private WhatsAppOutputService whatsAppOutputService;
    private TelegramOutputService telegramOutputService;
    private WorkflowNodeExecutor executor;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        whatsAppOutputService = mock(WhatsAppOutputService.class);
        telegramOutputService = mock(TelegramOutputService.class);
        when(whatsAppOutputService.send(any(), any(), any(), any())).thenReturn("sent");
        when(telegramOutputService.send(any(), any(), any(), any())).thenReturn("sent");
        executor =
                NodeExecutorTestFactory.create(
                        mock(OrchestratorService.class),
                        mock(AgentChatService.class),
                        mock(AgentService.class),
                        mock(ObjectProvider.class),
                        mock(WorkflowEventPublisher.class),
                        mock(EmailOutputService.class),
                        telegramOutputService,
                        mock(TeamsOutputService.class),
                        whatsAppOutputService,
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
                        // 24th arg: ObjectProvider<SidecarClient> for the MEDIA node. A bare
                        // mock returns null from getIfAvailable(), i.e. "no sidecar in this
                        // context" - which is what every test here wants.
                        mock(ObjectProvider.class),
                        mock(ObjectProvider.class),
                        mock(ObjectProvider.class));
    }

    @AfterEach
    void clearTriggerContext() {
        WorkflowTriggerContextHolder.clear();
    }

    private WorkflowNodeDto outputNode(String channel, String destinationKey, String destination) {
        Map<String, Object> config = new HashMap<>();
        config.put("channel", channel);
        if (destination != null) {
            config.put(destinationKey, destination);
        }
        return new WorkflowNodeDto(
                "n1", "output", Map.of(), Map.of("config", config, "label", "Reply"));
    }

    private String whatsAppDestination(String whatsappTo) {
        executor.executeNode(
                "wf-1",
                "My workflow",
                "run-1",
                outputNode("whatsapp", "whatsappTo", whatsappTo),
                "the reply body",
                "system:test");
        ArgumentCaptor<String> to = ArgumentCaptor.forClass(String.class);
        verify(whatsAppOutputService)
                .send(to.capture(), eq("the reply body"), eq("run-1"), eq("n1"));
        return to.getValue();
    }

    private String telegramDestination(String telegramChatId) {
        executor.executeNode(
                "wf-1",
                "My workflow",
                "run-1",
                outputNode("telegram", "telegramChatId", telegramChatId),
                "the reply body",
                "system:test");
        ArgumentCaptor<String> to = ArgumentCaptor.forClass(String.class);
        verify(telegramOutputService)
                .send(to.capture(), eq("the reply body"), eq("run-1"), eq("n1"));
        return to.getValue();
    }

    // --- WhatsApp -----------------------------------------------------------

    @Test
    void whatsAppExplicitNumberIsUsedVerbatimEvenWithATriggerSender() {
        WorkflowTriggerContextHolder.set(
                new InboundTriggerContext(WorkflowTriggerChannel.WHATSAPP, "+15550000001"));
        assertEquals("+15559999999", whatsAppDestination("+15559999999"));
    }

    @Test
    void whatsAppBlankNumberFallsBackToTheTriggeringSender() {
        WorkflowTriggerContextHolder.set(
                new InboundTriggerContext(WorkflowTriggerChannel.WHATSAPP, "+15550000001"));
        assertEquals("+15550000001", whatsAppDestination(null));
    }

    @Test
    void whatsAppNeverUsesANonWhatsAppTriggerContext() {
        WorkflowTriggerContextHolder.set(
                new InboundTriggerContext(WorkflowTriggerChannel.TELEGRAM, "998877"));
        assertNull(whatsAppDestination(null));
    }

    @Test
    void whatsAppBlankNumberWithNoTriggerContextResolvesToNoDestination() {
        assertNull(whatsAppDestination(null));
    }

    // --- Telegram -----------------------------------------------------------

    @Test
    void telegramExplicitChatIdIsUsedVerbatimEvenWithATriggerSender() {
        WorkflowTriggerContextHolder.set(
                new InboundTriggerContext(WorkflowTriggerChannel.TELEGRAM, "111222"));
        assertEquals("999888", telegramDestination("999888"));
    }

    @Test
    void telegramBlankChatIdFallsBackToTheTriggeringChat() {
        WorkflowTriggerContextHolder.set(
                new InboundTriggerContext(WorkflowTriggerChannel.TELEGRAM, "111222"));
        assertEquals("111222", telegramDestination(null));
    }

    @Test
    void telegramNeverUsesANonTelegramTriggerContext() {
        WorkflowTriggerContextHolder.set(
                new InboundTriggerContext(WorkflowTriggerChannel.WHATSAPP, "+15550000001"));
        assertNull(telegramDestination(null));
    }

    @Test
    void telegramBlankChatIdWithNoTriggerContextResolvesToNoDestination() {
        assertNull(telegramDestination(null));
    }

    // --- Trace --------------------------------------------------------------

    @Test
    void traceNotesWhenItRepliesToTheTriggeringSender() {
        WorkflowTriggerContextHolder.set(
                new InboundTriggerContext(WorkflowTriggerChannel.WHATSAPP, "+15550000001"));
        WorkflowNodeExecutor.NodeExecutionResult result =
                executor.executeNode(
                        "wf-1",
                        "My workflow",
                        "run-1",
                        outputNode("whatsapp", "whatsappTo", null),
                        "body",
                        "system:test");
        assertTrue(
                result.logLines().stream()
                        .anyMatch(l -> l.toLowerCase(Locale.ROOT).contains("sender")),
                "expected a trace line explaining the reply went to the triggering sender");
    }

    // --- file output name (V21 output_file_name is VARCHAR(255))

    @Test
    void aDataDrivenFileNameIsClampedAndStrippedOfPathsBeforeItReachesTheRunRow() {
        String longName = "report-" + "x".repeat(400) + ".pdf";
        WorkflowNodeDto node =
                new WorkflowNodeDto(
                        "n1",
                        "output",
                        Map.of(),
                        Map.of(
                                "config",
                                Map.of("channel", "file", "fileName", longName),
                                "label",
                                "Save"));
        WorkflowNodeExecutor.NodeExecutionResult r =
                executor.executeNode("wf-1", "My workflow", "run-1", node, "body", "system:test");
        assertTrue(r.fileName().length() <= 200, "was " + r.fileName().length());
        assertTrue(r.fileName().endsWith(".pdf"));

        WorkflowNodeDto traversal =
                new WorkflowNodeDto(
                        "n1",
                        "output",
                        Map.of(),
                        Map.of(
                                "config",
                                Map.of("channel", "file", "fileName", "../../etc/pass\nwd"),
                                "label",
                                "Save"));
        String safe =
                executor.executeNode(
                                "wf-1", "My workflow", "run-1", traversal, "body", "system:test")
                        .fileName();
        assertTrue(!safe.contains("/") && !safe.contains("\n"), safe);
    }

    @Test
    void safeFileNameFallsBackWhenNothingUsableIsLeft() {
        assertEquals(
                "workflow-output.txt", OutputNodeHandler.safeFileName("  ", "workflow-output.txt"));
        assertEquals(
                "workflow-output.txt",
                OutputNodeHandler.safeFileName("///", "workflow-output.txt"));
    }
}
