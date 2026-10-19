package com.agenticai.core.rl.api;

/**
 * A proposed arm collides with one that already exists on the same policy.
 * Arm keys are the operator-facing identity of an arm (and what {@code
 * PolicyStore.findArm} resolves rewards by), so they must stay unique per
 * policy. Mapped to {@code 409 Conflict}.
 */
public class RlArmConflictException extends RuntimeException {

    public RlArmConflictException(String message) {
        super(message);
    }
}
