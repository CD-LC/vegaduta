package com.agenticai.core.common.ambient;

import com.agenticai.core.approval.dto.ApprovalRequestResponse;
import com.agenticai.core.audit.NotificationCategory;
import com.agenticai.core.audit.NotificationService;
import com.agenticai.core.audit.dto.NotificationResponse;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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

    private final AmbientDigestService digestService;
    private final NotificationService notificationService;

    public AmbientDigestController(
        AmbientDigestService digestService,
        NotificationService notificationService
    ) {
        this.digestService = digestService;
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
            digestService.sentinelScheduleCount(),
            digestService.pendingApprovals(),
            digestService.sentinelOutcomes(since),
            digestService.jobsInWindow(since),
            agentNotifications(authentication, since));
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
