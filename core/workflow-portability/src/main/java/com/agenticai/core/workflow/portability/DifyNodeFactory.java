package com.agenticai.core.workflow.portability;

import com.agenticai.core.agents.importer.AgentImportJson;
import com.agenticai.core.agents.importer.AgentImportText;
import com.agenticai.core.workflow.portability.DifyImportMapper.DNode;
import com.agenticai.core.workflow.portability.DifyImportModel.Ctx;
import com.agenticai.core.workflow.portability.DifyImportModel.Exit;
import com.agenticai.core.workflow.portability.DifyImportModel.Level;
import com.agenticai.core.workflow.portability.DifyImportModel.Mapped;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Builds the mapped node shapes every Dify node mapper ends with: a single node, or an inert
 * placeholder.
 */
final class DifyNodeFactory {

    static final String REFS_NOTE =
            "Dify variable references were rewritten to $node[\"Title\"].field "
                    + "reads; a VegaDuta node's output shape differs, so check them.";

    private static final String PLATFORM = "dify";
    private static final int PARAMS_CAP_CHARS = 30_000;

    private final ObjectMapper mapper;

    DifyNodeFactory(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    Mapped placeholder(Ctx c, String reason) {
        return placeholder(c, reason, PortableNodes.passthroughConfig());
    }

    Mapped placeholder(Ctx c, String reason, Map<String, Object> config) {
        config.put("placeholder", Boolean.TRUE);
        config.put(
                "importedFrom",
                PortableNodes.importedFrom(
                        PLATFORM,
                        c.node.type(),
                        c.node.title(),
                        capParams(PortableNodes.plain(mapper, c.data))));
        c.notes.add(reason);
        Mapped m = single(c, "code", "[Unsupported] " + c.node.title(), config, Level.PLACEHOLDER);
        m.reason = reason;
        m.exits =
                handle ->
                        new Exit(
                                c.node.id(),
                                handle == null || "source".equals(handle)
                                        ? null
                                        : "output_" + PortableNodes.slug(handle, "x"));
        return m;
    }

    private Object capParams(Object plain) {
        return PortableNodes.capped(mapper, plain, PARAMS_CAP_CHARS);
    }

    /**
     * One mapped node with the default exit (every outgoing edge starts from it, handle unchanged
     * except "source").
     */
    Mapped single(Ctx c, String type, String label, Map<String, Object> config, Level level) {
        Level effective = level;
        if (level == Level.EXACT && (c.approximate || c.refs)) {
            effective = Level.APPROXIMATE;
        }
        List<String> notes = new ArrayList<>(c.notes);
        if (c.refs && effective != Level.PLACEHOLDER) {
            notes.add(REFS_NOTE);
        }
        Mapped m = new Mapped();
        m.level = effective;
        m.label = label;
        m.notes.addAll(notes);
        m.entryId = c.node.id();
        m.nodes.add(
                PortableNodes.node(
                        c.node.id(),
                        type,
                        AgentImportText.truncate(label, 200),
                        position(c.node),
                        config,
                        notes,
                        effective != Level.EXACT));
        m.exits = handle -> new Exit(c.node.id(), "fail-branch".equals(handle) ? "error" : null);
        return m;
    }

    Map<String, Object> position(DNode n) {
        JsonNode p = AgentImportJson.obj(n.raw(), "position");
        Double x = p == null ? null : AgentImportJson.number(p, "x");
        Double y = p == null ? null : AgentImportJson.number(p, "y");
        if (x != null && y != null) {
            return PortableNodes.position(x, y);
        }
        return PortableNodes.position((n.index() % 5) * 260.0, (n.index() / 5) * 160.0);
    }
}
