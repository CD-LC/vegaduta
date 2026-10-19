package com.agenticai.core.guardrails.moderation;

/**
 * Thrown when an operator tries to enable a policy pack that is held pending
 * legal review (today: CSAM only - see {@link ModerationPolicyPack#CSAM}).
 *
 * <p>Mapped to HTTP 409 CONFLICT rather than 403: the caller has the right role,
 * and the request is well-formed. What is missing is a precondition on the
 * PLATFORM side - the reporting flow and restricted evidence trail that this
 * category legally requires. 409 says "not in this state"; 403 would have
 * implied the operator needed different permissions, sending them to fix the
 * wrong thing.
 */
// Moved verbatim from core/platform's moderation package (2026-08 unification):
// the enforcement point is ModerationPolicyService.setPackEnabled, so the
// exception lives beside it; core/platform's controller maps it to 409.
public class ModerationPolicyPackNotAvailableException extends RuntimeException {

    private final String packId;

    public ModerationPolicyPackNotAvailableException(String packId) {
        super("Policy pack '" + packId + "' cannot be enabled yet. It carries mandatory reporting "
            + "obligations and requires a restricted evidence trail; that flow must be designed with "
            + "counsel before this pack can be switched on. See the Safety & Moderation runbook.");
        this.packId = packId;
    }

    public String getPackId() {
        return packId;
    }
}
