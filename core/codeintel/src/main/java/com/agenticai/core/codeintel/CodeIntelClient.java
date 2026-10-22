package com.agenticai.core.codeintel;

import com.agenticai.core.json.JsonMappers;
import com.agenticai.core.security.TenantContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriUtils;

/**
 * Thin server-to-server client over the code-intel microservice. Same shape and posture as {@code
 * E2bSandboxClient}: a {@link RestClient} built from the Spring-managed builder, one auth mechanism
 * on every call. Here that is two headers - {@code X-Tenant-Id} (the current {@link
 * TenantContext}'s tenant, so the service scopes to exactly this tenant's indexed repos) and {@code
 * X-Internal-Token} (the shared secret; the service runs with {@code auth_mode=internal}). Both are
 * resolved per call, never cached, so a rotated token or a different tenant on the next request
 * Just Works.
 *
 * <p>Only created when {@code code-intel.enabled=true} - so when the feature is off this bean is
 * absent and {@code AgentToolProvider}'s {@code ObjectProvider<CodeIntelClient>} yields nothing,
 * offering no tools.
 *
 * <p>Every method returns the service's raw JSON string, which the calling {@link
 * org.springframework.ai.tool.ToolCallback} hands straight to the model - the service already
 * returns clean, agent-readable JSON (hits with path/line/symbol citations, graph edges with
 * confidence, grounded passages), so there is nothing to re-map here.
 */
@Component
@ConditionalOnProperty(prefix = "code-intel", name = "enabled", havingValue = "true")
public class CodeIntelClient {

    private static final String TENANT_HEADER = "X-Tenant-Id";
    private static final String TOKEN_HEADER = "X-Internal-Token";
    private static final ObjectMapper MAPPER = JsonMappers.plain();

    private final RestClient restClient;
    private final CodeIntelProperties properties;

    public CodeIntelClient(RestClient.Builder restClientBuilder, CodeIntelProperties properties) {
        this.properties = properties;
        // Plain builder + baseUrl - no custom request factory, to stay
        // version-agnostic across Spring Boot's evolving ClientHttpRequest
        // factory API. (Timeouts can be added later via a shared
        // RestClient.Builder customizer if needed.)
        this.restClient = restClientBuilder.baseUrl(properties.getBaseUrl()).build();
    }

    /**
     * Body + the REAL upstream HTTP status (0 when the service was unreachable - no HTTP response
     * ever arrived to have a status). Every {@code String}-returning public method below discards
     * the status half of this and keeps returning just {@code body()} - unchanged, so every {@link
     * org.springframework.ai.tool.ToolCallback} consumer (the model only ever wants the JSON text)
     * and every existing caller of this class keeps working exactly as before.
     *
     * <p>{@link CodeIntelController}, which DOES care whether a call actually succeeded (so it can
     * give the browser a real non-2xx instead of silently swallowing a 403/404/500 into a 200),
     * uses the package-private {@code *Result()} twin of each pass-through method instead - see
     * e.g. {@link #createWorkspaceResult}. This was a real bug (2026-08-14): every proxied endpoint
     * always answered 200 regardless of the upstream outcome, so the web console's error handling
     * (which only triggers on a non-2xx response) never fired - a failed "create workspace"
     * silently produced a workspace object with no {@code workspace_id}, and the console broke one
     * step later instead of showing the real error.
     */
    public record Result(String body, int status) {}

    /**
     * Semantic code search. When {@code repoId} is null/blank, searches every repo this tenant has
     * (the grounded, cited /knowledge/query path).
     */
    public String searchCode(String query, String repoId, int topK) {
        return searchCodeResult(query, repoId, topK).body();
    }

    Result searchCodeResult(String query, String repoId, int topK) {
        if (repoId == null || repoId.isBlank()) {
            return post(
                    "/knowledge/query",
                    "{\"query\":" + jsonString(query) + ",\"top_k\":" + topK + "}");
        }
        return post(
                "/repos/" + enc(repoId) + "/search",
                "{\"query\":" + jsonString(query) + ",\"top_k\":" + topK + "}");
    }

    /** Who calls this symbol (deterministic AST code graph). */
    public String callers(String repoId, String symbol) {
        return callersResult(repoId, symbol).body();
    }

    Result callersResult(String repoId, String symbol) {
        return get("/repos/" + enc(repoId) + "/graph/callers/" + enc(symbol));
    }

    /** Transitive blast radius of a symbol or file. */
    public String impact(String repoId, String symbol, int depth) {
        return impactResult(repoId, symbol, depth).body();
    }

    Result impactResult(String repoId, String symbol, int depth) {
        return get("/repos/" + enc(repoId) + "/graph/impact/" + enc(symbol) + "?depth=" + depth);
    }

    /** What this symbol calls (reverse of {@link #callers}). */
    public String callees(String repoId, String symbol) {
        return calleesResult(repoId, symbol).body();
    }

    Result calleesResult(String repoId, String symbol) {
        return get("/repos/" + enc(repoId) + "/graph/callees/" + enc(symbol));
    }

    /** Repo-wide code graph stats: file/symbol/edge counts, by engine and resolution confidence. */
    public String graphStats(String repoId) {
        return graphStatsResult(repoId).body();
    }

    Result graphStatsResult(String repoId) {
        return get("/repos/" + enc(repoId) + "/graph/stats");
    }

    // ------------------------------------------------------------------ //
    // repo lifecycle + guardrail state - the read/config surface the web
    // module's /code-intel console needs, now proxied through core (see
    // CodeIntelController) instead of the browser hitting the service
    // directly. All tenant-scoped by the X-Tenant-Id header this client
    // already sends; none mutate code or run commands (that stays behind the
    // approval-gated apply/run path in CodeIntelController).

    /**
     * Liveness of the underlying code-intel service ({@code {status, auth_mode}}) - proxied so the
     * console's health pill shows the real service mode, not a core-synthesized stub.
     */
    public String health() {
        return healthResult().body();
    }

    Result healthResult() {
        return get("/health");
    }

    /** Every repo this tenant has registered with code-intel. */
    public String listRepos() {
        return listReposResult().body();
    }

    Result listReposResult() {
        return get("/repos");
    }

    /**
     * Register a repo (git URL or pre-mounted local path). {@code jsonBody} is code-intel's
     * RegisterRepoRequest shape, forwarded verbatim.
     */
    public String registerRepo(String jsonBody) {
        return registerRepoResult(jsonBody).body();
    }

    Result registerRepoResult(String jsonBody) {
        return post("/repos", jsonBody);
    }

    /** Remove a repo and its index/graph/workspaces from this tenant's subtree. */
    public String deleteRepo(String repoId) {
        return deleteRepoResult(repoId).body();
    }

    Result deleteRepoResult(String repoId) {
        return delete("/repos/" + enc(repoId));
    }

    /** Kick an (incremental unless {@code full}) index of a registered repo. */
    public String indexRepo(String repoId, boolean full) {
        return indexRepoResult(repoId, full).body();
    }

    Result indexRepoResult(String repoId, boolean full) {
        return post("/repos/" + enc(repoId) + "/index", "{\"full\":" + full + "}");
    }

    /** Last index/graph outcome for a repo. */
    public String repoStatus(String repoId) {
        return repoStatusResult(repoId).body();
    }

    Result repoStatusResult(String repoId) {
        return get("/repos/" + enc(repoId) + "/status");
    }

    /** (Re)build the deterministic AST code graph for a repo. */
    public String graphBuild(String repoId) {
        return graphBuildResult(repoId).body();
    }

    Result graphBuildResult(String repoId) {
        return post("/repos/" + enc(repoId) + "/graph/build", "{}");
    }

    /**
     * Current per-tenant guardrail policy (autonomy mode, exec/apply toggles, blast-radius caps,
     * protected paths).
     */
    public String getPolicy() {
        return getPolicyResult().body();
    }

    Result getPolicyResult() {
        return get("/policy");
    }

    /**
     * Merge-update the per-tenant guardrail policy. {@code jsonBody} is code-intel's PolicyBody
     * shape (only supplied fields change).
     */
    public String setPolicy(String jsonBody) {
        return setPolicyResult(jsonBody).body();
    }

    Result setPolicyResult(String jsonBody) {
        return put("/policy", jsonBody);
    }

    /** Tail of the per-tenant code-intel audit trail (index/apply/run/ approval events). */
    public String audit(int limit) {
        return auditResult(limit).body();
    }

    Result auditResult(int limit) {
        return get("/audit?limit=" + limit);
    }

    /**
     * Grounded, cited code retrieval across every repo this tenant has (or a single one). {@code
     * jsonBody} is code-intel's KnowledgeQuery shape ({@code {query, repo_id?, top_k?}}) -
     * read-only, zero execution risk.
     */
    public String knowledgeQuery(String jsonBody) {
        return knowledgeQueryResult(jsonBody).body();
    }

    Result knowledgeQueryResult(String jsonBody) {
        return post("/knowledge/query", jsonBody);
    }

    /**
     * code-intel's OWN pending approvals for this tenant. Largely vestigial now that {@link
     * CodeIntelController} routes web writes through core/approval instead (internal-token calls
     * are platform-pre-approved, so they no longer create a code-intel-side pending) - kept so the
     * console's Guardrails tab still renders any that exist.
     */
    public String listApprovals() {
        return listApprovalsResult().body();
    }

    Result listApprovalsResult() {
        return get("/approvals");
    }

    /**
     * Resolve one of code-intel's own pending approvals. Only reachable by a tenant-admin (core
     * enforces the role; code-intel independently treats this internal-token caller as admin - see
     * its resolve_approval).
     */
    public String resolveApproval(String approvalId, boolean approved) {
        return resolveApprovalResult(approvalId, approved).body();
    }

    Result resolveApprovalResult(String approvalId, boolean approved) {
        return post("/approvals/" + enc(approvalId) + "/resolve?approved=" + approved, "{}");
    }

    /**
     * Autonomous SDLC resolve loop (context -> workspace -> fix -> test -> rework -> diff) inside
     * the service, under its own guardrails. {@code jsonBody} is code-intel's AgentResolveRequest
     * shape. Long-running: the service caps iterations; core forwards and returns the trace/diff.
     */
    public String agentResolve(String repoId, String jsonBody) {
        return agentResolveResult(repoId, jsonBody).body();
    }

    Result agentResolveResult(String repoId, String jsonBody) {
        return post("/repos/" + enc(repoId) + "/agent/resolve", jsonBody);
    }

    // ------------------------------------------------------------------ //
    // execution workspaces - the write/execute surface, 403 unless the
    // service itself has CODE_INTEL_ALLOW_EXEC=true. These five methods are
    // the thin HTTP transport ONLY, same posture as every other method in
    // this class - they do NOT and CANNOT enforce that a caller went
    // through core/approval's HumanApprovalGateService first. That
    // enforcement lives one layer up, in ApplyCodeChangesToolCallback /
    // RunInCodeWorkspaceToolCallback and their registered
    // ApprovalActionExecutors - see those classes' javadoc for the full
    // gate. Do not call applyToWorkspace/runInWorkspace directly from
    // anywhere that skips that layer.

    /**
     * Creates an isolated scratch workspace for a repo (git worktree when the source is a git
     * checkout, an rsync-style copy otherwise) - the primary checkout is never touched. Response
     * shape: {"workspace_id","git", "iteration":0}.
     */
    public String createWorkspace(String repoId) {
        return createWorkspaceResult(repoId).body();
    }

    Result createWorkspaceResult(String repoId) {
        return post("/repos/" + enc(repoId) + "/workspaces", "{}");
    }

    /**
     * Applies full-file-content writes/deletes to an EXISTING workspace. {@code files} entries are
     * {"path":..,"content":..} or {"path":..,"delete":true}, matching the service's
     * ApplyFilesRequest exactly. {@code approvalId}, when non-null, is forwarded as the service's
     * OWN {@code ?approval_id=} query param - that only matters if a prior call to this same method
     * already came back carrying the service's own 428/approval_id (code-intel's independent, local
     * guardrail asking for its own sign-off); it is never a substitute for going through
     * core/approval first.
     */
    public String applyToWorkspace(
            String repoId, String workspaceId, List<Map<String, Object>> files, String approvalId) {
        try {
            String body = MAPPER.writeValueAsString(Map.of("files", files));
            return post(
                            "/repos/"
                                    + enc(repoId)
                                    + "/workspaces/"
                                    + enc(workspaceId)
                                    + "/apply"
                                    + approvalQuery(approvalId),
                            body)
                    .body();
        } catch (Exception e) {
            return errorJson("failed to serialize apply request: " + e.getMessage(), 0);
        }
    }

    /**
     * Runs {@code command} inside an existing workspace: hard timeout, capped output, env scrubbed
     * to an allowlist. {@code timeoutSeconds} null uses the service's own default; {@code attempts}
     * > 1 reruns UNCHANGED code on failure to absorb flaky tests (not a rework/retry of changed
     * code). {@code approvalId} - see {@link #applyToWorkspace}'s javadoc.
     */
    public String runInWorkspace(
            String repoId,
            String workspaceId,
            String command,
            Integer timeoutSeconds,
            int attempts,
            String approvalId) {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("command", command);
            body.put("attempts", attempts);
            if (timeoutSeconds != null) {
                body.put("timeout", timeoutSeconds);
            }
            String json = MAPPER.writeValueAsString(body);
            return post(
                            "/repos/"
                                    + enc(repoId)
                                    + "/workspaces/"
                                    + enc(workspaceId)
                                    + "/run"
                                    + approvalQuery(approvalId),
                            json)
                    .body();
        } catch (Exception e) {
            return errorJson("failed to serialize run request: " + e.getMessage(), 0);
        }
    }

    /**
     * Unified diff of everything changed in a workspace so far (the rework loop's final artifact).
     * Read-only - no approval needed to call this.
     */
    public String diffWorkspace(String repoId, String workspaceId) {
        return diffWorkspaceResult(repoId, workspaceId).body();
    }

    Result diffWorkspaceResult(String repoId, String workspaceId) {
        return get("/repos/" + enc(repoId) + "/workspaces/" + enc(workspaceId) + "/diff");
    }

    /**
     * Tears down a scratch workspace (git worktree remove + rmtree). Only removes the disposable
     * copy - never touches the primary checkout.
     */
    public String deleteWorkspace(String repoId, String workspaceId) {
        return deleteWorkspaceResult(repoId, workspaceId).body();
    }

    Result deleteWorkspaceResult(String repoId, String workspaceId) {
        return delete("/repos/" + enc(repoId) + "/workspaces/" + enc(workspaceId));
    }

    /**
     * Platform-admin "delete tenant completely" control - removes this tenant's ENTIRE {@code
     * data/tenants/<tenantId>/} tree on the code-intel service (every registered repo, index,
     * graph, and workspace), not just one repo. Unlike every other method in this class, the tenant
     * id is passed explicitly rather than read from {@link TenantContext}: this call is made by a
     * platform admin acting on an ARBITRARY tenant, not from inside that tenant's own request -
     * {@code TenantContext.current()} would be empty here (a platform-admin JWT carries no {@code
     * tenant_id} claim at all), which is exactly why every other method's default behavior would be
     * wrong for this one call site.
     */
    public String deleteTenantData(String tenantId) {
        return delete("/tenant", tenantId).body();
    }

    // ------------------------------------------------------------------ //

    private Result get(String path) {
        try {
            var resp =
                    restClient
                            .get()
                            .uri(path)
                            .header(TENANT_HEADER, tenantId())
                            .header(TOKEN_HEADER, properties.getInternalToken())
                            .retrieve()
                            .toEntity(String.class);
            return new Result(resp.getBody(), resp.getStatusCode().value());
        } catch (RestClientResponseException e) {
            return new Result(
                    errorJson(e.getResponseBodyAsString(), e.getStatusCode().value()),
                    e.getStatusCode().value());
        } catch (RestClientException e) {
            return new Result(errorJson(e.getMessage(), 0), 0);
        }
    }

    private Result post(String path, String jsonBody) {
        try {
            var resp =
                    restClient
                            .post()
                            .uri(path)
                            .header(TENANT_HEADER, tenantId())
                            .header(TOKEN_HEADER, properties.getInternalToken())
                            .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                            .body(jsonBody)
                            .retrieve()
                            .toEntity(String.class);
            return new Result(resp.getBody(), resp.getStatusCode().value());
        } catch (RestClientResponseException e) {
            return new Result(
                    errorJson(e.getResponseBodyAsString(), e.getStatusCode().value()),
                    e.getStatusCode().value());
        } catch (RestClientException e) {
            return new Result(errorJson(e.getMessage(), 0), 0);
        }
    }

    private Result put(String path, String jsonBody) {
        try {
            var resp =
                    restClient
                            .put()
                            .uri(path)
                            .header(TENANT_HEADER, tenantId())
                            .header(TOKEN_HEADER, properties.getInternalToken())
                            .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                            .body(jsonBody)
                            .retrieve()
                            .toEntity(String.class);
            return new Result(resp.getBody(), resp.getStatusCode().value());
        } catch (RestClientResponseException e) {
            return new Result(
                    errorJson(e.getResponseBodyAsString(), e.getStatusCode().value()),
                    e.getStatusCode().value());
        } catch (RestClientException e) {
            return new Result(errorJson(e.getMessage(), 0), 0);
        }
    }

    private Result delete(String path) {
        return delete(path, tenantId());
    }

    private Result delete(String path, String tenantIdOverride) {
        try {
            var resp =
                    restClient
                            .delete()
                            .uri(path)
                            .header(TENANT_HEADER, tenantIdOverride)
                            .header(TOKEN_HEADER, properties.getInternalToken())
                            .retrieve()
                            .toEntity(String.class);
            return new Result(resp.getBody(), resp.getStatusCode().value());
        } catch (RestClientResponseException e) {
            return new Result(
                    errorJson(e.getResponseBodyAsString(), e.getStatusCode().value()),
                    e.getStatusCode().value());
        } catch (RestClientException e) {
            return new Result(errorJson(e.getMessage(), 0), 0);
        }
    }

    /**
     * {@code ?approval_id=...} query suffix, or "" when none - code-intel's OWN approval token (see
     * {@link #applyToWorkspace}), unrelated to core/approval's ApprovalRequest ids.
     */
    private static String approvalQuery(String approvalId) {
        return (approvalId == null || approvalId.isBlank())
                ? ""
                : "?approval_id=" + UriUtils.encodeQueryParam(approvalId, StandardCharsets.UTF_8);
    }

    private static String tenantId() {
        return TenantContext.current().map(TenantContext::getTenantId).orElse("");
    }

    private static String enc(String s) {
        return UriUtils.encodePathSegment(s, StandardCharsets.UTF_8);
    }

    private static String jsonString(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> sb.append(c);
            }
        }
        return sb.append("\"").toString();
    }

    private static String errorJson(String detail, int status) {
        String d = detail == null ? "" : detail.replace("\"", "'");
        if (d.length() > 500) {
            d = d.substring(0, 500);
        }
        // If the service already returned JSON (its {timestamp,status,error}
        // shape), pass it through so the model sees the real message.
        if (d.trim().startsWith("{")) {
            return d;
        }
        return "{\"error\":\"code-intel request failed"
                + (status > 0 ? " (" + status + ")" : "")
                + ": "
                + d
                + "\"}";
    }
}
