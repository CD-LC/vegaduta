package com.agenticai.core.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agenticai.core.workflow.dto.WorkflowEdgeDto;
import com.agenticai.core.workflow.dto.WorkflowGraphDto;
import com.agenticai.core.workflow.dto.WorkflowNodeDto;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** {@link WorkflowDataNodeValidator}: save-time checks for the eight data nodes and Switch ports. */
class WorkflowDataNodeValidatorTest {

    private List<String> errors;

    @BeforeEach
    void setUp() {
        errors = new ArrayList<>();
    }

    private static WorkflowNodeDto node(String type, Map<String, Object> config) {
        return new WorkflowNodeDto("sw", type, Map.of(), Map.of("config", config, "label", "N"));
    }

    private void validate(String type, Map<String, Object> config) {
        WorkflowDataNodeValidator.validate(node(type, config), errors);
    }

    private boolean anyErrorContaining(String fragment) {
        return errors.stream().anyMatch(e -> e.contains(fragment));
    }

    @Test
    void handlesKnowsTheEightDataTypesCaseInsensitively() {
        for (String t : List.of("set", "filter", "sort", "aggregate", "limit", "dedupe", "switch", "merge")) {
            assertTrue(WorkflowDataNodeValidator.handles(t), t);
            assertTrue(WorkflowDataNodeValidator.handles(t.toUpperCase()), t);
        }
        assertFalse(WorkflowDataNodeValidator.handles(null));
        assertFalse(WorkflowDataNodeValidator.handles("http"));
    }

    @Test
    void setNeedsNamedFieldsAndValidValues() {
        validate("set", Map.of());
        assertTrue(anyErrorContaining("no fields to set"));

        errors.clear();
        validate("set", Map.of("assignments", List.of(Map.of("value", "x"))));
        assertTrue(anyErrorContaining("no name"));

        errors.clear();
        validate(
                "set",
                Map.of("assignments", List.of(Map.of("name", "n", "type", "number", "value", "abc"))));
        assertTrue(anyErrorContaining("not a number"));

        errors.clear();
        validate(
                "set",
                Map.of("assignments", List.of(Map.of("name", "n", "type", "wat", "value", "{{ x }}"))));
        assertTrue(anyErrorContaining("unknown type"), "expression values still get their type checked");

        errors.clear();
        validate(
                "set",
                Map.of("assignments", List.of(Map.of("name", "n", "value", "{{ x }}"))));
        assertTrue(errors.isEmpty(), "an expression value needs no literal check");

        errors.clear();
        validate(
                "set",
                Map.of("assignments", List.of(Map.of("name", "n", "type", "number", "value", "3"))));
        assertTrue(errors.isEmpty());
    }

    @Test
    void filterNeedsConditionsAndKnownOperators() {
        validate("filter", Map.of());
        assertTrue(anyErrorContaining("no condition"));

        errors.clear();
        validate("filter", Map.of("conditions", List.of(Map.of("field", "a", "operator", "zap"))));
        assertTrue(anyErrorContaining("unknown operator"));

        errors.clear();
        validate(
                "filter",
                Map.of(
                        "conditions", List.of(Map.of("field", "a", "operator", "regex", "value", "([")),
                        "combinator", "xor"));
        assertTrue(anyErrorContaining("invalid regex"));
        assertTrue(anyErrorContaining("invalid combinator"));

        errors.clear();
        validate(
                "filter",
                Map.of("conditions", List.of(Map.of("field", "a", "operator", "regex",
                    "value", "x".repeat(201)))));
        assertTrue(anyErrorContaining("longer than"));

        errors.clear();
        validate(
                "filter",
                Map.of("conditions", List.of(Map.of("field", "a", "operator", "regex", "value", "{{ p }}"))));
        assertTrue(errors.isEmpty(), "a regex containing an expression is a run-time concern");

        errors.clear();
        validate(
                "filter",
                Map.of("conditions", List.of(Map.of("field", "a", "operator", "contains", "value", "x"))));
        assertTrue(errors.isEmpty());
    }

    @Test
    void sortNeedsAKeyAndKnownChoices() {
        validate("sort", Map.of());
        assertTrue(anyErrorContaining("no sort key"));

        errors.clear();
        validate("sort", Map.of("field", "name", "direction", "up", "sortType", "colour"));
        assertTrue(anyErrorContaining("invalid direction"));
        assertTrue(anyErrorContaining("invalid sortType"));

        errors.clear();
        validate("sort", Map.of("sortKeys", List.of(Map.of("field", "a", "type", "colour"))));
        assertTrue(anyErrorContaining("invalid type"));

        errors.clear();
        validate("sort", Map.of("field", "name", "direction", "desc", "sortType", "number"));
        assertTrue(errors.isEmpty());
    }

    @Test
    void aggregateNeedsAtLeastOneKnownOperation() {
        validate("aggregate", Map.of());
        assertTrue(anyErrorContaining("nothing to calculate"));

        errors.clear();
        validate("aggregate", Map.of("aggregations", List.of(Map.of("operation", "median"))));
        assertTrue(anyErrorContaining("unknown operation"));

        errors.clear();
        validate("aggregate", Map.of("aggregations", List.of(Map.of("operation", "sum"))));
        assertTrue(errors.isEmpty());
    }

    @Test
    void limitNeedsANonNegativeWholeCount() {
        validate("limit", Map.of());
        assertTrue(anyErrorContaining("no count"));

        errors.clear();
        validate("limit", Map.of("count", -1));
        assertTrue(anyErrorContaining("whole number"));

        errors.clear();
        validate("limit", Map.of("count", "{{ n }}"));
        assertFalse(anyErrorContaining("whole number"), "an expression count is a run-time concern");

        errors.clear();
        validate("limit", Map.of("count", 3, "mode", "middle"));
        assertTrue(anyErrorContaining("invalid mode"));

        errors.clear();
        validate("limit", Map.of("count", 0));
        assertTrue(errors.isEmpty());
    }

    @Test
    void dedupeKeepIsAChoice() {
        validate("dedupe", Map.of("keep", "middle"));
        assertTrue(anyErrorContaining("invalid keep"));
        errors.clear();
        validate("dedupe", Map.of("keep", "last"));
        assertTrue(errors.isEmpty());
    }

    @Test
    void switchCasesNeedUniqueNonReservedNamesAndConditions() {
        validate("switch", Map.of());
        assertTrue(anyErrorContaining("no cases"));

        errors.clear();
        validate("switch", Map.of("cases", List.of(Map.of("conditions", List.of(Map.of("value", "x"))))));
        assertTrue(anyErrorContaining("no name"));

        errors.clear();
        validate(
                "switch",
                Map.of("cases", List.of(Map.of("name", "default",
                    "conditions", List.of(Map.of("value", "x"))))));
        assertTrue(anyErrorContaining("reserved"));

        errors.clear();
        validate(
                "switch",
                Map.of(
                        "cases",
                        List.of(
                                Map.of("name", "A", "conditions", List.of(Map.of("value", "x"))),
                                Map.of("name", "a", "conditions", List.of(Map.of("value", "y"))))));
        assertTrue(anyErrorContaining("must be unique"));

        errors.clear();
        validate("switch", Map.of("cases", List.of(Map.of("name", "A"))));
        assertTrue(anyErrorContaining("no condition"), "a case with no conditions can never match");

        errors.clear();
        validate(
                "switch",
                Map.of("cases", List.of(Map.of("name", "A", "combinator", "xor",
                    "conditions", List.of(Map.of("value", "x"))))));
        assertTrue(anyErrorContaining("invalid combinator"));

        errors.clear();
        validate(
                "switch",
                Map.of("cases", List.of(Map.of("name", "A",
                    "conditions", List.of(Map.of("operator", "eq", "value", "x"))))));
        assertTrue(errors.isEmpty());
    }

    @Test
    void mergeChecksModeKeyAndMinimum() {
        validate("merge", Map.of("mode", "tangle"));
        assertTrue(anyErrorContaining("unknown mode"));

        errors.clear();
        validate("merge", Map.of("mode", "join"));
        assertTrue(anyErrorContaining("no key field"));

        errors.clear();
        validate("merge", Map.of("mode", "join", "key", "id", "joinType", "sideways"));
        assertTrue(anyErrorContaining("invalid joinType"));

        errors.clear();
        validate("merge", Map.of("minInputs", "-2"));
        assertTrue(anyErrorContaining("minimum inputs"));

        errors.clear();
        validate("merge", Map.of("mode", "append"));
        assertTrue(errors.isEmpty());
    }

    @Test
    void graphCheckFlagsEdgesOffUnknownSwitchPorts() {
        WorkflowNodeDto sw =
                node("switch",
                        Map.of("cases", List.of(Map.of("name", "vip",
                            "conditions", List.of(Map.of("value", "x"))))));
        WorkflowNodeDto sink = new WorkflowNodeDto("sink", "set", Map.of(), Map.of("label", "S"));

        WorkflowGraphDto ok =
                new WorkflowGraphDto(
                        List.of(sw, sink),
                        List.of(
                                new WorkflowEdgeDto("e1", "sw", "sink", "vip", null),
                                new WorkflowEdgeDto("e2", "sw", "sink", "default", null),
                                new WorkflowEdgeDto("e3", "sw", "sink", "error", null)));
        WorkflowDataNodeValidator.validateGraph(ok, errors);
        assertTrue(errors.isEmpty());

        WorkflowGraphDto bad =
                new WorkflowGraphDto(
                        List.of(sw, sink),
                        List.of(
                                new WorkflowEdgeDto("e4", "sw", "sink", null, null),
                                new WorkflowEdgeDto("e5", "sw", "sink", "renamed", null),
                                new WorkflowEdgeDto("e6", "sink", "sw", null, null)));
        WorkflowDataNodeValidator.validateGraph(bad, errors);
        assertEquals(2, errors.size(), "the edge into the switch is not its port");
        assertTrue(anyErrorContaining("not attached to a case"));
        assertTrue(anyErrorContaining("not one of its cases"));
    }
}
