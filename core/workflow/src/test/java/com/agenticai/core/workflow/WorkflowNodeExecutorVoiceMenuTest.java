package com.agenticai.core.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.agents.hosted.FreeTierChatClientService;
import com.agenticai.core.agents.tools.AgentToolProvider;
import com.agenticai.core.agents.tools.ToolCallLogRepository;
import com.agenticai.core.agents.turn.AgentChatService;
import com.agenticai.core.agents.turn.cache.LlmResponseCacheService;
import com.agenticai.core.agents.turn.quality.QueryComplexityClassifier;
import com.agenticai.core.approval.ApprovalReplayGrant;
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
import com.agenticai.core.workflow.voicemenu.WorkflowVoiceMenuRequest;
import com.agenticai.core.workflow.voicemenu.WorkflowVoiceMenuRequestService;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Dispatch-level coverage for the {@code voice_menu} node - {@link
 * WorkflowNodeTypeRegistrationTest} already asserts every supported type (VOICE_MENU included)
 * reaches a dispatch arm at all; this class checks what that arm actually DOES, mirroring how
 * {@link WorkflowVoiceMenuRequestService}'s halt/resume pair is meant to be driven by {@code
 * WorkflowNodeExecutor#executeVoiceMenuNode}.
 */
class WorkflowNodeExecutorVoiceMenuTest {

    @AfterEach
    void clearAnyLeakedGrant() {
        // Defense in depth: a failing assertion before a test's own
        // try/finally would otherwise leak the grant onto later tests on
        // this same thread (ApprovalReplayGrant is a ThreadLocal - see its
        // javadoc). Clearing an already-empty grant is a harmless no-op.
        ApprovalReplayGrant.clear();
    }

    @SuppressWarnings("unchecked")
    private WorkflowNodeExecutor newExecutor(
            WorkflowVoiceMenuRequestService voiceMenuRequestService) {
        ObjectProvider<WorkflowVoiceMenuRequestService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(voiceMenuRequestService);
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
                mock(ObjectProvider.class),
                mock(ObjectProvider.class),
                provider);
    }

    private static WorkflowNodeDto menuNode(Map<String, Object> config) {
        return new WorkflowNodeDto(
                "n1", "voice_menu", Map.of(), Map.of("config", config, "label", "Menu"));
    }

    @Test
    void firstExecutionHaltsAwaitingVoiceInputAndCreatesAPendingRequest() {
        WorkflowVoiceMenuRequestService service = mock(WorkflowVoiceMenuRequestService.class);
        WorkflowVoiceMenuRequest pending = mock(WorkflowVoiceMenuRequest.class);
        UUID requestId = UUID.randomUUID();
        when(pending.getId()).thenReturn(requestId);
        when(service.createPending(any(), any(), any(), any(), any(), any())).thenReturn(pending);

        WorkflowNodeExecutor executor = newExecutor(service);
        WorkflowNodeDto node = menuNode(Map.of("prompt", "Press 1 for sales, 2 for support"));

        WorkflowNodeExecutor.NodeExecutionResult result =
                executor.executeNode(
                        UUID.randomUUID().toString(),
                        "My IVR",
                        UUID.randomUUID().toString(),
                        node,
                        "hello",
                        "system:test");

        assertEquals(WorkflowRunStatus.AWAITING_VOICE_INPUT, result.haltRun());
        assertTrue(result.summary().contains(requestId.toString()));
    }

    @Test
    void whenTheVoiceMenuServiceIsUnavailableTheNodePassesInputThrough() {
        WorkflowNodeExecutor executor = newExecutor(null);
        WorkflowNodeDto node = menuNode(Map.of("prompt", "Press 1 for sales"));

        WorkflowNodeExecutor.NodeExecutionResult result =
                executor.executeNode(
                        UUID.randomUUID().toString(),
                        "My IVR",
                        UUID.randomUUID().toString(),
                        node,
                        "hello",
                        "system:test");

        assertNull(result.haltRun());
        assertEquals("hello", result.output());
    }

    @Test
    void resumeUnderTheReplayGrantReturnsTheMatchedBranchFromTheEnvelope() throws Exception {
        WorkflowNodeExecutor executor = newExecutor(mock(WorkflowVoiceMenuRequestService.class));
        WorkflowNodeDto node = menuNode(Map.of("prompt", "Press 1 for sales, 2 for support"));
        String resumeInput =
                com.agenticai.core.json.JsonMappers.plain()
                        .writeValueAsString(
                                Map.of(
                                        "digits",
                                        "2",
                                        "branch",
                                        "support",
                                        "input",
                                        "original upstream output"));

        ApprovalReplayGrant.grant(WorkflowVoiceMenuRequestService.REPLAY_ACTION_TYPE);
        WorkflowNodeExecutor.NodeExecutionResult result;
        try {
            result =
                    executor.executeNode(
                            UUID.randomUUID().toString(),
                            "My IVR",
                            UUID.randomUUID().toString(),
                            node,
                            resumeInput,
                            "system:test");
        } finally {
            ApprovalReplayGrant.clear();
        }

        assertNull(result.haltRun());
        assertEquals("support", result.branch());
        assertEquals(resumeInput, result.output());
    }

    @Test
    void aStaleReplayGrantForADifferentActionTypeDoesNotConsumeThisNodesHalt() {
        WorkflowVoiceMenuRequestService service = mock(WorkflowVoiceMenuRequestService.class);
        WorkflowVoiceMenuRequest pending = mock(WorkflowVoiceMenuRequest.class);
        when(pending.getId()).thenReturn(UUID.randomUUID());
        when(service.createPending(any(), any(), any(), any(), any(), any())).thenReturn(pending);
        WorkflowNodeExecutor executor = newExecutor(service);
        WorkflowNodeDto node = menuNode(Map.of("prompt", "Press 1 for sales"));

        ApprovalReplayGrant.grant("workflow.node.human_input");
        WorkflowNodeExecutor.NodeExecutionResult result;
        try {
            result =
                    executor.executeNode(
                            UUID.randomUUID().toString(),
                            "My IVR",
                            UUID.randomUUID().toString(),
                            node,
                            "hello",
                            "system:test");
        } finally {
            ApprovalReplayGrant.clear();
        }

        // A grant for a DIFFERENT action type must not be consumed by this
        // node - it should still halt normally, exactly as with no grant at all.
        assertEquals(WorkflowRunStatus.AWAITING_VOICE_INPUT, result.haltRun());
    }
}
