package com.agenticai.core.workflow.portability;

import com.agenticai.core.agents.importer.AgentImportJson;
import com.agenticai.core.workflow.dto.WorkflowNodeDto;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;

/**
 * The value types shared by {@link N8nImportMapper} and its collaborators (HTTP, IF and edge
 * mapping).
 */
final class N8nImportModel {

    private N8nImportModel() {}

    enum Level {
        EXACT,
        APPROXIMATE,
        PLACEHOLDER
    }

    /** How an edge's {@code sourceHandle} is derived from the n8n output index. */
    enum Outputs {
        PLAIN,
        CONDITION,
        INDEXED,
        LOOP
    }

    record Src(
            int index,
            String id,
            String name,
            String type,
            double version,
            JsonNode raw,
            JsonNode params) {}

    record Conn(String from, int outputIndex, String to) {}

    record AiLink(String from, String connectionType, String to) {}

    static final class Built {
        WorkflowNodeDto node;
        Level level;
        Outputs outputs = Outputs.PLAIN;
        String reason = "";
        final List<String> notes = new ArrayList<>();
    }

    /** Per-node scratch state: notes, and whether anything needs a person's review. */
    static final class NodeCtx {
        final Src src;
        final JsonNode params;
        final List<String> notes = new ArrayList<>();
        boolean approximate;
        boolean expression;

        NodeCtx(Src src, JsonNode params) {
            this.src = src;
            this.params = params;
        }

        /** An n8n string as template text; marks the node as carrying an expression. */
        String tx(String raw) {
            if (raw == null) {
                return null;
            }
            if (raw.startsWith("=")) {
                expression = true;
            }
            return PortableExpressions.n8nText(raw);
        }

        void lose(String note) {
            approximate = true;
            notes.add(note);
        }

        String text(String... fields) {
            return AgentImportJson.firstText(params, fields);
        }
    }

    static String firstNonBlank(String a, String b) {
        return a != null ? a : b;
    }
}
