package com.agenticai.core.approval;

import com.agenticai.core.approval.dto.ApprovalRequestResponse;
import com.agenticai.core.tenancy.OffsetLimitRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Service facade for {@code ApprovalController}: owns the repository reads, the decision
 * authorization rules (which inspect the {@link ApprovalRequest} entity) and the entity-to-DTO
 * mapping, so the controller never touches a repository or a JPA entity. The decision itself runs
 * in {@link HumanApprovalGateService}, which owns its own transaction boundaries.
 */
@Service
public class ApprovalApiService {

    private static final Logger log = LoggerFactory.getLogger(ApprovalApiService.class);

    /**
     * Must stay in sync with {@code GuardedToolCallback.ACTION_TYPE_PREFIX} (verified against that
     * class 2026-07-29). Duplicated rather than imported because {@code core/approval} deliberately
     * does not depend on {@code core/guardrails} - the dependency runs the other way, guardrails
     * -&gt; approval, and inverting it for one string constant would create a module cycle.
     */
    private static final String TOOL_CALL_ACTION_TYPE_PREFIX = "mcp_tool.";

    private final HumanApprovalGateService gateService;
    private final ApprovalRequestRepository requestRepository;
    private final ObjectMapper objectMapper;
    private final List<ApprovalDecisionAuthorizer> decisionAuthorizers;

    public ApprovalApiService(
            HumanApprovalGateService gateService,
            ApprovalRequestRepository requestRepository,
            ObjectMapper objectMapper,
            List<ApprovalDecisionAuthorizer> decisionAuthorizers) {
        this.gateService = gateService;
        this.requestRepository = requestRepository;
        this.objectMapper = objectMapper;
        this.decisionAuthorizers = decisionAuthorizers;
    }

    @Transactional(readOnly = true)
    public List<ApprovalRequestResponse> list(ApprovalStatus status, OffsetLimitRequest page) {
        List<ApprovalRequest> requests =
                status != null
                        ? requestRepository.findByStatusOrderByCreatedAtDescIdDesc(status, page)
                        : requestRepository.findAllByOrderByCreatedAtDescIdDesc(page);
        return requests.stream().map(ApprovalRequestResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public long count(ApprovalStatus status) {
        return status != null ? requestRepository.countByStatus(status) : requestRepository.count();
    }

    @Transactional(readOnly = true)
    public ApprovalRequestResponse get(UUID id) {
        return requestRepository
                .findById(id)
                .map(ApprovalRequestResponse::from)
                .orElseThrow(() -> new ApprovalRequestNotFoundException(id));
    }

    public ApprovalRequestResponse approve(
            UUID id, String decidedBy, Map<String, String> formValues) {
        if (formValues != null && !formValues.isEmpty()) {
            return ApprovalRequestResponse.from(gateService.approve(id, decidedBy, formValues));
        }
        return ApprovalRequestResponse.from(gateService.approve(id, decidedBy));
    }

    public ApprovalRequestResponse reject(UUID id, String decidedBy, String reason) {
        return ApprovalRequestResponse.from(gateService.reject(id, decidedBy, reason));
    }

    /**
     * #640 introduced this as an opt-in check: a workflow Approval node carrying {@code adminOnly:
     * true} in its {@code payloadJson} required {@code PERM_MANAGE_APPROVALS} (then {@code
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
     *
     * @param isTenantAdmin whether the caller holds the approvals permission or is platform-admin
     * @param callerSubject the server-derived identity of the caller
     */
    public void enforceApproverAuthorization(UUID id, boolean isTenantAdmin, String callerSubject) {
        ApprovalRequest request =
                requestRepository
                        .findById(id)
                        .orElseThrow(() -> new ApprovalRequestNotFoundException(id));

        if (isToolCallApproval(request) && !isTenantAdmin) {
            throw new ApprovalNotAuthorizedException(
                    id,
                    "a gated agent tool call may only be approved or rejected by a tenant-admin");
        }
        if (requiresTenantAdmin(request) && !isTenantAdmin) {
            throw new ApprovalNotAuthorizedException(id);
        }
        rejectSelfApproval(id, request, callerSubject, isTenantAdmin);
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
            UUID id, ApprovalRequest request, String callerSubject, boolean isTenantAdmin) {
        if (isTenantAdmin) {
            return;
        }
        String requestedBy = request.getRequestedBy();
        if (requestedBy == null || requestedBy.isBlank()) {
            return;
        }
        if (requestedBy.equals(callerSubject)) {
            throw new ApprovalNotAuthorizedException(
                    id,
                    "you requested this action, so you may not also approve it - "
                            + "another tenant member or a tenant-admin must decide");
        }
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
