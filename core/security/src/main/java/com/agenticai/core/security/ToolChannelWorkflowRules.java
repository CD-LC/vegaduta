package com.agenticai.core.security;

import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AuthorizeHttpRequestsConfigurer;

/**
 * Tool, knowledge, channel and workflow rules, in chain order: custom tools, knowledge, channel
 * credentials, routing and pairing, ElevenLabs, workflows, agent schedules and triggers, and leads.
 *
 * <p>One contiguous run of the main chain's authorization rules, moved out of {@link
 * SecurityConfig} so that file stays reviewable. Spring Security applies the FIRST matching rule,
 * so order is significant: {@code SecurityConfig#filterChain} calls the rule classes in a fixed
 * order, and the rules inside each class keep their original order. {@code
 * SecurityRuleSnapshotTest} pins the whole chain (order, matchers, decisions).
 */
final class ToolChannelWorkflowRules {

    private ToolChannelWorkflowRules() {}

    static void apply(
            AuthorizeHttpRequestsConfigurer<HttpSecurity>.AuthorizationManagerRequestMatcherRegistry
                    authorize) {
        authorize
                // Custom Code Tools (CustomCodeToolController) -
                // tenant-authored
                // Java/Python programs that the tool-sandbox microservice
                // compiles
                // and runs, and that every agent in the workspace is then
                // offered.
                // Writes are gated to tenant-admin DELIBERATELY, which is
                // stricter
                // than the sibling /api/tools/scripts surface directly
                // analogous to
                // it: a SpEL expression there cannot invoke a method,
                // construct an
                // object, or loop (see UserToolScript's javadoc), whereas
                // this is
                // real code, and authoring it is a privileged act even with
                // the
                // sandbox in between. GETs fall through to
                // anyRequest().authenticated() below, same
                // read-open/write-gated
                // shape as /api/mcp/registry and the channel-credential
                // routes.
                // IDE/browser plugin SDLC alignment (2026-08-09): the two
                // stateless,
                // non-persistent endpoints (generate a draft, validate
                // arbitrary
                // source - CodeToolGenerationController) are opened to
                // agent-builder
                // for the plugins' "validate this / draft a tool from this
                // requirement" features. Declared BEFORE the broader rule
                // below so
                // these two exact paths win the first-match. Deliberately
                // NOT
                // widened: creating/enabling/editing a PERSISTENT
                // CustomCodeTool that
                // every agent in the tenant is then offered (POST base
                // path, PUT,
                // PATCH, DELETE, /{id}/test, /{id}/revalidate) - the
                // comment below
                // already calls authoring one "a privileged act even with
                // the sandbox
                // in between", and that stays tenant-admin-only.
                .requestMatchers(
                        HttpMethod.POST, "/api/tools/code/generate", "/api/tools/code/validate")
                .hasAnyAuthority("ROLE_tenant-admin", "ROLE_agent-builder")
                .requestMatchers(HttpMethod.POST, "/api/tools/code", "/api/tools/code/**")
                .hasAuthority("PERM_MANAGE_TOOLS")
                .requestMatchers(HttpMethod.PUT, "/api/tools/code/**")
                .hasAuthority("PERM_MANAGE_TOOLS")
                .requestMatchers(HttpMethod.PATCH, "/api/tools/code/**")
                .hasAuthority("PERM_MANAGE_TOOLS")
                .requestMatchers(HttpMethod.DELETE, "/api/tools/code/**")
                .hasAuthority("PERM_MANAGE_TOOLS")
                // Script tools (UserToolScriptController) are tenant-wide
                // and offered to every agent, like code tools; the SpEL
                // dry-run is part of authoring one.
                .requestMatchers(HttpMethod.POST, "/api/tools/scripts", "/api/tools/scripts/test")
                .hasAuthority("PERM_MANAGE_TOOLS")
                .requestMatchers(HttpMethod.PUT, "/api/tools/scripts/*")
                .hasAuthority("PERM_MANAGE_TOOLS")
                .requestMatchers(HttpMethod.PATCH, "/api/tools/scripts/*/enabled")
                .hasAuthority("PERM_MANAGE_TOOLS")
                .requestMatchers(HttpMethod.DELETE, "/api/tools/scripts/*")
                .hasAuthority("PERM_MANAGE_TOOLS")
                // Import from OpenAPI (OpenApiToolSourceController) -
                // registers
                // an external API, with a stored credential, as tools every
                // agent in the workspace is then offered. Same write-gated,
                // read-open shape as /api/mcp/registry and /api/tools/code
                // above, and for the same reason: pointing every agent at
                // outside infrastructure is a privileged act. POST includes
                // /preview deliberately - it makes a server-side fetch of a
                // caller-supplied URL (SSRF-guarded, but still an outbound
                // request on the caller's say-so). GETs fall through to
                // anyRequest().authenticated().
                .requestMatchers(HttpMethod.POST, "/api/tools/openapi", "/api/tools/openapi/**")
                .hasAuthority("PERM_MANAGE_TOOLS")
                .requestMatchers(HttpMethod.PUT, "/api/tools/openapi/**")
                .hasAuthority("PERM_MANAGE_TOOLS")
                .requestMatchers(HttpMethod.PATCH, "/api/tools/openapi/**")
                .hasAuthority("PERM_MANAGE_TOOLS")
                .requestMatchers(HttpMethod.DELETE, "/api/tools/openapi/**")
                .hasAuthority("PERM_MANAGE_TOOLS")
                // Knowledge-base authoring (authorization review 2026-10-10):
                // ingesting, uploading, re-embedding and deleting documents,
                // and creating/updating/deleting collections, change what
                // EVERY agent of the tenant retrieves - a route for prompt
                // injection into other users' agents, and an erasure path for
                // shared content. Gated on PERM_MANAGE_AGENTS (the
                // agent-builder bundle) like the agent configuration it
                // feeds; reads and POST /api/knowledge/search stay open to
                // every member (collections have no per-user ACL).
                // These controllers are @Profile("knowledge"), which is why
                // the coverage test never saw them before.
                .requestMatchers(
                        HttpMethod.POST,
                        "/api/knowledge/ingest",
                        "/api/knowledge/ingest-url",
                        "/api/knowledge/upload",
                        "/api/knowledge/upload-audio",
                        "/api/knowledge/collections",
                        "/api/knowledge/collections/*/reembed",
                        "/api/knowledge/*/eval")
                .hasAuthority("PERM_MANAGE_AGENTS")
                .requestMatchers(HttpMethod.PUT, "/api/knowledge/collections/**")
                .hasAuthority("PERM_MANAGE_AGENTS")
                .requestMatchers(
                        HttpMethod.DELETE,
                        "/api/knowledge/collections/**",
                        "/api/knowledge/documents/**")
                .hasAuthority("PERM_MANAGE_AGENTS")
                // Face enrollments are biometric templates stored in the
                // knowledge base: writing or erasing them is knowledge
                // authoring too. FaceEnrollmentController additionally
                // requires EDITOR on the agent for enroll and RUNNER for
                // list (the templates are the agent's data).
                .requestMatchers(HttpMethod.POST, "/api/face-enrollments")
                .hasAuthority("PERM_MANAGE_AGENTS")
                .requestMatchers(HttpMethod.DELETE, "/api/face-enrollments/**")
                .hasAuthority("PERM_MANAGE_AGENTS")
                // Tenant-configurable OCR provider BYOK credential (OCR
                // provider abstraction) - same write-gated, read-open
                // shape.
                .requestMatchers(HttpMethod.POST, "/api/knowledge/ocr-provider")
                .hasAuthority("PERM_MANAGE_INTEGRATIONS")
                .requestMatchers(HttpMethod.DELETE, "/api/knowledge/ocr-provider")
                .hasAuthority("PERM_MANAGE_INTEGRATIONS")
                // Knowledge connectors (P2 live-sync) - writes store
                // external
                // credentials and trigger outbound fetches, so they're
                // gated
                // like every other integration-credential write above;
                // listing
                // stays on anyRequest().authenticated().
                .requestMatchers(
                        HttpMethod.POST,
                        "/api/knowledge/connectors",
                        "/api/knowledge/connectors/**")
                .hasAuthority("PERM_MANAGE_INTEGRATIONS")
                .requestMatchers(HttpMethod.PUT, "/api/knowledge/connectors/**")
                .hasAuthority("PERM_MANAGE_INTEGRATIONS")
                .requestMatchers(HttpMethod.DELETE, "/api/knowledge/connectors/**")
                .hasAuthority("PERM_MANAGE_INTEGRATIONS")
                .requestMatchers(HttpMethod.POST, "/api/channels/twilio/credential")
                .hasAuthority("PERM_MANAGE_CHANNELS")
                // Security audit 2026-09-18: DELETE on the Twilio
                // credential was
                // never matched (only POST above), and the ElevenLabs
                // credential
                // controller had no matcher at all, so any tenant MEMBER
                // could
                // wipe or replace the tenant's voice credentials. Same
                // write-gated, read-open shape as the siblings here.
                .requestMatchers(HttpMethod.DELETE, "/api/channels/twilio/credential")
                .hasAuthority("PERM_MANAGE_CHANNELS")
                .requestMatchers(HttpMethod.POST, "/api/elevenlabs/credential")
                .hasAuthority("PERM_MANAGE_CHANNELS")
                .requestMatchers(HttpMethod.DELETE, "/api/elevenlabs/credential")
                .hasAuthority("PERM_MANAGE_CHANNELS")
                // Cloned voices run on that key and are shared by every
                // voice agent; cloning also takes a person's voice sample.
                .requestMatchers(HttpMethod.POST, "/api/elevenlabs/voices")
                .hasAuthority("PERM_MANAGE_CHANNELS")
                .requestMatchers(HttpMethod.DELETE, "/api/elevenlabs/voices/*")
                .hasAuthority("PERM_MANAGE_CHANNELS")
                // SAST audit 2026-07-29 (H3): these two channel-credential
                // controllers were the only ones in this file with no gate
                // at
                // all - every sibling POST below is admin-gated, they were
                // simply
                // omitted. A non-admin member could repoint the tenant's
                // Telegram
                // bot token (TelegramCredentialController:24) or IMAP
                // credentials
                // (EmailCredentialController:24) at infrastructure they
                // control
                // and capture inbound customer traffic. GET is included
                // because
                // unlike the status reads elsewhere in this file, these
                // return
                // the stored credential record rather than a masked status.
                .requestMatchers("/api/channels/telegram/credential")
                .hasAuthority("PERM_MANAGE_CHANNELS")
                // Matrix: connect/disconnect, sender + invite policy, test send and default agent
                // all speak as the tenant's bot, every method.
                .requestMatchers("/api/channels/matrix/**")
                .hasAuthority("PERM_MANAGE_CHANNELS")
                .requestMatchers("/api/channels/email/credential")
                .hasAuthority("PERM_MANAGE_CHANNELS")
                // Signal (operator-run signal-cli): the credential points core at a
                // server it will call and receive from, and the policy decides who
                // the agent answers, so every verb is the same tier as Telegram's
                // credential above (GET too: it returns the base URL and number).
                .requestMatchers("/api/channels/signal/credential")
                .hasAuthority("PERM_MANAGE_CHANNELS")
                .requestMatchers(HttpMethod.PUT, "/api/channels/signal/credential/policy")
                .hasAuthority("PERM_MANAGE_CHANNELS")
                // WhatsApp QR (operator-run bridge, linked-device): pairing a session, reading its
                // status, unlinking, sending and default-agent all speak as the tenant's own
                // number. GET is gated too, not the usual "read-open" split: GET /session returns
                // the live QR pairing payload while waiting for a scan, and that string is
                // precisely what completes a device link as the tenant's number.
                .requestMatchers("/api/channels/whatsapp-qr/**")
                .hasAuthority("PERM_MANAGE_CHANNELS")
                // Direct Meta WhatsApp Cloud API connection - the
                // alternative
                // to Twilio (see WhatsAppDispatchService's javadoc). Same
                // credential-write posture as the Twilio line above; the
                // inbound webhook itself lives under /webhook/** (already
                // permitAll below) since Meta's servers can't present a JWT
                // either.
                .requestMatchers(HttpMethod.POST, "/api/channels/meta-whatsapp/credential")
                .hasAuthority("PERM_MANAGE_CHANNELS")
                .requestMatchers(HttpMethod.DELETE, "/api/channels/meta-whatsapp/credential")
                .hasAuthority("PERM_MANAGE_CHANNELS")
                // Embedded Signup's exchange endpoint does the same
                // credential write as the manual form above, just triggered
                // by Meta's popup instead of a typed-in form - same gate.
                // Its /config GET stays open to any authenticated member
                // (no secret in that response - see
                // EmbeddedSignupConfigResponse).
                .requestMatchers(
                        HttpMethod.POST, "/api/channels/meta-whatsapp/embedded-signup/exchange")
                .hasAuthority("PERM_MANAGE_CHANNELS")
                // Facebook Page connection (Messenger + Instagram DMs) -
                // same
                // credential-write posture as the WhatsApp lines above; the
                // inbound webhook lives under /webhook/** (permitAll
                // below).
                .requestMatchers(HttpMethod.POST, "/api/channels/meta-social/credential")
                .hasAuthority("PERM_MANAGE_CHANNELS")
                .requestMatchers(HttpMethod.DELETE, "/api/channels/meta-social/credential")
                .hasAuthority("PERM_MANAGE_CHANNELS")
                .requestMatchers(HttpMethod.PUT, "/api/channels/social/messenger/default-agent")
                .hasAuthority("PERM_MANAGE_CHANNELS")
                .requestMatchers(HttpMethod.PUT, "/api/channels/social/instagram/default-agent")
                .hasAuthority("PERM_MANAGE_CHANNELS")
                // Slack App connection - same posture as the channel
                // credential writes above; inbound events land under
                // /webhook/** (permitAll below).
                .requestMatchers(HttpMethod.POST, "/api/channels/slack/credential")
                .hasAuthority("PERM_MANAGE_CHANNELS")
                .requestMatchers(HttpMethod.DELETE, "/api/channels/slack/credential")
                .hasAuthority("PERM_MANAGE_CHANNELS")
                .requestMatchers(HttpMethod.PUT, "/api/channels/slack/default-agent")
                .hasAuthority("PERM_MANAGE_CHANNELS")
                // Discord Application/Bot connection - same posture.
                // Inbound
                // messages arrive from the platform's own channels/discord
                // Node microservice, not a public webhook, but its endpoint
                // still lives under /webhook/** for consistency.
                .requestMatchers(HttpMethod.POST, "/api/channels/discord/credential")
                .hasAuthority("PERM_MANAGE_CHANNELS")
                .requestMatchers(HttpMethod.DELETE, "/api/channels/discord/credential")
                .hasAuthority("PERM_MANAGE_CHANNELS")
                .requestMatchers(HttpMethod.PUT, "/api/channels/discord/default-agent")
                .hasAuthority("PERM_MANAGE_CHANNELS")
                // Microsoft Teams (Azure Bot) - same channel-credential
                // posture. Channel audit 2026-09-23: this controller had NO
                // matcher at all, so POST fell through to
                // anyRequest().authenticated() and any tenant MEMBER could
                // register or replace the tenant's bot credentials - the
                // same H3-class gap the telegram/email comment above fixed.
                .requestMatchers(HttpMethod.POST, "/api/channels/teams/credential")
                .hasAuthority("PERM_MANAGE_CHANNELS")
                .requestMatchers(HttpMethod.DELETE, "/api/channels/teams/credential")
                .hasAuthority("PERM_MANAGE_CHANNELS")
                .requestMatchers(HttpMethod.PUT, "/api/channels/teams/default-agent")
                .hasAuthority("PERM_MANAGE_CHANNELS")
                // iMessage (BlueBubbles on the operator's Mac). Every method on the
                // credential is admin-gated, GET included: the response carries
                // the webhook secret that authenticates inbound calls, and the
                // POST repoints the tenant's outbound traffic at a server of the
                // caller's choosing. The settings PUT edits the sender allow-list,
                // i.e. who is served without pairing. Inbound itself lives under
                // /webhook/** (permitAll above) behind that per-tenant secret.
                .requestMatchers("/api/channels/imessage/credential")
                .hasAuthority("PERM_MANAGE_CHANNELS")
                .requestMatchers(HttpMethod.PUT, "/api/channels/imessage/credential/settings")
                .hasAuthority("PERM_MANAGE_CHANNELS")
                .requestMatchers(HttpMethod.PUT, "/api/channels/imessage/default-agent")
                .hasAuthority("PERM_MANAGE_CHANNELS")
                // Per-channel live health check ("Test connection") - uses
                // the stored credential to make a real outbound validation
                // call, same tier as a credential write: a member shouldn't
                // be able to probe third-party APIs with tenant secrets.
                .requestMatchers(HttpMethod.POST, "/api/channels/health/*")
                .hasAuthority("PERM_MANAGE_CHANNELS")
                // Google Workspace integration - starting the OAuth flow
                // and
                // disconnecting are both admin-gated connect/disconnect
                // actions, same tier as the other channel credential
                // writes;
                // GET status stays open to any authenticated tenant member.
                .requestMatchers(HttpMethod.GET, "/api/channels/google/authorize-url")
                .hasAuthority("PERM_MANAGE_CHANNELS")
                .requestMatchers(HttpMethod.DELETE, "/api/channels/google/connection")
                .hasAuthority("PERM_MANAGE_CHANNELS")
                // X (Twitter) DMs - two admin-gated writes (BYOK app
                // registration, then starting the OAuth flow) plus
                // disconnect, same tier as Google's OAuth lines above; GET
                // credential/status stays open to any authenticated member.
                .requestMatchers(HttpMethod.POST, "/api/channels/x/credential")
                .hasAuthority("PERM_MANAGE_CHANNELS")
                .requestMatchers(HttpMethod.GET, "/api/channels/x/authorize-url")
                .hasAuthority("PERM_MANAGE_CHANNELS")
                .requestMatchers(HttpMethod.DELETE, "/api/channels/x/connection")
                .hasAuthority("PERM_MANAGE_CHANNELS")
                .requestMatchers(HttpMethod.PUT, "/api/channels/x/default-agent")
                .hasAuthority("PERM_MANAGE_CHANNELS")
                // Per-node trigger rewrite - webhook/trigger-channel moved
                // from
                // /api/workflows/{id}/... to
                // /api/workflows/{id}/nodes/{nodeId}/...
                // (each trigger node now binds its own channel/webhook
                // independently). Matchers below updated to match; posture
                // unchanged from the F1/F2 audit notes further down.
                .requestMatchers(HttpMethod.POST, "/api/workflows/*/nodes/*/webhook")
                .hasAuthority("PERM_MANAGE_WORKFLOWS_ADMIN")
                .requestMatchers(HttpMethod.DELETE, "/api/workflows/*/nodes/*/webhook")
                .hasAuthority("PERM_MANAGE_WORKFLOWS_ADMIN")
                // Security audit 2026-07-29 (F2) - the GET on this same
                // path
                // hands back the workflow's PLAINTEXT webhook trigger token
                // (WorkflowWebhookResponse.token), which is a permanent,
                // unauthenticated, internet-reachable trigger URL for that
                // workflow - and one deliberately kept stable across
                // disable/re-enable (see WorkflowDefinition.webhookToken's
                // field javadoc), so a leaked token survives an admin
                // toggling
                // the webhook off and back on. It was the only verb on this
                // path NOT admin-gated, so it fell through to
                // anyRequest().authenticated() and any tenant member (or,
                // for
                // any pre-V151 workflow, ANY member - see WorkflowService
                // #requireAccess's LEGACY_OPEN note) could read the secret.
                // This is not the "GET stays open" posture the credential
                // lines above use: those GETs return status, never a
                // secret.
                .requestMatchers(HttpMethod.GET, "/api/workflows/*/nodes/*/webhook")
                .hasAuthority("PERM_MANAGE_WORKFLOWS_ADMIN")
                // V482 - webhook signature config and the HTTP poll trigger.
                // Signature writes can WEAKEN a node an admin hardened (back
                // to unsigned deliveries), so they sit in the same tier as the
                // token endpoints above; GET /webhook/signature returns only
                // mode + configured, never the secret - read-open. The poll
                // trigger is the same unattended-execution grant as PUT
                // /schedule below (fires forever against the configurer's
                // authority, on a cadence + an external endpoint), so its
                // writes gate identically; GET /poll-trigger returns config +
                // status but never the encrypted headers - read-open.
                .requestMatchers(HttpMethod.PUT, "/api/workflows/*/nodes/*/webhook/signature")
                .hasAuthority("PERM_MANAGE_WORKFLOWS_ADMIN")
                .requestMatchers(HttpMethod.DELETE, "/api/workflows/*/nodes/*/webhook/signature")
                .hasAuthority("PERM_MANAGE_WORKFLOWS_ADMIN")
                .requestMatchers(HttpMethod.PUT, "/api/workflows/*/nodes/*/poll-trigger")
                .hasAuthority("PERM_MANAGE_WORKFLOWS_ADMIN")
                .requestMatchers(HttpMethod.DELETE, "/api/workflows/*/nodes/*/poll-trigger")
                .hasAuthority("PERM_MANAGE_WORKFLOWS_ADMIN")
                .requestMatchers(HttpMethod.PUT, "/api/workflows/*/schedule")
                .hasAuthority("PERM_MANAGE_WORKFLOWS_ADMIN")
                .requestMatchers(HttpMethod.DELETE, "/api/workflows/*/schedule")
                .hasAuthority("PERM_MANAGE_WORKFLOWS_ADMIN")
                // Creating a workflow (new, a copy, or an LLM-drafted graph)
                // is building, like creating an agent. Not
                // MANAGE_WORKFLOWS_ADMIN, which also overrides access on
                // every workflow.
                .requestMatchers(
                        HttpMethod.POST,
                        "/api/workflows",
                        "/api/workflows/*/duplicate",
                        "/api/workflows/*/generate-graph")
                .hasAuthority("PERM_MANAGE_AGENTS")
                // Autonomous agents (PR-2) - an agent schedule is the same
                // trust
                // decision as the workflow schedule directly above, so it
                // is
                // placed here rather than with the agent routes, where the
                // parity
                // would not be visible. Configuring one means "this agent
                // runs
                // unattended, forever, spending against the configuring
                // human's
                // budget with that human's frozen authority" - strictly a
                // bigger
                // grant than running the agent once, which is why EDITOR
                // alone
                // (checked in AgentScheduleService) is not enough. GET
                // deliberately falls through to
                // anyRequest().authenticated():
                // AgentScheduleResponse is configuration plus skip counters
                // and
                // carries no secret, the same read-open split GET
                // /api/workflows/*/schedule uses.
                .requestMatchers(HttpMethod.PUT, "/api/agents/*/schedule")
                .hasAuthority("PERM_MANAGE_AGENTS")
                .requestMatchers(HttpMethod.DELETE, "/api/agents/*/schedule")
                .hasAuthority("PERM_MANAGE_AGENTS")
                // Resume and run-now are the same trust decision as
                // PUT/DELETE above
                // (they cause unattended execution under the configurer's
                // identity);
                // GET /schedule/runs stays read-open (VIEWER in-service).
                .requestMatchers(HttpMethod.POST, "/api/agents/*/schedule/resume")
                .hasAuthority("PERM_MANAGE_AGENTS")
                .requestMatchers(HttpMethod.POST, "/api/agents/*/schedule/run-now")
                .hasAuthority("PERM_MANAGE_AGENTS")
                // Crew handoff - marking an agent as a specialist makes it
                // callable by
                // other agents via delegate_to_specialist; GET stays
                // read-open
                // (authorised in-service, VIEWER), same split as the
                // schedule above.
                .requestMatchers(HttpMethod.PUT, "/api/agents/*/specialties")
                .hasAuthority("PERM_MANAGE_AGENTS")
                // Autonomous agents (PR-3) - an agent EVENT TRIGGER is the
                // same
                // trust decision as the agent schedule directly above, and
                // gets
                // the same rule for the same reason: configuring one means
                // "this
                // agent runs unattended, on somebody else's inbound
                // message,
                // spending against the configuring human's budget with that
                // human's frozen authority". A trigger is arguably the
                // sharper
                // of the two, since its firing rate is set by external
                // traffic
                // rather than by a cron the configurer chose. Note that no
                // matcher for ANY /api/agent* job path existed before this
                // pair:
                // they all fall through to anyRequest().authenticated(),
                // which
                // is correct for running and reading a job and was not
                // correct
                // for configuring unattended execution. GET deliberately
                // keeps
                // falling through - it is authorised in-service by
                // agentService.requireAccess(agentId, VIEWER), the same
                // read-open split GET /api/agents/*/schedule uses.
                .requestMatchers(HttpMethod.PUT, "/api/agents/*/triggers/**")
                .hasAuthority("PERM_MANAGE_AGENTS")
                .requestMatchers(HttpMethod.DELETE, "/api/agents/*/triggers/**")
                .hasAuthority("PERM_MANAGE_AGENTS")
                // Security audit 2026-07-29 (F1) - trigger-channel was
                // missed
                // when webhook/schedule above were gated, so it fell
                // through
                // to anyRequest().authenticated(). It is strictly MORE
                // sensitive than either:
                // WorkflowService#setTriggerChannel's
                // WHATSAPP/SMS/CALL branch calls
                // ChannelWorkflowTriggerService#setTrigger, a TENANT-GLOBAL
                // claim - it re-points every inbound WhatsApp/SMS/voice
                // message for the whole business at this one workflow. A
                // non-admin member could therefore create their own
                // workflow
                // (owner => EDITOR passes), activate it, and hijack the
                // tenant's inbound messaging, reading customer messages and
                // replying as the business. Exactly the same trust tier as
                // PUT /api/channels/whatsapp/default-agent below, which has
                // always been admin-gated. The WEBHOOK branch of the same
                // endpoint also writes webhookAuthMode, i.e. a non-admin
                // could
                // downgrade OAUTH2 back to SHARED_SECRET and re-arm an
                // unauthenticated token URL an admin had deliberately moved
                // to
                // OIDC - this one matcher closes both paths. GET
                // /trigger-channel stays on anyRequest().authenticated()
                // (it
                // returns only which channel/auth-mode is configured, no
                // secret), same read-open posture as GET /schedule.
                .requestMatchers(HttpMethod.PUT, "/api/workflows/*/nodes/*/trigger-channel")
                .hasAuthority("PERM_MANAGE_WORKFLOWS_ADMIN")
                // Per-tenant channel configuration (Stage 4
                // credential/webhook
                // follow-up) - a real config change (which agent answers
                // inbound WhatsApp for this tenant), not a read, so it's
                // gated the same way as the approval policy and MCP
                // registry
                // writes above. See WhatsAppSendController#setDefaultAgent.
                .requestMatchers(HttpMethod.PUT, "/api/channels/whatsapp/default-agent")
                .hasAuthority("PERM_MANAGE_CHANNELS")
                // WhatsApp opt-in records (docs/channels/whatsapp-policy.md): they authorise
                // business-initiated template sends, so recording or revoking them is a channel
                // configuration change. The GET quality endpoint stays authenticated().
                .requestMatchers(HttpMethod.POST, "/api/channels/whatsapp-compliance/opt-in")
                .hasAuthority("PERM_MANAGE_CHANNELS")
                .requestMatchers(HttpMethod.DELETE, "/api/channels/whatsapp-compliance/opt-in")
                .hasAuthority("PERM_MANAGE_CHANNELS")
                // Master Roadmap 2026 item #17 (real-time voice agents) -
                // same posture as the WhatsApp default-agent line above;
                // the
                // inbound call TwiML webhook and the Conversation Relay
                // WebSocket both live under /webhook/** (permitAll below) -
                // see VoiceChannelController/TwilioVoiceWebhookController.
                .requestMatchers(HttpMethod.PUT, "/api/channels/voice/default-agent")
                .hasAuthority("PERM_MANAGE_CHANNELS")
                // Plain SMS conversations (2026-10-14) - same posture as the
                // voice line above: reuses the tenant's Twilio credential, the
                // inbound webhook is /webhook/twilio/sms (permitAll below).
                .requestMatchers(HttpMethod.PUT, "/api/channels/sms/default-agent")
                .hasAuthority("PERM_MANAGE_CHANNELS")
                // Edge relay (2026-10-14): a browser registers as the model behind
                // the tenant's channels and reads their inbound messages, which
                // the channel-management permission already grants through the
                // conversation inbox. See EdgeRelayController.
                .requestMatchers("/api/channels/edge-relay/**")
                .hasAuthority("PERM_MANAGE_CHANNELS")
                // Master Roadmap 2026 item #17 fast-follow (voice DTMF
                // menu)
                // - the writes are the same tier as the default-agent line
                // just above (which agent a caller reaches is exactly what
                // both control); GET /dtmf-menu falls through to plain
                // anyRequest().authenticated() so any authenticated tenant
                // member can see the configured menu, same posture as every
                // other channel status/config read in this file.
                .requestMatchers(HttpMethod.PUT, "/api/channels/voice/dtmf-menu/**")
                .hasAuthority("PERM_MANAGE_CHANNELS")
                .requestMatchers(HttpMethod.DELETE, "/api/channels/voice/dtmf-menu/**")
                .hasAuthority("PERM_MANAGE_CHANNELS")
                // Autonomy/Guardrails Strategy Stage A -
                // approving/rejecting
                // an inbound-sender pairing grants a stranger a live agent
                // conversation, same trust level as the config writes
                // above.
                // The GET (viewing the pending list) is left to plain
                // `anyRequest().authenticated()` below - any authenticated
                // tenant member can see what's pending, only an admin can
                // decide it.
                .requestMatchers(HttpMethod.POST, "/api/channels/pairing/*/approve")
                .hasAuthority("PERM_MANAGE_CHANNELS")
                .requestMatchers(HttpMethod.DELETE, "/api/channels/pairing/*")
                .hasAuthority("PERM_MANAGE_CHANNELS")
                // #590 follow-up ("wrong agent for telegram message
                // sending")
                // - re-binding one already-existing conversation to a
                // different agent is the same trust tier as approving a
                // stranger into a live agent conversation above.
                .requestMatchers(HttpMethod.PUT, "/api/channels/pairing/*/agent")
                .hasAuthority("PERM_MANAGE_CHANNELS")
                // Siblings of the rules around them that were missed:
                // routing inbound email/Telegram/voice to an agent, and
                // unpairing a live conversation, are the same trust tier.
                .requestMatchers(
                        HttpMethod.PUT,
                        "/api/channels/email/default-agent",
                        "/api/channels/telegram/default-agent",
                        "/api/channels/signal/default-agent",
                        "/api/channels/voice/numbers/*")
                .hasAuthority("PERM_MANAGE_CHANNELS")
                .requestMatchers(HttpMethod.DELETE, "/api/channels/voice/numbers/*")
                .hasAuthority("PERM_MANAGE_CHANNELS")
                .requestMatchers(HttpMethod.POST, "/api/channels/pairing/*/unpair")
                .hasAuthority("PERM_MANAGE_CHANNELS")
                // Ad-hoc sends speak as the tenant to any recipient (paid
                // per message on some channels, public on X). The approval
                // gate still applies; this decides who may ask.
                .requestMatchers(
                        HttpMethod.POST,
                        "/api/channels/discord/send",
                        "/api/channels/email/send",
                        "/api/channels/imessage/send",
                        "/api/channels/slack/send",
                        "/api/channels/signal/send",
                        "/api/channels/social/instagram/send",
                        "/api/channels/social/messenger/send",
                        "/api/channels/teams/send",
                        "/api/channels/telegram/send",
                        "/api/channels/whatsapp/send",
                        "/api/channels/x/send")
                .hasAuthority("PERM_MANAGE_CHANNELS")
                // Human handoff: records OperatorInboxController's own
                // check (PERM_MANAGE_CHANNELS or platform-admin).
                .requestMatchers(
                        HttpMethod.POST,
                        "/api/handoff/sessions/*/escalate",
                        "/api/handoff/sessions/*/release",
                        "/api/handoff/sessions/*/reply",
                        "/api/handoff/sessions/*/take-over")
                .hasAnyAuthority("PERM_MANAGE_CHANNELS", "ROLE_platform-admin")
                // #815 Governance follow-up (PII handling review) - GDPR
                // Article 17 data-subject erasure. Same trust tier as
                // approve/reject/rebind above - erasing a real person's
                // data
                // is at least as consequential as any of those, and
                // strictly
                // more irreversible.
                .requestMatchers(HttpMethod.POST, "/api/channels/pairing/erase-subject")
                .hasAuthority("PERM_MANAGE_CHANNELS")
                // Contacts & request mapping (V238, /admin/contacts) -
                // every
                // write here is the same trust tier as the pairing actions
                // directly above: creating/importing a contact or flipping
                // a
                // channel to auto-approve is granting a stranger a live
                // agent
                // conversation WITHOUT the pairing queue standing in
                // between.
                // The GETs (contact list, groups, per-channel routing)
                // carry
                // no secret and stay on plain anyRequest().authenticated(),
                // same read-open split as GET
                // /api/channels/pairing/pending.
                .requestMatchers(HttpMethod.POST, "/api/channels/contacts/**")
                .hasAuthority("PERM_MANAGE_CHANNELS")
                .requestMatchers(HttpMethod.PUT, "/api/channels/contacts/**")
                .hasAuthority("PERM_MANAGE_CHANNELS")
                .requestMatchers(HttpMethod.DELETE, "/api/channels/contacts/**")
                .hasAuthority("PERM_MANAGE_CHANNELS")
                .requestMatchers(HttpMethod.POST, "/api/channels/contact-groups/**")
                .hasAuthority("PERM_MANAGE_CHANNELS")
                .requestMatchers(HttpMethod.PUT, "/api/channels/contact-groups/**")
                .hasAuthority("PERM_MANAGE_CHANNELS")
                .requestMatchers(HttpMethod.DELETE, "/api/channels/contact-groups/**")
                .hasAuthority("PERM_MANAGE_CHANNELS")
                .requestMatchers(HttpMethod.PUT, "/api/channels/routing/**")
                .hasAuthority("PERM_MANAGE_CHANNELS")
                // Built-in Leads CRM (V337) - leads are PII, so reads AND
                // writes are gated by MANAGE_LEADS (no read-open split).
                .requestMatchers("/api/leads", "/api/leads/**")
                .hasAuthority("PERM_MANAGE_LEADS");
    }
}
