package com.agenticai.core.workflow;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.admin.identity.IdentityUserAdminPort;
import com.agenticai.core.admin.keycloak.KeycloakAdminClient;
import com.agenticai.core.audit.AuditLogService;
import com.agenticai.core.crypto.EncryptionService;
import com.agenticai.core.omnichannel.trigger.WorkflowTriggerNodeService;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.TenantLimits;
import com.agenticai.core.sharing.PermissionLevel;
import com.agenticai.core.sharing.ResourceAccessDeniedException;
import com.agenticai.core.sharing.ResourceAccessService;
import com.agenticai.core.sharing.ResourceGrant;
import com.agenticai.core.sharing.ResourceGrantRepository;
import com.agenticai.core.sharing.ResourceType;
import com.agenticai.core.tenancy.TenantResolutionService;
import com.agenticai.core.tenancy.TenantSchemaResolver;
import com.agenticai.core.workflow.temporal.TemporalWorkflowExecutionEngine;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * WorkflowService#requireAccess}/{@link WorkflowService#listAll}/ {@link WorkflowService#update}
 * against a REAL {@link ResourceAccessService} (only its own leaf dependencies mocked), same style
 * as {@link AgentResourceSharingTest} in the sibling module and {@code ResourceAccessServiceTest}
 * in core/sharing itself.
 *
 * <p>Deliberately does not exercise {@link WorkflowController} directly (no Spring context in this
 * test style) - and deliberately does NOT touch {@link WorkflowService#run} at all, since that
 * method has NO access check by design (see its own javadoc) - the RUNNER check for a human "Play"
 * click lives one layer up, in {@code WorkflowController#run}, which this plain-JUnit test style
 * can't exercise without a full MVC harness. What IS covered here - {@link
 * WorkflowService#requireAccess} itself reaching RUNNER - is the exact same tiering logic that
 * controller method delegates to.
 */
class WorkflowResourceSharingTest {

    private static final String TENANT_ID = "tenant-1";
    private static final String OWNER = "owner-sub";
    private static final String OTHER_USER = "other-sub";

    private WorkflowDefinitionRepository definitionRepository;
    private ResourceGrantRepository grantRepository;
    private WorkflowService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        definitionRepository = mock(WorkflowDefinitionRepository.class);
        grantRepository = mock(ResourceGrantRepository.class);
        ResourceAccessService resourceAccessService =
                new ResourceAccessService(
                        grantRepository,
                        mock(AuditLogService.class),
                        mockProviderOf(mock(KeycloakAdminClient.class)));

        WorkflowRunRepository runRepository = mock(WorkflowRunRepository.class);
        OrchestratorService orchestratorService = mock(OrchestratorService.class);
        WorkflowExecutionEngine executionEngine = mock(WorkflowExecutionEngine.class);
        ObjectMapper objectMapper = new ObjectMapper();
        TenantResolutionService tenantResolutionService = mock(TenantResolutionService.class);
        TenantSchemaResolver tenantSchemaResolver = mock(TenantSchemaResolver.class);
        TenantLimits tenantLimits = mock(TenantLimits.class);
        WorkflowTriggerNodeService workflowTriggerNodeService =
                mock(WorkflowTriggerNodeService.class);
        ObjectProvider<TemporalWorkflowExecutionEngine> temporalExecutionEngine =
                mock(ObjectProvider.class);
        EncryptionService encryptionService = mock(EncryptionService.class);

        service =
                WorkflowServiceFixture.of(
                        definitionRepository,
                        runRepository,
                        orchestratorService,
                        executionEngine,
                        objectMapper,
                        tenantResolutionService,
                        tenantSchemaResolver,
                        tenantLimits,
                        workflowTriggerNodeService,
                        temporalExecutionEngine,
                        encryptionService,
                        mock(WorkflowRunCancellationService.class),
                        mock(WorkflowRunPauseService.class),
                        resourceAccessService,
                        mock(WorkflowExecutionParamsInferenceService.class),
                        mock(com.agenticai.core.workflow.version.WorkflowVersionService.class));
        when(definitionRepository.save(any(WorkflowDefinition.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    @AfterEach
    void clearTenantContext() {
        TenantContext.clear();
    }

    private void loginAs(String subject, Set<String> roles) {
        TenantContext.set(TenantContext.of(TENANT_ID, subject, roles));
    }

    private static WorkflowDefinition newDraft() {
        return new WorkflowDefinition(
                "Test Workflow", "desc", "{\"nodes\":[],\"edges\":[]}", WorkflowStatus.DRAFT);
    }

    private UUID stub(WorkflowDefinition definition) {
        UUID id = UUID.randomUUID();
        when(definitionRepository.findById(id)).thenReturn(Optional.of(definition));
        return id;
    }

    private static void setId(WorkflowDefinition definition, UUID id) {
        try {
            java.lang.reflect.Field field = WorkflowDefinition.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(definition, id);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    // -- Legacy (createdBy == null): runnable by anyone, NOT readable/editable --
    //
    // These replace legacyWorkflowIsFullyAccessibleToAnyTenantMember, which
    // asserted the vulnerability rather than the requirement: it pinned
    // "a legacy workflow is fully readable AND editable by any tenant member"
    // as intended behaviour. Because tenant/V151 added created_by with no
    // backfill, every pre-retrofit row was legacy, so that test was keeping a
    // green suite over an inert permission model. Fixed 2026-07-29 alongside the
    // identical twin in AgentResourceSharingTest; the graduated rule now is that
    // RUNNER stays open (the demo sandbox, public share links, omnichannel
    // webhooks and delegation all execute under synthetic role-less subjects
    // that can never satisfy an ownership check) while VIEWER/EDITOR do not.

    @Test
    void legacyWorkflowNoLongerGrantsViewerOrEditorToAnArbitraryMember() {
        WorkflowDefinition legacy = newDraft(); // createdBy left null - never set
        UUID id = stub(legacy);

        loginAs(OTHER_USER, Set.of());
        assertThrows(
                ResourceAccessDeniedException.class,
                () -> service.requireAccess(id, PermissionLevel.VIEWER));
        assertThrows(
                ResourceAccessDeniedException.class,
                () -> service.requireAccess(id, PermissionLevel.EDITOR));
    }

    @Test
    void legacyWorkflowIsStillRunnableByAnyTenantMember() {
        WorkflowDefinition legacy = newDraft();
        UUID id = stub(legacy);

        loginAs(OTHER_USER, Set.of());
        assertDoesNotThrow(() -> service.requireAccess(id, PermissionLevel.RUNNER));
    }

    @Test
    void legacyWorkflowIsStillReachableByATenantAdmin() {
        WorkflowDefinition legacy = newDraft();
        UUID id = stub(legacy);

        loginAs(OTHER_USER, Set.of("PERM_MANAGE_WORKFLOWS_ADMIN"));
        assertDoesNotThrow(() -> service.requireAccess(id, PermissionLevel.EDITOR));
        assertDoesNotThrow(
                () ->
                        service.update(
                                id,
                                "New name",
                                "desc",
                                new com.agenticai.core.workflow.dto.WorkflowGraphDto(
                                        java.util.List.of(), java.util.List.of()),
                                WorkflowStatus.DRAFT));
    }

    // -- A brand-new private workflow is invisible to a non-owner ----------

    @Test
    void newWorkflowIs404ToADifferentTenantUserWithNoGrant() {
        WorkflowDefinition definition = newDraft();
        definition.setCreatedBy(OWNER);
        UUID id = stub(definition);

        loginAs(OTHER_USER, Set.of());
        assertThrows(
                ResourceAccessDeniedException.class,
                () -> service.requireAccess(id, PermissionLevel.VIEWER));
        assertThrows(
                ResourceAccessDeniedException.class,
                () ->
                        service.update(
                                id,
                                "x",
                                "y",
                                new com.agenticai.core.workflow.dto.WorkflowGraphDto(
                                        java.util.List.of(), java.util.List.of()),
                                WorkflowStatus.DRAFT));
    }

    @Test
    void ownerAlwaysHasFullAccess() {
        WorkflowDefinition definition = newDraft();
        definition.setCreatedBy(OWNER);
        UUID id = stub(definition);

        loginAs(OWNER, Set.of());
        assertDoesNotThrow(() -> service.requireAccess(id, PermissionLevel.VIEWER));
        assertDoesNotThrow(() -> service.delete(id));
    }

    // -- Tenant-admin override ----------------------------------------------

    @Test
    void tenantAdminAlwaysHasAccessRegardlessOfOwnershipOrGrants() {
        WorkflowDefinition definition = newDraft();
        definition.setCreatedBy(OWNER);
        UUID id = stub(definition);

        loginAs(OTHER_USER, Set.of("PERM_MANAGE_WORKFLOWS_ADMIN"));
        assertDoesNotThrow(() -> service.requireAccess(id, PermissionLevel.VIEWER));
        assertDoesNotThrow(
                () ->
                        service.update(
                                id,
                                "x",
                                "y",
                                new com.agenticai.core.workflow.dto.WorkflowGraphDto(
                                        java.util.List.of(), java.util.List.of()),
                                WorkflowStatus.DRAFT));
    }

    // -- RUNNER grant: run only, no config visibility -----------------------

    @Test
    void runnerGranteeCanBeCheckedForRunButNotReadOrUpdateConfig() {
        WorkflowDefinition definition = newDraft();
        definition.setCreatedBy(OWNER);
        UUID id = stub(definition);
        ResourceGrant runnerGrant =
                new ResourceGrant(
                        ResourceType.WORKFLOW, id, OTHER_USER, PermissionLevel.RUNNER, OWNER);
        when(grantRepository.findByResourceTypeAndResourceIdAndGranteeUserIdAndRevokedAtIsNull(
                        ResourceType.WORKFLOW, id, OTHER_USER))
                .thenReturn(Optional.of(runnerGrant));

        loginAs(OTHER_USER, Set.of());
        // RUNNER-level access (what WorkflowController#run/#cancelRun check) succeeds:
        assertDoesNotThrow(() -> service.requireAccess(id, PermissionLevel.RUNNER));
        // But VIEWER (a plain config read) and EDITOR (a config mutation) are
        // both denied - a RUNNER grant never implies them.
        assertThrows(
                ResourceAccessDeniedException.class,
                () -> service.requireAccess(id, PermissionLevel.VIEWER));
        assertThrows(
                ResourceAccessDeniedException.class,
                () ->
                        service.update(
                                id,
                                "x",
                                "y",
                                new com.agenticai.core.workflow.dto.WorkflowGraphDto(
                                        java.util.List.of(), java.util.List.of()),
                                WorkflowStatus.DRAFT));
    }

    // -- EDITOR grant: full config visibility + mutation ---------------------

    @Test
    void editorGranteeCanReadAndUpdateConfig() {
        WorkflowDefinition definition = newDraft();
        definition.setCreatedBy(OWNER);
        UUID id = stub(definition);
        ResourceGrant editorGrant =
                new ResourceGrant(
                        ResourceType.WORKFLOW, id, OTHER_USER, PermissionLevel.EDITOR, OWNER);
        when(grantRepository.findByResourceTypeAndResourceIdAndGranteeUserIdAndRevokedAtIsNull(
                        ResourceType.WORKFLOW, id, OTHER_USER))
                .thenReturn(Optional.of(editorGrant));

        loginAs(OTHER_USER, Set.of());
        assertDoesNotThrow(() -> service.requireAccess(id, PermissionLevel.VIEWER));
        assertDoesNotThrow(
                () ->
                        service.update(
                                id,
                                "x",
                                "y",
                                new com.agenticai.core.workflow.dto.WorkflowGraphDto(
                                        java.util.List.of(), java.util.List.of()),
                                WorkflowStatus.DRAFT));
    }

    @Test
    void viewerGranteeCanReadButNotUpdateConfig() {
        WorkflowDefinition definition = newDraft();
        definition.setCreatedBy(OWNER);
        UUID id = stub(definition);
        ResourceGrant viewerGrant =
                new ResourceGrant(
                        ResourceType.WORKFLOW, id, OTHER_USER, PermissionLevel.VIEWER, OWNER);
        when(grantRepository.findByResourceTypeAndResourceIdAndGranteeUserIdAndRevokedAtIsNull(
                        ResourceType.WORKFLOW, id, OTHER_USER))
                .thenReturn(Optional.of(viewerGrant));

        loginAs(OTHER_USER, Set.of());
        assertDoesNotThrow(() -> service.requireAccess(id, PermissionLevel.VIEWER));
        assertThrows(
                ResourceAccessDeniedException.class,
                () ->
                        service.update(
                                id,
                                "x",
                                "y",
                                new com.agenticai.core.workflow.dto.WorkflowGraphDto(
                                        java.util.List.of(), java.util.List.of()),
                                WorkflowStatus.DRAFT));
    }

    // -- List filtering -------------------------------------------------------

    @Test
    void listExcludesPrivateWorkflowsTheCallerHasNoAccessTo() {
        WorkflowDefinition legacyWorkflow = newDraft(); // createdBy null

        WorkflowDefinition ownedByCaller = newDraft();
        ownedByCaller.setCreatedBy(OTHER_USER);

        WorkflowDefinition grantedToCaller = newDraft();
        grantedToCaller.setCreatedBy(OWNER);
        UUID grantedId = UUID.randomUUID();
        setId(grantedToCaller, grantedId);

        WorkflowDefinition privateToSomeoneElse = newDraft();
        privateToSomeoneElse.setCreatedBy(OWNER);
        setId(privateToSomeoneElse, UUID.randomUUID());

        when(definitionRepository.findAll())
                .thenReturn(
                        List.of(
                                legacyWorkflow,
                                ownedByCaller,
                                grantedToCaller,
                                privateToSomeoneElse));

        loginAs(OTHER_USER, Set.of());
        ResourceGrant grant =
                new ResourceGrant(
                        ResourceType.WORKFLOW,
                        grantedId,
                        OTHER_USER,
                        PermissionLevel.RUNNER,
                        OWNER);
        when(grantRepository.findByGranteeUserIdAndRevokedAtIsNullOrderByGrantedAtDesc(OTHER_USER))
                .thenReturn(List.of(grant));

        List<WorkflowDefinition> visible = service.listAll();
        // Visible: legacy, owned-by-caller, granted-to-caller. Excluded: the
        // one fully-private workflow this caller has no relationship to.
        assertEquals(3, visible.size());
    }

    @Test
    void adminSeesEveryWorkflowInList() {
        WorkflowDefinition legacyWorkflow = newDraft();
        WorkflowDefinition privateWorkflow = newDraft();
        privateWorkflow.setCreatedBy(OWNER);
        when(definitionRepository.findAll()).thenReturn(List.of(legacyWorkflow, privateWorkflow));

        loginAs("admin-sub", Set.of("PERM_MANAGE_WORKFLOWS_ADMIN"));
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
