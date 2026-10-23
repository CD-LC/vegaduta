package com.agenticai.core.audit;

import com.agenticai.core.audit.dto.NotificationResponse;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Notification bell - tenant-facing sibling of {@link PlatformIssueController}'s admin Issues
 * portal. Falls under {@code SecurityConfig}'s default {@code anyRequest().authenticated()} rule
 * (not {@code /api/admin/**}), same as {@code SessionConfigController} - any authenticated tenant
 * member can call these, and the "mine or broadcast-if-admin" visibility narrowing happens at the
 * query level in {@link NotificationRepository}, not via a security-config role gate.
 */
@RestController
public class NotificationController {

    // Granular RBAC (2026-09-23): "sees broadcast notifications" means
    // "holds ANY administrative scope" now - the bell is how every scoped
    // administrator gets platform alerts, not just the blanket role.
    private static final String PERM_PREFIX = "PERM_";
    private static final String PLATFORM_ADMIN_AUTHORITY = "ROLE_platform-admin";
    private static final int DEFAULT_PAGE_SIZE = 50;
    private static final int MAX_PAGE_SIZE = 200;

    private final NotificationService notificationService;

    public NotificationController(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @GetMapping("/api/notifications")
    public ResponseEntity<List<NotificationResponse>> list(
            @RequestParam(required = false, defaultValue = "false") boolean unreadOnly,
            @RequestParam(required = false, defaultValue = "" + DEFAULT_PAGE_SIZE) int limit,
            @RequestParam(required = false) Long offset,
            Authentication authentication) {
        if (isTenantlessPlatformAdmin(authentication)) {
            return AuditPaging.respondWithTotal(List.of(), 0, 0);
        }
        int size = AuditPaging.clampLimit(limit, DEFAULT_PAGE_SIZE, MAX_PAGE_SIZE);
        long skip = AuditPaging.clampOffset(offset);
        String user = userId(authentication);
        boolean admin = isTenantAdmin(authentication);
        Pageable pageable = AuditPaging.offsetLimit(skip, size, Sort.unsorted());
        List<NotificationResponse> rows =
                notificationService.listVisible(user, admin, unreadOnly, pageable).stream()
                        .map(NotificationResponse::from)
                        .toList();
        return AuditPaging.respond(
                rows, size, skip, () -> notificationService.countVisible(user, admin, unreadOnly));
    }

    /** Backs a cheap, frequently-polled unread-count badge on the bell icon. */
    @GetMapping("/api/notifications/unread-count")
    public long unreadCount(Authentication authentication) {
        if (isTenantlessPlatformAdmin(authentication)) {
            return 0;
        }
        return notificationService.countUnread(
                userId(authentication), isTenantAdmin(authentication));
    }

    @PostMapping("/api/notifications/{id}/read")
    public void markRead(@PathVariable UUID id, Authentication authentication) {
        if (isTenantlessPlatformAdmin(authentication)) {
            return;
        }
        notificationService.markRead(id, userId(authentication), isTenantAdmin(authentication));
    }

    @PostMapping("/api/notifications/read-all")
    public void markAllRead(Authentication authentication) {
        if (isTenantlessPlatformAdmin(authentication)) {
            return;
        }
        notificationService.markAllRead(userId(authentication), isTenantAdmin(authentication));
    }

    // The web-admin console signs in as a tenant-less platform-admin: its token has
    // ROLE_platform-admin but never a tenant_id, so GranularPermissionConverter emits no
    // PERM_* for it and its requests resolve to the public schema - where the
    // per-tenant notification table does not exist (V176 is tenant-only). Answer
    // "nothing to show" instead of a 500 for the bell it polls.
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
        return authentication != null
                && authentication.getAuthorities().stream()
                        .map(GrantedAuthority::getAuthority)
                        .anyMatch(
                                a ->
                                        a != null
                                                && (a.startsWith(PERM_PREFIX)
                                                        || PLATFORM_ADMIN_AUTHORITY.equals(a)));
    }
}
