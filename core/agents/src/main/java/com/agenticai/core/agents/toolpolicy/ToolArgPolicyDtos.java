package com.agenticai.core.agents.toolpolicy;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;

/** Wire shapes of {@code /api/agents/{agentId}/tool-policies}. */
public final class ToolArgPolicyDtos {

    private ToolArgPolicyDtos() {
    }

    /** The size caps a PUT is validated against, so the console can pre-check the same limits. */
    public record Limits(
        int maxPolicies, int maxRulesPerPolicy, int maxDocumentChars, int maxPatternLength, int maxEnumValues
    ) {
    }

    /**
     * @param enabled false when {@code app.tool-arg-policies.enabled} is off: the document is not read, nothing
     *     is enforced, and a PUT answers 409
     * @param policies the stored document ({@code {"policies":[...]}}); an empty list when none
     * @param updatedBy null when nothing was ever stored
     * @param updatedAt null when nothing was ever stored
     */
    public record PoliciesResponse(
        boolean enabled, JsonNode policies, Limits limits, String updatedBy, Instant updatedAt
    ) {
    }
}
