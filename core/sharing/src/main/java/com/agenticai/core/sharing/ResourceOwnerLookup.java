package com.agenticai.core.sharing;

import java.util.Optional;
import java.util.UUID;

/**
 * The inversion point that lets {@link ShareController} resolve "who owns
 * this resource" without {@code core/sharing} depending on {@code
 * core/agents}/{@code core/workflow}.
 *
 * <p><b>Why this exists:</b> {@link ResourceAccessService}'s methods all take
 * {@code createdBy} as a parameter rather than looking it up themselves -
 * by design, since this module has zero dependency on the entity types
 * ({@code Agent}, {@code WorkflowDefinition}) that would own that column.
 * That's a clean seam for a caller who ALREADY has the entity in hand (e.g.
 * a future {@code AgentController} endpoint that loaded the {@code Agent}
 * anyway). {@link ShareController} in THIS module has no such entity in
 * hand - it only ever sees a bare resource id off the URL - so it needs its
 * own way to ask "what is this resource's owner" before it can call {@link
 * ResourceAccessService}. This interface is that seam: {@code core/sharing}
 * defines the port, {@code core/agents}/{@code core/workflow} (the next
 * workstream, once {@code Agent}/{@code WorkflowDefinition} gain a {@code
 * createdBy} column) provide the adapter as a plain {@code @Component}
 * implementing {@link #supportedType()} and {@link #findCreatedBy}.
 *
 * <p><b>Current state (as of this module's initial build):</b> neither
 * {@code Agent} nor {@code WorkflowDefinition} has a {@code createdBy}
 * column yet, so no implementation of this interface is registered anywhere
 * in the app. {@link ShareController} handles that gracefully - Spring
 * autowires an empty {@code List<ResourceOwnerLookup>} when no beans exist,
 * and an unresolvable owner is treated the same as {@link
 * Optional#empty()}, which flows into {@link
 * ResourceAccessService#requireAccess} as {@code createdBy == null} -
 * {@link AccessCheckResult#LEGACY_OPEN} for reads, admin-only for
 * grant/revoke/listGrants (see that class's javadoc). Nothing here is
 * broken by the column's absence; it just means per-owner grant management
 * is admin-only until the next workstream backfills ownership.
 */
public interface ResourceOwnerLookup {

    /** Which {@link ResourceType} this implementation resolves owners for. */
    ResourceType supportedType();

    /**
     * @return the resource's {@code createdBy} subject id, or {@link
     *     Optional#empty()} if the resource doesn't exist, or if it exists
     *     but predates this feature (no owner recorded).
     */
    Optional<String> findCreatedBy(UUID resourceId);
}
