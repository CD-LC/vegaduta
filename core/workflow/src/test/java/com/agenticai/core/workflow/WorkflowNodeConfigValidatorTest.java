package com.agenticai.core.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.agenticai.core.agents.core.AgentNotFoundException;
import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.workflow.WorkflowNodeConfigValidator.Flags;
import com.agenticai.core.workflow.dto.WorkflowNodeDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** {@link WorkflowNodeConfigValidator}: the per-node-type config rules checked at save/activate. */
class WorkflowNodeConfigValidatorTest {

    private static final UUID EXISTING_AGENT = UUID.randomUUID();
    private static final UUID MISSING_AGENT = UUID.randomUUID();

    private AgentService agentService;
    private WorkflowNodeConfigValidator validator;
    private List<String> errors;

    @BeforeEach
    void setUp() {
        agentService = mock(AgentService.class);
        doThrow(new AgentNotFoundException(MISSING_AGENT)).when(agentService).requireExists(MISSING_AGENT);
        validator = new WorkflowNodeConfigValidator(agentService, new ObjectMapper());
        errors = new ArrayList<>();
    }

    private static WorkflowNodeDto node(String type, Map<String, Object> config) {
        return new WorkflowNodeDto("n1", type, Map.of(), Map.of("config", config, "label", "N1"));
    }

    private void validate(String type, Map<String, Object> config) {
        validator.validate(node(type, config), errors, null, Set.of(), flags());
    }

    private void validate(String type, Map<String, Object> config, Flags flags) {
        validator.validate(node(type, config), errors, null, Set.of(), flags);
    }

    private static Flags flags() {
        return new Flags(false, false, true, 30);
    }

    @Test
    void agentNeedsAnExistingAgent() {
        validate("agent", Map.of());
        assertTrue(errors.get(0).contains("no agentId"));

        errors.clear();
        validate("agent", Map.of("agentId", "not-a-uuid"));
        assertTrue(errors.get(0).contains("invalid agentId"));

        errors.clear();
        validate("agent", Map.of("agentId", MISSING_AGENT.toString()));
        assertTrue(errors.get(0).contains("no longer exists"));

        errors.clear();
        validate("agent", Map.of("agentId", EXISTING_AGENT.toString()));
        assertTrue(errors.isEmpty());
    }

    @Test
    void assumedAgentsSkipTheExistenceCheck() {
        WorkflowNodeDto node = node("agent", Map.of("agentId", MISSING_AGENT.toString()));
        validator.validate(node, errors, null, Set.of(MISSING_AGENT), flags());
        assertTrue(errors.isEmpty(), "an agent created in the same batch is assumed");
        verify(agentService, never()).requireExists(MISSING_AGENT);
    }

    @Test
    void conditionReadsExpressionOrContainsByMode() {
        validate("condition", Map.of("mode", "expression"));
        assertTrue(errors.get(0).contains("expression"));

        errors.clear();
        validate("condition", Map.of("mode", "expression", "expression", "$json.x > 1"));
        assertTrue(errors.isEmpty());

        errors.clear();
        validate("condition", Map.of());
        assertTrue(errors.get(0).contains("contains"));

        errors.clear();
        validate("condition", Map.of("contains", "yes"));
        assertTrue(errors.isEmpty());
    }

    @Test
    void outputChannelsAndWhatsAppTemplates() {
        validate("output", Map.of("channel", "pigeon"));
        assertTrue(errors.get(0).contains("unrecognized channel"));

        errors.clear();
        validate("output", Map.of("channel", "google_drive"));
        assertTrue(errors.isEmpty());

        errors.clear();
        Map<String, Object> half = Map.of("name", "welcome");
        validate(
                "output",
                Map.of("channel", "whatsapp", "whatsappTemplate", half),
                new Flags(true, false, true, 30));
        assertTrue(errors.get(0).contains("without a name or a language"));

        errors.clear();
        validate(
                "output",
                Map.of("channel", "whatsapp", "whatsappTemplate", half),
                flags());
        assertTrue(errors.isEmpty(), "with the feature off a leftover template is ignored");

        errors.clear();
        validate(
                "output",
                Map.of("channel", "whatsapp", "whatsappTemplate", "notAMap"),
                new Flags(true, false, true, 30));
        assertTrue(errors.get(0).contains("not a {name, language} object"));
    }

    @Test
    void toolReportsBothMissingFields() {
        validate("tool", Map.of());
        assertEquals(2, errors.size());
        assertTrue(errors.get(0).contains("no agent"));
        assertTrue(errors.get(1).contains("no tool"));
    }

    @Test
    void codeRejectsUnknownOperations() {
        validate("code", Map.of());
        assertTrue(errors.get(0).contains("no operation"));
        errors.clear();
        validate("code", Map.of("operation", "transmute"));
        assertTrue(errors.get(0).contains("unrecognized operation"));
        errors.clear();
        validate("code", Map.of("operation", "json_parse"));
        assertTrue(errors.isEmpty());
    }

    @Test
    void httpNeedsAUrlAndKnownMethodAndBodyType() {
        validate("http", Map.of());
        assertTrue(errors.get(0).contains("no URL"));

        errors.clear();
        validate("http", Map.of("url", "https://x", "method", "TELEPORT"));
        assertTrue(errors.get(0).contains("unsupported method"));

        errors.clear();
        validate("http", Map.of("url", "https://x", "bodyType", "yaml"));
        assertTrue(errors.get(0).contains("unrecognized body type"));

        errors.clear();
        validate("http", Map.of("url", "https://x", "method", "post", "bodyType", "JSON"));
        assertTrue(errors.isEmpty());
    }

    @Test
    void supervisorNeedsUsableRoutes() {
        Map<String, Object> base = Map.of("agentId", EXISTING_AGENT.toString());
        validate("supervisor", base);
        assertTrue(errors.stream().anyMatch(e -> e.contains("no usable routes")));

        errors.clear();
        validate(
                "supervisor",
                Map.of("agentId", EXISTING_AGENT.toString(), "routes", "[{\"handle\":\"a\"}]"));
        assertTrue(errors.isEmpty(), "a raw JSON string of routes parses");

        errors.clear();
        validate(
                "supervisor",
                Map.of(
                        "agentId", EXISTING_AGENT.toString(),
                        "routes", List.of(Map.of("value", "a"))));
        assertTrue(errors.stream().anyMatch(e -> e.contains("no usable routes")),
                "entries without a handle do not count");
    }

    @Test
    void workflowCannotTargetItself() {
        UUID own = UUID.randomUUID();
        validate("workflow", Map.of());
        assertTrue(errors.get(0).contains("no target workflow"));

        errors.clear();
        validate("workflow", Map.of("targetWorkflowId", "bogus"));
        assertTrue(errors.get(0).contains("invalid target workflow id"));

        errors.clear();
        validator.validate(
                node("workflow", Map.of("targetWorkflowId", own.toString())),
                errors, own, Set.of(), flags());
        assertTrue(errors.get(0).contains("same workflow"));

        errors.clear();
        validator.validate(
                node("workflow", Map.of("targetWorkflowId", UUID.randomUUID().toString())),
                errors, own, Set.of(), flags());
        assertTrue(errors.isEmpty());
    }

    @Test
    void humanInputChecksFieldShape() {
        validate("human_input", Map.of());
        assertTrue(errors.isEmpty(), "a field-less pause is legal");

        errors.clear();
        validate("human_input", Map.of("fields", "oops"));
        assertTrue(errors.get(0).contains("not a list"));

        errors.clear();
        validate("human_input", Map.of("fields", List.of(Map.of("key", "name"), Map.of("x", 1))));
        assertTrue(errors.get(0).contains("no 'key'"));
        assertEquals(1, errors.size(), "one bad entry reported once");

        errors.clear();
        validate("human_input", Map.of("fields", List.of(Map.of("key", "name"))));
        assertTrue(errors.isEmpty());
    }

    @Test
    void voiceMenuNeedsAPromptAndWellFormedBranches() {
        validate("voice_menu", Map.of());
        assertTrue(errors.get(0).contains("no prompt"));

        errors.clear();
        validate("voice_menu", Map.of("prompt", "say it", "branches", "oops"));
        assertTrue(errors.get(0).contains("not a list"));

        errors.clear();
        validate("voice_menu", Map.of("prompt", "say it", "branches", List.of(Map.of("digit", "1"))));
        assertTrue(errors.get(0).contains("missing a 'digit' or 'label'"));

        errors.clear();
        validate(
                "voice_menu",
                Map.of("prompt", "say it", "branches", List.of(Map.of("digit", "1", "label", "L"))));
        assertTrue(errors.isEmpty());
    }

    @Test
    void bulkEmailNeedsSubjectBodyAndAConsistentRecipientSource() {
        validate("bulk_email", Map.of());
        assertTrue(errors.stream().anyMatch(e -> e.contains("no subject")));
        assertTrue(errors.stream().anyMatch(e -> e.contains("no body")));

        errors.clear();
        validate("bulk_email", Map.of("subject", "s", "body", "b", "recipientsSource", "space"));
        assertTrue(errors.get(0).contains("unknown recipientsSource"));

        errors.clear();
        validate("bulk_email", Map.of("subject", "s", "body", "b", "recipientsSource", "group"));
        assertTrue(errors.get(0).contains("groupId"));

        errors.clear();
        validate(
                "bulk_email",
                Map.of("subject", "s", "body", "b", "recipientsSource", "group",
                    "groupId", UUID.randomUUID().toString()));
        assertTrue(errors.isEmpty());

        errors.clear();
        validate("bulk_email", Map.of("subject", "s", "body", "b", "recipientsSource", "static"));
        assertTrue(errors.get(0).contains("no static recipients"));
    }

    @Test
    void triggerChannelAndFormFlags() {
        validate("trigger", Map.of());
        assertTrue(errors.isEmpty(), "a missing channel is the implicit webhook default");

        errors.clear();
        validate("trigger", Map.of("channel", "TELEPATHY"));
        assertTrue(errors.get(0).contains("invalid channel"));

        errors.clear();
        validate("trigger", Map.of("channel", "whatsapp"));
        assertTrue(errors.isEmpty(), "channel values are case-insensitive");

        errors.clear();
        validate("trigger", Map.of("triggerType", "form"), new Flags(false, false, true, 30));
        assertTrue(errors.get(0).contains("form triggers are not enabled"));

        errors.clear();
        validate("trigger", Map.of("triggerType", "form"), new Flags(false, true, true, 30));
        assertTrue(errors.get(0).contains("form"), "an enabled form trigger validates its schema");
    }

    @Test
    void waitHonoursTheFeatureFlagAndCapsDurations() {
        validate("wait", Map.of(), new Flags(false, false, false, 30));
        assertTrue(errors.get(0).contains("not enabled"));

        errors.clear();
        validate("wait", Map.of("mode", "sideways"), new Flags(false, false, true, 30));
        assertTrue(errors.get(0).contains("unknown wait mode"));

        errors.clear();
        validate("wait", Map.of("duration", "999999", "unit", "days"), new Flags(false, false, true, 30));
        assertEquals(1, errors.size(), "a duration past the cap is one error");

        errors.clear();
        validate("wait", Map.of("duration", "5", "unit", "minutes"), new Flags(false, false, true, 30));
        assertTrue(errors.isEmpty());
    }

    @Test
    void subFlowNeedsANonEmptyBodyGraph() {
        validate("sub_flow", Map.of());
        assertTrue(errors.get(0).contains("no 'body'"));

        errors.clear();
        validate("sub_flow", Map.of("body", Map.of("nodes", List.of())));
        assertTrue(errors.get(0).contains("no 'body'"));

        errors.clear();
        validate("sub_flow", Map.of("body", Map.of("nodes", List.of(Map.of("id", "x")))));
        assertTrue(errors.isEmpty());
    }

    @Test
    void dataNodesDelegateToTheDataValidatorAndUnknownTypesPass() {
        validate("filter", Map.of());
        assertTrue(errors.get(0).contains("no condition"));

        errors.clear();
        validate("approval", Map.of());
        assertTrue(errors.isEmpty());
        validate("loop", Map.of());
        assertTrue(errors.isEmpty());
    }
}
