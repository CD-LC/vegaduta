package com.agenticai.core.agents.tools.code;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.approval.ApprovalEvaluation;
import com.agenticai.core.approval.HumanApprovalGateService;
import com.agenticai.core.json.JsonMappers;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Plain JUnit 5 + Mockito, same posture as {@code CustomCodeToolServiceTest}. What is under test is
 * this callback's own contract: the arguments it demands, the "create DISABLED, enable only via a
 * human approval" wiring (the structural gate that no agent autonomy tier can bypass - see the
 * class javadoc), and the never-throw-into-the-turn failure surface.
 */
class CreateCustomToolToolCallbackTest {

    private CustomCodeToolService service;
    private HumanApprovalGateService gate;
    private ObjectProvider<HumanApprovalGateService> gateProvider;
    private CreateCustomToolToolCallback callback;

    @BeforeEach
    void setUp() {
        service = mock(CustomCodeToolService.class);
        gate = mock(HumanApprovalGateService.class);
        gateProvider = mock(ObjectProvider.class);
        when(gateProvider.getIfAvailable()).thenReturn(gate);
        callback = new CreateCustomToolToolCallback(service, gateProvider);
    }

    private static Map<String, Object> validArgs() {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("tool_name", "csv_to_markdown");
        args.put("description", "Converts CSV rows to a markdown table");
        args.put("language", "python");
        args.put("source_code", "def run(input):\n    return {}");
        return args;
    }

    // A mock, not a constructed entity: the id is JPA-assigned at persist and
    // the callback dereferences it, so a hand-built instance cannot carry one.
    private static CustomCodeTool toolOf(CustomCodeToolStatus status) {
        CustomCodeTool tool = mock(CustomCodeTool.class);
        when(tool.getId()).thenReturn(UUID.randomUUID());
        when(tool.getToolName()).thenReturn("csv_to_markdown");
        when(tool.getLanguage()).thenReturn(CustomCodeToolLanguage.PYTHON);
        when(tool.getStatus()).thenReturn(status);
        if (status == CustomCodeToolStatus.REJECTED) {
            when(tool.getValidationError()).thenReturn("Import of `os` is not permitted.");
        }
        return tool;
    }

    private static String json(Map<String, Object> args) throws Exception {
        return JsonMappers.plain().writeValueAsString(args);
    }

    // ------------------------------------------------------------------ //
    // Schema the model sees
    // ------------------------------------------------------------------ //

    @Test
    void schemaNamesEveryRequiredArgumentAndAllFourLanguages() {
        var def = callback.getToolDefinition();
        assertThat(def.name()).isEqualTo("create_custom_tool");
        assertThat(def.inputSchema())
                .contains("\"tool_name\"")
                .contains("\"source_code\"")
                .contains("java")
                .contains("python")
                .contains("javascript")
                .contains("typescript");
        // The description is what a model reads first - it must state the
        // human-approval step, not hide it.
        assertThat(def.description()).contains("approve");
    }

    // ------------------------------------------------------------------ //
    // Argument validation - returns text, never throws
    // ------------------------------------------------------------------ //

    @Test
    void missingRequiredFieldsExplainThemselves() throws Exception {
        String out = callback.call(json(Map.of("language", "python")));
        assertThat(out).contains("tool_name");
        verify(service, never())
                .create(
                        anyString(),
                        any(),
                        anyString(),
                        any(),
                        anyString(),
                        any(),
                        any(),
                        any(),
                        any(),
                        eq(false));
    }

    @Test
    void unknownLanguageIsNamedAndRejected() throws Exception {
        Map<String, Object> args = validArgs();
        args.put("language", "cobol");
        String out = callback.call(json(args));
        assertThat(out).contains("cobol").contains("typescript");
    }

    // ------------------------------------------------------------------ //
    // The approval contract
    // ------------------------------------------------------------------ //

    @Test
    void validatedToolIsCreatedDisabledAndAnApprovalIsRequested() throws Exception {
        CustomCodeTool tool = toolOf(CustomCodeToolStatus.VALIDATED);
        when(service.create(
                        anyString(),
                        any(),
                        anyString(),
                        any(),
                        anyString(),
                        any(),
                        any(),
                        any(),
                        any(),
                        eq(false)))
                .thenReturn(tool);
        when(gate.evaluateInTurn(
                        eq(CustomCodeToolEnableApprovalExecutor.ACTION_TYPE),
                        eq("create_custom_tool"),
                        anyString(),
                        anyString(),
                        any()))
                .thenReturn(ApprovalEvaluation.pending(UUID.randomUUID()));

        String out = callback.call(json(validArgs()));

        // The draft is disabled - nothing model-written is callable yet.
        verify(service)
                .create(
                        anyString(),
                        any(),
                        anyString(),
                        any(),
                        anyString(),
                        any(),
                        any(),
                        any(),
                        any(),
                        eq(false));
        verify(gate)
                .evaluateInTurn(
                        eq("custom_code_tool.enable"),
                        eq("create_custom_tool"),
                        anyString(),
                        org.mockito.ArgumentMatchers.contains("toolId"),
                        any());
        assertThat(out).contains("waiting for a person's approval").contains("csv_to_markdown");
    }

    @Test
    void autoApprovedPolicyEnablesImmediately() throws Exception {
        CustomCodeTool tool = toolOf(CustomCodeToolStatus.VALIDATED);
        when(service.create(
                        anyString(),
                        any(),
                        anyString(),
                        any(),
                        anyString(),
                        any(),
                        any(),
                        any(),
                        any(),
                        eq(false)))
                .thenReturn(tool);
        when(gate.evaluateInTurn(anyString(), anyString(), anyString(), anyString(), any()))
                .thenReturn(ApprovalEvaluation.autoApproved());

        String out = callback.call(json(validArgs()));

        assertThat(out).contains("enabled").contains("csv_to_markdown");
    }

    @Test
    void noApprovalBeanStillLeavesTheToolSafelyDisabled() throws Exception {
        when(gateProvider.getIfAvailable()).thenReturn(null);
        CustomCodeTool tool = toolOf(CustomCodeToolStatus.VALIDATED);
        when(service.create(
                        anyString(),
                        any(),
                        anyString(),
                        any(),
                        anyString(),
                        any(),
                        any(),
                        any(),
                        any(),
                        eq(false)))
                .thenReturn(tool);

        String out = callback.call(json(validArgs()));

        // Degrades to "disabled until a person enables it in the editor" - never
        // "enabled without review".
        assertThat(out).contains("stays disabled");
    }

    @Test
    void rejectedToolIsSavedDisabledAndExplainsWhy() throws Exception {
        CustomCodeTool tool = toolOf(CustomCodeToolStatus.REJECTED);
        when(service.create(
                        anyString(),
                        any(),
                        anyString(),
                        any(),
                        anyString(),
                        any(),
                        any(),
                        any(),
                        any(),
                        eq(false)))
                .thenReturn(tool);

        String out = callback.call(json(validArgs()));

        assertThat(out).contains("failed verification").contains("Import of `os`");
        // No approval is requested for a program that cannot run anyway.
        verify(gate, never())
                .evaluateInTurn(anyString(), anyString(), anyString(), anyString(), any());
    }

    @Test
    void sandboxDownReturnsAPlainExplanation() throws Exception {
        when(service.create(
                        anyString(),
                        any(),
                        anyString(),
                        any(),
                        anyString(),
                        any(),
                        any(),
                        any(),
                        any(),
                        eq(false)))
                .thenThrow(new ToolSandboxUnavailableException("no sandbox on this deployment"));

        String out = callback.call(json(validArgs()));
        assertThat(out).contains("tool-sandbox service is unavailable");
    }
}
