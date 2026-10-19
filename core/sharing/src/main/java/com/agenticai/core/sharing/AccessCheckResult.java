package com.agenticai.core.sharing;

/**
 * The reason an access check via {@link ResourceAccessService#requireAccess}
 * succeeded - returned (never thrown) on success, so the calling module
 * (core/agents, core/workflow) can make its own audit-logging decisions
 * without re-deriving "how did this caller qualify". In particular, the
 * distinction between {@link #OWNER}/{@link #GRANTED} (ordinary, expected
 * access) and {@link #ADMIN_OVERRIDE} (a tenant-admin reaching a resource
 * they don't own and have no grant on) is exactly the kind of thing worth
 * audit-logging specially - see this module's README for the recommended
 * pattern.
 */
public enum AccessCheckResult {
    /** The caller's subject id matched the resource's {@code createdBy}. */
    OWNER,
    /** The caller has an active {@link ResourceGrant} at or above the required {@link PermissionLevel}. */
    GRANTED,
    /**
     * The resource's {@code createdBy} was {@code null} - a legacy resource
     * that predates this feature, or one whose owning module hasn't started
     * populating {@code createdBy} yet - AND the required level was no higher
     * than {@link PermissionLevel#RUNNER}.
     *
     * <p><b>Narrowed 2026-07-29.</b> This used to be returned for ANY
     * required level, which made the whole permission model inert for every
     * pre-{@code V151} row (all 38 agents in the live database at the time).
     * It is now only reachable at {@link PermissionLevel#RUNNER} - a legacy
     * resource can still be executed by the anonymous/synthetic-subject
     * surfaces that have no user identity to check (demo sandbox, public
     * share links, omnichannel webhooks, agent-to-agent delegation), but
     * reading or editing its configuration falls through to the ordinary
     * owner/admin/grant checks. See {@link
     * ResourceAccessService#requireAccess}.
     */
    LEGACY_OPEN,
    /** The caller was neither the owner nor grantee, but holds {@code ROLE_tenant-admin} or {@code ROLE_platform-admin}. */
    ADMIN_OVERRIDE
}
