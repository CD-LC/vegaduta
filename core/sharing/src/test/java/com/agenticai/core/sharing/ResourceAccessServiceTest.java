package com.agenticai.core.sharing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.admin.identity.IdentityUserAdminPort;
import com.agenticai.core.admin.keycloak.KeycloakAdminClient;
import com.agenticai.core.admin.keycloak.KeycloakUserRepresentation;
import com.agenticai.core.audit.AuditLogEntry;
import com.agenticai.core.audit.AuditOutcome;
import com.agenticai.core.audit.AuditLogService;
import com.agenticai.core.security.TenantContext;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Covers {@link ResourceAccessService}'s full contract per the sharing workstream spec:
 * legacy-open, owner, tenant-admin, grant-level ordinal checks, re-grant-upserts, idempotent
 * revoke, non-owner denial, and cross-tenant grantee rejection. Plain Mockito unit tests, no Spring
 * context - same style as {@code AgentServiceTest}.
 */
class ResourceAccessServiceTest {

    private static final String TENANT_ID = "tenant-1";
    private static final String OWNER = "owner-sub";
    private static final String OTHER_USER = "other-sub";
    private static final UUID RESOURCE_ID = UUID.randomUUID();

    private ResourceGrantRepository repository;
    private AuditLogService auditLogService;
    private KeycloakAdminClient keycloakAdminClient;
    private ResourceAccessService service;

    @BeforeEach
    void setUp() {
        repository = mock(ResourceGrantRepository.class);
        auditLogService = mock(AuditLogService.class);
        keycloakAdminClient = mock(KeycloakAdminClient.class);
        ObjectProvider<IdentityUserAdminPort> keycloakProvider =
                mockProviderOf(keycloakAdminClient);
        service = new ResourceAccessService(repository, auditLogService, keycloakProvider);
        when(repository.save(any(ResourceGrant.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(auditLogService.record(any(), any(), any(), any(), any(), any()))
                .thenReturn(mock(AuditLogEntry.class));
    }

    @AfterEach
    void clearContext() {
        TenantContext.clear();
    }

    private void loginAs(String subject, Set<String> roles) {
        TenantContext.set(TenantContext.of(TENANT_ID, subject, roles));
    }

    // -- requireAccess ---------------------------------------------------

    // -- Legacy (createdBy == null) after the 2026-07-29 narrowing ---------
    //
    // These four replace a single old test that asserted the bug:
    // "legacyResourceAlwaysAllowed_evenWithNoTenantContext" passed
    // PermissionLevel.EDITOR and expected LEGACY_OPEN, which is precisely
    // the behaviour that made this whole permission model inert (every
    // agent row in the live database had created_by IS NULL). RUNNER stays
    // open on purpose - see requireAccess's javadoc.

    @Test
    void legacyResourceStillRunnableByAnyone_evenWithNoTenantContext() {
        AccessCheckResult result =
                service.requireAccess(
                        ResourceType.AGENT, RESOURCE_ID, null, PermissionLevel.RUNNER);
        assertEquals(AccessCheckResult.LEGACY_OPEN, result);
    }

    @Test
    void legacyResourceNoLongerGrantsViewerOrEditorToAnArbitraryMember() {
        loginAs(OTHER_USER, Set.of());
        when(repository.findByResourceTypeAndResourceIdAndGranteeUserIdAndRevokedAtIsNull(
                        any(), any(), any()))
                .thenReturn(Optional.empty());

        assertThrows(
                ResourceAccessDeniedException.class,
                () ->
                        service.requireAccess(
                                ResourceType.AGENT, RESOURCE_ID, null, PermissionLevel.VIEWER));
        assertThrows(
                ResourceAccessDeniedException.class,
                () ->
                        service.requireAccess(
                                ResourceType.AGENT, RESOURCE_ID, null, PermissionLevel.EDITOR));
    }

    @Test
    void legacyResourceAboveRunnerRequiresIdentifiableCaller() {
        // No TenantContext at all - the old code returned LEGACY_OPEN here
        // before it ever looked for a caller.
        assertThrows(
                ResourceAccessDeniedException.class,
                () ->
                        service.requireAccess(
                                ResourceType.AGENT, RESOURCE_ID, null, PermissionLevel.EDITOR));
    }

    @Test
    void legacyResourceIsStillReachableByAScopedAdminAndByAnExplicitGrantee() {
        loginAs(OTHER_USER, Set.of("PERM_MANAGE_AGENTS"));
        assertEquals(
                AccessCheckResult.ADMIN_OVERRIDE,
                service.requireAccess(
                        ResourceType.AGENT, RESOURCE_ID, null, PermissionLevel.EDITOR));

        loginAs(OTHER_USER, Set.of());
        ResourceGrant editorGrant =
                new ResourceGrant(
                        ResourceType.AGENT, RESOURCE_ID, OTHER_USER, PermissionLevel.EDITOR, OWNER);
        when(repository.findByResourceTypeAndResourceIdAndGranteeUserIdAndRevokedAtIsNull(
                        ResourceType.AGENT, RESOURCE_ID, OTHER_USER))
                .thenReturn(Optional.of(editorGrant));
        assertEquals(
                AccessCheckResult.GRANTED,
                service.requireAccess(
                        ResourceType.AGENT, RESOURCE_ID, null, PermissionLevel.EDITOR));
    }

    // -- requireOwnerAccess (the public-share-link bar) --------------------

    @Test
    void requireOwnerAccessAllowsOwnerAndAdmin() {
        loginAs(OWNER, Set.of());
        assertEquals(
                AccessCheckResult.OWNER, service.requireOwnerAccess(ResourceType.AGENT, OWNER));

        loginAs("admin-sub", Set.of("PERM_MANAGE_AGENTS"));
        assertEquals(
                AccessCheckResult.ADMIN_OVERRIDE,
                service.requireOwnerAccess(ResourceType.AGENT, OWNER));
    }

    @Test
    void requireOwnerAccessRejectsEvenAnEditorGrantee() {
        // The whole point: publishing a resource to the anonymous internet
        // must not be reachable one level BELOW the bar for granting a
        // single colleague access (see #grant's javadoc).
        loginAs(OTHER_USER, Set.of());
        ResourceGrant editorGrant =
                new ResourceGrant(
                        ResourceType.AGENT, RESOURCE_ID, OTHER_USER, PermissionLevel.EDITOR, OWNER);
        when(repository.findByResourceTypeAndResourceIdAndGranteeUserIdAndRevokedAtIsNull(
                        ResourceType.AGENT, RESOURCE_ID, OTHER_USER))
                .thenReturn(Optional.of(editorGrant));

        assertThrows(
                ResourceAccessDeniedException.class,
                () -> service.requireOwnerAccess(ResourceType.AGENT, OWNER));
    }

    @Test
    void requireOwnerAccessOnALegacyResourceIsAdminOnly() {
        loginAs(OTHER_USER, Set.of());
        assertThrows(
                ResourceAccessDeniedException.class,
                () -> service.requireOwnerAccess(ResourceType.AGENT, null));

        loginAs(OTHER_USER, Set.of("PERM_MANAGE_AGENTS"));
        assertEquals(
                AccessCheckResult.ADMIN_OVERRIDE,
                service.requireOwnerAccess(ResourceType.AGENT, null));
    }

    @Test
    void requireOwnerAccessRejectsAnUnauthenticatedCaller() {
        assertThrows(
                ResourceAccessDeniedException.class,
                () -> service.requireOwnerAccess(ResourceType.AGENT, OWNER));
    }

    // -- public share link auditing ---------------------------------------

    @Test
    void publicLinkCreateAndRevokeAreAuditedUnderTheirOwnActionTypes() {
        loginAs(OWNER, Set.of());

        service.recordPublicLinkCreated(
                ResourceType.AGENT, RESOURCE_ID, "Minted public share link abc");
        verify(auditLogService)
                .record(
                        eqAny(OWNER),
                        eqAny("agent.share.link.create"),
                        eqAny("agent"),
                        eqAny(RESOURCE_ID.toString()),
                        eqAny(AuditOutcome.SUCCESS),
                        any());

        service.recordPublicLinkRevoked(
                ResourceType.WORKFLOW, RESOURCE_ID, "Revoked public share link abc");
        verify(auditLogService)
                .record(
                        eqAny(OWNER),
                        eqAny("workflow.share.link.revoke"),
                        eqAny("workflow"),
                        eqAny(RESOURCE_ID.toString()),
                        eqAny(AuditOutcome.SUCCESS),
                        any());
    }

    @Test
    void ownerAlwaysAllowed() {
        loginAs(OWNER, Set.of());
        AccessCheckResult result =
                service.requireAccess(
                        ResourceType.AGENT, RESOURCE_ID, OWNER, PermissionLevel.EDITOR);
        assertEquals(AccessCheckResult.OWNER, result);
    }

    @Test
    void scopedAdminAlwaysAllowed() {
        loginAs(OTHER_USER, Set.of("PERM_MANAGE_AGENTS"));
        AccessCheckResult result =
                service.requireAccess(
                        ResourceType.AGENT, RESOURCE_ID, OWNER, PermissionLevel.EDITOR);
        assertEquals(AccessCheckResult.ADMIN_OVERRIDE, result);
    }

    @Test
    void runnerGrantPassesRunnerButFailsViewerAndEditor() {
        loginAs(OTHER_USER, Set.of());
        ResourceGrant runnerGrant =
                new ResourceGrant(
                        ResourceType.AGENT, RESOURCE_ID, OTHER_USER, PermissionLevel.RUNNER, OWNER);
        when(repository.findByResourceTypeAndResourceIdAndGranteeUserIdAndRevokedAtIsNull(
                        ResourceType.AGENT, RESOURCE_ID, OTHER_USER))
                .thenReturn(Optional.of(runnerGrant));

        assertEquals(
                AccessCheckResult.GRANTED,
                service.requireAccess(
                        ResourceType.AGENT, RESOURCE_ID, OWNER, PermissionLevel.RUNNER));
        assertThrows(
                ResourceAccessDeniedException.class,
                () ->
                        service.requireAccess(
                                ResourceType.AGENT, RESOURCE_ID, OWNER, PermissionLevel.VIEWER));
        assertThrows(
                ResourceAccessDeniedException.class,
                () ->
                        service.requireAccess(
                                ResourceType.AGENT, RESOURCE_ID, OWNER, PermissionLevel.EDITOR));
    }

    @Test
    void editorGrantImpliesViewerImpliesRunner() {
        loginAs(OTHER_USER, Set.of());
        ResourceGrant editorGrant =
                new ResourceGrant(
                        ResourceType.AGENT, RESOURCE_ID, OTHER_USER, PermissionLevel.EDITOR, OWNER);
        when(repository.findByResourceTypeAndResourceIdAndGranteeUserIdAndRevokedAtIsNull(
                        ResourceType.AGENT, RESOURCE_ID, OTHER_USER))
                .thenReturn(Optional.of(editorGrant));

        for (PermissionLevel required : PermissionLevel.values()) {
            assertEquals(
                    AccessCheckResult.GRANTED,
                    service.requireAccess(ResourceType.AGENT, RESOURCE_ID, OWNER, required));
        }
    }

    @Test
    void noAccessAtAllThrows() {
        loginAs(OTHER_USER, Set.of());
        when(repository.findByResourceTypeAndResourceIdAndGranteeUserIdAndRevokedAtIsNull(
                        any(), any(), any()))
                .thenReturn(Optional.empty());
        assertThrows(
                ResourceAccessDeniedException.class,
                () ->
                        service.requireAccess(
                                ResourceType.AGENT, RESOURCE_ID, OWNER, PermissionLevel.RUNNER));
    }

    // -- grant -------------------------------------------------------------

    @Test
    void ownerCanGrant_validatesSameTenantGrantee() {
        loginAs(OWNER, Set.of());
        when(keycloakAdminClient.getUserById(OTHER_USER)).thenReturn(sameTenantUser(OTHER_USER));
        when(repository.findByResourceTypeAndResourceIdAndGranteeUserIdAndRevokedAtIsNull(
                        ResourceType.AGENT, RESOURCE_ID, OTHER_USER))
                .thenReturn(Optional.empty());

        ResourceGrant grant =
                service.grant(
                        ResourceType.AGENT, RESOURCE_ID, OWNER, OTHER_USER, PermissionLevel.VIEWER);

        assertEquals(PermissionLevel.VIEWER, grant.getPermissionLevel());
        verify(auditLogService)
                .record(
                        eqAny(OWNER),
                        eqAny("agent.share.grant"),
                        any(),
                        any(),
                        eqAny(AuditOutcome.SUCCESS),
                        any());
    }

    @Test
    void reGrantingExistingActiveGrantUpdatesRatherThanDuplicates() {
        loginAs(OWNER, Set.of());
        when(keycloakAdminClient.getUserById(OTHER_USER)).thenReturn(sameTenantUser(OTHER_USER));
        ResourceGrant existing =
                new ResourceGrant(
                        ResourceType.AGENT, RESOURCE_ID, OTHER_USER, PermissionLevel.RUNNER, OWNER);
        when(repository.findByResourceTypeAndResourceIdAndGranteeUserIdAndRevokedAtIsNull(
                        ResourceType.AGENT, RESOURCE_ID, OTHER_USER))
                .thenReturn(Optional.of(existing));

        ResourceGrant result =
                service.grant(
                        ResourceType.AGENT, RESOURCE_ID, OWNER, OTHER_USER, PermissionLevel.EDITOR);

        assertEquals(PermissionLevel.EDITOR, result.getPermissionLevel());
        // Same instance updated in place, not a second row created.
        verify(repository, times(1)).save(existing);
    }

    @Test
    void nonOwnerCannotGrant_evenWithEditorGrant() {
        loginAs(OTHER_USER, Set.of());
        ResourceGrant editorGrant =
                new ResourceGrant(
                        ResourceType.AGENT, RESOURCE_ID, OTHER_USER, PermissionLevel.EDITOR, OWNER);
        when(repository.findByResourceTypeAndResourceIdAndGranteeUserIdAndRevokedAtIsNull(
                        ResourceType.AGENT, RESOURCE_ID, OTHER_USER))
                .thenReturn(Optional.of(editorGrant));

        assertThrows(
                ResourceAccessDeniedException.class,
                () ->
                        service.grant(
                                ResourceType.AGENT,
                                RESOURCE_ID,
                                OWNER,
                                "third-user",
                                PermissionLevel.VIEWER));
    }

    @Test
    void crossTenantGranteeRejected() {
        loginAs(OWNER, Set.of());
        when(keycloakAdminClient.getUserById(OTHER_USER)).thenReturn(crossTenantUser(OTHER_USER));

        assertThrows(
                InvalidGranteeException.class,
                () ->
                        service.grant(
                                ResourceType.AGENT,
                                RESOURCE_ID,
                                OWNER,
                                OTHER_USER,
                                PermissionLevel.VIEWER));
    }

    @Test
    void scopedAdminCanGrantOnBehalfOfAnotherOwner() {
        loginAs("admin-sub", Set.of("PERM_MANAGE_AGENTS"));
        when(keycloakAdminClient.getUserById(OTHER_USER)).thenReturn(sameTenantUser(OTHER_USER));
        when(repository.findByResourceTypeAndResourceIdAndGranteeUserIdAndRevokedAtIsNull(
                        ResourceType.AGENT, RESOURCE_ID, OTHER_USER))
                .thenReturn(Optional.empty());

        ResourceGrant grant =
                service.grant(
                        ResourceType.AGENT, RESOURCE_ID, OWNER, OTHER_USER, PermissionLevel.RUNNER);
        assertEquals(PermissionLevel.RUNNER, grant.getPermissionLevel());
    }

    // -- revoke --------------------------------------------------------

    @Test
    void revokeIsIdempotentSafe() {
        loginAs(OWNER, Set.of());
        UUID grantId = UUID.randomUUID();
        ResourceGrant grant =
                new ResourceGrant(
                        ResourceType.AGENT, RESOURCE_ID, OTHER_USER, PermissionLevel.VIEWER, OWNER);
        when(repository.findByIdAndResourceTypeAndResourceId(
                        grantId, ResourceType.AGENT, RESOURCE_ID))
                .thenReturn(Optional.of(grant));

        service.revoke(ResourceType.AGENT, RESOURCE_ID, OWNER, grantId);
        assertTrue(grant.getRevokedAt() != null);
        verify(auditLogService, times(1)).record(any(), any(), any(), any(), any(), any());

        // Second revoke of the same (now-revoked) grant: no-op, no second audit entry.
        service.revoke(ResourceType.AGENT, RESOURCE_ID, OWNER, grantId);
        verify(auditLogService, times(1)).record(any(), any(), any(), any(), any(), any());
    }

    @Test
    void revokeOfUnknownGrantIdIsSilentNoOp() {
        loginAs(OWNER, Set.of());
        UUID unknownGrantId = UUID.randomUUID();
        when(repository.findByIdAndResourceTypeAndResourceId(
                        unknownGrantId, ResourceType.AGENT, RESOURCE_ID))
                .thenReturn(Optional.empty());

        service.revoke(ResourceType.AGENT, RESOURCE_ID, OWNER, unknownGrantId);
        verify(auditLogService, never()).record(any(), any(), any(), any(), any(), any());
    }

    @Test
    void nonOwnerCannotRevoke() {
        loginAs(OTHER_USER, Set.of());
        assertThrows(
                ResourceAccessDeniedException.class,
                () -> service.revoke(ResourceType.AGENT, RESOURCE_ID, OWNER, UUID.randomUUID()));
    }

    // ------------------------------------------------------------------

    private static KeycloakUserRepresentation sameTenantUser(String id) {
        return new KeycloakUserRepresentation(
                id,
                "user",
                "user@example.com",
                "First",
                "Last",
                true,
                true,
                List.of(),
                Map.of("tenant_id", List.of(TENANT_ID)),
                List.of());
    }

    private static KeycloakUserRepresentation crossTenantUser(String id) {
        return new KeycloakUserRepresentation(
                id,
                "user",
                "user@example.com",
                "First",
                "Last",
                true,
                true,
                List.of(),
                Map.of("tenant_id", List.of("some-other-tenant")),
                List.of());
    }

    // Small readability helper so verify() calls above don't need `eq(...)` imported alongside
    // `any()`.
    private static <T> T eqAny(T value) {
        return org.mockito.ArgumentMatchers.eq(value);
    }

    /**
     * ResourceAccessService takes IdentityUserAdminPort as an ObjectProvider because the client
     * is @Profile("security") while the service is unconditional - see that constructor's javadoc.
     * Tests supply a provider that always resolves.
     */
    @SuppressWarnings("unchecked")
    private static ObjectProvider<IdentityUserAdminPort> mockProviderOf(
            IdentityUserAdminPort client) {
        ObjectProvider<IdentityUserAdminPort> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(client);
        return provider;
    }

    // -- knowledge collections (V710) ------------------------------------

    @Test
    void activeGrantLevels_keepsTheHighestLevelPerCollectionAndIgnoresOtherTypes() {
        UUID collectionA = UUID.randomUUID();
        UUID collectionB = UUID.randomUUID();
        ResourceGrant viewerA = new ResourceGrant(ResourceType.KNOWLEDGE_COLLECTION, collectionA, OTHER_USER, PermissionLevel.VIEWER, OWNER);
        ResourceGrant editorA = new ResourceGrant(ResourceType.KNOWLEDGE_COLLECTION, collectionA, OTHER_USER, PermissionLevel.EDITOR, OWNER);
        ResourceGrant viewerB = new ResourceGrant(ResourceType.KNOWLEDGE_COLLECTION, collectionB, OTHER_USER, PermissionLevel.VIEWER, OWNER);
        ResourceGrant agentGrant = new ResourceGrant(ResourceType.AGENT, RESOURCE_ID, OTHER_USER, PermissionLevel.EDITOR, OWNER);
        when(repository.findByGranteeUserIdAndRevokedAtIsNullOrderByGrantedAtDesc(OTHER_USER))
                .thenReturn(List.of(viewerA, editorA, viewerB, agentGrant));

        Map<UUID, PermissionLevel> levels =
                service.activeGrantLevels(ResourceType.KNOWLEDGE_COLLECTION, OTHER_USER);

        assertEquals(Map.of(collectionA, PermissionLevel.EDITOR, collectionB, PermissionLevel.VIEWER), levels);
        assertEquals(Map.of(), service.activeGrantLevels(ResourceType.KNOWLEDGE_COLLECTION, null));
        assertEquals(Map.of(), service.activeGrantLevels(ResourceType.KNOWLEDGE_COLLECTION, " "));
    }

    @Test
    void knowledgeCollectionAdminOverride_isTheTenantAdminRoleNotTheBroadBuilderPermission() {
        TenantContext builder = TenantContext.of(TENANT_ID, OTHER_USER, Set.of("PERM_MANAGE_AGENTS"));
        TenantContext tenantAdmin = TenantContext.of(TENANT_ID, OTHER_USER, Set.of("ROLE_tenant-admin"));
        TenantContext platformAdmin = TenantContext.of(TENANT_ID, OTHER_USER, Set.of("ROLE_platform-admin"));

        org.junit.jupiter.api.Assertions.assertFalse(service.isAdminFor(ResourceType.KNOWLEDGE_COLLECTION, builder));
        assertTrue(service.isAdminFor(ResourceType.KNOWLEDGE_COLLECTION, tenantAdmin));
        assertTrue(service.isAdminFor(ResourceType.KNOWLEDGE_COLLECTION, platformAdmin));
        // The agent override is unchanged.
        assertTrue(service.isAdminFor(ResourceType.AGENT, builder));
    }
}
