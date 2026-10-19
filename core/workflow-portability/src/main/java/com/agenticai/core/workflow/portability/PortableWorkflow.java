package com.agenticai.core.workflow.portability;

import com.agenticai.core.agents.importer.AgentImportReport;
import com.agenticai.core.workflow.dto.WorkflowEdgeDto;
import com.agenticai.core.workflow.dto.WorkflowNodeDto;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A workflow on its way in: what a mapper (VegaDuta DSL, n8n, Dify) produced from a foreign
 * document, before the placeholders are resolved and the graph is validated. Plain mutable data -
 * mappers fill it in, the service reads it. {@code nodes} and {@code edges} may still carry
 * {@code @placeholder:<key>} tokens in node config (see {@link PlaceholderRegistry}).
 */
public final class PortableWorkflow {

    /**
     * How faithfully the source converted, counted per source node. {@code exact} nodes have a
     * direct equivalent; {@code approximate} ones were mapped but need a person to check them;
     * {@code placeholders} have no equivalent and were kept as inert stand-ins; {@code skipped} are
     * annotations that are not part of the flow (sticky notes). Connections are counted separately
     * because a mapped node can still lose an edge.
     */
    public static final class Fidelity {
        public int sourceNodes;
        public int exact;
        public int approximate;
        public int placeholders;
        public int skipped;
        public int sourceConnections;
        public int connectionsKept;
        public int connectionsDropped;
    }

    public String name;
    public String description;
    public final List<WorkflowNodeDto> nodes = new ArrayList<>();
    public final List<WorkflowEdgeDto> edges = new ArrayList<>();
    public final PlaceholderRegistry placeholders = new PlaceholderRegistry();
    public final AgentImportReport report = new AgentImportReport();
    public final Fidelity fidelity = new Fidelity();

    /**
     * Workflow-level settings carried by a VegaDuta DSL file (execution limits); empty for foreign
     * formats.
     */
    public final Map<String, Object> settings = new LinkedHashMap<>();
}
