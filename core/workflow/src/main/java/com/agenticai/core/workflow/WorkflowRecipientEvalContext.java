package com.agenticai.core.workflow;

import com.agenticai.core.workflow.expression.EvalContext;
import com.agenticai.core.workflow.expression.NodeOutput;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;

/** The node context with the input's top-level fields replaced by one bulk_email recipient's fields. */
final class WorkflowRecipientEvalContext implements EvalContext {

    private final ObjectMapper objectMapper;
    private final WorkflowNodeEvalContext base;
    private final Map<String, String> vars;
    private final int index;
    private JsonNode tree;

    WorkflowRecipientEvalContext(ObjectMapper objectMapper, WorkflowNodeEvalContext base, Map<String, String> vars, int index) {
        this.objectMapper = objectMapper;
        this.base = base;
        this.vars = vars;
        this.index = index;
    }

    @Override
    public String input() {
        return base.input();
    }

    @Override
    public JsonNode inputTree() {
        if (tree == null) {
            ObjectNode o = objectMapper.createObjectNode();
            vars.forEach((k, v) -> o.put(k, v));
            tree = o;
        }
        return tree;
    }

    @Override
    public NodeOutput nodeByLabel(String label) {
        return base.nodeByLabel(label);
    }

    @Override
    public NodeOutput nodeById(String id) {
        return base.nodeById(id);
    }

    @Override
    public Object trigger() {
        return base.trigger();
    }

    @Override
    public Map<String, Object> run() {
        return base.run();
    }

    @Override
    public Map<String, Object> workflow() {
        return base.workflow();
    }

    @Override
    public Object item() {
        return vars;
    }

    @Override
    public Object index() {
        return index;
    }

    @Override
    public List<Object> items() {
        return null;
    }
}
