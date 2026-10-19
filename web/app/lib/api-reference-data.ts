// Public API reference data, rendered by /api-reference. Endpoint paths are
// the platform's real REST surface (the @RequestMapping roots under core/);
// group descriptions are written for an external integrator, not copied from
// internal docs. Keep this file honest: only list routes that actually exist,
// and mark the auth level each route family really enforces.
import { publicApiBase } from "./env";

export type ApiAuth = "Bearer JWT" | "Public" | "Webhook signature" | "Device token";

export type ApiEndpoint = {
  methods: string;
  path: string;
  auth: ApiAuth;
  description: string;
};

export type ApiGroup = {
  id: string;
  name: string;
  blurb: string;
  endpoints: ApiEndpoint[];
};

// The deployment's public API origin (runtime public config); local-dev default.
export const API_BASE_PUBLIC = publicApiBase();

export const API_GROUPS: ApiGroup[] = [
  {
    id: "agents",
    name: "Agents",
    blurb:
      "Create and operate agents: personas, provider/model settings, sessions, versions, schedules, triggers and share links.",
    endpoints: [
      { methods: "GET POST", path: "/api/agents", auth: "Bearer JWT", description: "List and create agents." },
      {
        methods: "GET PUT DELETE",
        path: "/api/agents/{id}",
        auth: "Bearer JWT",
        description: "Read, update or retire one agent.",
      },
      {
        methods: "GET",
        path: "/api/agents/{id}/versions",
        auth: "Bearer JWT",
        description: "Immutable version history; every save is a new version.",
      },
      {
        methods: "GET POST",
        path: "/api/agents/{id}/triggers",
        auth: "Bearer JWT",
        description: "Event/webhook triggers that start this agent.",
      },
      {
        methods: "GET PUT",
        path: "/api/agents/{id}/schedule",
        auth: "Bearer JWT",
        description: "Cron-style schedule for autonomous runs.",
      },
      {
        methods: "POST",
        path: "/api/agents/{id}/share",
        auth: "Bearer JWT",
        description: "Mint a read-only public share link (/a/<id>).",
      },
      {
        methods: "GET",
        path: "/api/agents/{id}/guardrails",
        auth: "Bearer JWT",
        description: "Guardrail rules bound to this agent.",
      },
      {
        methods: "GET POST",
        path: "/api/agents/{id}/quality/*",
        auth: "Bearer JWT",
        description: "Eval datasets, runs and the quality gate verdict.",
      },
    ],
  },
  {
    id: "sessions",
    name: "Sessions & feedback",
    blurb:
      "Chat sessions, assisted replies and the turn-level feedback stream that powers RL exports and the resolution meter.",
    endpoints: [
      { methods: "GET POST", path: "/api/session", auth: "Bearer JWT", description: "Open and list chat sessions." },
      {
        methods: "POST",
        path: "/api/assist",
        auth: "Bearer JWT",
        description: "One-shot assist completions outside a session.",
      },
      {
        methods: "POST",
        path: "/api/rl",
        auth: "Bearer JWT",
        description: "Record a feedback signal (THUMBS_UP/DOWN, RATING, EDIT, RESOLVED, UNRESOLVED) for a turn.",
      },
      {
        methods: "GET",
        path: "/api/agents/{id}/sessions/{sessionId}/llm-usage",
        auth: "Bearer JWT",
        description: "Token/cost rollup for one session.",
      },
    ],
  },
  {
    id: "workflows",
    name: "Workflows",
    blurb: "Multi-step deterministic automations an agent can trigger; runs can pause for human input and resume.",
    endpoints: [
      { methods: "GET POST", path: "/api/workflows", auth: "Bearer JWT", description: "List and create workflows." },
      {
        methods: "GET PUT",
        path: "/api/workflows/{id}",
        auth: "Bearer JWT",
        description: "Read or update a workflow definition.",
      },
      {
        methods: "GET POST",
        path: "/api/workflows/input-requests",
        auth: "Bearer JWT",
        description: "Pending human-input questions and their answers.",
      },
      {
        methods: "POST",
        path: "/api/workflows/{id}/share",
        auth: "Bearer JWT",
        description: "Mint a read-only public share link (/w/<id>).",
      },
    ],
  },
  {
    id: "channels",
    name: "Channels (omnichannel)",
    blurb:
      "Connect WhatsApp, Telegram, Slack, Teams, Discord, email, voice and social channels; route inbound conversations to agents with human handoff.",
    endpoints: [
      {
        methods: "GET",
        path: "/api/channels",
        auth: "Bearer JWT",
        description: "Configured channels and their state.",
      },
      {
        methods: "GET",
        path: "/api/channels/status",
        auth: "Bearer JWT",
        description: "Per-channel connectivity health.",
      },
      {
        methods: "GET PUT",
        path: "/api/channels/routing",
        auth: "Bearer JWT",
        description: "Which agent answers which channel/session.",
      },
      {
        methods: "POST",
        path: "/api/channels/pairing",
        auth: "Bearer JWT",
        description: "Pair an end-user session to an agent (pairing codes).",
      },
      {
        methods: "POST",
        path: "/api/channels/{channel}/credential",
        auth: "Bearer JWT",
        description: "Store channel credentials (encrypted at rest).",
      },
      {
        methods: "GET POST",
        path: "/api/handoff",
        auth: "Bearer JWT",
        description: "Operator inbox: take over or release a live conversation (AGENT/AWAITING_HUMAN/HUMAN).",
      },
    ],
  },
  {
    id: "mcp",
    name: "MCP & tool ecosystem",
    blurb:
      "Model Context Protocol server registrations, the tool registry, and the agent-facing A2A surface. Dynamic STDIO registrations run sandboxed (see the security changelog).",
    endpoints: [
      {
        methods: "GET POST DELETE",
        path: "/api/mcp/registrations",
        auth: "Bearer JWT",
        description: "Dynamic MCP server registrations (reviewed before use).",
      },
      {
        methods: "GET",
        path: "/api/mcp/registry",
        auth: "Bearer JWT",
        description: "Tools exposed to this tenant's agents.",
      },
      {
        methods: "GET POST",
        path: "/api/mcpserver/grants",
        auth: "Bearer JWT",
        description: "Grant an agent access to an MCP server.",
      },
      {
        methods: "GET",
        path: "/.well-known/agent-card.json",
        auth: "Public",
        description: "A2A agent card describing this deployment's agents.",
      },
      { methods: "POST", path: "/api/a2a", auth: "Bearer JWT", description: "Agent-to-agent JSON-RPC endpoint." },
      {
        methods: "POST",
        path: "/api/a2a/rest/message:send",
        auth: "Bearer JWT",
        description: "A2A HTTP+JSON binding: send a message. Also message:stream (SSE), GET tasks/{id}, POST tasks/{id}:cancel; per-agent base /api/agents/{agentId}/a2a/rest.",
      },
      {
        methods: "GET",
        path: "/api/platform/security/outbound-policy",
        auth: "Bearer JWT",
        description: "Platform-admin only. Read-only view of the outbound egress allow-list and A2A client and server settings.",
      },
      {
        methods: "GET POST",
        path: "/api/tools/*",
        auth: "Bearer JWT",
        description: "Tool classifications, OpenAPI-imported tools, script tools, code tools.",
      },
    ],
  },
  {
    id: "knowledge",
    name: "Knowledge",
    blurb: "Document collections, connectors and ingestion jobs behind retrieval-augmented answers.",
    endpoints: [
      {
        methods: "GET POST",
        path: "/api/knowledge/collections",
        auth: "Bearer JWT",
        description: "Collections of ingested documents.",
      },
      {
        methods: "GET POST",
        path: "/api/knowledge/connectors",
        auth: "Bearer JWT",
        description: "External sources (drives, buckets, sites) feeding collections.",
      },
      {
        methods: "GET",
        path: "/api/knowledge/jobs",
        auth: "Bearer JWT",
        description: "Ingestion job state and history.",
      },
    ],
  },
  {
    id: "billing",
    name: "Billing & resolutions",
    blurb:
      "Subscriptions, outcome-based resolution metering, and provider webhooks. Resolution events are the billable unit of outcome-priced plans.",
    endpoints: [
      {
        methods: "GET",
        path: "/api/tenant/subscription",
        auth: "Bearer JWT",
        description: "Current plan, seats and billing state.",
      },
      {
        methods: "GET",
        path: "/api/tenant/resolutions/summary",
        auth: "Bearer JWT",
        description: "Outcome counts + billable total for a period (PERM_MANAGE_BILLING).",
      },
      {
        methods: "GET",
        path: "/api/tenant/resolutions",
        auth: "Bearer JWT",
        description: "The underlying resolution events, newest first.",
      },
      {
        methods: "POST",
        path: "/api/tenant/resolutions",
        auth: "Bearer JWT",
        description: "Explicitly record a resolution the sweep cannot see (idempotent on dedupeKey).",
      },
      {
        methods: "POST",
        path: "/api/tenant/resolutions/{id}/dispute",
        auth: "Bearer JWT",
        description: "Contest a billable event; the row stays, billing flips off.",
      },
      {
        methods: "POST",
        path: "/api/billing/webhook/*",
        auth: "Webhook signature",
        description: "Provider (Razorpay/Stripe) delivery endpoints; deduped on a durable ledger.",
      },
      {
        methods: "POST",
        path: "/api/budget/top-up",
        auth: "Bearer JWT",
        description: "Add prepaid budget for usage-billed features.",
      },
    ],
  },
  {
    id: "sharing",
    name: "Public sharing",
    blurb: "Read-only public snapshots behind /a/<id> and /w/<id>. A leaked share id buys a viewer, never an editor.",
    endpoints: [
      {
        methods: "GET",
        path: "/api/share/agents/{shareId}",
        auth: "Public",
        description: "The shared agent snapshot rendered at /a/<id>.",
      },
      {
        methods: "GET",
        path: "/api/share/workflows/{shareId}",
        auth: "Public",
        description: "The shared workflow snapshot rendered at /w/<id>.",
      },
      {
        methods: "GET",
        path: "/api/templates/gallery",
        auth: "Public",
        description: "Community template gallery (submit/moderate stays authenticated).",
      },
      {
        methods: "GET",
        path: "/api/plugins/catalog",
        auth: "Public",
        description: "First-party plugin catalog behind /plugins.",
      },
      {
        methods: "GET",
        path: "/api/commerce/feed/*",
        auth: "Public",
        description: "Storefront-style product feed for commerce agents.",
      },
    ],
  },
  {
    id: "devices",
    name: "Devices & edge",
    blurb: "Claim-and-poll device pairing and the edge gateway that runs turns close to the user.",
    endpoints: [
      {
        methods: "POST",
        path: "/api/devices/claim",
        auth: "Public",
        description: "Claim a device pairing code (no session exists yet - same posture as /api/signup).",
      },
      {
        methods: "POST",
        path: "/api/devices/poll",
        auth: "Device token",
        description: "Long-poll for commands addressed to this device.",
      },
      {
        methods: "POST",
        path: "/api/devices/commands/{id}/result",
        auth: "Device token",
        description: "Report a command result back.",
      },
      {
        methods: "POST",
        path: "/api/edge/turns",
        auth: "Bearer JWT",
        description: "Execute a turn through the edge gateway.",
      },
    ],
  },
  {
    id: "platform",
    name: "Operations & administration",
    blurb: "Tenant administration, platform-level operations, and the public liveness/status probes.",
    endpoints: [
      {
        methods: "GET",
        path: "/api/public/status",
        auth: "Public",
        description: "Component-level platform status feeding /status (names + status codes only).",
      },
      {
        methods: "GET",
        path: "/api/system/status/live",
        auth: "Public",
        description: 'Bare liveness probe: {"status":"UP"}.',
      },
      {
        methods: "GET",
        path: "/api/system/status",
        auth: "Bearer JWT",
        description: "Full internal dashboard status (pool, JVM, MCP inventory).",
      },
      { methods: "GET PUT", path: "/api/tenant/settings", auth: "Bearer JWT", description: "Tenant-scoped settings." },
      {
        methods: "GET POST",
        path: "/api/tenant/sso",
        auth: "Bearer JWT",
        description: "SSO/SCIM enterprise identity configuration.",
      },
      {
        methods: "*",
        path: "/api/platform/*",
        auth: "Bearer JWT",
        description:
          "Platform-operator surface (tenants, plan limits, abuse monitor, MCP review) - requires the platform-admin role.",
      },
      {
        methods: "POST",
        path: "/webhook/*",
        auth: "Webhook signature",
        description: "Inbound channel webhooks (WhatsApp/Telegram/Slack/…), verified per provider.",
      },
    ],
  },
];

export const API_AUTH_NOTES = [
  "All authenticated endpoints take an OAuth2/OIDC Bearer token issued by the platform's Keycloak realm (<auth-host>/realms/agentic-ai). Interactive sign-in uses PKCE; service integrations use the client-credentials flow on a confidential client.",
  "Tenant isolation is enforced below the API: every tenant reads and writes its own database schema. No endpoint accepts a tenant id from the caller to cross tenants.",
  "Rate limits are enforced per-IP on public endpoints and per-tenant on authenticated ones; 429 responses carry a Retry-After header.",
];
