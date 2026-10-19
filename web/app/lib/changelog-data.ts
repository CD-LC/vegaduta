// Public changelog entries, rendered by /changelog and mirrored by the
// repository-root CHANGELOG.md (Keep a Changelog format). Keep the two in
// step: this file is what the page renders, the markdown is what humans and
// release tooling read. Dates are the day the change shipped to
// the hosted production deployment (or was merged for not-yet-deployed entries - those are
// marked clearly), not the day it was written.

export type ChangelogKind = "added" | "changed" | "fixed" | "security";

export type ChangelogItem = { kind: ChangelogKind; text: string };

export type ChangelogEntry = {
  /** Month-precision release window - honest when no exact deploy date is recorded. */
  released: string;
  title: string;
  items: ChangelogItem[];
};

export const CHANGELOG_ENTRIES: ChangelogEntry[] = [
  {
    released: "2026-10",
    title: "Workflow expressions and pinning, agent teams, Memory page, eval reliability",
    items: [
      {
        kind: "added",
        text: "Workflow expression language: node fields can read the trigger payload and earlier nodes' outputs, with a live preview and a lint panel. Guide at /help/workflow-expressions.",
      },
      {
        kind: "added",
        text: "Workflow data pinning: save a node's output on the draft so later steps can be built and tested without re-running it. Draft runs use pins and are labelled as such; live runs (webhooks, schedules, channels, share links) never do.",
      },
      {
        kind: "added",
        text: "Team / Swarm: a lead agent delegates to two or more member agents in parallel. Depth 1 only, at most 8 branches per call, each branch stopped after 240 seconds. Three presets (research crew, review pair, support triage).",
      },
      {
        kind: "added",
        text: "Memory page: read, edit and delete what your assistants remember about you, including per-project notes.",
      },
      {
        kind: "added",
        text: "Eval reliability: pass^k promotion gate (a case must pass on every repeat), confidence intervals on scores, and paired comparison between runs.",
      },
      {
        kind: "changed",
        text: "Multi-agent delegation now runs under a shared per-run budget by default (2 US dollars, an estimate used as a runaway guard). A run that uses it up stops with a stated reason.",
      },
      {
        kind: "changed",
        text: "Eval datasets, reliability statistics and Agent Lab knockout and consistency runs are open to Free workspaces with smaller run limits.",
      },
      {
        kind: "added",
        text: "Public agent directory at /directory for agents a workspace has chosen to publish.",
      },
    ],
  },
  {
    released: "2026-09",
    title: "Agent Lab and Agent Skills",
    items: [
      {
        kind: "added",
        text: "Agent Lab in the playground: a run graph of each turn (plan, tool calls, guardrail decisions, handoffs, reply), fork-from-a-message with side-by-side comparison, prompt knockout, and a consistency check. API under /api/agents/{id}/lab.",
      },
      {
        kind: "added",
        text: "Agent Skills: SKILL.md packages that agents load only when a request matches. 14 built-in skills, a Skills page with .zip import and export, and agent-drafted skills that stay switched off until a workspace admin enables them.",
      },
    ],
  },
  {
    released: "2026-09",
    title: "Versions, simulation testing, sales nodes and App Studio",
    items: [
      {
        kind: "added",
        text: "Staging and production labels on agent and workflow versions, with restore. Promotion to production can require a passing eval run.",
      },
      {
        kind: "added",
        text: "Simulation testing: bounded multi-turn conversations between an agent and built-in or custom personas, each ending with a stated reason.",
      },
      {
        kind: "added",
        text: "Workflow nodes: bulk email (recipient cap, send-rate limit, unsubscribe and suppression list), CRM lead (writes to a built-in leads list with stages), and voice menu (keypad options on a call). The node catalog is now 21 types.",
      },
      {
        kind: "added",
        text: "Email reply and bounce detection: a reply to outreach is recorded on the lead and moves it to the replied stage.",
      },
      {
        kind: "added",
        text: "App Studio: Figma frame import, GitHub repository import and sync, a bounded self-repair loop on build errors, a database role per project, end-user sign-in for published apps, and disposable apps that archive themselves. Custom domains can be verified but do not serve apps yet.",
      },
      {
        kind: "added",
        text: "Background workflow runs: start a run and poll for its status instead of holding a request open.",
      },
    ],
  },
  {
    released: "2026-09",
    title: "Enterprise hardening: sandboxed MCP servers, outcome pricing, public transparency",
    items: [
      {
        kind: "security",
        text: "Dynamic MCP STDIO servers (npx/uvx packages a tenant admin registers) now spawn inside an isolated mcp-stdio-runner container - no platform secrets, no route to the data tier, non-root, all capabilities dropped, memory and PID limits, and a custom seccomp profile that denies unprivileged user-namespace creation, ptrace and the kernel-attack surface. Previously the package ran as a child of the core JVM holding APP_ENCRYPTION_KEY.",
      },
      {
        kind: "added",
        text: "Outcome-based pricing substrate: billable resolution events with evidence classification, idempotent dedupe keys, per-event price snapshots, dispute handling, and a tenant-facing summary API (GET/POST /api/tenant/resolutions).",
      },
      {
        kind: "added",
        text: "Public status page at /status backed by a new unauthenticated GET /api/public/status endpoint reporting live component health.",
      },
      {
        kind: "added",
        text: "Public API reference at /api-reference covering authentication and the platform's REST surface.",
      },
    ],
  },
  {
    released: "2026-09",
    title: "Evaluation gates and agent quality infrastructure",
    items: [
      {
        kind: "added",
        text: "Agent eval gate: dataset/case/run/judge infrastructure with a per-agent quality gate that can block promotion on eval verdicts (V260).",
      },
      {
        kind: "added",
        text: "Workflow run pause/resume and input requests (V261, V271) so long-running flows can wait on a human answer.",
      },
    ],
  },
  {
    released: "2026-08",
    title: "Marketplace, sharing and first-party plugins",
    items: [
      {
        kind: "added",
        text: "Community template marketplace with star ratings (V251, V264) and a public gallery at /templates.",
      },
      {
        kind: "added",
        text: "First-party plugin catalog (IDE/browser extensions) at /plugins, served by a public GET /api/plugins/catalog.",
      },
      {
        kind: "added",
        text: "Public share links for agents and workflows (/a/<id>, /w/<id>) backed by /api/share/** - read-only snapshots that never leak edit surfaces.",
      },
      {
        kind: "added",
        text: "Tenant SSO / SCIM provisioning (V253) for enterprise identity rollouts.",
      },
    ],
  },
  {
    released: "2026-08",
    title: "Billing hardening",
    items: [
      {
        kind: "added",
        text: "Billing webhook event ledger (V250): provider webhooks are deduplicated on a durable unique key so a redelivery can never double-apply a money event.",
      },
      {
        kind: "added",
        text: "Commerce product feed (V252) and public /api/commerce/feed/** endpoints for storefront-style agent catalogs.",
      },
    ],
  },
  {
    released: "2026-07",
    title: "Channels, handoff and observability",
    items: [
      {
        kind: "added",
        text: "Human handoff for channel sessions (V214): an operator can take a live WhatsApp/Telegram/etc. conversation away from the agent (AGENT / AWAITING_HUMAN / HUMAN states) and release it back.",
      },
      {
        kind: "added",
        text: "Public documentation hubs: /docs plus the /integrations, /ai, /platform and /tutorials libraries, all server-rendered and crawlable.",
      },
      {
        kind: "added",
        text: "Turn-level feedback signals on agent replies: thumbs, star rating, edit, and resolved/unresolved.",
      },
    ],
  },
];
