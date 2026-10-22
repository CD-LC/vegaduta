package com.agenticai.core.workflow.portability;

import com.agenticai.core.workflow.WorkflowPortabilityRedactor;

import tools.jackson.core.util.DefaultIndenter;
import tools.jackson.core.util.DefaultPrettyPrinter;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Builds the VegaDuta workflow DSL v1 document for one saved workflow. Pure: the caller hands over
 * the stored name, description, graph JSON and settings, and gets back the document tree - no
 * repository, no tenant context.
 *
 * <p>What stays out of the file, and how:
 *
 * <ul>
 *   <li><b>Credentials</b> typed straight into a node (an API key in an HTTP header, a bearer
 *       token, a key in a URL) are found by the same detector the AI editor uses ({@link
 *       WorkflowPortabilityRedactor}, i.e. {@code WorkflowEditRedaction}) and replaced by {@code
 *       [[CREDENTIAL_n]]}. Vault references ({@code {{secret.NAME}}}) are references, not values,
 *       and are kept - and listed as placeholders so the importer knows which vault entries the
 *       workflow expects.
 *   <li><b>Tenant-specific ids</b> (the agent a node runs, a knowledge collection, another
 *       workflow, a contact group, a device) and <b>delivery destinations</b> (an email address, a
 *       chat id, a Drive folder) become {@code @placeholder:<key>} tokens; the importer maps or
 *       drops them.
 *   <li><b>Not graph data at all</b> - webhook tokens, schedules, channel bindings, owner,
 *       timestamps, run history, version history, shares - are never read.
 * </ul>
 */
public final class WorkflowDslExporter {

    /** The workflow-level settings that travel with the design. */
    public record Settings(
            int maxStepsPerRun,
            int stepTimeoutSeconds,
            int maxRetryAttempts,
            String reasoningDepth,
            String tuningMode,
            boolean stepCachingEnabled) {}

    private WorkflowDslExporter() {}

    /**
     * @throws IllegalArgumentException when the stored graph is not valid JSON (it cannot be
     *     exported safely)
     */
    public static ObjectNode export(
            ObjectMapper mapper,
            String name,
            String description,
            String graphJson,
            Settings settings) {
        WorkflowPortabilityRedactor.Result redacted =
                WorkflowPortabilityRedactor.redactText(mapper, graphJson, "", true);
        JsonNode graph = redacted.json();

        PlaceholderRegistry registry = new PlaceholderRegistry();
        ArrayNode nodes = mapper.createArrayNode();
        JsonNode sourceNodes = graph == null ? null : graph.get("nodes");
        if (sourceNodes != null && sourceNodes.isArray()) {
            for (JsonNode source : sourceNodes) {
                if (!source.isObject()) {
                    continue;
                }
                ObjectNode node = mapper.createObjectNode();
                copyIfPresent(source, node, "id");
                copyIfPresent(source, node, "type");
                copyIfPresent(source, node, "position");
                copyIfPresent(source, node, "data");
                tokenise(mapper, node, registry);
                nodes.add(node);
            }
        }
        ArrayNode edges = mapper.createArrayNode();
        JsonNode sourceEdges = graph == null ? null : graph.get("edges");
        if (sourceEdges != null && sourceEdges.isArray()) {
            for (JsonNode source : sourceEdges) {
                if (!source.isObject()) {
                    continue;
                }
                ObjectNode edge = mapper.createObjectNode();
                copyIfPresent(source, edge, "id");
                copyIfPresent(source, edge, "source");
                copyIfPresent(source, edge, "target");
                copyIfPresent(source, edge, "sourceHandle");
                copyIfPresent(source, edge, "targetHandle");
                edges.add(edge);
            }
        }

        ObjectNode doc = mapper.createObjectNode();
        doc.put("format", WorkflowDslV1.FORMAT);
        doc.put("schemaVersion", WorkflowDslV1.SCHEMA_VERSION);
        doc.put("name", name == null ? "" : name);
        doc.put("description", description == null ? "" : description);
        ObjectNode config = doc.putObject("config");
        if (settings != null) {
            config.put("maxStepsPerRun", settings.maxStepsPerRun());
            config.put("stepTimeoutSeconds", settings.stepTimeoutSeconds());
            config.put("maxRetryAttempts", settings.maxRetryAttempts());
            if (settings.reasoningDepth() != null) {
                config.put("reasoningDepth", settings.reasoningDepth());
            }
            if (settings.tuningMode() != null) {
                config.put("tuningMode", settings.tuningMode());
            }
            config.put("stepCachingEnabled", settings.stepCachingEnabled());
        }
        doc.set("nodes", nodes);
        doc.set("edges", edges);
        doc.set("placeholders", placeholders(mapper, registry));
        doc.put("checksum", WorkflowDslV1.checksum(doc));
        return doc;
    }

    /**
     * Pretty JSON with "\n" line endings regardless of the platform, so the same workflow exports
     * the same bytes.
     */
    public static String toJson(ObjectMapper mapper, JsonNode doc) {
        try {
            DefaultIndenter indenter = new DefaultIndenter("  ", "\n");
            DefaultPrettyPrinter printer =
                    new DefaultPrettyPrinter()
                            .withObjectIndenter(indenter)
                            .withArrayIndenter(indenter);
            return mapper.writer().with(printer).writeValueAsString(doc) + "\n";
        } catch (Exception e) {
            throw new IllegalStateException("Could not serialise the workflow file.", e);
        }
    }

    /**
     * A file-name-safe version of the workflow name: {@code my-workflow.vegaduta-workflow.json}.
     */
    public static String fileName(String workflowName) {
        String base =
                workflowName == null
                        ? ""
                        : workflowName
                                .toLowerCase(Locale.ROOT)
                                .replaceAll("[^a-z0-9]+", "-")
                                .replaceAll("^-+|-+$", "");
        if (base.length() > 60) {
            base = base.substring(0, 60).replaceAll("-+$", "");
        }
        return (base.isEmpty() ? "workflow" : base) + ".vegaduta-workflow.json";
    }

    // ---- internals
    // ---------------------------------------------------------------------------------------

    private static void copyIfPresent(JsonNode from, ObjectNode to, String field) {
        JsonNode value = from.get(field);
        if (value != null && !value.isNull()) {
            to.set(field, value.deepCopy());
        }
    }

    /**
     * Swaps the tenant-specific ids of one node (and of any sub-flow body nodes inside it) for
     * placeholder tokens.
     */
    private static void tokenise(
            ObjectMapper mapper, ObjectNode node, PlaceholderRegistry registry) {
        String id = node.path("id").asString("");
        String type = node.path("type").asString("").toLowerCase(Locale.ROOT);
        JsonNode data = node.get("data");
        String label =
                data != null && data.path("label").isString() ? data.path("label").asString() : id;
        JsonNode configNode = data == null ? null : data.get("config");
        if (configNode != null && configNode.isObject()) {
            ObjectNode config = (ObjectNode) configNode;
            List<String[]> fields = WorkflowDslV1.ID_FIELDS.get(type);
            if (fields != null) {
                for (String[] field : fields) {
                    JsonNode value = config.get(field[0]);
                    if (value != null
                            && value.isString()
                            && !value.asString().isBlank()
                            && !value.asString().contains("{{")
                            && PlaceholderRegistry.keyOfToken(value.asString()) == null) {
                        String key =
                                registry.register(field[1], value.asString(), label, id, field[0]);
                        config.put(field[0], PlaceholderRegistry.token(key));
                    }
                }
            }
            if ("sub_flow".equals(type)) {
                JsonNode body = config.get("body");
                JsonNode inner = body == null ? null : body.get("nodes");
                if (inner != null && inner.isArray()) {
                    for (JsonNode child : inner) {
                        if (child.isObject()) {
                            tokenise(mapper, (ObjectNode) child, registry);
                        }
                    }
                }
            }
            // Credential markers and vault references anywhere in this node's config (including a
            // sub-flow body).
            PlaceholderScanner.scan(
                    mapper.convertValue(config, Object.class),
                    id,
                    label,
                    registry,
                    Map.of(),
                    false);
        }
    }

    private static ArrayNode placeholders(ObjectMapper mapper, PlaceholderRegistry registry) {
        ArrayNode out = mapper.createArrayNode();
        for (PlaceholderRegistry.Entry entry : registry.entries()) {
            ObjectNode item = out.addObject();
            item.put("key", entry.key());
            item.put("kind", entry.kind());
            item.put("label", entry.label());
            ArrayNode uses = item.putArray("usedBy");
            for (PlaceholderRegistry.Use use : entry.usedBy()) {
                ObjectNode u = uses.addObject();
                u.put("nodeId", use.nodeId());
                u.put("field", use.field());
            }
        }
        return out;
    }
}
