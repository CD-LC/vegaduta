package com.agenticai.core.workflow.portability;

import com.agenticai.core.agents.importer.AgentImportDtos.ConversionReport;
import com.agenticai.core.workflow.dto.WorkflowGraphDto;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.UUID;

/**
 * Wire shapes of the workflow import routes ({@link WorkflowImportController}); the web client
 * declares the same field names, so a rename here is a breaking change on both sides. The
 * conversion report is the one the agent importer already uses ({@link ConversionReport}: converted
 * / needsManual / unsupported / warnings), so both import pages render the same structure.
 *
 * <p>Records are {@code @JsonInclude(ALWAYS)}: the client renders "nothing mapped" from an explicit
 * empty value, so a field must never disappear if a global non-null inclusion is ever configured.
 */
public final class PortabilityDtos {

    private PortabilityDtos() {}

    /** Where a placeholder is used inside the workflow. */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record UseView(String nodeId, String field) {}

    /**
     * One thing the file refers to that does not exist here (an agent, a knowledge base, a
     * credential, ...). {@code mappable} placeholders can be pointed at an existing resource
     * through {@code mappings} on create; the rest are listed so the person knows what to set up.
     * {@code mappedTo} is the id the request mapped it to.
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record PlaceholderView(
            String key,
            String kind,
            String label,
            boolean mappable,
            boolean resolved,
            String mappedTo,
            List<UseView> usedBy) {}

    /**
     * How faithfully the source converted; {@code level} is {@code high}, {@code partial} or {@code
     * low}.
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record FidelityView(
            String level,
            int sourceNodes,
            int exact,
            int needsReview,
            int placeholders,
            int skipped,
            int sourceConnections,
            int connectionsKept,
            int connectionsDropped) {}

    /** The workflow as it would be created. */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record WorkflowView(String name, String description, WorkflowGraphDto graph) {}

    /** {@code POST /api/workflows/import/preview} 200 body. Creates nothing. */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record PreviewResponse(
            String source,
            String detectedSource,
            WorkflowView workflow,
            List<PlaceholderView> placeholders,
            List<String> validationIssues,
            FidelityView fidelity,
            ConversionReport report) {}

    /** {@code POST /api/workflows/import} 201 body. {@code status} is always {@code DRAFT}. */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record ImportResponse(
            UUID workflowId,
            String status,
            List<String> validationIssues,
            List<PlaceholderView> placeholders,
            FidelityView fidelity,
            ConversionReport report) {}
}
