package com.agenticai.core.workflow;

import com.agenticai.core.workflow.WorkflowExpressionResolver.NodeEvalInfo;
import com.agenticai.core.workflow.expression.EvalContext;
import com.agenticai.core.workflow.expression.ExpressionException;
import com.agenticai.core.workflow.expression.NodeOutput;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * What expressions can read for one node execution: the node's input, upstream outputs from the run
 * data store, {@code $trigger}, {@code $run}, {@code $workflow} and the loop scope. Built by {@link
 * WorkflowExpressionResolver}; single-threaded: built and used inside one executeNode call.
 */
final class WorkflowNodeEvalContext implements EvalContext {

    private static final Logger log = LoggerFactory.getLogger(WorkflowNodeEvalContext.class);

    private final WorkflowExpressionResolver resolver;
    private final String input;
    private final NodeEvalInfo info;
    private final ExpressionScope scope;
    final boolean withInputJson;
    private final UUID runId;
    private final Map<String, NodeOutput> byLabel = new HashMap<>();
    private JsonNode tree;
    private boolean treeLoaded;
    private Map<String, Object> runMap;

    /**
     * Set while resolving an INPUT_ONLY / CRM field: {{input}} is the raw input there (see
     * EvalContext).
     */
    boolean rawInputWins;

    WorkflowNodeEvalContext(
            WorkflowExpressionResolver resolver,
            String input,
            NodeEvalInfo info,
            ExpressionScope scope,
            boolean withInputJson) {
        this.resolver = resolver;
        this.input = input == null ? "" : input;
        this.info = info;
        this.scope = scope;
        this.withInputJson = withInputJson;
        this.runId = WorkflowRunOutputRecorder.parseUuid(info == null ? null : info.runId());
    }

    @Override
    public String input() {
        return input;
    }

    @Override
    public boolean rawInputWins() {
        return rawInputWins;
    }

    @Override
    public JsonNode inputTree() {
        if (!treeLoaded) {
            treeLoaded = true;
            tree = buildTree();
        }
        return tree;
    }

    /**
     * Old renderTemplate semantics: blank input is an empty object, non-JSON is null; HTTP adds
     * {@code input_json}.
     */
    private JsonNode buildTree() {
        JsonNode parsed;
        try {
            parsed = resolver.objectMapper().readTree(input.isBlank() ? "{}" : input);
        } catch (Exception e) {
            parsed = null;
        }
        if (!withInputJson) {
            return parsed;
        }
        ObjectNode out = resolver.objectMapper().createObjectNode();
        try {
            out.put("input_json", resolver.objectMapper().writeValueAsString(input));
        } catch (Exception e) {
            out.put("input_json", "\"\"");
        }
        if (parsed != null && parsed.isObject()) {
            out.setAll(
                    (ObjectNode)
                            parsed.deepCopy()); // the input's own fields win over input_json, as
            // before
        }
        return out;
    }

    @Override
    public NodeOutput nodeByLabel(String label) {
        if (scope != null && scope.supplied() != null) {
            return suppliedOutput("label:" + label, label);
        }
        if (resolver.store() == null || runId == null || label == null) {
            return null;
        }
        if (byLabel.containsKey(label)) {
            return byLabel.get(label);
        }
        RunDataStore.Stored stored = readStore(() -> resolver.store().latestByLabel(runId, label));
        failIfRecordingLost(stored, label);
        NodeOutput out = toOutput(stored, label);
        byLabel.put(label, out);
        return out;
    }

    @Override
    public NodeOutput nodeById(String id) {
        if (scope != null && scope.supplied() != null) {
            return suppliedOutput("id:" + id, id);
        }
        if (resolver.store() == null || runId == null || id == null) {
            return null;
        }
        RunDataStore.Stored stored = readStore(() -> resolver.store().latestById(runId, id));
        failIfRecordingLost(stored, id);
        return toOutput(stored, id);
    }

    /**
     * Step test: outputs come only from what the caller supplied. A reference with nothing behind
     * it fails loudly - an empty string here would show a green test for a request that a real run
     * would not make.
     */
    private NodeOutput suppliedOutput(String key, String name) {
        NodeOutput out = scope.supplied().get(key);
        if (out == null) {
            throw new ExpressionException(
                    "No data for node '"
                            + name
                            + "' in this test - pin or supply its "
                            + "output (run the workflow once and pin the node), then test again",
                    -1,
                    -1);
        }
        return out;
    }

    /**
     * A missing row only means "that node did not run" when writing rows did not fail earlier in
     * this run.
     */
    private void failIfRecordingLost(RunDataStore.Stored stored, String what) {
        if (stored == null && runId != null && resolver.recordingFailed(runId)) {
            throw new ExpressionException(
                    "The output of '"
                            + what
                            + "' is unavailable: saving run data failed "
                            + "earlier in this run, so this value cannot be read",
                    -1,
                    -1);
        }
    }

    @Override
    public Object trigger() {
        if (scope != null && scope.supplied() != null) {
            return resolver.parseLoose(
                    suppliedOutput("id:" + RunDataStore.TRIGGER_NODE_ID, "$trigger").output());
        }
        if (resolver.store() == null || runId == null) {
            return null;
        }
        RunDataStore.Stored stored =
                readStore(() -> resolver.store().latestById(runId, RunDataStore.TRIGGER_NODE_ID));
        failIfRecordingLost(stored, "$trigger");
        if (stored == null) {
            return null;
        }
        failIfTruncated(stored, "the trigger payload");
        return resolver.parseLoose(stored.outputText());
    }

    @Override
    public Map<String, Object> run() {
        if (runMap == null) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", info == null ? null : info.runId());
            m.put("startedAt", startedAt());
            m.put("mode", scope == null ? "production" : scope.modeOrDefault());
            m.put("requestedBy", info == null ? null : info.requestedBy());
            String waitUrl = resolver.runWaitUrl(runId);
            if (waitUrl != null) {
                m.put("waitUrl", waitUrl);
            }
            runMap = m;
        }
        return runMap;
    }

    private String startedAt() {
        if (resolver.runs() == null || runId == null) {
            return null;
        }
        try {
            return resolver.runs()
                    .findById(runId)
                    .map(r -> String.valueOf(r.getStartedAt()))
                    .orElse(null);
        } catch (RuntimeException e) {
            return null;
        }
    }

    @Override
    public Map<String, Object> workflow() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", info == null ? null : info.workflowId());
        m.put("name", info == null ? null : info.workflowName());
        m.put("version", null);
        return m;
    }

    @Override
    public Object item() {
        return scope == null ? null : resolver.parseLoose(scope.item());
    }

    @Override
    public Object index() {
        return scope == null ? null : scope.index();
    }

    @Override
    public List<Object> items() {
        if (scope == null || scope.items() == null) {
            return null;
        }
        List<Object> out = new ArrayList<>(scope.items().size());
        for (String s : scope.items()) {
            out.add(resolver.parseLoose(s));
        }
        return out;
    }

    private RunDataStore.Stored readStore(Supplier<RunDataStore.Stored> read) {
        try {
            return read.get();
        } catch (RuntimeException e) {
            log.warn("Run data store read failed for run {}: {}", runId, e.getMessage());
            throw new ExpressionException(
                    "Run data is unavailable right now (" + e.getClass().getSimpleName() + ")",
                    -1,
                    -1);
        }
    }

    private NodeOutput toOutput(RunDataStore.Stored stored, String key) {
        if (stored == null) {
            return null;
        }
        failIfTruncated(stored, "node '" + key + "'");
        return new NodeOutput(
                stored.nodeId(), stored.label(), stored.outputText(), stored.status(), null);
    }

    private void failIfTruncated(RunDataStore.Stored stored, String what) {
        if (stored.truncated()) {
            throw new ExpressionException(
                    "Upstream output of "
                            + what
                            + " is too large (over "
                            + RunDataStore.MAX_OUTPUT_CHARS / 1000
                            + " KB) - reduce it with a Transform node",
                    -1,
                    -1);
        }
    }
}
