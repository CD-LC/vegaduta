package com.agenticai.core.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;

import com.agenticai.core.crypto.EncryptionService;
import com.agenticai.core.omnichannel.trigger.WorkflowTriggerChannel;
import com.agenticai.core.omnichannel.trigger.WorkflowTriggerNode;
import com.agenticai.core.omnichannel.trigger.WorkflowTriggerNodeService;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.TenantLimitExceededException;
import com.agenticai.core.security.TenantLimits;
import com.agenticai.core.security.TenantPlan;
import com.agenticai.core.sharing.ResourceAccessService;
import com.agenticai.core.tenancy.ExternalAccountProvider;
import com.agenticai.core.tenancy.TenantResolutionService;
import com.agenticai.core.tenancy.TenantSchemaResolver;
import com.agenticai.core.workflow.dto.WorkflowGraphDto;
import com.agenticai.core.workflow.dto.WorkflowResponse;
import com.agenticai.core.workflow.dto.WorkflowTriggerChannelResponse;
import com.agenticai.core.workflow.dto.WorkflowWebhookResponse;
import com.agenticai.core.workflow.temporal.TemporalWorkflowExecutionEngine;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;

/**
 * {@link WorkflowService} had zero test coverage before this - including its {@code
 * assertEditable}/{@link com.agenticai.core.security.EditLockPolicy} wiring, the FREE-tier
 * edit-lock enforcement VEGADUTA-MASTER-ROADMAP-2026-07-19.md §4.1 asked for and which turned out
 * to already be fully built here (6 mutating methods, all gated - see this session's
 * AGENT-COORDINATION-2026-07-18.md Status Log entry). Scoped to the edit-lock behavior specifically
 * (via {@link #update} and {@link #enableWebhook}/{@link #disableWebhook} as representative gated
 * methods), not full class coverage - that's a separate, larger effort.
 */
class WorkflowServiceTest {

    private WorkflowDefinitionRepository definitionRepository;
    private TenantResolutionService tenantResolutionService;
    private TenantSchemaResolver tenantSchemaResolver;
    private TenantLimits tenantLimits;
    private EncryptionService encryptionService;
    private WorkflowTriggerNodeService workflowTriggerNodeService;
    private WorkflowService service;

    // Per-node workflow triggers - every webhook test now needs a real
    // Trigger node id to enable/disable/read the webhook for (WorkflowService
    // validates nodeId against the workflow's own graph before touching
    // WorkflowTriggerNodeService - see WorkflowService#requireTriggerNode).
    private static final String TRIGGER_NODE_ID = "t1";

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        definitionRepository = mock(WorkflowDefinitionRepository.class);
        WorkflowRunRepository runRepository = mock(WorkflowRunRepository.class);
        OrchestratorService orchestratorService = mock(OrchestratorService.class);
        WorkflowExecutionEngine executionEngine = mock(WorkflowExecutionEngine.class);
        ObjectMapper objectMapper = new ObjectMapper();
        tenantResolutionService = mock(TenantResolutionService.class);
        tenantSchemaResolver = mock(TenantSchemaResolver.class);
        tenantLimits = mock(TenantLimits.class);
        workflowTriggerNodeService = mock(WorkflowTriggerNodeService.class);
        ObjectProvider<TemporalWorkflowExecutionEngine> temporalExecutionEngine =
                mock(ObjectProvider.class);
        encryptionService = mock(EncryptionService.class);

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
                        // Cross-user resource sharing retrofit (Round 2) - a bare mock never
                        // throws unless stubbed, and every WorkflowDefinition built in this
                        // test class has createdBy == null (legacy/open) regardless, so this
                        // is a no-op for every existing test here.
                        mock(ResourceAccessService.class),
                        mock(WorkflowExecutionParamsInferenceService.class),
                        mock(com.agenticai.core.workflow.version.WorkflowVersionService.class));
        // Trigger config moved to WorkflowTriggerConfigService (file-size
        // ratchet split); WorkflowService keeps delegates that need this set.
        service.setTriggerConfigService(
                new WorkflowTriggerConfigService(
                        service,
                        workflowTriggerNodeService,
                        encryptionService,
                        tenantResolutionService,
                        tenantSchemaResolver,
                        mock(com.agenticai.core.workflow.poll.WorkflowPollTriggerService.class)));
    }

    @AfterEach
    void clearTenantContext() {
        TenantContext.clear();
    }

    private static WorkflowDefinition draftDefinition() {
        return new WorkflowDefinition(
                "Test Workflow", "desc", "{\"nodes\":[],\"edges\":[]}", WorkflowStatus.DRAFT);
    }

    /**
     * A single Trigger node ({@link #TRIGGER_NODE_ID}), ACTIVE - the shape every webhook test below
     * needs.
     */
    private static WorkflowDefinition activeDefinitionWithTriggerNode() {
        String graphJson =
                "{\"nodes\":[{\"id\":\""
                        + TRIGGER_NODE_ID
                        + "\",\"type\":\"trigger\",\"position\":{},\"data\":{\"config\":{\"channel\":\"WEBHOOK\"}}}],\"edges\":[]}";
        return new WorkflowDefinition("Test Workflow", "desc", graphJson, WorkflowStatus.ACTIVE);
    }

    private UUID stub(WorkflowDefinition definition) {
        UUID id = UUID.randomUUID();
        Mockito.when(definitionRepository.findById(id)).thenReturn(Optional.of(definition));
        return id;
    }

    private void setFreeContext() {
        TenantContext.set(TenantContext.of("acme", "user-1", Set.of()));
        Mockito.when(tenantLimits.plan("acme")).thenReturn(TenantPlan.FREE);
    }

    private void setPremiumContext() {
        TenantContext.set(TenantContext.of("acme", "user-1", Set.of()));
        Mockito.when(tenantLimits.plan("acme")).thenReturn(TenantPlan.PREMIUM);
        Mockito.when(tenantLimits.automationAllowed("acme")).thenReturn(true);
    }

    @Test
    void updateRejectsAFreeTenantsLockedWorkflow() {
        setFreeContext();
        // Created "long ago" - past the 24h grace window.
        WorkflowDefinition definition =
                new WorkflowDefinition(
                        "Old", "desc", "{\"nodes\":[],\"edges\":[]}", WorkflowStatus.DRAFT);
        setCreatedAtDaysAgo(definition, 2);
        UUID id = stub(definition);

        assertThrows(
                TenantLimitExceededException.class,
                () ->
                        service.update(
                                id,
                                "New name",
                                "desc",
                                new WorkflowGraphDto(List.of(), List.of()),
                                WorkflowStatus.DRAFT));
    }

    @Test
    void updateAllowsAFreeTenantsWorkflowStillWithinTheGraceWindow() {
        setFreeContext();
        WorkflowDefinition definition = draftDefinition(); // createdAt = now
        UUID id = stub(definition);

        WorkflowDefinition updated =
                service.update(
                        id,
                        "New name",
                        "desc",
                        new WorkflowGraphDto(List.of(), List.of()),
                        WorkflowStatus.DRAFT);

        assertEquals("New name", updated.getName());
    }

    @Test
    void updateAllowsAPremiumTenantsWorkflowRegardlessOfAge() {
        setPremiumContext();
        WorkflowDefinition definition = draftDefinition();
        setCreatedAtDaysAgo(definition, 30);
        UUID id = stub(definition);

        WorkflowDefinition updated =
                service.update(
                        id,
                        "New name",
                        "desc",
                        new WorkflowGraphDto(List.of(), List.of()),
                        WorkflowStatus.DRAFT);

        assertEquals("New name", updated.getName());
    }

    @Test
    void enableWebhookRejectsALockedFreeTenantWorkflowBeforeEvenCheckingStatus() {
        setFreeContext();
        WorkflowDefinition definition = activeDefinitionWithTriggerNode();
        setCreatedAtDaysAgo(definition, 2);
        UUID id = stub(definition);

        assertThrows(
                TenantLimitExceededException.class,
                () -> service.enableWebhook(id, TRIGGER_NODE_ID));
    }

    @Test
    void enableWebhookSucceedsForAnUnlockedActiveWorkflow() {
        setPremiumContext();
        Mockito.when(tenantSchemaResolver.resolveCurrentTenantIdentifier()).thenReturn("acme");
        WorkflowDefinition definition = activeDefinitionWithTriggerNode();
        UUID id = stub(definition);

        WorkflowWebhookResponse response = service.enableWebhook(id, TRIGGER_NODE_ID);

        assertTrue(response.enabled());
    }

    @Test
    void enableWebhookRejectsANodeIdThatIsNotATriggerNodeInTheGraph() {
        setPremiumContext();
        WorkflowDefinition definition = activeDefinitionWithTriggerNode();
        UUID id = stub(definition);

        assertThrows(
                WorkflowValidationException.class,
                () -> service.enableWebhook(id, "not-a-real-node"));
    }

    @Test
    void enableWebhookEncryptsTheTokenAtRestButReturnsThePlaintextInTheResponse() {
        setPremiumContext();
        Mockito.when(tenantSchemaResolver.resolveCurrentTenantIdentifier()).thenReturn("acme");
        Mockito.when(encryptionService.encrypt(org.mockito.ArgumentMatchers.anyString()))
                .thenAnswer(invocation -> "ENC(" + invocation.getArgument(0) + ")");
        WorkflowDefinition definition = activeDefinitionWithTriggerNode();
        UUID id = stub(definition);

        WorkflowWebhookResponse response = service.enableWebhook(id, TRIGGER_NODE_ID);

        // The response carries the raw, usable token (what a tenant pastes into a third-party
        // tool) - the WorkflowTriggerNode row itself only ever holds the ciphertext (#815
        // Governance follow-up), pushed through WorkflowTriggerNodeService#enableWebhook.
        Mockito.verify(workflowTriggerNodeService)
                .enableWebhook(
                        id,
                        TRIGGER_NODE_ID,
                        "ENC(" + response.token() + ")",
                        com.agenticai.core.workflow.WebhookTokenHasher.sha256Hex(response.token()));
        // The cross-tenant link table stores only the SHA-256 hash of the token, never the
        // plaintext (L4 hardening) - the inbound resolve path hashes the token symmetrically.
        Mockito.verify(tenantResolutionService)
                .link(
                        ExternalAccountProvider.WORKFLOW_WEBHOOK,
                        com.agenticai.core.workflow.WebhookTokenHasher.sha256Hex(response.token()),
                        "acme");
    }

    @Test
    void enableWebhookReusesTheExistingPlaintextTokenAcrossReEnables() {
        setPremiumContext();
        Mockito.when(tenantSchemaResolver.resolveCurrentTenantIdentifier()).thenReturn("acme");
        Mockito.when(encryptionService.decrypt("old-ciphertext")).thenReturn("existing-token");
        Mockito.when(encryptionService.encrypt(org.mockito.ArgumentMatchers.anyString()))
                .thenAnswer(invocation -> "ENC(" + invocation.getArgument(0) + ")");
        WorkflowDefinition definition = activeDefinitionWithTriggerNode();
        UUID id = stub(definition);
        WorkflowTriggerNode existingRow = mock(WorkflowTriggerNode.class);
        Mockito.when(existingRow.getWebhookTokenEncrypted()).thenReturn("old-ciphertext");
        Mockito.when(workflowTriggerNodeService.findByNodeId(TRIGGER_NODE_ID))
                .thenReturn(Optional.of(existingRow));

        WorkflowWebhookResponse response = service.enableWebhook(id, TRIGGER_NODE_ID);

        // Re-enabling must hand back the SAME plaintext token a tenant may have already pasted
        // into a third-party tool, not silently mint a new one.
        assertEquals("existing-token", response.token());
    }

    @Test
    void getWebhookDecryptsTheStoredCiphertextBackToThePlaintextToken() {
        setPremiumContext();
        Mockito.when(encryptionService.decrypt("ciphertext-abc")).thenReturn("plaintext-token-123");
        WorkflowDefinition definition = activeDefinitionWithTriggerNode();
        UUID id = stub(definition);
        WorkflowTriggerNode existingRow = mock(WorkflowTriggerNode.class);
        Mockito.when(existingRow.getWebhookTokenEncrypted()).thenReturn("ciphertext-abc");
        Mockito.when(existingRow.isWebhookEnabled()).thenReturn(true);
        Mockito.when(workflowTriggerNodeService.findByNodeId(TRIGGER_NODE_ID))
                .thenReturn(Optional.of(existingRow));

        WorkflowWebhookResponse response = service.getWebhook(id, TRIGGER_NODE_ID);

        assertEquals("plaintext-token-123", response.token());
    }

    @Test
    void getWebhookReturnsANullTokenWhenTheWebhookWasNeverEnabled() {
        setPremiumContext();
        WorkflowDefinition definition = activeDefinitionWithTriggerNode();
        UUID id = stub(definition);
        Mockito.when(workflowTriggerNodeService.findByNodeId(TRIGGER_NODE_ID))
                .thenReturn(Optional.empty());

        WorkflowWebhookResponse response = service.getWebhook(id, TRIGGER_NODE_ID);

        assertEquals(null, response.token());
        Mockito.verify(encryptionService, never())
                .decrypt(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void disableWebhookRejectsALockedFreeTenantWorkflow() {
        setFreeContext();
        WorkflowDefinition definition = activeDefinitionWithTriggerNode();
        setCreatedAtDaysAgo(definition, 2);
        UUID id = stub(definition);

        assertThrows(
                TenantLimitExceededException.class,
                () -> service.disableWebhook(id, TRIGGER_NODE_ID));
    }

    @Test
    void toResponseReflectsTheEditableFlagForALockedFreeTenantWorkflow() {
        setFreeContext();
        WorkflowDefinition definition =
                new WorkflowDefinition(
                        "Old", "desc", "{\"nodes\":[],\"edges\":[]}", WorkflowStatus.DRAFT);
        setCreatedAtDaysAgo(definition, 2);

        WorkflowResponse response = service.toResponse(definition);

        assertEquals(false, response.editable());
    }

    @Test
    void toResponseReflectsTheEditableFlagForAnUnlockedWorkflow() {
        setPremiumContext();
        WorkflowDefinition definition = draftDefinition();

        WorkflowResponse response = service.toResponse(definition);

        assertEquals(true, response.editable());
    }

    @Test
    void noTenantContextIsNeverLocked() {
        // No TenantContext.set(...) - matches assertEditable's own
        // documented "no HTTP request context -> unlocked" guard.
        WorkflowDefinition definition = draftDefinition();
        UUID id = stub(definition);

        WorkflowDefinition updated =
                service.update(
                        id,
                        "New name",
                        "desc",
                        new WorkflowGraphDto(List.of(), List.of()),
                        WorkflowStatus.DRAFT);

        assertEquals("New name", updated.getName());
        Mockito.verify(tenantLimits, never()).plan(any());
    }

    /**
     * Direct feedback (2026-08-04): "values doesn't get updated, whenever i click on same trigger
     * node it says webhook - it should reflect what[ever] trigger node is configured." Root cause:
     * {@code triggerChannelResponse} reads the node's channel from the graph's own {@code
     * data.config.channel} (its documented source of truth), but {@code setTriggerChannel} only
     * used to update {@code WorkflowTriggerNodeService}'s separate table, never the graph itself -
     * so a GET right after a successful PUT still saw the graph's stale/absent channel and fell
     * back to WEBHOOK. This locks in that a GET immediately after a non-WEBHOOK setTriggerChannel
     * call reflects the new channel, with no separate whole-workflow save in between.
     */
    @Test
    void setTriggerChannelPersistsIntoTheGraphSoAnImmediateGetReflectsIt() {
        setPremiumContext();
        WorkflowDefinition definition = activeDefinitionWithTriggerNode(); // starts as WEBHOOK
        UUID id = stub(definition);

        service.setTriggerChannel(id, TRIGGER_NODE_ID, WorkflowTriggerChannel.WHATSAPP, null);
        WorkflowTriggerChannelResponse response = service.getTriggerChannel(id, TRIGGER_NODE_ID);

        assertEquals(WorkflowTriggerChannel.WHATSAPP, response.channel());
        assertTrue(definition.getGraphJson().contains("\"channel\":\"WHATSAPP\""));
    }

    // --- schedule trigger ("long running" / recurring runs) ----------------------
    // WorkflowService#enableSchedule/findDueScheduledWorkflowIds/runScheduledWorkflow
    // had zero test coverage before this - these are the methods WorkflowScheduler's
    // real 60-second tick (core/workflow/.../WorkflowScheduler.java) calls, so this
    // proves the recurring-run contract without needing to wait on a real tick or
    // stand up a database, same no-DB posture as the rest of this test class.

    @Test
    void enableScheduleThenFindDueScheduledWorkflowIdsReturnsItOnceDue()
            throws ReflectiveOperationException {
        setPremiumContext();
        WorkflowDefinition definition = activeDefinitionWithTriggerNode();
        java.lang.reflect.Field idField = WorkflowDefinition.class.getDeclaredField("id");
        idField.setAccessible(true);
        UUID definitionId = UUID.randomUUID();
        idField.set(definition, definitionId);
        UUID id = stub(definition);

        service.enableSchedule(
                id, new com.agenticai.core.workflow.dto.WorkflowScheduleRequest("*/1 * * * *"));

        assertTrue(definition.isScheduleEnabled());
        java.time.Instant wellPastNextRun = definition.getNextRunAt().plusSeconds(120);
        Mockito.when(
                        definitionRepository
                                .findByScheduleEnabledTrueAndStatusAndNextRunAtLessThanEqual(
                                        WorkflowStatus.ACTIVE, wellPastNextRun))
                .thenReturn(List.of(definition));

        List<UUID> due = service.findDueScheduledWorkflowIds(wellPastNextRun);

        assertTrue(due.contains(definitionId));
    }

    /**
     * {@code runScheduledWorkflow}'s own javadoc: "advances past this occurrence... regardless of
     * whether the run succeeded" - a schedule attached to a workflow whose run fails must not fire
     * on every subsequent scheduler tick forever. {@code executionEngine} here is a bare, unstubbed
     * mock, so the run itself does nothing useful - exactly the failure case this behavior exists
     * for.
     */
    @Test
    void runScheduledWorkflowAdvancesTheScheduleRegardlessOfRunOutcome() {
        setPremiumContext();
        WorkflowDefinition definition = activeDefinitionWithTriggerNode();
        UUID id = stub(definition);
        service.enableSchedule(
                id, new com.agenticai.core.workflow.dto.WorkflowScheduleRequest("*/1 * * * *"));
        assertTrue(definition.isScheduleEnabled());
        java.time.Instant beforeRun = java.time.Instant.now();

        service.runScheduledWorkflow(id);

        assertTrue(
                definition.isScheduleEnabled(),
                "a failed scheduled run must not disable the schedule");
        assertTrue(
                !definition.getNextRunAt().isBefore(beforeRun),
                "nextRunAt must have advanced forward from the attempt");
    }

    // SE-13: a schedule tighter than app.workflow.schedule.min-interval-seconds (default 60) is a
    // 400.
    @Test
    void aSubMinuteScheduleIsRejectedWithAValidationError() {
        setPremiumContext();
        WorkflowDefinition definition = activeDefinitionWithTriggerNode();
        UUID id = stub(definition);

        for (String cron : List.of("* * * * * *", "*/30 * * * * *", "0,10 * * * * *")) {
            WorkflowValidationException e =
                    org.junit.jupiter.api.Assertions.assertThrows(
                            WorkflowValidationException.class,
                            () ->
                                    service.enableSchedule(
                                            id,
                                            new com.agenticai.core.workflow.dto
                                                    .WorkflowScheduleRequest(cron)),
                            cron);
            assertTrue(e.getMessage().contains("minimum interval"), e.getMessage());
        }
        assertTrue(!definition.isScheduleEnabled(), "a rejected schedule must not be armed");
    }

    @Test
    void aScheduleAtOrAboveTheMinimumIntervalIsStillAccepted() {
        setPremiumContext();
        WorkflowDefinition definition = activeDefinitionWithTriggerNode();
        UUID id = stub(definition);

        service.enableSchedule(
                id, new com.agenticai.core.workflow.dto.WorkflowScheduleRequest("0 * * * * *"));
        assertTrue(definition.isScheduleEnabled());
        service.enableSchedule(
                id, new com.agenticai.core.workflow.dto.WorkflowScheduleRequest("0 9 * * 1-5"));
        assertTrue(definition.isScheduleEnabled());
    }

    // SE-55: execution-limits fields are optional; null leaves the stored value alone.
    @Test
    void partialExecutionLimitsLeaveTheOtherSettingsUnchanged() {
        WorkflowDefinition definition = activeDefinitionWithTriggerNode();
        definition.setExecutionLimits(40, 90);
        definition.setMaxRetryAttempts(2);
        UUID id = stub(definition);

        service.setExecutionLimits(id, 7, null, null);

        assertEquals(7, definition.getMaxStepsPerRun());
        assertEquals(90, definition.getStepTimeoutSeconds());
        assertEquals(2, definition.getMaxRetryAttempts());

        service.setExecutionLimits(id, null, null, 0);
        assertEquals(7, definition.getMaxStepsPerRun());
        assertEquals(0, definition.getMaxRetryAttempts());
    }

    @Test
    void anEmptyExecutionLimitsPatchIsAValidationError() {
        UUID id = stub(activeDefinitionWithTriggerNode());

        org.junit.jupiter.api.Assertions.assertThrows(
                WorkflowValidationException.class,
                () -> service.setExecutionLimits(id, null, null, null));
    }

    private static void setCreatedAtDaysAgo(WorkflowDefinition definition, int days) {
        try {
            java.lang.reflect.Field field = WorkflowDefinition.class.getDeclaredField("createdAt");
            field.setAccessible(true);
            field.set(
                    definition,
                    java.time.Instant.now().minus(days, java.time.temporal.ChronoUnit.DAYS));
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    // --- unattended automation is a Premium+ entitlement (V470 automation_allowed)
    // -----------------------------

    @Test
    void aFreeTenantCannotTurnOnAWorkflowSchedule() {
        setFreeContext();
        UUID id = stub(activeDefinitionWithTriggerNode());

        org.junit.jupiter.api.Assertions.assertThrows(
                com.agenticai.core.security.TenantLimitExceededException.class,
                () ->
                        service.enableSchedule(
                                id,
                                new com.agenticai.core.workflow.dto.WorkflowScheduleRequest(
                                        "*/5 * * * *")));
    }

    @Test
    void aFreeTenantCannotEnableAWebhookTrigger() {
        setFreeContext();
        UUID id = stub(activeDefinitionWithTriggerNode());

        org.junit.jupiter.api.Assertions.assertThrows(
                com.agenticai.core.security.TenantLimitExceededException.class,
                () -> service.enableWebhook(id, TRIGGER_NODE_ID));
    }

    @Test
    void aFreeTenantCannotBindAnInboundChannelTrigger() {
        setFreeContext();
        UUID id = stub(activeDefinitionWithTriggerNode());

        org.junit.jupiter.api.Assertions.assertThrows(
                com.agenticai.core.security.TenantLimitExceededException.class,
                () ->
                        service.setTriggerChannel(
                                id,
                                TRIGGER_NODE_ID,
                                com.agenticai.core.omnichannel.trigger.WorkflowTriggerChannel
                                        .WEBHOOK,
                                null));
    }
}
