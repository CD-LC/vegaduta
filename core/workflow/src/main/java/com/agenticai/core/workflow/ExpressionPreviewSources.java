package com.agenticai.core.workflow;

import com.agenticai.core.workflow.dto.WorkflowExpressionApi.Supplied;
import com.agenticai.core.workflow.dto.WorkflowGraphDto;
import com.agenticai.core.workflow.dto.WorkflowNodeDto;
import com.agenticai.core.workflow.expression.EvalContext;
import com.agenticai.core.workflow.expression.NodeOutput;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Where the expression preview of {@link WorkflowExpressionService#evaluate} reads node data from - pins, the last
 * run, or data the caller supplied - and the evaluation context that reads through one of them while recording
 * which nodes were actually read.
 */
final class ExpressionPreviewSources {

    private final WorkflowPinStore pinStore;
    private final RunDataStore runData;
    private final WorkflowRunRepository runs;

    ExpressionPreviewSources(WorkflowPinStore pinStore, RunDataStore runData, WorkflowRunRepository runs) {
        this.pinStore = pinStore;
        this.runData = runData;
        this.runs = runs;
    }

    /** Where the preview reads node data from. */
    abstract static class Source {
        abstract String label();

        abstract NodeOutput byId(String nodeId);

        /** Label lookup; the default finds the first node with that label that has data. */
        NodeOutput byLabel(String label, WorkflowGraphDto graph) {
            for (WorkflowNodeDto n : graph.nodes()) {
                if (label.equals(n.label())) {
                    NodeOutput out = byId(n.id());
                    if (out != null) {
                        return out;
                    }
                }
            }
            return null;
        }

        NodeOutput trigger(WorkflowGraphDto graph) {
            for (WorkflowNodeDto n : graph.nodes()) {
                if ("trigger".equalsIgnoreCase(n.type())) {
                    NodeOutput out = byId(n.id());
                    if (out != null) {
                        return out;
                    }
                }
            }
            return null;
        }

        String runId() {
            return null;
        }

        String runStartedAt() {
            return null;
        }
    }

    Source pinned(UUID workflowId, WorkflowGraphDto graph) {
        Map<String, WorkflowNodePin> pins = new HashMap<>();
        for (WorkflowNodePin p : pinStore.list(workflowId)) {
            pins.put(p.getNodeId(), p);
        }
        Map<String, String> labels = labelsById(graph);
        return new Source() {
            @Override
            String label() {
                return "pinned";
            }

            @Override
            NodeOutput byId(String nodeId) {
                WorkflowNodePin pin = pins.get(nodeId);
                return pin == null ? null
                    : new NodeOutput(nodeId, labels.getOrDefault(nodeId, nodeId), pin.getDataText(), "PINNED", null);
            }
        };
    }

    Source lastRun(UUID workflowId, WorkflowGraphDto graph, Set<String> warnings) {
        WorkflowRun last = runs.findFirstByWorkflowIdOrderByStartedAtDesc(workflowId).orElse(null);
        if (last == null) {
            warnings.add("This workflow has not been run yet, so there is no last-run data.");
            return new Source() {
                @Override
                String label() {
                    return "last-run";
                }

                @Override
                NodeOutput byId(String nodeId) {
                    return null;
                }
            };
        }
        UUID runId = last.getId();
        return new Source() {
            @Override
            String label() {
                return "last-run";
            }

            @Override
            NodeOutput byId(String nodeId) {
                RunDataStore.Stored s = runData.latestById(runId, nodeId);
                if (s == null) {
                    return null;
                }
                if (s.truncated()) {
                    warnings.add("The last-run output of '" + s.label() + "' was over "
                        + RunDataStore.MAX_OUTPUT_CHARS / 1000 + " KB and is cut off; a real run would fail on it.");
                }
                return new NodeOutput(s.nodeId(), s.label(), s.outputText(), s.status(), null);
            }

            @Override
            NodeOutput trigger(WorkflowGraphDto g) {
                NodeOutput out = super.trigger(g);
                if (out != null) {
                    return out;
                }
                RunDataStore.Stored s = runData.latestById(runId, RunDataStore.TRIGGER_NODE_ID);
                return s == null ? null
                    : new NodeOutput(s.nodeId(), s.label(), s.outputText(), s.status(), null);
            }

            @Override
            String runId() {
                return runId.toString();
            }

            @Override
            String runStartedAt() {
                return String.valueOf(last.getStartedAt());
            }
        };
    }

    Source supplied(WorkflowGraphDto graph, Supplied supplied) {
        Map<String, String> nodes = supplied == null || supplied.nodes() == null ? Map.of() : supplied.nodes();
        Map<String, String> labels = labelsById(graph);
        return new Source() {
            @Override
            String label() {
                return "supplied";
            }

            @Override
            NodeOutput byId(String nodeId) {
                String text = nodes.get(labels.getOrDefault(nodeId, nodeId));
                if (text == null) {
                    text = nodes.get(nodeId);
                }
                return text == null ? null : new NodeOutput(nodeId, labels.getOrDefault(nodeId, nodeId), text, "OK", null);
            }

            @Override
            NodeOutput byLabel(String label, WorkflowGraphDto g) {
                String text = nodes.get(label);
                if (text != null) {
                    String id = labels.entrySet().stream().filter(e -> label.equals(e.getValue()))
                        .map(Map.Entry::getKey).findFirst().orElse(label);
                    return new NodeOutput(id, label, text, "OK", null);
                }
                return super.byLabel(label, g);
            }

            @Override
            NodeOutput trigger(WorkflowGraphDto g) {
                NodeOutput out = super.trigger(g);
                if (out != null) {
                    return out;
                }
                String text = nodes.get("$trigger");
                return text == null ? null : new NodeOutput(RunDataStore.TRIGGER_NODE_ID, "$trigger", text, "OK", null);
            }
        };
    }

    /** The evaluation context: reads through {@link Source} and records which nodes were actually read. */
    static final class SourceContext implements EvalContext {

        final Set<String> sources = new LinkedHashSet<>();
        private final String input;
        private final String inputSourceId;
        private final Source source;
        private final WorkflowGraphDto graph;
        private final Map<String, Object> run;
        private final Map<String, Object> workflow;
        private final Set<String> warnings;

        SourceContext(
            String input, String inputSourceId, Source source, WorkflowGraphDto graph, Map<String, Object> run,
            Map<String, Object> workflow, Set<String> warnings
        ) {
            this.input = input;
            this.inputSourceId = inputSourceId;
            this.source = source;
            this.graph = graph;
            this.run = run;
            this.workflow = workflow;
            this.warnings = warnings;
        }

        @Override
        public String input() {
            if (inputSourceId != null) {
                sources.add(inputSourceId);
            }
            return input;
        }

        @Override
        public NodeOutput nodeByLabel(String label) {
            NodeOutput out = label == null ? null : source.byLabel(label, graph);
            note(out, "'" + label + "'");
            return out;
        }

        @Override
        public NodeOutput nodeById(String id) {
            NodeOutput out = id == null ? null : source.byId(id);
            note(out, "id '" + id + "'");
            return out;
        }

        private void note(NodeOutput out, String what) {
            if (out != null) {
                sources.add(out.id());
            } else {
                warnings.add("No " + source.label() + " data for node " + what + ", so it reads as empty.");
            }
        }

        @Override
        public Object trigger() {
            NodeOutput out = source.trigger(graph);
            if (out == null) {
                warnings.add("No " + source.label() + " trigger data, so $trigger reads as empty.");
                return null;
            }
            sources.add(out.id());
            return out.value();
        }

        @Override
        public Map<String, Object> run() {
            return run;
        }

        @Override
        public Map<String, Object> workflow() {
            return workflow;
        }
    }

    private static Map<String, String> labelsById(WorkflowGraphDto graph) {
        Map<String, String> labels = new HashMap<>();
        for (WorkflowNodeDto n : graph.nodes()) {
            labels.put(n.id(), n.label());
        }
        return labels;
    }
}
