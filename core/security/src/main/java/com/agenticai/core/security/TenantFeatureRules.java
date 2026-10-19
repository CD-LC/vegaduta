package com.agenticai.core.security;

import org.springframework.http.HttpMethod;
import org.springframework.security.authorization.AuthorityAuthorizationManager;
import org.springframework.security.authorization.AuthorizationManagers;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AuthorizeHttpRequestsConfigurer;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;

/**
 * Tenant and feature rules, in chain order: data tables, tenant settings and billing, SCIM, payment
 * mandates, internal calls, platform, briefs, wallets and commerce, memory, research, account,
 * mobile, browser automation, coding tasks and completions.
 *
 * <p>One contiguous run of the main chain's authorization rules, moved out of {@link
 * SecurityConfig} so that file stays reviewable. Spring Security applies the FIRST matching rule,
 * so order is significant: {@code SecurityConfig#filterChain} calls the rule classes in a fixed
 * order, and the rules inside each class keep their original order. {@code
 * SecurityRuleSnapshotTest} pins the whole chain (order, matchers, decisions).
 */
final class TenantFeatureRules {

    private TenantFeatureRules() {}

    static void apply(
            AuthorizeHttpRequestsConfigurer<HttpSecurity>.AuthorizationManagerRequestMatcherRegistry
                    authorize) {
        authorize
                // Data Tables (core/agents tables.DataTableController):
                // tenant-wide shared state that workflows
                // and agents read and write, so every mutating verb
                // (create/rename/delete a table, change its
                // columns, write rows, bulk CSV import) takes the
                // workflow-admin permission. Reads (list, rows,
                // export, config) stay open to any member - the workflow
                // editor lists tables to build a node.
                .requestMatchers(HttpMethod.POST, "/api/data-tables", "/api/data-tables/**")
                .hasAuthority("PERM_MANAGE_WORKFLOWS_ADMIN")
                .requestMatchers(HttpMethod.PUT, "/api/data-tables/**")
                .hasAuthority("PERM_MANAGE_WORKFLOWS_ADMIN")
                .requestMatchers(HttpMethod.DELETE, "/api/data-tables/**")
                .hasAuthority("PERM_MANAGE_WORKFLOWS_ADMIN")
                // GET /api/tenant/settings is readable by any authenticated
                // tenant member (falls through to
                // anyRequest().authenticated()
                // below) - only changing the Simple/Advanced default is an
                // admin-only write.
                .requestMatchers(HttpMethod.PUT, "/api/tenant/settings")
                .hasAuthority("PERM_MANAGE_TENANT_SETTINGS")
                // Uploading/removing the white-label logo writes the very
                // same
                // brand_logo_url column the PUT above guards, so it carries
                // the
                // identical admin-only posture. There is no blanket
                // /api/tenant/** rule in this chain (settings GET
                // deliberately
                // falls through to authenticated()), so these are spelled
                // out
                // rather than assumed. The anonymous READ half is the
                // /api/share/branding/** rule further up.
                .requestMatchers(HttpMethod.POST, "/api/tenant/branding/logo")
                .hasAuthority("PERM_MANAGE_TENANT_SETTINGS")
                .requestMatchers(HttpMethod.DELETE, "/api/tenant/branding/logo")
                .hasAuthority("PERM_MANAGE_TENANT_SETTINGS")
                // "AI Model Control" settings page
                // (TenantAiModelPreferenceController) -
                // same admin-only-write / any-member-read posture as the
                // settings
                // line just above; GET falls through to
                // anyRequest().authenticated().
                .requestMatchers(HttpMethod.PUT, "/api/tenant/ai-model-preferences/**")
                .hasAuthority("PERM_MANAGE_TENANT_SETTINGS")
                .requestMatchers(HttpMethod.DELETE, "/api/tenant/ai-model-preferences/**")
                .hasAuthority("PERM_MANAGE_TENANT_SETTINGS")
                // LLM-gateway content policy (PII redaction /
                // prompt-injection
                // screening, LlmGatewayContentPolicyController) -
                // workspace-wide
                // safety config, so the write takes the same authority as
                // tenant guardrail rules; GET falls through to
                // authenticated().
                .requestMatchers(HttpMethod.PUT, "/api/tenant/llm-gateway/content-policy")
                .hasAuthority("PERM_MANAGE_GUARDRAILS")
                // P2 tenant SSO/SCIM productization (core/admin/sso) - the
                // tenant's own IdP config (OIDC/SAML) and the bearer tokens
                // for its SCIM-lite provisioning endpoint are
                // tenant-security
                // settings, same trust tier as the branding/settings writes
                // above. GET is gated too here - unlike
                // /api/tenant/settings,
                // even READING an SSO config leaks the tenant's IdP shape.
                .requestMatchers("/api/tenant/sso", "/api/tenant/sso/**")
                .hasAuthority("PERM_MANAGE_TENANT_SETTINGS")
                // The SCIM-lite endpoint itself (/scim/v2/Users) is called
                // by
                // the TENANT'S IdP (Okta/Entra provisioning jobs) with a
                // bearer
                // token ScimProvisioningController verifies itself against
                // tenant_scim_token's hash - it can't carry a Keycloak JWT,
                // so
                // it's permitAll at the filter layer like /webhook/** and
                // authenticated inside the controller.
                .requestMatchers("/scim/v2/**")
                .permitAll()
                // Tenant REST API keys (V483, TenantApiKeyController) -
                // issuing a machine credential that can carry PERM_* scopes
                // IS permission administration, so every method (list/read
                // included - even a key list reveals integration surface)
                // sits at the same tier as /api/admin/users.
                .requestMatchers("/api/tenant/api-keys", "/api/tenant/api-keys/**")
                .hasAuthority("PERM_MANAGE_USERS")
                // #811 - a tenant-admin's own self-serve plan upgrade
                // (commits the workspace to a real paid Razorpay
                // subscription) - same admin-only posture as the settings
                // write just above, not readable/writable by every member.
                .requestMatchers(HttpMethod.POST, "/api/tenant/upgrade")
                .hasAuthority("PERM_MANAGE_BILLING")
                // GST invoicing (audit D6, InvoiceController in
                // core/billing)
                // - invoice history/downloads and the GSTIN/billing-profile
                // block are billing-sensitive, same admin-only trust tier
                // as
                // the upgrade line just above.
                .requestMatchers(HttpMethod.GET, "/api/tenant/invoices", "/api/tenant/invoices/**")
                .hasAuthority("PERM_MANAGE_BILLING")
                .requestMatchers(HttpMethod.GET, "/api/tenant/billing-profile")
                .hasAuthority("PERM_MANAGE_BILLING")
                .requestMatchers(HttpMethod.PUT, "/api/tenant/billing-profile")
                .hasAuthority("PERM_MANAGE_BILLING")
                // Resolution metering (Tier-4 outcome-based pricing,
                // ResolutionController in core/billing) - the event list,
                // summary, explicit record and dispute endpoints are all
                // billing-sensitive: this is the surface a tenant audits
                // (and contests) their per-resolution charges against.
                // Same admin-only trust tier as the invoice lines above.
                .requestMatchers("/api/tenant/resolutions", "/api/tenant/resolutions/**")
                .hasAuthority("PERM_MANAGE_BILLING")
                // Per-agent cost and ROI view (RoiController in core/app):
                // reading it needs the same permission as the spend reports;
                // changing prices, value assumptions or recording outcomes is
                // billing-tier, like the resolution meter it reuses.
                .requestMatchers(HttpMethod.GET, "/api/tenant/roi", "/api/tenant/roi/**")
                .hasAuthority("PERM_VIEW_USAGE")
                .requestMatchers("/api/tenant/roi", "/api/tenant/roi/**")
                .hasAuthority("PERM_MANAGE_BILLING")
                // D5 (PRICING-MONETIZATION-AUDIT-2026-07-31 §8.7) - the
                // mirror-image of the upgrade line above: ending the
                // workspace's paid subscription is at least as
                // consequential
                // as starting one, so the same admin-only gate. GET
                // /api/tenant/subscription (the status view) deliberately
                // falls through to anyRequest().authenticated() below - any
                // member can see the plan state, same as GET
                // /api/tenant/settings.
                .requestMatchers(HttpMethod.POST, "/api/tenant/subscription/cancel")
                .hasAuthority("PERM_MANAGE_BILLING")
                // Authorization review 2026-10-10. The commerce catalog
                // (/api/tenant/commerce/products) feeds the PUBLIC checkout
                // feed, where prices are taken from these rows: any member
                // could create, reprice or delete what the tenant sells.
                // Payment checkout (create-order, verify-payment, the UPI
                // collect/QR variants) charges the tenant's customers or the
                // tenant itself through the tenant's gateway credentials -
                // same admin-only tier as /api/tenant/upgrade. The
                // per-member prepaid budget uses its own /api/budget/top-up
                // endpoints and is not affected. GET reads stay open.
                .requestMatchers(HttpMethod.POST, "/api/tenant/commerce/**")
                .hasAuthority("PERM_MANAGE_BILLING")
                .requestMatchers(HttpMethod.PUT, "/api/tenant/commerce/**")
                .hasAuthority("PERM_MANAGE_BILLING")
                .requestMatchers(HttpMethod.DELETE, "/api/tenant/commerce/**")
                .hasAuthority("PERM_MANAGE_BILLING")
                .requestMatchers(HttpMethod.POST, "/api/billing/checkout/**")
                .hasAuthority("PERM_MANAGE_BILLING")
                // Agent payment mandates let an agent spend tenant money
                // (caps, payees, approval policy), so granting or
                // revoking one is a billing decision. They fell through
                // to anyRequest(): any member, the widget end-customer
                // role included, could sign one. The preview stays
                // open: it computes a draft and changes nothing.
                .requestMatchers(
                        HttpMethod.POST,
                        "/api/payments/mandates",
                        "/api/payments/mandates/*/revoke")
                .hasAuthority("PERM_MANAGE_BILLING")
                // Academy Fast-Track Scholarship one-click fulfilment
                // (CORE-COMP-GRANT-CONTRACT.md) - a service-to-service call
                // from the Academy webapp, authenticated by a shared
                // X-Service-Token, NOT a Keycloak JWT (the
                // web-admin-proxied
                // path has no end-user token to forward). permitAll here
                // for
                // the same reason as /webhook/** and the device-token
                // endpoints above - the JWT filter can't authenticate it;
                // CompGrantController itself validates the service token
                // against CORE_COMP_SERVICE_TOKEN and fails closed if
                // that's
                // unset. Scoped to POST on the exact path so nothing else
                // under /api/tenant is loosened.
                .requestMatchers(HttpMethod.POST, "/api/tenant/comp-grant")
                .permitAll()
                // App Builder generation routing fix (2026-08-06) - same
                // service-to-service, non-JWT posture as comp-grant just
                // above (see AppBuilderCompletionController's javadoc):
                // app-builder has no end-user token to forward, so
                // X-Service-Token + X-Tenant-Id are the credential,
                // verified
                // entirely inside the controller. Scoped to this one path.
                .requestMatchers(HttpMethod.POST, "/api/internal/app-builder/generate-files")
                .permitAll()
                // App Builder image generation - same service-token
                // posture,
                // gated inside AppBuilderImageController.
                .requestMatchers(HttpMethod.POST, "/api/internal/app-builder/generate-image")
                .permitAll()
                // Master Roadmap 2026 item #22 (GDPR/data-residency basics,
                // §G) - TenantDataController's self-service "Request data
                // export" / "Request account deletion" actions. Same
                // admin-only posture as the upgrade line just above (both
                // resolve "which tenant" from TenantContext, never a path
                // variable); data-deletion is strictly more consequential
                // (irreversible schema drop) so it gets no looser a gate.
                .requestMatchers(HttpMethod.POST, "/api/tenant/data-export")
                .hasAuthority("PERM_MANAGE_DATA_GOVERNANCE")
                .requestMatchers(HttpMethod.POST, "/api/tenant/data-deletion")
                .hasAuthority("PERM_MANAGE_DATA_GOVERNANCE")
                // "How does our infrastructure use your data" transparency
                // view (TenantDataUsageController, core/app) - same
                // tenant-scoped, admin-only posture as the two lines just
                // above; read-only, but still sensitive
                // tenant-account-level
                // surface, not opened up to every authenticated role.
                .requestMatchers(HttpMethod.GET, "/api/tenant/data-usage")
                .hasAuthority("PERM_MANAGE_DATA_GOVERNANCE")
                // DPDP-oriented controls (PrivacyController, core/app): consent policies,
                // retention periods, purpose tags, per-contact export/erase and the breach
                // audit export. Every verb, same data-governance gate as the lines above;
                // erase is irreversible and the audit export reads every event, so no
                // looser tier for the read-only paths either.
                .requestMatchers("/api/tenant/privacy/**")
                .hasAuthority("PERM_MANAGE_DATA_GOVERNANCE")
                // Ecosystem Maintenance console - platform-wide,
                // cross-tenant
                // operator surface (LLM task routing, translation-job
                // reference settings, cross-tenant health/metrics roster).
                // Deliberately its OWN authority, not ROLE_tenant-admin: a
                // tenant-admin only ever governs their own tenant's schema,
                // while everything under /api/platform/** reads/writes
                // control_plane data and iterates every tenant's schema
                // (see
                // TenantSchemaIterator). Nothing provisions this role
                // automatically (contrast TenantProvisioningService
                // granting
                // tenant-admin to a tenant's first signup) - it must be
                // assigned by hand in Keycloak's own admin console to
                // whichever real operator account(s) run this platform. See
                // agentic-ai-realm.json's "platform-admin" role entry.
                .requestMatchers("/api/platform/**")
                .hasAuthority("ROLE_platform-admin")
                // Earned Autonomy Briefs (core/brief) - explicit per verb,
                // never permitAll. Reads (list, get,
                // versions, diff, ledger) and the readiness recompute (a
                // POST that only reads) are open to
                // any authenticated tenant member. Planning, saving,
                // editing, tuning, rehearsing and deleting
                // are agent-building actions: PERM_MANAGE_AGENTS. Apply and
                // rollback additionally create/edit
                // workflows, so they need BOTH PERM_MANAGE_AGENTS and
                // PERM_MANAGE_WORKFLOWS_ADMIN (integration
                // parts of an apply degrade on PERM_MANAGE_INTEGRATIONS
                // instead of failing - see BriefValidator).
                // Trust promotion/demotion is a tenant-admin decision. The
                // trailing catch-all keeps any verb
                // or path not listed here (a future route) at the
                // agent-builder tier rather than open.
                .requestMatchers(HttpMethod.GET, "/api/briefs", "/api/briefs/**")
                .authenticated()
                .requestMatchers(HttpMethod.POST, "/api/briefs/*/readiness")
                .authenticated()
                // A2A REST binding (A2aRestController): message:send/stream, task
                // cancel/subscribe and the push-notification-config verbs. Same
                // posture as the JSON-RPC routes POST /api/a2a and
                // /api/agents/{id}/a2a (allowlisted HANDLER): any authenticated
                // member reaches the route, and the handler enforces the real check -
                // requireAccess RUNNER on the agent, with tasks scoped to the
                // calling subject and tenant (A2aTaskService). Spelled out here so
                // the route is an explicit decision rather than a fall-through.
                .requestMatchers(HttpMethod.POST, "/api/a2a/rest/**", "/api/agents/*/a2a/rest/**")
                .authenticated()
                .requestMatchers(HttpMethod.DELETE, "/api/a2a/rest/**", "/api/agents/*/a2a/rest/**")
                .authenticated()
                .requestMatchers(HttpMethod.POST, "/api/briefs/*/trust/**")
                .hasAuthority("ROLE_tenant-admin")
                .requestMatchers(
                        HttpMethod.POST, "/api/briefs/*/apply", "/api/briefs/*/versions/*/rollback")
                .access(
                        AuthorizationManagers.allOf(
                                AuthorityAuthorizationManager
                                        .<RequestAuthorizationContext>hasAuthority(
                                                "PERM_MANAGE_AGENTS"),
                                AuthorityAuthorizationManager
                                        .<RequestAuthorizationContext>hasAuthority(
                                                "PERM_MANAGE_WORKFLOWS_ADMIN")))
                // Tune and rehearse are named explicitly (contract section
                // 2); DELETE and PUT fall to the catch-all.
                .requestMatchers(
                        HttpMethod.POST,
                        "/api/briefs/*/tune",
                        "/api/briefs/*/rehearse",
                        "/api/briefs/*/experiments")
                .hasAuthority("PERM_MANAGE_AGENTS")
                .requestMatchers("/api/briefs", "/api/briefs/**")
                .hasAuthority("PERM_MANAGE_AGENTS")
                // Agent trust, commerce and memory surfaces (sync 4).
                // Explicit rather than falling through to
                // anyRequest() so each is a recorded decision
                // (AuthorizationCoverageTest):
                // - Wallet creation/top-up, x402 seller offers and
                // agent-directory publishing configure how an
                //   agent holds money or is exposed outside the tenant:
                // tenant permission PERM_MANAGE_AGENTS.
                // - Delegation grants/tokens and commerce mandates are
                // bound to the JWT SUBJECT, never a
                //   request field (DelegationController /
                // CommerceMandateController read it from
                //   TenantContext; CommerceMandateService refuses anyone
                // but the buyer with NOT_THE_BUYER), so
                //   any signed-in member acts only as themselves.
                // /delegations/introspect verifies the
                //   presented token itself.
                // - /api/memory/** is the caller's own memory (the service
                // scopes every read/erase to the
                //   subject); POST /api/research/runs and its cancel act on
                // the caller's own runs (daily
                //   allowance + plan ceiling enforced in
                // ResearchRunController).
                .requestMatchers(HttpMethod.POST, "/api/wallets", "/api/wallets/*/credit")
                .hasAuthority("PERM_MANAGE_AGENTS")
                .requestMatchers(
                        HttpMethod.POST,
                        "/api/agents/*/x402/offers",
                        "/api/agents/*/x402/offers/*/disable")
                .hasAuthority("PERM_MANAGE_AGENTS")
                .requestMatchers(
                        HttpMethod.POST,
                        "/api/agents/*/directory/publish",
                        "/api/agents/*/directory/unpublish")
                .hasAuthority("PERM_MANAGE_AGENTS")
                .requestMatchers(
                        HttpMethod.POST,
                        "/api/agents/*/delegations",
                        "/api/agents/*/delegations/*/revoke",
                        "/api/agents/*/delegations/*/tokens",
                        "/api/agents/delegations/introspect")
                .authenticated()
                .requestMatchers(HttpMethod.POST, "/api/commerce/mandates/**")
                .authenticated()
                .requestMatchers(HttpMethod.POST, "/api/memory/facts")
                .authenticated()
                .requestMatchers(HttpMethod.PUT, "/api/memory/blocks/*")
                .authenticated()
                .requestMatchers(HttpMethod.PATCH, "/api/memory/facts/*/*")
                .authenticated()
                .requestMatchers(
                        HttpMethod.DELETE,
                        "/api/memory",
                        "/api/memory/blocks/*",
                        "/api/memory/facts/*/*")
                .authenticated()
                .requestMatchers(
                        HttpMethod.POST, "/api/research/runs", "/api/research/runs/*/cancel")
                .authenticated()
                // Store-compliance / self-service surfaces (2026-09 sync).
                // Explicit rather than falling through
                // to anyRequest() so each is a recorded decision
                // (AuthorizationCoverageTest):
                // - DELETE /api/account: any signed-in user deletes their
                // OWN account. Identity comes only
                //   from the token (never the body);
                // AccountSelfDeletionService refuses platform admins and
                //   requires the literal "DELETE" confirmation.
                // Deliberately no tenant permission: an app-store
                //   rule requires the account holder to be able to do this
                // without extra grants.
                // - POST /api/ai-reports: any signed-in user reports AI
                // output (30/hour/user cap in the
                //   service); reading and triaging reports is
                // /api/platform/** above.
                // - /api/mobile/sentinels: events are the caller's own
                // (service scopes every read/erase to the
                //   subject); the tenant-wide sync policy and the aggregate
                // summary are admin-only, mirroring
                //   MobileSentinelService#requireAdmin.
                // - POST /api/assist/code-edit: the build studio's code
                // editor, which any member may use
                //   (plain LLM completion for the caller's tenant, metered
                // by the tenant's plan/quota).
                .requestMatchers(HttpMethod.DELETE, "/api/account")
                .authenticated()
                .requestMatchers(HttpMethod.POST, "/api/ai-reports")
                .authenticated()
                .requestMatchers(HttpMethod.PUT, "/api/mobile/sentinels/policy")
                .hasAnyAuthority("ROLE_tenant-admin", "ROLE_platform-admin")
                .requestMatchers(HttpMethod.GET, "/api/mobile/sentinels/admin/summary")
                .hasAnyAuthority("ROLE_tenant-admin", "ROLE_platform-admin")
                .requestMatchers(HttpMethod.POST, "/api/mobile/sentinels/events")
                .authenticated()
                .requestMatchers(HttpMethod.DELETE, "/api/mobile/sentinels/events")
                .authenticated()
                .requestMatchers(HttpMethod.POST, "/api/assist/code-edit")
                .authenticated()
                // Sync 4 surfaces, explicit rather than falling through to
                // anyRequest() (AuthorizationCoverageTest).
                // Agent-building / shared configuration - the permission of
                // the nearest sibling rule:
                // - Agent and workflow import (+ the parse-only previews,
                // same untrusted-file parser) build a
                //   draft from a third-party export: PERM_MANAGE_AGENTS /
                // PERM_MANAGE_WORKFLOWS_ADMIN.
                // - Judge-calibration sets and trajectory-eval expectations
                // are agent quality configuration
                //   shared by everyone who grades the agent; neither
                // service checks agent access (see
                //   EvalDatasetService's access-posture note), so the
                // tenant permission is the gate.
                // - Skill supply-chain approve/previews mirror
                // SkillSupplyChainController#requireWorkspaceAdmin.
                .requestMatchers(
                        HttpMethod.POST, "/api/agents/import", "/api/agents/import/preview")
                .hasAuthority("PERM_MANAGE_AGENTS")
                // Creating an agent, and the Ask-AI drafting the create flow
                // uses: agent builders, plus delegated-user, whose role is
                // setting agents up for someone else. Without
                // PERM_MANAGE_AGENTS a delegated user edits only agents it
                // owns or was granted (AgentService#requireAccess). Importing
                // an agent bundle creates an agent through that same
                // AgentService.create, so it takes the same gate; its preview
                // is the first step of that flow and reads nothing, so it
                // matches the import rather than opening a looser door.
                .requestMatchers(
                        HttpMethod.POST,
                        "/api/agents",
                        "/api/agents/generate",
                        "/api/assist/draft",
                        "/api/agent-bundles/import",
                        "/api/agent-bundles/preview")
                .hasAnyAuthority("PERM_MANAGE_AGENTS", "ROLE_delegated-user")
                // Builder-only creates: a duplicate needs only VIEWER on the
                // source yet copies its tool and knowledge scope; a template
                // import may also create workflows; simulation personas are a
                // tenant-wide catalog whatever agent the path names.
                .requestMatchers(
                        HttpMethod.POST,
                        "/api/agents/*/duplicate",
                        "/api/templates/*/import",
                        "/api/agents/*/quality/simulations/personas")
                .hasAuthority("PERM_MANAGE_AGENTS")
                // Sharing a template snapshots an agent or workflow's
                // configuration (prompt included) for the cross-tenant
                // gallery; a VIEWER grant on the source is not enough.
                .requestMatchers(HttpMethod.POST, "/api/templates", "/api/templates/*/submit")
                .hasAuthority("PERM_MANAGE_TEMPLATES")
                // Workspace skills: records SkillController#requireWorkspaceAdmin.
                .requestMatchers(
                        HttpMethod.POST,
                        "/api/skills",
                        "/api/skills/import",
                        "/api/skills/registry/install",
                        "/api/skills/*/improvement/apply",
                        "/api/skills/*/approve",
                        "/api/skills/*/reject",
                        "/api/skills/*/archive",
                        "/api/skills/*/agents",
                        "/api/agents/*/skills/distill")
                .hasAnyAuthority("PERM_MANAGE_AGENTS", "ROLE_platform-admin")
                .requestMatchers(HttpMethod.PUT, "/api/skills/*")
                .hasAnyAuthority("PERM_MANAGE_AGENTS", "ROLE_platform-admin")
                .requestMatchers(HttpMethod.PATCH, "/api/skills/*/enabled")
                .hasAnyAuthority("PERM_MANAGE_AGENTS", "ROLE_platform-admin")
                .requestMatchers(HttpMethod.DELETE, "/api/skills/*", "/api/skills/*/improvement")
                .hasAnyAuthority("PERM_MANAGE_AGENTS", "ROLE_platform-admin")
                .requestMatchers(
                        HttpMethod.POST, "/api/workflows/import", "/api/workflows/import/preview")
                .hasAuthority("PERM_MANAGE_WORKFLOWS_ADMIN")
                .requestMatchers(
                        HttpMethod.POST,
                        "/api/agents/*/quality/judge-calibration-sets",
                        "/api/agents/*/quality/judge-calibration-sets/*/items")
                .hasAuthority("PERM_MANAGE_AGENTS")
                .requestMatchers(HttpMethod.PUT, "/api/agents/*/quality/judge-calibration-sets/*")
                .hasAuthority("PERM_MANAGE_AGENTS")
                .requestMatchers(
                        HttpMethod.DELETE,
                        "/api/agents/*/quality/judge-calibration-sets/*",
                        "/api/agents/*/quality/judge-calibration-sets/*/items/*")
                .hasAuthority("PERM_MANAGE_AGENTS")
                .requestMatchers(
                        HttpMethod.PUT, "/api/agents/*/quality/trajectory-eval/datasets/*/cases/*")
                .hasAuthority("PERM_MANAGE_AGENTS")
                .requestMatchers(
                        HttpMethod.POST,
                        "/api/skill-security/skills/*/approve",
                        "/api/skill-security/preview/draft",
                        "/api/skill-security/preview/archive",
                        "/api/skill-security/preview/registry")
                .hasAnyAuthority("PERM_MANAGE_AGENTS", "ROLE_platform-admin")
                // The caller's OWN records - any signed-in member, because
                // every read/write is scoped to the
                // JWT subject (or to agent access) in the class named on
                // each line; someone else's record is a 404.
                .requestMatchers(HttpMethod.POST, "/api/scheduled-tasks", "/api/scheduled-tasks/**")
                .authenticated() // ScheduledTaskService#requireVisible/#requireOwned (owner
                // subject; PERM_MANAGE_AGENTS may pause/run/delete any); create
                // needs RUNNER on the agent
                .requestMatchers(HttpMethod.PUT, "/api/scheduled-tasks/*")
                .authenticated() // ScheduledTaskService#update ->
                // #requireOwned (owner only)
                .requestMatchers(HttpMethod.DELETE, "/api/scheduled-tasks/*")
                .authenticated() // ScheduledTaskService#delete ->
                // #requireVisible
                .requestMatchers(
                        HttpMethod.POST,
                        "/api/chat-import",
                        "/api/chat-import/preview",
                        "/api/chat-import/memory-candidates")
                .authenticated() // ChatImportService: owner =
                // Principal#getName
                // (ChatImportController#subject);
                // ImportedChatRepository#findByIdAndOwnerSubject
                .requestMatchers(HttpMethod.DELETE, "/api/chat-import/chats/*")
                .authenticated() // ChatImportService#delete -> #find
                // (findByIdAndOwnerSubject, 404 otherwise)
                .requestMatchers(
                        HttpMethod.POST, "/api/memory/import", "/api/memory/import/preview")
                .authenticated() // MemoryImportService#importFacts writes
                // through UserMemoryController#addFact as
                // the caller, same as POST
                // /api/memory/facts above
                .requestMatchers(
                        HttpMethod.POST,
                        "/api/browser/profiles",
                        "/api/browser/profiles/*/clear-state")
                .authenticated() // BrowserProfileService#create/#requireManageable: USER profiles
                // owner-only, TENANT profiles need PERM_MANAGE_AGENTS
                .requestMatchers(HttpMethod.PATCH, "/api/browser/profiles/*")
                .authenticated() // BrowserProfileService#update ->
                // #requireManageable
                .requestMatchers(HttpMethod.PUT, "/api/browser/profiles/*/agents/*")
                .authenticated() // BrowserProfileService#attach:
                // #requireManageable + EDITOR on the agent
                // + USER profile only on the owner's own
                // agent
                .requestMatchers(
                        HttpMethod.DELETE,
                        "/api/browser/profiles/*",
                        "/api/browser/profiles/*/agents/*")
                .authenticated() // BrowserProfileService#delete/#detach ->
                // #requireManageable
                .requestMatchers(
                        HttpMethod.POST, "/api/browser/recipes", "/api/browser/recipes/from-run")
                .authenticated() // BrowserRecipeService#requireManage:
                // EDITOR on the recipe's agent,
                // tenant-wide recipes need
                // PERM_MANAGE_AGENTS
                .requestMatchers(HttpMethod.PATCH, "/api/browser/recipes/*")
                .authenticated() // BrowserRecipeService#update ->
                // #requireVisible + #requireManage
                .requestMatchers(HttpMethod.DELETE, "/api/browser/recipes/*")
                .authenticated() // BrowserRecipeService#delete ->
                // #requireVisible + #requireManage
                .requestMatchers(
                        HttpMethod.POST,
                        "/api/browser/takeover/pause",
                        "/api/browser/takeover/*/begin",
                        "/api/browser/takeover/*/cancel",
                        "/api/browser/takeover/*/hand-back",
                        "/api/browser/takeover/*/input",
                        "/api/browser/takeover/*/token")
                .authenticated() // BrowserTakeoverService#ownedById/#ownedByKey
                // (BrowserTakeoverControl#isOwnedBy: tenant AND user, 404
                // otherwise)
                .requestMatchers(
                        HttpMethod.POST,
                        "/api/agents/*/sessions/*/messages/*/edit",
                        "/api/agents/*/sessions/*/messages/*/regenerate")
                .authenticated() // ChatBranchService#openForChange: RUNNER
                // on the agent + tree owner /
                // SessionMemoryService#isOwnedBy
                .requestMatchers(HttpMethod.PUT, "/api/agents/*/sessions/*/messages/active-leaf")
                .authenticated() // ChatBranchService#switchActiveLeaf ->
                // #openForChange
                .requestMatchers(HttpMethod.POST, "/api/coding-tasks")
                .authenticated() // CodingTaskService#start: RUNNER on the
                // agent, requester = JWT subject
                .requestMatchers(
                        HttpMethod.POST,
                        "/api/coding-tasks/*/approve",
                        "/api/coding-tasks/*/reject",
                        "/api/coding-tasks/*/cancel",
                        "/api/coding-tasks/*/rollback")
                .authenticated() // CodingTaskService#lookup (requestedBy
                // must equal the caller, 404 otherwise)
                .requestMatchers(HttpMethod.POST, "/api/completions/agent-turn")
                .authenticated(); // stateless inference like the allowlisted
        // /api/completions/fim; per-user budget in
        // AgentTurnService
    }
}
