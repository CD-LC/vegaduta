package com.agenticai.core.workflow.portability;

import com.agenticai.core.agents.importer.AgentImportJson;
import com.agenticai.core.agents.importer.AgentImportText;
import com.agenticai.core.workflow.WorkflowNodeType;
import com.agenticai.core.workflow.dto.WorkflowEdgeDto;
import com.agenticai.core.workflow.dto.WorkflowNodeDto;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Reads a VegaDuta workflow DSL v1 file (see {@link WorkflowDslV1}) into a {@link
 * PortableWorkflow}. Pure.
 *
 * <p>The file is untrusted text that happens to be shaped like one of our own graphs. Nothing in it
 * is executed or fetched; ids and sizes are bounded; and the version is checked so a file from a
 * newer server is refused with a clear message instead of being half-understood. A checksum that
 * does not match is only a warning (the file was edited after export, which is legitimate).
 */
public final class WorkflowDslImporter {

    static final int MAX_NODES = 500;
    static final int MAX_EDGES = 2000;
    private static final int ID_MAX = 100;
    private static final int LABEL_MAX = 200;

    private WorkflowDslImporter() {}

    /**
     * @throws IllegalArgumentException when the document is not a usable DSL v1 workflow
     */
    public static PortableWorkflow map(ObjectMapper mapper, JsonNode root) {
        String format = AgentImportJson.text(root, "format");
        if (format != null && !WorkflowDslV1.FORMAT.equals(format)) {
            throw new IllegalArgumentException(
                    "The file says its format is not a VegaDuta workflow file.");
        }
        JsonNode versionNode = root.get("schemaVersion");
        if (versionNode == null || !versionNode.isIntegralNumber()) {
            throw new IllegalArgumentException(
                    "The workflow file has no numeric \"schemaVersion\".");
        }
        int version = versionNode.asInt();
        if (version > WorkflowDslV1.SCHEMA_VERSION) {
            throw new IllegalArgumentException(
                    "This workflow file uses schemaVersion "
                            + version
                            + ", which is newer than this server understands ("
                            + WorkflowDslV1.SCHEMA_VERSION
                            + "). Update the platform or export the workflow again from this one.");
        }
        if (version < 1) {
            throw new IllegalArgumentException("\"schemaVersion\" must be 1 or higher.");
        }
        JsonNode nodes = AgentImportJson.arr(root, "nodes");
        if (nodes == null) {
            throw new IllegalArgumentException("The workflow file has no \"nodes\" list.");
        }
        if (nodes.size() > MAX_NODES) {
            throw new IllegalArgumentException(
                    "The workflow has "
                            + nodes.size()
                            + " nodes; the import limit is "
                            + MAX_NODES
                            + ".");
        }
        JsonNode edges = AgentImportJson.arr(root, "edges");
        if (edges != null && edges.size() > MAX_EDGES) {
            throw new IllegalArgumentException(
                    "The workflow has "
                            + edges.size()
                            + " connections; the import limit is "
                            + MAX_EDGES
                            + ".");
        }

        PortableWorkflow wf = new PortableWorkflow();
        wf.name =
                AgentImportText.sanitizeName(
                        AgentImportJson.text(root, "name"), "Imported workflow");
        String description = AgentImportJson.text(root, "description");
        wf.description = description == null ? null : AgentImportText.truncate(description, 2000);

        Map<String, String> labels = placeholderLabels(root);
        Set<String> ids = new HashSet<>();
        int index = 0;
        for (JsonNode source : nodes) {
            index++;
            if (!source.isObject()) {
                wf.report.warn("Node #" + index + " is not an object and was skipped.");
                continue;
            }
            String id = AgentImportJson.text(source, "id");
            String type = AgentImportJson.text(source, "type");
            if (id == null || id.length() > ID_MAX) {
                throw new IllegalArgumentException(
                        "Node #"
                                + index
                                + " has no id (or one longer than "
                                + ID_MAX
                                + " characters).");
            }
            if (type == null) {
                throw new IllegalArgumentException(
                        "Node '" + AgentImportText.inline(id, 40) + "' has no type.");
            }
            if (!ids.add(id)) {
                throw new IllegalArgumentException(
                        "Node id '" + AgentImportText.inline(id, 40) + "' appears more than once.");
            }
            wf.nodes.add(toNode(mapper, source, id, type));
            if (!isKnownType(type)) {
                wf.report.warn(
                        "Node '"
                                + AgentImportText.inline(id, 40)
                                + "' has the type '"
                                + AgentImportText.inline(type, 40)
                                + "', which this server does not know; it was kept as is.");
            }
        }
        wf.fidelity.sourceNodes = wf.nodes.size();
        wf.fidelity.exact = wf.nodes.size();

        int edgeIndex = 0;
        if (edges != null) {
            for (JsonNode source : edges) {
                edgeIndex++;
                wf.fidelity.sourceConnections++;
                String from = AgentImportJson.text(source, "source");
                String to = AgentImportJson.text(source, "target");
                if (from == null || to == null) {
                    wf.fidelity.connectionsDropped++;
                    wf.report.warn(
                            "Connection #"
                                    + edgeIndex
                                    + " has no source or target and was skipped.");
                    continue;
                }
                String edgeId = AgentImportJson.text(source, "id");
                wf.edges.add(
                        new WorkflowEdgeDto(
                                edgeId == null
                                        ? "e" + edgeIndex
                                        : AgentImportText.truncate(edgeId, ID_MAX),
                                from,
                                to,
                                AgentImportJson.text(source, "sourceHandle"),
                                AgentImportJson.text(source, "targetHandle")));
                wf.fidelity.connectionsKept++;
            }
        }

        readSettings(root, wf);
        verifyChecksum(root, wf);

        for (WorkflowNodeDto node : wf.nodes) {
            PlaceholderScanner.scan(
                    node.config(), node.id(), node.label(), wf.placeholders, labels);
        }
        wf.report.converted(
                "Workflow file",
                wf.nodes.size()
                        + " node(s) and "
                        + wf.edges.size()
                        + " connection(s) read from a VegaDuta workflow file (schemaVersion "
                        + version
                        + ").");
        wf.report.converted(
                "Triggers and schedules",
                "A workflow file carries only the design. Webhook tokens, "
                        + "schedules and channel bindings are never part of it; set them up on the new workflow after activating it.");
        return wf;
    }

    private static boolean isKnownType(String type) {
        try {
            WorkflowNodeType.fromWire(type);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    @SuppressWarnings("unchecked")
    private static WorkflowNodeDto toNode(
            ObjectMapper mapper, JsonNode source, String id, String type) {
        JsonNode position = source.get("position");
        Map<String, Object> pos =
                position != null && position.isObject()
                        ? (Map<String, Object>) mapper.convertValue(position, Map.class)
                        : null;
        JsonNode dataNode = source.get("data");
        Map<String, Object> data =
                dataNode != null && dataNode.isObject()
                        ? new LinkedHashMap<>(
                                (Map<String, Object>) mapper.convertValue(dataNode, Map.class))
                        : new LinkedHashMap<>();
        Object label = data.get("label");
        if (!(label instanceof String text) || text.isBlank()) {
            data.put("label", id);
        } else if (text.length() > LABEL_MAX) {
            data.put("label", AgentImportText.truncate(text, LABEL_MAX));
        }
        if (!(data.get("config") instanceof Map)) {
            data.put("config", new LinkedHashMap<String, Object>());
        }
        return new WorkflowNodeDto(id, type, pos, data);
    }

    private static Map<String, String> placeholderLabels(JsonNode root) {
        Map<String, String> labels = new LinkedHashMap<>();
        JsonNode list = AgentImportJson.arr(root, "placeholders");
        if (list != null) {
            for (JsonNode item : list) {
                String key = AgentImportJson.text(item, "key");
                String label = AgentImportJson.text(item, "label");
                if (key != null && label != null) {
                    labels.put(key, AgentImportText.inline(label, 120));
                }
            }
        }
        return labels;
    }

    private static void readSettings(JsonNode root, PortableWorkflow wf) {
        JsonNode config = AgentImportJson.obj(root, "config");
        if (config == null) {
            return;
        }
        putInt(config, "maxStepsPerRun", 1, 200, wf);
        putInt(config, "stepTimeoutSeconds", 1, 3600, wf);
        putInt(config, "maxRetryAttempts", 0, 5, wf);
    }

    private static void putInt(
            JsonNode config, String field, int min, int max, PortableWorkflow wf) {
        JsonNode value = config.get(field);
        if (value == null || value.isNull()) {
            return;
        }
        if (value.isIntegralNumber() && value.asInt() >= min && value.asInt() <= max) {
            wf.settings.put(field, value.asInt());
        } else {
            wf.report.warn(
                    "The execution limit '"
                            + field
                            + "' in the file is not a whole number between "
                            + min
                            + " and "
                            + max
                            + " and was ignored.");
        }
    }

    private static void verifyChecksum(JsonNode root, PortableWorkflow wf) {
        String declared = AgentImportJson.text(root, "checksum");
        if (declared == null) {
            wf.report.warn(
                    "The file has no checksum, so it cannot be confirmed unchanged since it was exported.");
        } else if (!declared.equals(WorkflowDslV1.checksum(root))) {
            wf.report.warn(
                    "The file's checksum does not match its content: it was edited after it was exported. "
                            + "Review the graph before relying on it.");
        }
    }
}
