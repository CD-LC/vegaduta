// THE mapping from a console screen to the Academy lesson(s) that teach it.
//
// This is the single source of truth for every "Learn this" affordance in the
// console. One constant, one file, so adding a screen or re-pointing a lesson
// is a one-line edit and nobody has to hunt for a second copy.
//
// TWO HARD RULES, because a dead link here is worse than no link:
//
//  1. Every `route` MUST be a real page in web/app (…/page.tsx on disk). The
//     Academy enforces the same rule in the other direction — see
//     vegaduta-webapp/test/webapp-links-routes.test.ts, which checks every
//     console path it links to against web/app/<path>/page.tsx.
//  2. Every `lessonId` MUST be a content unit that exists in
//     vegaduta-webapp/src/seed.ts or a sibling src/seed-level-*.ts. A
//     /lesson/<id> for an unseeded id renders the Academy's 404 path, which is
//     a worse experience than omitting the link.
//
// Where a screen has no honest match, it is simply absent.
//   * /billing — renders without AppShell (it doubles as the public pricing
//     page), so it has no console chrome to hang a "Learn this" on. The
//     in-console plans screen /features carries the money lessons instead.
//
// Verified against the seed files and web/app on 2026-10-01: levels 11-22 are
// seeded and live, so the entries below may point at l11-l22 ids freely.
// Level 22 ("Reading the Numbers") is the instruments level — the lesson for
// each metrics surface lives there (reliability panel l22-2, per-turn metrics
// + Request X-ray l22-3, Agent Lab l22-4, workflow metrics l22-5, expressions
// and pins l22-6, usage/spend l22-7, quality screens l22-8).
import { academyLessonUrl } from "./curriculum";

export interface ConsoleLessonLink {
  /** Academy content-unit id, e.g. "l3-3". */
  lessonId: string;
  /** The lesson's title, exactly as seed.ts spells it. */
  title: string;
  /** Which Academy level it sits in — rendered as an "L4" chip. */
  level: number;
}

export interface ConsoleScreenLessons {
  /** Console route, exactly as it appears in the URL bar. */
  route: string;
  /**
   * How `route` is matched against the current pathname:
   *   "exact"  — only this path (used for index screens like /agents, which
   *              must not swallow /agents/templates).
   *   "prefix" — this path and anything under it (used where every child screen
   *              is taught by the same lesson, e.g. /code-intel/**).
   */
  match: "exact" | "prefix";
  /** Human label for the screen, for the /learn index table. */
  label: string;
  /**
   * True when the route is gated by the realm's tenant-admin role. Those pages
   * bounce a non-admin to the console home with no explanation (e.g.
   * admin/channels/page.tsx does `if (!isAdmin) router.replace("/")`), so
   * /learn hides the in-console link for non-admins while still offering the
   * lesson — the same reasoning the Academy applies in the other direction in
   * vegaduta-webapp/src/web-console-links.ts.
   */
  adminOnly?: boolean;
  /**
   * Lessons that teach this screen, most directly-relevant FIRST — the first
   * entry is what a single-button "Learn this" affordance opens.
   */
  lessons: readonly ConsoleLessonLink[];
}

export const CONSOLE_SCREEN_LESSONS: readonly ConsoleScreenLessons[] = [
  {
    route: "/",
    match: "exact",
    label: "Home — your Cognition Cockpit",
    lessons: [
      { lessonId: "l3-1", level: 3, title: "Signing In & Your Cognition Cockpit" },
      { lessonId: "l3-13", level: 3, title: "Getting Started, For Real: The Onboarding Tour" },
      { lessonId: "l3-12", level: 3, title: "Your First Week With VegaDuta" },
    ],
  },
  {
    route: "/home",
    match: "exact",
    label: "Home",
    lessons: [
      { lessonId: "l3-1", level: 3, title: "Signing In & Your Cognition Cockpit" },
      { lessonId: "l3-13", level: 3, title: "Getting Started, For Real: The Onboarding Tour" },
    ],
  },
  {
    route: "/providers",
    match: "prefix",
    label: "Providers",
    lessons: [
      { lessonId: "l3-2", level: 3, title: "Providers: Powering Your Agents" },
      { lessonId: "l5-4", level: 5, title: "Fallback Models — Graceful Degradation for Real" },
      { lessonId: "l5-5", level: 5, title: "Cost Routing — Spending Smart, Automatically" },
    ],
  },
  {
    route: "/ai-models",
    match: "exact",
    label: "AI Model Control",
    lessons: [
      { lessonId: "l5-3", level: 5, title: "Manual Tuning — Temperature, Tokens, and Top-P for Real" },
      { lessonId: "l5-4", level: 5, title: "Fallback Models — Graceful Degradation for Real" },
      { lessonId: "l5-5", level: 5, title: "Cost Routing — Spending Smart, Automatically" },
    ],
  },
  {
    // Exact: /agents is the workbench list. /agents/templates and
    // /agents/quality have their own, better-matched entries below.
    route: "/agents",
    match: "exact",
    label: "Agents",
    lessons: [
      { lessonId: "l3-3", level: 3, title: "Agents: Building One For Real" },
      { lessonId: "l22-2", level: 22, title: "Agent Metrics — Reading the Reliability Panel" },
      { lessonId: "l5-2", level: 5, title: "Reasoning Depth & Autonomy Tier — Two Dials Every Agent Has" },
      { lessonId: "l5-6", level: 5, title: "Collaborators — When One Agent Calls In a Specialist" },
    ],
  },
  {
    route: "/agents/templates",
    match: "prefix",
    label: "Agent templates",
    lessons: [{ lessonId: "l3-5", level: 3, title: "Agent Templates: Don't Start From Blank" }],
  },
  {
    // The quality page is several sections deep, each taught by its own
    // lesson: the per-section learn links point at the same ids listed here
    // (l16-3 datasets, l16-5 gate, l16-4 simulations, l13-3 the statistics
    // layer's noise floor, l16-9 the Solutions tab's envoys).
    route: "/agents/quality",
    match: "prefix",
    label: "Agent quality",
    lessons: [
      { lessonId: "l22-8", level: 22, title: "The Quality Screens — Datasets, Simulations and the Eval Gate" },
      { lessonId: "l16-3", level: 16, title: "Golden Datasets and Regression Runs" },
      { lessonId: "l16-5", level: 16, title: "The Eval Gate — Promotion Policies for Prompts" },
      { lessonId: "l16-4", level: 16, title: "Simulations and Personas — Practice Before the Phones Ring" },
      { lessonId: "l13-3", level: 13, title: "The Noise Floor, and the A/B You Should Refuse to Call" },
      { lessonId: "l16-9", level: 16, title: "Earned Autonomy — Briefs and the End of the Level" },
      { lessonId: "l5-15", level: 5, title: "Evals & Regression — Turning One Good Run Into a Test" },
      { lessonId: "l5-10", level: 5, title: "Reliability Panel — Watching an Agent's Real Numbers" },
    ],
  },
  {
    route: "/agents/learning",
    match: "prefix",
    label: "Agent learning",
    lessons: [
      { lessonId: "l16-1", level: 16, title: "The Learning Loop for Real — Policies, Arms and Rewards" },
      { lessonId: "l22-2", level: 22, title: "Agent Metrics — Reading the Reliability Panel" },
    ],
  },
  {
    route: "/playground",
    match: "prefix",
    label: "Playground",
    lessons: [
      { lessonId: "l22-3", level: 22, title: "Per-Turn Numbers and the Request X-ray" },
      { lessonId: "l22-4", level: 22, title: "The Agent Lab — Run Graph, Prompt Knockout, Consistency" },
      { lessonId: "l3-4", level: 3, title: "Playground: Testing Before You Trust It" },
      { lessonId: "l1-8", level: 1, title: "The Agent Loop: From Your Message to a Real Action" },
    ],
  },
  {
    // Prefix covers the two children the route carries: the Build Center
    // itself (/build — brief in, envoy out) and the envoy detail at
    // /build/[id]. Both are taught first by the briefs lesson — the applied
    // envoy IS a brief on the trust ladder — with the App Builder lesson as
    // the second offer because the same surface also scaffolds apps.
    route: "/build",
    match: "prefix",
    label: "Build Center & envoys",
    lessons: [
      { lessonId: "l16-9", level: 16, title: "Earned Autonomy — Briefs and the End of the Level" },
      { lessonId: "l18-3", level: 18, title: "The App Builder — a Screen That Ships Software" },
    ],
  },
  {
    // The App Builder's GitHub connect/manage screen — pure app-builder
    // territory, so the app-builder lesson leads alone.
    route: "/build/github",
    match: "prefix",
    label: "App Builder — GitHub",
    lessons: [
      { lessonId: "l18-3", level: 18, title: "The App Builder — a Screen That Ships Software" },
    ],
  },
  {
    route: "/tools",
    match: "exact",
    label: "Tools & MCP",
    lessons: [
      { lessonId: "l3-6", level: 3, title: "Tools & MCP" },
      { lessonId: "l4-1", level: 4, title: "MCP: The Universal Adapter for AI Tools" },
      { lessonId: "l5-13", level: 5, title: "When Tools Fail — Timeouts, Retries, Malformed Output, Safe Stops" },
    ],
  },
  {
    route: "/tools/code",
    match: "prefix",
    label: "Custom code tools",
    lessons: [
      { lessonId: "l4-2", level: 4, title: "Build Your Own Tool — No Server Required" },
      { lessonId: "l5-9", level: 5, title: "E2B — A Real Sandboxed Code Interpreter" },
    ],
  },
  {
    route: "/tools/servers",
    match: "prefix",
    label: "MCP servers",
    lessons: [
      { lessonId: "l4-1", level: 4, title: "MCP: The Universal Adapter for AI Tools" },
      { lessonId: "l7-6", level: 7, title: "The Model Context Protocol — Tools In, Agents Out" },
      { lessonId: "l7-14", level: 7, title: "MCP Security — stdio sandboxing, tool poisoning, the pentest" },
    ],
  },
  {
    route: "/knowledge",
    match: "prefix",
    label: "Knowledge bases",
    lessons: [
      { lessonId: "l3-7", level: 3, title: "Knowledge Bases" },
      { lessonId: "l2-11", level: 2, title: "Retrieval-Augmented Generation (RAG)" },
      { lessonId: "l7-2", level: 7, title: "RAG, Part 1 — From Raw Document to Searchable Chunks" },
      { lessonId: "l7-3", level: 7, title: "RAG, Part 2 — Retrieval, Reranking, and Grounded Answers" },
    ],
  },
  {
    // Prefix: the canvas (/workflows/[id]) and the run list/detail under it are
    // all taught by the same three lessons. The run-trace lesson is listed
    // first because "I am staring at a run and it failed" is the common reason
    // someone reaches for help on these screens.
    route: "/workflows",
    match: "prefix",
    label: "Workflows",
    lessons: [
      { lessonId: "l3-8", level: 3, title: "Workflows: Wiring It All Together" },
      { lessonId: "l4-7", level: 4, title: "Workflows Deep Dive: All 13 Building Blocks" },
      { lessonId: "l5-11", level: 5, title: "Reading a Workflow Run — Debugging From the Real Trace" },
      { lessonId: "l22-5", level: 22, title: "Workflow Metrics — Eval Badges and the Run History" },
      { lessonId: "l22-6", level: 22, title: "Expressions and Pins — Moving Real Data Between Nodes" },
    ],
  },
  {
    route: "/quick-task",
    match: "prefix",
    label: "Quick Task",
    lessons: [
      { lessonId: "l3-9", level: 3, title: "Quick Task: When You Don't Need a Whole Agent" },
      { lessonId: "l4-6", level: 4, title: "Quick Task: One Question, Real Dispatch" },
    ],
  },
  {
    route: "/admin/channels",
    match: "prefix",
    label: "Channels & device pairing",
    adminOnly: true,
    lessons: [
      { lessonId: "l3-10", level: 3, title: "Channels: Reaching People Where They Are" },
      { lessonId: "l4-4", level: 4, title: "WhatsApp: Putting an Agent in Someone's Pocket" },
      { lessonId: "l4-5", level: 4, title: "Social Channels: Meeting People Everywhere Else" },
      { lessonId: "l3-11", level: 3, title: "Devices: Your Phone in the Loop" },
    ],
  },
  {
    // /devices is a redirect shim into /admin/channels?channel=mobile-sdk
    // (see devices/page.tsx). Kept as its own entry so a bookmark that still
    // hits /devices resolves a lesson during the render before the replace().
    route: "/devices",
    match: "exact",
    label: "Devices (pair a phone)",
    lessons: [
      { lessonId: "l3-11", level: 3, title: "Devices: Your Phone in the Loop" },
      { lessonId: "l4-4", level: 4, title: "WhatsApp: Putting an Agent in Someone's Pocket" },
    ],
  },
  {
    // The tenant-admin Approvals panel lives on /admin/maintenance (direct
    // feedback #554 moved it there); /admin/approvals serves the same
    // lessons to the delegated MANAGE_APPROVALS holder — see the entry
    // below.
    route: "/admin/maintenance",
    match: "prefix",
    label: "Platform maintenance & approvals",
    adminOnly: true,
    lessons: [
      { lessonId: "l4-8", level: 4, title: "Trust & Approvals: Where Real Guardrails Actually Live" },
      { lessonId: "l7-8", level: 7, title: "Guardrails & Approvals — How the Platform Says 'Not So Fast'" },
    ],
  },
  {
    // No longer a redirect shim: admin/approvals/page.tsx is a real page
    // again (granular RBAC, restored 2026-09-23), gated by
    // useRequireScope("MANAGE_APPROVALS") for holders who can't reach the
    // tenant-admin-only /admin/maintenance.
    route: "/admin/approvals",
    match: "exact",
    label: "Approvals",
    adminOnly: true,
    lessons: [
      { lessonId: "l4-8", level: 4, title: "Trust & Approvals: Where Real Guardrails Actually Live" },
      { lessonId: "l7-8", level: 7, title: "Guardrails & Approvals — How the Platform Says 'Not So Fast'" },
    ],
  },
  {
    route: "/admin/mcp-servers",
    match: "prefix",
    label: "MCP server admin",
    adminOnly: true,
    lessons: [
      { lessonId: "l7-14", level: 7, title: "MCP Security — stdio sandboxing, tool poisoning, the pentest" },
      { lessonId: "l4-1", level: 4, title: "MCP: The Universal Adapter for AI Tools" },
    ],
  },
  {
    route: "/admin/audit",
    match: "prefix",
    label: "Audit log",
    adminOnly: true,
    lessons: [{ lessonId: "l9-11", level: 9, title: "Governance in Code — Guardrails, Approvals, Audit, Entitlements" }],
  },
  {
    // The live tail is a sibling of /admin/audit, not a child — its own entry
    // so the prefix rule's route-boundary matching does not miss it.
    route: "/admin/audit-tail",
    match: "exact",
    label: "Audit live tail",
    adminOnly: true,
    lessons: [{ lessonId: "l9-11", level: 9, title: "Governance in Code — Guardrails, Approvals, Audit, Entitlements" }],
  },
  {
    route: "/code-intel",
    match: "prefix",
    label: "Code Intelligence",
    lessons: [
      { lessonId: "l6-1", level: 6, title: "Meet Code Intel — RAG for Your Own Codebase" },
      { lessonId: "l6-2", level: 6, title: "Register & Index Your First Repository" },
      { lessonId: "l6-4", level: 6, title: "The Code Graph — Callers, Callees, and Blast Radius" },
      { lessonId: "l6-7", level: 6, title: "Governance — Set the Guardrails Before You Trust It" },
    ],
  },
  {
    route: "/jobs",
    match: "prefix",
    label: "Jobs & schedules",
    lessons: [{ lessonId: "l16-7", level: 16, title: "Jobs and Schedules — Agents That Wake Themselves Up" }],
  },
  {
    route: "/files",
    match: "prefix",
    label: "Files",
    lessons: [{ lessonId: "l18-6", level: 18, title: "Studio, Clips and Files — the Artifact Layer" }],
  },
  {
    route: "/shared-with-me",
    match: "prefix",
    label: "Shared with me",
    lessons: [{ lessonId: "l18-7", level: 18, title: "Shared With Me — Grants, Not Copies" }],
  },
  {
    route: "/templates",
    match: "prefix",
    label: "Community templates",
    lessons: [{ lessonId: "l18-2", level: 18, title: "The Marketplace — Install Someone Else's Workspace" }],
  },
  {
    // The decision layer (/capabilities/decision) is where a Jev provider is
    // picked and tuned — the Jev lesson is the one that teaches it.
    route: "/capabilities",
    match: "prefix",
    label: "Decision providers",
    lessons: [{ lessonId: "l18-5", level: 18, title: "Jev — the Fast Brain Under the Slow One" }],
  },
  {
    route: "/skills",
    match: "prefix",
    label: "Skills",
    lessons: [{ lessonId: "l18-1", level: 18, title: "Skills — Packaged Expertise, Loaded on Demand" }],
  },
  {
    route: "/data-usage",
    match: "prefix",
    label: "Data usage",
    lessons: [
      { lessonId: "l22-7", level: 22, title: "Usage, Spend and Data — the Plan Meters" },
      { lessonId: "l17-4", level: 17, title: "Usage, Quotas and Spend Protection" },
    ],
  },
  {
    route: "/admin/contacts",
    match: "prefix",
    label: "Contacts",
    adminOnly: true,
    lessons: [{ lessonId: "l17-1", level: 17, title: "Contacts — the Directory Behind Every Channel" }],
  },
  {
    route: "/admin/inbox",
    match: "prefix",
    label: "Operator inbox",
    adminOnly: true,
    lessons: [{ lessonId: "l16-8", level: 16, title: "The Operator Inbox — When a Human Is the Tool" }],
  },
  {
    route: "/admin/leads",
    match: "prefix",
    label: "Leads",
    adminOnly: true,
    lessons: [{ lessonId: "l17-2", level: 17, title: "Leads and the CRM Node — the Pipeline in the Product" }],
  },
  {
    route: "/admin/issues",
    match: "prefix",
    label: "Issues",
    adminOnly: true,
    lessons: [{ lessonId: "l17-7", level: 17, title: "Audit, Issues and Data Privacy — the Evidence Surfaces" }],
  },
  {
    route: "/admin/access",
    match: "prefix",
    label: "Access control",
    adminOnly: true,
    lessons: [{ lessonId: "l17-5", level: 17, title: "Users, Roles and Access — Who Can See What" }],
  },
  {
    route: "/admin/users",
    match: "prefix",
    label: "Users",
    adminOnly: true,
    lessons: [{ lessonId: "l17-5", level: 17, title: "Users, Roles and Access — Who Can See What" }],
  },
  {
    route: "/admin/integrations",
    match: "prefix",
    label: "Integrations",
    adminOnly: true,
    lessons: [{ lessonId: "l4-10", level: 4, title: "Google Workspace as Agent Superpowers" }],
  },
  {
    route: "/admin/input-requests",
    match: "prefix",
    label: "Input requests",
    adminOnly: true,
    lessons: [{ lessonId: "l16-8", level: 16, title: "The Operator Inbox — When a Human Is the Tool" }],
  },
  {
    route: "/admin/data-privacy",
    match: "prefix",
    label: "Data privacy",
    adminOnly: true,
    lessons: [{ lessonId: "l17-7", level: 17, title: "Audit, Issues and Data Privacy — the Evidence Surfaces" }],
  },
  {
    route: "/admin/payments",
    match: "prefix",
    label: "Payments",
    adminOnly: true,
    lessons: [{ lessonId: "l4-9", level: 4, title: "Money Matters: Plans, Upgrades, and Real Payments" }],
  },
  {
    route: "/admin/guardrails",
    match: "prefix",
    label: "Guardrails",
    adminOnly: true,
    lessons: [
      { lessonId: "l4-8", level: 4, title: "Trust & Approvals: Where Real Guardrails Actually Live" },
      { lessonId: "l7-8", level: 7, title: "Guardrails & Approvals — How the Platform Says 'Not So Fast'" },
    ],
  },
  {
    route: "/admin/branding",
    match: "prefix",
    label: "Branding",
    adminOnly: true,
    lessons: [{ lessonId: "l17-8", level: 17, title: "Branding, Tenants and the Shape of Your Workspace" }],
  },
  {
    route: "/secrets",
    match: "prefix",
    label: "Secrets vault",
    lessons: [{ lessonId: "l17-3", level: 17, title: "The Secrets Vault — Where Keys Actually Live" }],
  },
  {
    route: "/usage",
    match: "prefix",
    label: "Usage & cost",
    lessons: [
      { lessonId: "l22-7", level: 22, title: "Usage, Spend and Data — the Plan Meters" },
      { lessonId: "l17-4", level: 17, title: "Usage, Quotas and Spend Protection" },
      { lessonId: "l5-5", level: 5, title: "Cost Routing — Spending Smart, Automatically" },
      { lessonId: "l8-7", level: 8, title: "Cost, Latency & Reliability — Engineering Agents at Scale" },
    ],
  },
  {
    route: "/features",
    match: "exact",
    label: "Plans & features",
    lessons: [
      { lessonId: "l4-9", level: 4, title: "Money Matters: Plans, Upgrades, and Real Payments" },
      { lessonId: "l3-14", level: 3, title: "Hitting a Real Wall: Plan Limits & the Upgrade Prompt" },
    ],
  },
  {
    route: "/edge",
    match: "prefix",
    label: "Edge / on-device AI",
    lessons: [{ lessonId: "l5-14", level: 5, title: "On-Device Models — When the Agent Runs in the Browser" }],
  },
  {
    // The local code assistant works on-device against a folder the browser
    // was granted — taught by the edge/on-device lesson. Prefix matching is
    // safe here: it tests "/code/" boundaries, so /code-intel still resolves
    // to its own (longer) entry above.
    route: "/code",
    match: "prefix",
    label: "Local code assistant",
    lessons: [{ lessonId: "l18-4", level: 18, title: "Edge and On-Device Models — Agents Without the Cloud Round-Trip" }],
  },
  {
    route: "/studio",
    match: "prefix",
    label: "Studio",
    lessons: [{ lessonId: "l8-10", level: 8, title: "Voice, Vision & Multimodal Agents" }],
  },
  {
    // MANAGE_AGENTS-gated. The per-agent trust surface: the directory
    // publish panel + signed manifest and the delegation grants card with
    // its grant wizard, Mint token and Revoke. The L17 lesson leads; the
    // L18 directory lesson is second because this page is where an agent
    // gets published to /directory, and the L14 handoff lesson teaches the
    // scoped-authority model behind every grant row.
    route: "/admin/agent-trust",
    match: "prefix",
    label: "Agent trust & identity",
    adminOnly: true,
    lessons: [
      { lessonId: "l17-10", level: 17, title: "Agent Trust and Delegation — Grants, Tokens and the Signed Manifest" },
      { lessonId: "l18-11", level: 18, title: "The Public Agent Directory — Know-Your-Agent in the Open" },
      { lessonId: "l14-5", level: 14, title: "Every Handoff Is an Authority Grant — Name the Scope" },
    ],
  },
  {
    // MANAGE_AGENTS-gated. Wallets, AP2 mandates and the x402 offers this
    // agent sells — the L17 commerce lesson leads, with the L4
    // agents-that-pay lesson as the gentler on-ramp.
    route: "/admin/commerce",
    match: "prefix",
    label: "Agent commerce",
    adminOnly: true,
    lessons: [
      { lessonId: "l17-11", level: 17, title: "Agent Commerce — Wallets, AP2 Mandates and x402 Paid Endpoints" },
      { lessonId: "l4-13", level: 4, title: "Agents That Pay — x402, AP2, ACP and UPI Payment Links" },
    ],
  },
  {
    // The PUBLIC directory (route is in lib/publicRoutes.ts) — deliberately
    // not adminOnly: a signed-out visitor can read it, and the lesson
    // explains the signed manifest an entry's verify affordance surfaces.
    route: "/directory",
    match: "exact",
    label: "Agent directory (public)",
    lessons: [
      { lessonId: "l18-11", level: 18, title: "The Public Agent Directory — Know-Your-Agent in the Open" },
    ],
  },
  {
    // Exact: /admin is the dashboard index only. Every /admin/<child> has
    // its own entry, and prefix matching would only ever lose to them
    // anyway — exact keeps that explicit.
    route: "/admin",
    match: "exact",
    label: "Admin dashboard",
    adminOnly: true,
    lessons: [
      { lessonId: "l22-1", level: 22, title: "The Numbers Map — Where Every Metric Lives" },
      { lessonId: "l4-8", level: 4, title: "Trust & Approvals: Where Real Guardrails Actually Live" },
    ],
  },
  {
    // MANAGE_BILLING-gated — a different scope from the MANAGE_AGENTS admin
    // screens above, so it gets its own entry rather than riding /admin.
    route: "/admin/billing",
    match: "prefix",
    label: "Workspace billing",
    adminOnly: true,
    lessons: [
      { lessonId: "l4-9", level: 4, title: "Money Matters: Plans, Upgrades, and Real Payments" },
      { lessonId: "l17-4", level: 17, title: "Usage, Quotas and Spend Protection" },
    ],
  },
  {
    route: "/memory",
    match: "prefix",
    label: "Memory",
    lessons: [{ lessonId: "l1-4", level: 1, title: "Memory" }],
  },
  {
    route: "/clips",
    match: "prefix",
    label: "Clip Studio",
    lessons: [{ lessonId: "l18-6", level: 18, title: "Studio, Clips and Files — the Artifact Layer" }],
  },
  {
    route: "/tools/credentials",
    match: "prefix",
    label: "Tool credentials",
    lessons: [
      { lessonId: "l4-1", level: 4, title: "MCP: The Universal Adapter for AI Tools" },
      { lessonId: "l17-3", level: 17, title: "The Secrets Vault — Where Keys Actually Live" },
    ],
  },
  {
    // Prefix covers /help/docs and the anchored doc sections the same page
    // carries; the how-the-Academy-works lesson is the honest offer for a
    // reader who is already reading documentation.
    route: "/help",
    match: "prefix",
    label: "Help & docs",
    lessons: [{ lessonId: "l0-0", level: 0, title: "How This Academy Works — Levels, Labs, Quizzes, XP, Tiers" }],
  },
];

/**
 * Lessons for the screen at `pathname`, or an empty array when nothing is
 * mapped. Longest matching route wins, so a prefix entry can never shadow a
 * more specific one (e.g. /tools/servers beats /tools even if /tools were
 * later widened to "prefix").
 *
 * `pathname` is expected to be a bare path (what next/navigation's
 * usePathname() returns) — no query, no hash.
 */
export function lessonsForConsolePath(pathname: string): ConsoleScreenLessons | null {
  // Normalise a trailing slash so "/agents/" matches the "/agents" exact entry.
  const path = pathname.length > 1 && pathname.endsWith("/") ? pathname.slice(0, -1) : pathname;

  let best: ConsoleScreenLessons | null = null;
  for (const entry of CONSOLE_SCREEN_LESSONS) {
    const hit =
      entry.match === "exact"
        ? path === entry.route
        : path === entry.route || path.startsWith(`${entry.route}/`);
    if (!hit) continue;
    if (!best || entry.route.length > best.route.length) best = entry;
  }
  return best;
}

/**
 * The primary lesson for a screen — what a one-button "Learn this" opens.
 * Returns null when the screen has no mapping, which is the signal for the
 * affordance to render nothing at all rather than a disabled control.
 */
export function primaryLessonForConsolePath(
  pathname: string
): { link: ConsoleLessonLink; href: string; screenLabel: string } | null {
  const screen = lessonsForConsolePath(pathname);
  const link = screen?.lessons[0];
  if (!screen || !link) return null;
  return { link, href: academyLessonUrl(link.lessonId, screen.route), screenLabel: screen.label };
}
