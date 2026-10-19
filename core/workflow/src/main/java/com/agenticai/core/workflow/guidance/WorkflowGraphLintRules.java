package com.agenticai.core.workflow.guidance;

import static com.agenticai.core.workflow.guidance.WorkflowLintService.issue;

import com.agenticai.core.workflow.dto.WorkflowEdgeDto;
import com.agenticai.core.workflow.dto.WorkflowGraphDto;
import com.agenticai.core.workflow.dto.WorkflowNodeDto;
import com.agenticai.core.workflow.guidance.WorkflowLintService.LintIssue;
import java.net.URI;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The lint rules of {@link WorkflowLintService} that need nothing but the graph: the parity rules shared with the
 * browser's {@code canvasOps.ts#lintGraph}, the HTTP URL checks and the outbound-send approval check.
 */
final class WorkflowGraphLintRules {

    private static final Logger log = LoggerFactory.getLogger(WorkflowGraphLintRules.class);

    static final Set<String> ERROR_ROUTED_TYPES = Set.of("agent", "http", "tool");

    private WorkflowGraphLintRules() {
    }

    // --- parity rules (same codes and logic as canvasOps.ts#lintGraph) ---

    static void shapeRules(
            List<WorkflowNodeDto> nodes, List<WorkflowEdgeDto> edges, List<LintIssue> issues) {
        Set<String> ids = new HashSet<>();
        for (WorkflowNodeDto n : nodes) {
            ids.add(n.id());
        }
        List<WorkflowEdgeDto> valid = new ArrayList<>();
        for (WorkflowEdgeDto e : edges) {
            if (ids.contains(e.source()) && ids.contains(e.target())) {
                valid.add(e);
            } else {
                issues.add(
                        issue(
                                "dangling-edge",
                                "error",
                                null,
                                e.id(),
                                "A connection points at a node that no longer exists - delete it.",
                                Map.of("kind", "delete-edge", "edgeId", String.valueOf(e.id()))));
            }
        }
        List<WorkflowNodeDto> triggers =
                nodes.stream().filter(n -> "trigger".equalsIgnoreCase(n.type())).toList();
        if (!nodes.isEmpty() && triggers.isEmpty()) {
            issues.add(
                    issue(
                            "no-trigger",
                            "error",
                            null,
                            null,
                            "Add a Trigger node so the workflow has a starting point.",
                            Map.of("kind", "add-node", "type", "trigger")));
        }
        Set<String> touched = new HashSet<>();
        Map<String, List<String>> out = new HashMap<>();
        for (WorkflowEdgeDto e : valid) {
            touched.add(e.source());
            touched.add(e.target());
            out.computeIfAbsent(e.source(), k -> new ArrayList<>()).add(e.target());
        }
        Set<String> reach = new HashSet<>();
        Deque<String> stack = new ArrayDeque<>();
        triggers.forEach(t -> stack.push(t.id()));
        while (!stack.isEmpty()) {
            String id = stack.pop();
            if (reach.add(id)) {
                out.getOrDefault(id, List.of()).forEach(stack::push);
            }
        }
        for (WorkflowNodeDto n : nodes) {
            String name = n.label();
            String type = n.type() == null ? "" : n.type().toLowerCase(Locale.ROOT);
            if (nodes.size() > 1 && !touched.contains(n.id())) {
                issues.add(
                        issue(
                                "disconnected-node",
                                "warning",
                                n.id(),
                                null,
                                "\"" + name + "\" is not connected to anything.",
                                null));
            } else if (!triggers.isEmpty() && touched.contains(n.id()) && !reach.contains(n.id())) {
                issues.add(
                        issue(
                                "unreachable-from-trigger",
                                "warning",
                                n.id(),
                                null,
                                "\""
                                        + name
                                        + "\" can never run: no path leads to it from a Trigger.",
                                null));
            }
            if (ERROR_ROUTED_TYPES.contains(type) && !hasErrorRoute(n, valid)) {
                issues.add(
                        issue(
                                "missing-error-route",
                                "warning",
                                n.id(),
                                null,
                                "\"" + name + "\" has no error route; a failure will stop the run.",
                                Map.of("kind", "add-error-route", "nodeId", n.id())));
            }
            if ("loop".equals(type)) {
                Object max = n.config().get("maxIterations");
                if (!(max instanceof Number num)
                        || !(num.doubleValue() > 0)
                        || Double.isInfinite(num.doubleValue())) {
                    issues.add(
                            issue(
                                    "loop-no-max-iterations",
                                    "warning",
                                    n.id(),
                                    null,
                                    "\""
                                            + name
                                            + "\" has no max iterations; a long list could run away.",
                                    Map.of(
                                            "kind",
                                            "set-config",
                                            "nodeId",
                                            n.id(),
                                            "path",
                                            "config.maxIterations",
                                            "value",
                                            25)));
                }
            }
        }
    }

    private static boolean hasErrorRoute(WorkflowNodeDto node, List<WorkflowEdgeDto> edges) {
        for (WorkflowEdgeDto e : edges) {
            if (node.id().equals(e.source())
                    && "error".equalsIgnoreCase(e.sourceHandle() == null ? "" : e.sourceHandle())) {
                return true;
            }
        }
        Object fallback = node.config().get("onErrorNodeId");
        return fallback != null && !fallback.toString().isBlank();
    }

    // --- http ---

    static void httpRules(List<WorkflowNodeDto> nodes, List<LintIssue> issues) {
        for (WorkflowNodeDto n : nodes) {
            if (!"http".equalsIgnoreCase(n.type())) {
                continue;
            }
            Object url = n.config().get("url");
            String u = url == null ? "" : url.toString().strip();
            if (u.isEmpty()) {
                issues.add(
                        issue(
                                "http-url-missing",
                                "error",
                                n.id(),
                                null,
                                "\"" + n.label() + "\" has no URL.",
                                Map.of(
                                        "kind",
                                        "set-config",
                                        "nodeId",
                                        n.id(),
                                        "path",
                                        "config.url")));
                continue;
            }
            if (u.toLowerCase(Locale.ROOT).startsWith("http://") && !isLocal(u)) {
                issues.add(
                        issue(
                                "http-insecure",
                                "warning",
                                n.id(),
                                null,
                                "\""
                                        + n.label()
                                        + "\" calls a plain http:// URL; data (and any auth header) travels unencrypted.",
                                Map.of(
                                        "kind",
                                        "set-config",
                                        "nodeId",
                                        n.id(),
                                        "path",
                                        "config.url",
                                        "value",
                                        "https://" + u.substring("http://".length()))));
            }
        }
    }

    private static boolean isLocal(String url) {
        try {
            String host = URI.create(url).getHost();
            return host != null
                    && (host.equals("localhost")
                            || host.equals("127.0.0.1")
                            || host.endsWith(".internal"));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    // --- outbound sends without a human gate ---

    /**
     * {@code send-without-approval} (warning): a node that messages someone or writes to an
     * external system ({@link WorkflowSendApprovalGuard#isOutboundSend}) that a Trigger can reach
     * without passing an approval / human-input step. Advisory, with a one-click {@code
     * insert-approval} fix. A reply to whoever triggered the run ({@link
     * WorkflowSendApprovalGuard#repliesToTriggerSender}) is normally meant to go out unattended, so
     * there it is only an {@code info} suggestion - an existing chatbot-style workflow does not
     * suddenly carry a warning. (The web's lint parser drops a fix kind it does not know and keeps
     * the issue, so an older canvas shows the message with no button.) {@code insertBeforeNodeIds}
     * differs from {@code nodeId} when the send is a loop / fan-out body: the gate then belongs in
     * front of that container.
     */
    static void sendRules(WorkflowGraphDto graph, List<LintIssue> issues) {
        List<WorkflowSendApprovalGuard.Finding> findings;
        try {
            findings = WorkflowSendApprovalGuard.findings(graph);
        } catch (RuntimeException e) {
            log.debug("Lint could not check sends for approval gates: {}", e.getMessage());
            return;
        }
        for (WorkflowSendApprovalGuard.Finding finding : findings) {
            WorkflowNodeDto n = finding.send();
            Map<String, Object> fix = new LinkedHashMap<>();
            fix.put("kind", "insert-approval");
            fix.put("nodeId", n.id());
            fix.put("insertBeforeNodeIds", finding.insertBeforeNodeIds());
            String severity =
                    WorkflowSendApprovalGuard.repliesToTriggerSender(n) ? "info" : "warning";
            issues.add(
                    issue(
                            "send-without-approval",
                            severity,
                            n.id(),
                            null,
                            "\""
                                    + n.label()
                                    + "\" sends without anyone checking it first - add an approval step before it "
                                    + "if a person should review what goes out.",
                            fix));
        }
    }
}
