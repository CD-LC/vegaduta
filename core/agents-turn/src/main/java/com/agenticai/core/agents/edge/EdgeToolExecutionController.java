package com.agenticai.core.agents.edge;

import com.agenticai.core.agents.core.Agent;
import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.agents.memory.AgentMemoryEntry;
import com.agenticai.core.agents.memory.AgentMemoryService;
import com.agenticai.core.agents.tools.AgentToolPort;
import com.agenticai.core.devices.DeviceAuthResolver;
import com.agenticai.core.sharing.PermissionLevel;
import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.Principal;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The propose/execute split's server half. An on-device (VegaDuta Edge) model <em>proposes</em> a tool
 * call in the visitor's browser; this endpoint is the only way that proposal ever
 * <em>executes</em>, and it runs the call through the same guarded resolution/decorator stack a
 * hosted chat turn uses ({@code AgentToolProvider#resolveToolCandidatesFor} - the same merge,
 * allow-list filtering, and {@code GuardedToolCallback}/{@code LoggingToolCallback} wrapping {@link
 * AgentToolProvider#resolveToolsFor} applies, minus only the per-turn LLM-facing relevance cap,
 * which has nothing to contribute to a lookup-by-exact-name call that never reaches an LLM provider
 * - see that method's own javadoc). No new trust boundary, no forked execution path.
 *
 * <p><b>Full tool parity with hosted (2026-08-06, direct product decision):</b> this endpoint used
 * to intersect the resolved candidate set with a small hardcoded whitelist ({@code
 * EDGE_EXECUTABLE_TOOL_NAMES}) that excluded media generation and every MCP-sourced tool, on the
 * theory that an edge session is a less-trusted execution context and a tenant-supplied MCP tool
 * schema was never reviewed the way a curated name was. That whitelist is gone: an edge session may
 * now propose/execute EXACTLY the same candidate set {@code resolveToolCandidatesFor} would hand a
 * hosted turn for the same agent - platform pool, BYOK, and edge all see identical tool access. The
 * remaining gates below (auth, RUNNER access, the agent's own {@code allowedToolNames}) are
 * unchanged and still apply identically to every caller shape.
 *
 * <p><b>Origin marker:</b> every execution here runs under a {@code "edge:"}-prefixed session id,
 * which {@code LoggingToolCallback} captures at wrap time and persists onto each {@code
 * tool_call_log} row's {@code session_id} column - so edge-originated tool calls are
 * queryable/auditable (via {@code ToolCallLogService}) distinctly from hosted chat turns without
 * any schema change. With full parity, this audit trail is the primary place to notice an on-device
 * session actually invoking a side-effectful or MCP-sourced tool.
 *
 * <p><b>The above is {@link #execute} only.</b> {@link #listTools} (the discovery half, below) does
 * NOT use {@code resolveToolCandidatesFor} - see its own javadoc for why the uncapped candidate set
 * is wrong for a call whose output becomes an on-device model's system prompt.
 *
 * <p><b>Authorization layers, in order:</b>
 *
 * <ol>
 *   <li>Authenticated + tenant-scoped, via EITHER credential form: a
 *       Keycloak JWT (the original browser path - the normal bearer + {@code TenantContextFilter}
 *       flow applies, {@code TenantContext} already populated before this controller runs) OR an
 *       {@code X-Device-Token} header (new - a desktop daemon has no Keycloak session, same posture
 *       as {@code DeviceGatewayController}'s poll/result endpoints; {@link DeviceAuthResolver#run}
 *       resolves the token to a tenant schema and populates {@code TenantContext} itself for the
 *       duration of the call). See {@code SecurityConfig}'s matchers for this path - permitAll() at
 *       that layer, verified here, same split as {@code DeviceGatewayController}. Neither
 *       credential present or valid -&gt; 401 before either agent lookup below runs. See {@link
 *       #withCallerSubject} for exactly how the two forms are told apart.
 *   <li>{@link AgentService#requireAccess} at {@code RUNNER} - the same "can this human actually
 *       use this agent" gate {@code AgentController#chat} enforces, 404 (never 403,
 *       anti-enumeration) for a missing, cross-tenant, or unshared private agent. A
 *       device-authenticated call resolves against the SAME check - it is scoped to the device's
 *       tenant schema exactly like a JWT-authenticated call is scoped to the JWT's tenant, and gets
 *       the same legacy-open-at-RUNNER / owner / admin / grant treatment {@link
 *       com.agenticai.core.sharing.ResourceAccessService#requireAccess} already gives every other
 *       non-human caller (the demo sandbox, the public agent-share surface) - see that method's
 *       javadoc.
 *   <li>The agent's own {@code allowedToolNames} allow-list (empty = unrestricted, matching {@code
 *       AgentToolProvider#filterByAllowedTools}' semantics exactly) - the only remaining tool-scope
 *       gate, and it applies to hosted turns of the same agent too.
 * </ol>
 */
@RestController
@RequestMapping("/api/edge")
public class EdgeToolExecutionController {

    private static final Logger log = LoggerFactory.getLogger(EdgeToolExecutionController.class);

    /**
     * Ceiling on facts returned to an edge session. These go straight into the on-device model's
     * system prompt, where context is the scarcest resource on the whole platform - a tier-1 WebLLM
     * model runs a 4k-8k window, and every fact spent here is a token the conversation itself
     * cannot use. 24 is deliberately below what a hosted turn would tolerate: hosted recall is
     * relevance-ranked per message, this list is fetched blind once per session, so it must stay
     * small enough to be harmless when most of it is irrelevant to the turn actually being asked.
     */
    private static final int EDGE_MEMORY_MAX_FACTS = 24;

    private final AgentService agentService;
    private final AgentToolPort agentToolProvider;
    private final ObjectMapper objectMapper;
    private final DeviceAuthResolver deviceAuthResolver;

    /**
     * ObjectProvider, not a hard dependency: AgentMemoryService is profile-gated (it needs the
     * sidecar for embeddings), and an edge session on a deployment without it must still get its
     * tools - so a missing memory subsystem degrades to "no facts", never to a failed edge surface.
     * Mirrors how AgentChatService injects the same bean.
     */
    private final ObjectProvider<AgentMemoryService> agentMemoryService;

    public EdgeToolExecutionController(
            AgentService agentService,
            AgentToolPort agentToolProvider,
            ObjectMapper objectMapper,
            DeviceAuthResolver deviceAuthResolver,
            ObjectProvider<AgentMemoryService> agentMemoryService) {
        this.agentService = agentService;
        this.agentToolProvider = agentToolProvider;
        this.objectMapper = objectMapper;
        this.deviceAuthResolver = deviceAuthResolver;
        this.agentMemoryService = agentMemoryService;
    }

    /**
     * Resolves the long-term-memory subject key from EITHER accepted credential form, then runs
     * {@code action} with it - the ONE place this controller decides "who is this caller" (see
     * class javadoc's authorization-layers list, item 1).
     *
     * <p>{@code principal} is deliberately checked against {@link AnonymousAuthenticationToken}
     * rather than just null: now that {@code SecurityConfig} permits this path unauthenticated at
     * the filter-chain layer (required so an X-Device-Token caller with no bearer token can reach
     * this controller at all), Spring Security's default {@code AnonymousAuthenticationFilter}
     * still populates a non-null {@code Principal} (getName() == "anonymousUser") for a request
     * that presents no credential whatsoever - request.getUserPrincipal() would otherwise return
     * that anonymous token and this method would wrongly treat "nobody authenticated" as "a
     * Keycloak user named anonymousUser". The only two {@code Authentication} shapes this chain's
     * {@code oauth2ResourceServer()} + default anonymous filter can ever produce for this path are
     * {@code JwtAuthenticationToken} (valid bearer JWT presented) and that anonymous token, so
     * excluding the anonymous one is equivalent to requiring a real Keycloak session - it also
     * happens to keep this method compatible with plain-{@code Principal} test doubles (see {@code
     * EdgeToolExecutionControllerTest}), which are neither anonymous nor JWT but represent "a real
     * authenticated caller" for a unit test that never runs the actual filter chain.
     *
     * <p>Falls through to the {@code X-Device-Token} header (validated via {@link
     * DeviceAuthResolver#run}, which populates {@code TenantContext} for the duration of {@code
     * action} - mirroring exactly what {@code DeviceGatewayController}'s poll/result endpoints
     * already do) only when there is no Keycloak session. An invalid/unknown/revoked device token
     * throws {@code DeviceException} (mapped to 400 by {@code GlobalExceptionHandler}), exactly
     * like every other device-token endpoint. Neither credential present -&gt; 401, without {@code
     * action} ever running (so no agent/tenant lookup happens for an unauthenticated caller).
     */
    private <T> ResponseEntity<T> withCallerSubject(
            Principal principal, String deviceToken, Function<String, ResponseEntity<T>> action) {
        if (isKeycloakAuthenticated(principal)) {
            return action.apply(principal.getName());
        }
        if (StringUtils.hasText(deviceToken)) {
            return deviceAuthResolver.run(
                    deviceToken, device -> action.apply("device:" + device.getId()));
        }
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
    }

    private static boolean isKeycloakAuthenticated(Principal principal) {
        return principal != null
                && StringUtils.hasText(principal.getName())
                && !(principal instanceof AnonymousAuthenticationToken);
    }

    public record EdgeToolExecuteRequest(UUID agentId, String toolName, String argumentsJson) {}

    /**
     * One edge-invocable tool as the client's proposal loop needs it: name, human description, and
     * the tool's JSON schema as a real JSON object ({@code inputSchema}), taken verbatim from the
     * resolved {@link ToolCallback}'s definition - the same schema the hosted LLM sees, so the
     * on-device model proposes against identical contracts.
     */
    public record EdgeToolDescriptor(String name, String description, JsonNode inputSchema) {}

    public record EdgeToolListResponse(List<EdgeToolDescriptor> tools) {}

    /**
     * {@code GET /api/edge/tools?agentId=...} - the discovery half of the propose/execute split:
     * which tools may an edge session propose for this agent, with their schemas. Same
     * authorization layers as {@link #execute} in the same order: authenticated (via EITHER a
     * Keycloak JWT or an {@code X-Device-Token} - see {@link #withCallerSubject}) + tenant-scoped,
     * {@code requireAccess(RUNNER)} (404 anti-enumeration for missing/cross-tenant/unshared
     * agents), then the resolved tool list filtered ONLY to the agent's own {@code
     * allowedToolNames} (empty = unrestricted, mirroring {@code
     * AgentToolProvider#filterByAllowedTools}) - full parity with hosted, see class javadoc.
     *
     * <p><b>Deliberately NOT {@code AgentToolProvider#resolveToolCandidatesFor} (bug fix, D-9
     * follow-up):</b> that method exists for {@link #execute}'s lookup-by-exact-name case, where
     * trimming would risk hiding the one tool the caller already knows it wants. This endpoint is
     * the opposite case - its whole output becomes the on-device model's tool-instruction
     * system-prompt block (see {@code web/app/lib/edge/tier1.ts}'s {@code
     * toolProtocolInstructions}) - so handing it the untrimmed candidate set (every
     * MCP/connector/built-in tool the tenant has resolvable, full JSON schema each) blew the D-9
     * context budget on nearly every turn, worst on a freshly-created agent: an empty {@code
     * allowedToolNames} means "unrestricted," so a new agent inherited the tenant's *entire* tool
     * catalog with nothing curated down yet. {@code AgentToolProvider#resolveToolsFor} is the SAME
     * resolution a real hosted chat turn goes through - relevance-selected and hard-capped ({@code
     * app.tools.auto-selection.max-tools- per-turn}, default 12) - called here with no {@code
     * userMessage}/{@code chatClient} (this list is fetched once per agent per session, before any
     * message exists to score relevance against - {@code fetchEdgeTools} in tier1.ts), which is the
     * same safe, already- capped blank-query path {@code AgentToolProvider#listAvailableToolsFor}'s
     * discovery probe uses. Net effect: an edge session now advertises the same bounded tool set a
     * hosted turn with a similarly generic message would see - true parity with what hosted
     * actually sends an LLM provider, not with hosted's uncapped internal candidate pool. {@link
     * #execute} is untouched (still {@code resolveToolCandidatesFor}) - a tool the model proposes
     * that happens to fall outside this trimmed list still executes fine as long as it's resolvable
     * and allow-listed; it just isn't advertised up front.
     */
    @GetMapping("/tools")
    public ResponseEntity<EdgeToolListResponse> listTools(
            @RequestParam("agentId") UUID agentId,
            @RequestHeader(value = "X-Device-Token", required = false) String deviceToken,
            Principal principal) {
        return withCallerSubject(
                principal,
                deviceToken,
                subjectKey -> {
                    Agent agent = agentService.requireAccess(agentId, PermissionLevel.RUNNER);
                    Set<String> allowed = agent.getAllowedToolNames();

                    String sessionId = "edge:" + UUID.randomUUID();

                    List<EdgeToolDescriptor> tools =
                            agentToolProvider
                                    // null depth = use the agent's stored reasoning depth; this is
                                    // a tool
                                    // DISCOVERY probe with no turn behind it, so there is no
                                    // dispatched depth
                                    // to prefer (see AgentToolProvider#resolveToolsFor).
                                    .resolveToolsFor(
                                            agentId, sessionId, subjectKey, null, null, null)
                                    .stream()
                                    // Edge execute resolves STATELESS, where deep_research /
                                    // verify_citations are fail-closed
                                    // stubs that always error; do not advertise live schemas the
                                    // on-device model cannot use.
                                    .filter(
                                            t ->
                                                    !com.agenticai.core.agents.tools.WebToolPort
                                                            .RESEARCH_TOOL_NAMES
                                                            .contains(t.getToolDefinition().name()))
                                    .filter(
                                            t ->
                                                    allowed.isEmpty()
                                                            || allowed.contains(
                                                                    t.getToolDefinition().name()))
                                    .map(
                                            t ->
                                                    new EdgeToolDescriptor(
                                                            t.getToolDefinition().name(),
                                                            t.getToolDefinition().description(),
                                                            parseInputSchema(t)))
                                    .toList();
                    return ResponseEntity.ok(new EdgeToolListResponse(tools));
                });
    }

    public record EdgeMemoryResponse(List<String> facts) {}

    /**
     * {@code GET /api/edge/memory?agentId=...} - closes finding E-6 ("edge agent has amnesia"), the
     * last open gap in EDGE-AI-STRATEGY.md's seven.
     *
     * <p><b>The gap.</b> A hosted turn gets {@code recallMemoryFactsBlock} injected into its system
     * prompt ({@code AgentChatService}); an edge turn got only the raw prompt. Tools reached parity
     * in 2026-08-06, context never did - so the same agent answering the same question knew less
     * on-device than in the cloud, which reads to a user as the local model being worse rather than
     * less-informed.
     *
     * <p><b>Why this lives in THIS controller and not a new one.</b> It needs exactly the
     * authorization {@link #execute} and {@link #listTools} already have, and the one thing that
     * must never be duplicated here is {@link #withCallerSubject}: the memory {@code subjectKey} is
     * derived from the CREDENTIAL, never from the request. A parallel controller that accepted a
     * caller-supplied subjectKey would hand any authenticated user another user's memories - which
     * is precisely the defect that got {@code
     * com.agenticai.core.agents.tools.ToolExecutionController} deleted on 2026-09-17. One
     * controller, one auth path.
     *
     * <p><b>Query-independent by default.</b> With no {@code q} this returns {@link
     * AgentMemoryService#listAll} capped at {@link #EDGE_MEMORY_MAX_FACTS}, because the client
     * fetches this ONCE PER SESSION (before any message exists to score relevance against) exactly
     * as {@code fetchEdgeTools} does - a per-turn server call would defeat the point of answering
     * locally. Passing {@code q} opts into the same relevance-ranked {@code recall} a hosted turn
     * uses, for callers that would rather spend the round trip.
     *
     * <p>Degrades to an EMPTY list, never an error, when the memory subsystem is absent - an edge
     * turn with no facts is the status quo, and failing the fetch would take the whole local turn
     * down with it.
     */
    @GetMapping("/memory")
    public ResponseEntity<EdgeMemoryResponse> listMemory(
            @RequestParam("agentId") UUID agentId,
            @RequestParam(value = "q", required = false) String query,
            @RequestHeader(value = "X-Device-Token", required = false) String deviceToken,
            Principal principal) {
        return withCallerSubject(
                principal,
                deviceToken,
                subjectKey -> {
                    // Same 404-anti-enumeration gate as the tool endpoints; a caller who cannot run
                    // the agent must not learn anything about it, memories included.
                    agentService.requireAccess(agentId, PermissionLevel.RUNNER);

                    AgentMemoryService memoryService = agentMemoryService.getIfAvailable();
                    if (memoryService == null) {
                        return ResponseEntity.ok(new EdgeMemoryResponse(List.of()));
                    }
                    try {
                        List<AgentMemoryEntry> entries =
                                StringUtils.hasText(query)
                                        ? memoryService.recall(
                                                agentId, subjectKey, query, EDGE_MEMORY_MAX_FACTS)
                                        : memoryService.listAll(agentId, subjectKey);
                        List<String> facts =
                                entries.stream()
                                        .map(AgentMemoryEntry::content)
                                        .filter(StringUtils::hasText)
                                        .limit(EDGE_MEMORY_MAX_FACTS)
                                        .toList();
                        return ResponseEntity.ok(new EdgeMemoryResponse(facts));
                    } catch (RuntimeException e) {
                        log.warn(
                                "Edge memory fetch failed for agent {} - returning no facts",
                                agentId,
                                e);
                        return ResponseEntity.ok(new EdgeMemoryResponse(List.of()));
                    }
                });
    }

    /**
     * The definition's schema string, re-emitted as a JSON object so the wire carries {@code
     * "inputSchema": { ... }} - never a double-encoded string. An unparseable schema (shouldn't
     * happen; every registered tool's schema is generated) degrades to an empty object rather than
     * failing the whole listing.
     */
    private JsonNode parseInputSchema(ToolCallback tool) {
        String schema = tool.getToolDefinition().inputSchema();
        if (!StringUtils.hasText(schema)) {
            return objectMapper.createObjectNode();
        }
        try {
            return objectMapper.readTree(schema);
        } catch (JacksonException e) {
            log.warn(
                    "Tool '{}' has an unparseable input schema - serving an empty object to the edge client. {}",
                    tool.getToolDefinition().name(),
                    e.getMessage());
            return objectMapper.createObjectNode();
        }
    }

    /** Exactly one of {@code resultJson}/{@code error} is non-null, keyed by {@code ok}. */
    public record EdgeToolExecuteResponse(boolean ok, String resultJson, String error) {

        static EdgeToolExecuteResponse success(String resultJson) {
            return new EdgeToolExecuteResponse(true, resultJson, null);
        }

        static EdgeToolExecuteResponse failure(String error) {
            return new EdgeToolExecuteResponse(false, null, error);
        }
    }

    @PostMapping("/tools/execute")
    public ResponseEntity<EdgeToolExecuteResponse> execute(
            @RequestBody EdgeToolExecuteRequest request,
            @RequestHeader(value = "X-Device-Token", required = false) String deviceToken,
            Principal principal) {
        if (request.agentId() == null || !StringUtils.hasText(request.toolName())) {
            return reject(HttpStatus.BAD_REQUEST, "Both agentId and toolName are required.");
        }
        String toolName = request.toolName().trim();

        // Layer 1: authenticated, via EITHER credential form - see
        // #withCallerSubject. 401s here (before Layer 2 ever runs) if neither
        // a Keycloak JWT nor a valid X-Device-Token is present.
        return withCallerSubject(
                principal,
                deviceToken,
                subjectKey -> executeAuthenticated(request, toolName, subjectKey));
    }

    private ResponseEntity<EdgeToolExecuteResponse> executeAuthenticated(
            EdgeToolExecuteRequest request, String toolName, String subjectKey) {
        // Layer 2: tenant-scoped lookup + RUNNER access. Throws
        // AgentNotFoundException / ResourceAccessDeniedException (both 404 via
        // GlobalExceptionHandler, anti-enumeration) for missing/cross-tenant/
        // unshared agents - deliberately BEFORE any tool-scope feedback so an
        // unauthorized caller can't even probe which tool names exist.
        Agent agent = agentService.requireAccess(request.agentId(), PermissionLevel.RUNNER);

        // Layer 3 (full parity with hosted, 2026-08-06 - see class javadoc):
        // the agent's own allow-list is the ONLY remaining tool-scope gate.
        // Empty means unrestricted - identical semantics to
        // AgentToolProvider#filterByAllowedTools, so this endpoint grants an
        // edge session EXACTLY the tools a hosted turn of the same agent gets.
        Set<String> allowed = agent.getAllowedToolNames();
        if (!allowed.isEmpty() && !allowed.contains(toolName)) {
            return reject(HttpStatus.FORBIDDEN, "This agent doesn't have access to that tool.");
        }

        String argumentsJson =
                StringUtils.hasText(request.argumentsJson()) ? request.argumentsJson() : "{}";
        try {
            objectMapper.readTree(argumentsJson);
        } catch (JacksonException e) {
            return reject(HttpStatus.BAD_REQUEST, "argumentsJson must be a valid JSON document.");
        }

        // "edge:" prefix = the origin marker LoggingToolCallback persists onto
        // every tool_call_log row for this execution (see class javadoc) -
        // unaffected by which credential form authenticated this call.
        String sessionId = "edge:" + UUID.randomUUID();
        // subjectKey is the long-term-memory identity resolved by
        // #withCallerSubject: the authenticated principal for the Keycloak
        // path (stable identity for remember_fact/recall across sessions,
        // same rule as AgentController#chat), or "device:<deviceId>" for the
        // device-token path (equally stable per paired device).

        // Resolve through the ONE existing chokepoint - never a hand-built
        // callback. resolveToolCandidatesFor (not resolveToolsFor) - this call
        // looks up ONE specific tool by name and never reaches an LLM
        // provider, so ToolRelevanceSelector's per-turn relevance cap has
        // nothing to contribute here and would only risk arbitrarily hiding
        // the exact tool this request asked for; see that method's own
        // javadoc. The RIGOROUS verifier still fails open without a chat
        // context (chatClient is null either way).
        List<ToolCallback> tools =
                agentToolProvider.resolveToolCandidatesFor(
                        request.agentId(), sessionId, subjectKey);
        ToolCallback callback =
                tools.stream()
                        .filter(t -> toolName.equals(t.getToolDefinition().name()))
                        .findFirst()
                        .orElse(null);
        if (callback == null) {
            // Whitelisted and allow-listed but not currently resolvable - e.g.
            // knowledge base not enabled on this agent, Sarvam credential
            // missing. A configuration state, not a permissions probe.
            return reject(
                    HttpStatus.CONFLICT, "That tool isn't available for this agent right now.");
        }

        try {
            String result = callback.call(argumentsJson);
            return ResponseEntity.ok(EdgeToolExecuteResponse.success(result));
        } catch (RuntimeException e) {
            // The decorator stack already logged the failure (tool_call_log row
            // + platform issue + circuit breaker); keep the caller-facing
            // message consumer-safe and detail-free.
            log.warn(
                    "Edge tool execution failed: agent {} tool {} session {}",
                    request.agentId(),
                    toolName,
                    sessionId,
                    e);
            return ResponseEntity.ok(
                    EdgeToolExecuteResponse.failure(
                            "The tool couldn't complete this time. Please try again, or use the hosted agent."));
        }
    }

    private static ResponseEntity<EdgeToolExecuteResponse> reject(
            HttpStatus status, String message) {
        return ResponseEntity.status(status).body(EdgeToolExecuteResponse.failure(message));
    }
}
