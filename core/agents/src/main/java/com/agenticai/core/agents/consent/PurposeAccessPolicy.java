package com.agenticai.core.agents.consent;

import java.util.Set;
import java.util.UUID;

/**
 * Purpose limitation for knowledge stores an agent can read or write (Act s.5 / s.6(1): consent is
 * for a specified purpose). Implemented in {@code core/app}.
 *
 * <p>Rule: a collection with NO purpose tag is unrestricted (existing behaviour). A collection
 * with one or more tags is usable only by an agent that carries at least one of the same tags.
 */
public interface PurposeAccessPolicy {

    /** The subset of {@code collectionIds} the agent may use; never null. */
    Set<UUID> permittedCollections(UUID agentId, Set<UUID> collectionIds);
}
