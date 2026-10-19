package com.agenticai.core.workflow;

import com.agenticai.core.crypto.EncryptionService;
import com.agenticai.core.omnichannel.trigger.WorkflowTriggerNodeService;
import com.agenticai.core.security.TenantLimits;
import com.agenticai.core.sharing.ResourceAccessService;
import com.agenticai.core.tenancy.TenantResolutionService;
import com.agenticai.core.tenancy.TenantSchemaResolver;
import com.agenticai.core.workflow.temporal.TemporalWorkflowExecutionEngine;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Hand-wires a {@link WorkflowService} facade and its capability services from the collaborators
 * the pre-split 16-argument constructor took, so the characterisation tests keep constructing the
 * service exactly as before. Some arguments (tenant resolution, schema resolver, trigger-node
 * service, encryption) were only used by the trigger-config service and are accepted for source
 * compatibility.
 */
final class WorkflowServiceFixture {

    private WorkflowServiceFixture() {}

    @SuppressWarnings("unused")
    static WorkflowService of(
            WorkflowDefinitionRepository definitionRepository,
            WorkflowRunRepository runRepository,
            OrchestratorService orchestratorService,
            WorkflowExecutionEngine executionEngine,
            ObjectMapper objectMapper,
            TenantResolutionService tenantResolutionService,
            TenantSchemaResolver tenantSchemaResolver,
            TenantLimits tenantLimits,
            WorkflowTriggerNodeService workflowTriggerNodeService,
            ObjectProvider<TemporalWorkflowExecutionEngine> temporalExecutionEngine,
            EncryptionService encryptionService,
            WorkflowRunCancellationService cancellationService,
            WorkflowRunPauseService pauseService,
            ResourceAccessService resourceAccessService,
            WorkflowExecutionParamsInferenceService executionParamsInferenceService,
            com.agenticai.core.workflow.version.WorkflowVersionService versionService) {
        WorkflowAccessGuard guard =
                new WorkflowAccessGuard(definitionRepository, tenantLimits, resourceAccessService);
        WorkflowGraphCodec codec = new WorkflowGraphCodec(objectMapper);
        WorkflowCrudService crud =
                new WorkflowCrudService(
                        definitionRepository,
                        orchestratorService,
                        tenantLimits,
                        resourceAccessService,
                        executionParamsInferenceService,
                        versionService,
                        guard,
                        codec,
                        objectMapper);
        WorkflowVersionRestoreService versions =
                new WorkflowVersionRestoreService(
                        orchestratorService, objectMapper, versionService, guard, codec, crud);
        WorkflowRunService runs =
                new WorkflowRunService(
                        runRepository,
                        executionEngine,
                        objectMapper,
                        temporalExecutionEngine,
                        cancellationService,
                        pauseService,
                        guard,
                        codec,
                        versions);
        WorkflowTriggerService triggers =
                new WorkflowTriggerService(definitionRepository, guard, runs);
        return new WorkflowService(crud, runs, triggers, versions, guard, codec);
    }
}
