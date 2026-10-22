package com.agenticai.core.workflow;

import com.agenticai.core.agents.core.ReasoningDepth;
import com.agenticai.core.agents.core.TuningMode;
import com.agenticai.core.providers.ProviderName;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.TenantLimitExceededException;
import com.agenticai.core.security.TenantLimits;
import com.agenticai.core.sharing.PermissionLevel;
import com.agenticai.core.sharing.ResourceAccessService;
import com.agenticai.core.sharing.ResourceType;
import com.agenticai.core.workflow.dto.ValidationResult;
import com.agenticai.core.workflow.dto.WorkflowGraphDto;
import com.agenticai.core.workflow.dto.WorkflowNodeDto;
import com.agenticai.core.workflow.dto.WorkflowResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Create / list / update / delete / duplicate / validate and the per-workflow settings setters,
 * split out of {@link WorkflowService}. A graph is always validated before it is saved as {@link
 * WorkflowStatus#ACTIVE}; DRAFT saves skip that gate so a work-in-progress graph can still be
 * saved.
 */
@Service
class WorkflowCrudService {

    private final WorkflowDefinitionRepository definitionRepository;
    private final OrchestratorService orchestratorService;
    private final TenantLimits tenantLimits;
    private final ResourceAccessService resourceAccessService;
    private final WorkflowExecutionParamsInferenceService executionParamsInferenceService;
    private final com.agenticai.core.workflow.version.WorkflowVersionService versionService;
    private final WorkflowAccessGuard guard;
    private final WorkflowGraphCodec codec;
    private final ObjectMapper objectMapper;

    // Pins (V374): setter-injected so hand-built services in tests keep compiling; also keeps this
    // class free of a constructor dependency on the pin API (WorkflowPinService depends on
    // WorkflowService, not the reverse).
    private WorkflowPinStore pinStore;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setPinStore(WorkflowPinStore pinStore) {
        this.pinStore = pinStore;
    }

    WorkflowCrudService(
            WorkflowDefinitionRepository definitionRepository,
            OrchestratorService orchestratorService,
            TenantLimits tenantLimits,
            ResourceAccessService resourceAccessService,
            WorkflowExecutionParamsInferenceService executionParamsInferenceService,
            com.agenticai.core.workflow.version.WorkflowVersionService versionService,
            WorkflowAccessGuard guard,
            WorkflowGraphCodec codec,
            ObjectMapper objectMapper) {
        this.definitionRepository = definitionRepository;
        this.orchestratorService = orchestratorService;
        this.tenantLimits = tenantLimits;
        this.resourceAccessService = resourceAccessService;
        this.executionParamsInferenceService = executionParamsInferenceService;
        this.versionService = versionService;
        this.guard = guard;
        this.codec = codec;
        this.objectMapper = objectMapper;
    }

    /**
     * Universal-tier cap (#387, mirrors {@code AgentService#create}'s own {@link
     * TenantLimits#maxAgents} check exactly): a standing ceiling, not just at creation - deleting a
     * workflow always re-opens a slot under the same cap. {@code tenantId == null} (no HTTP request
     * context, e.g. a future non-request caller) is left uncapped rather than NPE-ing, same guard
     * {@code AgentService} uses.
     */
    @Transactional
    public WorkflowDefinition create(String name, String description, WorkflowGraphDto graph) {
        String tenantId = TenantContext.current().map(TenantContext::getTenantId).orElse(null);
        if (tenantId != null) {
            Optional<Integer> maxWorkflows = tenantLimits.maxWorkflows(tenantId);
            if (maxWorkflows.isPresent() && definitionRepository.count() >= maxWorkflows.get()) {
                throw new TenantLimitExceededException(
                        "The "
                                + tenantLimits.plan(tenantId)
                                + " plan allows up to "
                                + maxWorkflows.get()
                                + " workflows at a time - upgrade your plan, or delete an existing"
                                + " workflow, to add another.");
            }
        }
        String graphJson = codec.writeGraph(graph);
        WorkflowDefinition definition =
                new WorkflowDefinition(name, description, graphJson, WorkflowStatus.DRAFT);
        // Cross-user resource sharing retrofit (Round 2) - private-going-forward-only
        // rollout (migration V151's own header comment), same shape as
        // AgentService#create: every workflow created from this point on records
        // its creator's subject. TenantContext.current() empty (no HTTP request
        // context) leaves createdBy null, same "legacy/open" treatment as every
        // pre-migration workflow.
        definition.setCreatedBy(
                TenantContext.current().map(TenantContext::getSubject).orElse(null));
        WorkflowDefinition saved = definitionRepository.save(definition);
        // Version history (V273): v1 = "as created". Written after the save
        // so the snapshot's workflow_id FK has a row to point at.
        versionService.snapshot(saved, "Workflow created");
        return saved;
    }

    /**
     * Cross-user resource sharing retrofit (Round 2) - list-level filter, identical shape/reasoning
     * to {@code AgentService#listAll}: a caller should simply never see a private workflow it has
     * no access to IN the list, rather than getting a full list back and 404ing per-entry.
     * Included: every legacy workflow ({@code createdBy == null}), every workflow this caller owns,
     * every workflow for a tenant-admin/platform- admin caller, and every workflow this caller has
     * ANY active grant on.
     */
    @Transactional(readOnly = true)
    public List<WorkflowDefinition> listAll() {
        List<WorkflowDefinition> all = definitionRepository.findAll();
        String subject = TenantContext.current().map(TenantContext::getSubject).orElse(null);
        // Granular RBAC (2026-09-23): "sees every workflow" is the
        // MANAGE_WORKFLOWS_ADMIN scope now, not the blanket tenant-admin role.
        boolean admin =
                TenantContext.current()
                        .map(
                                ctx ->
                                        ctx.hasRole("PERM_MANAGE_WORKFLOWS_ADMIN")
                                                || ctx.hasRole("ROLE_platform-admin"))
                        .orElse(false);
        java.util.Set<UUID> grantedWorkflowIds =
                subject == null
                        ? java.util.Set.of()
                        : resourceAccessService.listSharedWithMe().stream()
                                .filter(grant -> grant.getResourceType() == ResourceType.WORKFLOW)
                                .map(com.agenticai.core.sharing.ResourceGrant::getResourceId)
                                .collect(java.util.stream.Collectors.toSet());
        return all.stream()
                .filter(
                        definition ->
                                definition.getCreatedBy() == null
                                        || definition.getCreatedBy().equals(subject)
                                        || admin
                                        || grantedWorkflowIds.contains(definition.getId()))
                .toList();
    }

    @Transactional
    public WorkflowDefinition update(
            UUID id,
            String name,
            String description,
            WorkflowGraphDto graph,
            WorkflowStatus status) {
        WorkflowDefinition definition = guard.requireAccess(id, PermissionLevel.EDITOR);
        guard.assertEditable(definition);
        if (status == WorkflowStatus.ACTIVE) {
            orchestratorService.validate(graph, id);
        }
        definition.update(name, description, codec.writeGraph(graph), status);
        pruneRemovedPins(id, graph);
        // Version history (V273): the snapshot AFTER the change, so the
        // newest row always describes the definition the workflow is
        // running right now - same "newest row IS the live config"
        // convention AgentVersion documents.
        versionService.snapshot(
                definition, status == WorkflowStatus.ACTIVE ? "Updated + activated" : "Updated");
        return definition;
    }

    /**
     * Graph-save hook: a node that is gone from the saved graph takes its pin (and the pinned data,
     * which may hold PII) with it. Runs inside the save's transaction, so a failed save keeps its
     * pins.
     */
    public void pruneRemovedPins(UUID workflowId, WorkflowGraphDto graph) {
        if (pinStore == null || graph == null) {
            return;
        }
        pinStore.deleteForRemovedNodes(
                workflowId,
                graph.nodes().stream()
                        .map(WorkflowNodeDto::id)
                        .filter(java.util.Objects::nonNull)
                        .toList());
    }

    @Transactional
    public void delete(UUID id) {
        WorkflowDefinition definition = guard.requireAccess(id, PermissionLevel.EDITOR);
        definitionRepository.delete(definition);
    }

    /**
     * Subscription Plan &amp; GTM Strategy doc, Section 4, Decision 2 - a FREE tenant's only way to
     * change a locked (24h+) workflow: duplicate it into a fresh DRAFT copy (never ACTIVE -
     * re-activating re-runs governance via {@link #update}, same as any other newly-created
     * workflow) with its own grace window. Triggers (webhook token, schedule, channel binding) are
     * deliberately NOT copied - those are live integrations tied to the original workflow's
     * identity, not part of "the graph", and copying them would silently create two workflows
     * racing to answer the same inbound webhook/schedule/channel. Available regardless of plan,
     * same "useful shortcut for everyone" posture as {@code AgentService#duplicate}.
     */
    @Transactional
    public WorkflowDefinition duplicate(UUID id) {
        // Cross-user resource sharing retrofit (Round 2) - VIEWER: duplicating
        // copies the full graph into a brand-new workflow, same "needs at
        // least read visibility into the source" bar as AgentService#duplicate.
        WorkflowDefinition source = guard.requireAccess(id, PermissionLevel.VIEWER);
        String tenantId = TenantContext.current().map(TenantContext::getTenantId).orElse(null);
        if (tenantId != null) {
            Optional<Integer> maxWorkflows = tenantLimits.maxWorkflows(tenantId);
            if (maxWorkflows.isPresent() && definitionRepository.count() >= maxWorkflows.get()) {
                throw new TenantLimitExceededException(
                        "The "
                                + tenantLimits.plan(tenantId)
                                + " plan allows up to "
                                + maxWorkflows.get()
                                + " workflows at a time - upgrade your plan, or delete an existing"
                                + " workflow, to duplicate this one.");
            }
        }
        WorkflowDefinition copy =
                new WorkflowDefinition(
                        source.getName() + " (copy)",
                        source.getDescription(),
                        source.getGraphJson(),
                        WorkflowStatus.DRAFT);
        // Cross-user resource sharing retrofit (Round 2) - the copy is a
        // brand-new workflow (own id, own createdAt/grace window), so it gets
        // its own owner too - whoever clicked duplicate, not the source's
        // createdBy (left untouched on the source).
        copy.setCreatedBy(TenantContext.current().map(TenantContext::getSubject).orElse(null));
        return definitionRepository.save(copy);
    }

    /**
     * {@link WorkflowController}'s single response-building path, so the {@code editable} flag is
     * never computed twice.
     */
    public WorkflowResponse toResponse(WorkflowDefinition definition) {
        return WorkflowResponse.from(
                definition,
                objectMapper,
                guard.isEditable(definition),
                guard.accessLevelOf(definition));
    }

    @Transactional(readOnly = true)
    public ValidationResult validate(UUID id) {
        // Cross-user resource sharing retrofit (Round 2) - VIEWER: read-only,
        // reveals graph-validity detail, same bar as a plain config read.
        WorkflowDefinition definition = guard.requireAccess(id, PermissionLevel.VIEWER);
        try {
            orchestratorService.validate(codec.readGraph(definition.getGraphJson()), id);
            return ValidationResult.ok();
        } catch (WorkflowValidationException e) {
            return ValidationResult.failed(e.getErrors());
        }
    }

    /**
     * Public accessor for callers (e.g. {@code WorkflowDiagramService}) that need the parsed graph,
     * not just the persisted entity's raw JSON.
     */
    @Transactional(readOnly = true)
    public WorkflowGraphDto graph(UUID id) {
        return codec.readGraph(guard.get(id).getGraphJson());
    }

    /**
     * PATCH /reasoning-depth - opts an existing workflow into a different level of deliberation for
     * its step executions; mirrors {@code AgentService#setReasoningDepth} exactly.
     */
    @Transactional
    public WorkflowDefinition setReasoningDepth(UUID id, ReasoningDepth d) {
        WorkflowDefinition definition = guard.requireAccess(id, PermissionLevel.EDITOR);
        guard.assertEditable(definition);
        definition.setReasoningDepth(d);
        return definitionRepository.save(definition);
    }

    /**
     * PATCH /tuning-mode - switches a workflow's execution-limits source. Switching to {@link
     * TuningMode#SIMPLE} immediately (re-)infers {@code maxStepsPerRun}/{@code stepTimeoutSeconds}
     * from the workflow's own name and description via {@link
     * WorkflowExecutionParamsInferenceService} and stores the result. Switching to {@link
     * TuningMode#MANUAL} only flips the mode flag - whatever limits are currently set are left in
     * place as an editable starting point, not reset to any default (mirrors {@code
     * AgentService#setTuningMode}'s identical reasoning).
     */
    @Transactional
    public WorkflowDefinition setTuningMode(UUID id, TuningMode m) {
        WorkflowDefinition definition = guard.requireAccess(id, PermissionLevel.EDITOR);
        guard.assertEditable(definition);
        definition.setTuningMode(m);
        if (m == TuningMode.SIMPLE) {
            WorkflowExecutionParamsInferenceService.InferredLimits inferred =
                    executionParamsInferenceService.infer(
                            definition.getName(), definition.getDescription());
            definition.setExecutionLimits(inferred.maxStepsPerRun(), inferred.stepTimeoutSeconds());
        }
        return definitionRepository.save(definition);
    }

    /**
     * PATCH /step-caching - toggles result-caching for individual workflow steps. When enabled, a
     * step whose input has not changed since its last execution can reuse the cached output rather
     * than re-running the underlying agent call or tool invocation.
     */
    @Transactional
    public WorkflowDefinition setStepCaching(UUID id, boolean enabled) {
        WorkflowDefinition definition = guard.requireAccess(id, PermissionLevel.EDITOR);
        guard.assertEditable(definition);
        definition.setStepCachingEnabled(enabled);
        return definitionRepository.save(definition);
    }

    /**
     * PATCH /cost-routing - configures an economy-tier provider/model for workflow steps that don't
     * need a premium model (mirrors {@code AgentService#setCostRouting}'s identical shape and
     * "both-together- or-neither" invariant for the provider/model pair).
     */
    @Transactional
    public WorkflowDefinition setCostRouting(
            UUID id, boolean enabled, ProviderName provider, String model) {
        WorkflowDefinition definition = guard.requireAccess(id, PermissionLevel.EDITOR);
        guard.assertEditable(definition);
        definition.setCostRouting(enabled, provider, model);
        return definitionRepository.save(definition);
    }

    /**
     * PATCH /execution-limits - explicitly sets per-run step ceiling and per-step timeout.
     * Switching a workflow to {@link TuningMode#MANUAL} before calling this is not required - these
     * limits can be overridden regardless of the current tuning mode (subsequent SIMPLE inference
     * would overwrite them, but that requires an explicit re-infer action, not an implicit one). A
     * null argument leaves that setting unchanged.
     */
    @Transactional
    public WorkflowDefinition setExecutionLimits(
            UUID id, Integer maxSteps, Integer timeout, Integer maxRetryAttempts) {
        WorkflowDefinition definition = guard.requireAccess(id, PermissionLevel.EDITOR);
        guard.assertEditable(definition);
        if (maxSteps == null && timeout == null && maxRetryAttempts == null) {
            throw new WorkflowValidationException(
                    List.of(
                            "Provide at least one of maxStepsPerRun, stepTimeoutSeconds,"
                                    + " maxRetryAttempts."));
        }
        // null = unchanged (SE-55)
        definition.setExecutionLimits(
                maxSteps != null ? maxSteps : definition.getMaxStepsPerRun(),
                timeout != null ? timeout : definition.getStepTimeoutSeconds());
        if (maxRetryAttempts != null) {
            definition.setMaxRetryAttempts(maxRetryAttempts);
        }
        return definitionRepository.save(definition);
    }

    /**
     * The definition's {@code updatedAt}, the entity tag of {@code /api/workflows/{id}}; no access
     * check.
     */
    Optional<java.time.Instant> lastModified(UUID workflowId) {
        return definitionRepository.findById(workflowId).map(WorkflowDefinition::getUpdatedAt);
    }
}
