package com.agenticai.core.agents.job.approval;

import com.agenticai.core.agents.job.AgentJob;
import com.agenticai.core.agents.job.AgentJobApprovalResumption;
import com.agenticai.core.agents.job.AgentJobSession;
import com.agenticai.core.agents.turn.ApprovalContinuation;
import com.agenticai.core.agents.turn.ApprovalContinuation.Outcome;
import com.agenticai.core.agents.turn.approval.ApprovalContinuationClaims;
import com.agenticai.core.agents.turn.approval.ApprovalContinuationFactory;
import com.agenticai.core.agents.turn.ApprovalContinuationProperties;
import com.agenticai.core.agents.turn.approval.ApprovalContinuationPrompt;
import com.agenticai.core.settings.SettingsResolver;
import com.agenticai.core.approval.ApprovalRequest;
import com.agenticai.core.approval.ApprovalStatus;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * What a RESUMED agent job is asked: the approval decisions it was parked on, delivered with the
 * same continuation builder a chat session's server-started turn uses ({@link
 * ApprovalContinuationPrompt}), so there is one mechanism for "a human decided, carry on" and not
 * two.
 *
 * <p>The approval sweep resumes a parked job by dispatching an ordinary turn. Before this class that
 * turn re-sent the job's goal and relied on a 500-character note in the history for the result of
 * the approved tool. Now the first turn after a park is the continuation message: the job's goal
 * restated, the approved tool's result in full (up to {@code
 * app.approvals.continuation.max-result-chars}), a failure or a denial as such, and the pipeline
 * selects tools for the goal and keeps the ones the job already used on offer.
 *
 * <p>Each decision is delivered once: the same one-shot claim the chat path uses
 * ({@link ApprovalContinuationClaims}) is taken per approval request, so a job resumed twice (a
 * retry, an orphan re-queue) is not told the same result twice. Anything that cannot be built - a
 * request recorded without a goal, Redis unavailable - yields no continuation, and the job resumes
 * the way it did before: the goal again, with the history note.
 */
@Component
public class AgentJobApprovalResume implements AgentJobApprovalResumption {

    private static final Logger log = LoggerFactory.getLogger(AgentJobApprovalResume.class);

    /** Most recent requests of a job considered, the bound {@code AgentJobApprovalLookup} offers. */
    private final AgentJobApprovalLookup approvals;

    private final ApprovalContinuationFactory factory;
    private final ApprovalContinuationPrompt prompt;
    private final ApprovalContinuationClaims claims;
    private final ApprovalContinuationProperties properties;

    public AgentJobApprovalResume(
            AgentJobApprovalLookup approvals,
            ApprovalContinuationFactory factory,
            ApprovalContinuationPrompt prompt,
            ApprovalContinuationClaims claims,
            ApprovalContinuationProperties properties) {
        this.approvals = approvals;
        this.factory = factory;
        this.prompt = prompt;
        this.claims = claims;
        this.properties = properties;
    }

    /** The settings catalog; optional: absent, {@code app.approvals.continuation.enabled} decides for every job. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setSettingsResolver(SettingsResolver settings) {
        this.settings = settings;
    }

    private volatile SettingsResolver settings;

    /**
     * @param goal the job's goal as the model is normally asked it (the output contract's effective
     *     goal), restated in the continuation
     * @return empty when no decided, not-yet-delivered approval can be turned into a continuation
     */
    @Override
    public Optional<ResumeTurn> resumeFor(AgentJob job, String goal) {
        if (!ApprovalContinuationSettings.enabled(properties, settings, job.getAgentId())) {
            return Optional.empty();
        }
        try {
            String label = AgentJobSession.requestedByLabel(job.getAgentId(), job.getId());
            List<ApprovalRequest> decided =
                    new ArrayList<>(approvals.findTop20ByRequestedByOrderByCreatedAtDesc(label));
            decided.sort(Comparator.comparing(ApprovalRequest::getCreatedAt));
            List<ApprovalContinuation> delivered = new ArrayList<>();
            for (ApprovalRequest request : decided) {
                Optional<ApprovalContinuation> continuation = decision(request);
                if (continuation.isPresent() && claims.tryClaim(request.getId())) {
                    delivered.add(continuation.get().withGoal(goal));
                }
            }
            if (delivered.isEmpty()) {
                return Optional.empty();
            }
            StringBuilder message = new StringBuilder();
            Set<String> sticky = new LinkedHashSet<>();
            for (ApprovalContinuation c : delivered) {
                if (message.length() > 0) {
                    message.append("\n\n");
                }
                message.append(prompt.render(c));
                sticky.addAll(c.stickyTools());
            }
            ApprovalContinuation last = delivered.get(delivered.size() - 1);
            return Optional.of(
                    new ResumeTurn(
                            message.toString(),
                            new ApprovalContinuation(
                                    last.requestId(),
                                    last.outcome(),
                                    last.toolName(),
                                    last.argumentsJson(),
                                    last.result(),
                                    last.decidedBy(),
                                    goal,
                                    last.requestedAgentId(),
                                    last.sessionId(),
                                    last.subjectKey(),
                                    last.tenantId(),
                                    last.subject(),
                                    last.memorySessionId(),
                                    List.copyOf(sticky),
                                    last.otherPending())));
        } catch (RuntimeException e) {
            log.warn(
                    "Could not build the approval continuation for resumed agent job {}; it resumes with its goal and the"
                            + " history note",
                    job.getId(),
                    e);
            return Optional.empty();
        }
    }

    /** The outcome a decided request stands for, or empty for one still waiting or in flight. */
    private Optional<ApprovalContinuation> decision(ApprovalRequest request) {
        ApprovalStatus status = request.getStatus();
        if (status == null) {
            return Optional.empty();
        }
        return switch (status) {
            case APPROVED -> request.getExecutionResult() != null
                    ? factory.build(request, Outcome.COMPLETED, request.getExecutionResult())
                    : factory.build(
                            request,
                            Outcome.FAILED,
                            properties.getResultNotRecordedText());
            case EXECUTION_FAILED, DEAD_LETTERED -> request.getNextRetryAt() == null
                    ? factory.build(request, Outcome.FAILED, nullToEmpty(request.getExecutionError()))
                    : Optional.empty();
            case REJECTED -> factory.build(
                    request,
                    Outcome.DENIED,
                    request.getRejectionReason() == null || request.getRejectionReason().isBlank()
                            ? properties.getNoReasonText()
                            : request.getRejectionReason());
            default -> Optional.empty();
        };
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
