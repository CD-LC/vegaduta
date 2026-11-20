package com.agenticai.core.approval;

import com.agenticai.core.approval.dto.ApprovalDecisionRequest;
import com.agenticai.core.approval.dto.ApprovalPolicyUpdateRequest;
import com.agenticai.core.approval.dto.ApprovalRequestResponse;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.tenancy.ListPaging;
import com.agenticai.core.tenancy.OffsetLimitRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/approvals")
public class ApprovalController {

    /**
     * Optional {@code ?limit=}/{@code ?offset=}: newest N rows, hard max 1000; without a limit the
     * full list (see ListPaging, LIST_DEFAULT_CAP).
     */
    static final int LIST_DEFAULT_LIMIT = 200;

    static final int LIST_MAX_LIMIT = 1000;

    // Granular RBAC (2026-09-23): deciding approvals tracks MANAGE_APPROVALS
    // instead of the blanket tenant-admin role - a delegated approver is the
    // scope's whole point. platform-admin keeps the role check (no tenant
    // grants by design).
    private static final String APPROVALS_AUTHORITY = "PERM_MANAGE_APPROVALS";
    private static final String PLATFORM_ADMIN_AUTHORITY = "ROLE_platform-admin";

    private final ApprovalApiService apiService;
    private final ApprovalPolicyService policyService;

    public ApprovalController(ApprovalApiService apiService, ApprovalPolicyService policyService) {
        this.apiService = apiService;
        this.policyService = policyService;
    }

    @GetMapping
    public ResponseEntity<List<ApprovalRequestResponse>> list(
            @RequestParam(required = false) ApprovalStatus status,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) Long offset) {
        int size = ListPaging.limit(limit, LIST_DEFAULT_LIMIT, LIST_MAX_LIMIT);
        long skip = ListPaging.offset(offset);
        var page = new OffsetLimitRequest(skip, size);
        List<ApprovalRequestResponse> requests = apiService.list(status, page);
        // Truncation must be visible: only a full page (or a paged-in request)
        // can hide rows, so only then pay for the count. Clients that need a
        // real total (dashboard counters) read X-Total-Count, and older rows
        // are reached with ?offset=.
        return ListPaging.respond(requests, size, skip, () -> apiService.count(status));
    }

    @GetMapping("/{id}")
    public ApprovalRequestResponse get(@PathVariable UUID id) {
        return apiService.get(id);
    }

    /**
     * <b>Audit-attribution fix.</b> {@code decidedBy} is derived from the authenticated principal
     * via {@link #callerSubject}, never from {@code body.decidedBy()}. Previously the client
     * supplied it and it was persisted verbatim into {@code approval_request.decided_by} and the
     * audit log, so an attacker could approve a privileged action and record a colleague's name
     * against it - the audit trail for the platform's primary human-in-the-loop control was
     * attacker-controlled text.
     */
    @PostMapping("/{id}/approve")
    public ApprovalRequestResponse approve(
            @PathVariable UUID id,
            @RequestBody(required = false) ApprovalDecisionRequest body,
            Authentication authentication) {
        enforceApproverAuthorization(id, authentication);
        return apiService.approve(
                id, callerSubject(authentication), body != null ? body.formValues() : null);
    }

    /** Same authorization and same server-derived attribution as {@link #approve}. */
    @PostMapping("/{id}/reject")
    public ApprovalRequestResponse reject(
            @PathVariable UUID id,
            @RequestBody(required = false) ApprovalDecisionRequest body,
            Authentication authentication) {
        enforceApproverAuthorization(id, authentication);
        String reason = body != null ? body.reason() : null;
        return apiService.reject(id, callerSubject(authentication), reason);
    }

    /**
     * Who is deciding, according to the server. {@link TenantContext}'s subject is the JWT {@code
     * sub} that {@code TenantContextFilter} put there, and is the same identity {@code
     * AuditLogService} records as the actor everywhere else in this codebase, so decisions
     * attribute consistently with the rest of the audit trail. {@link Authentication#getName()} is
     * the fallback for the (test / non-JWT-filter) paths where no {@code TenantContext} is bound -
     * for a {@code JwtAuthenticationToken} it resolves to the same {@code sub}. Never null in
     * practice; {@code "unknown"} rather than {@code null} as a last resort, because a blank
     * attribution in an approval audit row is itself a finding an operator should be able to see.
     */
    private String callerSubject(Authentication authentication) {
        String subject = TenantContext.current().map(TenantContext::getSubject).orElse(null);
        if (subject == null || subject.isBlank()) {
            subject = authentication != null ? authentication.getName() : null;
        }
        return subject == null || subject.isBlank() ? "unknown" : subject;
    }

    @PutMapping("/policy/{actionType}")
    public void setPolicy(
            @PathVariable String actionType, @Valid @RequestBody ApprovalPolicyUpdateRequest body) {
        policyService.setOverride(actionType, body.requiresApproval());
    }

    private void enforceApproverAuthorization(UUID id, Authentication authentication) {
        apiService.enforceApproverAuthorization(
                id, isTenantAdmin(authentication), callerSubject(authentication));
    }

    private boolean isTenantAdmin(Authentication authentication) {
        return authentication != null
                && authentication.getAuthorities().stream()
                        .anyMatch(
                                authority ->
                                        APPROVALS_AUTHORITY.equals(authority.getAuthority())
                                                || PLATFORM_ADMIN_AUTHORITY.equals(
                                                        authority.getAuthority()));
    }
}
