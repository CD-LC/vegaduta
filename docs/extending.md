# Extending VegaDūta

Seven extension points, from "no code at all" to "a new module", then three
configuration-only ways to connect the platform to other systems, a rule for
threaded code, and four internal seams (tool sources, the HTTP request pipeline,
LLM call sinks, the turn journal) for changing how the core itself behaves. Java paths are abbreviated: `core/<module>/.../X.java` means
`core/<module>/src/main/java/com/agenticai/core/.../X.java`.

| You want to | Code needed | Section |
|---|---|---|
| Call an existing HTTP API from agents | none (OpenAPI import) | [1](#1-add-tools-from-an-openapi-spec) |
| Give agents an MCP server's tools | none (registry) or one catalog entry | [2](#2-add-an-mcp-server) |
| Teach agents a procedure or house style | a markdown file | [3](#3-add-a-skill-skillmd) |
| Support a new LLM vendor | one Java class + enum value | [4](#4-add-an-llm-provider) |
| Add a step type to the workflow canvas | one `NodeHandler` class + TypeScript form and palette entry | [5](#5-add-a-workflow-node-type) |
| Reach agents from a new messaging channel | a Java package + migration + one web adapter file | [6](#6-add-a-channel) |
| Administer users and SSO in a non-Keycloak identity provider | one Java adapter class | [7](#7-add-an-identity-provider-adapter) |
| Hand work to another thread without losing the tenant | a rule, not a feature | [8](#8-hand-work-to-another-thread) |
| Start a workflow from CI, a script or another system | none (tenant API key, webhook or HTTP polling trigger) | [9](#9-connect-other-systems-without-code) |
| Offer another local model in `scripts/vegaduta models` | one line in a text file | [9](#9-connect-other-systems-without-code) |
| Give agents a new built-in tool, or a new origin of tools | one `ToolSource` bean | [10](#10-add-a-tool-source) |
| Add or tighten a rule of the `http_request` tool and `http` node | one stage class and one line in the executor | [11](#11-change-the-http-request-pipeline) |
| Record, export or alert on every LLM call | one `LlmCallSink` class | [12](#12-add-an-llm-call-sink) |
| Record another stage of an agent turn | a `TurnRecorder` method (+ a migration for a new type) | [13](#13-record-more-of-a-turn-in-the-turn-journal) |
| Put governance in your own Java or Python application | a dependency, not a platform extension | [SDKs](sdk/README.md) |

All REST calls below need a bearer token for a user of the tenant (from
Keycloak); most of them are also available in the console.

## 1. Add tools from an OpenAPI spec

Any OpenAPI 3.x or Swagger 2.0 document becomes a set of tools, one per
operation, named `<toolPrefix>_<operationId>`.

**Console:** Tools, then Import from OpenAPI.

**API** (`core/agents-tools/.../tools/openapi/OpenApiToolSourceController.java`, base `/api/tools/openapi`):

```http
POST /api/tools/openapi/preview      # parse and list operations, saves nothing
POST /api/tools/openapi
Content-Type: application/json

{
  "name": "Weather",
  "toolPrefix": "weather",
  "specUrl": "https://api.example.com/openapi.json",
  "baseUrl": "https://api.example.com",
  "authType": "API_KEY_HEADER",
  "authHeaderName": "X-API-Key",
  "secret": "<api key>",
  "enabledOperationKeys": ["GET /v1/forecast"]
}
```

- Use `specText` instead of `specUrl` to paste the document.
- `authType`: `NONE`, `API_KEY_HEADER`, `BEARER` or `OAUTH2_CLIENT_CREDENTIALS`
  (with `oauth2TokenUrl`, `oauth2ClientId`, `oauth2Scope`, and the client
  secret in `secret`).
- `secret` is encrypted at rest with `APP_ENCRYPTION_KEY`.
- `enabledOperationKeys` (`"METHOD /path"`) limits which operations become
  tools; omit it to enable all.
- Other endpoints: `PUT /{id}`, `POST /{id}/refresh` (re-fetch the spec),
  `GET /{id}/tools`, `POST /{id}/tools/{toolId}/test`.

Example specs: `docs/connectors/*.openapi.yaml`.

## 2. Add an MCP server

VegaDūta connects to MCP servers over **stdio**, **SSE** and **Streamable
HTTP**. Every tool from a connected server is wrapped by the guardrail and
logging callbacks, and can be put behind human approval.

### Per tenant, at runtime (no deploy)

**Console:** Tools, then MCP servers (catalog, registry search, or custom).

**API** (`core/mcp/.../McpRegistryController.java`, base `/api/mcp/registry`):

```http
POST /api/mcp/registry
Content-Type: application/json

{
  "name": "home-assistant",
  "type": "STDIO",
  "description": "Controls Home Assistant entities",
  "enabled": true,
  "command": "npx",
  "args": ["-y", "@your-org/mcp-server-home-assistant"],
  "env": { "HASS_URL": "http://homeassistant.local:8123", "HASS_TOKEN": "..." }
}
```

For a remote server use `"type": "STREAMABLE_HTTP"` (or `"SSE"`) and `"url"`
instead of `command`/`args`. Remote URLs pass through an SSRF guard
(`McpRemoteUrlGuard`).

- `env` is encrypted at rest and only decrypted to start the process.
- If `enabled` is true, core connects right away; watch `connectionStatus`
  (`CONNECTING`, `CONNECTED`, `ERROR`, ...) with `GET /api/mcp/registry`.
  Retry with `POST /api/mcp/registry/{id}/connect`, disable with
  `PATCH /api/mcp/registry/{id}` and `{"enabled": false}`.
- A health job lists tools on every live connection every few minutes and
  marks dead ones `ERROR`.
- Search the official MCP registry: `GET /api/mcp/registry/search?q=...`.
- To run stdio servers outside the core process, enable the MCP stdio runner
  (`MCP_STDIO_SIDECAR_ENABLED=true`, Compose profile `sandbox`).

### For every tenant, at deploy time

Static servers go in `core/app-config/src/main/resources/application-mcp.yml`
under `spring.ai.mcp.client.<transport>.connections.<name>` and are active
with the `mcp` Spring profile.

### A one-click catalog entry

Add an entry to `core/mcp/.../McpCatalogService.java`. `{{VAR}}` placeholders
in its arguments are replaced by values the user types in the console.

More detail (response shape, governance, caveats):
[core/mcp/REGISTERING-A-CUSTOM-MCP-SERVER.md](core/mcp/REGISTERING-A-CUSTOM-MCP-SERVER.md).
Example servers: `examples/mcp-servers/`.

## 3. Add a skill (`SKILL.md`)

A skill is a folder with a `SKILL.md` file (and optional bundled files). Agents
see only each skill's name and description until they decide to load one
(progressive disclosure).

```markdown
---
name: incident-report
description: Write a post-incident report in our format. Use when the user asks for an incident write-up, postmortem or RCA.
---

# Incident reports

1. Start with a one-paragraph summary: impact, duration, root cause.
2. ...
```

`name` and `description` are required (`core/agents-turn/.../skills/SkillMarkdownParser.java`).

- **For a tenant:** create it in the console, or
  `POST /api/skills` with `{"skillMd": "<the whole file>"}`, or upload a zip of
  the folder with `POST /api/skills/import` (multipart field `file`). Enable it
  per agent with `PUT /api/agents/{agentId}/skills`. Export with
  `GET /api/skills/{name}/export`.
- **Built in, for everyone:** add `core/agents-turn/src/main/resources/skills/<name>/SKILL.md`.
  Built-ins are loaded from the classpath at start-up (`BuiltInSkillCatalog`);
  no migration is needed. A built-in that fails to parse is logged and skipped,
  so check the start-up log after adding one.

## 4. Add an LLM provider

Providers are Spring beans discovered automatically.

1. Add a value to `core/providers/.../providers/ProviderName.java`.
2. Create a `@Component` in `core/chat` implementing
   `core/chat/.../chat/AgentChatClientFactory.java`:
   `supportedProvider()` returns your enum value and `create(apiKey, model,
   maxTokens, temperature, topP)` returns a Spring AI `ChatClient`. OpenAI-
   compatible APIs can reuse the OpenAI client with a different base URL; see
   `DeepSeekAgentChatClientFactory` or `GroqAgentChatClientFactory` for small
   examples. `ChatClientRegistry` picks up every factory bean.
3. If the provider needs more than an API key (endpoint, region, deployment),
   add a credential type and resolver like `AzureOpenAiCredential` or
   `AwsBedrockCredential`.
4. Add its models to the model catalog with a new **controlplane** Flyway
   migration (`provider_model_catalog`); remember that versions are shared with
   the tenant folder.
5. Console: add the key card in `web/app/components/ProvidersPanel.tsx` and
   the setup guide in `web/app/lib/credentialGuides.ts`.

Design notes: [ADR-0011](architecture/ADR-0011-provider-plugins.md) (current) and the historical [provider capability registry note](architecture/archive/GENERIC-PROVIDER-CAPABILITY-REGISTRY-2026-08-09.md).

## 5. Add a workflow node type

The executor does not switch on the node type. `WorkflowNodeExecutor` does the steps every node shares (pin
lookup, approval gate, `{{ }}` expression resolution, dry run, run-data recording) and then looks the node's
lower-case `type` up in a registry built from every `NodeHandler` bean
(`core/workflow/.../workflow/NodeHandler.java`, one class per node type, injected as `List<NodeHandler>`).

**Server**

1. Add a value to `core/workflow/.../workflow/WorkflowNodeType.java`. The wire value is the lower-case
   name (`fan_out` for `FAN_OUT`).
2. Add a handler: a Spring `@Component` in package `com.agenticai.core.workflow` implementing `NodeHandler`
   (the existing ones, such as `ConditionNodeHandler`, are package-private).

   ```java
   @Component
   class MyNodeHandler implements NodeHandler {
       @Override public Set<String> nodeTypes() { return Set.of("my_node"); }

       @Override public NodeExecutionResult handle(NodeInvocation inv) {
           // inv.node() already has its {{ }} config resolved; do not substitute again.
           String out = ...;                       // inv.input() is the upstream output
           return NodeExecutionResult.ok(out, "what happened, one line", null);
       }
   }
   ```

   - `NodeInvocation` carries everything run-specific (node, the stored `unresolvedNode`, input, workflow and
     run ids, `requestedBy`, intelligence settings, attachments, the expression resolver and scope). A handler
     keeps no per-run state.
   - Result helpers on `NodeExecutionResult`: `ok(output, summary, branch)` (set `branch` to `"true"`/`"false"`-style
     labels to route), `skipped`, `halted(status, summary)` (stop the run), and `pinned`.
   - One handler may claim several types (`nodeTypes()` returns a set). Two handlers claiming the same type fail
     the executor's construction at startup, so a collision cannot go unnoticed.
   - A type with no handler is not an error: the node passes its input through unchanged with a "not yet
     executable" summary.
   - Shared helpers: `WorkflowNodeSupport` (config accessors such as `stringConfig`), `NodeConfigResolver`,
     `NodeOutputMapper`.
3. Nodes that are not a single-node operation are deliberately **not** handlers: `loop`, `parallel`, `fan_out`
   and `wait` are driven by `WorkflowExecutionEngine`, which alone holds the graph's adjacency
   (`WaitNodeHandler` only exists to fail loudly if one reaches the executor through the Temporal path or a
   loop body). If your node pauses or branches the run, it belongs in the engine, and the pure rules go in
   `WorkflowTraversalRules`, which both engines share.
4. The Temporal engine runs a node through `workflow/temporal/WorkflowNodeActivitiesImpl.java`, which calls the
   same `WorkflowNodeExecutor`, so a registered handler works under both engines with no extra code.

**Console**

5. Palette: add an entry to `NODE_DEFS` in `web/app/components/workflow/nodeDefs.ts` (type, icon, category,
   `defaultConfig`) and its labels to the `nodeDefs` namespace of `web/messages/en.json`.
6. Configuration form: `NodeConfigDrawer.tsx` is only a shell; it asks `resolveNodeForm(node.type)` in
   `web/app/components/workflow/nodeConfig/registry.tsx`. Pick one:
   - **A few plain text or number inputs:** add a descriptor to `SIMPLE_FORMS` in
     `nodeConfig/descriptors.ts` (`{ kind: "text" | "number", key, labelKey, ... }`, label keys in the
     `nodeConfigDrawer` namespace) and map the type to `SimpleFieldsForm` in `NODE_FORMS`. No form file.
   - **Anything richer:** write `nodeConfig/forms/<Name>Form.tsx` (props: `NodeFormProps` from
     `nodeConfig/registryTypes.ts`: `nodeId`, `nodeType`, `workflowId`, `config`, `setConfig(patch)`, and the
     agent, collection and device lists) and add one line `my_node: MyForm` to the `NODE_FORMS` map.
   - The AI-node and data-node families resolve by predicate (`isAiNodeType`, `isDataNodeType`) rather than
     by map entry.
   `nodeConfig/registry.test.tsx` checks that every `SIMPLE_FORMS` type is registered and that an unknown type
   resolves to `null`.
7. Tests: a handler is tested alone (see `BulkEmailNodeHandlerTest`, `AiClassifierHandlerTest`); the
   executor-level tests (`WorkflowNodeExecutor*Test`, built with `NodeExecutorTestFactory`) cover the shared
   steps. Consider a template in `examples/workflow-templates/`.

## 6. Add a channel

Channels live in `core/omnichannel`, one package per channel (see
`telegram/` for a compact example).

1. Add a value to `core/omnichannel/.../session/ChannelType.java`.
2. **Outbound:** a `*ChannelAdapter` implementing
   `core/approval/.../approval/ApprovalActionExecutor.java` with a unique
   `actionType()` and `execute(ApprovalRequest)`. Outbound messages therefore
   pass through the approval policy like any other side effect. For images or
   files, also implement `media/ChannelMediaSender`.
3. **Inbound:** a webhook controller (see `TelegramWebhookController`) that
   verifies the request signature, resolves the conversation through
   `ChannelSessionService`, and hands the message to the tenant's agent.
   Permit the webhook path in `core/security/.../SecurityConfig.java`; the
   signature check is then the only authentication, so make it strict.
4. **Credentials:** an entity, repository and service for the tenant's channel
   credentials (encrypted with `EncryptionService`) and a **tenant** Flyway
   migration for its table.
5. Console: channels are a registry on the web side too. `ChannelsPanel.tsx` lists the tiles and renders
   the selected channel's inspector with `adapterFor(selected)?.Inspector`; it has no per-channel markup.
   - Add the channel to `CHANNEL_META` and `CHANNEL_ORDER` in
     `web/app/components/channels/ChannelInspectorCards.tsx` (label key, initials, brand colours from
     `BrandIcons.tsx`) so a tile appears.
   - Create `web/app/components/channels/adapters/<channel>.tsx` exporting
     `adapter: ChannelAdapter = { keys: ["<channel>"], Inspector }` (types in `adapters/types.ts`). The
     inspector receives one `ctx` object from the panel (shared flags, translators, agent list, and the
     per-channel state the panel owns); `telegram.tsx` is the compact example and uses `SimpleConnectPanel` and
     `TestSendCard`.
   - Register it in the `CHANNEL_ADAPTERS` array in `adapters/index.ts`. If the channel is a plain
     credential-plus-default-agent channel, add one `useSimpleChannel({...})` instance next to the Telegram one
     in `ChannelsPanel.tsx` and put it into the `ctx` object.
   - `adapters/registry.test.ts` fails when a tile has no adapter or a key is registered twice.

## 7. Add an identity provider adapter

The platform administers users and SSO through two ports in
`core/admin/.../identity/`, not through Keycloak directly. Only code in
`com.agenticai.core.admin.keycloak` may name `KeycloakAdminClient`;
`IdentityAdminPortRulesTest` fails the build otherwise. The rule covers
production classes only: unit tests may still mock `KeycloakAdminClient`
directly, and many do today.

| Port | Covers | Callers | Implementations |
|---|---|---|---|
| `IdentityUserAdminPort` | list/find/create/enable/delete users, tenant attributes, realm roles, account emails | signup and onboarding, tenant erasure and account deletion, user admin, SCIM, sharing, platform console | `KeycloakAdminClient` (default), `Auth0UserAdminClient` |
| `IdentityProviderAdminPort` | federated identity providers and their mappers (per-tenant SSO, social login) | `TenantSsoService`, `SocialAuthConfigController` | `KeycloakAdminClient` only |

`app.identity.provider` (`keycloak` by default, `auth0` for the Auth0
adapter) selects the `IdentityUserAdminPort` bean; each adapter is a
`@ConditionalOnProperty` bean so exactly one exists per deployment. The
Auth0 adapter covers **user admin only** — `IdentityProviderAdminPort`
(SSO connection management) remains Keycloak-only, so `TenantSsoService`,
`TenantSsoController` and `SocialAuthConfigController` are gated on the
same property and absent under Auth0 (`/api/tenant/sso/**` — including
SCIM token minting, which lives on that controller — and
`/api/auth/social-providers` return 404). Token validation is separate:
it already works for Auth0 via `KEYCLOAK_ISSUER_URI`,
`KEYCLOAK_JWK_SET_URI` and the `app.security.oidc.*` claim mapping below.


To add a provider:

1. Write a class implementing both ports (or one of them; inject sites ask for exactly the port they use).
   Mirror `KeycloakAdminClient`: a `@Component` behind a profile or `@ConditionalOnProperty`, so exactly one bean
   implements each port. Two beans of the same port make injection ambiguous at startup.
2. Keep the exception contract: throw `KeycloakUserConflictException` for a duplicate email or username,
   `KeycloakUserValidationException` for rejected input and `KeycloakAdminException` for anything else
   upstream; callers map these to 409, 400 and 502. `findByEmail` and `getIdentityProvider` return empty, not an
   exception, when nothing matches. `replaceUserRealmRoles` must keep calling
   `TenantAssignableRoles.requireAllAssignable` (it is the privilege-escalation guard).
3. Map your provider's user onto `KeycloakUserRepresentation` (id, username, email, names, enabled, attributes with
   `tenant_id`). The type keeps its Keycloak name for now; it has no Keycloak dependency.
4. Copy `KeycloakAdminClientTest` for the HTTP contract of your adapter.

What the adapter cannot change, because the token side is separate from the
admin port:

- Roles come from the claim named by `app.security.oidc.roles-claim-path`
  (default `realm_access.roles`, the Keycloak shape; `JwtClaimPaths` resolves
  dot-paths and literal namespaced keys like `https://example.com/roles`).
- The tenant comes from the claim named by `app.security.oidc.tenant-claim`
  (default `tenant_id`, via `TenantContextFilter`); the adapter must write
  the `tenant_id` user attribute (`createUserForSignup`, `setUserAttributes`)
  and the provider must emit it as a token claim under that name.
- Issuers are validated against `KEYCLOAK_ADDITIONAL_ISSUERS` plus the primary issuer.

An adapter for a provider with different claim names therefore needs no code —
only configuration (`OIDC_ROLES_CLAIM_PATH`, `OIDC_TENANT_CLAIM`,
`OIDC_SCOPES_CLAIM` in `core.env`/`application-security.yml`). Remaining
Keycloak-shaped defaults to override for other providers: `KEYCLOAK_JWK_SET_URI`
(defaults to the Keycloak `/protocol/openid-connect/certs` path) and
`APP_KEYCLOAK_ALLOWED_AZP` (defaults to Keycloak's first-party client names;
empty disables the check). See
[configuration.md](configuration.md#authentication-oidc--keycloak).

## 8. Hand work to another thread

Tenants are Postgres schemas chosen per request from `TenantContext`, a ThreadLocal. Work you hand to
another thread (an executor, `CompletableFuture.supplyAsync`, a Reactor `subscribeOn` or `publishOn`, a raw
`Thread`, `@Async`) does not have it: on a fresh thread the query fails with `tenant_context_missing`, and on a
pooled thread that was left holding a tenant it runs against the wrong schema.

- Get every thread, pool and scheduler from `com.agenticai.core.security.concurrent.TenantAwareExecutors`
  (Reactor: `TenantAwareSchedulers.boundedElastic()` / `.parallel()`), never from `Executors.*`, `new Thread`,
  `Thread.ofVirtual()`, a pool constructor or `Schedulers.*`. Its executors capture the submitting thread's
  tenant, agent-version pin and MDC on every `execute`/`submit`/`schedule`, apply them on the worker and
  restore the worker afterwards; a task submitted without a tenant runs with none. Pass one of these executors
  to every `CompletableFuture.*Async` call (the executor-less form runs on the JVM-wide common pool).
  `RawExecutorBanRulesTest` (core/app) fails the build on the raw forms; see
  [ADR-0024](architecture/ADR-0024-tenant-aware-executors.md) for the semantics and the full API.
- Capture on the originating thread, re-apply on the worker: `TenantScope scope = TenantScope.capture();` then
  `scope.run(..)`, `scope.wrapRunnable(..)` or `scope.wrapSupplier(..)` on the worker. It restores the thread
  afterwards, so a pooled thread never keeps a tenant.
- For a Reactor subscription use `TenantScopedFlux.subscribeInScope(scope, () -> flux)`
  (`com.agenticai.core.agents.turn.TenantScopedFlux`, `core/agents-turn`). Do not set the context
  inside `Flux.defer` and clear it in `doFinally`: `doFinally` runs on the thread that delivers the terminal
  signal, not on the thread that set it.
- `ContextPropagatingExecutor` (the `platformVirtualExecutor` bean) and `TenantAwareExecutors.propagating`
  (`ContextPropagatingExecutor.propagating` delegates to it) do the same for a plain `Runnable`.
- A periodic task keeps the scheduling thread's tenant on every run, so schedule timers from a tenant-less
  thread and bind tenants per tick. A pod-wide timer started lazily from a request thread wraps its task
  in `TenantAwareExecutors.platform(..)` so it runs with no tenant. After `publishOn`, downstream work runs with the emitting thread's tenant
  (often none): bind a captured `TenantScope` there.
- Callbacks that Reactor runs on whatever thread delivers a signal (`doOnComplete`, `subscribe(..)` lambdas)
  are not caught by any check: wrap tenant-table work in them with a scope captured when the stream is built.

`AsyncHopTenantScopeRulesTest` (core/app) fails when a class hands work to another thread without one of these in
the same method (a field typed `ExecutorService` does not say it came from the factory, so the hand-off is still
checked). If the hop genuinely needs no tenant (it only touches `control_plane`-qualified tables, Redis or
HTTP, or the tenant travels as an explicit id), add `fully.qualified.Class | reason` to
`core/app/src/test/resources/archrules/async-hop-allowlist.txt`.

## 9. Connect other systems without code

These need no new class. Each is gated by a permission and recorded in the audit log; check the code
paths named before relying on a detail.

- **Tenant API key** (`core/tenancy/.../apikey/TenantApiKeyController.java`, base `/api/tenant/api-keys`;
  console: Administration, API keys). `GET` lists, `POST` creates, `POST /{id}/rotate` replaces the
  secret, `PATCH /{id}` enables or disables, `DELETE /{id}` revokes. The secret is a `vdak_...` string
  shown once and stored hashed. Call the API with `Authorization: Bearer vdak_...`
  (`core/security/.../apikey/ApiKeyAuthFilter.java`). A key's scopes are validated as a subset of the
  permissions of the user who creates it, so a key cannot exceed its issuer. This is separate from the
  `vmcp_` grant keys for `/mcp`
  ([MCP alignment](architecture/mcp-spec-alignment.md)).
- **HTTP polling trigger** for a workflow Trigger node
  (`core/workflow/.../WorkflowController.java`, `PUT|GET|DELETE
  /api/workflows/{workflowId}/nodes/{nodeId}/poll-trigger`; limits in `app.workflow.poll.*`,
  `WorkflowPollProperties`). The body takes `url`, `method`, `headers`, `intervalSeconds`, `itemsPath`,
  `idPath` and `seedOnEnable`. The node polls the URL, de-duplicates items by `idPath` (falling back to a
  content hash), and starts one run per new item. The first poll records the existing items without firing
  unless `seedOnEnable` is `false`. URLs go through the SSRF guard, redirects are not followed, and
  writing needs `PERM_MANAGE_WORKFLOWS_ADMIN`. The workflow must be active and the tenant's plan must allow
  unattended automation. Webhook and inbound-channel triggers are configured per Trigger node too (`WorkflowTriggerConfigService`).
- **Local model catalog.** `scripts/vegaduta models list|pull|rm` reads `deploy/models/catalog.txt`; a row is
  `tag|family|download GB|RAM GB|note` (an Ollama tag). Installing a model puts the weights on the server; making
  the free pool use it is a separate step in Platform Admin, LLM providers
  ([Run free with local models](local-models.md#model-catalog)).

## 10. Add a tool source

An agent turn's tool list is not built by one class. `ToolResolver`
(`core/agents-tools/.../tools/ToolResolver.java`) collects every `ToolSource` bean
(`ToolSource.java`) and does the merging; a source only answers "which guard-wrapped tools do you offer for this
turn". `ToolSource` and its beans are package-private to `com.agenticai.core.agents.tools`.

What the resolver does, in order: collect the `MCP` and `BUILT_IN` sources (each in `@Order`), merge (a built-in
wins a name collision with an MCP tool and the collision is reported on the Issues portal), filter by the agent's
allow-list, append the `POST_ALLOW_LIST` sources (skills and context-restore tools, deliberately not subject to the
allow-list), then, unless the caller wants the untrimmed candidates, on-demand loading, the relevance selector and
the hard per-turn cap.

To add a source:

1. Put the logic in a resolver class that returns guard-wrapped `ToolCallback`s and **self-gates** (feature flag,
   collaborator present, agent allow-list). An absent optional collaborator must give an empty list, never an
   exception. The existing ones (`AgentKnowledgeToolResolver`, `AgentChannelToolResolver`,
   `AgentIntegrationToolResolver`, `AgentMcpToolResolver`, ...) are the examples; wire optional cross-module
   collaborators as `ObjectProvider` in `ToolResolverConfiguration` so slim contexts stay constructible.
2. Register a bean in `ToolSourceConfiguration`:

   ```java
   @Bean
   @Order(65)                                     // between 60 and 70
   ToolSource myToolSource(MyToolResolver r) {
       return ToolSource.ofResolver("my", ToolSource.Kind.BUILT_IN, r::resolveMyTools);
   }
   ```

   `ToolSource.of(id, kind, applies, fn)` takes a predicate and a function of the `ToolResolutionContext`
   (agent id, session id, step counter, user message, chat client, rigour) when the six-argument resolver shape
   does not fit.
3. `@Order` is the resolution order and is the order the former monolithic provider appended candidates in; it
   decides relevance ties and which tools survive the hard cap. The 32 existing sources are spaced by 10 so a new
   one can take a gap; do not renumber.
4. Test through `ToolResolverTest` (the characterisation suite for the merged list) and add a resolver test beside
   it, like `AgentDataTableToolResolverTest`.

MCP servers, OpenAPI sources and skills (sections 1 to 3) need none of this: they are data that the existing
sources already read. A new `ToolSource` is for a new built-in tool family or a new kind of origin.

## 11. Change the HTTP request pipeline

`HttpRequestExecutor` (`core/agents-browser/.../tools/web/`) is the one engine behind the `http_request` tools and the
`http` workflow node, and it takes URLs from model output or tenant-authored config, so its safety rules are in
separate stage classes (all package-private, mostly static and side-effect free, each unit-tested alone). The
executor only sequences them:

| Stage | Class | Owns |
|---|---|---|
| Validate | `HttpRequestValidation` | method, clamps, header names and values, body encoding, caller trust class, default headers; runs before any secret or host lookup |
| Resolve secrets | `HttpSecretResolution` | `{{secret.NAME}}` bound to the destination host, one-pass workflow templating, one generic "not available" message |
| Host policy | `HttpHostPolicy` | SSRF guard, operator and agent host allow-list (before DNS), numeric-host refusal, https-only, origin comparison |
| Send | `HttpSendStage` | the single network call over a pinned-DNS client (no proxy, redirects off, response read capped) |
| Redirect | `HttpRedirectStage` | bounded hop count, per-hop re-validation, dropping credential and secret-bearing headers on a cross-origin hop |
| Read and redact | `HttpResponseReader` | binary summary, clipping with a marker, safe-mode content-type and cookie rules, `SecretRedactor` on everything returned |
| (isolated) | `X402PaymentAttempt` | one x402 payment attempt on a 402; tenant vault key only, never for untrusted callers |

The stages share no interface; the pipeline is the executor's method sequence plus the hop loop (which stays in
the executor next to the `send` seam). To add a rule, put it in the stage that owns that decision, add a test for
that stage, and keep the invariants the class javadoc names: nothing is sent before validation and host policy
pass, a secret resolves only for its allowed hosts, a redirect never carries credentials to another origin, and
every string heading back to the model passes the redactor. `HttpRequestExecutorSafeModeTest` and the
stage-level tests are the regression net.

## 12. Add an LLM call sink

`LlmMetricsAdvisor` (`core/chat/.../chat/`) wraps every model call. When a call ends it builds one immutable
`LlmCallObservation` (provider, model, attribution, tokens, cache and reasoning accounting, estimated cost,
failure, latency, trace span) through `LlmCallObserver`, and hands it to every `LlmCallSink`
(`LlmCallSink.java`: `void onCall(LlmCallObservation)`). The built-in sinks, in dispatch order:

| Sink | Does |
|---|---|
| `GenAiSpanRecorder` | tags the OTel GenAI span |
| `LlmCallLogSink` | writes the durable `llm_call_log` row |
| `MicrometerLlmMetrics` | business meters |
| `CacheTokenMetrics` | cache token meters |

Streaming terminal signals go through `StreamFinalizer`, which calls the same observer.

The contract is in `LlmCallObserver`: an exception while building the observation or inside any sink is logged
and swallowed, so observability can never fail (or mis-classify as failed) the call it observes; sinks run on
whichever thread delivered the terminal signal, inside the call's captured tenant scope (so
`TenantContext.current()` is the originating call's tenant, rule 12 in AGENTS.md). Derive nothing from raw
request objects in a sink; read the observation, which is the single place cost and token maths happen.

To add one: write a class implementing `LlmCallSink` in package `com.agenticai.core.chat`, then add it to the
`List.of(...)` in `LlmMetricsAdvisor.rebuildObserver()` (the sinks are built there, not discovered as beans).
`LlmMetricsAdvisor.withSinks(...)` is the test seam, and `LlmCallSinkIsolationTest` shows how to assert that a
throwing sink does not affect the others or the call.

## 13. Record more of a turn in the turn journal

The turn journal ([ADR-0020](architecture/ADR-0020-turn-journal.md), bet 1) is an append-only, hash-chained
record of what an agent turn did. It is **off by default** (`app.turn-journal.enabled=false`) and only the
streaming fast path of `ChatTurnExecutor` writes it today.

The SPI is `core/agents-turn/.../turnjournal/TurnJournal.java`: `append(agentId, turnId, type, payloadHash, summary)`
(the implementation assigns `seq` and the chain hashes) and `read(agentId, turnId)`. Implementations:
`InMemoryTurnJournal` (tests, replay) and `JdbcTurnJournal` (tenant table `turn_journal_event`).
`TurnJournalConfig` builds the JDBC one and installs it in `TurnJournalGateway`, because `ChatTurnExecutor` is
constructed by hand rather than as a bean. A different store therefore means changing `TurnJournalConfig`, not
just adding a bean.

Call sites never touch the journal directly. They hold a `TurnRecorder` from `TurnJournalGateway.begin(agentId)`,
which is `TurnRecorder.NOOP` when the journal is off, so no flag checks are needed. The recorder captures the
tenant scope on the request thread, re-applies it where it writes, and never throws into the turn. Its methods
are `intake`, `guard`, `modelCall`, `toolCall`, `approval`, `verify`, `commit` and `wrapTools`.

- **Never store payloads.** Pass text only to be hashed (`payloadHash`); `summary` is a short structural string
  that goes through `TurnRedaction` (240 characters at most).
- **To record a stage that already has a method** (for example `approval`, which the approval gate does not call
  yet), call it from that code via the recorder of the turn in flight.
- **To add an event type:** add a value to `TurnEventType`, a method on `TurnRecorder`, and a **new tenant Flyway
  migration** that widens the `turn_journal_event_type_chk` check constraint (V498 restricts `event_type` to the
  seven current names and a released migration is never edited; see rule 3 in AGENTS.md). The hash includes the
  type, so existing chains stay valid.
- **To read or replay:** `GET /api/agents/{agentId}/turns/{turnId}/journal` (needs `PERM_VIEW_AUDIT_LOG` and agent
  VIEWER; works whether or not writing is on), `TurnHashChain.verify` for integrity, `TurnReplay` for replaying a
  turn against a stubbed model. The console reads it at `/agents/turns`
  ([ADR-0021](architecture/ADR-0021-turn-inspector.md)), which also exports a turn as a test fixture.
- Tests: `TurnJournalChainTest`, `TurnReplayTest`.

## Before you open the pull request

- `make gates` (Flyway versions, bean and entity names, secrets) and the
  component tests (`make test-core`, `make test-web`).
- Document new configuration in [configuration.md](configuration.md) and
  `deploy/compose/.env.example`.
- See [CONTRIBUTING.md](../CONTRIBUTING.md) for the rest.
