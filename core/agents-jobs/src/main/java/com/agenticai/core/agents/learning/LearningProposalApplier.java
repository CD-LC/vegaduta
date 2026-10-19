package com.agenticai.core.agents.learning;

/**
 * Turns an approved proposal into its effect (a stored fact, a profile bullet, a schedule...). One bean per
 * {@link LearningProposalKind}; the service looks the applier up by {@link #kind()}.
 *
 * <p>Runs on the request thread of the approving person, so the tenant context is already in place. It must
 * either complete the change or throw: a {@link LearningProposalApplyException} is reported to the person as 409
 * with its message (safe to show) and leaves the proposal PENDING so they can edit and retry.
 */
public interface LearningProposalApplier {

    LearningProposalKind kind();

    /**
     * @param proposal the stored proposal
     * @param text the text to apply: the proposal body, or the person's edit of it (already scrubbed)
     */
    void apply(LearningProposal proposal, String text) throws LearningProposalApplyException;
}
