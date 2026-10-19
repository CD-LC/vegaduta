package com.agenticai.core.codeintel;

import com.agenticai.core.approval.ApprovalEvaluation;
import com.agenticai.core.approval.HumanApprovalGateService;
import com.agenticai.core.json.JsonMappers;
import com.agenticai.core.security.TenantContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Core-side proxy for the web module's {@code /code-intel} console, so the
 * browser talks to core (localhost:8080, its normal Keycloak session) instead
 * of hitting the standalone code-intel service directly on its own origin.
 *
 * <p><b>Why this exists (the governance backbone).</b> Before this, the
 * {@code /code-intel} page called the service directly with the user's own
 * token, entirely outside {@code core/approval} - so a code write/execute
 * initiated by a human from that page never appeared on the platform's one
 * central Approvals screen, and (until a later patch) a non-admin could even
 * self-approve it inside the service's own disconnected {@code /approvals}
 * flow (see {@code code-intel-approval-reconciliation/}). Routing the page
 * through this controller closes that: the two mutating actions
 * ({@code apply}, {@code run}) go through the SAME {@link
 * HumanApprovalGateService#evaluate} call, the SAME {@link
 * ApplyCodeWorkspaceApprovalExecutor}/{@link RunCodeWorkspaceApprovalExecutor}
 * with the SAME {@code adminOnly:true} payload that an AGENT's
 * {@code apply_code_changes}/{@code run_in_code_workspace} tool call already
 * uses. A human-initiated write and an agent-initiated write are now the same
 * request type on the same Approvals screen, decided by the same
 * ROLE_tenant-admin rule ({@code ApprovalController#enforceApproverAuthorization}).
 *
 * <p>Everything else here is a transparent, tenant-scoped read/config
 * pass-through to {@link CodeIntelClient} (which adds {@code X-Tenant-Id} +
 * {@code X-Internal-Token}); none of those mutate code or run commands. Every
 * pass-through endpoint returns {@code ResponseEntity<String>} carrying the
 * REAL upstream HTTP status (via {@link CodeIntelClient.Result}, not the
 * plain-{@code String}-returning methods {@code CodeIntelClient} also exposes
 * for {@code ToolCallback} consumers) - until 2026-08-14 every one of these
 * always answered 200 regardless of what the service actually returned, so a
 * failed "create workspace" (403 exec-disabled, 404 unregistered repo, 500
 * upstream error, service unreachable) looked identical to a successful one
 * to the browser: the web console's error handling only triggers on a
 * non-2xx response, so it silently rendered a workspace object with no
 * {@code workspace_id} instead of showing the real error. Status 0 (no HTTP
 * response ever arrived - the service was unreachable) maps to 502. The
 * two genuinely powerful non-apply/run actions that DO reach the service's
 * write surface without an approval request - resolving code-intel's own
 * (now vestigial) approvals, and the autonomous SDLC agent - are gated to
 * ROLE_tenant-admin here, because code-intel treats this internal-token
 * caller as trusted/admin (see {@code CodeIntelService._is_admin}), so
 * without this check a non-admin browser user could reach them THROUGH core.
 *
 * <p>Only created when {@code code-intel.enabled=true} (via {@code
 * @ConditionalOnBean(CodeIntelClient.class)}), exactly like the approval
 * executors - the whole feature stays absent and startup-safe when off.
 */
@RestController
@RequestMapping(value = "/api/code-intel", produces = MediaType.APPLICATION_JSON_VALUE)
@ConditionalOnBean(CodeIntelClient.class)
public class CodeIntelController {

    private static final Logger log = LoggerFactory.getLogger(CodeIntelController.class);
    private static final String TENANT_ADMIN_AUTHORITY = "ROLE_tenant-admin";
    private static final ObjectMapper MAPPER = JsonMappers.plain();

    private final CodeIntelClient client;
    private final HumanApprovalGateService gateService;

    public CodeIntelController(CodeIntelClient client, HumanApprovalGateService gateService) {
        this.client = client;
        this.gateService = gateService;
    }

    // ---- liveness ----------------------------------------------------- //

    @GetMapping("/health")
    public ResponseEntity<String> health() {
        // Proxy the service's real health so the console's pill shows the
        // actual {status, auth_mode} (a core stub omitted auth_mode -> the UI
        // rendered "service: undefined").
        return respond(client.healthResult());
    }

    // ---- repos + index (reads/config) --------------------------------- //

    @GetMapping("/repos")
    public ResponseEntity<String> listRepos() {
        return respond(client.listReposResult());
    }

    @PostMapping("/repos")
    public ResponseEntity<String> registerRepo(@RequestBody(required = false) String body) {
        return respond(client.registerRepoResult(body == null ? "{}" : body));
    }

    @DeleteMapping("/repos/{repoId}")
    public ResponseEntity<String> deleteRepo(@PathVariable String repoId) {
        return respond(client.deleteRepoResult(repoId));
    }

    @PostMapping("/repos/{repoId}/index")
    public ResponseEntity<String> indexRepo(@PathVariable String repoId, @RequestBody(required = false) String body) {
        return respond(client.indexRepoResult(repoId, readBool(body, "full", false)));
    }

    @GetMapping("/repos/{repoId}/status")
    public ResponseEntity<String> repoStatus(@PathVariable String repoId) {
        return respond(client.repoStatusResult(repoId));
    }

    // ---- search + graph (reads) --------------------------------------- //

    @PostMapping("/repos/{repoId}/search")
    public ResponseEntity<String> search(@PathVariable String repoId, @RequestBody String body) {
        JsonNode json = read(body);
        String query = json.path("query").asText("");
        int topK = json.path("top_k").asInt(8);
        return respond(client.searchCodeResult(query, repoId, topK));
    }

    @PostMapping("/knowledge/query")
    public ResponseEntity<String> knowledgeQuery(@RequestBody(required = false) String body) {
        return respond(client.knowledgeQueryResult(body == null ? "{}" : body));
    }

    @PostMapping("/repos/{repoId}/graph/build")
    public ResponseEntity<String> graphBuild(@PathVariable String repoId) {
        return respond(client.graphBuildResult(repoId));
    }

    @GetMapping("/repos/{repoId}/graph/stats")
    public ResponseEntity<String> graphStats(@PathVariable String repoId) {
        return respond(client.graphStatsResult(repoId));
    }

    @GetMapping("/repos/{repoId}/graph/callers/{symbol}")
    public ResponseEntity<String> callers(@PathVariable String repoId, @PathVariable String symbol) {
        return respond(client.callersResult(repoId, symbol));
    }

    @GetMapping("/repos/{repoId}/graph/callees/{symbol}")
    public ResponseEntity<String> callees(@PathVariable String repoId, @PathVariable String symbol) {
        return respond(client.calleesResult(repoId, symbol));
    }

    @GetMapping("/repos/{repoId}/graph/impact/{symbol}")
    public ResponseEntity<String> impact(@PathVariable String repoId, @PathVariable String symbol,
                         @RequestParam(defaultValue = "3") int depth) {
        return respond(client.impactResult(repoId, symbol, depth));
    }

    // ---- guardrail policy + audit (config/reads) ---------------------- //

    @GetMapping("/policy")
    public ResponseEntity<String> getPolicy() {
        return respond(client.getPolicyResult());
    }

    @PutMapping("/policy")
    public ResponseEntity<String> setPolicy(@RequestBody(required = false) String body) {
        return respond(client.setPolicyResult(body == null ? "{}" : body));
    }

    @GetMapping("/audit")
    public ResponseEntity<String> audit(@RequestParam(defaultValue = "100") int limit) {
        return respond(client.auditResult(limit));
    }

    @GetMapping("/approvals")
    public ResponseEntity<String> listApprovals() {
        return respond(client.listApprovalsResult());
    }

    /**
     * Resolving one of code-intel's OWN (now vestigial) approvals reaches the
     * service's write surface with core's trusted internal token, which the
     * service treats as admin - so this must be ROLE_tenant-admin here, or a
     * non-admin browser user could bypass the very governance rule this whole
     * controller exists to enforce.
     */
    @PostMapping("/approvals/{approvalId}/resolve")
    public ResponseEntity<String> resolveApproval(@PathVariable String approvalId,
                                  @RequestParam(defaultValue = "true") boolean approved,
                                  Authentication authentication) {
        requireTenantAdmin(authentication);
        return respond(client.resolveApprovalResult(approvalId, approved));
    }

    // ---- workspaces: create/diff/delete are harmless -------------------//

    @PostMapping("/repos/{repoId}/workspaces")
    public ResponseEntity<String> createWorkspace(@PathVariable String repoId) {
        return respond(client.createWorkspaceResult(repoId));
    }

    @GetMapping("/repos/{repoId}/workspaces/{workspaceId}/diff")
    public ResponseEntity<String> diffWorkspace(@PathVariable String repoId, @PathVariable String workspaceId) {
        return respond(client.diffWorkspaceResult(repoId, workspaceId));
    }

    @DeleteMapping("/repos/{repoId}/workspaces/{workspaceId}")
    public ResponseEntity<String> deleteWorkspace(@PathVariable String repoId, @PathVariable String workspaceId) {
        return respond(client.deleteWorkspaceResult(repoId, workspaceId));
    }

    // ---- the two mutating actions: routed through core/approval -------- //

    @PostMapping("/repos/{repoId}/workspaces/{workspaceId}/apply")
    @SuppressWarnings("unchecked")
    public String apply(@PathVariable String repoId, @PathVariable String workspaceId,
                        @RequestBody String body) {
        try {
            JsonNode json = read(body);
            JsonNode filesNode = json.path("files");
            if (!filesNode.isArray() || filesNode.isEmpty()) {
                return "{\"error\":\"files is required and must be a non-empty array\"}";
            }
            List<Map<String, Object>> files = MAPPER.convertValue(
                filesNode, new TypeReference<List<Map<String, Object>>>() {});
            ApplyCodeWorkspaceApprovalExecutor.ApplyPayload payload =
                new ApplyCodeWorkspaceApprovalExecutor.ApplyPayload(repoId, workspaceId, files, true);
            String summary = "Apply " + files.size() + " file change(s) to code workspace "
                + workspaceId + " (repo " + repoId + ") - initiated from the Code Intelligence console.";
            ApprovalEvaluation evaluation = gateService.evaluate(
                ApplyCodeWorkspaceApprovalExecutor.ACTION_TYPE, summary,
                MAPPER.writeValueAsString(payload), requestedBy());
            return responseJson(evaluation);
        } catch (Exception e) {
            log.warn("code-intel console apply failed", e);
            return "{\"error\":\"apply failed: " + safe(e.getMessage()) + "\"}";
        }
    }

    @PostMapping("/repos/{repoId}/workspaces/{workspaceId}/run")
    public String run(@PathVariable String repoId, @PathVariable String workspaceId,
                      @RequestBody String body) {
        try {
            JsonNode json = read(body);
            String command = json.path("command").asText("");
            if (command.isBlank()) {
                return "{\"error\":\"command is required\"}";
            }
            Integer timeout = json.has("timeout") && !json.path("timeout").isNull()
                ? json.path("timeout").asInt() : null;
            int attempts = json.path("attempts").asInt(1);
            RunCodeWorkspaceApprovalExecutor.RunPayload payload =
                new RunCodeWorkspaceApprovalExecutor.RunPayload(repoId, workspaceId, command, timeout, attempts, true);
            String summary = "Run in code workspace " + workspaceId + " (repo " + repoId
                + ") - initiated from the Code Intelligence console: "
                + (command.length() > 200 ? command.substring(0, 200) + "..." : command);
            ApprovalEvaluation evaluation = gateService.evaluate(
                RunCodeWorkspaceApprovalExecutor.ACTION_TYPE, summary,
                MAPPER.writeValueAsString(payload), requestedBy());
            return responseJson(evaluation);
        } catch (Exception e) {
            log.warn("code-intel console run failed", e);
            return "{\"error\":\"run failed: " + safe(e.getMessage()) + "\"}";
        }
    }

    /**
     * Autonomous SDLC resolve loop. Because the service treats core's
     * internal token as pre-approved, this drives isolated applies/runs
     * without a per-step approval, so it is gated to ROLE_tenant-admin here -
     * the most powerful single action the console exposes. It still only
     * produces a diff for a human to review; it never merges.
     */
    @PostMapping("/repos/{repoId}/agent/resolve")
    public ResponseEntity<String> agentResolve(@PathVariable String repoId, @RequestBody(required = false) String body,
                               Authentication authentication) {
        requireTenantAdmin(authentication);
        return respond(client.agentResolveResult(repoId, body == null ? "{}" : body));
    }

    // ---- helpers ------------------------------------------------------ //

    /** {@code status <= 0} means no HTTP response ever arrived (the service was
     *  unreachable, DNS failure, connection refused, etc.) - not a real status
     *  code to echo, so it maps to 502 (Bad Gateway) instead. Every other
     *  status, including 4xx/5xx the service itself returned, passes through
     *  unchanged so the browser's normal !res.ok error handling fires. */
    private static ResponseEntity<String> respond(CodeIntelClient.Result result) {
        int status = result.status() > 0 ? result.status() : 502;
        return ResponseEntity.status(status).body(result.body());
    }

    private void requireTenantAdmin(Authentication authentication) {
        boolean isTenantAdmin = authentication != null && authentication.getAuthorities().stream()
            .anyMatch(a -> TENANT_ADMIN_AUTHORITY.equals(a.getAuthority()));
        if (!isTenantAdmin) {
            throw new CodeIntelForbiddenException(
                "This action requires the tenant-admin role.");
        }
    }

    private static String requestedBy() {
        return TenantContext.current().map(TenantContext::getSubject).orElse(null);
    }

    private static JsonNode read(String body) {
        try {
            return MAPPER.readTree(body == null || body.isBlank() ? "{}" : body);
        } catch (Exception e) {
            return MAPPER.createObjectNode();
        }
    }

    private static boolean readBool(String body, String field, boolean fallback) {
        return read(body).path(field).asBoolean(fallback);
    }

    private static String responseJson(ApprovalEvaluation evaluation) {
        if (evaluation.approved()) {
            return "{\"status\":\"auto_approved_and_executed\","
                + "\"message\":\"This tenant's policy does not require approval for this action, so it "
                + "already ran. Use the diff view to see the result.\"}";
        }
        return "{\"status\":\"pending_approval\","
            + "\"approval_request_id\":\"" + evaluation.pendingRequestId() + "\","
            + "\"message\":\"This requires a tenant-admin's approval on the platform's Approvals screen "
            + "before it runs. It executes automatically once approved.\"}";
    }

    private static String safe(String s) {
        if (s == null) {
            return "";
        }
        String cleaned = s.replace("\"", "'").replace("\n", " ");
        return cleaned.length() > 300 ? cleaned.substring(0, 300) : cleaned;
    }
}
