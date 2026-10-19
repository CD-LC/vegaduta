package com.agenticai.core.agents.importer;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.UUID;

/**
 * Wire shapes of the agent-import routes ({@link AgentImportController}). The web
 * client declares the same field names, so a rename here is a breaking change on
 * both sides.
 *
 * <p>Records are {@code @JsonInclude(ALWAYS)}: the client renders "no model mapped" from an
 * explicit {@code null}/empty string, so a field must never disappear if a global non-null
 * inclusion is ever configured.
 */
public final class AgentImportDtos {

    private AgentImportDtos() {
    }

    /** Something the importer converted faithfully (or deliberately mapped to a platform feature). */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record ConvertedItem(String what, String detail) {
    }

    /** Something that exists in the source but needs the user to do a step on this platform. */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record NeedsManualItem(String what, String why, String hint) {
    }

    /** Something with no equivalent here; it is simply not carried over. */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record UnsupportedItem(String what, String why) {
    }

    /** The honest conversion report - everything that did and did not make it across. */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record ConversionReport(
        List<ConvertedItem> converted,
        List<NeedsManualItem> needsManual,
        List<UnsupportedItem> unsupported,
        List<String> warnings
    ) {
    }

    /**
     * The agent as it would be created. {@code modelProvider}/{@code model} are empty strings
     * when the source model could not be matched to the platform catalog (never a guess).
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record ImportedAgentView(
        String name,
        String description,
        String instructions,
        String modelProvider,
        String model,
        Double temperature,
        Double topP,
        List<String> suggestedCapabilities,
        List<String> suggestedTools
    ) {
    }

    /** {@code POST /api/agents/import/preview} 200 body. */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record PreviewResponse(
        String source,
        String detectedSource,
        ImportedAgentView agent,
        ConversionReport report
    ) {
    }

    /** {@code POST /api/agents/import} 201 body. */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record ImportResponse(UUID agentId, ConversionReport report) {
    }
}
