package com.agenticai.core.workflow;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agenticai.core.workflow.dto.WorkflowGraphDto;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** {@link OrchestratorService#validate}: each node type's required config (agent, tool, output, supervisor, ...). */
class OrchestratorNodeConfigValidationTest extends OrchestratorServiceTestBase {

    // --- agent node ----------------------------------------------------------

    @Test
    void agentNodeWithNoAgentIdIsRejected() {
        WorkflowGraphDto graph = new WorkflowGraphDto(
            List.of(trigger("t1"), node("n2", "agent", Map.of())), List.of());

        List<String> errors = errorsFrom(graph);
        assertTrue(errors.stream().anyMatch(e -> e.contains("no agentId configured")));
    }

    @Test
    void agentNodeWithInvalidUuidIsRejected() {
        WorkflowGraphDto graph = new WorkflowGraphDto(
            List.of(trigger("t1"), node("n2", "agent", Map.of("agentId", "not-a-uuid"))), List.of());

        List<String> errors = errorsFrom(graph);
        assertTrue(errors.stream().anyMatch(e -> e.contains("invalid agentId")));
    }

    @Test
    void agentNodeReferencingMissingAgentIsRejected() {
        WorkflowGraphDto graph = new WorkflowGraphDto(
            List.of(trigger("t1"), node("n2", "agent", Map.of("agentId", MISSING_AGENT_ID.toString()))), List.of());

        List<String> errors = errorsFrom(graph);
        assertTrue(errors.stream().anyMatch(e -> e.contains("no longer exists")));
    }

    @Test
    void agentNodeWithExistingAgentIsValid() {
        WorkflowGraphDto graph = new WorkflowGraphDto(
            List.of(trigger("t1"), node("n2", "agent", Map.of("agentId", EXISTING_AGENT_ID.toString()))), List.of());

        assertDoesNotThrow(() -> orchestrator.validate(graph));
    }

    // --- knowledge node --------------------------------------------------------

    @Test
    void knowledgeNodeWithNoCollectionIdIsValid() {
        // collectionId is optional - "search across everything" if unset.
        WorkflowGraphDto graph = new WorkflowGraphDto(
            List.of(trigger("t1"), node("n2", "knowledge", Map.of())), List.of());

        assertDoesNotThrow(() -> orchestrator.validate(graph));
    }

    @Test
    void knowledgeNodeWithInvalidCollectionIdIsRejected() {
        WorkflowGraphDto graph = new WorkflowGraphDto(
            List.of(trigger("t1"), node("n2", "knowledge", Map.of("collectionId", "not-a-uuid"))), List.of());

        List<String> errors = errorsFrom(graph);
        assertTrue(errors.stream().anyMatch(e -> e.contains("invalid collectionId")));
    }

    // --- condition node ----------------------------------------------------

    @Test
    void conditionNodeWithNoContainsValueIsRejected() {
        WorkflowGraphDto graph = new WorkflowGraphDto(
            List.of(trigger("t1"), node("n2", "condition", Map.of())), List.of());

        List<String> errors = errorsFrom(graph);
        assertTrue(errors.stream().anyMatch(e -> e.contains("no condition configured")));
    }

    @Test
    void conditionNodeInExpressionModeNeedsAnExpressionNotContains() {
        WorkflowGraphDto ok = new WorkflowGraphDto(
            List.of(trigger("t1"), node("n2", "condition", Map.of("mode", "expression", "expression", "$json.total > 1000"))),
            List.of());
        assertDoesNotThrow(() -> orchestrator.validate(ok));

        WorkflowGraphDto blank = new WorkflowGraphDto(
            List.of(trigger("t1"), node("n2", "condition", Map.of("mode", "expression", "contains", "x", "expression", " "))),
            List.of());
        List<String> errors = assertThrows(WorkflowValidationException.class, () -> orchestrator.validate(blank)).getErrors();
        assertTrue(errors.stream().anyMatch(e -> e.contains("'expression'")));
    }

    @Test
    void conditionNodeWithContainsValueIsValid() {
        WorkflowGraphDto graph = new WorkflowGraphDto(
            List.of(trigger("t1"), node("n2", "condition", Map.of("contains", "urgent"))), List.of());

        assertDoesNotThrow(() -> orchestrator.validate(graph));
    }

    // --- output node ---------------------------------------------------------

    @Test
    void outputNodeWithUnsupportedChannelIsRejected() {
        WorkflowGraphDto graph = new WorkflowGraphDto(
            List.of(trigger("t1"), node("n2", "output", Map.of("channel", "carrier_pigeon"))), List.of());

        List<String> errors = errorsFrom(graph);
        assertTrue(errors.stream().anyMatch(e -> e.contains("unrecognized channel")));
    }

    @Test
    void outputNodeWithEachSupportedChannelIsValid() {
        for (String channel : List.of("log", "event", "file", "email", "telegram", "whatsapp")) {
            WorkflowGraphDto graph = new WorkflowGraphDto(
                List.of(trigger("t1"), node("n2", "output", Map.of("channel", channel))), List.of());

            assertDoesNotThrow(() -> orchestrator.validate(graph), "channel '" + channel + "' should be supported");
        }
    }

    @Test
    void outputNodeWithNoChannelConfiguredIsValid() {
        // No channel set is fine - WorkflowExecutionEngine's own default is "log".
        WorkflowGraphDto graph = new WorkflowGraphDto(
            List.of(trigger("t1"), node("n2", "output", Map.of())), List.of());

        assertDoesNotThrow(() -> orchestrator.validate(graph));
    }

    // --- tool node -----------------------------------------------------------

    @Test
    void toolNodeWithNoAgentIsRejected() {
        WorkflowGraphDto graph = new WorkflowGraphDto(
            List.of(trigger("t1"), node("n2", "tool", Map.of("toolName", "search_web"))), List.of());

        List<String> errors = errorsFrom(graph);
        assertTrue(errors.stream().anyMatch(e -> e.contains("no agent selected")));
    }

    @Test
    void toolNodeWithNoToolNameIsRejected() {
        WorkflowGraphDto graph = new WorkflowGraphDto(
            List.of(trigger("t1"),
                node("n2", "tool", Map.of("agentId", EXISTING_AGENT_ID.toString()))),
            List.of());

        List<String> errors = errorsFrom(graph);
        assertTrue(errors.stream().anyMatch(e -> e.contains("no tool selected")));
    }

    @Test
    void toolNodeWithAgentAndToolNameIsValid() {
        WorkflowGraphDto graph = new WorkflowGraphDto(
            List.of(trigger("t1"), node("n2", "tool",
                Map.of("agentId", EXISTING_AGENT_ID.toString(), "toolName", "search_web"))),
            List.of());

        assertDoesNotThrow(() -> orchestrator.validate(graph));
    }

    // --- code (transform) node -----------------------------------------------

    @Test
    void codeNodeWithNoOperationIsRejected() {
        WorkflowGraphDto graph = new WorkflowGraphDto(
            List.of(trigger("t1"), node("n2", "code", Map.of())), List.of());

        List<String> errors = errorsFrom(graph);
        assertTrue(errors.stream().anyMatch(e -> e.contains("no operation selected")));
    }

    @Test
    void codeNodeWithUnsupportedOperationIsRejected() {
        WorkflowGraphDto graph = new WorkflowGraphDto(
            List.of(trigger("t1"), node("n2", "code", Map.of("operation", "eval_arbitrary_js"))), List.of());

        List<String> errors = errorsFrom(graph);
        assertTrue(errors.stream().anyMatch(e -> e.contains("unrecognized operation")));
    }

    @Test
    void codeNodeWithSupportedOperationIsValid() {
        WorkflowGraphDto graph = new WorkflowGraphDto(
            List.of(trigger("t1"), node("n2", "code", Map.of("operation", "uppercase"))), List.of());

        assertDoesNotThrow(() -> orchestrator.validate(graph));
    }

    // --- workflow (trigger-workflow) node -------------------------------------

    @Test
    void workflowNodeWithNoTargetIsRejected() {
        WorkflowGraphDto graph = new WorkflowGraphDto(
            List.of(trigger("t1"), node("n2", "workflow", Map.of())), List.of());

        List<String> errors = errorsFrom(graph);
        assertTrue(errors.stream().anyMatch(e -> e.contains("no target workflow selected")));
    }

    @Test
    void workflowNodeWithInvalidTargetUuidIsRejected() {
        WorkflowGraphDto graph = new WorkflowGraphDto(
            List.of(trigger("t1"), node("n2", "workflow", Map.of("targetWorkflowId", "not-a-uuid"))), List.of());

        List<String> errors = errorsFrom(graph);
        assertTrue(errors.stream().anyMatch(e -> e.contains("invalid target workflow id")));
    }

    @Test
    void workflowNodeTargetingItsOwnContainingWorkflowIsRejected() {
        UUID ownWorkflowId = UUID.randomUUID();
        WorkflowGraphDto graph = new WorkflowGraphDto(
            List.of(trigger("t1"), node("n2", "workflow", Map.of("targetWorkflowId", ownWorkflowId.toString()))),
            List.of());

        WorkflowValidationException ex = assertThrows(
            WorkflowValidationException.class, () -> orchestrator.validate(graph, ownWorkflowId));
        assertTrue(ex.getErrors().stream().anyMatch(e -> e.contains("targets this same workflow")));
    }

    @Test
    void workflowNodeTargetingADifferentWorkflowIsValid() {
        UUID ownWorkflowId = UUID.randomUUID();
        UUID otherWorkflowId = UUID.randomUUID();
        WorkflowGraphDto graph = new WorkflowGraphDto(
            List.of(trigger("t1"), node("n2", "workflow", Map.of("targetWorkflowId", otherWorkflowId.toString()))),
            List.of());

        assertDoesNotThrow(() -> orchestrator.validate(graph, ownWorkflowId));
    }

    @Test
    void workflowNodeSelfReferenceIsNotCheckedWhenOwnWorkflowIdIsUnknown() {
        // A brand-new, unsaved graph has no id yet - see validate(graph)'s
        // javadoc. Self-reference simply can't be checked yet; this must
        // not throw or misbehave just because ownWorkflowId is null.
        UUID someWorkflowId = UUID.randomUUID();
        WorkflowGraphDto graph = new WorkflowGraphDto(
            List.of(trigger("t1"), node("n2", "workflow", Map.of("targetWorkflowId", someWorkflowId.toString()))),
            List.of());

        assertDoesNotThrow(() -> orchestrator.validate(graph));
    }

    // --- supervisor node (Master Roadmap 2026 item #12, §E) ----------------------

    @Test
    void supervisorNodeWithNoAgentIsRejected() {
        WorkflowGraphDto graph = new WorkflowGraphDto(
            List.of(trigger("t1"), node("s1", "supervisor",
                Map.of("routes", List.of(Map.of("handle", "billing"))))),
            List.of());

        List<String> errors = errorsFrom(graph);
        assertTrue(errors.stream().anyMatch(e -> e.contains("no agent selected")));
    }

    @Test
    void supervisorNodeWithInvalidAgentUuidIsRejected() {
        WorkflowGraphDto graph = new WorkflowGraphDto(
            List.of(trigger("t1"), node("s1", "supervisor",
                Map.of("agentId", "not-a-uuid", "routes", List.of(Map.of("handle", "billing"))))),
            List.of());

        List<String> errors = errorsFrom(graph);
        assertTrue(errors.stream().anyMatch(e -> e.contains("invalid agentId")));
    }

    @Test
    void supervisorNodeReferencingMissingAgentIsRejected() {
        WorkflowGraphDto graph = new WorkflowGraphDto(
            List.of(trigger("t1"), node("s1", "supervisor",
                Map.of("agentId", MISSING_AGENT_ID.toString(), "routes", List.of(Map.of("handle", "billing"))))),
            List.of());

        List<String> errors = errorsFrom(graph);
        assertTrue(errors.stream().anyMatch(e -> e.contains("no longer exists")));
    }

    @Test
    void supervisorNodeWithNoRoutesIsRejected() {
        WorkflowGraphDto graph = new WorkflowGraphDto(
            List.of(trigger("t1"), node("s1", "supervisor", Map.of("agentId", EXISTING_AGENT_ID.toString()))),
            List.of());

        List<String> errors = errorsFrom(graph);
        assertTrue(errors.stream().anyMatch(e -> e.contains("no usable routes configured")));
    }

    @Test
    void supervisorNodeWithEmptyRoutesListIsRejected() {
        WorkflowGraphDto graph = new WorkflowGraphDto(
            List.of(trigger("t1"), node("s1", "supervisor",
                Map.of("agentId", EXISTING_AGENT_ID.toString(), "routes", List.of()))),
            List.of());

        List<String> errors = errorsFrom(graph);
        assertTrue(errors.stream().anyMatch(e -> e.contains("no usable routes configured")));
    }

    @Test
    void supervisorNodeWithRouteEntriesMissingAHandleFieldIsRejected() {
        // The bug this test locks in the fix for: a non-empty routes array whose
        // entries are shaped wrong (e.g. "value" instead of "handle" - plausible
        // output from an AI graph generator) used to pass this check, then silently
        // resolved to zero routes at runtime (WorkflowNodeExecutor#resolveRoutes
        // drops any entry without a non-blank "handle"), skipping the node on every
        // run with no validation error ever surfaced to the author.
        WorkflowGraphDto graph = new WorkflowGraphDto(
            List.of(trigger("t1"), node("s1", "supervisor",
                Map.of("agentId", EXISTING_AGENT_ID.toString(),
                    "routes", List.of(Map.of("value", "billing"), Map.of("value", "support"))))),
            List.of());

        List<String> errors = errorsFrom(graph);
        assertTrue(errors.stream().anyMatch(e -> e.contains("no usable routes configured")));
    }

    @Test
    void supervisorNodeWithAtLeastOneUsableRouteAmongMalformedOnesIsValid() {
        WorkflowGraphDto graph = new WorkflowGraphDto(
            List.of(trigger("t1"), node("s1", "supervisor",
                Map.of("agentId", EXISTING_AGENT_ID.toString(),
                    "routes", List.of(Map.of("value", "billing"), Map.of("handle", "support"))))),
            List.of());

        assertDoesNotThrow(() -> orchestrator.validate(graph));
    }

    @Test
    void supervisorNodeWithWellFormedRoutesIsValid() {
        WorkflowGraphDto graph = new WorkflowGraphDto(
            List.of(trigger("t1"), node("s1", "supervisor",
                Map.of("agentId", EXISTING_AGENT_ID.toString(),
                    "routes", List.of(Map.of("handle", "billing", "label", "Billing"),
                        Map.of("handle", "support", "label", "Support"))))),
            List.of());

        assertDoesNotThrow(() -> orchestrator.validate(graph));
    }

    @Test
    void supervisorNodeWithRoutesAsARawJsonStringIsValid() {
        // resolveRoutes at runtime tolerates 'routes' arriving as a raw JSON string
        // (not just an already-parsed List) - validate() needs to accept the same
        // shape or a legitimately-configured node would fail save-time validation.
        WorkflowGraphDto graph = new WorkflowGraphDto(
            List.of(trigger("t1"), node("s1", "supervisor",
                Map.of("agentId", EXISTING_AGENT_ID.toString(),
                    "routes", "[{\"handle\":\"billing\"}]"))),
            List.of());

        assertDoesNotThrow(() -> orchestrator.validate(graph));
    }

    // --- voice_menu node (V308, call-logic/DTMF fast-follow) ---------------------

    @Test
    void voiceMenuNodeWithNoPromptIsRejected() {
        WorkflowGraphDto graph = new WorkflowGraphDto(
            List.of(trigger("t1"), node("v1", "voice_menu", Map.of())),
            List.of());

        List<String> errors = errorsFrom(graph);
        assertTrue(errors.stream().anyMatch(e -> e.contains("no prompt to speak")));
    }

    @Test
    void voiceMenuNodeWithMalformedBranchEntryIsRejected() {
        WorkflowGraphDto graph = new WorkflowGraphDto(
            List.of(trigger("t1"), node("v1", "voice_menu", Map.of(
                "prompt", "Press 1 for sales",
                "branches", List.of(Map.of("digit", "1"))))), // label missing
            List.of());

        List<String> errors = errorsFrom(graph);
        assertTrue(errors.stream().anyMatch(e -> e.contains("branch entry missing a 'digit' or 'label'")));
    }

    @Test
    void voiceMenuNodeWithNonListBranchesIsRejected() {
        WorkflowGraphDto graph = new WorkflowGraphDto(
            List.of(trigger("t1"), node("v1", "voice_menu", Map.of(
                "prompt", "Press 1 for sales", "branches", "not-a-list"))),
            List.of());

        List<String> errors = errorsFrom(graph);
        assertTrue(errors.stream().anyMatch(e -> e.contains("not a list of {digit, label} objects")));
    }

    @Test
    void voiceMenuNodeWithPromptAndWellFormedBranchesIsValid() {
        WorkflowGraphDto graph = new WorkflowGraphDto(
            List.of(trigger("t1"),
                node("v1", "voice_menu", Map.of(
                    "prompt", "Press 1 for sales, 2 for support",
                    "branches", List.of(
                        Map.of("digit", "1", "label", "sales"),
                        Map.of("digit", "2", "label", "support")))),
                node("a", "output", Map.of("channel", "log"))),
            List.of(edge("e1", "t1", "v1"), edge("e2", "v1", "a")));

        assertDoesNotThrow(() -> orchestrator.validate(graph));
    }

    @Test
    void voiceMenuNodeWithPromptButNoBranchesIsValid() {
        // Deliberate: a menu can legitimately gather digits and rely solely on
        // the implicit "no_match"/"timeout" edges (e.g. "enter your account
        // number", where the digits themselves flow downstream as data).
        WorkflowGraphDto graph = new WorkflowGraphDto(
            List.of(trigger("t1"), node("v1", "voice_menu", Map.of("prompt", "Enter your account number"))),
            List.of(edge("e1", "t1", "v1")));

        assertDoesNotThrow(() -> orchestrator.validate(graph));
    }

    // --- Dify-style AI nodes (wave b, lane wfllm) ---

    @Test
    void aiNodeTypesAreRecognisedAndTheirConfigIsValidatedAtSave() {
        WorkflowGraphDto valid = new WorkflowGraphDto(
            List.of(trigger("t1"),
                node("c1", "question_classifier", Map.of("classes", List.of(
                    Map.of("handle", "billing", "description", "money"),
                    Map.of("handle", "support", "description", "bugs")))),
                node("x1", "parameter_extractor", Map.of("parameters", List.of(
                    Map.of("name", "city", "type", "string", "required", true)))),
                node("g1", "variable_aggregator", Map.of("sources", List.of("{{ $node[\"A\"].x }}", "{{input}}"))),
                node("d1", "document_extractor", Map.of("format", "pdf")),
                node("o1", "output", Map.of("channel", "log"))),
            List.of(edge("e1", "t1", "c1"), edge("e2", "c1", "x1"), edge("e3", "x1", "g1"),
                edge("e4", "g1", "d1"), edge("e5", "d1", "o1")));
        assertDoesNotThrow(() -> orchestrator.validate(valid));

        WorkflowGraphDto invalid = new WorkflowGraphDto(
            List.of(trigger("t1"),
                node("c1", "question_classifier", Map.of("classes", List.of(Map.of("handle", "only")))),
                node("x1", "parameter_extractor", Map.of()),
                node("g1", "variable_aggregator", Map.of()),
                node("d1", "document_extractor", Map.of("format", "rtf"))),
            List.of(edge("e1", "t1", "c1"), edge("e2", "c1", "x1"), edge("e3", "x1", "g1"), edge("e4", "g1", "d1")));
        List<String> errors = errorsFrom(invalid);
        assertTrue(errors.stream().anyMatch(e -> e.contains("Question Classifier") && e.contains("at least two classes")), errors.toString());
        assertTrue(errors.stream().anyMatch(e -> e.contains("Parameter Extractor") && e.contains("at least one parameter")), errors.toString());
        assertTrue(errors.stream().anyMatch(e -> e.contains("Variable Aggregator") && e.contains("at least one source")), errors.toString());
        assertTrue(errors.stream().anyMatch(e -> e.contains("Document Extractor") && e.contains("unsupported document format")), errors.toString());
    }

    // --- nlflow W5: WhatsApp approved template on an Output node ---

    @Test
    void aHalfFilledWhatsAppTemplateIsIgnoredWhileTemplatesAreSwitchedOff() {
        // Flag off (the default): the template config is never sent, so it must not block the workflow.
        WorkflowGraphDto graph = new WorkflowGraphDto(List.of(trigger("t"),
            node("o", "output", Map.of("channel", "whatsapp", "whatsappTemplate", Map.of("name", "invoice_reminder")))),
            List.of(edge("e1", "t", "o")));
        assertDoesNotThrow(() -> orchestrator.validate(graph));
    }

    @Test
    void teamsIsARecognisedOutputChannel() {
        WorkflowGraphDto graph = new WorkflowGraphDto(List.of(trigger("t"),
            node("o", "output", Map.of("channel", "teams"))), List.of(edge("e1", "t", "o")));
        assertDoesNotThrow(() -> orchestrator.validate(graph));
    }

    @Test
    void whatsAppTemplateNeedsBothANameAndALanguage() {
        orchestrator.setWhatsAppTemplatesEnabled(true);
        WorkflowGraphDto complete = new WorkflowGraphDto(List.of(trigger("t"),
            node("o", "output", Map.of("channel", "whatsapp", "whatsappTo", "+1555",
                "whatsappTemplate", Map.of("name", "invoice_reminder", "language", "en_US"),
                "whatsappTemplateParams", List.of("{{ $json.name }}")))), List.of(edge("e1", "t", "o")));
        assertDoesNotThrow(() -> orchestrator.validate(complete));

        WorkflowGraphDto noTemplate = new WorkflowGraphDto(List.of(trigger("t"),
            node("o", "output", Map.of("channel", "whatsapp", "whatsappMessage", "hi", "whatsappTemplate", Map.of()))),
            List.of(edge("e1", "t", "o")));
        assertDoesNotThrow(() -> orchestrator.validate(noTemplate), "an empty template object means none chosen");

        for (Object broken : List.of(Map.of("name", "invoice_reminder"), Map.of("name", " ", "language", "en"), "invoice_reminder")) {
            WorkflowGraphDto graph = new WorkflowGraphDto(List.of(trigger("t"),
                node("o", "output", Map.of("channel", "whatsapp", "whatsappTemplate", broken))), List.of(edge("e1", "t", "o")));
            WorkflowValidationException e = assertThrows(WorkflowValidationException.class, () -> orchestrator.validate(graph));
            assertTrue(e.getErrors().stream().anyMatch(m -> m.contains("WhatsApp template")), e.getErrors().toString());
        }

        // The field is only read for the whatsapp channel.
        WorkflowGraphDto otherChannel = new WorkflowGraphDto(List.of(trigger("t"),
            node("o", "output", Map.of("channel", "email", "whatsappTemplate", "leftover"))), List.of(edge("e1", "t", "o")));
        assertDoesNotThrow(() -> orchestrator.validate(otherChannel));
    }
}
