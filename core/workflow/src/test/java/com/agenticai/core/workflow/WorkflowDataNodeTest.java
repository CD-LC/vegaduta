package com.agenticai.core.workflow;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.approval.HumanApprovalGateService;
import com.agenticai.core.workflow.WorkflowNodeExecutor.NodeExecutionResult;
import com.agenticai.core.workflow.dto.WorkflowEdgeDto;
import com.agenticai.core.workflow.dto.WorkflowGraphDto;
import com.agenticai.core.workflow.dto.WorkflowNodeDto;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The data nodes as the rest of the engine sees them: dispatched by the executor (with expression
 * resolution in front), validated by {@link OrchestratorService#validate}, and routed by {@link
 * WorkflowGraphSupport}. The operations themselves are covered in {@link WorkflowDataOpsTest}.
 */
class WorkflowDataNodeTest {

    private static final ObjectMapper M = com.agenticai.core.json.JsonMappers.plain();

    // ---- helpers

    private static Map<String, Object> m(Object... kv) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            out.put((String) kv[i], kv[i + 1]);
        }
        return out;
    }

    private static Map<String, Object> cond(String field, String op, String value) {
        return m("field", field, "operator", op, "value", value);
    }

    private static WorkflowNodeDto node(String id, String type, Map<String, Object> config) {
        return new WorkflowNodeDto(id, type, Map.of(), Map.of("config", config, "label", id));
    }

    private static JsonNode j(String s) {
        try {
            return M.readTree(s);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static WorkflowEdgeDto edge(String id, String source, String target, String handle) {
        return new WorkflowEdgeDto(id, source, target, handle, null);
    }

    private final WorkflowNodeExecutor executor =
            WorkflowNodeExecutorPinTest.newExecutor(mock(OrchestratorService.class), M);

    private NodeExecutionResult run(WorkflowNodeDto node, String input) {
        return executor.executeNode("wf-1", "wf", "run-1", node, input, "tester");
    }

    private OrchestratorService orchestrator() {
        return new OrchestratorService(
                mock(AgentService.class),
                mock(HumanApprovalGateService.class),
                M,
                mock(ObjectProvider.class));
    }

    private List<String> errorsFrom(WorkflowGraphDto graph) {
        return assertThrows(WorkflowValidationException.class, () -> orchestrator().validate(graph))
                .getErrors();
    }

    private static WorkflowGraphDto graph(
            List<WorkflowNodeDto> extra, List<WorkflowEdgeDto> edges) {
        List<WorkflowNodeDto> nodes = new ArrayList<>();
        nodes.add(node("t", "trigger", Map.of("channel", "WEBHOOK")));
        nodes.addAll(extra);
        return new WorkflowGraphDto(nodes, edges);
    }

    // =====================================================================================================
    // registration
    // =====================================================================================================

    @Test
    void allEightWireValuesAreSupportedNodeTypes() {
        for (String wire :
                List.of(
                        "set",
                        "filter",
                        "sort",
                        "aggregate",
                        "limit",
                        "dedupe",
                        "switch",
                        "merge")) {
            assertTrue(WorkflowNodeType.fromWire(wire).isSupported(), wire);
            assertTrue(WorkflowDataNodeValidator.handles(wire), wire);
        }
        assertFalse(WorkflowDataNodeValidator.handles("agent"));
    }

    @Test
    void aSwitchIsNeverServedFromAPin() {
        assertTrue(WorkflowNodeExecutor.PIN_NOT_HONOURED_TYPES.contains("switch"));
    }

    // =====================================================================================================
    // executor dispatch + expression resolution
    // =====================================================================================================

    @Test
    void theExecutorRunsAFilterNode() {
        NodeExecutionResult r =
                run(
                        node("f", "filter", m("conditions", List.of(cond("p", "gt", "10")))),
                        "[{\"p\":5},{\"p\":15}]");
        assertEquals("OK", r.status());
        assertEquals(j("[{\"p\":15}]"), j(r.output()));
        assertNull(r.branch());
    }

    @Test
    void expressionValuesAreResolvedBeforeTheNodeRuns() {
        // {{ $json.a }} is the node's own input field; the Set node sees the resolved text "21" and
        // types it.
        NodeExecutionResult r =
                run(
                        node(
                                "s",
                                "set",
                                m(
                                        "assignments",
                                        List.of(
                                                m(
                                                        "name",
                                                        "copy",
                                                        "value",
                                                        "{{ $json.a }}",
                                                        "type",
                                                        "auto"),
                                                m(
                                                        "name",
                                                        "label",
                                                        "value",
                                                        "item {{ $json.a }}",
                                                        "type",
                                                        "string")))),
                        "{\"a\":21}");
        assertEquals(j("{\"a\":21,\"copy\":21,\"label\":\"item 21\"}"), j(r.output()));
    }

    @Test
    void anExpressionInsideANestedSwitchConditionIsResolved() {
        WorkflowNodeDto sw =
                node(
                        "sw",
                        "switch",
                        m(
                                "cases",
                                List.of(
                                        m(
                                                "name",
                                                "over",
                                                "conditions",
                                                List.of(cond("n", "gt", "{{ $json.limit }}"))))));
        assertEquals("over", run(sw, "{\"n\":5,\"limit\":3}").branch());
        assertEquals("default", run(sw, "{\"n\":2,\"limit\":3}").branch());
    }

    @Test
    void filterConditionValuesAndMergeSourcesAreExpressionFields() {
        assertEquals(List.of("assignments[].value"), ExpressionFields.paths("set"));
        assertEquals(List.of("conditions[].value"), ExpressionFields.paths("filter"));
        assertEquals(List.of("cases[].conditions[].value"), ExpressionFields.paths("switch"));
        assertEquals(List.of("count"), ExpressionFields.paths("limit"));
        assertEquals(List.of("sources[]"), ExpressionFields.paths("merge"));
        assertTrue(ExpressionFields.paths("sort").isEmpty());

        // merge sources: each {{ }} resolves to JSON text, then the node parses it
        NodeExecutionResult r =
                run(
                        node("m", "merge", m("sources", List.of("{{ $json.a }}", "{{ $json.b }}"))),
                        "{\"a\":[1,2],\"b\":{\"k\":3}}");
        assertEquals(j("[1,2,{\"k\":3}]"), j(r.output()));
    }

    @Test
    void aLimitCountMayBeAnExpression() {
        NodeExecutionResult r =
                run(
                        node("l", "limit", m("count", "{{ $json.n }}", "itemsField", "rows")),
                        "{\"n\":1,\"rows\":[1,2,3]}");
        assertEquals(j("{\"n\":1,\"rows\":[1]}"), j(r.output()));
    }

    @Test
    void aNodeThatCannotDoItsJobIsSkippedWithTheReasonAndPassesInputThrough() {
        NodeExecutionResult r =
                run(
                        node("f", "filter", m("conditions", List.of(cond("p", "gt", "1")))),
                        "just text");
        assertEquals("SKIPPED", r.status());
        assertEquals("just text", r.output());
        assertTrue(r.summary().contains("not a JSON array"), r.summary());
        assertNull(r.branch());
    }

    @Test
    void aFailingSwitchStillRoutesToItsDefaultPort() {
        NodeExecutionResult r =
                run(
                        node(
                                "sw",
                                "switch",
                                m(
                                        "cases",
                                        List.of(
                                                m(
                                                        "name",
                                                        "x",
                                                        "conditions",
                                                        List.of(cond("a", "zzz", "1")))))),
                        "{\"a\":1}");
        assertEquals("SKIPPED", r.status());
        assertEquals("default", r.branch());
        assertEquals("{\"a\":1}", r.output());
        assertTrue(r.summary().contains("defaulted to 'default'"), r.summary());
    }

    @Test
    void aMergeBelowItsMinimumIsSkippedNotSilentlyThin() {
        NodeExecutionResult r =
                run(
                        node("mg", "merge", m("sources", List.of("{\"a\":1}", ""), "minInputs", 2)),
                        "orig");
        assertEquals("SKIPPED", r.status());
        assertEquals("orig", r.output());
    }

    // =====================================================================================================
    // switch routing through the engine's own branch mechanism
    // =====================================================================================================

    @Test
    void switchBranchesAreMatchedAgainstEdgeSourceHandlesLikeCondition() {
        WorkflowGraphSupport support = new WorkflowGraphSupport(M);
        WorkflowNodeDto sw = node("sw", "switch", m());
        List<WorkflowEdgeDto> edges =
                List.of(
                        edge("e1", "sw", "t-big", "big"),
                        edge("e2", "sw", "t-def", "default"),
                        edge("e3", "sw", "t-err", "error"));
        assertEquals("t-big", support.nextNodeId(sw, edges, "big"));
        assertEquals("t-big", support.nextNodeId(sw, edges, "BIG"));
        assertEquals("t-def", support.nextNodeId(sw, edges, "default"));
        // a case with no edge simply ends that leg, exactly like an unwired condition port
        assertNull(support.nextNodeId(sw, List.of(edge("e1", "sw", "t-big", "big")), "default"));
    }

    // =====================================================================================================
    // validation
    // =====================================================================================================

    @Test
    void wellFormedDataNodesPassValidation() {
        WorkflowGraphDto g =
                graph(
                        List.of(
                                node(
                                        "a",
                                        "set",
                                        m(
                                                "assignments",
                                                List.of(
                                                        m(
                                                                "name", "x", "value", "1", "type",
                                                                "number")))),
                                node("b", "filter", m("conditions", List.of(cond("p", "gt", "1")))),
                                node(
                                        "c",
                                        "sort",
                                        m(
                                                "sortKeys",
                                                List.of(
                                                        m(
                                                                "field",
                                                                "p",
                                                                "direction",
                                                                "desc",
                                                                "type",
                                                                "number")))),
                                node(
                                        "d",
                                        "aggregate",
                                        m(
                                                "aggregations",
                                                List.of(m("operation", "sum", "field", "p")))),
                                node("e", "limit", m("count", 5, "mode", "last")),
                                node("f", "dedupe", m("dedupeBy", "id", "keep", "last")),
                                node(
                                        "g",
                                        "switch",
                                        m(
                                                "cases",
                                                List.of(
                                                        m(
                                                                "name",
                                                                "big",
                                                                "conditions",
                                                                List.of(cond("p", "gt", "100")))))),
                                node(
                                        "h",
                                        "merge",
                                        m("mode", "join", "key", "id", "joinType", "outer"))),
                        List.of(
                                new WorkflowEdgeDto("e0", "t", "a", null, null),
                                new WorkflowEdgeDto("e1", "a", "b", null, null),
                                new WorkflowEdgeDto("e2", "b", "c", null, null),
                                new WorkflowEdgeDto("e3", "c", "d", null, null),
                                new WorkflowEdgeDto("e4", "d", "e", null, null),
                                new WorkflowEdgeDto("e5", "e", "f", null, null),
                                new WorkflowEdgeDto("e6", "f", "g", null, null),
                                new WorkflowEdgeDto("e7", "g", "h", "big", null),
                                new WorkflowEdgeDto("e8", "g", "e", "default", null)));
        // e8 closes a cycle (g -> e -> ... -> g); drop it for the clean-pass assertion
        WorkflowGraphDto clean = new WorkflowGraphDto(g.nodes(), g.edges().subList(0, 8));
        assertDoesNotThrow(() -> orchestrator().validate(clean));
    }

    @Test
    void setNeedsFieldsAndWellFormedLiterals() {
        List<String> none = errorsFrom(graph(List.of(node("a", "set", m())), List.of()));
        assertTrue(none.stream().anyMatch(e -> e.contains("no fields to set")), none.toString());
        List<String> badNumber =
                errorsFrom(
                        graph(
                                List.of(
                                        node(
                                                "a",
                                                "set",
                                                m(
                                                        "assignments",
                                                        List.of(
                                                                m(
                                                                        "name", "x", "value", "abc",
                                                                        "type", "number"))))),
                                List.of()));
        assertTrue(
                badNumber.stream().anyMatch(e -> e.contains("is not a number")),
                badNumber.toString());
        List<String> noName =
                errorsFrom(
                        graph(
                                List.of(
                                        node(
                                                "a",
                                                "set",
                                                m(
                                                        "assignments",
                                                        List.of(m("name", " ", "value", "1"))))),
                                List.of()));
        assertTrue(noName.stream().anyMatch(e -> e.contains("no name")), noName.toString());
        // an expression value is only known at run time: no literal check
        assertDoesNotThrow(
                () ->
                        orchestrator()
                                .validate(
                                        graph(
                                                List.of(
                                                        node(
                                                                "a",
                                                                "set",
                                                                m(
                                                                        "assignments",
                                                                        List.of(
                                                                                m(
                                                                                        "name",
                                                                                        "x",
                                                                                        "value",
                                                                                        "{{ $json.n }}",
                                                                                        "type",
                                                                                        "number"))))),
                                                List.of())));
    }

    @Test
    void filterNeedsConditionsWithKnownOperatorsAndSaneRegexes() {
        List<String> none = errorsFrom(graph(List.of(node("a", "filter", m())), List.of()));
        assertTrue(none.stream().anyMatch(e -> e.contains("no condition")), none.toString());
        List<String> badOp =
                errorsFrom(
                        graph(
                                List.of(
                                        node(
                                                "a",
                                                "filter",
                                                m(
                                                        "conditions",
                                                        List.of(cond("p", "wobbles", "1"))))),
                                List.of()));
        assertTrue(badOp.stream().anyMatch(e -> e.contains("unknown operator")), badOp.toString());
        List<String> badRegex =
                errorsFrom(
                        graph(
                                List.of(
                                        node(
                                                "a",
                                                "filter",
                                                m(
                                                        "conditions",
                                                        List.of(cond("p", "regex", "(open"))))),
                                List.of()));
        assertTrue(
                badRegex.stream().anyMatch(e -> e.contains("invalid regex")), badRegex.toString());
        List<String> longRegex =
                errorsFrom(
                        graph(
                                List.of(
                                        node(
                                                "a",
                                                "filter",
                                                m(
                                                        "conditions",
                                                        List.of(
                                                                cond(
                                                                        "p",
                                                                        "regex",
                                                                        "a".repeat(201)))))),
                                List.of()));
        assertTrue(
                longRegex.stream().anyMatch(e -> e.contains("longer than")), longRegex.toString());
        List<String> badCombinator =
                errorsFrom(
                        graph(
                                List.of(
                                        node(
                                                "a",
                                                "filter",
                                                m(
                                                        "combinator",
                                                        "xor",
                                                        "conditions",
                                                        List.of(cond("p", "eq", "1"))))),
                                List.of()));
        assertTrue(
                badCombinator.stream().anyMatch(e -> e.contains("invalid combinator")),
                badCombinator.toString());
    }

    @Test
    void sortAggregateLimitDedupeMergeRejectBadConfig() {
        assertTrue(
                errorsFrom(graph(List.of(node("a", "sort", m())), List.of()))
                        .toString()
                        .contains("no sort key"));
        assertTrue(
                errorsFrom(
                                graph(
                                        List.of(
                                                node(
                                                        "a",
                                                        "sort",
                                                        m(
                                                                "sortKeys",
                                                                List.of(
                                                                        m(
                                                                                "field",
                                                                                "p",
                                                                                "direction",
                                                                                "up"))))),
                                        List.of()))
                        .toString()
                        .contains("invalid direction"));
        assertTrue(
                errorsFrom(graph(List.of(node("a", "aggregate", m())), List.of()))
                        .toString()
                        .contains("nothing to calculate"));
        assertTrue(
                errorsFrom(
                                graph(
                                        List.of(
                                                node(
                                                        "a",
                                                        "aggregate",
                                                        m(
                                                                "aggregations",
                                                                List.of(
                                                                        m(
                                                                                "operation",
                                                                                "median"))))),
                                        List.of()))
                        .toString()
                        .contains("unknown operation"));
        assertTrue(
                errorsFrom(graph(List.of(node("a", "limit", m())), List.of()))
                        .toString()
                        .contains("no count"));
        assertTrue(
                errorsFrom(graph(List.of(node("a", "limit", m("count", -2))), List.of()))
                        .toString()
                        .contains("whole number"));
        assertTrue(
                errorsFrom(graph(List.of(node("a", "dedupe", m("keep", "middle"))), List.of()))
                        .toString()
                        .contains("invalid keep"));
        assertTrue(
                errorsFrom(graph(List.of(node("a", "merge", m("mode", "zip"))), List.of()))
                        .toString()
                        .contains("unknown mode"));
        assertTrue(
                errorsFrom(graph(List.of(node("a", "merge", m("mode", "join"))), List.of()))
                        .toString()
                        .contains("no key field"));
        assertTrue(
                errorsFrom(graph(List.of(node("a", "merge", m("minInputs", "many"))), List.of()))
                        .toString()
                        .contains("minimum inputs"));
    }

    @Test
    void switchCaseNamesMustBeUniqueNonReservedAndHaveConditions() {
        List<String> none = errorsFrom(graph(List.of(node("a", "switch", m())), List.of()));
        assertTrue(none.stream().anyMatch(e -> e.contains("no cases")), none.toString());

        List<String> dup =
                errorsFrom(
                        graph(
                                List.of(
                                        node(
                                                "a",
                                                "switch",
                                                m(
                                                        "cases",
                                                        List.of(
                                                                m(
                                                                        "name",
                                                                        "Big",
                                                                        "conditions",
                                                                        List.of(
                                                                                cond(
                                                                                        "p", "gt",
                                                                                        "1"))),
                                                                m(
                                                                        "name",
                                                                        "big",
                                                                        "conditions",
                                                                        List.of(
                                                                                cond(
                                                                                        "p", "gt",
                                                                                        "2"))))))),
                                List.of()));
        assertTrue(dup.stream().anyMatch(e -> e.contains("unique")), dup.toString());

        List<String> reserved =
                errorsFrom(
                        graph(
                                List.of(
                                        node(
                                                "a",
                                                "switch",
                                                m(
                                                        "cases",
                                                        List.of(
                                                                m(
                                                                        "name",
                                                                        "default",
                                                                        "conditions",
                                                                        List.of(
                                                                                cond(
                                                                                        "p", "gt",
                                                                                        "1"))),
                                                                m(
                                                                        "name",
                                                                        "error",
                                                                        "conditions",
                                                                        List.of(
                                                                                cond(
                                                                                        "p", "gt",
                                                                                        "1"))))))),
                                List.of()));
        assertEquals(
                2,
                reserved.stream().filter(e -> e.contains("reserved")).count(),
                reserved.toString());

        List<String> unnamed =
                errorsFrom(
                        graph(
                                List.of(
                                        node(
                                                "a",
                                                "switch",
                                                m(
                                                        "cases",
                                                        List.of(
                                                                m(
                                                                        "name",
                                                                        "",
                                                                        "conditions",
                                                                        List.of(
                                                                                cond(
                                                                                        "p", "gt",
                                                                                        "1"))),
                                                                m("name", "x"))))),
                                List.of()));
        assertTrue(unnamed.stream().anyMatch(e -> e.contains("no name")), unnamed.toString());
        assertTrue(unnamed.stream().anyMatch(e -> e.contains("no condition")), unnamed.toString());
    }

    @Test
    void switchEdgesMustLeaveFromACaseDefaultOrErrorPort() {
        WorkflowNodeDto sw =
                node(
                        "sw",
                        "switch",
                        m(
                                "cases",
                                List.of(
                                        m(
                                                "name",
                                                "big",
                                                "conditions",
                                                List.of(cond("p", "gt", "100"))))));
        WorkflowNodeDto n1 = node("n1", "output", m("channel", "log"));
        WorkflowNodeDto n2 = node("n2", "output", m("channel", "log"));

        assertDoesNotThrow(
                () ->
                        orchestrator()
                                .validate(
                                        graph(
                                                List.of(sw, n1, n2),
                                                List.of(
                                                        edge("e0", "t", "sw", null),
                                                        edge("e1", "sw", "n1", "big"),
                                                        edge("e2", "sw", "n2", "DEFAULT")))));

        List<String> stale =
                errorsFrom(
                        graph(
                                List.of(sw, n1),
                                List.of(
                                        edge("e0", "t", "sw", null),
                                        edge("e1", "sw", "n1", "renamed-case"))));
        assertTrue(stale.stream().anyMatch(e -> e.contains("renamed-case")), stale.toString());

        List<String> unattached =
                errorsFrom(
                        graph(
                                List.of(sw, n1),
                                List.of(
                                        edge("e0", "t", "sw", null),
                                        edge("e1", "sw", "n1", null))));
        assertTrue(
                unattached.stream().anyMatch(e -> e.contains("not attached to a case")),
                unattached.toString());
    }
}
