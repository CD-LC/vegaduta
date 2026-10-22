package com.agenticai.core.workflow;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.approval.HumanApprovalGateService;
import com.agenticai.core.workflow.dto.WorkflowEdgeDto;
import com.agenticai.core.workflow.dto.WorkflowGraphDto;
import com.agenticai.core.workflow.dto.WorkflowNodeDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Save/activate/run-time validation of the two flag-gated wfcontrol constructs. The property that
 * matters: with a flag off, a graph that uses the construct is REJECTED with a clear message - it
 * is never silently skipped (a Wait that does not wait) or silently run as something else (a form
 * that is just a webhook).
 */
class OrchestratorServiceWfControlTest {

    private OrchestratorService orchestrator;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        orchestrator =
                new OrchestratorService(
                        mock(AgentService.class),
                        mock(HumanApprovalGateService.class),
                        new ObjectMapper(),
                        mock(ObjectProvider.class));
    }

    private static WorkflowNodeDto node(String id, String type, Map<String, Object> config) {
        return new WorkflowNodeDto(id, type, Map.of(), Map.of("config", config, "label", id));
    }

    private static WorkflowGraphDto waitGraph(Map<String, Object> waitConfig) {
        return new WorkflowGraphDto(
                List.of(
                        node("t", "trigger", Map.of("channel", "WEBHOOK")),
                        node("w", "wait", waitConfig)),
                List.of(new WorkflowEdgeDto("e1", "t", "w", null, null)));
    }

    private static Map<String, Object> formTrigger() {
        Map<String, Object> form = new HashMap<>();
        form.put("fields", List.of(Map.of("key", "name", "type", "text")));
        Map<String, Object> config = new HashMap<>();
        config.put("channel", "WEBHOOK");
        config.put("triggerType", "form");
        config.put("form", form);
        return config;
    }

    private static WorkflowGraphDto formGraph(Map<String, Object> triggerConfig) {
        return new WorkflowGraphDto(List.of(node("t", "trigger", triggerConfig)), List.of());
    }

    private List<String> errors(WorkflowGraphDto graph) {
        return assertThrows(WorkflowValidationException.class, () -> orchestrator.validate(graph))
                .getErrors();
    }

    // ---- wait
    // ---------------------------------------------------------------------------------------------------------

    @Test
    void aWaitNodeIsRejectedClearlyWhenTheFeatureIsOff() {
        List<String> errors = errors(waitGraph(Map.of("mode", "duration", "duration", "5m")));

        assertTrue(
                errors.stream()
                        .anyMatch(
                                e ->
                                        e.contains("Wait node 'w'")
                                                && e.contains("app.workflow.wait-node.enabled")),
                errors.toString());
    }

    @Test
    void aValidWaitNodePassesWhenTheFeatureIsOn() {
        orchestrator.setWaitNodeEnabled(true);

        assertDoesNotThrow(
                () ->
                        orchestrator.validate(
                                waitGraph(Map.of("mode", "duration", "duration", "5m"))));
        assertDoesNotThrow(
                () ->
                        orchestrator.validate(
                                waitGraph(
                                        Map.of("mode", "until", "until", "{{ $trigger.when }}"))));
        assertDoesNotThrow(() -> orchestrator.validate(waitGraph(Map.of("mode", "webhook"))));
    }

    @Test
    void aBadWaitConfigIsRejectedAtSaveTimeWhenTheFeatureIsOn() {
        orchestrator.setWaitNodeEnabled(true);

        assertTrue(
                errors(waitGraph(Map.of("mode", "duration"))).stream()
                        .anyMatch(e -> e.contains("no duration")));
        assertTrue(
                errors(waitGraph(Map.of("duration", "90d"))).stream()
                        .anyMatch(e -> e.contains("exceeds the maximum")));
        assertTrue(
                errors(waitGraph(Map.of("mode", "until", "until", "tomorrow-ish"))).stream()
                        .anyMatch(e -> e.contains("ISO-8601")));
        assertTrue(
                errors(waitGraph(Map.of("mode", "sometimes"))).stream()
                        .anyMatch(e -> e.contains("unknown wait mode")));
    }

    // ---- form trigger
    // ---------------------------------------------------------------------------------------------------

    @Test
    void aFormTriggerIsRejectedClearlyWhenTheFeatureIsOff() {
        List<String> errors = errors(formGraph(formTrigger()));

        assertTrue(
                errors.stream()
                        .anyMatch(
                                e ->
                                        e.contains("hosted form")
                                                && e.contains("app.workflow.form-trigger.enabled")),
                errors.toString());
    }

    @Test
    void aValidFormTriggerPassesWhenTheFeatureIsOn() {
        orchestrator.setFormTriggerEnabled(true);

        assertDoesNotThrow(() -> orchestrator.validate(formGraph(formTrigger())));
    }

    @Test
    void aBadFormSchemaIsRejectedAtSaveTimeWhenTheFeatureIsOn() {
        orchestrator.setFormTriggerEnabled(true);
        Map<String, Object> config = formTrigger();
        config.put("form", Map.of("fields", List.of(Map.of("key", "1bad", "type", "text"))));

        List<String> errors = errors(formGraph(config));

        assertTrue(
                errors.stream()
                        .anyMatch(
                                e -> e.contains("form:") && e.contains("must start with a letter")),
                errors.toString());
    }

    @Test
    void anOrdinaryTriggerIsUntouchedByEitherFlag() {
        assertDoesNotThrow(() -> orchestrator.validate(formGraph(Map.of("channel", "WEBHOOK"))));
        assertDoesNotThrow(
                () ->
                        orchestrator.validate(
                                formGraph(Map.of("channel", "WEBHOOK", "triggerType", "webhook"))));
    }
}
