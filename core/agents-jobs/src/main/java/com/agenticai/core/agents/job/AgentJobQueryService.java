package com.agenticai.core.agents.job;

import com.agenticai.core.agents.job.schedule.AgentSchedule;
import com.agenticai.core.agents.job.schedule.AgentScheduleRepository;
import com.agenticai.core.agents.job.schedule.AgentScheduleRun;
import com.agenticai.core.agents.job.schedule.AgentScheduleRunRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read-only job and sentinel-schedule queries for other feature areas (ambient digest), so they do
 * not use the job and schedule repositories directly.
 */
@Service
public class AgentJobQueryService {

    /** A sentinel schedule run with its schedule (null if the schedule was deleted). */
    public record SentinelRun(AgentScheduleRun run, AgentSchedule schedule) {}

    private final AgentJobRepository jobRepository;
    private final AgentScheduleRepository scheduleRepository;
    private final AgentScheduleRunRepository scheduleRunRepository;

    public AgentJobQueryService(
            AgentJobRepository jobRepository,
            AgentScheduleRepository scheduleRepository,
            AgentScheduleRunRepository scheduleRunRepository) {
        this.jobRepository = jobRepository;
        this.scheduleRepository = scheduleRepository;
        this.scheduleRunRepository = scheduleRunRepository;
    }

    @Transactional(readOnly = true)
    public long sentinelScheduleCount() {
        return scheduleRepository.countBySentinelEnabledTrue();
    }

    /** Up to 50 most recent runs since {@code since} that recorded a sentinel outcome. */
    @Transactional(readOnly = true)
    public List<SentinelRun> recentSentinelRuns(Instant since) {
        List<AgentScheduleRun> runs =
                scheduleRunRepository
                        .findTop50BySentinelOutcomeIsNotNullAndStartedAtGreaterThanEqualOrderByStartedAtDesc(
                                since);
        if (runs.isEmpty()) {
            return List.of();
        }
        Map<java.util.UUID, AgentSchedule> schedules =
                scheduleRepository
                        .findAllById(runs.stream().map(AgentScheduleRun::getScheduleId).distinct().toList())
                        .stream()
                        .collect(Collectors.toMap(AgentSchedule::getId, Function.identity()));
        return runs.stream().map(r -> new SentinelRun(r, schedules.get(r.getScheduleId()))).toList();
    }

    /** Up to 50 most recently created jobs since {@code since}. */
    @Transactional(readOnly = true)
    public List<AgentJob> recentJobs(Instant since) {
        return jobRepository.findTop50ByCreatedAtGreaterThanEqualOrderByCreatedAtDesc(since);
    }
}
