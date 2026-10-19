package com.agenticai.core.agents.job.ownership;

import com.agenticai.core.agents.job.AgentJobDurabilityProperties;
import com.agenticai.core.agents.job.AgentJobProperties;
import com.agenticai.core.agents.job.ownership.JobLeaseStore.LeasedJob;
import com.agenticai.core.agents.job.ownership.JobResumePlanner.Facts;
import com.agenticai.core.agents.job.ownership.JobResumePlanner.TurnState;
import com.agenticai.core.agents.tools.StepJournal;
import com.agenticai.core.agents.tools.ToolCallLogRepository;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Reads the step journal and tool log for {@link JobResumePlanner}. Runs inside the sweep's tenant
 * context (the iterator sets it), so every read is routed to the job's own schema; it never sets or
 * clears the context itself. Every lookup failure is surfaced as "unknown" (a null count, an
 * in-flight flag of {@code true}), which the planner turns into a refusal to resume, never into a
 * resume.
 */
@Component
public class JobResumeFactsReader {

    private static final Logger log = LoggerFactory.getLogger(JobResumeFactsReader.class);

    private final ObjectProvider<StepJournal> journalProvider;
    private final ToolCallLogRepository toolCallLogRepository;
    private final AgentJobProperties properties;

    public JobResumeFactsReader(
            ObjectProvider<StepJournal> journalProvider,
            ToolCallLogRepository toolCallLogRepository,
            AgentJobProperties properties) {
        this.journalProvider = journalProvider;
        this.toolCallLogRepository = toolCallLogRepository;
        this.properties = properties;
    }

    public Facts read(LeasedJob job) {
        AgentJobDurabilityProperties durability = properties.getDurability();
        StepJournal journal = journalProvider.getIfAvailable();
        boolean usable =
                journal != null
                        && journal.isEnabled()
                        && durability.isStepJournalEnabled()
                        && durability.isResumeFromJournal();

        TurnState lastTurn = TurnState.NONE;
        boolean trackingActive = false;
        boolean incomplete = true;
        if (usable && job.turnsUsed() > 0) {
            Optional<StepJournal.Step> last =
                    journal.find(
                            StepJournal.runIdForJob(job.id()),
                            StepJournal.turnStepId(job.turnsUsed() - 1));
            if (last.isPresent()) {
                StepJournal.Step step = last.get();
                if (step.completedWithResult()) {
                    lastTurn = TurnState.COMPLETED_WITH_RESULT;
                } else if (step.completed()) {
                    lastTurn = TurnState.COMPLETED_BLANK;
                } else {
                    lastTurn = TurnState.STARTED;
                }
            }
            trackingActive = journal.isDurableCallTrackingActive();
            incomplete = journal.hasIncompleteDurableCall(job.id());
        }

        Long toolCalls = null;
        try {
            toolCalls =
                    toolCallLogRepository.countByAgentIdAndSessionId(
                            job.agentId(), job.sessionId());
        } catch (RuntimeException lookupFailed) {
            log.warn(
                    "Could not count tool calls for agent job {} (session '{}'); treating zero work as unprovable",
                    job.id(),
                    job.sessionId(),
                    lookupFailed);
        }
        return new Facts(usable, lastTurn, trackingActive, incomplete, toolCalls);
    }
}
