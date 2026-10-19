package com.agenticai.core.agents.tools.classification;

import java.util.List;

/**
 * Body of {@code GET /api/agents/{agentId}/tool-access/trifecta}. The first four fields and
 * {@code lethalTrifecta} are the {@link LethalTrifectaDetector.Report} shape the console already reads;
 * {@code warning}, {@code enforcement}, {@code enforced} and {@code gated} were added with enforcement.
 *
 * @param enforcement the configured mode, {@code off|warn|require-approval|block}
 * @param enforced    the agent holds the trifecta AND the mode is require-approval or block
 * @param gated       actions that send data out always need a person for this agent right now
 */
public record TrifectaStatusResponse(
    List<String> privateDataTools,
    List<String> untrustedContentTools,
    List<String> egressTools,
    List<String> unclassifiedTools,
    boolean lethalTrifecta,
    String warning,
    String enforcement,
    boolean enforced,
    boolean gated
) {

    public static TrifectaStatusResponse of(
        LethalTrifectaDetector.Report report,
        TrifectaEnforcementMode mode,
        boolean enforced,
        boolean gated
    ) {
        return new TrifectaStatusResponse(
            report.privateDataTools(),
            report.untrustedContentTools(),
            report.egressTools(),
            report.unclassifiedTools(),
            report.isLethalTrifecta(),
            report.warning(),
            mode.wireName(),
            enforced,
            gated
        );
    }
}
