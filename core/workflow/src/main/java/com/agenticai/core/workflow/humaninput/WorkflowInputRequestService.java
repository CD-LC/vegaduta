package com.agenticai.core.workflow.humaninput;

import com.agenticai.core.approval.ApprovalReplayGrant;
import com.agenticai.core.audit.NotificationCategory;
import com.agenticai.core.audit.NotificationService;
import com.agenticai.core.audit.NotificationSeverity;
import com.agenticai.core.workflow.WorkflowApprovalResumeException;
import com.agenticai.core.workflow.WorkflowDefinition;
import com.agenticai.core.workflow.WorkflowDefinitionRepository;
import com.agenticai.core.workflow.WorkflowExecutionEngine;
import com.agenticai.core.workflow.WorkflowRun;
import com.agenticai.core.workflow.WorkflowRunRepository;
import com.agenticai.core.workflow.WorkflowRunStatus;
import com.agenticai.core.workflow.dto.NodeTraceEntry;
import com.agenticai.core.workflow.dto.WorkflowGraphDto;
import com.agenticai.core.workflow.dto.WorkflowNodeDto;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The two halves of the human-input node:
 *
 * <p><b>Halt</b> ({@link #createPending}): called by {@code
 * WorkflowNodeExecutor#executeHumanInputNode} immediately before it returns {@code
 * haltRun(AWAITING_INPUT)}. Snapshots the node's form definition and the upstream output (the
 * resume envelope) onto a PENDING request row so the console can render the exact form the run
 * presented.
 *
 * <p><b>Resume</b> ({@link #submit}): validates the posted values against the snapshotted schema
 * (server-side - a hand-rolled POST can't skip a required field), atomically claims the run
 * AWAITING_INPUT &rarr; RUNNING via the same {@code compareAndSetStatus} CAS the approval executor
 * uses (exactly one resolver wins a double-submit), then re-enters {@link
 * WorkflowExecutionEngine#resume} at the halted node under an {@link ApprovalReplayGrant} for
 * {@code "workflow.node.human_input"} - the grant is what lets the re-executed node return the
 * submitted values instead of recording a SECOND pending request and re-halting forever. The node's
 * output on resume is a JSON envelope {@code {"form": {...values...}, "input": "<original upstream
 * output>"}} so downstream nodes can read the collected fields AND still see what flowed in.
 *
 * <p>ObjectProvider on the engine for the same reason {@code WorkflowApprovalActionExecutor}
 * documents: the bean cycle service &rarr; engine &rarr; node executor &rarr; this service is real.
 */
@Service
public class WorkflowInputRequestService {

    private static final Logger log = LoggerFactory.getLogger(WorkflowInputRequestService.class);

    /** Must stay in sync with WorkflowNodeExecutor#executeHumanInputNode's grant consumption. */
    public static final String REPLAY_ACTION_TYPE = "workflow.node.human_input";

    private final WorkflowInputRequestRepository requestRepository;
    private final WorkflowRunRepository runRepository;
    private final WorkflowDefinitionRepository definitionRepository;
    private final ObjectProvider<WorkflowExecutionEngine> executionEngine;
    private final ObjectProvider<NotificationService> notificationService;
    private final ObjectMapper objectMapper;

    public WorkflowInputRequestService(
            WorkflowInputRequestRepository requestRepository,
            WorkflowRunRepository runRepository,
            WorkflowDefinitionRepository definitionRepository,
            ObjectProvider<WorkflowExecutionEngine> executionEngine,
            ObjectProvider<NotificationService> notificationService,
            ObjectMapper objectMapper) {
        this.requestRepository = requestRepository;
        this.runRepository = runRepository;
        this.definitionRepository = definitionRepository;
        this.executionEngine = executionEngine;
        this.notificationService = notificationService;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public WorkflowInputRequest createPending(
            UUID runId,
            UUID workflowId,
            String workflowName,
            WorkflowNodeDto node,
            String nodeInput,
            String requestedBy) {
        String formSchema;
        try {
            formSchema =
                    objectMapper.writeValueAsString(
                            Map.of(
                                    "message", node.config().getOrDefault("message", ""),
                                    "fields", node.config().getOrDefault("fields", List.of())));
        } catch (Exception e) {
            formSchema = "{}";
        }
        WorkflowInputRequest request =
                requestRepository.save(
                        new WorkflowInputRequest(
                                runId,
                                workflowId,
                                workflowName,
                                node.id(),
                                node.label(),
                                formSchema,
                                nodeInput,
                                requestedBy));
        // Notifications (Tier-3): an AWAITING_INPUT halt is exactly the
        // "someone must act before this continues" signal the bell exists
        // for - same broadcast-to-tenant-admins shape as the approval
        // notification in OrchestratorService#authorizeSensitiveNode.
        // Best-effort: a bell-write failure must never un-halt a run.
        try {
            NotificationService service = notificationService.getIfAvailable();
            if (service != null) {
                service.create(
                        null,
                        NotificationCategory.WORKFLOW,
                        "Workflow '" + workflowName + "' needs human input",
                        "Node '" + node.label() + "' paused the run waiting for a form submission.",
                        NotificationSeverity.INFO,
                        null,
                        null);
            }
        } catch (RuntimeException notifyFailure) {
            log.warn(
                    "Failed to create notification for input request {}",
                    request.getId(),
                    notifyFailure);
        }
        return request;
    }

    @Transactional(readOnly = true)
    public List<WorkflowInputRequest> list(WorkflowInputRequestStatus status, UUID runId) {
        if (runId != null) {
            return requestRepository.findByRunIdOrderByCreatedAtAsc(runId);
        }
        return status != null
                ? requestRepository.findByStatusOrderByCreatedAtDesc(status)
                : requestRepository.findAllByOrderByCreatedAtDesc();
    }

    @Transactional(readOnly = true)
    public WorkflowInputRequest get(UUID id) {
        return requestRepository
                .findById(id)
                .orElseThrow(
                        () ->
                                new WorkflowInputRequestNotFoundException(
                                        "Input request " + id + " does not exist."));
    }

    /**
     * Resolve a pending request with the human's submitted values and resume the run. Returns the
     * run AFTER the resumed traversal finishes (the legacy engine is synchronous), matching {@code
     * WorkflowApprovalActionExecutor}'s contract.
     */
    public WorkflowRun submit(UUID requestId, Map<String, Object> values, String submittedBy) {
        WorkflowInputRequest request = get(requestId);
        if (request.getStatus() != WorkflowInputRequestStatus.PENDING) {
            throw new WorkflowInputRequestStateException(
                    "Input request " + requestId + " is already " + request.getStatus() + ".");
        }
        WorkflowRun run =
                runRepository
                        .findById(request.getRunId())
                        .orElseThrow(
                                () ->
                                        new WorkflowApprovalResumeException(
                                                "Input request "
                                                        + requestId
                                                        + " references workflow run "
                                                        + request.getRunId()
                                                        + ", which no longer exists."));
        if (run.getStatus() != WorkflowRunStatus.AWAITING_INPUT) {
            throw new WorkflowInputRequestStateException(
                    "Workflow run "
                            + run.getId()
                            + " is "
                            + run.getStatus()
                            + ", not AWAITING_INPUT"
                            + " - there is nothing to submit to.");
        }
        validateValues(request, values);

        int claimed =
                runRepository.compareAndSetStatus(
                        run.getId(), WorkflowRunStatus.AWAITING_INPUT, WorkflowRunStatus.RUNNING);
        if (claimed == 0) {
            throw new WorkflowInputRequestStateException(
                    "Workflow run "
                            + run.getId()
                            + " was already claimed by a concurrent submission.");
        }
        String valuesJson;
        try {
            valuesJson = objectMapper.writeValueAsString(values == null ? Map.of() : values);
        } catch (Exception e) {
            throw new WorkflowInputRequestStateException(
                    "Submitted values are not serializable JSON.");
        }
        request.markSubmitted(valuesJson, submittedBy);
        requestRepository.save(request);

        WorkflowDefinition definition =
                definitionRepository
                        .findById(run.getWorkflowId())
                        .orElseThrow(
                                () ->
                                        new WorkflowApprovalResumeException(
                                                "Workflow "
                                                        + run.getWorkflowId()
                                                        + " behind run "
                                                        + run.getId()
                                                        + " no longer exists - cannot resume."));
        WorkflowGraphDto graph = readGraph(definition);
        List<NodeTraceEntry> trace = readTraceTolerantly(run);
        // Re-fetch after the CAS so the resumed leg can't resurrect the
        // halt-time fields the stale entity still carries - same reason
        // WorkflowApprovalActionExecutor re-fetches.
        WorkflowRun claimedRun = runRepository.findById(run.getId()).orElseThrow();
        // The resumed node's output envelope: the collected fields, plus
        // the upstream input the node originally consumed so downstream
        // nodes lose nothing by the pause having happened.
        Map<String, Object> resumeEnvelope = new LinkedHashMap<>();
        resumeEnvelope.put("form", values == null ? Map.of() : values);
        resumeEnvelope.put("input", request.getNodeInput() == null ? "" : request.getNodeInput());
        String resumeInput;
        try {
            resumeInput = objectMapper.writeValueAsString(resumeEnvelope);
        } catch (Exception e) {
            throw new WorkflowInputRequestStateException(
                    "Could not serialize the resume envelope.");
        }

        ApprovalReplayGrant.grant(REPLAY_ACTION_TYPE);
        try {
            return executionEngine
                    .getObject()
                    .resume(
                            definition,
                            graph,
                            claimedRun,
                            trace,
                            request.getNodeId(),
                            resumeInput,
                            run.getRequestedBy());
        } finally {
            ApprovalReplayGrant.clear();
        }
    }

    /**
     * Required-field enforcement against the halt-time schema snapshot. Field shape: {@code fields:
     * [{key, label, type, required}]} - anything else in the list is tolerated (a partial schema
     * never blocks a submit, only a missing required key does).
     */
    private void validateValues(WorkflowInputRequest request, Map<String, Object> values) {
        List<String> missing = new ArrayList<>();
        try {
            JsonNode fields =
                    objectMapper
                            .readTree(
                                    request.getFormSchemaJson() == null
                                            ? "{}"
                                            : request.getFormSchemaJson())
                            .get("fields");
            if (fields != null && fields.isArray()) {
                for (JsonNode field : fields) {
                    JsonNode key = field.get("key");
                    boolean required =
                            field.has("required") && field.get("required").asBoolean(false);
                    if (required
                            && key != null
                            && (values == null
                                    || values.get(key.asString()) == null
                                    || values.get(key.asString()).toString().isBlank())) {
                        missing.add(key.asString());
                    }
                }
            }
        } catch (Exception e) {
            log.warn(
                    "Input request {} has an unreadable form schema - skipping required-field validation.",
                    request.getId());
        }
        if (!missing.isEmpty()) {
            throw new WorkflowInputRequestStateException(
                    "Missing required field(s): " + String.join(", ", missing) + ".");
        }
    }

    private WorkflowGraphDto readGraph(WorkflowDefinition definition) {
        try {
            return objectMapper.readValue(definition.getGraphJson(), WorkflowGraphDto.class);
        } catch (Exception e) {
            throw new WorkflowApprovalResumeException(
                    "Workflow '"
                            + definition.getName()
                            + "' has an unreadable graph - cannot resume: "
                            + e.getMessage(),
                    e);
        }
    }

    /**
     * Tolerant for the same reason WorkflowApprovalActionExecutor's is - the trace is history, not
     * resume state.
     */
    private List<NodeTraceEntry> readTraceTolerantly(WorkflowRun run) {
        try {
            if (run.getTraceJson() != null && !run.getTraceJson().isBlank()) {
                return new ArrayList<>(
                        objectMapper.readValue(
                                run.getTraceJson(), new TypeReference<List<NodeTraceEntry>>() {}));
            }
        } catch (Exception e) {
            log.warn(
                    "Could not parse trace for workflow run {} - resuming with an empty history.",
                    run.getId());
        }
        return new ArrayList<>();
    }
}
