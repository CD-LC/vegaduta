package com.agenticai.core.workflow.share;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.admin.identity.IdentityUserAdminPort;
import com.agenticai.core.admin.keycloak.KeycloakAdminClient;
import com.agenticai.core.audit.AuditLogService;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.sharing.ResourceAccessService;
import com.agenticai.core.sharing.ResourceGrantRepository;
import com.agenticai.core.tenancy.TenantResolutionService;
import com.agenticai.core.workflow.WorkflowDefinition;
import com.agenticai.core.workflow.WorkflowDefinitionRepository;
import com.agenticai.core.workflow.WorkflowStatus;
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
 * The workflow counterpart of {@code AgentShareOwnerControllerTest} - real {@link
 * WorkflowShareService} under the controller (only the repositories and infrastructure leaves are
 * mocked), focused on the re-share-after- revoke defect confirmed live in prod: {@code POST
 * /api/workflows/{id}/share} returned HTTP 500 for a workflow that had been shared, revoked, then
 * re-shared, because Hibernate's ActionQueue executes INSERTs before DELETEs at flush time - the
 * queued {@code repository.delete(existing)} never cleared {@code uq_workflow_share_workflow_id}
 * before {@code saveAndFlush} inserted its replacement, the constraint violation aborted the
 * transaction, and the catch-path {@code findByWorkflowId} recovery failed with it. {@link
 * WorkflowShareService#createOrGet} now flushes the delete explicitly; the test below pins both the
 * HTTP outcome and the repository ordering that makes it work.
 */
class WorkflowShareOwnerControllerTest {

    private static final String TENANT_ID = "tenant-1";
    private static final String OWNER = "owner-sub";

    private WorkflowShareRepository shareRepository;
    private WorkflowDefinitionRepository workflowDefinitionRepository;
    private TenantResolutionService tenantResolutionService;
    private WorkflowShareOwnerController controller;

    @BeforeEach
    void setUp() {
        shareRepository = mock(WorkflowShareRepository.class);
        workflowDefinitionRepository = mock(WorkflowDefinitionRepository.class);
        tenantResolutionService = mock(TenantResolutionService.class);
        ResourceAccessService resourceAccessService =
                new ResourceAccessService(
                        mock(ResourceGrantRepository.class),
                        mock(AuditLogService.class),
                        mockProviderOf(mock(KeycloakAdminClient.class)));
        WorkflowShareService shareService =
                new WorkflowShareService(
                        shareRepository,
                        workflowDefinitionRepository,
                        resourceAccessService,
                        tenantResolutionService,
                        mock(StringRedisTemplate.class),
                        "https://app.staging.example.com",
                        3,
                        12,
                        20);
        controller = new WorkflowShareOwnerController(shareService, resourceAccessService);
        when(shareRepository.saveAndFlush(any(WorkflowShare.class)))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    @AfterEach
    void clearTenantContext() {
        TenantContext.clear();
    }

    @Test
    void ownerReShareAfterRevokeReturns201WithAFreshShareId() {
        UUID workflowId = stubWorkflowOwnedBy(OWNER);
        loginAs(OWNER, Set.of());
        WorkflowShare revoked = new WorkflowShare(workflowId, "dead-share-id");
        revoked.revoke();

        // State-based stubbing: createOrGet's initial lookup sees the
        // revoked row; the controller's post-create statusFor must see the
        // replacement row saveAndFlush received.
        AtomicReference<WorkflowShare> inserted = new AtomicReference<>();
        when(shareRepository.saveAndFlush(any(WorkflowShare.class)))
                .thenAnswer(
                        invocation -> {
                            WorkflowShare share = invocation.getArgument(0);
                            inserted.set(share);
                            return share;
                        });
        when(shareRepository.findByWorkflowId(workflowId))
                .thenAnswer(
                        invocation ->
                                inserted.get() == null
                                        ? Optional.of(revoked)
                                        : Optional.of(inserted.get()));

        var response = controller.create(workflowId);

        assertEquals(201, response.getStatusCode().value());
        assertNotNull(response.getBody());
        assertNotEquals("dead-share-id", response.getBody().shareId());

        // The ordering the fix exists to guarantee: the DELETE must reach
        // the database (flush) before the INSERT runs.
        InOrder order = inOrder(shareRepository);
        order.verify(shareRepository).delete(revoked);
        order.verify(shareRepository).flush();
        order.verify(shareRepository).saveAndFlush(any(WorkflowShare.class));
    }

    // ------------------------------------------------------------------

    private void loginAs(String subject, Set<String> roles) {
        TenantContext.set(TenantContext.of(TENANT_ID, subject, roles));
    }

    private UUID stubWorkflowOwnedBy(String createdBy) {
        WorkflowDefinition definition =
                new WorkflowDefinition(
                        "Test Workflow",
                        "desc",
                        "{\"nodes\":[],\"edges\":[]}",
                        WorkflowStatus.DRAFT);
        definition.setCreatedBy(createdBy);
        UUID id = UUID.randomUUID();
        when(workflowDefinitionRepository.findById(id)).thenReturn(Optional.of(definition));
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
