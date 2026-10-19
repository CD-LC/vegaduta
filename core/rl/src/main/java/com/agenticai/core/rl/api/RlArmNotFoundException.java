package com.agenticai.core.rl.api;

/**
 * The addressed arm does not exist under the addressed agent+dimension policy.
 * Mapped to {@code 404 Not Found}.
 *
 * <p>This is also the cross-tenant answer. RL tables are schema-per-tenant (see
 * {@code PolicyArm}'s javadoc - no {@code tenant_id} column), so another
 * tenant's policy id / arm id simply does not resolve inside the caller's
 * schema; the caller learns nothing beyond "not here", which is the same thing
 * a genuinely absent arm reports.
 */
public class RlArmNotFoundException extends RuntimeException {

    public RlArmNotFoundException(String message) {
        super(message);
    }
}
