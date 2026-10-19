package com.agenticai.core.sharing;

import com.agenticai.core.admin.identity.IdentityUserAdminPort;
import com.agenticai.core.admin.keycloak.KeycloakUserRepresentation;
import com.agenticai.core.audit.AuditLogService;
import com.agenticai.core.audit.AuditOutcome;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.apikey.ApiKeyAuthenticationToken;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Foundational access-control service for cross-user Agent/Workflow sharing. See this module's
 * README.md for the full contract this class provides to its two downstream consumers (core/agents,
 * core/workflow retrofits; the web UI).
 *
 * <p><b>This service has no direct DB access to Agent/WorkflowDefinition</b> - every method that
 * needs to know "who owns this resource" takes {@code createdBy} as a parameter, supplied by the
 * caller (which, once the next workstream lands, will have already loaded the entity for its own
 * purposes anyway). {@code createdBy == null} is treated as "legacy resource, predates this feature
 * (or its owning module hasn't started populating the column yet)" - runnable by anyone, but NOT
 * readable or editable by an arbitrary tenant member; see {@link #requireAccess}'s security note
 * for why that distinction exists and what changed on 2026-07-29.
 */
@Service
public class ResourceAccessService {

    // Granular RBAC (2026-09-23): the admin override is now domain-scoped -
    // MANAGE_AGENTS overrides on agents, MANAGE_WORKFLOWS_ADMIN on
    // workflows - instead of the blanket tenant-admin role. A delegated
    // ops-engineer administers exactly the resources their scope names.
    // TenantContext.getRoles() carries the JWT's full authority set, PERM_*
    // included (TenantContextFilter). platform-admin keeps the role check.
    private static final String PERM_MANAGE_AGENTS = "PERM_MANAGE_AGENTS";
    private static final String PERM_MANAGE_WORKFLOWS_ADMIN = "PERM_MANAGE_WORKFLOWS_ADMIN";
    private static final String ROLE_PLATFORM_ADMIN = "ROLE_platform-admin";

    private final ResourceGrantRepository repository;
    private final AuditLogService auditLogService;

    /**
     * Optional on purpose. {@link IdentityUserAdminPort} is {@code @Profile("security")}, but this
     * service is a plain {@code @Service} that {@code AgentService} depends on unconditionally - so
     * a hard constructor dependency made the whole application context unloadable under any profile
     * set that omits {@code security}, including the CI smoke test. It is needed at exactly one
     * place (resolving a grantee when a grant is created), never on the access-check path that
     * every request takes, so resolving it lazily keeps sharing fully functional in production
     * while letting the context boot without Keycloak. Grant creation fails loudly if it is
     * genuinely absent.
     */
    private final ObjectProvider<IdentityUserAdminPort> keycloakAdminClientProvider;

    public ResourceAccessService(
            ResourceGrantRepository repository,
            AuditLogService auditLogService,
            ObjectProvider<IdentityUserAdminPort> keycloakAdminClientProvider) {
        this.repository = repository;
        this.auditLogService = auditLogService;
        this.keycloakAdminClientProvider = keycloakAdminClientProvider;
    }

    private IdentityUserAdminPort requireIdentityUserAdmin() {
        IdentityUserAdminPort client = keycloakAdminClientProvider.getIfAvailable();
        if (client == null) {
            throw new IllegalStateException(
                    "Cannot resolve the grantee: IdentityUserAdminPort is unavailable because the"
                            + " 'security' profile is not active. Sharing requires a configured"
                            + " Keycloak.");
        }
        return client;
    }

    /**
     * Throws {@link ResourceAccessDeniedException} unless the current caller (from {@link
     * TenantContext}) has at least {@code required} effective permission on this resource.
     *
     * <p>Check order: (1) {@code createdBy == null} AND {@code required} is no higher than {@link
     * PermissionLevel#RUNNER} -&gt; {@link AccessCheckResult#LEGACY_OPEN} - see the security note
     * below for the exact scope of what stayed open and why; (2) the caller's subject matches
     * {@code createdBy} -&gt; {@link AccessCheckResult#OWNER}; (3) the caller holds {@code
     * ROLE_tenant-admin}/{@code ROLE_platform-admin} -&gt; {@link
     * AccessCheckResult#ADMIN_OVERRIDE}; (4) an active {@link ResourceGrant} for this caller on
     * this resource, at or above {@code required} (via {@link PermissionLevel#compareTo}) -&gt;
     * {@link AccessCheckResult#GRANTED}. Anything else throws.
     *
     * <p><b>SECURITY FIX (2026-07-29) - {@code LEGACY_OPEN} no longer bypasses {@code
     * required}.</b> This method used to return {@link AccessCheckResult#LEGACY_OPEN} for {@code
     * createdBy == null} unconditionally, before it looked at {@code required} or even at whether
     * there was a caller at all. Combined with {@code tenant/V151__agent_workflow_owner.sql} adding
     * {@code created_by} with no backfill, that made this entire permission model INERT for every
     * row that existed before V151 ran: a live check of the production database on 2026-07-29 found
     * 38 agents, all 38 with {@code created_by IS NULL}, so every {@code requireAccess} call in the
     * product - RUNNER, VIEWER and EDITOR alike - was a no-op. {@code
     * tenant/V155__backfill_agent_workflow_owner.sql} backfills the rows whose owner is
     * unambiguously resolvable; this change closes the hole for the rows it cannot resolve.
     *
     * <p><b>Why RUNNER stays open for a legacy row (deliberate, not an oversight).</b> The
     * graduated rule is: a legacy resource may still be EXECUTED by anyone, but its configuration
     * may no longer be READ or EDITED by an arbitrary tenant member - only by a tenant-admin/
     * platform-admin or an explicit grantee. RUNNER has to stay open because the anonymous product
     * surfaces all run legacy resources under synthetic, role-less subjects that can never satisfy
     * an ownership or grant check: the demo sandbox ({@code DemoSessionService}'s {@code
     * "demo:<sessionId>"} context - 29 of those 38 legacy agents live in {@code demo_*} schemas
     * that have zero rows in {@code control_plane.app_user}, i.e. no user identity exists for them
     * at all), the public agent-share surface ({@code AgentShareController}'s {@code
     * "system:agent-share:<shareId>"} context), every omnichannel webhook's per-channel subject,
     * and {@code AgentChatService#chatWithCollaborator}'s delegation path. Requiring anything above
     * RUNNER of those callers would take down the public landing funnel, not harden it. Everything
     * that is genuinely a configuration read or write goes through VIEWER/EDITOR and IS now gated.
     *
     * @throws ResourceAccessDeniedException if none of the above apply, or if there is no current
     *     {@link TenantContext} at all (an unauthenticated/unidentifiable caller can never qualify
     *     for anything above the legacy RUNNER carve-out).
     */
    public AccessCheckResult requireAccess(
            ResourceType type, UUID resourceId, String createdBy, PermissionLevel required) {
        if (createdBy == null && required.compareTo(PermissionLevel.RUNNER) <= 0) {
            return AccessCheckResult.LEGACY_OPEN;
        }
        Optional<TenantContext> context = TenantContext.current();
        if (context.isEmpty()) {
            throw new ResourceAccessDeniedException();
        }
        String callerSubject = context.get().getSubject();
        // Tenant REST API keys (V483): a machine credential can always
        // EXECUTE tenant resources - that is the whole point of the
        // automation surface - while reading or editing configuration still
        // needs the key's stored PERM_* scopes (the domain admin-override
        // check just below evaluates those). Same graduated posture as the
        // LEGACY_OPEN rule above: execution is cheap to grant broadly, the
        // run output is the key's own credential boundary; configuration is
        // not. The synthetic "apikey:" subject can never be a createdBy
        // match or hold an explicit ResourceGrant, so without this branch a
        // key could never run a non-legacy resource - defeating the feature.
        if (callerSubject != null
                && callerSubject.startsWith(ApiKeyAuthenticationToken.SUBJECT_PREFIX)
                && required.compareTo(PermissionLevel.RUNNER) <= 0) {
            return AccessCheckResult.GRANTED;
        }
        // createdBy is null here only for a legacy row at VIEWER/EDITOR - no
        // subject can ever "match" a null owner, so fall through to the
        // admin-override / explicit-grant checks rather than NPE on equals.
        if (createdBy != null && createdBy.equals(callerSubject)) {
            return AccessCheckResult.OWNER;
        }
        if (isAdmin(context.get(), type)) {
            return AccessCheckResult.ADMIN_OVERRIDE;
        }
        Optional<ResourceGrant> grant =
                repository.findByResourceTypeAndResourceIdAndGranteeUserIdAndRevokedAtIsNull(
                        type, resourceId, callerSubject);
        if (grant.isPresent() && grant.get().getPermissionLevel().compareTo(required) >= 0) {
            return AccessCheckResult.GRANTED;
        }
        throw new ResourceAccessDeniedException();
    }

    /**
     * The strictest bar this module offers: the caller must BE the resource's owner ({@code
     * createdBy} match) or hold {@code ROLE_tenant-admin}/ {@code ROLE_platform-admin}. No grant
     * level - not even {@link PermissionLevel#EDITOR} - satisfies it, exactly matching the bar
     * {@link #grant}/{@link #revoke} already enforce (see {@link #grant}'s javadoc: "an
     * EDITOR-level grantee must NOT be able to grant further access").
     *
     * <p><b>Why this exists (2026-07-29):</b> {@code AgentShareOwnerController}'s three endpoints -
     * which mint, inspect and revoke a PUBLIC, unauthenticated share URL for an agent - were gated
     * on {@code AgentService#get}, i.e. {@link PermissionLevel#VIEWER}, under a stale comment
     * claiming it "404s if this isn't the caller's own agent". Publishing a colleague's agent to
     * the anonymous internet (or revoking the owner's own link) sat a full level BELOW the bar for
     * handing that same colleague read access, which is backwards. Any caller who can reach this
     * must be able to give the resource away entirely, so it is owner-or-admin only.
     *
     * <p>A legacy resource ({@code createdBy == null}) therefore resolves to admin-only here:
     * nobody is its owner, so only a tenant-admin can publish it. That is the intended outcome, not
     * an edge case.
     *
     * @return {@link AccessCheckResult#OWNER} or {@link AccessCheckResult#ADMIN_OVERRIDE} - the
     *     caller's own audit decisions can distinguish "the owner did this" from "an admin did this
     *     to somebody else's resource".
     * @throws ResourceAccessDeniedException (404-shaped, same anti-enumeration posture as {@link
     *     #requireAccess}) otherwise.
     */
    public AccessCheckResult requireOwnerAccess(ResourceType type, String createdBy) {
        TenantContext context = requireOwnerOrAdmin(type, createdBy);
        return createdBy != null && createdBy.equals(context.getSubject())
                ? AccessCheckResult.OWNER
                : AccessCheckResult.ADMIN_OVERRIDE;
    }

    /**
     * Audit trail for minting a PUBLIC, unauthenticated share link ({@code
     * "agent.share.link.create"}/{@code "workflow.share.link.create"}). Deliberately a distinct
     * action type from {@link #grant}'s {@code "*.share.grant"}: that records giving one named,
     * same-tenant colleague a scoped grant; this records exposing the resource to the entire
     * internet, which is the more serious of the two and must be filterable on its own in the audit
     * UI.
     *
     * <p>Lives here rather than in the calling module so that "every share-shaped state change is
     * audited through one code path" stays a property of this service, matching {@link
     * AuditLogService}'s own "deliberately the only way anything writes to audit_log_entry"
     * javadoc.
     */
    public void recordPublicLinkCreated(ResourceType type, UUID resourceId, String detail) {
        auditLogService.record(
                requireCallerSubject(),
                targetTypeName(type) + ".share.link.create",
                targetTypeName(type),
                resourceId.toString(),
                AuditOutcome.SUCCESS,
                detail);
    }

    /**
     * Revocation counterpart to {@link #recordPublicLinkCreated} ({@code "*.share.link.revoke"}).
     */
    public void recordPublicLinkRevoked(ResourceType type, UUID resourceId, String detail) {
        auditLogService.record(
                requireCallerSubject(),
                targetTypeName(type) + ".share.link.revoke",
                targetTypeName(type),
                resourceId.toString(),
                AuditOutcome.SUCCESS,
                detail);
    }

    /**
     * Owner-only (or tenant-admin). Validates the grantee belongs to the SAME tenant via the same
     * pattern as {@code UserAdminController#requireInTenant} (reject a cross-tenant grantee - this
     * must never succeed), then upserts: re-granting an existing active grant updates its level
     * rather than erroring or duplicating (respects the partial unique index on {@code
     * (resource_type, resource_id, grantee_user_id) WHERE revoked_at IS NULL}). Writes an audit log
     * entry ({@code "agent.share.grant"} or {@code "workflow.share.grant"} - derived from {@code
     * type}).
     *
     * @throws ResourceAccessDeniedException if the caller is neither {@code createdBy} nor a
     *     tenant-admin - an EDITOR-level grantee must NOT be able to grant further access, only the
     *     owner or an admin can. 404-shaped for the same anti-enumeration reason as {@link
     *     #requireAccess} - a non-owner caller learns nothing about whether this resource exists or
     *     who else has access to it.
     * @throws InvalidGranteeException if {@code granteeUserId} does not resolve to a Keycloak user
     *     in the caller's own tenant.
     */
    @Transactional
    public ResourceGrant grant(
            ResourceType type,
            UUID resourceId,
            String createdBy,
            String granteeUserId,
            PermissionLevel level) {
        TenantContext context = requireOwnerOrAdmin(type, createdBy);
        requireGranteeInTenant(granteeUserId, context.getTenantId());

        Optional<ResourceGrant> existing =
                repository.findByResourceTypeAndResourceIdAndGranteeUserIdAndRevokedAtIsNull(
                        type, resourceId, granteeUserId);
        ResourceGrant saved;
        if (existing.isPresent()) {
            existing.get().updateLevel(level, context.getSubject());
            saved = repository.save(existing.get());
        } else {
            saved =
                    repository.save(
                            new ResourceGrant(
                                    type, resourceId, granteeUserId, level, context.getSubject()));
        }

        auditLogService.record(
                context.getSubject(),
                grantActionType(type),
                targetTypeName(type),
                resourceId.toString(),
                AuditOutcome.SUCCESS,
                "Granted " + level + " access to grantee " + granteeUserId);
        return saved;
    }

    /**
     * Owner-only (or tenant-admin) revoke by grant id, scoped to {@code type}/{@code resourceId} so
     * a grant id can't be used to revoke a grant belonging to a different resource.
     * Idempotent-safe: revoking an already-revoked or unknown grant id is a silent no-op, NOT a 404
     * - distinguishing "already revoked" from "never existed" would leak whether a given grant id
     * was ever real. Audited ({@code "agent.share.revoke"}/{@code "workflow.share.revoke"}) only
     * when an actual state change happened.
     */
    @Transactional
    public void revoke(ResourceType type, UUID resourceId, String createdBy, UUID grantId) {
        TenantContext context = requireOwnerOrAdmin(type, createdBy);
        Optional<ResourceGrant> found =
                repository.findByIdAndResourceTypeAndResourceId(grantId, type, resourceId);
        if (found.isEmpty() || !found.get().isActive()) {
            return;
        }
        ResourceGrant grant = found.get();
        grant.revoke(context.getSubject());
        repository.save(grant);
        auditLogService.record(
                context.getSubject(),
                revokeActionType(type),
                targetTypeName(type),
                resourceId.toString(),
                AuditOutcome.SUCCESS,
                "Revoked grant " + grantId + " (was " + grant.getGranteeUserId() + ")");
    }

    /**
     * Owner-only (or tenant-admin) - active grants for one resource, for the owner's "who has
     * access" panel.
     */
    public List<ResourceGrant> listGrants(ResourceType type, UUID resourceId, String createdBy) {
        requireOwnerOrAdmin(type, createdBy);
        return repository.findByResourceTypeAndResourceIdAndRevokedAtIsNullOrderByGrantedAtDesc(
                type, resourceId);
    }

    /** Current caller's active grants across both resource types, for a "shared with me" view. */
    public List<ResourceGrant> listSharedWithMe() {
        String subject = requireCallerSubject();
        return repository.findByGranteeUserIdAndRevokedAtIsNullOrderByGrantedAtDesc(subject);
    }

    // ------------------------------------------------------------------

    private TenantContext requireOwnerOrAdmin(ResourceType type, String createdBy) {
        Optional<TenantContext> context = TenantContext.current();
        if (context.isEmpty()) {
            throw new ResourceAccessDeniedException();
        }
        TenantContext ctx = context.get();
        boolean isOwner = createdBy != null && createdBy.equals(ctx.getSubject());
        if (!isOwner && !isAdmin(ctx, type)) {
            throw new ResourceAccessDeniedException();
        }
        return ctx;
    }

    private String requireCallerSubject() {
        return TenantContext.current()
                .map(TenantContext::getSubject)
                .orElseThrow(ResourceAccessDeniedException::new);
    }

    private boolean isAdmin(TenantContext context, ResourceType type) {
        String perm =
                switch (type) {
                    case AGENT -> PERM_MANAGE_AGENTS;
                    case WORKFLOW -> PERM_MANAGE_WORKFLOWS_ADMIN;
                };
        return context.hasRole(perm) || context.hasRole(ROLE_PLATFORM_ADMIN);
    }

    /**
     * Same pattern as {@code UserAdminController#requireInTenant}: fetch the grantee by id and
     * confirm their {@code tenant_id} Keycloak attribute matches the caller's own tenant. Defense
     * in depth - {@link ShareController} is expected to have already resolved the grantee's email
     * to a same-tenant user id before ever calling {@link #grant}, but this service's own inputs
     * are plain ids with no guarantee of where they came from, so it re-checks rather than trusting
     * the caller.
     */
    private void requireGranteeInTenant(String granteeUserId, String callerTenantId) {
        KeycloakUserRepresentation grantee;
        try {
            grantee = requireIdentityUserAdmin().getUserById(granteeUserId);
        } catch (RuntimeException e) {
            throw new InvalidGranteeException();
        }
        boolean sameTenant =
                grantee.attributes() != null
                        && grantee.attributes()
                                .getOrDefault("tenant_id", List.of())
                                .contains(callerTenantId);
        if (!sameTenant) {
            throw new InvalidGranteeException();
        }
    }

    private static String grantActionType(ResourceType type) {
        return targetTypeName(type) + ".share.grant";
    }

    private static String revokeActionType(ResourceType type) {
        return targetTypeName(type) + ".share.revoke";
    }

    private static String targetTypeName(ResourceType type) {
        return type.name().toLowerCase(java.util.Locale.ROOT);
    }
}
