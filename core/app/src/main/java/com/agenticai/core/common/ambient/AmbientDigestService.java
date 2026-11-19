package com.agenticai.core.common.ambient;

import com.agenticai.core.agents.core.Agent;
import com.agenticai.core.agents.core.AgentRepository;
import com.agenticai.core.agents.job.AgentJob;
import com.agenticai.core.agents.job.AgentJobRepository;
import com.agenticai.core.agents.job.schedule.AgentSchedule;
import com.agenticai.core.agents.job.schedule.AgentScheduleRepository;
import com.agenticai.core.agents.job.schedule.AgentScheduleRun;
import com.agenticai.core.agents.job.schedule.AgentScheduleRunRepository;
import com.agenticai.core.approval.ApprovalRequest;
import com.agenticai.core.approval.ApprovalRequestRepository;
import com.agenticai.core.approval.ApprovalStatus;
import com.agenticai.core.approval.dto.ApprovalRequestResponse;
import com.agenticai.core.common.ambient.AmbientDigestController.JobsInWindow;
import com.agenticai.core.common.ambient.AmbientDigestController.PendingApprovals;
import com.agenticai.core.common.ambient.AmbientDigestController.SentinelOutcomeItem;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
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

    private final AgentScheduleRepository scheduleRepository;
    private final AgentScheduleRunRepository scheduleRunRepository;
    private final AgentJobRepository jobRepository;
    private final AgentRepository agentRepository;
    private final ApprovalRequestRepository approvalRepository;

    public AmbientDigestService(
        AgentScheduleRepository scheduleRepository,
        AgentScheduleRunRepository scheduleRunRepository,
        AgentJobRepository jobRepository,
        AgentRepository agentRepository,
        ApprovalRequestRepository approvalRepository
    ) {
        this.scheduleRepository = scheduleRepository;
        this.scheduleRunRepository = scheduleRunRepository;
        this.jobRepository = jobRepository;
        this.agentRepository = agentRepository;
        this.approvalRepository = approvalRepository;
    }

    public long sentinelScheduleCount() {
        try {
            return scheduleRepository.countBySentinelEnabledTrue();
        } catch (RuntimeException e) {
            return 0;
        }
    }

    public PendingApprovals pendingApprovals() {
        try {
            List<ApprovalRequest> pending =
                approvalRepository.findByStatusOrderByCreatedAtDesc(ApprovalStatus.PENDING);
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
            List<AgentScheduleRun> runs = scheduleRunRepository
                .findTop50BySentinelOutcomeIsNotNullAndStartedAtGreaterThanEqualOrderByStartedAtDesc(since);
            if (runs.isEmpty()) {
                return List.of();
            }
            Map<UUID, AgentSchedule> schedules = scheduleRepository
                .findAllById(runs.stream().map(AgentScheduleRun::getScheduleId).distinct().toList())
                .stream().collect(Collectors.toMap(AgentSchedule::getId, Function.identity()));
            Map<UUID, String> agentNames = agentRepository
                .findAllById(schedules.values().stream().map(AgentSchedule::getAgentId).distinct().toList())
                .stream().collect(Collectors.toMap(Agent::getId, a -> a.getName() == null ? "agent" : a.getName()));
            return runs.stream().map(run -> {
                AgentSchedule schedule = schedules.get(run.getScheduleId());
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
            List<AgentJob> jobs = jobRepository.findTop50ByCreatedAtGreaterThanEqualOrderByCreatedAtDesc(since);
            Map<String, Long> byStatus = jobs.stream()
                .collect(Collectors.groupingBy(j -> j.getStatus().name(), Collectors.counting()));
            return new JobsInWindow(jobs.size(), jobs.size() == 50, byStatus);
        } catch (RuntimeException e) {
            return new JobsInWindow(0, false, Map.of());
        }
    }
}
