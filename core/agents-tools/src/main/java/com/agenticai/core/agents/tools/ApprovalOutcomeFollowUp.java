package com.agenticai.core.agents.tools;

import com.agenticai.core.agents.memory.SessionMemoryService;
import com.agenticai.core.agents.turn.ApprovalContinuation;
import com.agenticai.core.agents.turn.ApprovalContinuationPort;
import com.agenticai.core.agents.turn.ApprovalContinuationProperties;
import com.agenticai.core.agents.turn.ToolLedger;
import com.agenticai.core.approval.ApprovalOutcomePort;
import com.agenticai.core.approval.ApprovalRequest;
import com.agenticai.core.security.TenantContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Puts the end of ANY approved (or refused) action back into the conversation that asked for it, and
 * continues the task: the single path by which an action's executor - a code-workspace run, a
 * code-workspace apply, a channel send, whatever a tool gated itself on - resumes the agent without
 * knowing anything about conversations. {@code HumanApprovalGateService} calls it once the action ended.
 *
 * <p>It does what {@link AgentToolCallApprovalExecutor} does for a replayed tool call, with the same
 * pieces: the outcome note goes to the SCOPED history the conversation's next turn reads ({@link
 * ApprovalOutcomeNotes}), then {@link ApprovalContinuationPort} starts the turn that restates the user's
 * goal and hands the model the real result, the failure, or the denial (also for an expired request).
 * The continuation is claimed once per request, so a repeated decision starts nothing.
 *
 * <p>Only a request recorded from an agent turn is acted on: its replay envelope (see {@code
 * HumanApprovalGateService#evaluateInTurn}) carries the goal and the scoped history id. An action raised
 * from an admin screen, a webhook or a scheduler has neither, and is left alone - nobody is waiting for it.
 */
@Component
public class ApprovalOutcomeFollowUp implements ApprovalOutcomePort {

    private static final Logger log = LoggerFactory.getLogger(ApprovalOutcomeFollowUp.class);

    private final ObjectProvider<SessionMemoryService> sessionMemoryService;
    private final ObjectProvider<ApprovalContinuationPort> continuationLauncher;
    private final ObjectProvider<ApprovalContinuationProperties> texts;
    private final ObjectMapper objectMapper;

    public ApprovalOutcomeFollowUp(
            ObjectProvider<SessionMemoryService> sessionMemoryService,
            ObjectProvider<ApprovalContinuationPort> continuationLauncher,
            ObjectProvider<ApprovalContinuationProperties> texts,
            ObjectMapper objectMapper) {
        this.sessionMemoryService = sessionMemoryService;
        this.continuationLauncher = continuationLauncher;
        this.texts = texts;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean afterDecision(ApprovalRequest request, Outcome outcome, String text) {
        ReplayTarget target = conversationOf(request);
        if (target == null) {
            return false;
        }
        ApprovalContinuationProperties wording = texts.getIfAvailable(ApprovalContinuationProperties::new);
        Optional<TenantContext> previous = TenantContext.current();
        if (target.tenantId() != null) {
            TenantContext.set(TenantContext.of(target.tenantId(), target.replaySubject(), Set.of()));
        }
        try {
            String shown = shownText(wording, outcome, text);
            String problem =
                    ApprovalOutcomeNotes.append(
                            sessionMemoryService, request, target, note(wording, outcome, target, request, shown), log);
            if (problem != null) {
                log.warn(
                        "Approval request {}: the outcome note could not be written ({}); the task is still continued",
                        request.getId(),
                        problem);
            }
            return continueTask(request, outcome, shown);
        } finally {
            previous.ifPresentOrElse(TenantContext::set, TenantContext::clear);
        }
    }

    /**
     * The conversation the request came from, or {@code null} when it did not come from an agent turn
     * that recorded its goal and history id (then nothing is waiting for the outcome).
     */
    private ReplayTarget conversationOf(ApprovalRequest request) {
        String payload = request.getPayloadJson();
        if (payload == null || payload.isBlank()) {
            return null;
        }
        try {
            JsonNode replay = objectMapper.readTree(payload).path("replay");
            if (!replay.hasNonNull("toolName")) {
                return null;
            }
            ReplayTarget target = ReplayTarget.resolve(request, objectMapper);
            // The note goes wherever the replay would run the call. A request recorded without the
            // turn-path origin (goal + scoped memory id on the envelope) still resolves one - the
            // scoped id derives from the fields every request carries. Only when nothing identifies
            // a conversation (no envelope fields, no derivable id) is there nobody to tell.
            return target.memorySessionIdOrDerived() == null ? null : target;
        } catch (Exception e) {
            log.debug("Approval request {} carries no usable conversation envelope: {}", request.getId(), e.toString());
            return null;
        }
    }

    private static String shownText(ApprovalContinuationProperties wording, Outcome outcome, String text) {
        boolean blank = text == null || text.isBlank();
        return switch (outcome) {
            case COMPLETED -> blank ? wording.getCompletedNoOutputText() : text;
            case FAILED -> blank ? wording.getResultNotRecordedText() : text;
            case DENIED -> blank ? wording.getNoReasonText() : text.strip();
        };
    }

    private static ToolLedger note(
            ApprovalContinuationProperties wording,
            Outcome outcome,
            ReplayTarget target,
            ApprovalRequest request,
            String shown) {
        ToolLedger.Status status =
                switch (outcome) {
                    case COMPLETED -> ToolLedger.Status.OK;
                    case FAILED -> ToolLedger.Status.FAILED;
                    case DENIED -> ToolLedger.Status.NOT_RUN;
                };
        return ApprovalOutcomeNotes.outcomeRecord(wording, request, target, status, request.getDecidedBy(), shown);
    }

    private boolean continueTask(ApprovalRequest request, Outcome outcome, String shown) {
        try {
            ApprovalContinuationPort launcher = continuationLauncher.getIfAvailable();
            if (launcher == null) {
                return false;
            }
            return launcher.afterDecision(request, ApprovalContinuation.Outcome.valueOf(outcome.name()), shown);
        } catch (RuntimeException e) {
            log.warn(
                    "Could not continue the task after the {} decision on approval request {}",
                    outcome,
                    request.getId(),
                    e);
            return false;
        }
    }
}
