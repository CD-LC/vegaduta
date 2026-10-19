package com.agenticai.core.approval;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.approval.dto.ApprovalDecisionRequest;
import com.agenticai.core.security.TenantContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;

/**
 * Regression coverage for the human-approval-gate hardening. This gate is the primary control
 * standing between model output and privileged action, and it had three separate holes: a
 * client-supplied {@code decidedBy} (forgeable audit attribution), a {@code requiresTenantAdmin}
 * check that failed open on any parse error, and gated agent tool calls - the highest-risk class of
 * request - being decidable by any authenticated tenant member, including the very user who induced
 * the agent to ask.
 */
class ApprovalControllerTest {

    private static final String CALLER = "user-123";
    private static final String SOMEONE_ELSE = "user-456";
    private static final String TENANT_ADMIN_AUTHORITY = "PERM_MANAGE_APPROVALS";

    private HumanApprovalGateService gateService;
    private ApprovalRequestRepository requestRepository;
    private ApprovalPolicyService policyService;
    private ApprovalController controller;

    /** Stands in for a module's own rule (WorkflowApprovalDecisionAuthorizer in core/workflow). */
    private boolean workflowRuleDenies;

    private final ApprovalDecisionAuthorizer workflowRule =
            new ApprovalDecisionAuthorizer() {
                @Override
                public boolean supports(ApprovalRequest request) {
                    return request.getActionType().startsWith("workflow.node.");
                }

                @Override
                public void authorize(ApprovalRequest request) {
                    if (workflowRuleDenies) {
                        throw new ApprovalNotAuthorizedException(request.getId(), "no run access");
                    }
                }
            };

    @BeforeEach
    void setUp() {
        gateService = mock(HumanApprovalGateService.class);
        requestRepository = mock(ApprovalRequestRepository.class);
        policyService = mock(ApprovalPolicyService.class);
        controller =
                new ApprovalController(
                        gateService,
                        requestRepository,
                        policyService,
                        new ObjectMapper(),
                        List.of(workflowRule));
    }

    @AfterEach
    void tearDown() {
        // callerSubject() prefers TenantContext - never let one test's
        // binding leak into the next.
        TenantContext.clear();
    }

    private Authentication member(String subject) {
        return new UsernamePasswordAuthenticationToken(
                subject, "n/a", AuthorityUtils.createAuthorityList("ROLE_user"));
    }

    private Authentication tenantAdmin(String subject) {
        return new UsernamePasswordAuthenticationToken(
                subject, "n/a", AuthorityUtils.createAuthorityList(TENANT_ADMIN_AUTHORITY));
    }

    /** A pending request of the given action type/payload/requester, wired into the repository. */
    private UUID pending(String actionType, String payloadJson, String requestedBy) {
        UUID id = UUID.randomUUID();
        ApprovalRequest request = mock(ApprovalRequest.class);
        when(request.getId()).thenReturn(id);
        when(request.getStatus()).thenReturn(ApprovalStatus.PENDING);
        when(request.getActionType()).thenReturn(actionType);
        when(request.getPayloadJson()).thenReturn(payloadJson);
        when(request.getRequestedBy()).thenReturn(requestedBy);
        when(requestRepository.findById(id)).thenReturn(Optional.of(request));
        when(gateService.approve(eq(id), anyString())).thenReturn(request);
        when(gateService.reject(eq(id), anyString(), any())).thenReturn(request);
        return id;
    }

    // --- decidedBy cannot be spoofed -----------------------------------------

    @Test
    void approveIgnoresTheBodysDecidedByAndRecordsTheAuthenticatedSubject() {
        // The forgery: approve a privileged action, record the CEO's name
        // against it. decided_by is the audit attribution for the platform's
        // primary human-in-the-loop control - it must be server-derived.
        UUID id = pending("channel.whatsapp.send", "{}", SOMEONE_ELSE);

        controller.approve(
                id, new ApprovalDecisionRequest("ceo-sub-pretend", null), member(CALLER));

        verify(gateService).approve(id, CALLER);
        verify(gateService, never()).approve(id, "ceo-sub-pretend");
    }

    @Test
    void rejectIgnoresTheBodysDecidedByButStillPassesTheCallerSuppliedReason() {
        // reason is free-text explanation, not an identity claim - it stays
        // caller-supplied by design.
        UUID id = pending("channel.whatsapp.send", "{}", SOMEONE_ELSE);

        controller.reject(
                id, new ApprovalDecisionRequest("ceo-sub-pretend", "not needed"), member(CALLER));

        verify(gateService).reject(id, CALLER, "not needed");
    }

    @Test
    void approveWithNoBodyAtAllStillAttributesTheDecisionToTheCaller() {
        UUID id = pending("channel.whatsapp.send", "{}", SOMEONE_ELSE);

        controller.approve(id, null, member(CALLER));

        verify(gateService).approve(id, CALLER);
    }

    @Test
    void tenantContextsSubjectIsPreferredOverTheAuthenticationName() {
        // TenantContext's subject is the JWT sub TenantContextFilter bound,
        // and is what AuditLogService records as the actor everywhere else.
        TenantContext.set(TenantContext.of("tenant-a", "jwt-sub-real", Set.of("ROLE_user")));
        UUID id = pending("channel.whatsapp.send", "{}", SOMEONE_ELSE);

        controller.approve(
                id, new ApprovalDecisionRequest("spoofed", null), member("different-name"));

        verify(gateService).approve(id, "jwt-sub-real");
    }

    // --- unparseable payload requires admin (default-deny) --------------------

    @Test
    void anUnparseablePayloadRequiresTenantAdminInsteadOfFailingOpen() {
        UUID id = pending("workflow.node.approval", "{\"adminOnly\": tru", SOMEONE_ELSE);

        assertThrows(
                ApprovalNotAuthorizedException.class,
                () -> controller.approve(id, null, member(CALLER)));

        verify(gateService, never()).approve(any(), any());
    }

    @Test
    void anUnparseablePayloadIsStillDecidableByATenantAdmin() {
        // Default-deny narrows who may decide; it must not brick the request.
        UUID id = pending("workflow.node.approval", "}{ garbage", SOMEONE_ELSE);

        assertDoesNotThrow(() -> controller.approve(id, null, tenantAdmin(CALLER)));

        verify(gateService).approve(id, CALLER);
    }

    @Test
    void anAbsentPayloadIsStillTreatedAsUnrestricted() {
        // Absent is a known, readable state (legacy rows, action types that
        // never carried a payload) - not the "cannot tell" case default-deny
        // is for. Regressing this would lock every ordinary channel send
        // behind an admin.
        UUID id = pending("channel.whatsapp.send", null, SOMEONE_ELSE);

        assertDoesNotThrow(() -> controller.approve(id, null, member(CALLER)));

        verify(gateService).approve(id, CALLER);
    }

    @Test
    void anExplicitAdminOnlyPayloadStillRequiresTenantAdmin() {
        UUID id = pending("workflow.node.approval", "{\"adminOnly\":true}", SOMEONE_ELSE);

        assertThrows(
                ApprovalNotAuthorizedException.class,
                () -> controller.approve(id, null, member(CALLER)));
    }

    // --- a non-admin cannot approve a tool call -------------------------------

    @Test
    void aNonAdminCannotApproveAGatedAgentToolCall() {
        // GuardedToolCallback records these as "mcp_tool." + toolName with
        // the raw tool-input JSON as payload and no adminOnly key - perfectly
        // parseable, so nothing else in this class would have caught it.
        UUID id =
                pending(
                        "mcp_tool.delete_customer_records",
                        "{\"customerId\":\"all\"}",
                        "agent:agent-7:session:s-1");

        assertThrows(
                ApprovalNotAuthorizedException.class,
                () -> controller.approve(id, null, member(CALLER)));

        verify(gateService, never()).approve(any(), any());
    }

    @Test
    void aNonAdminCannotRejectAGatedAgentToolCallEither() {
        UUID id =
                pending(
                        "mcp_tool.wire_transfer",
                        "{\"amount\":1000000}",
                        "agent:agent-7:session:s-1");

        assertThrows(
                ApprovalNotAuthorizedException.class,
                () ->
                        controller.reject(
                                id, new ApprovalDecisionRequest(null, "no"), member(CALLER)));

        verify(gateService, never()).reject(any(), any(), any());
    }

    @Test
    void aTenantAdminMayApproveAGatedAgentToolCall() {
        UUID id =
                pending(
                        "mcp_tool.delete_customer_records",
                        "{\"customerId\":\"all\"}",
                        "agent:agent-7:session:s-1");

        assertDoesNotThrow(() -> controller.approve(id, null, tenantAdmin(CALLER)));

        verify(gateService).approve(id, CALLER);
    }

    // --- self-approval --------------------------------------------------------

    @Test
    void aRequesterMayNotApproveTheirOwnRequest() {
        // e.g. CodeIntelController's "apply code changes"/"run in workspace"
        // gates, whose requestedBy IS the human's JWT subject.
        UUID id = pending("code.workspace.run", "{}", CALLER);

        assertThrows(
                ApprovalNotAuthorizedException.class,
                () -> controller.approve(id, null, member(CALLER)));

        verify(gateService, never()).approve(any(), any());
    }

    @Test
    void aRequesterMayNotRejectTheirOwnRequestEither() {
        UUID id = pending("code.workspace.run", "{}", CALLER);

        assertThrows(
                ApprovalNotAuthorizedException.class,
                () ->
                        controller.reject(
                                id,
                                new ApprovalDecisionRequest(null, "changed my mind"),
                                member(CALLER)));
    }

    @Test
    void aTenantAdminMayDecideTheirOwnRequest() {
        // Deliberate: an admin already holds every permission the action
        // would grant, so blocking this would only push them to a second
        // account - and single-admin tenants are the common case here.
        UUID id = pending("code.workspace.run", "{}", CALLER);

        assertDoesNotThrow(() -> controller.approve(id, null, tenantAdmin(CALLER)));

        verify(gateService).approve(id, CALLER);
    }

    @Test
    void aRegularMemberMayStillDecideSomeoneElsesOrdinaryRequest() {
        // No regression: the overwhelming majority of requests (channel
        // sends, non-adminOnly workflow nodes) stay decidable by any
        // authenticated tenant member, exactly as before.
        UUID id = pending("channel.whatsapp.send", "{\"to\":\"+1555\"}", SOMEONE_ELSE);

        assertDoesNotThrow(() -> controller.approve(id, null, member(CALLER)));

        verify(gateService).approve(id, CALLER);
    }

    @Test
    void aModulesOwnRuleCanStopAMemberDecidingItsApproval() {
        workflowRuleDenies = true;
        UUID id = pending("workflow.node.approval", "{\"runId\":\"r1\"}", SOMEONE_ELSE);

        assertThrows(
                ApprovalNotAuthorizedException.class,
                () -> controller.approve(id, null, member(CALLER)));
        assertThrows(
                ApprovalNotAuthorizedException.class,
                () -> controller.reject(id, null, member(CALLER)));
        verify(gateService, never()).approve(any(), anyString());
    }

    @Test
    void anApprovalsAdminIsNotSubjectToTheModulesRule() {
        workflowRuleDenies = true;
        UUID id = pending("workflow.node.approval", "{\"runId\":\"r1\"}", SOMEONE_ELSE);

        assertDoesNotThrow(() -> controller.approve(id, null, tenantAdmin(CALLER)));
    }

    @Test
    void aRequestWithNoRecordedRequesterIsNotBlockedBySelfApproval() {
        // Legacy rows predating requestedBy: nothing to compare against, so
        // the self-approval rule cannot apply. Documented rather than silently
        // defaulting either way.
        UUID id = pending("channel.whatsapp.send", "{}", null);

        assertDoesNotThrow(() -> controller.approve(id, null, member(CALLER)));

        verify(gateService).approve(id, CALLER);
    }

    @Test
    void decidingANonexistentRequestStillReports404NotAnAuthorizationError() {
        UUID id = UUID.randomUUID();
        when(requestRepository.findById(id)).thenReturn(Optional.empty());

        assertThrows(
                ApprovalRequestNotFoundException.class,
                () -> controller.approve(id, null, member(CALLER)));
    }
}
