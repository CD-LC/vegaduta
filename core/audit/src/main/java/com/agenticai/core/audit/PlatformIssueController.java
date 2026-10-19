package com.agenticai.core.audit;

import com.agenticai.core.audit.dto.CreateIssueTicketRequest;
import com.agenticai.core.audit.dto.PlatformIssueResponse;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin Issues portal (#425/#426). Sits under {@code /api/admin/**}, so
 * {@code SecurityConfig}'s existing {@code .requestMatchers("/api/admin/**")
 * .hasAuthority("ROLE_tenant-admin")} rule already covers every endpoint
 * here without a new security-config line.
 */
@RestController
public class PlatformIssueController {

    // Defense-in-depth: the 4 system-detected-only IssueCategory values (TOOL_FAILURE/SAFETY/
    // SYSTEM/WORKFLOW) must never be settable by a tenant-submitted ticket - those are reserved
    // for AiDiagnosticAnalysisService's async enrichment of system-detected rows.
    private static final Set<IssueCategory> TENANT_SELECTABLE = EnumSet.of(
        IssueCategory.BILLING, IssueCategory.TECHNICAL, IssueCategory.ACCOUNT,
        IssueCategory.FEATURE_REQUEST, IssueCategory.OTHER
    );

    private static final int LEGACY_LIMIT = 200;

    private final PlatformIssueService issueService;

    public PlatformIssueController(PlatformIssueService issueService) {
        this.issueService = issueService;
    }

    /**
     * Neither {@code limit} nor {@code offset}: the newest 200, exactly as before paging existed.
     * Either present: the paged contract (limit 1..1000, default 50). Always carries
     * {@code X-Total-Count} (rows matching {@code status}) and {@code X-Has-More}.
     */
    @GetMapping("/api/admin/issues")
    public ResponseEntity<List<PlatformIssueResponse>> list(
        @RequestParam(required = false) IssueStatus status,
        @RequestParam(required = false) Integer limit,
        @RequestParam(required = false) Long offset
    ) {
        int size = (limit == null && offset == null)
            ? LEGACY_LIMIT
            : AuditPaging.clampLimit(limit, 50, 1000);
        long skip = AuditPaging.clampOffset(offset);
        List<PlatformIssueResponse> rows = issueService.list(status, size, skip).stream()
            .map(PlatformIssueResponse::from)
            .toList();
        return AuditPaging.respond(rows, size, skip, () -> issueService.count(status));
    }

    /**
     * #974 - "give a link for ticket creation and categories on which issue
     * falls under" - a tenant admin self-filing a support ticket from the
     * Billing & usage page. Reuses this same tenant-scoped table/portal (no
     * parallel ticketing system) so a submitted ticket immediately shows up
     * in the tenant's own Issues list above, right next to any system-
     * detected failures the tenant may want to reference in the ticket.
     */
    @PostMapping("/api/admin/issues")
    public PlatformIssueResponse createTicket(@RequestBody CreateIssueTicketRequest request) {
        if (request.category() == null) {
            throw new IllegalArgumentException("A category is required to file a support ticket.");
        }
        if (!TENANT_SELECTABLE.contains(request.category())) {
            throw new IllegalArgumentException("Not a valid ticket category.");
        }
        if (request.title() == null || request.title().isBlank()) {
            throw new IllegalArgumentException("A short subject/title is required to file a support ticket.");
        }
        String title = request.title().trim();
        String message = request.message() == null ? "" : request.message().trim();
        return PlatformIssueResponse.from(issueService.createTicket(request.category(), title, message));
    }

    @PostMapping("/api/admin/issues/{id}/acknowledge")
    public PlatformIssueResponse acknowledge(@PathVariable UUID id, Authentication authentication) {
        String actorId = authentication != null ? authentication.getName() : null;
        return PlatformIssueResponse.from(issueService.acknowledge(id, actorId));
    }

    @PostMapping("/api/admin/issues/{id}/resolve")
    public PlatformIssueResponse resolve(@PathVariable UUID id) {
        return PlatformIssueResponse.from(issueService.resolve(id));
    }
}
