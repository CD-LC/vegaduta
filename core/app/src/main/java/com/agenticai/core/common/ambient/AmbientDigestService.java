package com.agenticai.core.common.ambient;

import com.agenticai.core.agents.core.AgentLookupService;
import com.agenticai.core.agents.job.AgentJobQueryService;
import com.agenticai.core.agents.job.AgentJob;
import com.agenticai.core.agents.job.schedule.AgentSchedule;
import com.agenticai.core.agents.job.schedule.AgentScheduleRun;
import com.agenticai.core.approval.ApprovalQueryService;
import com.agenticai.core.approval.ApprovalRequest;
import com.agenticai.core.approval.dto.ApprovalRequestResponse;
import com.agenticai.core.common.ambient.AmbientDigestController.JobsInWindow;
import com.agenticai.core.common.ambient.AmbientDigestController.PendingApprovals;
import com.agenticai.core.common.ambient.AmbientDigestController.SentinelOutcomeItem;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/**
 * Repository-backed reads for {@link AmbientDigestController}. Each section degrades to an empty
 * value on any failure, so one broken table never blanks the whole digest. Deliberately not
 * transactional: that matches the previous controller-level behavior (no surrounding transaction
 * that a swallowed exception could mark rollback-only).
 */
@Service
public class AmbientDigestService {

    private static final int MAX_PENDING_ITEMS = 10;

    private final AgentJobQueryService jobQueries;
    private final AgentLookupService agentLookup;
    private final ApprovalQueryService approvalQueries;

    public AmbientDigestService(
        AgentJobQueryService jobQueries,
        AgentLookupService agentLookup,
        ApprovalQueryService approvalQueries
    ) {
        this.jobQueries = jobQueries;
        this.agentLookup = agentLookup;
        this.approvalQueries = approvalQueries;
    }

    public long sentinelScheduleCount() {
        try {
            return jobQueries.sentinelScheduleCount();
        } catch (RuntimeException e) {
            return 0;
        }
    }

    public PendingApprovals pendingApprovals() {
        try {
            List<ApprovalRequest> pending =
                approvalQueries.pending();
            return new PendingApprovals(pending.size(), pending.stream()
                .limit(MAX_PENDING_ITEMS)
                .map(ApprovalRequestResponse::from)
                .toList());
        } catch (RuntimeException e) {
            return new PendingApprovals(0, List.of());
        }
    }

    public List<SentinelOutcomeItem> sentinelOutcomes(Instant since) {
        try {
            List<AgentJobQueryService.SentinelRun> runs = jobQueries.recentSentinelRuns(since);
            if (runs.isEmpty()) {
                return List.of();
            }
            Map<UUID, String> agentNames = agentLookup.namesByIds(runs.stream()
                .map(AgentJobQueryService.SentinelRun::schedule)
                .filter(java.util.Objects::nonNull)
                .map(AgentSchedule::getAgentId).distinct().toList());
            return runs.stream().map(sr -> {
                AgentScheduleRun run = sr.run();
                AgentSchedule schedule = sr.schedule();
                return new SentinelOutcomeItem(
                    run.getId(), run.getScheduleId(),
                    schedule == null ? null : schedule.getAgentId(),
                    schedule == null ? null : agentNames.get(schedule.getAgentId()),
                    run.getSentinelOutcome(), run.getSentinelOutcomeNote(),
                    run.getJobId(), run.getStartedAt());
            }).toList();
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    public JobsInWindow jobsInWindow(Instant since) {
        try {
            List<AgentJob> jobs = jobQueries.recentJobs(since);
            Map<String, Long> byStatus = jobs.stream()
                .collect(Collectors.groupingBy(j -> j.getStatus().name(), Collectors.counting()));
            return new JobsInWindow(jobs.size(), jobs.size() == 50, byStatus);
        } catch (RuntimeException e) {
            return new JobsInWindow(0, false, Map.of());
        }
    }
}
