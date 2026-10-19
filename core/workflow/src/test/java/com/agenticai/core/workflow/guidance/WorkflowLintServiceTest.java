package com.agenticai.core.workflow.guidance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.admin.permission.UserPermissionService;
import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.agents.dto.AgentResponse;
import com.agenticai.core.approval.ApprovalPolicyService;
import com.agenticai.core.providers.ProviderCredentialResolver;
import com.agenticai.core.providers.ProviderName;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.TenantLimits;
import com.agenticai.core.workflow.OrchestratorService;
import com.agenticai.core.workflow.WorkflowValidationException;
import com.agenticai.core.workflow.dto.WorkflowEdgeDto;
import com.agenticai.core.workflow.dto.WorkflowGraphDto;
import com.agenticai.core.workflow.dto.WorkflowNodeDto;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

class WorkflowLintServiceTest {

    private static final UUID WF = UUID.randomUUID();

    private OrchestratorService orchestrator;
    private WorkflowInsightsService insights;
    private AgentService agents;
    private ProviderCredentialResolver credentials;
    private TenantLimits limits;
    private ApprovalPolicyService policy;
    private UserPermissionService permissions;
    private WorkflowLintService lint;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        orchestrator = mock(OrchestratorService.class);
        insights = mock(WorkflowInsightsService.class);
        agents = mock(AgentService.class);
        credentials = mock(ProviderCredentialResolver.class);
        limits = mock(TenantLimits.class);
        ObjectProvider<AgentService> ap = mock(ObjectProvider.class);
        ObjectProvider<ProviderCredentialResolver> cp = mock(ObjectProvider.class);
        ObjectProvider<TenantLimits> lp = mock(ObjectProvider.class);
        when(ap.getIfAvailable()).thenReturn(agents);
        when(cp.getIfAvailable()).thenReturn(credentials);
        when(lp.getIfAvailable()).thenReturn(limits);
        when(insights.summary(any(), any(), org.mockito.ArgumentMatchers.anyInt())).thenReturn(Map.of("nodes", Map.of()));
        when(limits.usesPlatformPool("acme")).thenReturn(true);
        TenantContext.set(TenantContext.of("acme", "u1", Set.of()));
        policy = mock(ApprovalPolicyService.class);
        permissions = mock(UserPermissionService.class);
        ObjectProvider<ApprovalPolicyService> pp = mock(ObjectProvider.class);
        ObjectProvider<UserPermissionService> up = mock(ObjectProvider.class);
        when(pp.getIfAvailable()).thenReturn(policy);
        when(up.getIfAvailable()).thenReturn(permissions);
        when(policy.requiresApproval(org.mockito.ArgumentMatchers.anyString())).thenReturn(true);
        when(permissions.userIdsHolding("acme", "MANAGE_APPROVALS")).thenReturn(Set.of("admin-1"));
        lint = new WorkflowLintService(orchestrator, insights, ap, cp, lp, pp, up);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private static WorkflowNodeDto node(String id, String type, Map<String, Object> config) {
        return new WorkflowNodeDto(id, type, Map.of(), Map.of("label", id, "config", config));
    }

    private static WorkflowEdgeDto edge(String id, String from, String to, String handle) {
        return new WorkflowEdgeDto(id, from, to, handle, null);
    }

    private List<String> codes(WorkflowLintService.LintResult r) {
        return r.issues().stream().map(WorkflowLintService.LintIssue::code).toList();
    }

    private WorkflowLintService.LintResult run(WorkflowGraphDto g) {
        return lint.lint(WF, g, g);
    }

    @Test
    void cleanGraphHasNoIssues() {
        WorkflowGraphDto g = new WorkflowGraphDto(
            List.of(node("t", "trigger", Map.of()), node("c", "code", Map.of("operation", "uppercase"))),
            List.of(edge("e1", "t", "c", null)));
        WorkflowLintService.LintResult r = run(g);
        assertTrue(r.issues().isEmpty(), r.issues().toString());
        assertEquals(0, r.summary().get("errors"));
    }

    @Test
    void parityRulesUseTheClientCodes() {
        WorkflowGraphDto g = new WorkflowGraphDto(
            List.of(node("a", "agent", Map.of("agentId", UUID.randomUUID().toString())),
                node("lonely", "code", Map.of()),
                node("l", "loop", Map.of()),
                node("x", "code", Map.of())),
            List.of(edge("e1", "a", "l", null), edge("e2", "x", "a", null)));
        List<String> codes = codes(run(g));
        assertTrue(codes.contains("no-trigger"), codes.toString());
        assertTrue(codes.contains("disconnected-node"), codes.toString());
        assertTrue(codes.contains("missing-error-route"), codes.toString());
        assertTrue(codes.contains("loop-no-max-iterations"), codes.toString());
    }

    @Test
    void unreachableNodeIsFlaggedAndOnErrorFallbackCountsAsARoute() {
        WorkflowGraphDto g = new WorkflowGraphDto(
            List.of(node("t", "trigger", Map.of()),
                node("h", "http", Map.of("url", "https://example.com", "onErrorNodeId", "c")),
                node("island1", "code", Map.of()), node("island2", "code", Map.of()),
                node("c", "code", Map.of())),
            List.of(edge("e1", "t", "h", null), edge("e2", "h", "c", null), edge("e3", "island1", "island2", null)));
        WorkflowLintService.LintResult r = run(g);
        assertTrue(r.issues().stream().anyMatch(i -> i.code().equals("unreachable-from-trigger") && "island1".equals(i.nodeId())));
        assertFalse(codes(r).contains("missing-error-route"), codes(r).toString());
    }

    @Test
    void danglingEdgeIsAnErrorWithADeleteFix() {
        WorkflowGraphDto g = new WorkflowGraphDto(
            List.of(node("t", "trigger", Map.of())), List.of(edge("bad", "t", "ghost", null)));
        WorkflowLintService.LintIssue issue = run(g).issues().stream()
            .filter(i -> i.code().equals("dangling-edge")).findFirst().orElseThrow();
        assertEquals("error", issue.severity());
        assertEquals("bad", issue.edgeId());
        assertEquals("delete-edge", issue.fix().get("kind"));
    }

    @Test
    void governanceErrorsAreMappedToCodesAndNodes() {
        WorkflowGraphDto g = new WorkflowGraphDto(
            List.of(node("t", "trigger", Map.of()), node("Writer", "agent", Map.of("agentId", UUID.randomUUID().toString())),
                node("Check", "condition", Map.of())),
            List.of(edge("e1", "t", "Writer", "error"), edge("e2", "Writer", "Check", null)));
        doThrow(new WorkflowValidationException(List.of(
            "Agent node 'Writer' references an agent that no longer exists.",
            "Condition node 'Check' has no condition configured (expected a 'contains' value to match against).")))
            .when(orchestrator).validate(any(WorkflowGraphDto.class), any(UUID.class));

        WorkflowLintService.LintResult r = run(g);

        WorkflowLintService.LintIssue missing = r.issues().stream()
            .filter(i -> i.code().equals("agent-ref-missing")).findFirst().orElseThrow();
        assertEquals("Writer", missing.nodeId());
        WorkflowLintService.LintIssue invalid = r.issues().stream()
            .filter(i -> i.code().equals("invalid-config")).findFirst().orElseThrow();
        assertEquals("Check", invalid.nodeId());
        assertEquals("error", invalid.severity());
    }

    @Test
    void missingProviderKeyIsFlaggedOnlyOffThePlatformPool() {
        UUID agentId = UUID.randomUUID();
        AgentResponse agent = mock(AgentResponse.class);
        when(agent.providerName()).thenReturn(ProviderName.values()[0]);
        when(agents.get(agentId)).thenReturn(agent);
        when(credentials.resolveActiveKey(any())).thenThrow(new IllegalStateException("not active"));
        WorkflowGraphDto g = new WorkflowGraphDto(
            List.of(node("t", "trigger", Map.of()), node("a", "agent", Map.of("agentId", agentId.toString()))),
            List.of(edge("e1", "t", "a", null), edge("e2", "a", "t", "error")));

        assertFalse(codes(run(g)).contains("provider-key-missing"), "platform pool: no key needed");

        when(limits.usesPlatformPool("acme")).thenReturn(false);
        assertTrue(codes(run(g)).contains("provider-key-missing"));
    }

    @Test
    void httpUrlRules() {
        WorkflowGraphDto g = new WorkflowGraphDto(
            List.of(node("t", "trigger", Map.of()),
                node("h1", "http", Map.of("url", "", "onErrorNodeId", "t")),
                node("h2", "http", Map.of("url", "http://api.example.com/x", "onErrorNodeId", "t")),
                node("h3", "http", Map.of("url", "http://localhost:8080/x", "onErrorNodeId", "t"))),
            List.of(edge("e1", "t", "h1", null), edge("e2", "h1", "h2", null), edge("e3", "h2", "h3", null)));
        List<WorkflowLintService.LintIssue> issues = run(g).issues();
        assertTrue(issues.stream().anyMatch(i -> i.code().equals("http-url-missing") && "h1".equals(i.nodeId())));
        WorkflowLintService.LintIssue insecure = issues.stream()
            .filter(i -> i.code().equals("http-insecure")).findFirst().orElseThrow();
        assertEquals("h2", insecure.nodeId());
        assertEquals("https://api.example.com/x", insecure.fix().get("value"));
        assertEquals(1, issues.stream().filter(i -> i.code().equals("http-insecure")).count(), "localhost is exempt");
    }

    @Test
    void costAndLatencyHintsComeFromCachedStats() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("avgCostUsd", new BigDecimal("0.120000"));
        stats.put("p50DurationMs", 45_000L);
        when(insights.summary(any(), any(), org.mockito.ArgumentMatchers.anyInt()))
            .thenReturn(Map.of("nodes", Map.of("c", stats)));
        WorkflowGraphDto g = new WorkflowGraphDto(
            List.of(node("t", "trigger", Map.of()), node("c", "code", Map.of())), List.of(edge("e1", "t", "c", null)));
        List<WorkflowLintService.LintIssue> issues = run(g).issues();
        assertTrue(issues.stream().anyMatch(i -> i.code().equals("cost-hint") && "info".equals(i.severity())));
        assertTrue(issues.stream().anyMatch(i -> i.code().equals("latency-hint")));
    }

    @Test
    void issueIdsAreStableAndErrorsSortFirst() {
        WorkflowGraphDto g = new WorkflowGraphDto(
            List.of(node("l", "loop", Map.of()), node("x", "code", Map.of())), List.of(edge("e", "l", "x", null)));
        WorkflowLintService.LintResult a = run(g);
        WorkflowLintService.LintResult b = run(g);
        assertEquals(a.issues().stream().map(WorkflowLintService.LintIssue::id).toList(),
            b.issues().stream().map(WorkflowLintService.LintIssue::id).toList());
        assertEquals("error", a.issues().get(0).severity());
        assertEquals(WorkflowLintService.issueId("loop-no-max-iterations", "l", null),
            a.issues().stream().filter(i -> i.code().equals("loop-no-max-iterations")).findFirst().orElseThrow().id());
        assertNotNull(a.issues().get(0).id());
        assertEquals(16, a.issues().get(0).id().length());
    }

    // --- approval gates ---

    private WorkflowGraphDto gated(Map<String, Object> approvalConfig) {
        return new WorkflowGraphDto(
            List.of(node("t", "trigger", Map.of()), node("gate", "approval", approvalConfig),
                node("send", "code", Map.of())),
            List.of(edge("e1", "t", "gate", null), edge("e2", "gate", "send", null)));
    }

    @Test
    void approvalNodeThatPolicyAutoApprovesIsFlaggedWithAnEnablePolicyFix() {
        when(policy.requiresApproval("workflow.node.approval")).thenReturn(false);
        WorkflowLintService.LintIssue issue = run(gated(Map.of())).issues().stream()
            .filter(i -> i.code().equals("approval-auto-approved")).findFirst().orElseThrow();
        assertEquals("gate", issue.nodeId());
        assertEquals("warning", issue.severity());
        assertEquals("enable-approval-policy", issue.fix().get("kind"));
        assertEquals("/api/approvals/policy/workflow.node.approval", issue.fix().get("url"));
    }

    @Test
    void requiresApprovalFlagOnAnyNodeUsesThatNodesActionType() {
        when(policy.requiresApproval("workflow.node.agent")).thenReturn(false);
        WorkflowGraphDto g = new WorkflowGraphDto(
            List.of(node("t", "trigger", Map.of()),
                node("a", "agent", Map.of("agentId", UUID.randomUUID().toString(), "requiresApproval", true))),
            List.of(edge("e1", "t", "a", null), edge("e2", "a", "t", "error")));
        assertTrue(run(g).issues().stream().anyMatch(i ->
            i.code().equals("approval-auto-approved") && "workflow.node.agent".equals(i.fix().get("actionType"))));
    }

    @Test
    void adminOnlyGateWithNobodyHoldingManageApprovalsIsAnError() {
        when(permissions.userIdsHolding("acme", "MANAGE_APPROVALS")).thenReturn(Set.of());
        WorkflowLintService.LintIssue issue = run(gated(Map.of("adminOnly", true))).issues().stream()
            .filter(i -> i.code().equals("approval-no-approvers")).findFirst().orElseThrow();
        assertEquals("error", issue.severity());
        assertEquals("gate", issue.nodeId());
    }

    @Test
    void healthyGatesProduceNoApprovalIssues() {
        List<String> codes = codes(run(gated(Map.of("adminOnly", true))));
        assertFalse(codes.contains("approval-auto-approved"), codes.toString());
        assertFalse(codes.contains("approval-no-approvers"), codes.toString());
        // Non-adminOnly gates never need MANAGE_APPROVALS holders.
        when(permissions.userIdsHolding("acme", "MANAGE_APPROVALS")).thenReturn(Set.of());
        assertFalse(codes(run(gated(Map.of()))).contains("approval-no-approvers"));
    }

    @Test
    void unknownHolderCountNeverClaimsNoApprovers() {
        when(permissions.userIdsHolding("acme", "MANAGE_APPROVALS")).thenThrow(new IllegalStateException("db down"));
        assertFalse(codes(run(gated(Map.of("adminOnly", true)))).contains("approval-no-approvers"));
    }

    // --- sends without a human gate ---

    @Test
    void ungatedSendIsAWarningWithAnInsertApprovalFix() {
        WorkflowGraphDto g = new WorkflowGraphDto(
            List.of(node("t", "trigger", Map.of()), node("each", "loop", Map.of("maxIterations", 5)),
                node("wa", "output", Map.of("channel", "whatsapp", "whatsappTo", "{{ $json.phone }}"))),
            List.of(edge("e1", "t", "each", null), edge("e2", "each", "wa", null)));
        WorkflowLintService.LintIssue issue = run(g).issues().stream()
            .filter(i -> i.code().equals("send-without-approval")).findFirst().orElseThrow();
        assertEquals("warning", issue.severity());
        assertEquals("wa", issue.nodeId());
        assertEquals("insert-approval", issue.fix().get("kind"));
        assertEquals("wa", issue.fix().get("nodeId"));
        assertEquals(List.of("each"), issue.fix().get("insertBeforeNodeIds"), "a loop body is gated in front of the loop");
        assertEquals(WorkflowLintService.issueId("send-without-approval", "wa", null), issue.id());
    }

    @Test
    void aReplyToWhoeverTriggeredTheRunIsOnlyASuggestion() {
        WorkflowGraphDto g = new WorkflowGraphDto(
            List.of(node("t", "trigger", Map.of()), node("wa", "output", Map.of("channel", "whatsapp"))),
            List.of(edge("e1", "t", "wa", null)));
        WorkflowLintService.LintIssue issue = run(g).issues().stream()
            .filter(i -> i.code().equals("send-without-approval")).findFirst().orElseThrow();
        assertEquals("info", issue.severity());
        assertEquals("insert-approval", issue.fix().get("kind"));
    }

    @Test
    void gatedSendsAndPlainOutputsAreNotFlagged() {
        WorkflowGraphDto gatedSend = new WorkflowGraphDto(
            List.of(node("t", "trigger", Map.of()), node("gate", "approval", Map.of()),
                node("mail", "output", Map.of("channel", "email"))),
            List.of(edge("e1", "t", "gate", null), edge("e2", "gate", "mail", null)));
        assertFalse(codes(run(gatedSend)).contains("send-without-approval"));

        WorkflowGraphDto logOnly = new WorkflowGraphDto(
            List.of(node("t", "trigger", Map.of()), node("o", "output", Map.of("channel", "log"))),
            List.of(edge("e1", "t", "o", null)));
        assertFalse(codes(run(logOnly)).contains("send-without-approval"));
    }
}
