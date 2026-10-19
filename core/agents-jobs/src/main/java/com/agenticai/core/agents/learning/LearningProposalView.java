package com.agenticai.core.agents.learning;

import java.time.Instant;
import java.util.UUID;

/**
 * What the REST layer shows of a proposal. {@code payload} and {@code evidence} are JSON object text.
 * {@code applicable} is false when no applier is registered for the kind, so approving would answer 409.
 */
public record LearningProposalView(
        UUID id,
        UUID agentId,
        String kind,
        String title,
        String body,
        String payload,
        String evidence,
        String status,
        Instant createdAt,
        Instant decidedAt,
        boolean applicable) {

    static LearningProposalView of(LearningProposal p, boolean applicable) {
        return new LearningProposalView(
                p.getId(),
                p.getAgentId(),
                p.getKind().name(),
                p.getTitle(),
                p.getBody(),
                p.getPayload(),
                p.getEvidence(),
                p.getStatus().name(),
                p.getCreatedAt(),
                p.getDecidedAt(),
                applicable);
    }
}
