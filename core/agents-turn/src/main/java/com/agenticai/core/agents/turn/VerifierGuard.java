package com.agenticai.core.agents.turn;

/**
 * Limits on the buffered depths' verifiers (the self-consistency check and Reflexion). A verifier that finds
 * a problem rewrites the answer without tools - re-running them could repeat a side effect - which leaves two
 * things nothing bounded: what it is shown (the concatenation of every tool result of the turn) and what may
 * replace the answer (any non-blank text, however much shorter). Both limits are {@code
 * app.chat.parity.verifier-*}.
 */
final class VerifierGuard {

    private VerifierGuard() {}

    /** The newest {@code verifier-max-evidence-chars} characters of {@code evidence}; all of it when 0. */
    static String boundEvidence(String evidence, ChatParityProperties parity) {
        int max = parity.getVerifierMaxEvidenceChars();
        if (evidence == null || max <= 0 || evidence.length() <= max) {
            return evidence;
        }
        return evidence.substring(evidence.length() - max);
    }

    /**
     * {@code revised} when it may replace {@code original}, else {@code original}. A rewrite of an answer
     * longer than {@code verifier-ratio-applies-above-chars} that is shorter than {@code
     * verifier-min-revision-ratio} of it is not accepted: a verifier that turns a full answer into a line
     * has not fixed it.
     */
    static String acceptRevision(String original, String revised, ChatParityProperties parity) {
        if (revised == null || original == null || revised.equals(original)) {
            return revised;
        }
        double ratio = parity.getVerifierMinRevisionRatio();
        if (ratio > 0
                && original.length() > parity.getVerifierRatioAppliesAboveChars()
                && revised.length() < original.length() * ratio) {
            return original;
        }
        return revised;
    }
}
