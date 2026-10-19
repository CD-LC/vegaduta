package com.agenticai.core.tenancy.aireport;

import com.agenticai.core.tenancy.OffsetLimitRequest;
import com.agenticai.core.security.TenantContext;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * In-app "Report AI content" (Google Play AI-Generated Content policy) and
 * the platform-admin review queue behind it. Contract:
 * the app-store compliance requirements (described inline).
 *
 * <p>Submitting: any signed-in user. Tenant id + reporter subject come from
 * {@link TenantContext} (never from the body). Free-text fields are truncated
 * to the contract's limits rather than rejected - the client is told to
 * truncate too, and a report must never bounce for being a few characters
 * long. Enum fields are validated (400). 30 reports / user / hour (429).
 *
 * <p>Reviewing: {@code ROLE_platform-admin} only. {@code SecurityConfig}
 * already gates {@code /api/platform/**} on that authority; the check here
 * is defence in depth so the service stays safe if it is ever called from
 * somewhere else.
 *
 * <p>Notification: there is no platform-admin in-app bell today (the
 * {@code notification} table is per tenant schema and platform admins are
 * tenant-less), so a new report is logged at INFO - ids and enums only, never
 * the reported content or prompt.
 */
@Service
public class AiContentReportService {

    private static final Logger log = LoggerFactory.getLogger(AiContentReportService.class);

    static final String PLATFORM_ADMIN = "ROLE_platform-admin";
    static final int MAX_REPORTS_PER_HOUR = 30;
    static final int MAX_COMMENT = 1000;
    static final int MAX_CONTENT = 8000;
    static final int MAX_PROMPT = 4000;
    static final int MAX_REF = 255;
    static final int MAX_AGENT_ID = 128;
    static final int MAX_APP_VERSION = 32;
    static final int MAX_REVIEW_NOTE = 2000;
    static final int DEFAULT_LIMIT = 50;
    static final int MAX_LIMIT = 200;

    static final Set<String> SURFACES = Set.of("CHAT", "QUICK_TASK", "ASSIST", "IME", "SENTINEL", "WORKFLOW");
    static final Set<String> REASONS = Set.of(
        "OFFENSIVE", "HATEFUL", "SEXUAL", "VIOLENT_OR_DANGEROUS", "SELF_HARM", "MISINFORMATION", "PRIVACY", "OTHER");
    static final Set<String> APP_PLATFORMS = Set.of("ANDROID", "IOS", "WEB");
    static final Set<String> REVIEW_STATUSES = Set.of(
        AiContentReportStatus.REVIEWED.name(), AiContentReportStatus.ACTIONED.name(), AiContentReportStatus.DISMISSED.name());

    private final AiContentReportRepository repository;

    public AiContentReportService(AiContentReportRepository repository) {
        this.repository = repository;
    }

    public AiContentReport submit(AiContentReportRequest request) {
        TenantContext ctx = TenantContext.current()
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sign in to report content."));
        String reporterId = ctx.getSubject();
        if (reporterId == null || reporterId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sign in to report content.");
        }
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Report body is required.");
        }
        String surface = requireEnum(request.surface(), SURFACES, "surface");
        String reason = requireEnum(request.reason(), REASONS, "reason");
        String appPlatform = optionalEnum(request.appPlatform(), APP_PLATFORMS, "appPlatform");
        String content = truncate(request.content(), MAX_CONTENT);
        if (content == null || content.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "content (the reported AI output) is required.");
        }

        long recent = repository.countByReporterIdAndCreatedAtAfter(reporterId, Instant.now().minus(Duration.ofHours(1)));
        if (recent >= MAX_REPORTS_PER_HOUR) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                "Too many reports in the last hour. Please try again later.");
        }

        AiContentReport saved = repository.save(new AiContentReport(
            ctx.getTenantId(),
            reporterId,
            surface,
            reason,
            blankToNull(truncate(request.comment(), MAX_COMMENT)),
            content,
            blankToNull(truncate(request.prompt(), MAX_PROMPT)),
            blankToNull(truncate(request.agentId(), MAX_AGENT_ID)),
            blankToNull(truncate(request.sessionId(), MAX_REF)),
            blankToNull(truncate(request.messageRef(), MAX_REF)),
            Boolean.TRUE.equals(request.onDevice()),
            appPlatform,
            blankToNull(truncate(request.appVersion(), MAX_APP_VERSION))
        ));
        // Deliberately no content/prompt/comment in the log line.
        log.info("AI content report {} filed: tenant={} surface={} reason={} platform={} onDevice={}",
            saved.getId(), ctx.getTenantId(), surface, reason, appPlatform, saved.isOnDevice());
        return saved;
    }

    public List<AiContentReport> list(String status, Integer limit) {
        return list(status, limit, 0L);
    }

    /** Offset-paged variant of {@link #list(String, Integer)}; newest first. */
    public List<AiContentReport> list(String status, Integer limit, long offset) {
        requirePlatformAdmin();
        int size = limit == null ? DEFAULT_LIMIT : Math.max(1, Math.min(MAX_LIMIT, limit));
        Pageable page = new OffsetLimitRequest(offset, size);
        if (status == null || status.isBlank()) {
            return repository.findAllByOrderByCreatedAtDesc(page);
        }
        String normalised = requireEnum(status, statusNames(), "status");
        return repository.findByStatusOrderByCreatedAtDesc(normalised, page);
    }

    /** Total reports matching {@code status} (all when blank); backs X-Total-Count. */
    public long count(String status) {
        requirePlatformAdmin();
        if (status == null || status.isBlank()) {
            return repository.count();
        }
        return repository.countByStatus(requireEnum(status, statusNames(), "status"));
    }

    public AiContentReport review(UUID id, AiContentReportReviewRequest request) {
        String reviewer = requirePlatformAdmin();
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Review body is required.");
        }
        String status = requireEnum(request.status(), REVIEW_STATUSES, "status");
        AiContentReport report = repository.findById(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Report not found."));
        report.review(status, blankToNull(truncate(request.reviewNote(), MAX_REVIEW_NOTE)), reviewer);
        AiContentReport saved = repository.save(report);
        log.info("AI content report {} marked {} by platform admin", id, status);
        return saved;
    }

    /** JSON shape shared by list/patch responses (contract section 1). */
    public static Map<String, Object> toView(AiContentReport r) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", r.getId());
        view.put("tenantId", r.getTenantId());
        view.put("reporterId", r.getReporterId());
        view.put("surface", r.getSurface());
        view.put("reason", r.getReason());
        view.put("comment", r.getComment());
        view.put("content", r.getContent());
        view.put("prompt", r.getPrompt());
        view.put("agentId", r.getAgentId());
        view.put("sessionId", r.getSessionId());
        view.put("messageRef", r.getMessageRef());
        view.put("onDevice", r.isOnDevice());
        view.put("appPlatform", r.getAppPlatform());
        view.put("appVersion", r.getAppVersion());
        view.put("status", r.getStatus());
        view.put("reviewNote", r.getReviewNote());
        view.put("reviewedBy", r.getReviewedBy());
        view.put("reviewedAt", r.getReviewedAt());
        view.put("createdAt", r.getCreatedAt());
        return view;
    }

    private static String requirePlatformAdmin() {
        return TenantContext.current()
            .filter(ctx -> ctx.hasRole(PLATFORM_ADMIN))
            .map(TenantContext::getSubject)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "Platform admin only."));
    }

    private static Set<String> statusNames() {
        return Set.of(
            AiContentReportStatus.OPEN.name(), AiContentReportStatus.REVIEWED.name(),
            AiContentReportStatus.ACTIONED.name(), AiContentReportStatus.DISMISSED.name());
    }

    private static String requireEnum(String value, Set<String> allowed, String field) {
        if (value == null || value.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, field + " is required.");
        }
        String normalised = value.trim().toUpperCase(Locale.ROOT);
        if (!allowed.contains(normalised)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown " + field + ": " + truncate(value, 40));
        }
        return normalised;
    }

    private static String optionalEnum(String value, Set<String> allowed, String field) {
        return value == null || value.isBlank() ? null : requireEnum(value, allowed, field);
    }

    static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
