package com.agenticai.core.agents.learning.routines;

import com.agenticai.core.agents.learning.LearningProposal;
import com.agenticai.core.agents.learning.LearningProposalApplier;
import com.agenticai.core.agents.learning.LearningProposalApplyException;
import com.agenticai.core.agents.learning.LearningProposalKind;
import com.agenticai.core.agents.scheduledtask.ScheduledTaskDtos.TaskRequest;
import com.agenticai.core.agents.scheduledtask.ScheduledTaskDtos.TaskResponse;
import com.agenticai.core.agents.scheduledtask.ScheduledTaskService;
import com.agenticai.core.json.JsonMappers;
import com.agenticai.core.security.TenantLimitExceededException;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Locale;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * Approved ROUTINE: creates the scheduled task through {@link ScheduledTaskService#create}, the
 * same path the console form and the {@code schedule_task} tool use, so every rule applies (the
 * agent must be usable by the approver, the plan's task cap, the minimum interval, unattended-safe
 * autonomy). In-app delivery.
 *
 * <p>The proposal body reads {@code "<schedule in words>.\nTask: <prompt>"}. If the person edited
 * it, the text after {@code Task:} (or, with no marker, the whole edit) becomes the prompt; the
 * schedule is the proposal's own and is changed afterwards on the Scheduled page. Runs on the
 * approving person's request thread; keeps no state.
 */
@Component
@ConditionalOnProperty(
        name = {"app.learning.proposals.enabled", "app.learning.routines.enabled"},
        havingValue = "true")
public class RoutineApplier implements LearningProposalApplier {

    private static final Logger log = LoggerFactory.getLogger(RoutineApplier.class);

    static final String TASK_MARKER = "Task:";
    private static final int NAME_MAX = 80;

    private final ScheduledTaskService scheduledTasks;

    public RoutineApplier(ScheduledTaskService scheduledTasks) {
        this.scheduledTasks = scheduledTasks;
    }

    @Override
    public LearningProposalKind kind() {
        return LearningProposalKind.ROUTINE;
    }

    @Override
    public void apply(LearningProposal proposal, String text)
            throws LearningProposalApplyException {
        JsonNode payload;
        try {
            payload = JsonMappers.plain().readTree(proposal.getPayload());
        } catch (Exception e) {
            throw new LearningProposalApplyException("This routine has no usable schedule.");
        }
        UUID agentId = agentOf(proposal, payload);
        String frequency = payload.path("frequency").asText("");
        String time = payload.path("time").asText("");
        if (frequency.isBlank() || time.isBlank()) {
            throw new LearningProposalApplyException("This routine has no usable schedule.");
        }
        String prompt = promptOf(proposal, payload, text);
        if (prompt.length() < 3) {
            throw new LearningProposalApplyException("Say what the routine should do.");
        }
        String day = payload.path("day").asText(null);
        String timezone = payload.path("timezone").asText(null);
        try {
            for (TaskResponse existing : scheduledTasks.list()) {
                if (agentId.equals(existing.agentId())
                        && RoutineDetector.jaccard(
                                        RoutineDetector.tokens(prompt),
                                        RoutineDetector.tokens(existing.prompt()))
                                >= RoutineLearningService.SAME_AS_EXISTING) {
                    throw new LearningProposalApplyException(
                            "You already have a scheduled task for this (\""
                                    + existing.name()
                                    + "\").");
                }
            }
            scheduledTasks.create(
                    new TaskRequest(
                            agentId,
                            nameOf(prompt),
                            prompt,
                            frequency,
                            time,
                            day,
                            null,
                            timezone,
                            "in_app",
                            null,
                            null,
                            null));
        } catch (LearningProposalApplyException e) {
            throw e;
        } catch (IllegalArgumentException | TenantLimitExceededException e) {
            throw new LearningProposalApplyException(e.getMessage());
        } catch (ResponseStatusException e) {
            HttpStatusCode code = e.getStatusCode();
            throw new LearningProposalApplyException(
                    code.value() == 404
                            ? "Scheduled tasks are not enabled on this install."
                            : "Could not create the scheduled task.");
        } catch (RuntimeException e) {
            // Never leak internals to the person, but keep the cause in the log.
            log.warn("Could not apply routine proposal {}", proposal.getId(), e);
            throw new LearningProposalApplyException("That agent is no longer available to you.");
        }
    }

    private static UUID agentOf(LearningProposal proposal, JsonNode payload)
            throws LearningProposalApplyException {
        if (proposal.getAgentId() != null) {
            return proposal.getAgentId();
        }
        try {
            return UUID.fromString(payload.path("agentId").asText(""));
        } catch (RuntimeException e) {
            throw new LearningProposalApplyException(
                    "This routine does not say which agent should run it.");
        }
    }

    /**
     * The prompt to schedule: the stored one when the text is untouched, else what the person
     * wrote.
     */
    static String promptOf(LearningProposal proposal, JsonNode payload, String text) {
        String edited = text == null ? "" : text.strip();
        if (edited.equals(proposal.getBody() == null ? "" : proposal.getBody().strip())) {
            return payload.path("prompt").asText("").strip();
        }
        int at = edited.toLowerCase(Locale.ROOT).indexOf(TASK_MARKER.toLowerCase(Locale.ROOT));
        return (at >= 0 ? edited.substring(at + TASK_MARKER.length()) : edited).strip();
    }

    /** A short task name from the prompt: its first words, no trailing punctuation. */
    static String nameOf(String prompt) {
        String one = prompt == null ? "" : prompt.replaceAll("\\s+", " ").strip();
        if (one.length() > NAME_MAX) {
            one = one.substring(0, NAME_MAX).stripTrailing();
        }
        return one.replaceAll("[\\p{Punct}\\s]+$", "");
    }
}
