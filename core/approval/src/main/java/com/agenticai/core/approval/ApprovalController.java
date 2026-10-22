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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@RestController
@RequestMapping("/api/approvals")
public class ApprovalController {

    private static final Logger log = LoggerFactory.getLogger(ApprovalController.class);

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

    /**
     * Must stay in sync with {@code GuardedToolCallback.ACTION_TYPE_PREFIX} (verified against that
     * class 2026-07-29). Duplicated rather than imported because {@code core/approval} deliberately
     * does not depend on {@code core/guardrails} - the dependency runs the other way, guardrails
     * -&gt; approval, and inverting it for one string constant would create a module cycle.
     */
    private static final String TOOL_CALL_ACTION_TYPE_PREFIX = "mcp_tool.";

    private final HumanApprovalGateService gateService;
    private final ApprovalRequestRepository requestRepository;
    private final ApprovalPolicyService policyService;
    private final ObjectMapper objectMapper;
    private final List<ApprovalDecisionAuthorizer> decisionAuthorizers;

    public ApprovalController(
            HumanApprovalGateService gateService,
            ApprovalRequestRepository requestRepository,
            ApprovalPolicyService policyService,
            ObjectMapper objectMapper,
            List<ApprovalDecisionAuthorizer> decisionAuthorizers) {
        this.gateService = gateService;
        this.requestRepository = requestRepository;
        this.policyService = policyService;
        this.objectMapper = objectMapper;
        this.decisionAuthorizers = decisionAuthorizers;
    }

    @GetMapping
    public ResponseEntity<List<ApprovalRequestResponse>> list(
            @RequestParam(required = false) ApprovalStatus status,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) Long offset) {
        int size = ListPaging.limit(limit, LIST_DEFAULT_LIMIT, LIST_MAX_LIMIT);
        long skip = ListPaging.offset(offset);
        var page = new OffsetLimitRequest(skip, size);
        List<ApprovalRequest> requests =
                status != null
                        ? requestRepository.findByStatusOrderByCreatedAtDescIdDesc(status, page)
                        : requestRepository.findAllByOrderByCreatedAtDescIdDesc(page);
        // Truncation must be visible: only a full page (or a paged-in request)
        // can hide rows, so only then pay for the count. Clients that need a
        // real total (dashboard counters) read X-Total-Count, and older rows
        // are reached with ?offset=.
        return ListPaging.respond(
                requests.stream().map(ApprovalRequestResponse::from).toList(),
                size,
                skip,
                () ->
                        status != null
                                ? requestRepository.countByStatus(status)
                                : requestRepository.count());
    }

    @GetMapping("/{id}")
    public ApprovalRequestResponse get(@PathVariable UUID id) {
        return requestRepository
                .findById(id)
                .map(ApprovalRequestResponse::from)
                .orElseThrow(() -> new ApprovalRequestNotFoundException(id));
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
        if (body != null && body.formValues() != null && !body.formValues().isEmpty()) {
            return ApprovalRequestResponse.from(
                    gateService.approve(id, callerSubject(authentication), body.formValues()));
        }
        return ApprovalRequestResponse.from(gateService.approve(id, callerSubject(authentication)));
    }

    /** Same authorization and same server-derived attribution as {@link #approve}. */
    @PostMapping("/{id}/reject")
    public ApprovalRequestResponse reject(
            @PathVariable UUID id,
            @RequestBody(required = false) ApprovalDecisionRequest body,
            Authentication authentication) {
        enforceApproverAuthorization(id, authentication);
        String reason = body != null ? body.reason() : null;
        return ApprovalRequestResponse.from(
                gateService.reject(id, callerSubject(authentication), reason));
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

    /**
     * #640 introduced this as an opt-in check: a workflow Approval node carrying {@code adminOnly:
     * true} in its {@code payloadJson} required {@link #APPROVALS_AUTHORITY} (then {@code
     * ROLE_tenant-admin}, now {@code PERM_MANAGE_APPROVALS} - granular RBAC 2026-09-23), and
     * everything else stayed open to any authenticated tenant member. The approval-gate hardening
     * pass found that opt-in shape was the wrong default for the platform's primary control between
     * model output and privileged action, and closed three holes in it:
     *
     * <ol>
     *   <li><b>Gated agent tool calls were the least protected, not the most.</b> {@code
     *       GuardedToolCallback} records them with raw tool input as the payload and no {@code
     *       adminOnly} key at all (see {@link #isToolCallApproval}), so the highest-risk class of
     *       request - a tool call an agent was talked into asking for - was decidable by any
     *       authenticated tenant member. Now admin-only unconditionally.
     *   <li><b>Parse failure failed open.</b> An unparseable payload returned "no restriction", so
     *       a malformed/truncated payload on an adminOnly request silently dropped the check. Now
     *       default-deny: if the server cannot read the payload it cannot prove the request is
     *       unrestricted, and an admin must decide it. Absent/blank payload is still unrestricted -
     *       that is a known, readable state (legacy rows, action types that never carried a
     *       payload), not an unreadable one.
     *   <li><b>Self-approval was possible.</b> See {@link #rejectSelfApproval}.
     * </ol>
     */
    private void enforceApproverAuthorization(UUID id, Authentication authentication) {
        ApprovalRequest request =
                requestRepository
                        .findById(id)
                        .orElseThrow(() -> new ApprovalRequestNotFoundException(id));
        boolean isTenantAdmin = isTenantAdmin(authentication);

        if (isToolCallApproval(request) && !isTenantAdmin) {
            throw new ApprovalNotAuthorizedException(
                    id,
                    "a gated agent tool call may only be approved or rejected by a tenant-admin");
        }
        if (requiresTenantAdmin(request) && !isTenantAdmin) {
            throw new ApprovalNotAuthorizedException(id);
        }
        rejectSelfApproval(id, request, authentication, isTenantAdmin);
        if (!isTenantAdmin) {
            // The raising module's own rule, e.g. run access to the workflow an Approval node
            // belongs to (WorkflowApprovalDecisionAuthorizer).
            for (ApprovalDecisionAuthorizer authorizer : decisionAuthorizers) {
                if (authorizer.supports(request)) {
                    authorizer.authorize(request);
                }
            }
        }
    }

    /**
     * A requester may not sign off on their own request unless they are a tenant-admin (an admin
     * approving something they themselves initiated is a normal single-admin-tenant workflow, and
     * they already hold every permission the action would grant, so blocking it would only push
     * them to a second account).
     *
     * <p>The requester IS recorded: {@link ApprovalRequest#getRequestedBy()} is populated at
     * creation ({@code HumanApprovalGateService#evaluate}, {@code #recordToolCallForApproval}). One
     * honest caveat - for tool-call requests {@code GuardedToolCallback} writes an agent/session
     * label ({@code "agent:<agentId>:session:<sessionId>"}), not the human's subject, so this
     * comparison cannot identify the user who induced that tool call. That specific hole is closed
     * by the admin-only rule for tool calls above rather than by this check, which is exactly why
     * both exist.
     */
    private void rejectSelfApproval(
            UUID id,
            ApprovalRequest request,
            Authentication authentication,
            boolean isTenantAdmin) {
        if (isTenantAdmin) {
            return;
        }
        String requestedBy = request.getRequestedBy();
        if (requestedBy == null || requestedBy.isBlank()) {
            return;
        }
        if (requestedBy.equals(callerSubject(authentication))) {
            throw new ApprovalNotAuthorizedException(
                    id,
                    "you requested this action, so you may not also approve it - "
                            + "another tenant member or a tenant-admin must decide");
        }
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

    /**
     * Tool-call approvals are identified by their action type, not their payload: {@code
     * GuardedToolCallback} builds it as its own {@code ACTION_TYPE_PREFIX + toolName}, i.e. {@code
     * "mcp_tool." + name}. The payload is either the raw tool-input JSON (legacy rows) or the
     * replay envelope the 7-arg {@link HumanApprovalGateService#recordToolCallForApproval} records
     * now - but the envelope's {@code "replay"} key wraps model-influenced data and is a replay
     * concern, not an authorization one, so the action type remains the only signal this check
     * trusts at decision time.
     */
    private boolean isToolCallApproval(ApprovalRequest request) {
        String actionType = request.getActionType();
        return actionType != null && actionType.startsWith(TOOL_CALL_ACTION_TYPE_PREFIX);
    }

    private boolean requiresTenantAdmin(ApprovalRequest request) {
        String payloadJson = request.getPayloadJson();
        if (payloadJson == null || payloadJson.isBlank()) {
            return false;
        }
        try {
            JsonNode payload = objectMapper.readTree(payloadJson);
            return payload.path("adminOnly").asBoolean(false);
        } catch (Exception e) {
            // DEFAULT-DENY (was: fail open). An unreadable payload is the one
            // case where the server genuinely cannot tell whether this
            // request was marked adminOnly - and "cannot tell" must not
            // resolve to "unrestricted" on the gate that stands between model
            // output and privileged action. Requiring an admin here is
            // strictly the safer failure: at worst a tenant-admin has to
            // decide a request a regular member could have handled.
            log.warn(
                    "Approval request {} has an unparseable payloadJson - requiring tenant-admin to"
                            + " decide it (default-deny): {}",
                    request.getId(),
                    e.getMessage());
            return true;
        }
    }
}
