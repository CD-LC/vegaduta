package com.agenticai.core.agents.a2a;

import com.agenticai.core.security.edition.ConditionalOnEditionFeature;
import com.agenticai.core.security.edition.EditionFeature;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The tenant-admin console surface for A2A: {@code /api/a2a/console/**}. This is deliberately NOT
 * part of the A2A protocol bindings ({@link A2aJsonRpcController}/{@link A2aRestController}) - the
 * spec's ListTasks answers UnsupportedOperationError on the wire, while the console needs exactly
 * that listing to show "who called my agents" and "which remote agents did my agents call".
 *
 * <p><b>Authorization:</b> the protocol surface scopes tasks to their owning subject
 * ({@link A2aTaskStore#find}); this surface crosses subjects - an inbound task is owned by the
 * EXTERNAL caller's subject, which is never the console user's - so every method is gated on the
 * manager authorities below (same trio {@code ScheduledTaskService#isManager} and {@code
 * AgentJobOperatorService} accept). The filter chain contributes {@code authenticated()}; the
 * {@code @PreAuthorize} is the real check, which is also what JAVA-006's
 * {@code AuthorizationCoverageTest} requires of a mutating endpoint without a dedicated rule.
 *
 * <p><b>What a task row may contain:</b> the caller's message text and the agent's reply. That is
 * the same content {@code AgentController}'s session/history views already show a tenant manager,
 * so no additional redaction layer is applied.
 */
@ConditionalOnEditionFeature(EditionFeature.A2A_DIRECTORY)
@RestController
@RequestMapping("/api/a2a/console")
@ConditionalOnProperty(
        prefix = "app.a2a",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
public class A2aConsoleController {

    /**
     * Tenant agent-managers and tenant/platform admins: same posture as the directory publish and
     * wallet-management rules ({@code PERM_MANAGE_AGENTS}) with the legacy admin roles kept so an
     * older-grant admin token still works.
     */
    static final String MANAGER =
            "hasAnyAuthority('PERM_MANAGE_AGENTS','ROLE_tenant-admin','ROLE_platform-admin')";

    /** Reply cap on the LIST endpoint; the detail endpoint returns the whole reply. */
    private static final int LIST_REPLY_PREVIEW_CHARS = 400;

    private final A2aTaskService taskService;
    private final A2aProperties properties;

    public A2aConsoleController(A2aTaskService taskService, A2aProperties properties) {
        this.taskService = taskService;
        this.properties = properties;
    }

    /**
     * {@code GET /api/a2a/console/tasks?direction=inbound|outbound&state=<TASK_STATE_*>&agentId=<uuid>&limit=<n>}
     * - newest first, summaries only (reply capped at {@value #LIST_REPLY_PREVIEW_CHARS} chars).
     */
    @GetMapping("/tasks")
    @PreAuthorize(MANAGER)
    public ResponseEntity<Map<String, Object>> tasks(
            @RequestParam(required = false) String direction,
            @RequestParam(required = false) String state,
            @RequestParam(required = false) String agentId,
            @RequestParam(defaultValue = "200") int limit) {
        int capped = Math.max(1, Math.min(limit, 500));
        List<Map<String, Object>> rows =
                taskService.consoleTasks().stream()
                        .filter(t -> direction == null || direction(t).equals(direction))
                        .filter(
                                t ->
                                        state == null
                                                || t.snapshot()
                                                        .state()
                                                        .wire(A2aVersion.V1_0)
                                                        .equals(state))
                        .filter(t -> agentId == null || t.agentId().toString().equals(agentId))
                        .sorted(Comparator.comparing(A2aTask::createdAt).reversed())
                        .limit(capped)
                        .map(t -> toRow(t, LIST_REPLY_PREVIEW_CHARS))
                        .toList();
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(Map.of("tasks", rows));
    }

    /** {@code GET /api/a2a/console/tasks/{id}} - the full record, reply included. */
    @GetMapping("/tasks/{id}")
    @PreAuthorize(MANAGER)
    public ResponseEntity<Map<String, Object>> task(@PathVariable String id) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(toRow(taskService.consoleGet(id), -1));
    }

    /**
     * {@code POST /api/a2a/console/tasks/{id}:cancel} - 404 unknown/cross-tenant, 409 already
     * terminal. Canceling an outbound record annotates the local bookkeeping only; the remote
     * agent's task is authoritative and unaffected (see {@link
     * A2aTaskService#consoleCancel}).
     */
    @PostMapping("/tasks/{id}:cancel")
    @PreAuthorize(MANAGER)
    public ResponseEntity<Map<String, Object>> cancel(@PathVariable String id) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(toRow(taskService.consoleCancel(id), -1));
    }

    /**
     * {@code GET /api/a2a/console/policy} - the tenant-readable half of the operator outbound
     * policy: which remote A2A hosts this tenant's agents may call (the allow-list is
     * operator-set on purpose - a stolen admin session must not be able to widen egress, so this
     * view is read-only like {@code OutboundPolicyController}) plus this deployment's inbound
     * endpoints so the UI can show the URLs to hand a peer.
     */
    @GetMapping("/policy")
    @PreAuthorize(MANAGER)
    public ResponseEntity<Map<String, Object>> policy() {
        A2aProperties.Client client = properties.getClient();
        Map<String, Object> clientView = new LinkedHashMap<>();
        clientView.put("enabled", client.isEnabled());
        clientView.put(
                "allowedHosts",
                client.getAllowedHosts() == null ? List.of() : client.getAllowedHosts());
        clientView.put("requireSignedCard", client.isRequireSignedCard());
        clientView.put("maxCallsPerTurn", client.getMaxCallsPerTurn());
        clientView.put("maxWaitSeconds", client.getMaxWait().toSeconds());

        Map<String, Object> serverView = new LinkedHashMap<>();
        serverView.put("enabled", properties.isEnabled());
        serverView.put("jsonRpcPath", A2aJsonRpcController.PLATFORM_ROUTE);
        serverView.put("restPath", A2aRestController.PLATFORM_BASE);
        serverView.put("agentJsonRpcPath", "/api/agents/{agentId}/a2a");
        serverView.put("agentRestPath", "/api/agents/{agentId}/a2a/rest");
        serverView.put("agentCardPath", "/api/agents/{agentId}/a2a/agent-card.json");
        serverView.put("platformCardPath", A2aAgentCardController.WELL_KNOWN_CARD);
        serverView.put("jwksPath", A2aAgentCardController.JWKS);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("client", clientView);
        body.put("server", serverView);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }

    private static String direction(A2aTask task) {
        return task.id().startsWith(A2aTask.OUTBOUND_ID_PREFIX) ? "outbound" : "inbound";
    }

    private static Map<String, Object> toRow(A2aTask task, int replyCap) {
        A2aTask.Snapshot snap = task.snapshot();
        String reply = snap.reply() == null ? "" : snap.reply();
        boolean truncated = replyCap >= 0 && reply.length() > replyCap;
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", task.id());
        row.put("direction", direction(task));
        row.put("agentId", task.agentId().toString());
        row.put("contextId", task.contextId());
        row.put("subject", task.subject() == null ? "" : task.subject());
        row.put("state", snap.state().wire(A2aVersion.V1_0));
        row.put("createdAt", iso(task.createdAt()));
        row.put("stateTimestamp", iso(snap.timestamp()));
        row.put("lastTouched", iso(task.lastTouched()));
        row.put("userText", task.userText() == null ? "" : task.userText());
        row.put("reply", truncated ? reply.substring(0, replyCap) : reply);
        row.put("replyTruncated", truncated);
        row.put("replyLength", reply.length());
        row.put("failureMessage", snap.failureMessage() == null ? "" : snap.failureMessage());
        row.put("terminal", snap.state().isTerminal());
        return row;
    }

    private static String iso(Instant instant) {
        return instant == null ? null : instant.toString();
    }
}
