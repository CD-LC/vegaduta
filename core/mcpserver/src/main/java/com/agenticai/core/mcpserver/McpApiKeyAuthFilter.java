package com.agenticai.core.mcpserver;

import com.agenticai.core.json.JsonMappers;
import com.agenticai.core.mcpserver.expose.McpExposureRpcHandler;
import com.agenticai.core.security.TenantContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Authenticates every inbound request to the MCP server endpoint (see {@code McpServerToolConfig}
 * for exactly how this is registered, scoped only to that path - NOT a global filter). Not
 * registered into Spring Security's own filter chain (that chain has no concept of "authenticate
 * via a hashed platform-generated API key" - only JWT/OAuth2 resource server support), so {@code
 * /mcp/**} is {@code permitAll()} at the Spring Security layer (see {@code SecurityConfig}'s
 * comment there, same posture as {@code /webhook/**}) and this filter does the actual
 * authentication itself, running as a plain servlet filter positioned after Spring Security's own
 * chain.
 *
 * <p><b>Resolution mirrors {@code WorkflowWebhookController} exactly</b> (no parallel
 * tenant-resolution logic, per this build's explicit ask): step 1 resolves which tenant schema a
 * caller's raw key belongs to via {@code control_plane.channel_account_link} (no {@code
 * TenantContext} can be set yet - that lookup IS how the schema gets picked); step 2, only once
 * {@code TenantContext} is set to that schema, loads the actual grant row and re-verifies it (hash
 * match + enabled) from inside the correct tenant schema. A grant disabled or deleted the instant
 * its {@code channel_account_link} row would otherwise still resolve is therefore still rejected -
 * step 2 is not just a formality.
 *
 * <p>On success, populates BOTH {@link TenantContext} (so {@code AgentChatService#chat}'s existing
 * tenant kill-switch check and Hibernate's schema routing work exactly as they do for a
 * JWT-authenticated request) and {@link McpCallerContext} (so this module's own tool callbacks know
 * which agent ids this specific key is allowed to touch) - both cleared in the {@code finally}
 * block, same discipline {@code TenantContextFilter}/{@code WorkflowWebhookController} already use.
 */
public class McpApiKeyAuthFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(McpApiKeyAuthFilter.class);
    private static final String AUTH_HEADER = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";
    private static final ObjectMapper MAPPER = JsonMappers.plain();

    /** Only small, length-declared JSON-RPC bodies are inspected for the get_task poll bucket. */
    private static final int MAX_SNIFF_BYTES = 16 * 1024;

    private final McpServerAccessGrantService grantService;
    private final McpServerRateLimiter rateLimiter;

    /**
     * When false (async-tasks flag off, the default) the request body is never read or wrapped, so
     * the live /mcp path is byte-identical to before the get_task poll bucket existed.
     */
    private final boolean pollBucketEnabled;

    /** Null unless app.mcp-server.expose-agents.enabled - see the six-argument constructor. */
    private final McpExposureRpcHandler exposureHandler;

    private final int exposureMaxBodyBytes;

    /**
     * Literal prefix every grant key carries (see {@link McpApiKeyHasher}); lets the OpenAI-compat
     * mode tell a key from a JWT.
     */
    static final String KEY_PREFIX = "vmcp_";

    /**
     * {@code true} for the OpenAI-compatible {@code /v1/*} registration: a request whose bearer is
     * NOT a {@code vmcp_} key (a platform JWT, already authenticated by Spring Security's chain)
     * passes through untouched, and rejections use OpenAI's error envelope instead of {@code
     * {"error":"..."}}.
     */
    private final boolean openAiMode;

    /**
     * Null unless {@code app.mcpserver.oauth.enabled}: the OAuth 2.1 option next to the grant keys.
     */
    private McpOAuthAuthenticator oauth;

    private String resourceMetadataUrl;
    private static final int OAUTH_MAX_BODY_BYTES = 256 * 1024;

    /**
     * Turns on the OAuth 2.1 bearer option; a {@code vmcp_} key keeps working exactly as before.
     */
    void enableOAuth(McpOAuthAuthenticator authenticator, String resourceMetadataUrl) {
        this.oauth = authenticator;
        this.resourceMetadataUrl = resourceMetadataUrl;
    }

    public McpApiKeyAuthFilter(
            McpServerAccessGrantService grantService, McpServerRateLimiter rateLimiter) {
        this(grantService, rateLimiter, false, false);
    }

    /**
     * Poll bucket limits (documented): only a single, non-batched JSON-RPC body with a declared
     * Content-Length of at most 16KB is recognised as a get_task poll. Chunked or batched polls
     * fall back to the general bucket. The retry hint is the poll_after_seconds field in the
     * get_task result, not an HTTP Retry-After header.
     *
     * @param openAiMode true for the OpenAI-compatible /v1/* registration
     * @param pollBucketEnabled true when the async-tasks flag is on (enables the get_task poll
     *     bucket)
     */
    public McpApiKeyAuthFilter(
            McpServerAccessGrantService grantService,
            McpServerRateLimiter rateLimiter,
            boolean openAiMode,
            boolean pollBucketEnabled) {
        this(grantService, rateLimiter, openAiMode, pollBucketEnabled, null, 0);
    }

    /**
     * As above, plus the optional "agents and workflows as MCP tools" hook
     * (app.mcp-server.expose-agents). With a {@code null} handler (the default - flag off) the
     * request body handling is exactly the four-argument constructor's. With a handler, a POSTed
     * JSON-RPC body of at most {@code exposureMaxBodyBytes} is read once (and replayed for the MCP
     * transport), and once the caller is authenticated, rate limited and its grant loaded, the
     * handler may answer {@code tools/list} / a dynamic {@code tools/call} itself; a larger body is
     * rejected with 413 rather than silently bypassing the hook.
     */
    public McpApiKeyAuthFilter(
            McpServerAccessGrantService grantService,
            McpServerRateLimiter rateLimiter,
            boolean openAiMode,
            boolean pollBucketEnabled,
            McpExposureRpcHandler exposureHandler,
            int exposureMaxBodyBytes) {
        this.exposureHandler = exposureHandler;
        this.exposureMaxBodyBytes = exposureMaxBodyBytes;
        this.grantService = grantService;
        this.rateLimiter = rateLimiter;
        this.openAiMode = openAiMode;
        this.pollBucketEnabled = pollBucketEnabled;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String rawKey = extractRawKey(request);
        if (openAiMode && (rawKey == null || !rawKey.startsWith(KEY_PREFIX))) {
            // JWT caller: Spring Security already authenticated it (and TenantContextFilter set the
            // tenant). A request with no usable credential never gets here - the /v1/** rule
            // rejects it.
            filterChain.doFilter(request, response);
            return;
        }
        if (oauth != null && rawKey != null && !rawKey.startsWith(KEY_PREFIX)) {
            authenticateOAuth(request, response, filterChain, rawKey);
            return;
        }
        if (rawKey == null) {
            reject(
                    response,
                    HttpServletResponse.SC_UNAUTHORIZED,
                    "Missing or malformed Authorization: Bearer <api-key> header.");
            return;
        }

        Optional<String> tenantSchema = grantService.resolveTenantSchema(rawKey);
        if (tenantSchema.isEmpty()) {
            log.warn("Inbound MCP call rejected - unknown API key (***{})", lastFour(rawKey));
            reject(response, HttpServletResponse.SC_UNAUTHORIZED, "Invalid API key.");
            return;
        }

        // Same "no browser session, mirror the BYOK-credential auth shape" posture the task asks
        // for: subject is a synthetic identity naming the key, never a Keycloak principal.
        TenantContext.set(
                TenantContext.of(
                        tenantSchema.get(), "mcp-external-key", Set.of("ROLE_mcp-external")));
        try {
            Optional<McpServerAccessGrant> grant =
                    grantService.findByRawKeyWithinCurrentTenant(rawKey);
            if (grant.isEmpty()) {
                log.warn(
                        "Inbound MCP call rejected - API key resolved a tenant schema but no enabled grant (***{})",
                        lastFour(rawKey));
                reject(response, HttpServletResponse.SC_UNAUTHORIZED, "Invalid API key.");
                return;
            }
            serve(request, response, filterChain, tenantSchema.get(), grant.get());
        } finally {
            TenantContext.clear();
        }
    }

    /**
     * The authenticated part of the request, shared by the grant-key and OAuth paths. {@code
     * TenantContext} is set by the caller.
     */
    private void serve(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain,
            String tenantSchema,
            McpServerAccessGrant grant)
            throws ServletException, IOException {
        UUID grantId = grant.getId();
        HttpServletRequest downstream = request;
        boolean taskPoll = false;
        byte[] exposureBody = null;
        if (exposureHandler != null && "POST".equalsIgnoreCase(request.getMethod())) {
            // Agents/workflows-as-MCP-tools is ON: read the (bounded) body once so the hook below
            // can
            // inspect it, and replay it for the MCP transport. Chunked bodies are read up to the
            // cap too.
            int declared = request.getContentLength();
            if (declared > exposureMaxBodyBytes) {
                reject(response, 413, "Request body too large.");
                return;
            }
            byte[] body =
                    declared >= 0
                            ? request.getInputStream().readAllBytes()
                            : request.getInputStream().readNBytes(exposureMaxBodyBytes + 1);
            if (body.length > exposureMaxBodyBytes) {
                reject(response, 413, "Request body too large.");
                return;
            }
            downstream = new ReplayableRequest(request, body);
            exposureBody = body;
            taskPoll = pollBucketEnabled && body.length <= MAX_SNIFF_BYTES && isGetTaskCall(body);
        } else if (pollBucketEnabled
                && "POST".equalsIgnoreCase(request.getMethod())
                && request.getContentLength() > 0
                && request.getContentLength() <= MAX_SNIFF_BYTES) {
            byte[] body = request.getInputStream().readAllBytes();
            downstream = new ReplayableRequest(request, body);
            taskPoll = isGetTaskCall(body);
        }
        // get_task polls draw from their own, more generous bucket so a client waiting on a long
        // task cannot exhaust the general budget (and then fail to fetch the result).
        if (!(taskPoll ? rateLimiter.allowPoll(grantId) : rateLimiter.allow(grantId))) {
            log.warn("Inbound MCP call rejected - grant {} exceeded its rate limit", grantId);
            // jakarta.servlet's HttpServletResponse has no SC_TOO_MANY_REQUESTS constant -
            // 429 (RFC 6585) postdates the servlet spec's status code constants.
            reject(response, 429, "Rate limit exceeded - try again shortly.");
            return;
        }
        // Data-governance audit fix (2026-07-20): re-set TenantContext now that the grant is
        // known, so its subject carries the specific grantId rather than the generic literal
        // "mcp-external-key". Anything downstream that reads TenantContext.current().getSubject()
        // as an actor/requestedBy identity - e.g. ApplyCodeChangesToolCallback/
        // RunInCodeWorkspaceToolCallback's `requestedBy` passed into
        // HumanApprovalGateService.evaluate,
        // which is persisted verbatim on ApprovalRequest.requestedBy - is now traceable back to the
        // exact MCP grant that triggered it, without a new table or column. Same rationale as
        // McpCallAgentToolCallback's own "mcp-external:" + grantId subjectKey.
        //
        // Follow-up real fix (2026-07-20, same day): the subject-string convention above is
        // still a useful human-readable breadcrumb, but it is NOT what tool_call_log attribution
        // is actually built on any more - see TenantContext#getExternalTriggerId's javadoc. That
        // field is the typed seam AgentToolProvider#guardAndLog reads to tag every tool_call_log
        // row this MCP-triggered chat turn produces with a real mcp_grant_id column, so "show me
        // every action MCP grant X caused" is a real query, not string-parsing session_id or
        // subject. Populated here, at the exact point grantId first becomes known - the only
        // place this filter (or McpCallAgentToolCallback) needs to change for that to work.
        TenantContext.set(
                TenantContext.of(
                        tenantSchema,
                        "mcp-external:" + grantId,
                        Set.of("ROLE_mcp-external"),
                        grantId.toString()));
        McpCallerContext.set(
                grantId,
                grantService.parseExposedAgentIds(grant),
                grantService.parseExposedWorkflowIds(grant),
                grantService.parseExposedKnowledgeCollectionIds(grant));
        try {
            if (exposureHandler != null && exposureBody != null) {
                // Per-caller dynamic tools (tools/list + tools/call of agent_*/workflow_*/get_run)
                // - see
                // McpExposureRpcHandler. Empty means "not ours": fall through to the normal MCP
                // transport.
                java.util.Optional<String> answered = exposureHandler.tryHandle(exposureBody);
                if (answered.isPresent()) {
                    response.setStatus(HttpServletResponse.SC_OK);
                    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                    response.setCharacterEncoding("UTF-8");
                    response.getWriter().write(answered.get());
                    return;
                }
            }
            // B11: an error body from the MCP transport must never carry a serialized Java
            // exception.
            McpErrorScrubbingResponse scrubbed = new McpErrorScrubbingResponse(response);
            filterChain.doFilter(downstream, scrubbed);
            scrubbed.finish();
        } finally {
            McpCallerContext.clear();
        }
    }

    /**
     * OAuth 2.1 path: validate the access token (signature, issuer, audience == this server), check
     * the scopes the JSON-RPC body needs, then serve with the same grant-confined context a {@code
     * vmcp_} key gets. The inbound {@code Authorization} header is hidden from everything
     * downstream, so the token can never be passed through to an upstream service.
     */
    private void authenticateOAuth(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain,
            String token)
            throws ServletException, IOException {
        McpOAuthAuthenticator.Result result = oauth.authenticate(token);
        if (result.rejection() != null) {
            McpOAuthAuthenticator.Rejection r = result.rejection();
            challenge(response, r.status(), r.error(), r.description(), null);
            return;
        }
        McpOAuthAuthenticator.Principal principal = result.principal();
        HttpServletRequest downstream = request;
        java.util.Set<String> required = java.util.Set.of();
        if ("POST".equalsIgnoreCase(request.getMethod())) {
            byte[] body = request.getInputStream().readNBytes(OAUTH_MAX_BODY_BYTES + 1);
            if (body.length > OAUTH_MAX_BODY_BYTES) {
                reject(response, 413, "Request body too large.");
                return;
            }
            downstream = new ReplayableRequest(request, body);
            try {
                required = McpOAuthScopes.requiredFor(MAPPER.readTree(body));
            } catch (IOException | RuntimeException e) {
                required = java.util.Set.of();
            }
        }
        java.util.Set<String> missing = McpOAuthScopes.missing(principal.scopes(), required);
        if (!missing.isEmpty()) {
            challenge(
                    response,
                    HttpServletResponse.SC_FORBIDDEN,
                    "insufficient_scope",
                    "The token lacks a scope this request needs.",
                    String.join(" ", required));
            return;
        }
        TenantContext.set(
                TenantContext.of(principal.tenantId(), "mcp-oauth", Set.of("ROLE_mcp-external")));
        try {
            Optional<McpServerAccessGrant> grant =
                    grantService.findEnabledByIdWithinCurrentTenant(principal.grantId());
            if (grant.isEmpty()) {
                log.warn(
                        "Inbound MCP OAuth call rejected - grant {} is missing or disabled",
                        principal.grantId());
                challenge(
                        response,
                        HttpServletResponse.SC_UNAUTHORIZED,
                        "invalid_token",
                        "The access token is invalid, expired or not issued for this server.",
                        null);
                return;
            }
            serve(
                    new WithoutAuthorizationRequest(downstream),
                    response,
                    filterChain,
                    principal.tenantId(),
                    grant.get());
        } finally {
            TenantContext.clear();
        }
    }

    /**
     * RFC 6750 / RFC 9728 challenge: error, optional required scope, and where the resource
     * metadata lives.
     */
    private void challenge(
            HttpServletResponse response,
            int status,
            String error,
            String description,
            String scope)
            throws IOException {
        StringBuilder header = new StringBuilder("Bearer error=\"").append(error).append('"');
        header.append(", error_description=\"").append(description.replace("\"", "'")).append('"');
        if (scope != null && !scope.isBlank()) {
            header.append(", scope=\"").append(scope).append('"');
        }
        header.append(", resource_metadata=\"").append(resourceMetadataUrl).append('"');
        response.setHeader("WWW-Authenticate", header.toString());
        reject(response, status, description);
    }

    /** Hides {@code Authorization} from every downstream handler (no token passthrough). */
    private static final class WithoutAuthorizationRequest extends HttpServletRequestWrapper {
        WithoutAuthorizationRequest(HttpServletRequest request) {
            super(request);
        }

        @Override
        public String getHeader(String name) {
            return AUTH_HEADER.equalsIgnoreCase(name) ? null : super.getHeader(name);
        }

        @Override
        public java.util.Enumeration<String> getHeaders(String name) {
            return AUTH_HEADER.equalsIgnoreCase(name)
                    ? java.util.Collections.emptyEnumeration()
                    : super.getHeaders(name);
        }

        @Override
        public java.util.Enumeration<String> getHeaderNames() {
            return java.util.Collections.enumeration(
                    java.util.Collections.list(super.getHeaderNames()).stream()
                            .filter(n -> !AUTH_HEADER.equalsIgnoreCase(n))
                            .toList());
        }
    }

    /** True for a single JSON-RPC {@code tools/call} whose tool name is {@code get_task}. */
    static boolean isGetTaskCall(byte[] body) {
        try {
            JsonNode node = MAPPER.readTree(body);
            return node != null
                    && node.isObject()
                    && "tools/call".equals(node.path("method").asString())
                    && "get_task".equals(node.path("params").path("name").asString());
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Re-serves an already-read body so the MCP transport can still parse it. */
    private static final class ReplayableRequest extends HttpServletRequestWrapper {

        private final byte[] body;

        ReplayableRequest(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body;
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream in = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override
                public int read() {
                    return in.read();
                }

                @Override
                public boolean isFinished() {
                    return in.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener readListener) {
                    throw new UnsupportedOperationException("synchronous replay only");
                }
            };
        }

        @Override
        public BufferedReader getReader() {
            String enc = getCharacterEncoding();
            Charset charset = enc == null ? StandardCharsets.UTF_8 : Charset.forName(enc);
            return new BufferedReader(new InputStreamReader(getInputStream(), charset));
        }
    }

    private String extractRawKey(HttpServletRequest request) {
        String header = request.getHeader(AUTH_HEADER);
        if (!StringUtils.hasText(header)
                || !header.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            return null;
        }
        String token = header.substring(BEARER_PREFIX.length()).trim();
        return token.isEmpty() ? null : token;
    }

    private void reject(HttpServletResponse response, int status, String message)
            throws IOException {
        response.setStatus(status);
        if (oauth != null
                && status == HttpServletResponse.SC_UNAUTHORIZED
                && response.getHeader("WWW-Authenticate") == null) {
            response.setHeader(
                    "WWW-Authenticate", "Bearer resource_metadata=\"" + resourceMetadataUrl + "\"");
        }
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        String safe = message.replace("\"", "'");
        if (openAiMode) {
            boolean limited = status == 429;
            if (limited) {
                response.setHeader("Retry-After", "1");
            }
            response.getWriter()
                    .write(
                            "{\"error\":{\"message\":\""
                                    + safe
                                    + "\",\"type\":\""
                                    + (limited ? "rate_limit_exceeded" : "invalid_request_error")
                                    + "\",\"param\":null,\"code\":\""
                                    + (limited ? "rate_limit_exceeded" : "invalid_api_key")
                                    + "\"}}");
            return;
        }
        response.getWriter().write("{\"error\":\"" + safe + "\"}");
    }

    private static String lastFour(String value) {
        if (value == null || value.length() < 4) {
            return "****";
        }
        return value.substring(value.length() - 4);
    }
}
