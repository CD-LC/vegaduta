package com.agenticai.core.workflow.version;

import com.agenticai.core.agents.tools.AgentStepEventRedactor;
import com.agenticai.core.workflow.NodeTracePayloads;
import com.agenticai.core.workflow.WorkflowRun;
import com.agenticai.core.workflow.dto.NodeTraceEntry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * The read-only diffs of {@link WorkflowVersionService}: the structural version diff (graph +
 * {@code details_json}) and the run-to-run diff aligned off each run's trace. Pure functions of the
 * documents it is given - no repository, cache or tenant access.
 */
final class WorkflowVersionDiffer {

    static final int MAX_DIFF_VALUE_CHARS = 2 * 1024;
    static final String REDACTED = "[redacted]";

    private final ObjectMapper objectMapper;

    WorkflowVersionDiffer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Structural diff between two version snapshots. Node identity is the graph's own {@code id};
     * "changed" compares type, label and config.
     *
     * <p>Phase 4 (canvas version-diff view) added, without removing anything: {@code meta} ({@code
     * fromVersion, toVersion, createdAt, author} of the TO version, plus {@code fromCreatedAt,
     * fromAuthor}) and, on every changed node, {@code fields: [{path, before, after}]} - dotted
     * paths ({@code type}, {@code label}, {@code config.maxIterations}, {@code
     * config.headers.Authorization}); arrays compare whole under their own path. Values whose key
     * looks credential-bearing are replaced by {@code [redacted]} on both sides (the change is
     * still reported), and every other value goes through the trace redactor and a 2 KB cap - a
     * VIEWER can read a diff. The older {@code changes} strings stay for existing callers.
     */
    Map<String, Object> diffVersions(WorkflowVersion from, WorkflowVersion to) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("from", versionSummary(from));
        result.put("to", versionSummary(to));
        result.put("nameChanged", !from.getWorkflowName().equals(to.getWorkflowName()));
        result.put(
                "descriptionChanged",
                !safe(from.getDescription()).equals(safe(to.getDescription())));
        result.put("statusChanged", from.getStatus() != to.getStatus());
        result.put("details", diffDetailsJson(from.getDetailsJson(), to.getDetailsJson()));
        result.put("graph", diffGraphJson(from.getGraphJson(), to.getGraphJson()));
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("fromVersion", from.getVersionNumber());
        meta.put("toVersion", to.getVersionNumber());
        meta.put("createdAt", to.getCreatedAt());
        meta.put("author", to.getCreatedBy());
        meta.put("fromCreatedAt", from.getCreatedAt());
        meta.put("fromAuthor", from.getCreatedBy());
        result.put("meta", meta);
        return result;
    }

    /**
     * Field-level diff of two {@code details_json} documents (schedule, limits, economy/tuning).
     * Renders as {@code [{field, from, to}]}, the same shape {@code AgentVersionService#diff}
     * produces for bundle fields - one display contract for both entity kinds. Missing/null
     * documents (V273-era rows) diff as empty rather than erroring.
     */
    private List<Map<String, Object>> diffDetailsJson(String detailsA, String detailsB) {
        List<Map<String, Object>> fields = new ArrayList<>();
        JsonNode a = readObject(detailsA);
        JsonNode b = readObject(detailsB);
        Set<String> names = new LinkedHashSet<>();
        a.fieldNames().forEachRemaining(names::add);
        b.fieldNames().forEachRemaining(names::add);
        for (String name : names) {
            JsonNode va = a.get(name);
            JsonNode vb = b.get(name);
            if (!Objects.equals(va, vb)) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("field", name);
                row.put(
                        "from",
                        va == null || va.isNull()
                                ? null
                                : va.isValueNode() ? va.asText() : va.toString());
                row.put(
                        "to",
                        vb == null || vb.isNull()
                                ? null
                                : vb.isValueNode() ? vb.asText() : vb.toString());
                fields.add(row);
            }
        }
        return fields;
    }

    private JsonNode readObject(String json) {
        if (json == null || json.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            JsonNode node = objectMapper.readTree(json);
            return node != null && node.isObject() ? node : objectMapper.createObjectNode();
        } catch (Exception e) {
            return objectMapper.createObjectNode();
        }
    }

    /** Pure structural diff of two graph_json documents - exposed for the run-diff path too. */
    Map<String, Object> diffGraphJson(String graphJsonA, String graphJsonB) {
        Map<String, JsonNode> nodesA = indexById(readArray(graphJsonA, "nodes"));
        Map<String, JsonNode> nodesB = indexById(readArray(graphJsonB, "nodes"));
        Map<String, JsonNode> edgesA = indexById(readArray(graphJsonA, "edges"));
        Map<String, JsonNode> edgesB = indexById(readArray(graphJsonB, "edges"));

        List<Map<String, Object>> addedNodes = new ArrayList<>();
        List<Map<String, Object>> removedNodes = new ArrayList<>();
        List<Map<String, Object>> changedNodes = new ArrayList<>();
        for (Map.Entry<String, JsonNode> entry : nodesB.entrySet()) {
            if (!nodesA.containsKey(entry.getKey())) {
                addedNodes.add(nodeBrief(entry.getValue()));
            }
        }
        for (Map.Entry<String, JsonNode> entry : nodesA.entrySet()) {
            JsonNode b = nodesB.get(entry.getKey());
            if (b == null) {
                removedNodes.add(nodeBrief(entry.getValue()));
            } else {
                List<String> changes = nodeChanges(entry.getValue(), b);
                if (!changes.isEmpty()) {
                    Map<String, Object> row = nodeBrief(b);
                    row.put("changes", changes);
                    row.put("fields", nodeFieldChanges(entry.getValue(), b));
                    changedNodes.add(row);
                }
            }
        }

        List<Map<String, Object>> addedEdges = new ArrayList<>();
        List<Map<String, Object>> removedEdges = new ArrayList<>();
        for (Map.Entry<String, JsonNode> entry : edgesB.entrySet()) {
            if (!edgesA.containsKey(entry.getKey())) {
                addedEdges.add(edgeBrief(entry.getValue()));
            }
        }
        for (Map.Entry<String, JsonNode> entry : edgesA.entrySet()) {
            JsonNode b = edgesB.get(entry.getKey());
            if (b == null) {
                removedEdges.add(edgeBrief(entry.getValue()));
            } else if (!edgeBrief(entry.getValue()).equals(edgeBrief(b))) {
                // Same id, rewired endpoints - report as remove+add, the
                // only honest reading of an edge whose target moved.
                removedEdges.add(edgeBrief(entry.getValue()));
                addedEdges.add(edgeBrief(b));
            }
        }

        Map<String, Object> graph = new LinkedHashMap<>();
        graph.put("addedNodes", addedNodes);
        graph.put("removedNodes", removedNodes);
        graph.put("changedNodes", changedNodes);
        graph.put("addedEdges", addedEdges);
        graph.put("removedEdges", removedEdges);
        return graph;
    }

    /**
     * Run-to-run diff - see this class's javadoc. Both runs must belong to the same workflow (the
     * caller enforces); {@code versionA/B} are the snapshots each run's stamped {@code
     * workflow_version} resolves to, either nullable (run predates stamping, or the snapshot is
     * gone).
     */
    Map<String, Object> diffRuns(
            WorkflowRun a, WorkflowRun b, WorkflowVersion versionA, WorkflowVersion versionB) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("a", runSummary(a));
        result.put("b", runSummary(b));

        // Graph half: only when the two runs actually executed different
        // snapshots - comparing identical graphs to themselves would be a
        // wall of empty lists that reads as noise, so the field is present
        // but null when there's nothing to say.
        boolean sameVersion =
                a.getWorkflowVersion() != null
                        && a.getWorkflowVersion().equals(b.getWorkflowVersion());
        if (!sameVersion && versionA != null && versionB != null) {
            result.put(
                    "graphDiff", diffGraphJson(versionA.getGraphJson(), versionB.getGraphJson()));
        } else {
            result.put("graphDiff", null);
        }
        result.put("sameWorkflowVersion", sameVersion);

        // Execution half: per-node rollup off each run's trace. nodeId is
        // the alignment key; a node can appear N times in one trace
        // (loop/fan-out/sub-flow iterations), which is exactly why the
        // rollup carries an execution COUNT, not just a last status.
        Map<String, Map<String, Object>> byNode = new LinkedHashMap<>();
        accumulateTrace(byNode, a.getTraceJson(), "a");
        accumulateTrace(byNode, b.getTraceJson(), "b");
        List<Map<String, Object>> nodes = new ArrayList<>();
        for (Map.Entry<String, Map<String, Object>> entry : byNode.entrySet()) {
            Map<String, Object> side = entry.getValue();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("nodeId", entry.getKey());
            row.put("label", side.get("label"));
            row.put("nodeType", side.get("nodeType"));
            row.put("a", side.get("a"));
            row.put("b", side.get("b"));
            nodes.add(row);
        }
        result.put("nodes", nodes);
        return result;
    }

    private void accumulateTrace(
            Map<String, Map<String, Object>> byNode, String traceJson, String sideKey) {
        if (traceJson == null || traceJson.isBlank()) {
            return;
        }
        List<NodeTraceEntry> entries;
        try {
            entries =
                    objectMapper.readValue(
                            traceJson,
                            objectMapper
                                    .getTypeFactory()
                                    .constructCollectionType(List.class, NodeTraceEntry.class));
        } catch (Exception e) {
            return;
        }
        for (NodeTraceEntry entry : entries) {
            Map<String, Object> side =
                    byNode.computeIfAbsent(
                            entry.nodeId(),
                            k -> {
                                Map<String, Object> fresh = new LinkedHashMap<>();
                                fresh.put("label", entry.label());
                                fresh.put("nodeType", entry.nodeType());
                                return fresh;
                            });
            // Later trace rows carry the truer label/type (a node can be
            // re-labelled between loop iterations' "(loop 1/3)" suffixes
            // only in the label - take the LAST seen, same as "what did it
            // end as").
            side.put("label", entry.label());
            side.put("nodeType", entry.nodeType());
            @SuppressWarnings("unchecked")
            Map<String, Object> rollup =
                    (Map<String, Object>)
                            side.computeIfAbsent(
                                    sideKey,
                                    k -> {
                                        Map<String, Object> fresh = new LinkedHashMap<>();
                                        fresh.put("executions", 0);
                                        fresh.put("totalMs", 0L);
                                        return fresh;
                                    });
            rollup.put("executions", ((Number) rollup.get("executions")).intValue() + 1);
            rollup.put("lastStatus", entry.status());
            rollup.put("lastSummary", entry.summary());
            if (entry.startedAt() != null && entry.completedAt() != null) {
                long ms = Duration.between(entry.startedAt(), entry.completedAt()).toMillis();
                rollup.put("totalMs", ((Number) rollup.get("totalMs")).longValue() + ms);
            }
        }
    }

    private Map<String, Object> runSummary(WorkflowRun run) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("runId", run.getId().toString());
        summary.put("status", run.getStatus().name());
        summary.put("workflowVersion", run.getWorkflowVersion());
        summary.put("startedAt", run.getStartedAt() == null ? null : run.getStartedAt().toString());
        summary.put(
                "completedAt",
                run.getCompletedAt() == null ? null : run.getCompletedAt().toString());
        summary.put("requestedBy", run.getRequestedBy());
        summary.put("errorMessage", run.getErrorMessage());
        return summary;
    }

    private Map<String, Object> versionSummary(WorkflowVersion version) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("versionNumber", version.getVersionNumber());
        summary.put("workflowName", version.getWorkflowName());
        summary.put("status", version.getStatus().name());
        summary.put("changeSummary", version.getChangeSummary());
        summary.put("createdBy", version.getCreatedBy());
        summary.put("createdAt", version.getCreatedAt().toString());
        return summary;
    }

    private List<JsonNode> readArray(String graphJson, String field) {
        List<JsonNode> out = new ArrayList<>();
        try {
            JsonNode array = objectMapper.readTree(graphJson).get(field);
            if (array != null && array.isArray()) {
                array.forEach(out::add);
            }
        } catch (Exception ignored) {
            // A corrupt graph snapshot diffs as empty - the version row is
            // still listable/restorable, the diff just can't parse it.
        }
        return out;
    }

    private Map<String, JsonNode> indexById(List<JsonNode> items) {
        Map<String, JsonNode> index = new LinkedHashMap<>();
        for (JsonNode item : items) {
            JsonNode id = item.get("id");
            if (id != null) {
                index.put(id.asText(), item);
            }
        }
        return index;
    }

    private Map<String, Object> nodeBrief(JsonNode node) {
        Map<String, Object> brief = new LinkedHashMap<>();
        brief.put("id", node.path("id").asText(null));
        brief.put("type", node.path("type").asText(null));
        // Graph JSON keeps label+config under data{} (React Flow shape) -
        // the same place NodeConfigDrawer writes them.
        JsonNode data = node.path("data");
        brief.put("label", data.path("label").asText(node.path("id").asText(null)));
        return brief;
    }

    private List<String> nodeChanges(JsonNode a, JsonNode b) {
        List<String> changes = new ArrayList<>();
        String typeA = a.path("type").asText(null);
        String typeB = b.path("type").asText(null);
        if (!safe(typeA).equals(safe(typeB))) {
            changes.add("type: " + typeA + " -> " + typeB);
        }
        JsonNode dataA = a.path("data");
        JsonNode dataB = b.path("data");
        String labelA = dataA.path("label").asText(null);
        String labelB = dataB.path("label").asText(null);
        if (!safe(labelA).equals(safe(labelB))) {
            changes.add("label: \"" + labelA + "\" -> \"" + labelB + "\"");
        }
        // Whole-config canonical compare - see diffVersions' javadoc.
        JsonNode configA = dataA.path("config");
        JsonNode configB = dataB.path("config");
        if (!configA.equals(configB)) {
            changes.add("config changed");
        }
        return changes;
    }

    /**
     * Field-level node diff for the canvas: {@code [{path, before, after}]}, see {@link
     * #diffVersions}.
     */
    List<Map<String, Object>> nodeFieldChanges(JsonNode a, JsonNode b) {
        List<Map<String, Object>> out = new ArrayList<>();
        diffValue("type", a.path("type"), b.path("type"), false, out);
        diffValue("label", a.path("data").path("label"), b.path("data").path("label"), false, out);
        diffValue(
                "config", a.path("data").path("config"), b.path("data").path("config"), false, out);
        return out;
    }

    private void diffValue(
            String path, JsonNode a, JsonNode b, boolean secret, List<Map<String, Object>> out) {
        boolean aObj = a != null && a.isObject();
        boolean bObj = b != null && b.isObject();
        if (aObj && bObj) {
            Set<String> names = new TreeSet<>();
            a.fieldNames().forEachRemaining(names::add);
            b.fieldNames().forEachRemaining(names::add);
            for (String name : names) {
                boolean childSecret = secret || AgentStepEventRedactor.isSecretKey(name);
                diffValue(path + "." + name, a.path(name), b.path(name), childSecret, out);
            }
            return;
        }
        JsonNode va = a == null || a.isMissingNode() || a.isNull() ? null : a;
        JsonNode vb = b == null || b.isMissingNode() || b.isNull() ? null : b;
        if (Objects.equals(va, vb)) {
            return;
        }
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("path", path);
        row.put("before", renderDiffValue(va, secret));
        row.put("after", renderDiffValue(vb, secret));
        out.add(row);
    }

    private static String renderDiffValue(JsonNode value, boolean secret) {
        if (value == null) {
            return null;
        }
        if (secret) {
            return REDACTED;
        }
        String text = value.isValueNode() ? value.asText() : value.toString();
        String redacted = NodeTracePayloads.redact(text);
        return redacted.length() > MAX_DIFF_VALUE_CHARS
                ? redacted.substring(0, MAX_DIFF_VALUE_CHARS) + "..."
                : redacted;
    }

    private Map<String, Object> edgeBrief(JsonNode edge) {
        Map<String, Object> brief = new LinkedHashMap<>();
        brief.put("id", edge.path("id").asText(null));
        brief.put("source", edge.path("source").asText(null));
        brief.put("target", edge.path("target").asText(null));
        brief.put("sourceHandle", edge.path("sourceHandle").asText(null));
        return brief;
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
