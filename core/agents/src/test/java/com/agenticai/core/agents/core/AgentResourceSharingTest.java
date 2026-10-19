package com.agenticai.core.agents.core;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.admin.identity.IdentityUserAdminPort;
import com.agenticai.core.admin.keycloak.KeycloakAdminClient;
import com.agenticai.core.agents.modality.ModelModalitiesService;
import com.agenticai.core.audit.AuditLogService;
import com.agenticai.core.providers.ProviderModelCatalogService;
import com.agenticai.core.providers.ProviderName;
import com.agenticai.core.providers.modality.TextModelProviderValidator;
import com.agenticai.core.providers.modality.TextToSpeechProviderValidator;
import com.agenticai.core.providers.modality.VideoProviderValidator;
import com.agenticai.core.providers.modality.VoiceProviderValidator;
import com.agenticai.core.security.ExperienceMode;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.TenantLimits;
import com.agenticai.core.sharing.PermissionLevel;
import com.agenticai.core.sharing.ResourceAccessDeniedException;
import com.agenticai.core.sharing.ResourceAccessService;
import com.agenticai.core.sharing.ResourceGrant;
import com.agenticai.core.sharing.ResourceGrantRepository;
import com.agenticai.core.sharing.ResourceType;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Cross-user resource sharing retrofit (Round 2) - end-to-end coverage of {@link
 * AgentService#requireAccess}/{@link AgentService#get}/{@link AgentService#listAll} against a REAL
 * {@link ResourceAccessService} (only its own {@link ResourceGrantRepository}/{@code
 * AuditLogService}/{@code KeycloakAdminClient} dependencies are mocked - same "real service under
 * test, mock the leaves" style {@code ResourceAccessServiceTest} itself uses), not a mocked one -
 * this is the actual regression-proofing the task asked for, not just a unit test of the wiring.
 *
 * <p>Deliberately does not exercise {@link AgentController}/{@code AgentChatService} directly (no
 * Spring context in this test style, same as every other test in this class's package) - the
 * RUNNER-vs-VIEWER-vs-EDITOR tiering those call sites delegate to is exactly {@link
 * AgentService#requireAccess}, covered here directly.
 */
class AgentResourceSharingTest {

    private static final String TENANT_ID = "tenant-1";
    private static final String OWNER = "owner-sub";
    private static final String OTHER_USER = "other-sub";

    private AgentRepository repository;
    private ResourceGrantRepository grantRepository;
    private AgentService service;

    @BeforeEach
    void setUp() {
        repository = mock(AgentRepository.class);
        grantRepository = mock(ResourceGrantRepository.class);
        ResourceAccessService resourceAccessService =
                new ResourceAccessService(
                        grantRepository,
                        mock(AuditLogService.class),
                        mockProviderOf(mock(KeycloakAdminClient.class)));

        service =
                AgentServiceTestFactory.create(
                        repository,
                        mock(TenantLimits.class),
                        mock(GenerationParamsInferenceService.class),
                        mock(ProviderModelCatalogService.class),
                        mock(ModelModalitiesService.class),
                        mock(PromptVersionRepository.class),
                        mock(VoiceProviderValidator.class),
                        mock(VideoProviderValidator.class),
                        mock(TextModelProviderValidator.class),
                        mock(TextToSpeechProviderValidator.class),
                        resourceAccessService,
                        mock(AgentSchemaService.class),
                        "claude-sonnet-5",
                        1024,
                        0.7,
                        1.0);
        when(repository.save(any(Agent.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @AfterEach
    void clearTenantContext() {
        TenantContext.clear();
    }

    private void loginAs(String subject, Set<String> roles) {
        TenantContext.set(TenantContext.of(TENANT_ID, subject, roles));
    }

    private static Agent newAgent() {
        return new Agent(
                "Test Agent",
                "You are a helpful assistant.",
                ProviderName.ANTHROPIC,
                "claude-sonnet-5",
                1024,
                0.7,
                1.0,
                false,
                ExperienceMode.ADVANCED);
    }

    private UUID stub(Agent agent) {
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.of(agent));
        return id;
    }

    /**
     * {@link Agent#getId()} is JPA-{@code @GeneratedValue}, with no public setter - reflection is
     * the only way to give an unpersisted test entity an id, needed here only for {@link
     * #listExcludesPrivateAgentsTheCallerHasNoAccessTo} where the list-filter predicate itself
     * reads {@code agent.getId()} (unlike every other test in this class, which only ever needs the
     * id {@link AgentRepository#findById} was stubbed against, never the entity's own).
     */
    private static void setId(Agent agent, UUID id) {
        try {
            java.lang.reflect.Field field = Agent.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(agent, id);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    // -- Legacy (createdBy == null) - narrowed 2026-07-29 -----------------
    //
    // This block used to be headed "is untouched, forever" and asserted that
    // a legacy agent was fully readable AND editable by any tenant member.
    // That was the vulnerability, not the contract: V151 added created_by
    // with no backfill, so EVERY agent in the live database was "legacy" and
    // every requireAccess call in the product was a no-op. See
    // ResourceAccessService#requireAccess's security note and
    // tenant/V155__backfill_agent_workflow_owner.sql.

    @Test
    void legacyAgentNoLongerGrantsViewerOrEditorToAnArbitraryMember() {
        Agent legacy = newAgent(); // createdBy left null - never set
        UUID id = stub(legacy);

        loginAs(OTHER_USER, Set.of());
        when(grantRepository.findByResourceTypeAndResourceIdAndGranteeUserIdAndRevokedAtIsNull(
                        any(), any(), any()))
                .thenReturn(Optional.empty());

        // get() is VIEWER, setKnowledgeBaseEnabled() is EDITOR.
        assertThrows(ResourceAccessDeniedException.class, () -> service.get(id));
        assertThrows(
                ResourceAccessDeniedException.class,
                () -> service.setKnowledgeBaseEnabled(id, true));
    }

    @Test
    void legacyAgentIsStillReachableByATenantAdmin() {
        Agent legacy = newAgent();
        UUID id = stub(legacy);

        loginAs(OTHER_USER, Set.of("PERM_MANAGE_AGENTS"));
        assertDoesNotThrow(() -> service.get(id));
        assertDoesNotThrow(() -> service.setKnowledgeBaseEnabled(id, true));
    }

    @Test
    void legacyAgentIsStillRunnableByAnyone() {
        // RUNNER stays open on purpose - the demo sandbox, public share
        // links and every omnichannel webhook run legacy agents under
        // synthetic, role-less subjects that can never satisfy an ownership
        // or grant check. Asserted directly against the sharing service
        // because AgentService's RUNNER call sites live in AgentController/
        // AgentChatService, which this test style doesn't stand up.
        loginAs(OTHER_USER, Set.of());
        assertDoesNotThrow(
                () ->
                        new ResourceAccessService(
                                        grantRepository,
                                        mock(AuditLogService.class),
                                        mockProviderOf(mock(KeycloakAdminClient.class)))
                                .requireAccess(
                                        ResourceType.AGENT,
                                        UUID.randomUUID(),
                                        null,
                                        PermissionLevel.RUNNER));
    }

    // -- A brand-new private agent is invisible to a non-owner ------------

    @Test
    void newAgentIs404ToADifferentTenantUserWithNoGrant() {
        Agent agent = newAgent();
        agent.setCreatedBy(OWNER);
        UUID id = stub(agent);

        loginAs(OTHER_USER, Set.of());
        assertThrows(ResourceAccessDeniedException.class, () -> service.get(id));
        assertThrows(
                ResourceAccessDeniedException.class,
                () -> service.setKnowledgeBaseEnabled(id, true));
    }

    @Test
    void ownerAlwaysHasFullAccess() {
        Agent agent = newAgent();
        agent.setCreatedBy(OWNER);
        UUID id = stub(agent);

        loginAs(OWNER, Set.of());
        assertDoesNotThrow(() -> service.get(id));
        assertDoesNotThrow(() -> service.setKnowledgeBaseEnabled(id, true));
        assertDoesNotThrow(() -> service.delete(id));
    }

    // -- Tenant-admin override --------------------------------------------

    @Test
    void tenantAdminAlwaysHasAccessRegardlessOfOwnershipOrGrants() {
        Agent agent = newAgent();
        agent.setCreatedBy(OWNER);
        UUID id = stub(agent);

        loginAs(OTHER_USER, Set.of("PERM_MANAGE_AGENTS"));
        assertDoesNotThrow(() -> service.get(id));
        assertDoesNotThrow(() -> service.setKnowledgeBaseEnabled(id, true));
    }

    // -- RUNNER grant: chat/run only, no config visibility -----------------

    @Test
    void runnerGranteeCanRunButNotReadOrUpdateConfig() {
        Agent agent = newAgent();
        agent.setCreatedBy(OWNER);
        UUID id = stub(agent);
        ResourceGrant runnerGrant =
                new ResourceGrant(
                        ResourceType.AGENT, id, OTHER_USER, PermissionLevel.RUNNER, OWNER);
        when(grantRepository.findByResourceTypeAndResourceIdAndGranteeUserIdAndRevokedAtIsNull(
                        ResourceType.AGENT, id, OTHER_USER))
                .thenReturn(Optional.of(runnerGrant));

        loginAs(OTHER_USER, Set.of());
        // RUNNER-level access (what AgentController#chat/ChatAttachmentService/
        // AgentChatService#delegateCall check) succeeds:
        assertDoesNotThrow(() -> service.requireAccess(id, PermissionLevel.RUNNER));
        // But VIEWER (a plain config read, e.g. AgentController#get) and EDITOR
        // (a config mutation) are both denied - a RUNNER grant never implies them.
        assertThrows(ResourceAccessDeniedException.class, () -> service.get(id));
        assertThrows(
                ResourceAccessDeniedException.class,
                () -> service.setKnowledgeBaseEnabled(id, true));
    }

    // -- Collaborators: an editor may only add agents they can run ----------

    @Test
    void addingACollaboratorTheEditorCannotRunIsDenied() {
        Agent mine = newAgent();
        mine.setCreatedBy(OTHER_USER);
        UUID mineId = stub(mine);
        Agent privateAgent = newAgent();
        privateAgent.setCreatedBy(OWNER);
        UUID privateId = stub(privateAgent);

        loginAs(OTHER_USER, Set.of());
        assertThrows(
                ResourceAccessDeniedException.class,
                () -> service.setCollaboratorAgentIds(mineId, Set.of(privateId)));
    }

    @Test
    void addingACollaboratorTheEditorCanRunIsAllowed() {
        Agent mine = newAgent();
        mine.setCreatedBy(OTHER_USER);
        UUID mineId = stub(mine);
        Agent shared = newAgent();
        shared.setCreatedBy(OWNER);
        UUID sharedId = stub(shared);
        ResourceGrant runnerGrant =
                new ResourceGrant(
                        ResourceType.AGENT, sharedId, OTHER_USER, PermissionLevel.RUNNER, OWNER);
        when(grantRepository.findByResourceTypeAndResourceIdAndGranteeUserIdAndRevokedAtIsNull(
                        ResourceType.AGENT, sharedId, OTHER_USER))
                .thenReturn(Optional.of(runnerGrant));

        loginAs(OTHER_USER, Set.of());
        assertDoesNotThrow(() -> service.setCollaboratorAgentIds(mineId, Set.of(sharedId)));
    }

    // -- EDITOR grant: full config visibility + mutation --------------------

    @Test
    void editorGranteeCanReadAndUpdateConfig() {
        Agent agent = newAgent();
        agent.setCreatedBy(OWNER);
        UUID id = stub(agent);
        ResourceGrant editorGrant =
                new ResourceGrant(
                        ResourceType.AGENT, id, OTHER_USER, PermissionLevel.EDITOR, OWNER);
        when(grantRepository.findByResourceTypeAndResourceIdAndGranteeUserIdAndRevokedAtIsNull(
                        ResourceType.AGENT, id, OTHER_USER))
                .thenReturn(Optional.of(editorGrant));

        loginAs(OTHER_USER, Set.of());
        assertDoesNotThrow(() -> service.get(id));
        assertDoesNotThrow(() -> service.setKnowledgeBaseEnabled(id, true));
        // EDITOR does NOT imply owner-only operations like delete... actually
        // delete is also EDITOR-gated in this retrofit, so it succeeds too:
        assertDoesNotThrow(() -> service.delete(id));
    }

    @Test
    void viewerGranteeCanReadButNotUpdateConfig() {
        Agent agent = newAgent();
        agent.setCreatedBy(OWNER);
        UUID id = stub(agent);
        ResourceGrant viewerGrant =
                new ResourceGrant(
                        ResourceType.AGENT, id, OTHER_USER, PermissionLevel.VIEWER, OWNER);
        when(grantRepository.findByResourceTypeAndResourceIdAndGranteeUserIdAndRevokedAtIsNull(
                        ResourceType.AGENT, id, OTHER_USER))
                .thenReturn(Optional.of(viewerGrant));

        loginAs(OTHER_USER, Set.of());
        assertDoesNotThrow(() -> service.get(id));
        assertThrows(
                ResourceAccessDeniedException.class,
                () -> service.setKnowledgeBaseEnabled(id, true));
    }

    // -- List filtering ------------------------------------------------------

    @Test
    void listExcludesPrivateAgentsTheCallerHasNoAccessTo() {
        Agent legacyAgent = newAgent(); // createdBy null

        Agent ownedByCaller = newAgent();
        ownedByCaller.setCreatedBy(OTHER_USER);

        Agent grantedToCaller = newAgent();
        grantedToCaller.setCreatedBy(OWNER);
        UUID grantedId = UUID.randomUUID();
        setId(grantedToCaller, grantedId);

        Agent privateToSomeoneElse = newAgent();
        privateToSomeoneElse.setCreatedBy(OWNER);
        setId(privateToSomeoneElse, UUID.randomUUID());

        when(repository.findAll())
                .thenReturn(
                        List.of(legacyAgent, ownedByCaller, grantedToCaller, privateToSomeoneElse));

        loginAs(OTHER_USER, Set.of());
        ResourceGrant grant =
                new ResourceGrant(
                        ResourceType.AGENT, grantedId, OTHER_USER, PermissionLevel.RUNNER, OWNER);
        when(grantRepository.findByGranteeUserIdAndRevokedAtIsNullOrderByGrantedAtDesc(OTHER_USER))
                .thenReturn(List.of(grant));

        List<com.agenticai.core.agents.dto.AgentResponse> visible = service.listAll();
        // Visible: legacy (createdBy null), owned-by-caller, granted-to-caller
        // (matched by id via the RUNNER grant above - list inclusion doesn't
        // require VIEWER+, see listAll's own javadoc). Excluded: the one
        // fully-private agent this caller has no relationship to at all.
        assertEquals(3, visible.size());
    }

    @Test
    void adminSeesEveryAgentInList() {
        Agent legacyAgent = newAgent();
        Agent privateAgent = newAgent();
        privateAgent.setCreatedBy(OWNER);
        when(repository.findAll()).thenReturn(List.of(legacyAgent, privateAgent));

        loginAs("admin-sub", Set.of("PERM_MANAGE_AGENTS"));
        assertEquals(2, service.listAll().size());
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
}
