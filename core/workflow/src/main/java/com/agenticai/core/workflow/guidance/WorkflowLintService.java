package com.agenticai.core.workflow.guidance;

import com.agenticai.core.admin.permission.Permission;
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
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * The canvas lint panel's server half (phase 4). The browser already lints graph SHAPE ({@code
 * web/app/components/workflow/canvasOps.ts#lintGraph}); this re-runs those rules under the SAME
 * codes (so the panel can dedupe the two sources by {@link LintIssue#id()}) and adds what only the
 * server knows.
 *
 * <p>Codes (kebab-case, stable - the web panel keys on them):
 *
 * <ul>
 *   <li>Parity with the client: {@code no-trigger} (error), {@code disconnected-node}, {@code
 *       unreachable-from-trigger}, {@code missing-error-route} (agent/http/tool with neither an
 *       "error" edge nor an {@code onErrorNodeId} fallback - the engine honours both), {@code
 *       loop-no-max-iterations} (warnings).
 *   <li>Engine governance ({@link OrchestratorService#validate}, the check Activate and every run
 *       enforce): {@code agent-ref-missing} for a deleted agent, {@code invalid-config} for
 *       everything else it rejects - these are errors because the run WILL refuse to start. The
 *       node is found by the quoted label in the message when that label is unique.
 *   <li>Server-only: {@code dangling-edge} (error: an edge to a node that is not in the graph),
 *       {@code provider-key-missing} (error: the agent's provider has no active key and the tenant
 *       is not on the platform pool), {@code http-url-missing} (error), {@code http-insecure}
 *       (warning: plain http to a non-local host), {@code cost-hint} / {@code latency-hint} (info,
 *       from {@link WorkflowInsightsService}'s cached 30-day summary: average node cost at or above
 *       {@link #COST_HINT_USD}, median duration at or above {@link #LATENCY_HINT_MS}), {@code
 *       send-without-approval} (warning: an outbound send no human gate precedes, see {@link
 *       WorkflowSendApprovalGuard}; fix kind {@code insert-approval}).
 * </ul>
 *
 * <p>Approval gates. An Approval node (or any node with {@code requiresApproval}) has no approver
 * list of its own: whether it pauses is decided by the tenant's approval POLICY for action type
 * {@code workflow.node.<type>} ({@link ApprovalPolicyService}), and who may decide is decided by
 * {@code ApprovalController} (any member with run access to the workflow, except the requester -
 * see {@code WorkflowApprovalDecisionAuthorizer}; {@code adminOnly} nodes: {@code
 * PERM_MANAGE_APPROVALS} holders only). So "no approvers" is linted where it can actually happen:
 *
 * <ul>
 *   <li>{@code approval-auto-approved} (warning): the policy does not require approval for this
 *       action type, so the gate never pauses - the run continues with nobody deciding. Out of the
 *       box NONE of the {@code workflow.node.*} types match a default sensitive pattern, so every
 *       Approval node in a tenant that never set an override auto-approves; this rule is how an
 *       author finds out.
 *   <li>{@code approval-no-approvers} (error): an {@code adminOnly} gate that does pause, in a
 *       tenant where no user holds {@code MANAGE_APPROVALS} - runs would wait forever. Counts
 *       stored grants, which are seeded on a user's first request after the RBAC rollout, so an
 *       admin who has never signed in since is not counted.
 * </ul>
 *
 * Not linted: "the requester is the only member who could approve" - the member count lives in
 * Keycloak, not the tenant schema.
 *
 * <p>Cheap by design (called on a debounce while editing): one validation pass, one agent lookup
 * and at most one credential lookup per distinct agent/provider, and cached run statistics.
 */
@Service
public class WorkflowLintService {

    private static final Logger log = LoggerFactory.getLogger(WorkflowLintService.class);

    static final BigDecimal COST_HINT_USD = new BigDecimal("0.05");
    static final long LATENCY_HINT_MS = 30_000;

    private static final Pattern QUOTED = Pattern.compile("'([^']+)'");

    private final OrchestratorService orchestratorService;
    private final WorkflowInsightsService insightsService;
    private final ObjectProvider<AgentService> agentService;
    private final ObjectProvider<ProviderCredentialResolver> credentialResolver;
    private final ObjectProvider<TenantLimits> tenantLimits;
    private final ObjectProvider<ApprovalPolicyService> approvalPolicy;
    private final ObjectProvider<UserPermissionService> permissions;

    public WorkflowLintService(
            OrchestratorService orchestratorService,
            WorkflowInsightsService insightsService,
            ObjectProvider<AgentService> agentService,
            ObjectProvider<ProviderCredentialResolver> credentialResolver,
            ObjectProvider<TenantLimits> tenantLimits,
            ObjectProvider<ApprovalPolicyService> approvalPolicy,
            ObjectProvider<UserPermissionService> permissions) {
        this.orchestratorService = orchestratorService;
        this.insightsService = insightsService;
        this.agentService = agentService;
        this.credentialResolver = credentialResolver;
        this.tenantLimits = tenantLimits;
        this.approvalPolicy = approvalPolicy;
        this.permissions = permissions;
    }

    /** {@code fix} is a machine hint for the panel ({@code kind} + fields), or null. */
    public record LintIssue(
            String id,
            String code,
            String severity,
            String nodeId,
            String edgeId,
            String message,
            Map<String, Object> fix) {}

    public record LintResult(List<LintIssue> issues, Map<String, Integer> summary) {}

    /**
     * @param workflowId the saved workflow (VIEWER already checked by the caller); used for
     *     self-reference checks and hints
     * @param graph the graph to lint - the unsaved canvas graph when the caller sent one, else the
     *     saved graph
     * @param savedGraph the saved graph, for eval/insight lookups (may equal {@code graph})
     */
    public LintResult lint(UUID workflowId, WorkflowGraphDto graph, WorkflowGraphDto savedGraph) {
        List<LintIssue> issues = new ArrayList<>();
        List<WorkflowNodeDto> nodes = graph == null ? List.of() : graph.nodes();
        List<WorkflowEdgeDto> edges = graph == null ? List.of() : graph.edges();

        WorkflowGraphLintRules.shapeRules(nodes, edges, issues);
        governanceRules(workflowId, graph, nodes, issues);
        agentRules(nodes, issues);
        WorkflowGraphLintRules.httpRules(nodes, issues);
        approvalRules(nodes, issues);
        WorkflowGraphLintRules.sendRules(graph, issues);
        hintRules(workflowId, savedGraph, nodes, issues);

        Map<String, LintIssue> byId = new LinkedHashMap<>();
        for (LintIssue i : issues) {
            byId.putIfAbsent(i.id(), i);
        }
        List<LintIssue> deduped = new ArrayList<>(byId.values());
        deduped.sort(
                Comparator.comparingInt((LintIssue i) -> rank(i.severity()))
                        .thenComparing(LintIssue::code)
                        .thenComparing(i -> i.nodeId() == null ? "" : i.nodeId()));
        Map<String, Integer> summary = new LinkedHashMap<>();
        summary.put(
                "errors", (int) deduped.stream().filter(i -> "error".equals(i.severity())).count());
        summary.put(
                "warnings",
                (int) deduped.stream().filter(i -> "warning".equals(i.severity())).count());
        summary.put(
                "infos", (int) deduped.stream().filter(i -> "info".equals(i.severity())).count());
        return new LintResult(deduped, summary);
    }

    // --- engine governance ---

    private void governanceRules(
            UUID workflowId,
            WorkflowGraphDto graph,
            List<WorkflowNodeDto> nodes,
            List<LintIssue> issues) {
        if (graph == null || nodes.isEmpty()) {
            return;
        }
        try {
            orchestratorService.validate(graph, workflowId);
        } catch (WorkflowValidationException e) {
            for (String message : e.getErrors()) {
                // Shape problems the parity rules already report precisely.
                if (message != null
                        && message.toLowerCase(Locale.ROOT).contains("trigger node")
                        && nodes.stream().noneMatch(n -> "trigger".equalsIgnoreCase(n.type()))) {
                    continue;
                }
                String nodeId = nodeIdForMessage(message, nodes);
                String code =
                        message != null
                                        && message.contains(
                                                "references an agent that no longer exists")
                                ? "agent-ref-missing"
                                : "invalid-config";
                // invalid-config can fire several times for one node: the
                // message joins the id so each survives dedupe.
                String id =
                        "invalid-config".equals(code)
                                ? issueId(code, nodeId, null, message)
                                : issueId(code, nodeId, null);
                issues.add(
                        new LintIssue(
                                id,
                                code,
                                "error",
                                nodeId,
                                null,
                                message,
                                nodeId == null
                                        ? null
                                        : Map.of("kind", "open-config", "nodeId", nodeId)));
            }
        } catch (RuntimeException e) {
            log.debug(
                    "Lint could not run governance validation for workflow {}: {}",
                    workflowId,
                    e.getMessage());
        }
    }

    /** The node whose label is quoted in {@code message}, when exactly one node has that label. */
    static String nodeIdForMessage(String message, List<WorkflowNodeDto> nodes) {
        if (message == null) {
            return null;
        }
        Matcher m = QUOTED.matcher(message);
        while (m.find()) {
            String quoted = m.group(1);
            List<WorkflowNodeDto> matches =
                    nodes.stream().filter(n -> quoted.equals(n.label())).toList();
            if (matches.size() == 1) {
                return matches.get(0).id();
            }
        }
        return null;
    }

    // --- agent / provider ---

    private void agentRules(List<WorkflowNodeDto> nodes, List<LintIssue> issues) {
        AgentService agents = agentService.getIfAvailable();
        if (agents == null) {
            return;
        }
        String tenantId = TenantContext.current().map(TenantContext::getTenantId).orElse(null);
        TenantLimits limits = tenantLimits.getIfAvailable();
        boolean platformPool;
        try {
            platformPool = limits == null || tenantId == null || limits.usesPlatformPool(tenantId);
        } catch (RuntimeException e) {
            platformPool = true;
        }
        if (platformPool) {
            // Hosted pool serves every agent - no tenant key to be missing.
            return;
        }
        Map<String, AgentResponse> agentCache = new HashMap<>();
        Map<ProviderName, Boolean> keyCache = new HashMap<>();
        for (WorkflowNodeDto n : nodes) {
            String type = n.type() == null ? "" : n.type().toLowerCase(Locale.ROOT);
            if (!"agent".equals(type) && !"supervisor".equals(type)) {
                continue;
            }
            Object raw = n.config().get("agentId");
            if (raw == null || raw.toString().isBlank()) {
                continue;
            }
            AgentResponse agent =
                    agentCache.computeIfAbsent(
                            raw.toString(),
                            id -> {
                                try {
                                    return agents.get(UUID.fromString(id));
                                } catch (RuntimeException e) {
                                    return null; // missing agent is governance's agent-ref-missing
                                }
                            });
            if (agent == null || agent.providerName() == null) {
                continue;
            }
            boolean hasKey = keyCache.computeIfAbsent(agent.providerName(), this::hasActiveKey);
            if (!hasKey) {
                issues.add(
                        issue(
                                "provider-key-missing",
                                "error",
                                n.id(),
                                null,
                                "\""
                                        + n.label()
                                        + "\" uses "
                                        + agent.providerName()
                                        + " but no active "
                                        + agent.providerName()
                                        + " key is set up for this workspace - the node will fail.",
                                Map.of("kind", "open-url", "url", "/providers")));
            }
        }
    }

    private boolean hasActiveKey(ProviderName provider) {
        ProviderCredentialResolver resolver = credentialResolver.getIfAvailable();
        if (resolver == null) {
            return true;
        }
        try {
            return resolver.resolveActiveKey(provider) != null;
        } catch (RuntimeException e) {
            return false;
        }
    }

    // --- approval gates ---

    private void approvalRules(List<WorkflowNodeDto> nodes, List<LintIssue> issues) {
        ApprovalPolicyService policy = approvalPolicy.getIfAvailable();
        if (policy == null) {
            return;
        }
        Map<String, Boolean> required = new HashMap<>();
        Set<String> approvers = null;
        for (WorkflowNodeDto n : nodes) {
            String type = n.type() == null ? "" : n.type().toLowerCase(Locale.ROOT);
            boolean gated =
                    "approval".equals(type)
                            || Boolean.TRUE.equals(n.config().get("requiresApproval"));
            if (!gated || type.isEmpty()) {
                continue;
            }
            String actionType = "workflow.node." + type;
            boolean pauses;
            try {
                pauses = required.computeIfAbsent(actionType, policy::requiresApproval);
            } catch (RuntimeException e) {
                continue;
            }
            if (!pauses) {
                Map<String, Object> fix = new LinkedHashMap<>();
                fix.put("kind", "enable-approval-policy");
                fix.put("actionType", actionType);
                fix.put("method", "PUT");
                fix.put("url", "/api/approvals/policy/" + actionType);
                fix.put("body", Map.of("requiresApproval", true));
                issues.add(
                        issue(
                                "approval-auto-approved",
                                "warning",
                                n.id(),
                                null,
                                "\""
                                        + n.label()
                                        + "\" will not pause for approval: this workspace's approval policy auto-approves \""
                                        + actionType
                                        + "\", so runs continue without anyone deciding. Turn approval on for it "
                                        + "(needs Manage approvals).",
                                fix));
                continue;
            }
            if (Boolean.TRUE.equals(n.config().get("adminOnly"))) {
                if (approvers == null) {
                    approvers = approvalHolders();
                }
                if (approvers != null && approvers.isEmpty()) {
                    issues.add(
                            issue(
                                    "approval-no-approvers",
                                    "error",
                                    n.id(),
                                    null,
                                    "\""
                                            + n.label()
                                            + "\" can only be approved by someone with Manage approvals, and nobody in "
                                            + "this workspace has it - runs would wait here forever. Grant it to at least one person.",
                                    Map.of("kind", "open-url", "url", "/admin/access")));
                }
            }
        }
    }

    /**
     * User ids holding MANAGE_APPROVALS, or null when that cannot be determined (never "empty" on
     * doubt).
     */
    private Set<String> approvalHolders() {
        UserPermissionService service = permissions.getIfAvailable();
        String tenantId = TenantContext.current().map(TenantContext::getTenantId).orElse(null);
        if (service == null || tenantId == null) {
            return null;
        }
        try {
            return service.userIdsHolding(tenantId, Permission.MANAGE_APPROVALS.name());
        } catch (RuntimeException e) {
            return null;
        }
    }


    // --- hints from cached run statistics ---

    @SuppressWarnings("unchecked")
    private void hintRules(
            UUID workflowId,
            WorkflowGraphDto savedGraph,
            List<WorkflowNodeDto> nodes,
            List<LintIssue> issues) {
        if (workflowId == null) {
            return;
        }
        Map<String, Object> nodeStats;
        try {
            nodeStats =
                    (Map<String, Object>)
                            insightsService.summary(workflowId, savedGraph, 30).get("nodes");
        } catch (RuntimeException e) {
            return;
        }
        if (nodeStats == null) {
            return;
        }
        for (WorkflowNodeDto n : nodes) {
            if (!(nodeStats.get(n.id()) instanceof Map<?, ?> stats)) {
                continue;
            }
            if (stats.get("avgCostUsd") instanceof BigDecimal cost
                    && cost.compareTo(COST_HINT_USD) >= 0) {
                issues.add(
                        issue(
                                "cost-hint",
                                "info",
                                n.id(),
                                null,
                                "\""
                                        + n.label()
                                        + "\" averages $"
                                        + cost.stripTrailingZeros().toPlainString()
                                        + " per run over the last 30 days - consider a cheaper model or step caching.",
                                null));
            }
            if (stats.get("p50DurationMs") instanceof Long ms && ms >= LATENCY_HINT_MS) {
                issues.add(
                        issue(
                                "latency-hint",
                                "info",
                                n.id(),
                                null,
                                "\""
                                        + n.label()
                                        + "\" usually takes "
                                        + (ms / 1000)
                                        + " s - it dominates run time.",
                                null));
            }
        }
    }

    // --- helpers ---

    static LintIssue issue(
            String code,
            String severity,
            String nodeId,
            String edgeId,
            String message,
            Map<String, Object> fix) {
        return new LintIssue(
                issueId(code, nodeId, edgeId), code, severity, nodeId, edgeId, message, fix);
    }

    /**
     * Stable across calls and identical to what the client can compute: sha-256 of
     * code|nodeId|edgeId, 16 hex chars.
     */
    static String issueId(String code, String nodeId, String edgeId) {
        return issueId(code, nodeId, edgeId, null);
    }

    static String issueId(String code, String nodeId, String edgeId, String discriminator) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            String key =
                    code
                            + "|"
                            + (nodeId == null ? "" : nodeId)
                            + "|"
                            + (edgeId == null ? "" : edgeId)
                            + (discriminator == null ? "" : "|" + discriminator);
            byte[] digest = sha.digest(key.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 8);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static int rank(String severity) {
        return switch (severity) {
            case "error" -> 0;
            case "warning" -> 1;
            default -> 2;
        };
    }
}
