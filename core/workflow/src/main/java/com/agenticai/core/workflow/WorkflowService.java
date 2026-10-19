package com.agenticai.core.workflow;

import com.agenticai.core.agents.core.ReasoningDepth;
import com.agenticai.core.agents.core.TuningMode;
import com.agenticai.core.agents.dto.ChatInlineAttachment;
import com.agenticai.core.audit.KeysetCursor;
import com.agenticai.core.omnichannel.trigger.WorkflowTriggerChannel;
import com.agenticai.core.providers.ProviderName;
import com.agenticai.core.sharing.PermissionLevel;
import com.agenticai.core.workflow.dto.ValidationResult;
import com.agenticai.core.workflow.dto.WebhookSignatureRequest;
import com.agenticai.core.workflow.dto.WebhookSignatureResponse;
import com.agenticai.core.workflow.dto.WorkflowGraphDto;
import com.agenticai.core.workflow.dto.WorkflowPollTriggerRequest;
import com.agenticai.core.workflow.dto.WorkflowPollTriggerResponse;
import com.agenticai.core.workflow.dto.WorkflowResponse;
import com.agenticai.core.workflow.dto.WorkflowRunResponse;
import com.agenticai.core.workflow.dto.WorkflowScheduleRequest;
import com.agenticai.core.workflow.dto.WorkflowScheduleResponse;
import com.agenticai.core.workflow.dto.WorkflowTriggerChannelResponse;
import com.agenticai.core.workflow.dto.WorkflowWebhookResponse;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * The workflow API facade that controllers and other modules call. It owns no logic: every method
 * delegates to one capability service - {@link WorkflowCrudService} (CRUD + settings), {@link
 * WorkflowRunService} (run/cancel/pause/list runs), {@link WorkflowTriggerService} (cron schedule),
 * {@link WorkflowVersionRestoreService} (restore + run-graph resolution), {@link
 * WorkflowAccessGuard} (access/plan gates) - or, for webhook/signature/poll/channel trigger
 * configuration, to the existing {@link WorkflowTriggerConfigService}.
 *
 * <p>A graph is always validated before it is saved as {@link WorkflowStatus#ACTIVE} - DRAFT saves
 * skip that gate so a work-in-progress graph can still be saved.
 */
@Service
public class WorkflowService {

    private final WorkflowCrudService crud;
    private final WorkflowRunService runs;
    private final WorkflowTriggerService triggers;
    private final WorkflowVersionRestoreService versions;
    private final WorkflowAccessGuard guard;
    private final WorkflowGraphCodec codec;

    // Trigger configuration (webhook/channel/signature/poll) lives in WorkflowTriggerConfigService -
    // setter-injected because it depends back on this service for the access gates. @Lazy because
    // setter injection alone does not break the cycle under
    // spring.main.allow-circular-references=false (Boot's default).
    private WorkflowTriggerConfigService triggerConfigService;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setTriggerConfigService(
            @org.springframework.context.annotation.Lazy
                    WorkflowTriggerConfigService triggerConfigService) {
        this.triggerConfigService = triggerConfigService;
    }

    /** Pins (V374): optional; forwarded to the CRUD service that prunes them on graph save. */
    void setPinStore(WorkflowPinStore pinStore) {
        crud.setPinStore(pinStore);
    }

    WorkflowService(
            WorkflowCrudService crud,
            WorkflowRunService runs,
            WorkflowTriggerService triggers,
            WorkflowVersionRestoreService versions,
            WorkflowAccessGuard guard,
            WorkflowGraphCodec codec) {
        this.crud = crud;
        this.runs = runs;
        this.triggers = triggers;
        this.versions = versions;
        this.guard = guard;
        this.codec = codec;
    }

    // ---- CRUD

    public WorkflowDefinition create(String name, String description, WorkflowGraphDto graph) {
        return crud.create(name, description, graph);
    }

    public List<WorkflowDefinition> listAll() {
        return crud.listAll();
    }

    public WorkflowDefinition get(UUID id) {
        return guard.get(id);
    }

    public WorkflowDefinition update(
            UUID id,
            String name,
            String description,
            WorkflowGraphDto graph,
            WorkflowStatus status) {
        return crud.update(id, name, description, graph, status);
    }

    public void delete(UUID id) {
        crud.delete(id);
    }

    public WorkflowDefinition duplicate(UUID id) {
        return crud.duplicate(id);
    }

    public ValidationResult validate(UUID id) {
        return crud.validate(id);
    }

    public WorkflowGraphDto graph(UUID id) {
        return crud.graph(id);
    }

    /** The controller's single response-building path, so {@code editable} is never computed twice. */
    public WorkflowResponse toResponse(WorkflowDefinition definition) {
        return crud.toResponse(definition);
    }

    public WorkflowDefinition setReasoningDepth(UUID id, ReasoningDepth d) {
        return crud.setReasoningDepth(id, d);
    }

    public WorkflowDefinition setTuningMode(UUID id, TuningMode m) {
        return crud.setTuningMode(id, m);
    }

    public WorkflowDefinition setStepCaching(UUID id, boolean enabled) {
        return crud.setStepCaching(id, enabled);
    }

    public WorkflowDefinition setCostRouting(
            UUID id, boolean enabled, ProviderName provider, String model) {
        return crud.setCostRouting(id, enabled, provider, model);
    }

    public WorkflowDefinition setExecutionLimits(
            UUID id, Integer maxSteps, Integer timeout, Integer maxRetryAttempts) {
        return crud.setExecutionLimits(id, maxSteps, timeout, maxRetryAttempts);
    }

    // ---- access gates

    WorkflowDefinition requireAccess(UUID id, PermissionLevel required) {
        return guard.requireAccess(id, required);
    }

    /** Public VIEWER-level funnel for the version/history endpoints. */
    public WorkflowDefinition requireViewerAccess(UUID id) {
        return guard.requireViewer(id);
    }

    /** Public EDITOR-level funnel for the publish/promote/rollback endpoints. */
    public WorkflowDefinition requireEditorAccess(UUID id) {
        return guard.requireEditor(id);
    }

    void assertEditable(WorkflowDefinition definition) {
        guard.assertEditable(definition);
    }

    String accessLevelOf(WorkflowDefinition definition) {
        return guard.accessLevelOf(definition);
    }

    void requireUnattendedAutomation() {
        guard.requireUnattendedAutomation();
    }

    String writeGraph(WorkflowGraphDto graph) {
        return codec.writeGraph(graph);
    }

    /** Package-private for {@link WorkflowBackgroundRunService}: same parse, same corrupt-graph contract. */
    WorkflowGraphDto readGraph(String graphJson) {
        return codec.readGraph(graphJson);
    }

    // ---- versions

    /** C1: the graph a NEW run executes - PRODUCTION snapshot when one exists, else the live one. */
    public WorkflowGraphDto resolveRunGraph(WorkflowDefinition definition) {
        return versions.resolveRunGraph(definition);
    }

    /** C1 pin semantics for RESUMED runs: the graph of the version the run stamped at start. */
    public String resolvePinnedGraphJson(
            WorkflowDefinition definition, Integer pinnedVersionNumber) {
        return versions.resolvePinnedGraphJson(definition, pinnedVersionNumber);
    }

    /** Restores a snapshot onto the live definition (EDITOR access) and snapshots the restore. */
    public WorkflowDefinition restoreVersion(UUID id, int versionNumber) {
        return versions.restoreVersion(id, versionNumber);
    }

    // ---- runs

    /**
     * Deliberately NO access check on any {@code run} overload: it is the shared execution primitive
     * for the human Play button (which enforces RUNNER in {@link WorkflowController#run}) and every
     * autonomous trigger (cron, webhook, channel, sub-workflow) that has no tenant-member identity
     * to check. See {@link WorkflowRunService#run}.
     */
    public WorkflowRunResponse run(UUID id, String input, String requestedBy) {
        return runs.run(id, input, requestedBy);
    }

    public WorkflowRunResponse run(UUID id, String input, String requestedBy, UUID clientRunId) {
        return runs.run(id, input, requestedBy, clientRunId);
    }

    public WorkflowRunResponse run(UUID id, String startNodeId, String input, String requestedBy) {
        return runs.run(id, startNodeId, input, requestedBy);
    }

    public WorkflowRunResponse run(
            UUID id, String startNodeId, String input, String requestedBy, UUID clientRunId) {
        return runs.run(id, startNodeId, input, requestedBy, clientRunId);
    }

    public WorkflowRunResponse run(
            UUID id,
            String startNodeId,
            String input,
            String requestedBy,
            UUID clientRunId,
            List<ChatInlineAttachment> attachments) {
        return runs.run(id, startNodeId, input, requestedBy, clientRunId, attachments);
    }

    public WorkflowRunResponse run(
            UUID id,
            String startNodeId,
            String input,
            String requestedBy,
            UUID clientRunId,
            List<ChatInlineAttachment> attachments,
            RunMode mode) {
        return runs.run(id, startNodeId, input, requestedBy, clientRunId, attachments, mode);
    }

    public WorkflowRunResponse run(
            UUID id,
            String startNodeId,
            String input,
            String requestedBy,
            UUID clientRunId,
            List<ChatInlineAttachment> attachments,
            RunMode mode,
            String comment) {
        return runs.run(
                id, startNodeId, input, requestedBy, clientRunId, attachments, mode, comment);
    }

    public List<WorkflowRunResponse> listRuns(UUID workflowId) {
        return runs.listRuns(workflowId);
    }

    public long countRuns(UUID workflowId) {
        return runs.countRuns(workflowId);
    }

    /** Default / hard-max row counts for the run-history list. */
    static final int RUNS_DEFAULT_LIMIT = WorkflowRunService.RUNS_DEFAULT_LIMIT;

    static final int RUNS_MAX_LIMIT = WorkflowRunService.RUNS_MAX_LIMIT;

    public List<WorkflowRunResponse> listRuns(UUID workflowId, Integer limit) {
        return runs.listRuns(workflowId, limit);
    }

    public List<WorkflowRunResponse> listRuns(UUID workflowId, Integer limit, Long offset) {
        return runs.listRuns(workflowId, limit, offset);
    }

    public List<WorkflowRunResponse> listRunsAfter(UUID workflowId, KeysetCursor after, int limit) {
        return runs.listRunsAfter(workflowId, after, limit);
    }

    public WorkflowRunResponse getRun(UUID workflowId, UUID runId) {
        return runs.getRun(workflowId, runId);
    }

    public WorkflowRunResponse cancelRun(UUID workflowId, UUID runId) {
        return runs.cancelRun(workflowId, runId);
    }

    /** SE-54: cancel, pause and resume on a finished run answer 409 naming its status. */
    static void rejectTerminal(WorkflowRun run, String action) {
        WorkflowRunService.rejectTerminal(run, action);
    }

    public WorkflowRunResponse pauseRun(UUID workflowId, UUID runId) {
        return runs.pauseRun(workflowId, runId);
    }

    public WorkflowRun getRunEntity(UUID workflowId, UUID runId) {
        return runs.getRunEntity(workflowId, runId);
    }

    // ---- webhook / signature / poll / channel triggers (WorkflowTriggerConfigService)

    public WorkflowWebhookResponse enableWebhook(UUID id, String nodeId) {
        return triggerConfig().enableWebhook(id, nodeId);
    }

    public WorkflowWebhookResponse disableWebhook(UUID id, String nodeId) {
        return triggerConfig().disableWebhook(id, nodeId);
    }

    public WorkflowWebhookResponse getWebhook(UUID id, String nodeId) {
        return triggerConfig().getWebhook(id, nodeId);
    }

    /** Webhook signature verification (V482). */
    public WebhookSignatureResponse setWebhookSignature(
            UUID id, String nodeId, WebhookSignatureRequest request) {
        return triggerConfig().setWebhookSignature(id, nodeId, request);
    }

    public WebhookSignatureResponse getWebhookSignature(UUID id, String nodeId) {
        return triggerConfig().getWebhookSignature(id, nodeId);
    }

    /** HTTP polling trigger (V482). */
    public WorkflowPollTriggerResponse configurePollTrigger(
            UUID id, String nodeId, WorkflowPollTriggerRequest request) {
        return triggerConfig().configurePollTrigger(id, nodeId, request);
    }

    public WorkflowPollTriggerResponse disablePollTrigger(UUID id, String nodeId) {
        return triggerConfig().disablePollTrigger(id, nodeId);
    }

    public WorkflowPollTriggerResponse getPollTrigger(UUID id, String nodeId) {
        return triggerConfig().getPollTrigger(id, nodeId);
    }

    public WorkflowTriggerChannelResponse setTriggerChannel(
            UUID id, String nodeId, WorkflowTriggerChannel channel, WebhookAuthMode authMode) {
        return triggerConfig().setTriggerChannel(id, nodeId, channel, authMode);
    }

    public WorkflowTriggerChannelResponse getTriggerChannel(UUID id, String nodeId) {
        return triggerConfig().getTriggerChannel(id, nodeId);
    }

    private WorkflowTriggerConfigService triggerConfig() {
        if (triggerConfigService == null) {
            throw new IllegalStateException(
                    "Trigger configuration is not available in this deployment.");
        }
        return triggerConfigService;
    }

    // ---- cron schedule trigger

    public WorkflowScheduleResponse enableSchedule(UUID id, WorkflowScheduleRequest request) {
        return triggers.enableSchedule(id, request);
    }

    public WorkflowScheduleResponse disableSchedule(UUID id) {
        return triggers.disableSchedule(id);
    }

    public WorkflowScheduleResponse getSchedule(UUID id) {
        return triggers.getSchedule(id);
    }

    public List<UUID> findDueScheduledWorkflowIds(Instant now) {
        return triggers.findDueScheduledWorkflowIds(now);
    }

    public void runScheduledWorkflow(UUID id) {
        triggers.runScheduledWorkflow(id);
    }

    /**
     * The definition's {@code updatedAt} for conditional requests (ETag / If-Match). A plain read with
     * no access check: the handler the request reaches still enforces access.
     */
    public java.util.Optional<java.time.Instant> definitionLastModified(java.util.UUID workflowId) {
        return crud.lastModified(workflowId);
    }
}
