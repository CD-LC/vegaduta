package com.agenticai.core.agents.share;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.admin.identity.IdentityUserAdminPort;
import com.agenticai.core.admin.keycloak.KeycloakAdminClient;
import com.agenticai.core.agents.core.Agent;
import com.agenticai.core.agents.core.AgentNotFoundException;
import com.agenticai.core.agents.core.AgentRepository;
import com.agenticai.core.audit.AuditLogService;
import com.agenticai.core.audit.AuditOutcome;
import com.agenticai.core.providers.ProviderName;
import com.agenticai.core.security.ExperienceMode;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.sharing.PermissionLevel;
import com.agenticai.core.sharing.ResourceAccessDeniedException;
import com.agenticai.core.sharing.ResourceAccessService;
import com.agenticai.core.sharing.ResourceGrant;
import com.agenticai.core.sharing.ResourceGrantRepository;
import com.agenticai.core.sharing.ResourceType;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Security regression coverage for the 2026-07-29 finding: the three endpoints that mint / inspect
 * / revoke a PUBLIC, unauthenticated share URL for an agent were gated on {@code AgentService#get}
 * - a {@code PermissionLevel.VIEWER} check - under a comment claiming it "404s if this isn't the
 * caller's own agent". It did not. A VIEWER-level grantee could publish a colleague's agent to the
 * anonymous internet and revoke the owner's link; and because {@code
 * tenant/V151__agent_workflow_owner.sql} backfilled nothing, every agent row was {@code created_by
 * IS NULL} and {@code requireAccess} short-circuited to {@code LEGACY_OPEN}, so in practice it was
 * any authenticated tenant member on any agent.
 *
 * <p>Runs the controller against a REAL {@link ResourceAccessService} and a REAL {@link
 * AgentShareService} (only the repositories and infrastructure leaves are mocked) - same "real
 * service under test, mock the leaves" style as {@code AgentResourceSharingTest} - so these
 * assertions exercise the actual authorization logic rather than a stubbed gate.
 */
class AgentShareOwnerControllerTest {

    private static final String TENANT_ID = "tenant-1";
    private static final String OWNER = "owner-sub";
    private static final String OTHER_USER = "other-sub";

    private AgentRepository agentRepository;
    private AgentShareRepository shareRepository;
    private ResourceGrantRepository grantRepository;
    private AuditLogService auditLogService;
    private AgentShareOwnerController controller;

    @BeforeEach
    void setUp() {
        agentRepository = mock(AgentRepository.class);
        shareRepository = mock(AgentShareRepository.class);
        grantRepository = mock(ResourceGrantRepository.class);
        auditLogService = mock(AuditLogService.class);

        ResourceAccessService resourceAccessService =
                new ResourceAccessService(
                        grantRepository,
                        auditLogService,
                        mockProviderOf(mock(KeycloakAdminClient.class)));
        AgentShareService shareService =
                new AgentShareService(
                        shareRepository,
                        agentRepository,
                        resourceAccessService,
                        mock(com.agenticai.core.tenancy.TenantResolutionService.class),
                        mock(StringRedisTemplate.class),
                        "https://app.staging.example.com",
                        3,
                        12,
                        4,
                        20,
                        6);
        controller = new AgentShareOwnerController(shareService, resourceAccessService);
        when(shareRepository.saveAndFlush(any(AgentShare.class)))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    @AfterEach
    void clearTenantContext() {
        TenantContext.clear();
    }

    // -- A non-owner cannot publish or unpublish --------------------------

    @Test
    void nonOwnerCannotCreateAPublicShareLink() {
        UUID agentId = stubAgentOwnedBy(OWNER);
        loginAs(OTHER_USER, Set.of());
        noGrantsFor(OTHER_USER);

        assertThrows(ResourceAccessDeniedException.class, () -> controller.create(agentId));

        verify(shareRepository, never()).saveAndFlush(any());
        verify(auditLogService, never()).record(any(), any(), any(), any(), any(), any());
    }

    @Test
    void nonOwnerCannotRevokeTheOwnersPublicShareLink() {
        UUID agentId = stubAgentOwnedBy(OWNER);
        loginAs(OTHER_USER, Set.of());
        noGrantsFor(OTHER_USER);

        assertThrows(ResourceAccessDeniedException.class, () -> controller.revoke(agentId));

        // The share row is never even looked at, let alone revoked.
        verify(shareRepository, never()).findByAgentId(any());
    }

    @Test
    void nonOwnerCannotReadTheShareStatus() {
        // status() returns the live share URL itself, so it is owner-gated
        // too - handing a non-owner the public capability URL would defeat
        // the point of gating the mint.
        UUID agentId = stubAgentOwnedBy(OWNER);
        loginAs(OTHER_USER, Set.of());
        noGrantsFor(OTHER_USER);

        assertThrows(ResourceAccessDeniedException.class, () -> controller.status(agentId));
    }

    @Test
    void evenAnEditorGranteeCannotPublishToTheInternet() {
        // The module's own contract (ResourceAccessService#grant) says an
        // EDITOR must not be able to widen access further. Anonymous public
        // exposure must therefore not sit BELOW that bar.
        UUID agentId = stubAgentOwnedBy(OWNER);
        loginAs(OTHER_USER, Set.of());
        when(grantRepository.findByResourceTypeAndResourceIdAndGranteeUserIdAndRevokedAtIsNull(
                        ResourceType.AGENT, agentId, OTHER_USER))
                .thenReturn(
                        Optional.of(
                                new ResourceGrant(
                                        ResourceType.AGENT,
                                        agentId,
                                        OTHER_USER,
                                        PermissionLevel.EDITOR,
                                        OWNER)));

        assertThrows(ResourceAccessDeniedException.class, () -> controller.create(agentId));
        assertThrows(ResourceAccessDeniedException.class, () -> controller.revoke(agentId));
    }

    // -- A legacy (created_by IS NULL) agent is admin-only, not open ------

    @Test
    void legacyAgentCannotBePublishedByAnArbitraryTenantMember() {
        UUID agentId = stubAgentOwnedBy(null); // exactly the live production state pre-V155
        loginAs(OTHER_USER, Set.of());
        noGrantsFor(OTHER_USER);

        assertThrows(ResourceAccessDeniedException.class, () -> controller.create(agentId));
        assertThrows(ResourceAccessDeniedException.class, () -> controller.revoke(agentId));
        verify(shareRepository, never()).saveAndFlush(any());
    }

    @Test
    void legacyAgentCanStillBePublishedByATenantAdmin() {
        UUID agentId = stubAgentOwnedBy(null);
        loginAs("admin-sub", Set.of("PERM_MANAGE_AGENTS"));
        when(shareRepository.findByAgentId(agentId))
                .thenReturn(Optional.empty(), Optional.of(new AgentShare(agentId, "share-abc")));

        assertDoesNotThrow(() -> controller.create(agentId));
    }

    // -- The owner still works, and is now audited ------------------------

    @Test
    void ownerCanCreateAndTheMintIsAudited() {
        UUID agentId = stubAgentOwnedBy(OWNER);
        loginAs(OWNER, Set.of());
        when(shareRepository.findByAgentId(agentId))
                .thenReturn(Optional.empty(), Optional.of(new AgentShare(agentId, "share-abc")));

        assertEquals(201, controller.create(agentId).getStatusCode().value());

        verify(auditLogService)
                .record(
                        eq(OWNER),
                        eq("agent.share.link.create"),
                        eq("agent"),
                        eq(agentId.toString()),
                        eq(AuditOutcome.SUCCESS),
                        any());
    }

    @Test
    void ownerRevokeIsAuditedOnlyWhenThereWasALiveLinkToKill() {
        UUID agentId = stubAgentOwnedBy(OWNER);
        loginAs(OWNER, Set.of());

        // Nothing minted yet - revoke is a no-op and writes no audit row,
        // matching ResourceAccessService#revoke's own "audit an actual state
        // change, not a no-op" rule.
        when(shareRepository.findByAgentId(agentId)).thenReturn(Optional.empty());
        controller.revoke(agentId);
        verify(auditLogService, never()).record(any(), any(), any(), any(), any(), any());

        // A live link IS audited on the way out.
        when(shareRepository.findByAgentId(agentId))
                .thenReturn(Optional.of(new AgentShare(agentId, "share-abc")));
        controller.revoke(agentId);
        verify(auditLogService)
                .record(
                        eq(OWNER),
                        eq("agent.share.link.revoke"),
                        eq("agent"),
                        eq(agentId.toString()),
                        eq(AuditOutcome.SUCCESS),
                        any());
    }

    // -- Re-share after revoke: the Hibernate flush-ordering 500 ----------

    @Test
    void ownerReShareAfterRevokeReturns201WithAFreshShareId() {
        // Production defect, confirmed live on the workflow twin
        // (uq_workflow_share_workflow_id; identical constraint shape here):
        // share -> revoke -> re-share 500ed because Hibernate's ActionQueue
        // executes INSERTs BEFORE DELETEs at flush time, so the queued
        // delete() never cleared the old row before saveAndFlush inserted
        // its replacement - and the constraint violation aborted the
        // transaction, taking the catch-path recovery query down with it.
        // The service now flush()es the delete explicitly; this pins both
        // the HTTP outcome and the repository ordering that makes it work.
        UUID agentId = stubAgentOwnedBy(OWNER);
        loginAs(OWNER, Set.of());
        AgentShare revoked = new AgentShare(agentId, "dead-share-id");
        revoked.revoke();

        // State-based stubbing: createOrGet's initial lookup sees the
        // revoked row; the controller's post-create statusFor must see the
        // replacement row saveAndFlush received.
        AtomicReference<AgentShare> inserted = new AtomicReference<>();
        when(shareRepository.saveAndFlush(any(AgentShare.class)))
                .thenAnswer(
                        invocation -> {
                            AgentShare share = invocation.getArgument(0);
                            inserted.set(share);
                            return share;
                        });
        when(shareRepository.findByAgentId(agentId))
                .thenAnswer(
                        invocation ->
                                inserted.get() == null
                                        ? Optional.of(revoked)
                                        : Optional.of(inserted.get()));

        var response = controller.create(agentId);

        assertEquals(201, response.getStatusCode().value());
        assertNotNull(response.getBody());
        assertNotEquals("dead-share-id", response.getBody().shareId());

        // The ordering the fix exists to guarantee: the DELETE must reach
        // the database (flush) before the INSERT runs.
        InOrder order = inOrder(shareRepository);
        order.verify(shareRepository).delete(revoked);
        order.verify(shareRepository).flush();
        order.verify(shareRepository).saveAndFlush(any(AgentShare.class));
    }

    // -- Unchanged: an unknown agent id is still a plain 404 --------------

    @Test
    void unknownAgentIdStillThrowsAgentNotFound() {
        UUID agentId = UUID.randomUUID();
        when(agentRepository.findById(agentId)).thenReturn(Optional.empty());
        loginAs(OWNER, Set.of());

        assertThrows(AgentNotFoundException.class, () -> controller.create(agentId));
    }

    // ------------------------------------------------------------------

    private void loginAs(String subject, Set<String> roles) {
        TenantContext.set(TenantContext.of(TENANT_ID, subject, roles));
    }

    private void noGrantsFor(String subject) {
        when(grantRepository.findByResourceTypeAndResourceIdAndGranteeUserIdAndRevokedAtIsNull(
                        any(), any(), eq(subject)))
                .thenReturn(Optional.empty());
    }

    private UUID stubAgentOwnedBy(String createdBy) {
        Agent agent =
                new Agent(
                        "Test Agent",
                        "You are a helpful assistant.",
                        ProviderName.ANTHROPIC,
                        "claude-sonnet-5",
                        1024,
                        0.7,
                        1.0,
                        false,
                        ExperienceMode.ADVANCED);
        agent.setCreatedBy(createdBy);
        UUID id = UUID.randomUUID();
        when(agentRepository.findById(id)).thenReturn(Optional.of(agent));
        return id;
    }

    /**
     * See {@code ResourceAccessService}'s constructor javadoc for why KeycloakAdminClient is an
     * ObjectProvider.
     */
    @SuppressWarnings("unchecked")
    private static ObjectProvider<IdentityUserAdminPort> mockProviderOf(
            IdentityUserAdminPort client) {
        ObjectProvider<IdentityUserAdminPort> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(client);
        return provider;
    }
}
