package com.agenticai.core.security;

import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AuthorizeHttpRequestsConfigurer;

/**
 * Public and unauthenticated surfaces, in chain order: CORS preflight, actuator probes, public
 * status and landing endpoints, inbound webhooks, OAuth callbacks, device ingest and control,
 * signup and auth, the demo, share links, public studio apps and owner-side studio-app writes,
 * templates and plugins, commerce, {@code .well-known}, the agent directory and x402.
 *
 * <p>One contiguous run of the main chain's authorization rules, moved out of {@link
 * SecurityConfig} so that file stays reviewable. Spring Security applies the FIRST matching rule,
 * so order is significant: {@code SecurityConfig#filterChain} calls the rule classes in a fixed
 * order, and the rules inside each class keep their original order. {@code
 * SecurityRuleSnapshotTest} pins the whole chain (order, matchers, decisions).
 */
final class PublicSurfaceRules {

    private PublicSurfaceRules() {}

    static void apply(
            AuthorizeHttpRequestsConfigurer<HttpSecurity>.AuthorizationManagerRequestMatcherRegistry
                    authorize) {
        authorize
                .requestMatchers(HttpMethod.OPTIONS, "/**")
                .permitAll() // CORS preflight never carries a token
                // "/actuator/health/**" (not just the bare
                // "/actuator/health")
                // so the liveness/readiness probe groups Docker Compose's
                // healthcheck and any future Kubernetes probe hit directly
                // -
                // /actuator/health/liveness and /actuator/health/readiness
                // -
                // are reachable without a JWT too. See P1 of
                // Platform-Tech-Stack-Audit-2026.md (infra/ops section) and
                // application.yml's management.endpoint.health.probes
                // block.
                .requestMatchers("/actuator/health/**", "/actuator/info")
                .permitAll()
                // Bare liveness for the web app's logged-out probe
                // (SystemStatusController#live - answers {"status":"UP"}
                // and
                // nothing else). Public pages poll it so "is core up" never
                // needs an authenticated endpoint that 401s into the
                // browser
                // console. GET only; every other /api/system/status route
                // still requires a JWT.
                .requestMatchers(HttpMethod.GET, "/api/system/status/live")
                .permitAll()
                // Component-level public status for the /status page
                // (PublicStatusController) - the public-facing twin of
                // /api/system/status. Answers only component names + status
                // codes (UP/DOWN/DEGRADED/UNKNOWN/NOT_CONFIGURED): no
                // versions, pool internals, JVM counters or tenant data, so
                // it is safe to expose unauthenticated. GET only.
                .requestMatchers(HttpMethod.GET, "/api/public/status")
                .permitAll()
                // Landing-page funnel beacon (LandingEventController) -
                // fired
                // by anonymous visitors on the public landing page, so a
                // JWT
                // is impossible by definition. Whitelisted event names +
                // per-IP hourly lid + always-204 are enforced in the
                // controller itself; counters only, no PII. POST only; the
                // read side lives under /api/platform/** (gated below).
                .requestMatchers(HttpMethod.POST, "/api/public/landing-event")
                .permitAll()
                // Prometheus scrape endpoint
                // (micrometer-registry-prometheus,
                // see application.yml's management.endpoints exposure + the
                // `core` job in deploy/prometheus/prometheus.yml).
                // Permitted
                // unauthenticated because a Prometheus server scrapes with
                // no
                // JWT; without this it returns 401 and the scrape target
                // stays down. Security audit (2026-07-27): tried moving
                // this
                // off 8080 entirely via management.server.port - reverted,
                // see application.yml's management block for why. Public
                // unreachability is instead enforced at deploy/caddy/
                // Caddyfile, which blocks /actuator/* on the
                // api.staging.example.com
                // route; Prometheus itself reaches this path over the
                // internal compose network (core:8080), which Caddy never
                // sees at all.
                .requestMatchers("/actuator/prometheus")
                .permitAll()
                // Spring Boot's start-up timeline (bean names and timings).
                // The endpoint only exists when
                // app.startup.profiling.enabled=true AND an operator adds
                // "startup" to
                // management.endpoints.web.exposure.include, so this line
                // is inert by default; when it
                // is live it is platform-admin only
                // (ActuatorEdgeGuardFilter also 404s it behind any proxy).
                .requestMatchers("/actuator/startup")
                .hasAuthority("ROLE_platform-admin")
                // Channel webhooks (Omnichannel Stage 2+) authenticate via
                // the channel provider's own request-signature validation
                // (see ChannelConfigurationException), not a user JWT - a
                // WhatsApp/Telegram server can't present a Keycloak token.
                .requestMatchers("/webhook/**")
                .permitAll()
                // Payment gateway webhooks (BillingWebhookController, A1 -
                // 2026-08-03) - same posture as /webhook/** above:
                // Razorpay's
                // servers send X-Razorpay-Signature (HMAC of the raw body
                // with
                // the webhook secret), never a Keycloak Bearer token, so
                // this
                // is unauthenticated at the Spring Security layer BY DESIGN
                // -
                // BillingWebhookController calls PaymentGatewayClient#
                // verifyWebhookSignature before doing anything with the
                // body
                // and 401s a bad/missing signature itself. Unlike the
                // Alertmanager webhook (see alertWebhookFilterChain's
                // javadoc),
                // Razorpay never sends an Authorization header, so there is
                // no
                // BearerTokenAuthenticationFilter trap here - a plain
                // permitAll() in the main chain is sufficient.
                .requestMatchers("/api/billing/webhook/**")
                .permitAll()
                // core/mcpserver's INBOUND MCP server surface (/mcp/**,
                // /api/dev/**) moved to its own mcpServerFilterChain below,
                // same BearerTokenAuthenticationFilter trap as
                // alertWebhookFilterChain's javadoc - see that bean and
                // mcpServerFilterChain's own javadoc. permitAll() here
                // alone
                // was NOT sufficient (confirmed live 2026-08-15: a real
                // vmcp_... grant key 401'd with "Malformed token" before
                // McpApiKeyAuthFilter ever ran) for exactly the same reason
                // it wasn't sufficient for /webhook/alerts/** - this main
                // chain still runs oauth2ResourceServer().
                // Google OAuth's own redirect back to this platform after
                // consent (GoogleOAuthController#callback) - like any
                // third-party OAuth redirect, this request carries no JWT
                // at
                // all; tenant identity instead travels through the signed
                // `state` value (see GoogleOAuthService's javadoc). Not
                // under /webhook/** since it's not a third-party server
                // calling in on its own schedule, but the trust posture is
                // the same: authenticate the payload/state, not a bearer
                // token.
                .requestMatchers(HttpMethod.GET, "/oauth/google/callback")
                .permitAll()
                // X's own redirect back to this platform after consent
                // (XOAuthController#callback) - identical trust posture and
                // reasoning to the Google callback line above.
                .requestMatchers(HttpMethod.GET, "/oauth/x/callback")
                .permitAll()
                // Atlassian's own redirect back to this platform after
                // consent
                // (AtlassianOAuthController#callback) - identical trust
                // posture
                // and reasoning to the Google/X callbacks above, just never
                // added when that controller was built. Confirmed live
                // 2026-08-15: Atlassian's redirect (carrying ?code=&state=,
                // no JWT) 401'd here before the controller ever ran - the
                // OAuth consent screen itself had already succeeded.
                .requestMatchers(HttpMethod.GET, "/oauth/atlassian/callback")
                .permitAll()
                // Provider-registry OAuth
                // (OAuthProviderController#callback: Google, GitHub, Slack,
                // Notion, Linear, HubSpot, Microsoft, Asana, Zoom) -
                // identical trust posture to the
                // Atlassian callback above: the redirect carries no JWT,
                // tenant + user travel in the
                // encrypted, expiring, single-use state, and the whole
                // feature is OFF by default
                // (app.mcp.oauth.providers-enabled).
                .requestMatchers(HttpMethod.GET, "/oauth/mcp/*/callback")
                .permitAll()
                // Generic remote-MCP OAuth (McpOAuthController): the
                // platform's OAuth Client ID Metadata Document
                // (app.mcp.oauth.cimd.path, always under
                // /oauth/mcp/remote/) is fetched by the AUTHORIZATION
                // SERVER, which has no JWT. Public by design (a client_id
                // metadata document must be readable by
                // anyone); it 404s unless app.mcp.oauth.enabled AND
                // app.mcp.oauth.cimd.enabled. The callback of
                // that flow is already covered by the line above.
                .requestMatchers(HttpMethod.GET, "/oauth/mcp/remote/*")
                .permitAll()
                // Mobile SDK (#157) - a paired phone has no Keycloak
                // session,
                // so these three authenticate via possession of a bearer
                // credential instead (a pairing code for claim, an
                // X-Device-Token header for the other two), validated
                // inside
                // DeviceGatewayController itself - identical structural
                // posture to /webhook/** above, just under /api/devices
                // rather than /webhook because these aren't triggered by a
                // third-party webhook call, they're the Mobile SDK app's
                // own
                // API surface.
                .requestMatchers(HttpMethod.POST, "/api/devices/claim")
                .permitAll()
                .requestMatchers(HttpMethod.POST, "/api/devices/poll")
                .permitAll()
                .requestMatchers(HttpMethod.POST, "/api/devices/commands/*/result")
                .permitAll()
                // #157 follow-up ("send all data into agentic platform") -
                // DeviceDataIngestionController's push endpoints, same
                // device-token trust posture as the three lines above (see
                // its own javadoc); note /api/devices/register is
                // deliberately NOT listed here - that one goes through the
                // Mobile SDK's Keycloak login instead, so it falls through
                // to anyRequest().authenticated() below like the rest of
                // DeviceController.
                .requestMatchers(HttpMethod.POST, "/api/devices/screen-state")
                .permitAll()
                .requestMatchers(HttpMethod.POST, "/api/devices/notifications")
                .permitAll()
                .requestMatchers(HttpMethod.POST, "/api/devices/app-usage")
                .permitAll()
                .requestMatchers(HttpMethod.POST, "/api/devices/sms")
                .permitAll()
                // Owner-side device control: queueing a command on, or
                // revoking, a paired phone acts on someone's device, and
                // devices record no owner yet. Channel admins only.
                .requestMatchers(HttpMethod.POST, "/api/devices/*/commands")
                .hasAuthority("PERM_MANAGE_CHANNELS")
                .requestMatchers(HttpMethod.DELETE, "/api/devices/*")
                .hasAuthority("PERM_MANAGE_CHANNELS")
                // Enrolling a phone (a pairing code, or the Mobile SDK's
                // signed-in register) adds a tenant-wide device that pushes
                // SMS and notifications into the tenant: the same channel
                // admins, until devices record an owner.
                .requestMatchers(
                        HttpMethod.POST, "/api/devices/pairing-code", "/api/devices/register")
                .hasAuthority("PERM_MANAGE_CHANNELS")
                // Phase 0 Stage 5 - nobody has a token yet at signup time
                // by
                // definition; TenantSignupController's own
                // SignupRateLimiter
                // is the abuse control here instead of a JWT requirement.
                .requestMatchers(HttpMethod.POST, "/api/signup")
                .permitAll()
                // Infra audit (2026-07-27) - SocialAuthConfigController's
                // "which social login buttons should render" lookup. Same
                // "nobody has a token yet" reasoning as /api/signup above:
                // this is read by the signup page before anyone has
                // authenticated, and it returns nothing sensitive (just
                // which of google/github/microsoft are enabled) - no abuse
                // control needed beyond the controller's own short-TTL
                // cache,
                // which already bounds how often it actually calls
                // Keycloak.
                .requestMatchers(HttpMethod.GET, "/api/auth/social-providers")
                .permitAll()
                // "Try it without registering" demo sandbox
                // (core/tenancy/demo
                // + core/agents/demo). A demo visitor has no Keycloak
                // account
                // BY DEFINITION - that is the entire feature - so, exactly
                // like /api/signup above and the device-token endpoints
                // further up, Spring Security cannot be the thing
                // authenticating these. Identity is instead an opaque
                // 256-bit
                // random bearer token in the X-Demo-Token header, minted by
                // POST /api/demo/session and validated inside
                // DemoSessionController/DemoChatController (hash lookup
                // against control_plane.demo_session, migration V126).
                //
                // Scoped tightly to /api/demo/** and nothing else: the demo
                // token is deliberately useless everywhere in this file, so
                // a
                // leaked one buys an attacker one throwaway sandbox with a
                // message cap, not tenant access. Abuse controls are
                // DemoSessionRateLimiter (per-IP creation rate,
                // Redis-backed,
                // same shape as SignupRateLimiter), a per-IP live-sandbox
                // cap,
                // a per-sandbox message cap, and the FREE-tier daily/dollar
                // limits every demo tenant is already subject to.
                //
                // NOTE for whoever adds the next endpoint under /api/demo:
                // this rule is a path prefix, so anything you put there is
                // unauthenticated. Keep that surface to things that are
                // safe
                // for an anonymous internet caller to reach.
                .requestMatchers("/api/demo/**")
                .permitAll()
                // Agent sharing to the external world - a registered
                // tenant's
                // OWN agent (not the demo sandbox above), shared to
                // anonymous
                // viewers by an unguessable share id, same
                // capability-handle
                // posture as /api/demo/share/** but for real agents.
                // Identity
                // here is the share id itself, resolved cross-tenant via
                // TenantResolutionService/ChannelAccountLink (see
                // AgentShareController) - Spring Security cannot
                // authenticate
                // this caller any more than it can a demo viewer. Scoped
                // tightly to /api/share/agents/** for the same reason
                // /api/demo/** is scoped: a leaked share id buys an
                // attacker
                // one agent's chat surface, rate-limited, revocable, and
                // metered against the OWNING tenant's own plan/budget -
                // never
                // broader tenant access. The owner-side mint/revoke
                // endpoints
                // live under /api/agents/**, authenticated normally (no
                // separate rule needed - default authenticated() below).
                .requestMatchers("/api/share/agents/**")
                .permitAll()
                // Workflow Share - the same anonymous-public-link pattern
                // as
                // /api/share/agents/** above, for a registered tenant's own
                // workflow (see
                // WorkflowShareController/WorkflowShareService,
                // core/workflow). Owner-side mint/revoke lives under
                // /api/workflows/**, authenticated normally, same split.
                .requestMatchers("/api/share/workflows/**")
                .permitAll()
                // Published Phone-App-Studio apps (core/app
                // common/studioapp,
                // StudioAppPublicController) - the public runtime a
                // generated
                // app's share link hits: manifest, end-user signup/login,
                // wired agent/workflow calls, hosted-AI turns. Same
                // capability-handle posture as /api/share/** directly
                // above:
                // identity is the unguessable app slug resolved
                // cross-tenant
                // via TenantResolutionService, every dispatch runs inside
                // the
                // OWNING tenant's own plan/budget, per-IP rate limits apply
                // (StudioAppRateLimiter), and end-user auth - when the
                // owner
                // enabled it - is the app's own app-scoped HS256 token
                // checked
                // inside the controller, NOT this chain. Owner-side
                // publish/
                // manage endpoints live under /api/studio-apps,
                // authenticated
                // normally. Scoped tightly to /api/public/apps/**: a leaked
                // slug buys an attacker one app's call surface,
                // rate-limited
                // and revocable (disable/delete severs the slug's tenant
                // resolution), never broader tenant access.
                .requestMatchers("/api/public/apps/**")
                .permitAll()
                // Owner-side studio-app writes (publish, enable/disable,
                // delete, domains, app end users) change or take down a
                // public page that spends the tenant's budget; the
                // service checked only that the app exists. Reads stay
                // open to members.
                .requestMatchers(
                        HttpMethod.POST, "/api/studio-apps/publish", "/api/studio-apps/*/**")
                .hasAuthority("PERM_MANAGE_AGENTS")
                .requestMatchers(HttpMethod.DELETE, "/api/studio-apps/*", "/api/studio-apps/*/**")
                .hasAuthority("PERM_MANAGE_AGENTS")
                // Email unsubscribe (bulk_email node's List-Unsubscribe
                // link,
                // EmailUnsubscribeController). A recipient has no session;
                // the
                // HMAC token in ?t= (tenant + address, verified by
                // EmailUnsubscribeTokenService) is the sole credential. GET
                // renders a confirm button only; POST suppresses the
                // address.
                .requestMatchers("/api/public/email/unsubscribe")
                .permitAll()
                // Hosted workflow form (WorkflowFormController,
                // core/workflow) - the public page of a Trigger node
                // whose triggerType is "form". Same posture as the workflow
                // webhook trigger under /webhook/**
                // above: no JWT, the opaque per-node token in the path is
                // the credential, and the controller
                // answers a uniform 404 for anything it does not recognise,
                // rate-limits per token and per IP, and
                // is a no-op unless app.workflow.form-trigger.enabled. Only
                // GET (render) and POST (submit) on
                // exactly /{token} - nothing else under the prefix is
                // opened.
                .requestMatchers(HttpMethod.GET, "/api/public/workflow-forms/*")
                .permitAll()
                .requestMatchers(HttpMethod.POST, "/api/public/workflow-forms/*")
                .permitAll()
                // White-label brand logo (TenantBrandingLogoController) -
                // the
                // <img src> the agent/workflow share pages and the
                // embeddable
                // widget render. Those pages are anonymous by construction,
                // so
                // the image they reference cannot require a token any more
                // than the share meta above can. GET only: the write half
                // of
                // that controller lives at /api/tenant/branding/** and is
                // pinned to tenant-admin below, deliberately NOT under this
                // prefix. What a caller can get here is bounded by the
                // controller itself, which on every request re-checks the
                // owning tenant's white_label entitlement and that the
                // tenant
                // still publishes exactly this URL as its brand mark - so a
                // guessed tenant id yields a 404, and a plan downgrade or a
                // cleared brand stops serving immediately.
                .requestMatchers(HttpMethod.GET, "/api/share/branding/**")
                .permitAll()
                // Community template gallery (core/templates) - the PUBLIC,
                // crawlable catalogue of APPROVED templates. GET only, and
                // scoped tightly to /api/templates/gallery/** so nothing
                // else
                // under /api/templates
                // (create/edit/submit/withdraw/import/mine)
                // is exposed - those fall through to authenticated() below.
                // The
                // "APPROVED only" guarantee is enforced in the service, not
                // by
                // this matcher; a leaked/guessed id for a non-approved
                // template
                // 404s (never 403), same anti-enumeration posture as the
                // rest
                // of this feature. Admin moderation lives under
                // /api/admin/templates/** and is covered by the
                // /api/admin/**
                // matcher just below.
                .requestMatchers(
                        HttpMethod.GET, "/api/templates/gallery", "/api/templates/gallery/**")
                .permitAll()
                // First-party plugin marketplace (core/app common/plugins)
                // - public
                // catalog + artifact downloads, same
                // read-only/no-tenant-data posture
                // as the template gallery above; upload stays under
                // /api/platform/**.
                .requestMatchers(
                        HttpMethod.GET, "/api/plugins/catalog", "/api/plugins/artifacts/**")
                .permitAll()
                // ACP-shaped public product feed (core/billing commerce) -
                // the
                // crawlable catalog shopping agents read. GET serves
                // published
                // rows only; the POST is the public cart-checkout writer.
                // It is
                // the ONE public checkout-session writer - scoped to the
                // feed
                // path, server-side-priced (a cart names product+quantity,
                // never an amount), and capped per tenant per day
                // (app.commerce.feed.max-checkouts-per-day). Member catalog
                // management under /api/tenant/commerce/** stays
                // authenticated
                // via the fall-through below.
                .requestMatchers("/api/commerce/feed/**")
                .permitAll()
                // A2A (Agent2Agent) discovery - the platform Agent Card at
                // the
                // canonical well-known path plus its pre-0.3 legacy alias
                // (core/agents a2a/A2aAgentCardController). Discovery
                // necessarily precedes authentication, and the platform
                // card
                // carries no tenant data (skills is deliberately empty -
                // see
                // A2aAgentCardService's javadoc). GET only. The per-agent
                // card
                // (/api/agents/{id}/a2a/agent-card.json) and the JSON-RPC
                // endpoints (/api/a2a, /api/agents/{id}/a2a) are NOT listed
                // here: they fall through to anyRequest().authenticated()
                // below, which is exactly the bearer-JWT scheme both cards
                // advertise. A bearer JWT on the well-known GET still
                // passes
                // through oauth2ResourceServer() unharmed (it IS a JWT), so
                // no
                // dedicated chain like mcpServerFilterChain is needed.
                .requestMatchers(
                        HttpMethod.GET, "/.well-known/agent-card.json", "/.well-known/agent.json")
                .permitAll()
                // JWKS for A2A card signatures - public by definition: a
                // verifier resolves a signature's kid against the same
                // origin
                // that served the card. Public keys only; see
                // A2aCardSigningService/A2aAgentCardController#jwks.
                .requestMatchers(HttpMethod.GET, "/.well-known/jwks.json")
                .permitAll()
                // Public agent directory (Know-Your-Agent discovery): every
                // field served was explicitly published by the owning
                // tenant
                // (signed snapshots, not live tenant data) - discovery
                // necessarily precedes authentication.
                .requestMatchers(HttpMethod.GET, "/api/directory/agents", "/api/directory/agents/*")
                .permitAll()
                // x402 pay-per-call surface: the endpoint IS the credential
                // check - the X-PAYMENT header authorizes the request;
                // without
                // it the caller gets a 402 requirements body, never data.
                // POST is included for paid POST resources.
                .requestMatchers("/x402/*")
                .permitAll();
    }
}
