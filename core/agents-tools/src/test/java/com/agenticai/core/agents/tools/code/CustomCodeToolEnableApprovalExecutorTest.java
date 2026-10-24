package com.agenticai.core.agents.tools.code;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.approval.ApprovalRequest;
import com.agenticai.core.approval.ApprovalRequestRepository;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

/**
 * The approval-side half of {@code create_custom_tool}: this executor is the ONLY path that turns a
 * model-authored draft into a workspace-wide callable tool, so what it does on approve - and what
 * it deliberately does NOT do on reject - is the feature's security contract.
 */
class CustomCodeToolEnableApprovalExecutorTest {

    private CustomCodeToolService service;
    private ApprovalRequestRepository requestRepository;
    private CustomCodeToolEnableApprovalExecutor executor;

    @BeforeEach
    void setUp() {
        service = mock(CustomCodeToolService.class);
        requestRepository = mock(ApprovalRequestRepository.class);
        executor =
                new CustomCodeToolEnableApprovalExecutor(
                        service, requestRepository, mock(ApplicationEventPublisher.class));
    }

    private static ApprovalRequest requestOf(String payloadJson) {
        return new ApprovalRequest(
                "custom_code_tool.enable",
                "Enable model-authored tool 'csv_to_markdown'",
                payloadJson,
                "agent");
    }

    @Test
    void actionTypeIsTheDefaultGatedPattern() {
        // The name must match ApprovalPolicyService.DEFAULT_SENSITIVE_PATTERNS verbatim - a typo
        // here would silently auto-approve every model-authored tool.
        assertThat(executor.actionType()).isEqualTo("custom_code_tool.enable");
    }

    @Test
    void approvingEnablesTheToolAndRecordsWhatHappened() {
        UUID toolId = UUID.randomUUID();
        ApprovalRequest request =
                requestOf("{\"toolId\": \"" + toolId + "\", \"toolName\": \"csv_to_markdown\"}");
        CustomCodeTool tool =
                new CustomCodeTool(
                        "csv_to_markdown",
                        "CSV to Markdown",
                        "desc",
                        CustomCodeToolLanguage.PYTHON,
                        "def run(input):\n    return {}",
                        null,
                        null,
                        "sha",
                        5000,
                        128,
                        "agent");
        tool.markValidated();
        tool.setEnabled(true);
        when(service.setEnabled(toolId, true)).thenReturn(tool);

        executor.execute(request);

        verify(service).setEnabled(toolId, true);
        verify(requestRepository).save(request);
        assertThat(request.getExecutionResult()).contains("csv_to_markdown");
    }

    @Test
    void aPayloadWithoutAToolIdFailsLoudly() {
        ApprovalRequest request = requestOf("{\"toolName\": \"csv_to_markdown\"}");
        assertThatThrownBy(() -> executor.execute(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("toolId");
        verify(service, never()).setEnabled(any(), any(Boolean.class));
    }

    @Test
    void rejectingLeavesTheDraftAlone() {
        ApprovalRequest request = requestOf("{\"toolId\": \"" + UUID.randomUUID() + "\"}");
        executor.onRejected(request, "admin", "not now");
        // The draft stays disabled and intact - the tenant can still fix or
        // delete it from the editor; a rejection must not destroy work.
        verify(service, never()).setEnabled(any(), any(Boolean.class));
    }
}
