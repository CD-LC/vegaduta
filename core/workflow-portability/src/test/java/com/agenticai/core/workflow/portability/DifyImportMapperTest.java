package com.agenticai.core.workflow.portability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agenticai.core.agents.importer.AgentImportDtos.ConversionReport;
import com.agenticai.core.workflow.dto.WorkflowEdgeDto;
import com.agenticai.core.workflow.dto.WorkflowNodeDto;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Golden fixture for the Dify DSL importer: a small support-triage app written for this test in the
 * shape of Dify's exported YAML (not copied from Dify). Parsed through the real safe YAML loader,
 * so the whole YAML -> graph path runs.
 */
class DifyImportMapperTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String BEARER = "abcdefghijklmnopqrstuvwxyz0123456789";

    private static final String SUPPORT_TRIAGE =
            """
        app:
          description: Support triage
          mode: workflow
          name: Support triage
        kind: app
        version: 0.1.5
        workflow:
          environment_variables:
          - name: API_TOKEN
            value: ''
            value_type: secret
            id: env-1
          - name: REGION
            value: eu
            value_type: string
            id: env-2
          conversation_variables: []
          graph:
            edges:
            - {id: e1, source: '100', target: '200', sourceHandle: source}
            - {id: e2, source: '200', target: '300', sourceHandle: source}
            - {id: e3, source: '300', target: '400', sourceHandle: source}
            - {id: e4, source: '400', target: '500', sourceHandle: case_a}
            - {id: e5, source: '400', target: '600', sourceHandle: 'false'}
            - {id: e6, source: '500', target: '700', sourceHandle: source}
            - {id: e7, source: '700', target: '800', sourceHandle: source}
            nodes:
            - id: '100'
              position: {x: 0, y: 0}
              data: {type: start, title: Start, variables: [{variable: question, type: text-input}]}
            - id: '200'
              data:
                type: knowledge-retrieval
                title: Find docs
                dataset_ids: [ds-1]
                query_variable_selector: ['100', question]
                multiple_retrieval_config: {top_k: 4}
            - id: '300'
              data:
                type: llm
                title: Answer
                model: {provider: openai, name: gpt-4o}
                prompt_template:
                - role: system
                  text: You are support.
                - role: user
                  text: 'Context {{#200.result#}} Question {{#100.question#}}'
            - id: '400'
              data:
                type: if-else
                title: Escalate?
                cases:
                - case_id: case_a
                  logical_operator: and
                  conditions:
                  - variable_selector: ['300', text]
                    comparison_operator: contains
                    value: refund
            - id: '500'
              data:
                type: http-request
                title: Ticket API
                method: post
                url: https://api.example.com/tickets
                headers: "Content-Type: application/json\\nX-Key: {{#env.API_TOKEN#}}"
                authorization: {type: api-key, config: {type: bearer, api_key: %s}}
                body: {type: json, data: '{"q": "{{#100.question#}}"}'}
            - id: '600'
              data: {type: variable-aggregator, title: Merge}
            - id: '700'
              data:
                type: template-transform
                title: Format
                template: 'Ticket: {{ t }}'
                variables: [{variable: t, value_selector: ['500', body]}]
            - id: '800'
              data: {type: answer, title: Reply, answer: 'Done {{#700.output#}}'}
        """
                    .formatted(BEARER);

    private static JsonNode yaml(String text) {
        return SafeYamlLoader.load(text, 1_000_000);
    }

    private static PortableWorkflow supportTriage() {
        return DifyImportMapper.map(MAPPER, yaml(SUPPORT_TRIAGE));
    }

    private static WorkflowNodeDto node(PortableWorkflow wf, String id) {
        return wf.nodes.stream().filter(n -> n.id().equals(id)).findFirst().orElseThrow();
    }

    private static boolean hasEdge(PortableWorkflow wf, String from, String handle, String to) {
        for (WorkflowEdgeDto e : wf.edges) {
            if (e.source().equals(from)
                    && e.target().equals(to)
                    && (handle == null
                            ? e.sourceHandle() == null
                            : handle.equals(e.sourceHandle()))) {
                return true;
            }
        }
        return false;
    }

    @Test
    void detectedAsDifyAndNamed() {
        assertThat(PortabilityFormat.detect(yaml(SUPPORT_TRIAGE))).contains(PortabilityFormat.DIFY);
        PortableWorkflow wf = supportTriage();
        assertThat(wf.name).isEqualTo("Support triage");
        assertThat(wf.description).isEqualTo("Support triage");
        assertThat(wf.nodes).hasSize(9);
    }

    @Test
    void startBecomesATriggerAndKnowledgeRetrievalABoundedKnowledgeNode() {
        PortableWorkflow wf = supportTriage();
        assertThat(node(wf, "100").type()).isEqualTo("trigger");
        WorkflowNodeDto knowledge = node(wf, "200");
        assertThat(knowledge.type()).isEqualTo("knowledge");
        assertThat(knowledge.config())
                .containsEntry("collectionId", "@placeholder:kb_1")
                .containsEntry("topK", 4);
        assertThat(wf.placeholders.get("kb_1").kind())
                .isEqualTo(PlaceholderRegistry.KNOWLEDGE_BASE);
        // The foreign dataset id is never kept.
        assertThat(wf.placeholders.get("kb_1").label()).doesNotContain("ds-1");
    }

    @Test
    void llmBecomesAnAgentPlaceholderAndVariableReferencesAreRewritten() {
        WorkflowNodeDto llm = node(supportTriage(), "300");
        assertThat(llm.type()).isEqualTo("agent");
        assertThat(llm.config())
                .containsEntry("agentId", "@placeholder:agent_1")
                .containsEntry("importedModel", "openai/gpt-4o");
        String prompt = String.valueOf(llm.config().get("importedPrompt"));
        assertThat(prompt)
                .contains("system: You are support.")
                .contains("{{ $node[\"Find docs\"].result }}")
                .contains("{{ $node[\"Start\"].question }}")
                .doesNotContain("{{#");
        assertThat(llm.data().get("needsReview")).isEqualTo(Boolean.TRUE);
    }

    @Test
    void ifElseBecomesAConditionWithTrueAndFalseBranches() {
        PortableWorkflow wf = supportTriage();
        WorkflowNodeDto condition = node(wf, "400");
        assertThat(condition.type()).isEqualTo("condition");
        assertThat(condition.config())
                .containsEntry("mode", "expression")
                .containsEntry("expression", "(contains($node[\"Answer\"].text, 'refund'))");
        assertThat(hasEdge(wf, "400", "true", "500")).isTrue();
        assertThat(hasEdge(wf, "400", "false", "600")).isTrue();
    }

    @Test
    void multipleIfElseCasesBecomeAChainOfConditions() {
        JsonNode root =
                yaml(
                        """
            app: {mode: workflow, name: chain}
            kind: app
            workflow:
              graph:
                edges:
                - {id: a, source: s, target: c, sourceHandle: source}
                - {id: b, source: c, target: xa, sourceHandle: one}
                - {id: d, source: c, target: ya, sourceHandle: two}
                - {id: e, source: c, target: za, sourceHandle: 'false'}
                nodes:
                - {id: s, data: {type: start, title: Start}}
                - id: c
                  data:
                    type: if-else
                    title: Route
                    cases:
                    - case_id: one
                      logical_operator: or
                      conditions:
                      - {variable_selector: [s, a], comparison_operator: is, value: x}
                      - {variable_selector: [s, a], comparison_operator: is, value: y}
                    - case_id: two
                      logical_operator: and
                      conditions:
                      - {variable_selector: [s, num], comparison_operator: '>', value: 5, varType: number}
                - {id: xa, data: {type: end, title: X, outputs: []}}
                - {id: ya, data: {type: end, title: Y, outputs: []}}
                - {id: za, data: {type: end, title: Z, outputs: []}}
            """);
        PortableWorkflow wf = DifyImportMapper.map(MAPPER, root);
        assertThat(node(wf, "c").type()).isEqualTo("condition");
        assertThat(node(wf, "c__case2").type()).isEqualTo("condition");
        assertThat(node(wf, "c").config().get("expression").toString()).contains(" || ");
        assertThat(node(wf, "c__case2").config())
                .containsEntry("expression", "($node[\"Start\"].num > 5)");
        assertThat(hasEdge(wf, "c", "false", "c__case2")).isTrue();
        assertThat(hasEdge(wf, "c", "true", "xa")).isTrue();
        assertThat(hasEdge(wf, "c__case2", "true", "ya")).isTrue();
        assertThat(hasEdge(wf, "c__case2", "false", "za")).isTrue();
    }

    @Test
    void httpRequestKeepsHeadersAndTheBodyButNeverTheApiKey() throws Exception {
        PortableWorkflow wf = supportTriage();
        WorkflowNodeDto http = node(wf, "500");
        assertThat(http.type()).isEqualTo("http");
        assertThat(http.config())
                .containsEntry("method", "POST")
                .containsEntry("url", "https://api.example.com/tickets")
                .containsEntry("bodyType", "json");
        assertThat(String.valueOf(http.config().get("body")))
                .contains("{{ $node[\"Start\"].question }}");
        String all = MAPPER.writeValueAsString(wf.nodes);
        assertThat(all).doesNotContain(BEARER);
        // The secret environment variable becomes a vault reference, and the key a recognisable
        // marker.
        assertThat(all).contains("{{secret.API_TOKEN}}").contains("Bearer [[CREDENTIAL_500_");
        assertThat(wf.placeholders.get("secret:API_TOKEN").kind())
                .isEqualTo(PlaceholderRegistry.SECRET);
    }

    @Test
    void unsupportedNodesBecomeInertPlaceholders() {
        PortableWorkflow wf = supportTriage();
        WorkflowNodeDto merge = node(wf, "600");
        assertThat(merge.type()).isEqualTo("code");
        assertThat(merge.label()).isEqualTo("[Unsupported] Merge");
        assertThat(merge.config())
                .containsEntry("placeholder", Boolean.TRUE)
                .doesNotContainKey("template");
        @SuppressWarnings("unchecked")
        Map<String, Object> from = (Map<String, Object>) merge.config().get("importedFrom");
        assertThat(from)
                .containsEntry("platform", "dify")
                .containsEntry("type", "variable-aggregator");
        assertThat(wf.report.toReport().unsupported()).anyMatch(u -> u.what().contains("Merge"));
    }

    @Test
    void templateTransformSubstitutesItsDeclaredVariables() {
        WorkflowNodeDto format = node(supportTriage(), "700");
        assertThat(format.config())
                .containsEntry("operation", "template")
                .containsEntry("template", "Ticket: {{ $node[\"Ticket API\"].body }}");
    }

    @Test
    void jinjaLogicIsNotGuessed() {
        JsonNode root =
                yaml(
                        """
            app: {mode: workflow, name: j}
            kind: app
            workflow:
              graph:
                edges: []
                nodes:
                - {id: s, data: {type: start, title: Start}}
                - id: t
                  data:
                    type: template-transform
                    title: Loop
                    template: '{% for x in xs %}{{ x }}{% endfor %}'
                    variables: []
            """);
        WorkflowNodeDto t = node(DifyImportMapper.map(MAPPER, root), "t");
        assertThat(t.label()).isEqualTo("[Unsupported] Loop");
    }

    @Test
    void answerBecomesATemplateFollowedByAnOutput() {
        PortableWorkflow wf = supportTriage();
        WorkflowNodeDto reply = node(wf, "800");
        assertThat(reply.config()).containsEntry("template", "Done {{ $node[\"Format\"].output }}");
        WorkflowNodeDto output = node(wf, "800__out");
        assertThat(output.type()).isEqualTo("output");
        assertThat(output.config()).containsEntry("channel", "log");
        assertThat(hasEdge(wf, "800", null, "800__out")).isTrue();
        assertThat(hasEdge(wf, "700", null, "800")).isTrue();
    }

    @Test
    void secretsAreNamedNotImported() {
        ConversionReport report = supportTriage().report.toReport();
        assertThat(report.needsManual()).anyMatch(m -> m.what().contains("API_TOKEN"));
        assertThat(report.unsupported()).anyMatch(u -> u.what().contains("REGION"));
        assertThat(report.converted()).anyMatch(c -> c.what().contains("Draft only"));
    }

    @Test
    void fidelityCountsSourceNodesOnceEach() {
        PortableWorkflow.Fidelity f = supportTriage().fidelity;
        assertThat(f.sourceNodes).isEqualTo(8);
        assertThat(f.placeholders).isEqualTo(1);
        assertThat(f.exact + f.approximate + f.placeholders + f.skipped).isEqualTo(f.sourceNodes);
        assertThat(f.connectionsDropped).isZero();
        assertThat(f.connectionsKept).isEqualTo(7);
    }

    @Test
    void questionClassifierBecomesASupervisorWithOneRoutePerClass() {
        JsonNode root =
                yaml(
                        """
            app: {mode: advanced-chat, name: qc}
            kind: app
            workflow:
              graph:
                edges:
                - {id: a, source: s, target: q, sourceHandle: source}
                - {id: b, source: q, target: e1, sourceHandle: '1'}
                - {id: c, source: q, target: e2, sourceHandle: '2'}
                nodes:
                - {id: s, data: {type: start, title: Start}}
                - id: q
                  data:
                    type: question-classifier
                    title: Classify
                    instruction: Pick one.
                    classes: [{id: '1', name: Billing}, {id: '2', name: Tech}]
                - {id: e1, data: {type: end, title: E1, outputs: []}}
                - {id: e2, data: {type: end, title: E2, outputs: []}}
            """);
        PortableWorkflow wf = DifyImportMapper.map(MAPPER, root);
        WorkflowNodeDto q = node(wf, "q");
        assertThat(q.type()).isEqualTo("supervisor");
        assertThat(q.config()).containsEntry("agentId", "@placeholder:agent_1");
        assertThat(String.valueOf(q.config().get("routes")))
                .contains("handle=1")
                .contains("label=Billing")
                .contains("handle=2")
                .contains("label=Tech");
        assertThat(hasEdge(wf, "q", "1", "e1")).isTrue();
        assertThat(hasEdge(wf, "q", "2", "e2")).isTrue();
    }

    @Test
    void iterationBecomesASubFlowOverItsBodyNodes() {
        JsonNode root =
                yaml(
                        """
            app: {mode: workflow, name: it}
            kind: app
            workflow:
              graph:
                edges:
                - {id: a, source: s, target: i, sourceHandle: source}
                - {id: b, source: is, target: w, sourceHandle: source}
                nodes:
                - {id: s, data: {type: start, title: Start}}
                - id: i
                  data: {type: iteration, title: Each, iterator_selector: [s, items], output_selector: [w, text]}
                - id: is
                  parentId: i
                  data: {type: iteration-start, title: ' ', isInIteration: true, iteration_id: i}
                - id: w
                  parentId: i
                  data: {type: llm, title: Work, isInIteration: true, iteration_id: i, model: {name: m}}
            """);
        PortableWorkflow wf = DifyImportMapper.map(MAPPER, root);
        WorkflowNodeDto each = node(wf, "i");
        assertThat(each.type()).isEqualTo("sub_flow");
        assertThat(each.config()).containsEntry("itemsField", "items");
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) each.config().get("body");
        assertThat((List<?>) body.get("nodes")).hasSize(1);
        assertThat(body.get("nodes").toString()).contains("agent");
        assertThat(wf.placeholders.get("agent_1")).isNotNull();
        // Top-level graph only holds the start node and the sub-flow.
        assertThat(wf.nodes).hasSize(2);
    }

    @Test
    void nodesInsideANestedIterationAreCountedAndReportedNotSilentlyDropped() {
        JsonNode root =
                yaml(
                        """
            app: {mode: workflow, name: nested}
            kind: app
            workflow:
              graph:
                edges: []
                nodes:
                - {id: s, data: {type: start, title: Start}}
                - id: o
                  data: {type: iteration, title: Outer, iterator_selector: [s, items]}
                - id: x
                  parentId: o
                  data: {type: iteration, title: Inner, isInIteration: true, iteration_id: o, iterator_selector: [s, items]}
                - id: y
                  parentId: x
                  data: {type: llm, title: Deep, isInIteration: true, iteration_id: x, model: {name: m}}
            """);
        PortableWorkflow wf = DifyImportMapper.map(MAPPER, root);
        // Start, Outer, Inner (placeholder) and Deep: nothing disappears from the count.
        assertThat(wf.fidelity.sourceNodes).isEqualTo(4);
        assertThat(wf.fidelity.skipped).isEqualTo(1);
        assertThat(wf.report.toReport().unsupported())
                .anyMatch(u -> u.what().contains("Deep") && u.what().contains("Inner"));
    }

    @Test
    void pythonCodeIsAPlaceholderAndJavascriptAKeptSourceStub() {
        JsonNode root =
                yaml(
                        """
            app: {mode: workflow, name: c}
            kind: app
            workflow:
              graph:
                edges: []
                nodes:
                - {id: s, data: {type: start, title: Start}}
                - {id: p, data: {type: code, title: Py, code_language: python3, code: 'def main(): return 1'}}
                - {id: j, data: {type: code, title: Js, code_language: javascript, code: 'function main() { return 1 }'}}
            """);
        PortableWorkflow wf = DifyImportMapper.map(MAPPER, root);
        assertThat(node(wf, "p").label()).isEqualTo("[Unsupported] Py");
        WorkflowNodeDto js = node(wf, "j");
        assertThat(js.label()).isEqualTo("[Needs review] Js");
        assertThat(js.config())
                .containsEntry("importedSource", "function main() { return 1 }")
                .containsEntry("operation", "template")
                .doesNotContainKey("template");
    }

    @Test
    void aChatAppWithoutAWorkflowGraphIsRefusedWithAnExplanation() {
        assertThatThrownBy(
                        () ->
                                DifyImportMapper.map(
                                        MAPPER, yaml("kind: app\napp: {mode: chat, name: c}\n")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("workflow");
    }

    @Test
    void anUnresolvableReferenceIsLeftAsWrittenAndNoted() {
        JsonNode root =
                yaml(
                        """
            app: {mode: workflow, name: r}
            kind: app
            workflow:
              graph:
                edges: []
                nodes:
                - {id: s, data: {type: start, title: Start}}
                - id: l
                  data:
                    type: llm
                    title: Ask
                    prompt_template: [{role: user, text: 'Hi {{#conversation.memo#}} {{#ghost.x#}}'}]
            """);
        WorkflowNodeDto ask = node(DifyImportMapper.map(MAPPER, root), "l");
        assertThat(String.valueOf(ask.config().get("importedPrompt")))
                .contains("{{#conversation.memo#}}");
        assertThat(ask.data().get("importNotes").toString()).contains("no equivalent");
    }
}
