package com.agenticai.core.workflow.portability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agenticai.core.agents.importer.AgentImportDtos.ConversionReport;
import com.agenticai.core.workflow.dto.WorkflowEdgeDto;
import com.agenticai.core.workflow.dto.WorkflowNodeDto;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Golden fixtures for the n8n importer: small realistic exports written for this test (not copied
 * from n8n). The point of each assertion is the honesty of the report: what mapped, what needs a
 * person, what became an inert placeholder, and that no credential value or id survives.
 */
class N8nImportMapperTest {

    private static final ObjectMapper MAPPER = com.agenticai.core.json.JsonMappers.plain();
    private static final String BEARER = "abcdefghijklmnopqrstuvwxyz0123456789";

    /** JSON with single quotes standing in for double quotes. */
    private static JsonNode json(String singleQuoted) throws Exception {
        return MAPPER.readTree(singleQuoted.replace('\'', '"'));
    }

    private static final String ORDER_ALERTS =
            """
        {
          'name': 'Order alerts',
          'nodes': [
            {'parameters': {'httpMethod': 'POST', 'path': 'orders'}, 'id': 'w1', 'name': 'Webhook',
             'type': 'n8n-nodes-base.webhook', 'typeVersion': 1, 'position': [0, 0], 'webhookId': 'abc-123'},
            {'parameters': {'method': 'POST', 'url': '=https://api.example.com/orders/{{ $json.id }}',
               'sendHeaders': true,
               'headerParameters': {'parameters': [
                 {'name': 'Authorization', 'value': 'Bearer %s'}, {'name': 'X-Trace', 'value': '1'}]},
               'sendBody': true, 'contentType': 'json', 'specifyBody': 'json', 'jsonBody': '={{ $json }}',
               'options': {'timeout': 5000, 'redirect': {'redirect': {}}}},
             'id': 'h1', 'name': 'Fetch order', 'type': 'n8n-nodes-base.httpRequest', 'typeVersion': 4.2,
             'position': [250, 0],
             'credentials': {'httpHeaderAuth': {'id': '7788', 'name': 'Orders API key'}}},
            {'parameters': {'conditions': {'options': {}, 'conditions': [
               {'id': 'c1', 'leftValue': '={{ $json.total }}', 'rightValue': 1000,
                'operator': {'type': 'number', 'operation': 'gt'}}], 'combinator': 'and'}},
             'id': 'i1', 'name': 'Big order?', 'type': 'n8n-nodes-base.if', 'typeVersion': 2, 'position': [500, 0]},
            {'parameters': {'channel': '#ops', 'text': 'Big order'}, 'id': 's1', 'name': 'Notify Slack',
             'type': 'n8n-nodes-base.slack', 'typeVersion': 2, 'position': [750, -100],
             'credentials': {'slackApi': {'id': '9', 'name': 'Ops Slack'}}},
            {'parameters': {'jsCode': 'return items;'}, 'id': 'c1', 'name': 'Tidy',
             'type': 'n8n-nodes-base.code', 'typeVersion': 2, 'position': [750, 100]},
            {'parameters': {'rules': {'values': []}}, 'id': 'sw', 'name': 'Route',
             'type': 'n8n-nodes-base.switch', 'typeVersion': 3, 'position': [1000, 100]},
            {'parameters': {'respondWith': 'json'}, 'id': 'r1', 'name': 'Reply',
             'type': 'n8n-nodes-base.respondToWebhook', 'typeVersion': 1, 'position': [1250, 0]},
            {'parameters': {'content': 'remember'}, 'id': 'n1', 'name': 'Sticky Note',
             'type': 'n8n-nodes-base.stickyNote', 'typeVersion': 1, 'position': [0, 200]}
          ],
          'connections': {
            'Webhook': {'main': [[{'node': 'Fetch order', 'type': 'main', 'index': 0}]]},
            'Fetch order': {'main': [[{'node': 'Big order?', 'type': 'main', 'index': 0}]]},
            'Big order?': {'main': [[{'node': 'Notify Slack', 'type': 'main', 'index': 0}],
                                    [{'node': 'Tidy', 'type': 'main', 'index': 0}]]},
            'Notify Slack': {'main': [[{'node': 'Reply', 'type': 'main', 'index': 0}]]},
            'Tidy': {'main': [[{'node': 'Route', 'type': 'main', 'index': 0}]]},
            'Route': {'main': [[{'node': 'Reply', 'type': 'main', 'index': 0}],
                               [{'node': 'Reply', 'type': 'main', 'index': 0}]]}
          },
          'active': true,
          'settings': {'executionOrder': 'v1'},
          'pinData': {'Webhook': [{'json': {'a': 1}}]}
        }
        """
                    .formatted(BEARER);

    private static PortableWorkflow orderAlerts() throws Exception {
        return N8nImportMapper.map(MAPPER, json(ORDER_ALERTS));
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
    void detectedAsN8nAndStickyNotesAreNotNodes() throws Exception {
        assertThat(PortabilityFormat.detect(json(ORDER_ALERTS))).contains(PortabilityFormat.N8N);
        PortableWorkflow wf = orderAlerts();
        assertThat(wf.name).isEqualTo("Order alerts");
        assertThat(wf.nodes).hasSize(7);
        assertThat(wf.report.toReport().unsupported())
                .anyMatch(u -> u.what().contains("Sticky note"));
    }

    @Test
    void webhookBecomesAWebhookTriggerWithoutItsPathOrId() throws Exception {
        PortableWorkflow wf = orderAlerts();
        WorkflowNodeDto trigger = node(wf, "n1");
        assertThat(trigger.type()).isEqualTo("trigger");
        assertThat(trigger.config()).containsEntry("channel", "WEBHOOK");
        assertThat(MAPPER.writeValueAsString(wf.nodes)).doesNotContain("abc-123");
    }

    @Test
    void httpRequestMapsMethodUrlBodyAndTimeoutAndStripsTheInlineBearerToken() throws Exception {
        PortableWorkflow wf = orderAlerts();
        WorkflowNodeDto http = node(wf, "n2");
        assertThat(http.type()).isEqualTo("http");
        assertThat(http.config())
                .containsEntry("method", "POST")
                .containsEntry("url", "https://api.example.com/orders/{{ $json.id }}")
                .containsEntry("bodyType", "json")
                .containsEntry("timeoutSeconds", 5);
        String all = MAPPER.writeValueAsString(wf.nodes);
        assertThat(all).doesNotContain(BEARER).contains("[[CREDENTIAL_n2_");
        assertThat(http.data().get("needsReview")).isEqualTo(Boolean.TRUE);
        assertThat(http.data().get("importNotes").toString()).contains("redirect");
    }

    @Test
    void credentialsAreNeverImportedOnlyTheirNamesAsPlaceholders() throws Exception {
        PortableWorkflow wf = orderAlerts();
        String everything =
                MAPPER.writeValueAsString(wf.nodes)
                        + MAPPER.writeValueAsString(wf.report.toReport());
        assertThat(everything).doesNotContain("7788").doesNotContain("\"9\"");
        List<String> labels =
                wf.placeholders.entries().stream().map(PlaceholderRegistry.Entry::label).toList();
        assertThat(labels)
                .anyMatch(l -> l.contains("Orders API key"))
                .anyMatch(l -> l.contains("Ops Slack"));
        assertThat(wf.report.toReport().needsManual())
                .anyMatch(m -> m.what().contains("Orders API key"));
    }

    @Test
    void ifBecomesAnExpressionConditionWithTrueAndFalseBranches() throws Exception {
        PortableWorkflow wf = orderAlerts();
        WorkflowNodeDto condition = node(wf, "n3");
        assertThat(condition.type()).isEqualTo("condition");
        assertThat(condition.config())
                .containsEntry("mode", "expression")
                .containsEntry("expression", "(($json.total) > 1000)");
        assertThat(hasEdge(wf, "n3", "true", "n4")).isTrue();
        assertThat(hasEdge(wf, "n3", "false", "n5")).isTrue();
    }

    @Test
    void unsupportedNodesBecomeInertPlaceholdersThatKeepTheirIdentity() throws Exception {
        PortableWorkflow wf = orderAlerts();
        WorkflowNodeDto slack = node(wf, "n4");
        assertThat(slack.type()).isEqualTo("code");
        assertThat(slack.label()).isEqualTo("[Unsupported] Notify Slack");
        assertThat(slack.config())
                .containsEntry("operation", "template")
                .containsEntry("placeholder", Boolean.TRUE);
        @SuppressWarnings("unchecked")
        Map<String, Object> from = (Map<String, Object>) slack.config().get("importedFrom");
        assertThat(from)
                .containsEntry("platform", "n8n")
                .containsEntry("type", "n8n-nodes-base.slack")
                .containsEntry("name", "Notify Slack");
        // No template key: the Transform node passes its input through unchanged.
        assertThat(slack.config()).doesNotContainKey("template");

        // Switch has no equivalent here either, and keeps its numbered outputs on the edges.
        WorkflowNodeDto route = node(wf, "n6");
        assertThat(route.label()).isEqualTo("[Unsupported] Route");
        assertThat(hasEdge(wf, "n6", "output_0", "n7")).isTrue();
        assertThat(hasEdge(wf, "n6", "output_1", "n7")).isTrue();

        ConversionReport report = wf.report.toReport();
        assertThat(report.unsupported())
                .anyMatch(u -> u.what().contains("Notify Slack"))
                .anyMatch(u -> u.what().contains("Route") && u.why().contains("no Switch"));
    }

    @Test
    void javascriptCodeIsKeptAsTextInAReviewStubAndNeverExecutable() throws Exception {
        PortableWorkflow wf = orderAlerts();
        WorkflowNodeDto code = node(wf, "n5");
        assertThat(code.type()).isEqualTo("code");
        assertThat(code.label()).isEqualTo("[Needs review] Tidy");
        assertThat(code.config())
                .containsEntry("operation", "template")
                .containsEntry("importedSource", "return items;")
                .containsEntry("importedLanguage", "javascript")
                .doesNotContainKey("template");
        assertThat(wf.report.toReport().needsManual())
                .anyMatch(m -> m.what().contains("Tidy") && m.why().contains("never executed"));
    }

    @Test
    void pythonCodeIsAPlaceholderNotAStub() throws Exception {
        JsonNode root =
                json(
                        """
            {'name': 'py', 'nodes': [
              {'parameters': {}, 'name': 'Start', 'type': 'n8n-nodes-base.manualTrigger', 'position': [0, 0]},
              {'parameters': {'language': 'python', 'pythonCode': 'print(1)'}, 'name': 'Py',
               'type': 'n8n-nodes-base.code', 'typeVersion': 2, 'position': [200, 0]}],
             'connections': {'Start': {'main': [[{'node': 'Py', 'type': 'main', 'index': 0}]]}}}
            """);
        PortableWorkflow wf = N8nImportMapper.map(MAPPER, root);
        assertThat(node(wf, "n2").label()).isEqualTo("[Unsupported] Py");
        assertThat(wf.report.toReport().unsupported()).anyMatch(u -> u.why().contains("Python"));
    }

    @Test
    void respondToWebhookBecomesALogOutput() throws Exception {
        WorkflowNodeDto reply = node(orderAlerts(), "n7");
        assertThat(reply.type()).isEqualTo("output");
        assertThat(reply.config()).containsEntry("channel", "log");
    }

    @Test
    void fidelityCountsEverySourceNodeExactlyOnce() throws Exception {
        PortableWorkflow.Fidelity f = orderAlerts().fidelity;
        assertThat(f.sourceNodes).isEqualTo(8);
        assertThat(f.exact).isEqualTo(1);
        assertThat(f.approximate).isEqualTo(4);
        assertThat(f.placeholders).isEqualTo(2);
        assertThat(f.skipped).isEqualTo(1);
        assertThat(f.exact + f.approximate + f.placeholders + f.skipped).isEqualTo(f.sourceNodes);
        assertThat(f.sourceConnections).isEqualTo(8);
        assertThat(f.connectionsKept).isEqualTo(8);
        assertThat(f.connectionsDropped).isZero();
    }

    @Test
    void workflowLevelNotesAreHonest() throws Exception {
        ConversionReport report = orderAlerts().report.toReport();
        assertThat(report.warnings())
                .anyMatch(w -> w.contains("active in n8n") && w.contains("DRAFT"));
        assertThat(report.warnings()).anyMatch(w -> w.contains("settings"));
        assertThat(report.unsupported()).anyMatch(u -> u.what().contains("Pinned"));
        assertThat(report.converted()).anyMatch(c -> c.what().contains("Draft only"));
    }

    @Test
    void splitInBatchesBecomesALoopAndTheBackConnectionIsRemoved() throws Exception {
        JsonNode root =
                json(
                        """
            {'name': 'loop', 'nodes': [
              {'parameters': {}, 'name': 'Manual', 'type': 'n8n-nodes-base.manualTrigger', 'position': [0, 0]},
              {'parameters': {'batchSize': 1}, 'name': 'Split', 'type': 'n8n-nodes-base.splitInBatches',
               'typeVersion': 3, 'position': [200, 0]},
              {'parameters': {}, 'name': 'Work', 'type': 'n8n-nodes-base.noOp', 'position': [400, 0]},
              {'parameters': {}, 'name': 'After', 'type': 'n8n-nodes-base.noOp', 'position': [600, 0]}],
             'connections': {
               'Manual': {'main': [[{'node': 'Split', 'type': 'main', 'index': 0}]]},
               'Split': {'main': [[{'node': 'After', 'type': 'main', 'index': 0}],
                                  [{'node': 'Work', 'type': 'main', 'index': 0}]]},
               'Work': {'main': [[{'node': 'Split', 'type': 'main', 'index': 0}]]}}}
            """);
        PortableWorkflow wf = N8nImportMapper.map(MAPPER, root);
        assertThat(node(wf, "n2").type()).isEqualTo("loop");
        assertThat(hasEdge(wf, "n1", null, "n2")).isTrue();
        assertThat(hasEdge(wf, "n2", null, "n3")).isTrue();
        assertThat(hasEdge(wf, "n3", null, "n2")).isFalse();
        // The "done" output is reattached after the loop body so the flow still continues.
        assertThat(hasEdge(wf, "n3", null, "n4")).isTrue();
        assertThat(wf.edges).hasSize(3);
        assertThat(wf.report.toReport().unsupported())
                .anyMatch(u -> u.what().contains("Loop-back"));
        assertThat(node(wf, "n2").data().get("needsReview")).isEqualTo(Boolean.TRUE);
    }

    @Test
    void aiComponentsAreKeptOnTheAgentNotImportedAsSteps() throws Exception {
        JsonNode root =
                json(
                        """
            {'name': 'chat', 'nodes': [
              {'parameters': {}, 'name': 'Chat', 'type': '@n8n/n8n-nodes-langchain.chatTrigger', 'position': [0, 0]},
              {'parameters': {'text': '={{ $json.chatInput }}', 'options': {'systemMessage': 'Be brief.'}},
               'name': 'AI Agent', 'type': '@n8n/n8n-nodes-langchain.agent', 'typeVersion': 1.7,
               'position': [200, 0]},
              {'parameters': {'model': 'gpt-4o'}, 'name': 'OpenAI Chat Model',
               'type': '@n8n/n8n-nodes-langchain.lmChatOpenAi', 'position': [200, 200],
               'credentials': {'openAiApi': {'id': '5', 'name': 'My OpenAI'}}}],
             'connections': {
               'Chat': {'main': [[{'node': 'AI Agent', 'type': 'main', 'index': 0}]]},
               'OpenAI Chat Model': {'ai_languageModel': [[{'node': 'AI Agent', 'type': 'ai_languageModel', 'index': 0}]]}}}
            """);
        PortableWorkflow wf = N8nImportMapper.map(MAPPER, root);
        assertThat(wf.nodes).hasSize(2);
        WorkflowNodeDto agent = node(wf, "n2");
        assertThat(agent.type()).isEqualTo("agent");
        assertThat(agent.config())
                .containsEntry("agentId", "@placeholder:agent_1")
                .containsEntry("importedInstructions", "Be brief.");
        assertThat(wf.placeholders.get("agent_1").kind()).isEqualTo(PlaceholderRegistry.AGENT);
        assertThat((List<?>) agent.config().get("importedSubNodes")).hasSize(1);
        assertThat(wf.report.toReport().unsupported())
                .anyMatch(u -> u.what().contains("AI component"));
        assertThat(wf.fidelity.connectionsDropped).isEqualTo(1);
    }

    @Test
    void aDisabledNodeBecomesAPassThrough() throws Exception {
        JsonNode root =
                json(
                        """
            {'name': 'd', 'nodes': [
              {'parameters': {}, 'name': 'Start', 'type': 'n8n-nodes-base.manualTrigger', 'position': [0, 0]},
              {'parameters': {'method': 'GET', 'url': 'https://example.com'}, 'name': 'Off', 'disabled': true,
               'type': 'n8n-nodes-base.httpRequest', 'typeVersion': 4, 'position': [200, 0]}],
             'connections': {'Start': {'main': [[{'node': 'Off', 'type': 'main', 'index': 0}]]}}}
            """);
        WorkflowNodeDto off = node(N8nImportMapper.map(MAPPER, root), "n2");
        assertThat(off.type()).isEqualTo("code");
        assertThat(off.label()).isEqualTo("[Disabled] Off");
        assertThat(off.config()).doesNotContainKey("url");
    }

    @Test
    void nodesWithMultipleTargetsOnOneOutputAreFlagged() throws Exception {
        JsonNode root =
                json(
                        """
            {'name': 'fan', 'nodes': [
              {'parameters': {}, 'name': 'Start', 'type': 'n8n-nodes-base.manualTrigger', 'position': [0, 0]},
              {'parameters': {}, 'name': 'A', 'type': 'n8n-nodes-base.noOp', 'position': [200, 0]},
              {'parameters': {}, 'name': 'B', 'type': 'n8n-nodes-base.noOp', 'position': [200, 100]}],
             'connections': {'Start': {'main': [[{'node': 'A', 'type': 'main', 'index': 0},
                                                 {'node': 'B', 'type': 'main', 'index': 0}]]}}}
            """);
        PortableWorkflow wf = N8nImportMapper.map(MAPPER, root);
        assertThat(wf.edges).hasSize(2);
        assertThat(wf.report.toReport().warnings()).anyMatch(w -> w.contains("Parallel"));
    }

    @Test
    void aFileWithoutNodesIsRefused() throws Exception {
        assertThatThrownBy(
                        () -> N8nImportMapper.map(MAPPER, json("{'name': 'x', 'connections': {}}")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void hostileShapesDegradeToWarningsNotExceptions() throws Exception {
        JsonNode root =
                json(
                        """
            {'name': 5, 'nodes': ['text', {'name': 7, 'type': 'n8n-nodes-base.noOp'}, {'type': 12},
              {'name': 'Ok', 'type': 'n8n-nodes-base.noOp', 'parameters': 'not an object', 'position': 'x'}],
             'connections': {'Ghost': {'main': [[{'node': 'Nobody'}]]}, 'Ok': 'nope'}}
            """);
        PortableWorkflow wf = N8nImportMapper.map(MAPPER, root);
        assertThat(wf.nodes).isNotEmpty();
        assertThat(wf.report.toReport().warnings()).isNotEmpty();
    }

    @Test
    void duplicateAiLinksAreKeptOnce() throws Exception {
        StringBuilder links = new StringBuilder();
        for (int i = 0; i < 300; i++) {
            links.append(i == 0 ? "" : ",")
                    .append("{'node': 'AI Agent', 'type': 'ai_tool', 'index': 0}");
        }
        PortableWorkflow wf =
                N8nImportMapper.map(
                        MAPPER,
                        json(
                                """
            {'name': 'dup', 'nodes': [
              {'parameters': {}, 'name': 'Chat', 'type': '@n8n/n8n-nodes-langchain.chatTrigger', 'position': [0, 0]},
              {'parameters': {'text': 'x'}, 'name': 'AI Agent', 'type': '@n8n/n8n-nodes-langchain.agent',
               'position': [200, 0]},
              {'parameters': {'q': 1}, 'name': 'Tool', 'type': '@n8n/n8n-nodes-langchain.toolCode',
               'position': [200, 200]}],
             'connections': {
               'Chat': {'main': [[{'node': 'AI Agent', 'type': 'main', 'index': 0}]]},
               'Tool': {'ai_tool': [[%s]]}}}
            """
                                        .formatted(links)));
        assertThat((List<?>) node(wf, "n2").config().get("importedSubNodes")).hasSize(1);
    }

    @Test
    void subNodesPerParentAreCappedWithOneWarning() throws Exception {
        StringBuilder nodes = new StringBuilder();
        StringBuilder conns = new StringBuilder();
        for (int i = 0; i < 25; i++) {
            nodes.append(",{'parameters': {}, 'name': 'T")
                    .append(i)
                    .append("', 'type': '@n8n/n8n-nodes-langchain.toolCode', 'position': [0, 0]}");
            conns.append(",'T")
                    .append(i)
                    .append(
                            "': {'ai_tool': [[{'node': 'AI Agent', 'type': 'ai_tool', 'index': 0}]]}");
        }
        PortableWorkflow wf =
                N8nImportMapper.map(
                        MAPPER,
                        json(
                                """
            {'name': 'cap', 'nodes': [
              {'parameters': {}, 'name': 'Chat', 'type': '@n8n/n8n-nodes-langchain.chatTrigger', 'position': [0, 0]},
              {'parameters': {'text': 'x'}, 'name': 'AI Agent', 'type': '@n8n/n8n-nodes-langchain.agent',
               'position': [200, 0]}%s],
             'connections': {
               'Chat': {'main': [[{'node': 'AI Agent', 'type': 'main', 'index': 0}]]}%s}}
            """
                                        .formatted(nodes, conns)));
        assertThat((List<?>) node(wf, "n2").config().get("importedSubNodes")).hasSize(20);
        assertThat(wf.report.toReport().warnings())
                .filteredOn(w -> w.contains("AI components are attached"))
                .hasSize(1);
    }

    @Test
    void tooManyConnectionsAreRefused() throws Exception {
        StringBuilder links = new StringBuilder();
        for (int i = 0; i <= WorkflowDslImporter.MAX_EDGES; i++) {
            links.append(i == 0 ? "" : ",").append("{'node': 'B', 'type': 'main', 'index': 0}");
        }
        JsonNode root =
                json(
                        """
            {'name': 'big', 'nodes': [
              {'parameters': {}, 'name': 'A', 'type': 'n8n-nodes-base.manualTrigger', 'position': [0, 0]},
              {'parameters': {}, 'name': 'B', 'type': 'n8n-nodes-base.noOp', 'position': [200, 0]}],
             'connections': {'A': {'main': [[%s]]}}}
            """
                                .formatted(links));
        assertThatThrownBy(() -> N8nImportMapper.map(MAPPER, root))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("connections");
    }
}
