package com.agenticai.core.brief.dto;

import java.util.List;
import tools.jackson.databind.node.ArrayNode;

/** Trust ladder shapes (contract sections 1 and 2). */
public final class TrustDtos {

    private TrustDtos() {}

    /** One line of evidence, shown to the user verbatim. */
    public record EvidenceLine(String key, String label, boolean met, String detail) {}

    /**
     * {@code POST /api/briefs/{id}/trust/proposal}. {@code targetRung} is null when there is
     * nothing to propose (already at the top, the plan has no eval runs, the ledger is not
     * connected). {@code diff} is the JSON patch the promotion would make to the manifest; {@code
     * effects} says in words what changes in the running envoy (tiers, approval nodes, gate).
     */
    public record TrustProposal(
            boolean eligible,
            String currentRung,
            String targetRung,
            List<EvidenceLine> evidence,
            ArrayNode diff,
            List<String> effects) {}

    /** {@code POST /api/briefs/{id}/trust/{rung}} and an automatic demotion. */
    public record TrustChange(
            String kind,
            String fromRung,
            String toRung,
            int version,
            List<String> effects,
            List<EvidenceLine> evidence) {}
}
