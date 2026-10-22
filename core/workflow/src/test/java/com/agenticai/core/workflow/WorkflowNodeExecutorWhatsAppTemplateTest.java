package com.agenticai.core.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
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
import com.agenticai.core.chat.gateway.LlmGatewayService;
import com.agenticai.core.devices.DeviceCommandService;
import com.agenticai.core.eventbus.WorkflowEventPublisher;
import com.agenticai.core.providers.ProviderCredentialResolver;
import com.agenticai.core.security.TenantLimits;
import com.agenticai.core.workflow.dto.WorkflowNodeDto;
import com.agenticai.core.workflow.output.EmailOutputService;
import com.agenticai.core.workflow.output.GoogleDriveOutputService;
import com.agenticai.core.workflow.output.PdfOutputRenderer;
import com.agenticai.core.workflow.output.TeamsOutputService;
import com.agenticai.core.workflow.output.TelegramOutputService;
import com.agenticai.core.workflow.output.WhatsAppOutputService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/**
 * The Output node's WhatsApp branch: an approved template is sent only when the feature flag is on
 * AND the node names one; otherwise it is the plain-text send it always was, now with an optional
 * explicit message.
 */
class WorkflowNodeExecutorWhatsAppTemplateTest {

    private WhatsAppOutputService whatsApp;
    private WorkflowNodeExecutor executor;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        whatsApp = mock(WhatsAppOutputService.class);
        executor =
                NodeExecutorTestFactory.create(
                        mock(OrchestratorService.class),
                        mock(AgentChatService.class),
                        mock(AgentService.class),
                        mock(ObjectProvider.class),
                        mock(WorkflowEventPublisher.class),
                        mock(EmailOutputService.class),
                        mock(TelegramOutputService.class),
                        mock(TeamsOutputService.class),
                        whatsApp,
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
                        mock(ObjectProvider.class),
                        mock(ObjectProvider.class),
                        mock(ObjectProvider.class));
        when(whatsApp.send(anyString(), any(), anyString(), anyString())).thenReturn("Sent text.");
        when(whatsApp.sendTemplate(
                        anyString(),
                        anyString(),
                        anyString(),
                        any(),
                        any(),
                        anyString(),
                        anyString()))
                .thenReturn("Sent template.");
    }

    @AfterEach
    void clearScope() {
        ExpressionScopeContext.clear();
    }

    private static WorkflowNodeDto output(Map<String, Object> config) {
        return new WorkflowNodeDto(
                "wa", "output", Map.of(), Map.of("config", config, "label", "Remind"));
    }

    private static Map<String, Object> templated() {
        return Map.of(
                "channel",
                "whatsapp",
                "whatsappTo",
                "{{ $json.phone }}",
                "whatsappMessage",
                "Hi {{ $json.name }}, your invoice is due.",
                "whatsappTemplate",
                Map.of("name", "invoice_reminder", "language", "en_US"),
                "whatsappTemplateParams",
                List.of("{{ $json.name }}", "INV-{{ $json.number }}"));
    }

    private static final String ITEM =
            "{\"phone\":\"+15550001111\",\"name\":\"Asha\",\"number\":\"42\"}";

    private WorkflowNodeExecutor.NodeExecutionResult run(WorkflowNodeDto node, String input) {
        return executor.executeNode("wf-1", "wf", "run-1", node, input, "tester");
    }

    @Test
    void flagOnSendsTheTemplateWithResolvedParamsAndTheMessageAsFallback() {
        executor.setWhatsAppTemplatesEnabled(true);

        WorkflowNodeExecutor.NodeExecutionResult r = run(output(templated()), ITEM);

        assertEquals("OK", r.status());
        assertEquals("Sent template.", r.summary());
        assertEquals(ITEM, r.output(), "the node still passes its input on");
        verify(whatsApp)
                .sendTemplate(
                        "+15550001111",
                        "invoice_reminder",
                        "en_US",
                        List.of("Asha", "INV-42"),
                        "Hi Asha, your invoice is due.",
                        "run-1",
                        "wa");
        verify(whatsApp, never()).send(anyString(), any(), anyString(), anyString());
    }

    @Test
    void flagOffIgnoresTheTemplateAndSendsPlainText() {
        WorkflowNodeExecutor.NodeExecutionResult r = run(output(templated()), ITEM);

        assertEquals("Sent text.", r.summary());
        verify(whatsApp).send("+15550001111", "Hi Asha, your invoice is due.", "run-1", "wa");
        verify(whatsApp, never())
                .sendTemplate(
                        anyString(),
                        anyString(),
                        anyString(),
                        any(),
                        any(),
                        anyString(),
                        anyString());
    }

    @Test
    void withoutAMessageTheNodeInputIsSentExactlyAsBefore() {
        executor.setWhatsAppTemplatesEnabled(true);
        run(output(Map.of("channel", "whatsapp", "whatsappTo", "+1555")), "the reply body");
        verify(whatsApp).send("+1555", "the reply body", "run-1", "wa");

        // A blank message and an empty / half-filled template both mean "not set".
        run(
                output(
                        Map.of(
                                "channel",
                                "whatsapp",
                                "whatsappTo",
                                "+1556",
                                "whatsappMessage",
                                "  ",
                                "whatsappTemplate",
                                Map.of("name", "invoice_reminder"))),
                "body two");
        verify(whatsApp).send("+1556", "body two", "run-1", "wa");
        verify(whatsApp, never())
                .sendTemplate(
                        anyString(),
                        anyString(),
                        anyString(),
                        any(),
                        any(),
                        anyString(),
                        anyString());
    }

    @Test
    void templateRefParsesTheNodeConfig() {
        WhatsAppTemplateRef ref =
                WhatsAppTemplateRef.of(
                        output(
                                Map.of(
                                        "whatsappTemplate",
                                                Map.of(
                                                        "name",
                                                        " invoice_reminder ",
                                                        "language",
                                                        "en_US"),
                                        "whatsappTemplateParams", List.of("a", 7))));
        assertEquals(new WhatsAppTemplateRef("invoice_reminder", "en_US", List.of("a", "7")), ref);
        assertTrue(
                WhatsAppTemplateRef.of(
                                output(
                                        Map.of(
                                                "whatsappTemplate",
                                                Map.of("name", "t", "language", "en"))))
                        .params()
                        .isEmpty());
        assertNull(WhatsAppTemplateRef.of(output(Map.of("whatsappTemplate", "invoice_reminder"))));
        assertNull(
                WhatsAppTemplateRef.of(
                        output(Map.of("whatsappTemplate", Map.of("name", "t", "language", " ")))));
        assertNull(WhatsAppTemplateRef.of(output(Map.of())));
    }

    @Test
    void aDryRunReportsTheTemplateOnlyWhenARealRunWouldSendIt() {
        ExpressionScope dryRun = ExpressionScope.forMode(null, RunMode.DRAFT_DRY_RUN);
        WorkflowNodeDto resolved =
                output(
                        Map.of(
                                "channel",
                                "whatsapp",
                                "whatsappTo",
                                "+1555",
                                "whatsappMessage",
                                "Hi Asha",
                                "whatsappTemplate",
                                Map.of("name", "invoice_reminder", "language", "en_US"),
                                "whatsappTemplateParams",
                                List.of("Asha")));

        assertEquals(
                "DRY RUN - would send the WhatsApp template \"invoice_reminder\" (en_US) to +1555: variables [Asha]",
                WorkflowDryRun.simulateIfDryRun(resolved, "in", dryRun, true).summary());
        assertEquals(
                "DRY RUN - would send a WhatsApp message to +1555: Hi Asha",
                WorkflowDryRun.simulateIfDryRun(resolved, "in", dryRun, false).summary());

        executor.setWhatsAppTemplatesEnabled(true);
        ExpressionScopeContext.bind(dryRun);
        assertTrue(
                run(resolved, "in")
                        .summary()
                        .startsWith("DRY RUN - would send the WhatsApp template"));
        verifyNoInteractions(whatsApp);
    }
}
