package com.agenticai.core.workflow.portability;

import tools.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * VegaDuta workflow DSL, version 1 - the documented, versioned JSON file a workflow is exported to
 * and imported from (see {@code docs/api/workflow-dsl-v1.md}).
 *
 * <pre>
 * {
 *   "format": "vegaduta.workflow",
 *   "schemaVersion": 1,
 *   "name": "...", "description": "...",
 *   "config": { "maxStepsPerRun": 50, "stepTimeoutSeconds": 120, "maxRetryAttempts": 0, ... },
 *   "nodes": [ { "id", "type", "position": {x, y}, "data": { "label", "config": {...} } } ],
 *   "edges": [ { "id", "source", "target", "sourceHandle", "targetHandle" } ],
 *   "placeholders": [ { "key", "kind", "label", "usedBy": [ { "nodeId", "field" } ] } ],
 *   "checksum": "sha256:..."
 * }
 * </pre>
 *
 * <p>Deterministic by design: no export timestamp, no tenant, user, workflow or trigger identity,
 * so exporting the same workflow twice produces byte-identical files and a file can be diffed or
 * kept in version control. The checksum is the SHA-256 of the document's canonical form (object
 * keys sorted, no whitespace) with the {@code checksum} field itself left out; a mismatch on import
 * only means the file was edited after export, so it is reported as a warning, never as an error.
 */
public final class WorkflowDslV1 {

    public static final String FORMAT = "vegaduta.workflow";
    public static final int SCHEMA_VERSION = 1;
    public static final String CHECKSUM_PREFIX = "sha256:";

    private WorkflowDslV1() {}

    /**
     * {@code sha256:<hex>} over the canonical form of {@code doc}, ignoring its own {@code
     * checksum} field.
     */
    public static String checksum(JsonNode doc) {
        StringBuilder sb = new StringBuilder();
        canonical(doc, sb, true);
        try {
            byte[] digest =
                    MessageDigest.getInstance("SHA-256")
                            .digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            return CHECKSUM_PREFIX + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    /** Canonical JSON text: object keys sorted, arrays in order, compact. Exposed for tests. */
    static String canonicalJson(JsonNode node) {
        StringBuilder sb = new StringBuilder();
        canonical(node, sb, false);
        return sb.toString();
    }

    private static void canonical(JsonNode node, StringBuilder sb, boolean skipChecksum) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            sb.append("null");
        } else if (node.isObject()) {
            List<String> names = new ArrayList<>();
            Iterator<String> it = node.propertyNames().iterator();
            while (it.hasNext()) {
                names.add(it.next());
            }
            Collections.sort(names);
            sb.append('{');
            boolean first = true;
            for (String name : names) {
                if (skipChecksum && "checksum".equals(name)) {
                    continue;
                }
                if (!first) {
                    sb.append(',');
                }
                first = false;
                sb.append(tools.jackson.databind.node.StringNode.valueOf(name).toString())
                        .append(':');
                canonical(node.get(name), sb, false);
            }
            sb.append('}');
        } else if (node.isArray()) {
            sb.append('[');
            for (int i = 0; i < node.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                canonical(node.get(i), sb, false);
            }
            sb.append(']');
        } else {
            sb.append(node.toString());
        }
    }

    /**
     * The id fields that point at a tenant's own resources, per node type: {@code {configKey,
     * placeholderKind}}.
     */
    static final Map<String, List<String[]>> ID_FIELDS =
            Map.of(
                    "agent", List.<String[]>of(new String[] {"agentId", PlaceholderRegistry.AGENT}),
                    "tool", List.<String[]>of(new String[] {"agentId", PlaceholderRegistry.AGENT}),
                    "supervisor",
                            List.<String[]>of(new String[] {"agentId", PlaceholderRegistry.AGENT}),
                    "knowledge",
                            List.<String[]>of(
                                    new String[] {
                                        "collectionId", PlaceholderRegistry.KNOWLEDGE_BASE
                                    }),
                    "workflow",
                            List.<String[]>of(
                                    new String[] {
                                        "targetWorkflowId", PlaceholderRegistry.WORKFLOW
                                    }),
                    "device_control",
                            List.<String[]>of(
                                    new String[] {"deviceId", PlaceholderRegistry.DEVICE}),
                    "bulk_email",
                            List.of(
                                    new String[] {"groupId", PlaceholderRegistry.CONTACT_GROUP},
                                    new String[] {
                                        "staticRecipients", PlaceholderRegistry.DESTINATION
                                    },
                                    new String[] {"replyTo", PlaceholderRegistry.DESTINATION}),
                    "output",
                            List.of(
                                    new String[] {"emailTo", PlaceholderRegistry.DESTINATION},
                                    new String[] {
                                        "telegramChatId", PlaceholderRegistry.DESTINATION
                                    },
                                    new String[] {
                                        "teamsConversationId", PlaceholderRegistry.DESTINATION
                                    },
                                    new String[] {"whatsappTo", PlaceholderRegistry.DESTINATION},
                                    new String[] {
                                        "driveFolderId", PlaceholderRegistry.DESTINATION
                                    }));
}
