package com.agenticai.core.agents.version;

import java.util.UUID;

/**
 * 409 - a {@code rollback(entityId)} that has nothing to roll back TO: the
 * entity's current production version is already its oldest known version
 * (or no labeled version exists at all). Same role {@code
 * AgentVersionNotFoundException} plays for a missing row, mapped to
 * CONFLICT by the controllers because "one version exists" is a state
 * problem, not a request-syntax problem - the C1 spec's own edge case.
 */
public class VersionRollbackException extends RuntimeException {

    public VersionRollbackException(String entityType, UUID entityId) {
        super("Cannot roll back " + entityType + " " + entityId
            + " - there is no earlier version to point production at.");
    }
}
