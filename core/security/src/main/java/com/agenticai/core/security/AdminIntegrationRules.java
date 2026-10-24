package com.agenticai.core.security;

import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AuthorizeHttpRequestsConfigurer;

/**
 * Platform, governance and integration rules, in chain order: edge agents, platform admin, agent
 * learning, approvals, guardrails and procedures, agent configuration, the audit log, MCP registry
 * and server, capabilities, webhooks, providers and credentials, SDLC and code intelligence.
 *
 * <p>One contiguous run of the main chain's authorization rules, moved out of {@link
 * SecurityConfig} so that file stays reviewable. Spring Security applies the FIRST matching rule,
 * so order is significant: {@code SecurityConfig#filterChain} calls the rule classes in a fixed
 * order, and the rules inside each class keep their original order. {@code
 * SecurityRuleSnapshotTest} pins the whole chain (order, matchers, decisions).
 */
final class AdminIntegrationRules {

    private AdminIntegrationRules() {}

    static void apply(
            AuthorizeHttpRequestsConfigurer<HttpSecurity>.AuthorizationManagerRequestMatcherRegistry
                    authorize) {
        authorize
                // Edge Agents runtime kill switch - the
                // anonymous /try
                // surface must read this before any sign-in, same reasoning
                // as
                // /api/demo/** above. Read-only, no tenant data - safe
                // unauthenticated.
                .requestMatchers(HttpMethod.GET, "/api/edge/config")
                .permitAll()
                // On-device model manifest - same reasoning as
                // /api/edge/config directly above: read-only, static model
                // metadata with no tenant data, needed by the pre-sign-in
                // /try
                // surface's download manager. Safe unauthenticated.
                .requestMatchers(HttpMethod.GET, "/api/edge/manifest")
                .permitAll()
                // Desktop daemon device-token support
                // for the propose/execute tool bridge
                // (EdgeToolExecutionController).
                // A desktop daemon authenticates by X-Device-Token, exactly
                // like
                // /api/devices/** above, and has no Keycloak session to
                // present -
                // so this now needs the SAME "permitAll() here, verify the
                // credential inside the controller" posture as
                // DeviceGatewayController, rather than staying behind
                // anyRequest().authenticated() as it did before this
                // endpoint
                // accepted a second credential form. This is NOT a
                // weakening of
                // the existing browser path: oauth2ResourceServer() below
                // still
                // runs against any Authorization: Bearer header this
                // request
                // carries (see alertWebhookFilterChain's javadoc for why
                // that
                // filter processes bearer tokens independently of the
                // authorizeHttpRequests decision when, as here, it is part
                // of
                // THIS chain), so a present-but-invalid JWT is still
                // rejected
                // before the controller runs, exactly as before. The
                // endpoint
                // remains fully tenant-scoped - EdgeToolExecutionController
                // requires EITHER a valid JwtAuthenticationToken OR a valid
                // X-Device-Token (resolved via DeviceAuthResolver, same as
                // DeviceGatewayController) and 401s a request presenting
                // neither.
                .requestMatchers(HttpMethod.GET, "/api/edge/tools")
                .permitAll()
                // Same posture as /api/edge/tools directly above, and for
                // the same reason:
                // permitAll at the FILTER layer is what lets an
                // X-Device-Token caller (no
                // Keycloak bearer) reach the controller at all.
                // EdgeToolExecutionController
                // then does the real authorization itself -
                // withCallerSubject requires either
                // a non-anonymous Keycloak principal or a valid device
                // token and 401s
                // otherwise, and requireAccess(RUNNER) 404s a caller who
                // cannot run the agent.
                // The memory subjectKey is derived from that credential,
                // never from the
                // request, so this endpoint cannot be used to read another
                // user's facts.
                .requestMatchers(HttpMethod.GET, "/api/edge/memory")
                .permitAll()
                .requestMatchers(HttpMethod.POST, "/api/edge/tools/execute")
                .permitAll()
                // Edge turn quality telemetry (EdgeTurnTelemetryController,
                // 2026-08-06) - fire-and-forget from tierRouter.ts after
                // EVERY
                // edge turn, so it must never 401: the anonymous /try
                // surface
                // gets a structured log line only, an authenticated caller
                // additionally gets an RL episode. permitAll here does not
                // weaken anything - oauth2ResourceServer() still validates
                // any
                // Bearer token presented (same reasoning as /api/edge/tools
                // directly above), the controller itself checks Principal
                // before ever writing RL data.
                .requestMatchers(HttpMethod.POST, "/api/edge/turns")
                .permitAll()
                // Platform-admin-only Edge AI model catalog editor
                // (web-admin's edge-models/page.tsx): unlike /manifest
                // above,
                // this ALSO reveals admin-disabled candidate models, and it
                // is consumed only by the platform-admin console - not the
                // anonymous /try surface - so it belongs with the rest of
                // the
                // platform-admin surface below, NOT permitAll. Must be
                // registered before the generic /api/edge/** exposure stays
                // limited to exactly config+manifest above; every other
                // /api/edge/** path (tools, sessions) keeps falling through
                // to anyRequest().authenticated() as before.
                .requestMatchers(HttpMethod.GET, "/api/edge/manifest/full")
                .hasAuthority("ROLE_platform-admin")
                // "Browse official registry" candidate list
                // (EdgeAgentsConfigController's
                // REGISTRY_CATALOG) - same admin-only reasoning as
                // /manifest/full above.
                .requestMatchers(HttpMethod.GET, "/api/edge/manifest/registry")
                .hasAuthority("ROLE_platform-admin")
                // Granular RBAC (2026-09-23): every tenant-scoped admin
                // surface below now requires a PERM_<scope> authority,
                // which
                // GranularPermissionConverter emits from the caller's
                // stored
                // user_permission_grant rows - grants bind tenant-admins
                // too
                // (the role seeds the full bundle, it is not a bypass). The
                // /api/admin tree is split per-domain; the trailing
                // catch-all
                // stays MANAGE_USERS so a future admin subtree fails CLOSED
                // (tenant-admin-equivalent) rather than silently opening to
                // every authenticated member.
                .requestMatchers("/api/admin/users", "/api/admin/users/**")
                .hasAuthority("PERM_MANAGE_USERS")
                // SEC-010: the template queue lives in control_plane and is
                // shared by EVERY tenant, so
                // moderating it is platform-admin only.
                // PERM_MANAGE_TEMPLATES is a tenant permission that
                // every tenant admin holds; it must not admit anyone here.
                // Also enforced by @PreAuthorize
                // on AdminTemplateController and
                // CommunityTemplateService.requireAdmin.
                .requestMatchers("/api/admin/templates", "/api/admin/templates/**")
                .hasAuthority("ROLE_platform-admin")
                .requestMatchers("/api/admin/issues", "/api/admin/issues/**")
                .hasAuthority("PERM_VIEW_AUDIT_LOG")
                .requestMatchers("/api/admin/llm-usage/**", "/api/admin/spend-reports/**")
                .hasAuthority("PERM_VIEW_USAGE")
                .requestMatchers("/api/admin/**")
                .hasAuthority("PERM_MANAGE_USERS")
                // core/rl Agent-Learning policy editor: viewing/toggling an
                // agent's exploration policy changes how it behaves, so
                // both
                // the GET (admin-only editor load) and PUT are tenant-admin
                // -
                // the sibling /api/rl/agents/*/summary read stays open to
                // any
                // authenticated user (the dashboard view) via the
                // fall-through
                // below. Path-specific so /summary is unaffected.
                .requestMatchers(
                        "/api/rl/agents/*/policies",
                        "/api/rl/agents/*/policies/**",
                        // Preference export returns raw conversation text
                        // (training
                        // data) - admin-only, same posture as the policy
                        // editor.
                        "/api/rl/agents/*/preferences",
                        // NDJSON trace/preference exports are the same raw
                        // conversation text under a different format - same
                        // gate.
                        "/api/rl/agents/*/export/**")
                .hasAuthority("PERM_MANAGE_AGENTS")
                .requestMatchers(HttpMethod.PUT, "/api/approvals/policy/**")
                .hasAuthority("PERM_MANAGE_APPROVALS")
                // Tenant-authored guardrail rules (core/guardrails,
                // GuardrailRuleController). READS stay open to every member
                // -
                // "what is being filtered in this workspace" is not a
                // secret,
                // and the console shows the list to everyone. WRITES are
                // workspace-wide safety configuration that applies to every
                // agent and every member's tool calls, which is the same
                // bar
                // as the approval policy directly above, so they get the
                // same
                // gate. Before this, any authenticated member could author
                // or
                // DELETE a rule; the plan check in GuardrailRuleService was
                // the
                // only thing in the way, and it does not distinguish
                // members
                // from admins. Method-specific so the GET list/detail
                // endpoints keep falling through to authenticated().
                .requestMatchers(HttpMethod.POST, "/api/guardrails/rules")
                .hasAuthority("PERM_MANAGE_GUARDRAILS")
                .requestMatchers(HttpMethod.PUT, "/api/guardrails/rules/**")
                .hasAuthority("PERM_MANAGE_GUARDRAILS")
                .requestMatchers(HttpMethod.DELETE, "/api/guardrails/rules/**")
                .hasAuthority("PERM_MANAGE_GUARDRAILS")
                // Procedures / SOP layer (Tier-3, V270): the SOP documents
                // AND
                // their agent attachments are enforcement rules, so every
                // write
                // takes the same PERM_MANAGE_GUARDRAILS authority as tenant
                // guardrail rules. GETs fall through to authenticated().
                .requestMatchers(HttpMethod.POST, "/api/procedures", "/api/procedures/**")
                .hasAuthority("PERM_MANAGE_GUARDRAILS")
                .requestMatchers(HttpMethod.PUT, "/api/procedures/**")
                .hasAuthority("PERM_MANAGE_GUARDRAILS")
                .requestMatchers(HttpMethod.DELETE, "/api/procedures/**")
                .hasAuthority("PERM_MANAGE_GUARDRAILS")
                .requestMatchers(
                        HttpMethod.POST, "/api/agents/*/procedures", "/api/agents/*/procedures/**")
                .hasAuthority("PERM_MANAGE_GUARDRAILS")
                .requestMatchers(HttpMethod.DELETE, "/api/agents/*/procedures/**")
                .hasAuthority("PERM_MANAGE_GUARDRAILS")
                // Accepting an improvement can create, attach and activate
                // a procedure, so it needs the same permission as writing
                // one directly above.
                .requestMatchers(HttpMethod.POST, "/api/agents/*/improvements/*/accept")
                .hasAuthority("PERM_MANAGE_GUARDRAILS")
                // Moderation queue decisions: records
                // ModerationReviewController's own check.
                .requestMatchers(
                        HttpMethod.POST,
                        "/api/moderation/review/*/approve",
                        "/api/moderation/review/*/block",
                        "/api/moderation/review/*/escalate")
                .hasAnyAuthority("PERM_MANAGE_GUARDRAILS", "ROLE_platform-admin")
                // Which content guardrails an agent runs: switching them off
                // is a guardrail decision, like the agent's procedures and
                // tool policies above, not an agent edit.
                .requestMatchers(HttpMethod.PATCH, "/api/agents/*/guardrails")
                .hasAuthority("PERM_MANAGE_GUARDRAILS")
                // Agent hooks and tool-argument policies (sync 4) are
                // enforcement rules on an agent's tool
                // calls, the same class as the agent procedure attachments
                // just above. Hooks mirror
                // AgentHookConfigService#requireManageAuthority exactly
                // (guardrails permission, tenant admin or
                // platform admin; the service additionally needs EDITOR on
                // the agent) - workspace defaults
                // under /api/settings/agent-hooks too. Tool-argument
                // policies take PERM_MANAGE_GUARDRAILS like
                // the procedures (ToolArgPolicyService#put still requires
                // EDITOR on the agent): clearing one
                // loosens what the agent may pass to a tool, which is not
                // an agent-editor decision. GETs fall
                // through to authenticated() (VIEWER in-service; hook
                // webhook URLs are redacted there).
                .requestMatchers(HttpMethod.PUT, "/api/agents/*/hooks", "/api/settings/agent-hooks")
                .hasAnyAuthority(
                        "PERM_MANAGE_GUARDRAILS", "ROLE_tenant-admin", "ROLE_platform-admin")
                .requestMatchers(HttpMethod.PUT, "/api/agents/*/tool-policies")
                .hasAuthority("PERM_MANAGE_GUARDRAILS")
                // /** so the action-types filter-dropdown endpoint
                // (/api/audit-log/action-types) gets the same tenant-admin
                // gate as the log itself - was an exact match on just
                // /api/audit-log until that second endpoint was added.
                .requestMatchers("/api/audit-log", "/api/audit-log/**")
                .hasAuthority("PERM_VIEW_AUDIT_LOG")
                // ADR-0020: the per-turn journal is audit evidence (hash-chained
                // stage events of one agent turn), so it takes the audit-log
                // permission; the service additionally needs VIEWER on the agent.
                .requestMatchers(HttpMethod.GET, "/api/agents/*/turns/*/journal")
                .hasAuthority("PERM_VIEW_AUDIT_LOG")
                // The same evidence found by chat session instead of by turn id.
                .requestMatchers(HttpMethod.GET, "/api/agents/*/turn-journal")
                .hasAuthority("PERM_VIEW_AUDIT_LOG")
                .requestMatchers(HttpMethod.POST, "/api/mcp/registry")
                .hasAuthority("PERM_MANAGE_INTEGRATIONS")
                // Pentest M8: POST /api/mcp/registry/{id}/connect
                // (connectNow)
                // spawns a subprocess / makes an outbound request just like
                // registering does, but the exact-path POST rule above
                // didn't
                // cover it - it fell through to
                // anyRequest().authenticated(),
                // letting any authenticated tenant user re-trigger a
                // connect.
                // This wildcard POST rule closes that (register is the bare
                // path, so it's unaffected by the /*/ segment requirement).
                .requestMatchers(HttpMethod.POST, "/api/mcp/registry/*/connect")
                .hasAuthority("PERM_MANAGE_INTEGRATIONS")
                .requestMatchers(HttpMethod.PATCH, "/api/mcp/registry/**")
                .hasAuthority("PERM_MANAGE_INTEGRATIONS")
                // #edit-delete-gap: DELETE /api/mcp/registry/{id} removes a
                // registration outright - same admin-action class as the
                // POST/
                // PATCH rules right above (and without this, it would fall
                // through to anyRequest().authenticated(), letting any
                // authenticated tenant user delete another user's
                // connector).
                .requestMatchers(HttpMethod.DELETE, "/api/mcp/registry/**")
                .hasAuthority("PERM_MANAGE_INTEGRATIONS")
                // Provider-registry OAuth (OAuthProviderController):
                // starting a connection, attaching
                // it to a server and disconnecting are the same
                // admin-action class as registering an
                // MCP server; GET /api/mcp/oauth/providers stays read-open
                // to any authenticated user.
                .requestMatchers(HttpMethod.POST, "/api/mcp/oauth/**")
                .hasAuthority("PERM_MANAGE_INTEGRATIONS")
                .requestMatchers(HttpMethod.DELETE, "/api/mcp/oauth/**")
                .hasAuthority("PERM_MANAGE_INTEGRATIONS")
                // MCP Connection Doctor (2026-07-25): /test spawns a real
                // subprocess / makes a real outbound request, /acknowledge
                // overrides the agent-usage readiness gate - both are the
                // same class of admin action as registering the server in
                // the first place (the POST matcher right above).
                .requestMatchers(
                        HttpMethod.POST,
                        "/api/mcp/registrations/*/test",
                        "/api/mcp/registrations/*/acknowledge",
                        // Approving a changed tool list re-enables the server's tools for
                        // agents: same admin class as acknowledging an unverified one.
                        "/api/mcp/tool-pins/*/approve")
                .hasAuthority("PERM_MANAGE_INTEGRATIONS")
                // Jev BYOK (core/agents JevController): writing the
                // tenant's
                // TypeSafe credential - activate (POST /credential) and
                // deactivate (DELETE /credential) - is the same class of
                // admin action as registering a connector, matching the
                // MANAGE_INTEGRATIONS gate the /jev console already applies
                // client-side. Reads (/status, /credential/status) stay open
                // to any authenticated tenant member. The playground spends
                // the tenant's key, so it takes the same permission
                // (authorization review, 2026-10).
                .requestMatchers(HttpMethod.POST, "/api/jev/credential")
                .hasAuthority("PERM_MANAGE_INTEGRATIONS")
                .requestMatchers(HttpMethod.DELETE, "/api/jev/credential")
                .hasAuthority("PERM_MANAGE_INTEGRATIONS")
                // Same gate for the policy write - changing where Jev may
                // act
                // tenant-wide is an admin decision, same class as
                // credential
                // writes. GET /api/jev/policy stays open (read-only
                // status).
                .requestMatchers(HttpMethod.PUT, "/api/jev/policy")
                .hasAuthority("PERM_MANAGE_INTEGRATIONS")
                .requestMatchers(HttpMethod.POST, "/api/jev/playground")
                .hasAuthority("PERM_MANAGE_INTEGRATIONS")
                // Generic capability registry (core/providers
                // CapabilityController
                // + core/agents DecisionCapabilityController): the same
                // gates as
                // the /api/jev compat shim they supersede - credential
                // writes and
                // policy PUTs are MANAGE_INTEGRATIONS; reads stay open to
                // any authenticated tenant member, and the playground, which
                // spends the tenant's key, is MANAGE_INTEGRATIONS too.
                .requestMatchers(HttpMethod.POST, "/api/capabilities/*/credential")
                .hasAuthority("PERM_MANAGE_INTEGRATIONS")
                .requestMatchers(HttpMethod.DELETE, "/api/capabilities/*/credential")
                .hasAuthority("PERM_MANAGE_INTEGRATIONS")
                .requestMatchers(HttpMethod.PUT, "/api/capabilities/*/policy")
                .hasAuthority("PERM_MANAGE_INTEGRATIONS")
                .requestMatchers(HttpMethod.POST, "/api/capabilities/decision/playground")
                .hasAuthority("PERM_MANAGE_INTEGRATIONS")
                // core/mcpserver admin surface -
                // generating/rotating/revoking an
                // API key that can trigger real agent behavior is an admin
                // action, same posture as the registry lines above; plain
                // GET
                // (listing grants, never the raw key - see
                // McpServerAccessGrantResponse's javadoc) falls through to
                // anyRequest().authenticated() below, same as the
                // registry's
                // own GET.
                .requestMatchers(
                        HttpMethod.POST, "/api/mcpserver/grants", "/api/mcpserver/grants/*/rotate")
                .hasAuthority("PERM_MANAGE_INTEGRATIONS")
                .requestMatchers(HttpMethod.PUT, "/api/mcpserver/grants/**")
                .hasAuthority("PERM_MANAGE_INTEGRATIONS")
                .requestMatchers(HttpMethod.PATCH, "/api/mcpserver/grants/**")
                .hasAuthority("PERM_MANAGE_INTEGRATIONS")
                .requestMatchers(HttpMethod.DELETE, "/api/mcpserver/grants/**")
                .hasAuthority("PERM_MANAGE_INTEGRATIONS")
                // "Expose as MCP tool" (sync 4) publishes an agent/workflow
                // to every holder of an MCP grant
                // key - the same external-exposure decision as minting the
                // grants above, so the same
                // permission. McpToolExposureService#setExposed still
                // requires EDITOR on the resource; GET
                // falls through to authenticated() (VIEWER in-service).
                .requestMatchers(HttpMethod.PUT, "/api/mcpserver/exposure/*/*")
                .hasAuthority("PERM_MANAGE_INTEGRATIONS")
                // Data-governance audit fix (2026-07-20) - unlike the plain
                // grant-list GET above,
                // this one returns every tool_call_log row (input/output
                // included) attributed to
                // one grant, the same class of potentially sensitive
                // content /api/audit-log is
                // already gated for below - explicitly ROLE_tenant-admin
                // rather than left to fall
                // through to plain anyRequest().authenticated().
                // How callers may authenticate to /mcp (OAuth settings, allowed origins): admin view.
                .requestMatchers(HttpMethod.GET, "/api/mcpserver/grants/auth-options")
                .hasAuthority("PERM_MANAGE_INTEGRATIONS")
                .requestMatchers(HttpMethod.GET, "/api/mcpserver/grants/*/tool-calls")
                .hasAuthority("PERM_MANAGE_INTEGRATIONS")
                // Tenant outbound webhooks (V241) - same
                // PERM_MANAGE_INTEGRATIONS
                // posture as the MCP registry/grant writes above: creating
                // a
                // subscription means choosing an external endpoint this
                // platform
                // will POST tenant event data to, so it is
                // integration-config
                // admin work, not member work. GETs (list, detail,
                // event-type
                // catalog, delivery history) fall through to
                // anyRequest().authenticated() like every other integration
                // read.
                .requestMatchers(
                        HttpMethod.POST,
                        "/api/webhooks",
                        "/api/webhooks/*/rotate-secret",
                        "/api/webhooks/*/test")
                .hasAuthority("PERM_MANAGE_INTEGRATIONS")
                .requestMatchers(HttpMethod.PATCH, "/api/webhooks/**")
                .hasAuthority("PERM_MANAGE_INTEGRATIONS")
                .requestMatchers(HttpMethod.DELETE, "/api/webhooks/**")
                .hasAuthority("PERM_MANAGE_INTEGRATIONS")
                // Integration/credential config is admin-only across the
                // board (LLM providers, Twilio/WhatsApp, and the workflow
                // webhook trigger below) - a deliberate B2B/B2C-agnostic
                // rule, not two different rules for two tenant shapes:
                // every
                // tenant's *first* signed-up user is already granted
                // tenant-admin (see TenantProvisioningService), so a solo
                // B2C user configuring everything themselves and a B2B team
                // member being blocked from it are both just this one rule
                // applied to whatever role the caller actually holds. Reads
                // (GET) stay open to any authenticated tenant member in
                // both
                // cases - a non-admin teammate still needs to see which
                // provider/model an agent uses, just not change the key.
                .requestMatchers(HttpMethod.POST, "/api/providers")
                .hasAuthority("PERM_MANAGE_INTEGRATIONS")
                // Tool-call probe of the connected local model server: makes the platform send a
                // chat request to the tenant's own server, so it is admin-only like activation.
                .requestMatchers(HttpMethod.POST, "/api/providers/local-openai/probe")
                .hasAuthority("PERM_MANAGE_INTEGRATIONS")
                // "Give option to edit, delete the added key" - direct
                // feedback.
                // Exact-path POST above only ever covered activation;
                // DELETE here
                // is a sub-path (/api/providers/{provider}), so per the
                // SAST audit
                // 2026-07-29 (C3) lesson two lines below (exact-only
                // matchers leave
                // sub-paths uncovered), this needs its own explicit
                // wildcard rule
                // rather than assuming the POST line's protection extends
                // to it.
                .requestMatchers(HttpMethod.DELETE, "/api/providers/*")
                .hasAuthority("PERM_MANAGE_INTEGRATIONS")
                // Sarvam AI credential
                // - same BYOK-credential-write posture as the
                // provider/Twilio
                // lines here: only a tenant admin activates/deactivates the
                // key, GET status falls through to
                // anyRequest().authenticated()
                // below like every other credential-status read.
                .requestMatchers(HttpMethod.POST, "/api/sarvam/credential")
                .hasAuthority("PERM_MANAGE_INTEGRATIONS")
                .requestMatchers(HttpMethod.DELETE, "/api/sarvam/credential")
                .hasAuthority("PERM_MANAGE_INTEGRATIONS")
                // SDLC automation sandbox scaffold (#849) - same
                // BYOK-credential-write
                // posture as the Sarvam lines above. Sandbox lifecycle
                // writes
                // (create/kill a real, billed E2B sandbox capable of
                // running
                // arbitrary code) are gated the same way - a materially
                // more
                // sensitive action than a typical channel/provider
                // credential
                // write, not less; GET (listing what's running) stays open
                // to
                // any authenticated tenant member, same posture as every
                // other
                // integration status read in this file.
                .requestMatchers(HttpMethod.POST, "/api/sdlc/credential")
                .hasAuthority("PERM_MANAGE_INTEGRATIONS")
                .requestMatchers(HttpMethod.DELETE, "/api/sdlc/credential")
                .hasAuthority("PERM_MANAGE_INTEGRATIONS")
                // IDE/browser plugin SDLC alignment (2026-08-09): a
                // tenant-admin can
                // now hand a developer the `agent-builder` role
                // specifically to unlock
                // the plugins' "run snippet in sandbox" feature. Scoped to
                // the
                // stateless run-code primitive ONLY -
                // SdlcSandboxService.runCode
                // creates a disposable sandbox, executes the one call, and
                // always
                // kills it itself, so there is no lingering sandbox for
                // this grant to
                // leave behind. Declared BEFORE the broader
                // tenant-admin-only rule
                // below so this specific path wins the first-match;
                // standalone
                // persistent sandbox creation (POST /api/sdlc/sandbox with
                // caller-supplied env vars/metadata/template) and killing
                // an arbitrary
                // running sandbox (DELETE) are a materially more open-ended
                // primitive
                // that was never in scope for this grant and stay
                // tenant-admin-only.
                .requestMatchers(HttpMethod.POST, "/api/sdlc/sandbox/run-code")
                .hasAnyAuthority("ROLE_tenant-admin", "ROLE_agent-builder")
                // SAST audit 2026-07-29 (C3): the two lines below used to
                // be
                // POST-exact + DELETE-wildcard, which left POST SUB-paths
                // uncovered - and E2bSandboxController's
                // @PostMapping("/run-code")
                // (:64) is exactly that shape, with no @PreAuthorize of its
                // own.
                // Any non-admin tenant member could therefore execute
                // attacker-supplied python/node/bash in a real sandbox
                // billed to
                // the tenant's key - precisely the threat the comment above
                // says
                // these lines exist to block. POST is now exact AND
                // wildcard.
                // PUT /api/sdlc/credential/code-execution-template
                // (E2bCredentialController:47) was ungated by the same
                // omission
                // and chains with it into a fully attacker-controlled
                // execution
                // environment, so it is covered here too.
                .requestMatchers(HttpMethod.POST, "/api/sdlc/sandbox", "/api/sdlc/sandbox/**")
                .hasAuthority("PERM_MANAGE_INTEGRATIONS")
                .requestMatchers(HttpMethod.PUT, "/api/sdlc/credential/**")
                .hasAuthority("PERM_MANAGE_INTEGRATIONS")
                .requestMatchers(HttpMethod.DELETE, "/api/sdlc/sandbox/**")
                .hasAuthority("PERM_MANAGE_INTEGRATIONS")
                // SAST audit 2026-07-29 (C2): /api/code-intel/** had NO
                // matcher
                // at all, so all 21 handlers on CodeIntelController
                // (@RequestMapping "/api/code-intel", :63) fell through to
                // anyRequest().authenticated() - reachable by ANY tenant
                // member
                // while CODE_INTEL_ENABLED=true in production. Three of
                // them are
                // materially dangerous: POST /repos with source_type "git"
                // runs
                // `git clone <caller-supplied URL>` with no scheme/host
                // check and
                // resolves a caller-named env var into git's credential
                // helper
                // (an attacker host answering 401 harvests the internal
                // token,
                // which code-intel treats as pre-approved and which
                // therefore
                // BYPASSES the human-approval gate on workspace apply/run);
                // source_type "local" with an arbitrary path yields
                // arbitrary
                // file read via the search index; and PUT /policy widens
                // the
                // tenant's own code-intel guardrails. Gated to
                // tenant-admin,
                // mirroring /api/mcp/registry above - the closest analogue,
                // since
                // both register externally-controlled code for the agent to
                // run.
                // Two handlers (:186 approvals/resolve, :273) additionally
                // self-check admin; that stays as defence in depth.
                // IDE/browser plugin SDLC alignment (2026-08-09): widened
                // to
                // agent-builder on the same "tenant-admin decides who gets
                // this role"
                // basis as the run-code/validate grants below - a
                // tenant-admin
                // explicitly opts a developer into this, it is not open to
                // every
                // signed-in tenant member. The two in-controller
                // self-checks noted
                // above (:186 approvals/resolve, :273 agent/resolve) are
                // untouched by
                // this matcher and stay tenant-admin-only regardless of
                // authority.
                .requestMatchers("/api/code-intel/**")
                .hasAnyAuthority("ROLE_tenant-admin", "ROLE_agent-builder")
                // Pointing an agent at code-intel repos, or giving it a
                // code workspace (create/diff/delete without approval), is
                // the same opt-in as the /api/code-intel/** surface above;
                // without this an agent's owner could reach those repos
                // through the agent.
                .requestMatchers(
                        HttpMethod.PATCH,
                        "/api/agents/*/code-intel-repo-access",
                        "/api/agents/*/capabilities/code-workspace")
                .hasAnyAuthority("ROLE_tenant-admin", "ROLE_agent-builder");
    }
}
