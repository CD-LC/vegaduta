package com.agenticai.core.workflow.voicemenu;

import com.agenticai.core.approval.ApprovalReplayGrant;
import com.agenticai.core.omnichannel.trigger.WorkflowSyncTriggerResult;
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
 * The two halves of the {@code voice_menu} node - deliberately the same halt/resume shape {@code
 * WorkflowInputRequestService} established for {@code human_input} (see that class's javadoc),
 * swapped to a live Twilio DTMF round-trip instead of a console form submission:
 *
 * <p><b>Halt</b> ({@link #createPending}): called by {@code
 * WorkflowNodeExecutor#executeVoiceMenuNode} immediately before it returns {@code
 * haltRun(AWAITING_VOICE_INPUT)}. Snapshots the node's prompt/digit- count/timeout/retry/branch
 * config onto a PENDING {@code workflow_voice_menu_request} row so the exact TwiML {@code <Gather>}
 * Twilio renders next matches what the node actually asked for, even if the graph is edited while
 * this call is still on the line.
 *
 * <p><b>Resume</b> ({@link #collectDigits}): called by {@code TwilioVoiceWebhookController} once
 * Twilio posts the collected {@code Digits} back. Either re-prompts (empty/unmatched digits with
 * retries remaining left on the SAME pending row - not a resume at all, just a mutation) or
 * resolves the request, atomically claims the run {@code AWAITING_VOICE_INPUT -> RUNNING} via the
 * same {@code compareAndSetStatus} CAS approval/human-input resume both use, and re-enters {@link
 * WorkflowExecutionEngine#resume} at the halted node under an {@link ApprovalReplayGrant} for
 * {@link #REPLAY_ACTION_TYPE} - the grant is what lets the re-executed node return the matched
 * branch instead of recording a SECOND pending request and halting all over again. Returns the
 * OMNICHANNEL-owned {@link WorkflowSyncTriggerResult} directly (this module already depends on
 * {@code core-omnichannel} - see {@code WorkflowTriggerChannel}'s javadoc) so {@code
 * WorkflowTriggerPortImpl} is a thin pass-through and every caller-facing outcome (finished, halted
 * again on a second menu, or hit a halt a live call cannot service) is classified in exactly one
 * place.
 *
 * <p>ObjectProvider on the engine/notification-shaped dependencies for the same real bean-cycle
 * reason {@code WorkflowInputRequestService} documents.
 */
@Service
public class WorkflowVoiceMenuRequestService {

    private static final Logger log =
            LoggerFactory.getLogger(WorkflowVoiceMenuRequestService.class);

    /** Must stay in sync with WorkflowNodeExecutor#executeVoiceMenuNode's grant consumption. */
    public static final String REPLAY_ACTION_TYPE = "workflow.node.voice_menu";

    private static final String DEFAULT_FINISH_ON_KEY = "#";
    private static final int DEFAULT_TIMEOUT_SECONDS = 5;
    private static final int MIN_TIMEOUT_SECONDS = 1;
    private static final int MAX_TIMEOUT_SECONDS = 60;
    private static final int DEFAULT_MAX_RETRIES = 2;
    private static final int MIN_MAX_RETRIES = 0;
    private static final int MAX_MAX_RETRIES = 5;
    private static final String NO_MATCH_BRANCH = "no_match";
    private static final String TIMEOUT_BRANCH = "timeout";

    private final WorkflowVoiceMenuRequestRepository requestRepository;
    private final WorkflowRunRepository runRepository;
    private final WorkflowDefinitionRepository definitionRepository;
    private final ObjectProvider<WorkflowExecutionEngine> executionEngine;
    private final ObjectMapper objectMapper;

    public WorkflowVoiceMenuRequestService(
            WorkflowVoiceMenuRequestRepository requestRepository,
            WorkflowRunRepository runRepository,
            WorkflowDefinitionRepository definitionRepository,
            ObjectProvider<WorkflowExecutionEngine> executionEngine,
            ObjectMapper objectMapper) {
        this.requestRepository = requestRepository;
        this.runRepository = runRepository;
        this.definitionRepository = definitionRepository;
        this.executionEngine = executionEngine;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public WorkflowVoiceMenuRequest createPending(
            UUID runId,
            UUID workflowId,
            String workflowName,
            WorkflowNodeDto node,
            String nodeInput,
            String requestedBy) {
        Map<String, Object> config = node.config();
        String prompt = stringConfig(config, "prompt", "");
        String invalidPrompt = stringConfig(config, "invalidPrompt", null);
        Integer numDigits = intConfigOrNull(config, "numDigits");
        String finishOnKey = stringConfig(config, "finishOnKey", DEFAULT_FINISH_ON_KEY);
        int timeoutSeconds =
                clamp(
                        intConfig(config, "timeoutSeconds", DEFAULT_TIMEOUT_SECONDS),
                        MIN_TIMEOUT_SECONDS,
                        MAX_TIMEOUT_SECONDS);
        int maxRetries =
                clamp(
                        intConfig(config, "maxRetries", DEFAULT_MAX_RETRIES),
                        MIN_MAX_RETRIES,
                        MAX_MAX_RETRIES);
        String branchesJson = writeBranchesJson(config.get("branches"));

        WorkflowVoiceMenuRequest request =
                requestRepository.save(
                        new WorkflowVoiceMenuRequest(
                                runId,
                                workflowId,
                                workflowName,
                                node.id(),
                                node.label(),
                                prompt,
                                invalidPrompt,
                                numDigits,
                                finishOnKey,
                                timeoutSeconds,
                                maxRetries,
                                branchesJson,
                                nodeInput,
                                requestedBy));
        return request;
    }

    @Transactional(readOnly = true)
    public WorkflowVoiceMenuRequest get(UUID id) {
        return requestRepository
                .findById(id)
                .orElseThrow(
                        () ->
                                new WorkflowVoiceMenuRequestNotFoundException(
                                        "Voice menu request " + id + " does not exist."));
    }

    /**
     * The one hot-path read {@code WorkflowTriggerPortImpl#triggerSync} needs right after a FIRST
     * {@code voice_menu} halt (there is no "resume" yet to hand it a request id) and {@link
     * #collectDigits} needs again if the resumed graph hits a SECOND {@code voice_menu} node - both
     * cases are "a run is sitting AWAITING_VOICE_INPUT, describe the pending gather". Absent is
     * defensive-only (the halt that set this status always creates exactly one PENDING row first) -
     * reported as a failed result rather than thrown, since by this point a channel controller has
     * nothing left to retry against.
     */
    @Transactional(readOnly = true)
    public WorkflowSyncTriggerResult pendingResultForRun(UUID runId) {
        return requestRepository
                .findByRunIdAndStatus(runId, WorkflowVoiceMenuRequestStatus.PENDING)
                .map(WorkflowVoiceMenuRequestService::toPendingResult)
                .orElseGet(
                        () -> {
                            log.warn(
                                    "Run {} is AWAITING_VOICE_INPUT but has no PENDING workflow_voice_menu_request row.",
                                    runId);
                            return new WorkflowSyncTriggerResult(
                                    false,
                                    "Sorry, something went wrong setting up this menu. Goodbye.");
                        });
    }

    /**
     * Resolve a pending request with the caller's pressed digits (Twilio's raw, possibly-blank
     * {@code Digits} form field) and either re-prompt (a retry, no engine resume) or resume the
     * run. See class javadoc for the full outcome classification.
     */
    public WorkflowSyncTriggerResult collectDigits(
            UUID requestId, String digits, String submittedBy) {
        WorkflowVoiceMenuRequest request = get(requestId);
        if (request.getStatus() != WorkflowVoiceMenuRequestStatus.PENDING) {
            throw new WorkflowVoiceMenuRequestStateException(
                    "Voice menu request " + requestId + " is already " + request.getStatus() + ".");
        }
        WorkflowRun run =
                runRepository
                        .findById(request.getRunId())
                        .orElseThrow(
                                () ->
                                        new WorkflowApprovalResumeException(
                                                "Voice menu request "
                                                        + requestId
                                                        + " references workflow run "
                                                        + request.getRunId()
                                                        + ", which no longer exists."));
        if (run.getStatus() != WorkflowRunStatus.AWAITING_VOICE_INPUT) {
            throw new WorkflowVoiceMenuRequestStateException(
                    "Workflow run "
                            + run.getId()
                            + " is "
                            + run.getStatus()
                            + ", not AWAITING_VOICE_INPUT"
                            + " - there is nothing to collect digits for.");
        }

        String normalizedDigits = digits == null ? "" : digits.trim();
        String branch = matchBranch(request.getBranchesJson(), normalizedDigits);

        if (branch == null) {
            if (request.getAttempt() <= request.getMaxRetries()) {
                request.incrementAttempt();
                requestRepository.save(request);
                boolean blank = normalizedDigits.isBlank();
                String rePrompt =
                        blank
                                        || request.getInvalidPromptText() == null
                                        || request.getInvalidPromptText().isBlank()
                                ? request.getPromptText()
                                : request.getInvalidPromptText();
                return new WorkflowSyncTriggerResult(
                        true,
                        null,
                        new WorkflowSyncTriggerResult.PendingVoiceMenu(
                                request.getId().toString(),
                                rePrompt,
                                request.getNumDigits(),
                                request.getFinishOnKey(),
                                request.getTimeoutSeconds()));
            }
            branch = normalizedDigits.isBlank() ? TIMEOUT_BRANCH : NO_MATCH_BRANCH;
        }

        int claimed =
                runRepository.compareAndSetStatus(
                        run.getId(),
                        WorkflowRunStatus.AWAITING_VOICE_INPUT,
                        WorkflowRunStatus.RUNNING);
        if (claimed == 0) {
            throw new WorkflowVoiceMenuRequestStateException(
                    "Workflow run "
                            + run.getId()
                            + " was already claimed by a concurrent DTMF collection.");
        }
        request.markResolved(normalizedDigits, branch);
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
        // Re-fetch after the CAS - same reason WorkflowInputRequestService does.
        WorkflowRun claimedRun = runRepository.findById(run.getId()).orElseThrow();

        Map<String, Object> resumeEnvelope = new LinkedHashMap<>();
        resumeEnvelope.put("digits", normalizedDigits);
        resumeEnvelope.put("branch", branch);
        resumeEnvelope.put("input", request.getNodeInput() == null ? "" : request.getNodeInput());
        String resumeInput;
        try {
            resumeInput = objectMapper.writeValueAsString(resumeEnvelope);
        } catch (Exception e) {
            throw new WorkflowVoiceMenuRequestStateException(
                    "Could not serialize the resume envelope.");
        }

        WorkflowRun resumedRun;
        ApprovalReplayGrant.grant(REPLAY_ACTION_TYPE);
        try {
            resumedRun =
                    executionEngine
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

        return classifyResumedRun(resumedRun, submittedBy);
    }

    /**
     * A live call can only ever service a run that either finishes or halts on ANOTHER {@code
     * voice_menu} node - anything else (a downstream Approval/Human-Input node, most plausibly) is
     * a halt this ONE call cannot wait out. Same "cannot pause mid-conversation for a human to
     * click approve" limitation {@code VoiceRelayWebSocketHandler}'s javadoc already documents for
     * the Conversation Relay path, restated here for the classic-TwiML CALL-trigger path this class
     * serves instead.
     */
    private WorkflowSyncTriggerResult classifyResumedRun(
            WorkflowRun resumedRun, String submittedBy) {
        if (resumedRun.getStatus() == WorkflowRunStatus.AWAITING_VOICE_INPUT) {
            return pendingResultForRun(resumedRun.getId());
        }
        if (resumedRun.getStatus() == WorkflowRunStatus.AWAITING_APPROVAL
                || resumedRun.getStatus() == WorkflowRunStatus.AWAITING_INPUT) {
            log.info(
                    "Voice menu resume for run {} halted on a non-voice halt ({}) - a live call cannot wait for it.",
                    resumedRun.getId(),
                    resumedRun.getStatus());
            return new WorkflowSyncTriggerResult(
                    false,
                    "Sorry, this needs someone to review before I can continue, and I can't wait on the line for that. Goodbye.");
        }
        boolean succeeded = resumedRun.getStatus() == WorkflowRunStatus.COMPLETED;
        String outputText =
                resumedRun.getErrorMessage() != null && !resumedRun.getErrorMessage().isBlank()
                        ? resumedRun.getErrorMessage()
                        : lastStepSummary(resumedRun);
        return new WorkflowSyncTriggerResult(succeeded, outputText);
    }

    private static WorkflowSyncTriggerResult toPendingResult(WorkflowVoiceMenuRequest request) {
        return new WorkflowSyncTriggerResult(
                true,
                null,
                new WorkflowSyncTriggerResult.PendingVoiceMenu(
                        request.getId().toString(),
                        request.getPromptText(),
                        request.getNumDigits(),
                        request.getFinishOnKey(),
                        request.getTimeoutSeconds()));
    }

    /**
     * Exact-match digit lookup against the halt-time {@code branches} snapshot ({@code [{digit,
     * label}]}), tolerant of a malformed/empty snapshot the same "never fail the call over bad
     * config" way {@link #readTraceTolerantly} is tolerant of a malformed trace. Returns {@code
     * null} for "no configured branch matches" - the caller decides whether that means retry or
     * fall through to the implicit timeout/no_match branch.
     */
    private String matchBranch(String branchesJson, String digits) {
        if (digits.isBlank() || branchesJson == null || branchesJson.isBlank()) {
            return null;
        }
        try {
            JsonNode branches = objectMapper.readTree(branchesJson);
            if (!branches.isArray()) {
                return null;
            }
            for (JsonNode entry : branches) {
                JsonNode digitNode = entry.get("digit");
                JsonNode labelNode = entry.get("label");
                if (digitNode != null
                        && digits.equals(digitNode.asString())
                        && labelNode != null
                        && !labelNode.asString().isBlank()) {
                    return labelNode.asString();
                }
            }
        } catch (Exception e) {
            log.warn(
                    "Voice menu request has an unreadable branches snapshot - treating every digit as unmatched.",
                    e);
        }
        return null;
    }

    private String writeBranchesJson(Object branches) {
        try {
            return objectMapper.writeValueAsString(branches == null ? List.of() : branches);
        } catch (Exception e) {
            return "[]";
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
     * Tolerant for the same reason WorkflowInputRequestService's is - the trace is history, not
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

    private String lastStepSummary(WorkflowRun run) {
        List<NodeTraceEntry> trace = readTraceTolerantly(run);
        if (trace.isEmpty()) {
            return "This workflow ran but produced no output.";
        }
        NodeTraceEntry last = trace.get(trace.size() - 1);
        return last.summary() != null && !last.summary().isBlank()
                ? last.summary()
                : "This workflow ran but produced no output.";
    }

    private static String stringConfig(
            Map<String, Object> config, String key, String defaultValue) {
        Object raw = config.get(key);
        if (raw == null || raw.toString().isBlank()) {
            return defaultValue;
        }
        return raw.toString();
    }

    private static int intConfig(Map<String, Object> config, String key, int defaultValue) {
        Object raw = config.get(key);
        return raw instanceof Number n ? n.intValue() : defaultValue;
    }

    private static Integer intConfigOrNull(Map<String, Object> config, String key) {
        Object raw = config.get(key);
        return raw instanceof Number n ? n.intValue() : null;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(value, max));
    }
}
