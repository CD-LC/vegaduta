package com.agenticai.core.workflow.diagram;

import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.agents.dto.AgentResponse;
import com.agenticai.core.workflow.WorkflowDefinition;
import com.agenticai.core.workflow.WorkflowService;
import com.agenticai.core.workflow.dto.DiagramFormat;
import com.agenticai.core.workflow.dto.WorkflowDiagramResponse;
import com.agenticai.core.workflow.dto.WorkflowEdgeDto;
import com.agenticai.core.workflow.dto.WorkflowGraphDto;
import com.agenticai.core.workflow.dto.WorkflowNodeDto;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * "Customer should be able to generate a diagram of the existing workflow
 * including agents, tools and all configs" - walks a saved {@link
 * WorkflowGraphDto} and renders it as either Mermaid flowchart source or
 * PlantUML state-diagram source. Both formats deliberately use a generic
 * "nodes + labeled transitions" shape (Mermaid {@code flowchart}, PlantUML
 * {@code state} diagram - NOT PlantUML's structured activity-diagram
 * if/else/loop constructs) because this graph can be an arbitrary DAG with
 * branches, fan-out, and even indirect cross-workflow references
 * ({@code WORKFLOW} nodes) - forcing it into structured control-flow syntax
 * would require re-deriving block structure this engine's own executor
 * (see {@link com.agenticai.core.workflow.WorkflowNodeExecutor}) doesn't
 * need either; a plain node/edge graph maps 1:1 onto both diagram
 * languages with no lossy translation.
 *
 * <p>Node labels resolve real agent names/models (via {@link AgentService})
 * and target-workflow names (via {@link WorkflowService}) wherever a node's
 * config references one, so the diagram genuinely shows "which agent, which
 * tool, which config" rather than opaque UUIDs - the whole point of this
 * feature over just re-exporting the raw graph JSON the canvas already
 * round-trips. Lookups fail open (falls back to the raw id) rather than
 * failing the whole export, since a stale/deleted reference in an old graph
 * shouldn't block a customer from exporting the rest of their diagram.
 */
@Service
public class WorkflowDiagramService {

    private static final Logger log = LoggerFactory.getLogger(WorkflowDiagramService.class);

    private final WorkflowService workflowService;
    private final AgentService agentService;

    public WorkflowDiagramService(WorkflowService workflowService, AgentService agentService) {
        this.workflowService = workflowService;
        this.agentService = agentService;
    }

    public WorkflowDiagramResponse generate(UUID workflowId, DiagramFormat format) {
        ResolvedWorkflowGraph resolved = resolve(workflowId);
        String source = format == DiagramFormat.PLANTUML
            ? renderPlantUml(resolved.workflowName(), resolved.nodes(), resolved.edges())
            : renderMermaid(resolved.workflowName(), resolved.nodes(), resolved.edges());
        return new WorkflowDiagramResponse(workflowId.toString(), resolved.workflowName(), format, source);
    }

    /** Shared with {@link WorkflowDiagramPdfRenderer} so agent/tool/workflow name resolution happens exactly once per export regardless of format. */
    public ResolvedWorkflowGraph resolve(UUID workflowId) {
        WorkflowDefinition definition = workflowService.get(workflowId);
        WorkflowGraphDto graph = workflowService.graph(workflowId);
        List<DiagramNode> nodes = graph.nodes().stream().map(this::describe).toList();
        return new ResolvedWorkflowGraph(definition.getName(), nodes, graph.edges());
    }

    // --- Mermaid -----------------------------------------------------------

    private String renderMermaid(String workflowName, List<DiagramNode> nodes, List<WorkflowEdgeDto> edges) {
        StringBuilder sb = new StringBuilder();
        sb.append("%% ").append(mermaidComment(workflowName)).append('\n');
        sb.append("flowchart TD\n");
        for (DiagramNode node : nodes) {
            String safeId = safeId(node.id());
            String label = mermaidEscape(node.label());
            String shapeOpen = node.branching() ? "{" : "[";
            String shapeClose = node.branching() ? "}" : "]";
            sb.append("    ").append(safeId).append(shapeOpen).append('"').append(label).append('"').append(shapeClose)
                .append(":::").append(node.styleClass()).append('\n');
        }
        for (WorkflowEdgeDto edge : edges) {
            sb.append("    ").append(safeId(edge.source())).append(" -->");
            if (edge.sourceHandle() != null && !edge.sourceHandle().isBlank()) {
                sb.append("|").append(mermaidEscape(edge.sourceHandle())).append("|");
            }
            sb.append(' ').append(safeId(edge.target())).append('\n');
        }
        sb.append("    classDef trigger fill:#38bdf8,stroke:#0369a1,color:#0c4a6e\n");
        sb.append("    classDef agent fill:#a78bfa,stroke:#6d28d9,color:#2e1065\n");
        sb.append("    classDef condition fill:#fbbf24,stroke:#b45309,color:#78350f\n");
        sb.append("    classDef output fill:#34d399,stroke:#047857,color:#064e3b\n");
        sb.append("    classDef tool fill:#f472b6,stroke:#be185d,color:#831843\n");
        sb.append("    classDef other fill:#cbd5e1,stroke:#475569,color:#1e293b\n");
        return sb.toString();
    }

    private String mermaidComment(String value) {
        return value == null ? "Workflow" : value.replace("\n", " ").replace("\r", " ");
    }

    private String mermaidEscape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\"", "'").replace("\n", " ").replace("\r", " ");
    }

    // --- PlantUML ------------------------------------------------------------

    private String renderPlantUml(String workflowName, List<DiagramNode> nodes, List<WorkflowEdgeDto> edges) {
        StringBuilder sb = new StringBuilder();
        sb.append("@startuml\n");
        sb.append("title ").append(plantUmlEscape(workflowName == null ? "Workflow" : workflowName)).append('\n');
        sb.append("hide empty description\n");
        for (DiagramNode node : nodes) {
            String safeId = safeId(node.id());
            sb.append("state \"").append(plantUmlEscape(node.label())).append("\" as ").append(safeId).append('\n');
        }
        for (WorkflowEdgeDto edge : edges) {
            sb.append(safeId(edge.source())).append(" --> ").append(safeId(edge.target()));
            if (edge.sourceHandle() != null && !edge.sourceHandle().isBlank()) {
                sb.append(" : ").append(plantUmlEscape(edge.sourceHandle()));
            }
            sb.append('\n');
        }
        sb.append("@enduml\n");
        return sb.toString();
    }

    private String plantUmlEscape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\"", "'").replace("\n", " ").replace("\r", " ");
    }

    // --- Node description ----------------------------------------------------

    private DiagramNode describe(WorkflowNodeDto node) {
        String type = node.type() == null ? "" : node.type().toLowerCase(Locale.ROOT);
        Map<String, Object> config = node.config();
        String label = switch (type) {
            case "trigger" -> "Trigger: " + node.label();
            case "agent" -> "Agent: " + agentSummary(config.get("agentId"));
            case "tool" -> "Tool: " + stringOrNull(config.get("toolName")) + " (via " + agentSummary(config.get("agentId")) + ")";
            case "supervisor" -> "Supervisor routing via " + agentSummary(config.get("agentId"));
            case "knowledge" -> "Knowledge search (topK=" + stringOrDefault(config.get("topK"), "5") + ")";
            case "condition" -> "Condition: contains '" + stringOrDefault(config.get("contains"), "") + "'";
            case "approval" -> "Approval required" + (stringOrNull(config.get("message")) != null
                ? ": " + config.get("message") : "");
            case "output" -> "Output -> " + stringOrDefault(config.get("channel"), "log");
            case "device_control" -> "Device command: " + stringOrDefault(config.get("commandType"), "?");
            case "code" -> "Transform: " + stringOrDefault(config.get("operation"), "?");
            case "http" -> "HTTP " + stringOrDefault(config.get("method"), "GET").toUpperCase(Locale.ROOT)
                + " " + stringOrDefault(config.get("url"), "?");
            case "loop" -> "Loop / Iterator";
            case "parallel" -> "Parallel fan-out";
            // styleClass deliberately falls through to "other" below, same as
            // loop/parallel - a fan-out is engine plumbing, not one of the
            // four semantically-colored roles (trigger/agent/condition/output).
            case "fan_out" -> "Dynamic fan-out (max "
                + stringOrDefault(config.get("maxItems"), "25") + " items, "
                + stringOrDefault(config.get("maxConcurrency"), "5") + " at a time)";
            case "workflow" -> "Trigger workflow: " + targetWorkflowName(config.get("targetWorkflowId"));
            default -> node.label();
        };
        boolean branching = "condition".equals(type) || "supervisor".equals(type) || "switch".equals(type)
            || "question_classifier".equals(type);
        String styleClass = switch (type) {
            case "trigger" -> "trigger";
            case "agent", "supervisor" -> "agent";
            case "condition" -> "condition";
            case "output" -> "output";
            case "tool" -> "tool";
            default -> "other";
        };
        return new DiagramNode(node.id(), stripNewlines(label), branching, styleClass);
    }

    private String stripNewlines(String value) {
        return value == null ? "" : value.replace("\n", " ").replace("\r", " ");
    }

    private String agentSummary(Object agentIdRaw) {
        if (agentIdRaw == null || agentIdRaw.toString().isBlank()) {
            return "(no agent selected)";
        }
        try {
            AgentResponse agent = agentService.get(UUID.fromString(agentIdRaw.toString()));
            return agent.name() + " (" + agent.providerName() + "/" + agent.model() + ")";
        } catch (RuntimeException e) {
            log.debug("Could not resolve agent {} for diagram export: {}", agentIdRaw, e.getMessage());
            return agentIdRaw.toString();
        }
    }

    private String targetWorkflowName(Object workflowIdRaw) {
        if (workflowIdRaw == null || workflowIdRaw.toString().isBlank()) {
            return "(no workflow selected)";
        }
        try {
            return workflowService.get(UUID.fromString(workflowIdRaw.toString())).getName();
        } catch (RuntimeException e) {
            return workflowIdRaw.toString();
        }
    }

    private String stringOrNull(Object value) {
        return value == null || value.toString().isBlank() ? null : value.toString();
    }

    private String stringOrDefault(Object value, String fallback) {
        return value == null || value.toString().isBlank() ? fallback : value.toString();
    }

    /** Both Mermaid and PlantUML node ids must be identifier-safe; a React Flow node id can contain any character. */
    private String safeId(String rawId) {
        if (rawId == null) {
            return "n";
        }
        String cleaned = rawId.replaceAll("[^A-Za-z0-9_]", "_");
        return cleaned.isEmpty() || Character.isDigit(cleaned.charAt(0)) ? "n_" + cleaned : cleaned;
    }
}
