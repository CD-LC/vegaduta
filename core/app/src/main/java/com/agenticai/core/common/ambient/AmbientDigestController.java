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
import com.agenticai.core.audit.NotificationCategory;
import com.agenticai.core.audit.NotificationService;
import com.agenticai.core.audit.dto.NotificationResponse;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/ambient/digest} - the "while you were away" read that backs
 * the ambient-agent surface. One bounded aggregate over tables that already
 * exist, so opening the console is a single call instead of four:
 *
 * <ul>
 *   <li><b>Pending approvals</b> - everything waiting for a human decision,
 *       {@code agent.proposal} sentinel proposals included. Same rows {@code
 *       GET /api/approvals} already serves to any authenticated tenant member;
 *       the digest adds no new visibility, only a second place to see it.</li>
 *   <li><b>Sentinel outcomes</b> - judged watch runs since {@code sinceHours},
 *       with schedule and agent names resolved, so "the watcher noticed X" is
 *       attributable.</li>
 *   <li><b>Jobs in window</b> - status tallies over the newest 50 jobs in the
 *       window: the ambient headline is "N runs finished, M failed", not a
 *       job dump.</li>
 *   <li><b>Agent notifications</b> - AGENT-category bell items since
 *       {@code sinceHours}, narrowed by the same visibility rules {@code
 *       GET /api/notifications} applies.</li>
 * </ul>
 *
 * <p>Read-only and best-effort per section: a section that fails reads is
 * returned empty rather than failing the whole digest - a half-loaded "while
 * you were away" card beats none, and every section's own surface still
 * carries the full truth. Falls under {@code SecurityConfig}'s default
 * authenticated rule, same as {@code NotificationController}; a tenantless
 * platform-admin gets an empty digest because every table read here is
 * tenant-schema-only.
 */
@RestController
@RequestMapping("/api/ambient")
public class AmbientDigestController {

    private static final String PERM_PREFIX = "PERM_";
    private static final String PLATFORM_ADMIN_AUTHORITY = "ROLE_platform-admin";

    private static final int DEFAULT_SINCE_HOURS = 24;
    private static final int MAX_SINCE_HOURS = 24 * 14;
    private static final int MAX_PENDING_ITEMS = 10;

    private final AgentScheduleRepository scheduleRepository;
    private final AgentScheduleRunRepository scheduleRunRepository;
    private final AgentJobRepository jobRepository;
    private final AgentRepository agentRepository;
    private final ApprovalRequestRepository approvalRepository;
    private final NotificationService notificationService;

    public AmbientDigestController(
        AgentScheduleRepository scheduleRepository,
        AgentScheduleRunRepository scheduleRunRepository,
        AgentJobRepository jobRepository,
        AgentRepository agentRepository,
        ApprovalRequestRepository approvalRepository,
        NotificationService notificationService
    ) {
        this.scheduleRepository = scheduleRepository;
        this.scheduleRunRepository = scheduleRunRepository;
        this.jobRepository = jobRepository;
        this.agentRepository = agentRepository;
        this.approvalRepository = approvalRepository;
        this.notificationService = notificationService;
    }

    @GetMapping("/digest")
    public AmbientDigestResponse digest(
        @RequestParam(required = false, defaultValue = "" + DEFAULT_SINCE_HOURS) int sinceHours,
        Authentication authentication
    ) {
        if (isTenantlessPlatformAdmin(authentication)) {
            return AmbientDigestResponse.empty(Instant.now());
        }
        int boundedHours = Math.max(1, Math.min(sinceHours, MAX_SINCE_HOURS));
        Instant since = Instant.now().minus(boundedHours, ChronoUnit.HOURS);
        return new AmbientDigestResponse(
            since,
            sentinelScheduleCount(),
            pendingApprovals(),
            sentinelOutcomes(since),
            jobsInWindow(since),
            agentNotifications(authentication, since));
    }

    private long sentinelScheduleCount() {
        try {
            return scheduleRepository.countBySentinelEnabledTrue();
        } catch (RuntimeException e) {
            return 0;
        }
    }

    private PendingApprovals pendingApprovals() {
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

    private List<SentinelOutcomeItem> sentinelOutcomes(Instant since) {
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

    private JobsInWindow jobsInWindow(Instant since) {
        try {
            List<AgentJob> jobs = jobRepository.findTop50ByCreatedAtGreaterThanEqualOrderByCreatedAtDesc(since);
            Map<String, Long> byStatus = jobs.stream()
                .collect(Collectors.groupingBy(j -> j.getStatus().name(), Collectors.counting()));
            return new JobsInWindow(jobs.size(), jobs.size() == 50, byStatus);
        } catch (RuntimeException e) {
            return new JobsInWindow(0, false, Map.of());
        }
    }

    private List<NotificationResponse> agentNotifications(Authentication authentication, Instant since) {
        try {
            return notificationService
                .listVisible(userId(authentication), isTenantAdmin(authentication), false, PageRequest.of(0, 50))
                .stream()
                .filter(n -> n.getCategory() == NotificationCategory.AGENT)
                .filter(n -> n.getCreatedAt() != null && !n.getCreatedAt().isBefore(since))
                .map(NotificationResponse::from)
                .toList();
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    // Same derivation as NotificationController's (isTenantlessPlatformAdmin /
    // userId / isTenantAdmin) - re-implemented rather than imported because
    // these three helpers are private there, and the rules are three lines of
    // authority-prefix matching, not a shared component.
    private boolean isTenantlessPlatformAdmin(Authentication authentication) {
        if (authentication == null) {
            return false;
        }
        boolean platformAdmin = false;
        for (GrantedAuthority granted : authentication.getAuthorities()) {
            String a = granted.getAuthority();
            if (a != null && a.startsWith(PERM_PREFIX)) {
                return false;
            }
            platformAdmin |= PLATFORM_ADMIN_AUTHORITY.equals(a);
        }
        return platformAdmin;
    }

    private String userId(Authentication authentication) {
        return authentication != null ? authentication.getName() : null;
    }

    private boolean isTenantAdmin(Authentication authentication) {
        return authentication != null && authentication.getAuthorities().stream()
            .map(GrantedAuthority::getAuthority)
            .anyMatch(a -> a != null && (a.startsWith(PERM_PREFIX) || PLATFORM_ADMIN_AUTHORITY.equals(a)));
    }

    public record AmbientDigestResponse(
        Instant since,
        long sentinelScheduleCount,
        PendingApprovals pendingApprovals,
        List<SentinelOutcomeItem> sentinelOutcomes,
        JobsInWindow jobs,
        List<NotificationResponse> agentNotifications
    ) {
        static AmbientDigestResponse empty(Instant since) {
            return new AmbientDigestResponse(
                since, 0, new PendingApprovals(0, List.of()), List.of(),
                new JobsInWindow(0, false, Map.of()), List.of());
        }
    }

    /** @param total all pending rows; {@code items} is the newest {@value #MAX_PENDING_ITEMS} of them. */
    public record PendingApprovals(int total, List<ApprovalRequestResponse> items) {}

    public record SentinelOutcomeItem(
        UUID runId,
        UUID scheduleId,
        UUID agentId,
        String agentName,
        String outcome,
        String outcomeNote,
        UUID jobId,
        Instant startedAt
    ) {}

    public record JobsInWindow(int observed, boolean truncated, Map<String, Long> byStatus) {}
}
