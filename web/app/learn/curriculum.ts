// The curriculum map that backs /learn — the console's own index of the
// VegaDūta Academy.
//
// WHY this is a static constant in `web/` rather than a fetch:
// the Academy is a separate deployable (vegaduta-webapp-prototype) on its own
// origin, and its curriculum endpoint (GET /api/curriculum/tree in
// vegaduta-webapp/src/server.ts) is session-scoped to an Academy *learner* and
// sends no CORS headers. A browser in the console therefore cannot read it.
// Until core proxies it (see learnProgress.ts for the exact proposed
// endpoint), the level index lives here, and every lesson the map links to is
// resolved at click time against the Academy's own origin.
//
// PROVENANCE — every string below is transcribed, not invented:
//   * Level titles: the `LEVEL_TITLES` map in
//     vegaduta-webapp/src/learning-portal/content.ts (getCurriculumTree),
//     character-for-character, with ONE deliberate deviation: the brand is
//     spelled "VegaDuta" there and "VegaDūta" here, because the console
//     renders the macron everywhere else (see help/page.tsx's VegaD&#363;ta).
//   * Lesson counts: counted off the `level:` fields of the content units in
//     vegaduta-webapp/src/seed.ts + src/seed-level-*.ts on 2026-10-03 —
//     15/12/14/15/14/17/8/14/16/14/14 (levels 0-10) +
//     13/12/13/14/14/9/11/11/7/8/14/9 (levels 11-22) = 288.
//     Re-derive with:
//       grep -o '^    level: [0-9]*' src/seed*.ts | sort | uniq -c
//     The Academy's ui/src/lib/curriculum-totals.ts LESSONS_PER_LEVEL is the
//     guarded mirror these counts must agree with.
//   * The one-line `outcome` is a summary written from that level's real
//     lesson titles in the seed files — the Academy has no pitch/blurb field.
import { academyUrl } from "../lib/academyUrl";

/** Whether a level is teachable in the Academy today, or still on the agenda. */
export type LevelStatus = "live" | "in-design";

export interface CurriculumLevel {
  /** Academy level number — the `level` field on its content units. */
  level: number;
  title: string;
  /** One sentence: what this level makes a learner able to do. */
  outcome: string;
  status: LevelStatus;
  /** Number of lessons in the Academy today (0 for an in-design level). */
  lessonCount: number;
  /**
   * Lesson titles. Only populated for in-design levels, deliberately: a live
   * level's lessons are authored in, and owned by, the Academy's seed.ts, and
   * duplicating 135 titles here would drift the first time anyone renames one.
   * Live levels link out to the Academy's Journey Map instead.
   */
  lessons?: readonly string[];
}

/**
 * Levels 11-15 are numbered 11-15 in the Academy's level space but the agenda
 * presents them as a self-contained five-part advanced track ("Level 1 — The
 * Spring AI Machine" … "Level 5 — Self-Improvement and Running It for
 * Money"). We keep the Academy-space number as `level` and strip the
 * track-local prefix from the title so the map reads as one sequence.
 */
export const ADVANCED_TRACK_LEVELS = [11, 12, 13, 14, 15] as const;

export const CURRICULUM: readonly CurriculumLevel[] = [
  {
    level: 0,
    title: "Welcome to the World of Agents",
    outcome:
      "Say what AI, generative AI, an AI agent and agentic AI each actually are — in your own words — and pick the assistant you want to build first.",
    status: "live",
    lessonCount: 15,
  },
  {
    level: 1,
    title: "Thinking Like an Agent",
    outcome:
      "Follow one message all the way through the agent loop — goal, reasoning, plan, memory, action, feedback — and explain why a model sees tokens rather than magic.",
    status: "live",
    lessonCount: 12,
  },
  {
    level: 2,
    title: "Agentic AI Concepts",
    outcome:
      "Use the vocabulary precisely: single vs multi-agent, roles, collaboration, orchestration, tool use, memory, chain-of-thought and ReAct, RAG — and how anyone would evaluate the result.",
    status: "live",
    lessonCount: 14,
  },
  {
    level: 3,
    title: "Learn the VegaDūta Platform",
    outcome:
      "Drive this console without help: providers, agents, playground, templates, tools and MCP, knowledge bases, workflows, quick tasks, channels and devices.",
    status: "live",
    lessonCount: 15,
  },
  {
    level: 4,
    title: "The Builder's Gauntlet — Real-World Integrations",
    outcome:
      "Connect an agent to the world outside the console — MCP and your own tools, live databases, WhatsApp and social channels, Google Workspace, approvals and real payments — and ship a capstone assistant.",
    status: "live",
    lessonCount: 14,
  },
  {
    level: 5,
    title: "Mission Control — Under the Hood of Production Agents",
    outcome:
      "Operate an agent that people depend on: set its reasoning and autonomy dials, add fallbacks and cost routing, survive failing tools, read the reliability panel and a run trace, and turn one good run into a regression test.",
    status: "live",
    lessonCount: 17,
  },
  {
    level: 6,
    title: "Code Intelligence — Your Codebase, Understood",
    outcome:
      "Index a repository, search it by meaning, read the call graph's blast radius, insist on grounded answers, and take a change through the rework loop end to end.",
    status: "live",
    lessonCount: 8,
  },
  {
    level: 7,
    title: "The Architect's Track — Inside the VegaDūta Engine",
    outcome:
      "Draw this platform's architecture from memory — the RAG pipeline, the knowledge sidecar, MCP, the tool-calling loop, guardrails and approvals, multi-tenancy, the LLM gateway, the workflow engine, eventing — and trace a single request through all of it.",
    status: "live",
    lessonCount: 14,
  },
  {
    level: 8,
    title: "Agentic AI Mastery — From Practitioner to Expert",
    outcome:
      "Hold a specialist conversation about prompt and context engineering, evaluation, red-teaming, memory architectures, orchestration patterns, cost and latency, multimodal agents and governance — then teach it to someone else.",
    status: "live",
    lessonCount: 16,
  },
  {
    level: 9,
    title: "The Platform Engineer's Track — Inside the Engine Room",
    outcome:
      "Explain the running system at the code level: the modular monolith, Keycloak identity, schema-per-tenant Postgres, Redis, the event bus, ingestion, vector search, the gateway, governance, and the observability stack.",
    status: "live",
    lessonCount: 14,
  },
  {
    level: 10,
    title: "Full-Stack Agentic Engineering",
    outcome:
      "Build the product around the agent: streaming over SSE and WebSockets, AG-UI, webhooks done right, event-driven backbones, MCP in both directions, A2A handoffs, tenant isolation, and the standards a design review will ask about.",
    status: "live",
    lessonCount: 14,
  },
  {
    level: 11,
    title: "The Spring AI Machine",
    outcome:
      "Build, wire and observe a tool-using, RAG-backed agent in Spring Boot and explain every layer it passes through — and read the framework's own docs critically enough to know where it stops.",
    status: "live",
    lessonCount: 13,
  },
  {
    level: 12,
    title: "Agents That Work (and Stop)",
    outcome:
      "Choose a topology on evidence rather than fashion, engineer the context budget deliberately, design tools an agent can actually use, and build an agent that knows when to stop.",
    status: "live",
    lessonCount: 12,
  },
  {
    level: 13,
    title: "Measuring Agents Honestly",
    outcome:
      "Tell a real improvement from noise, calibrate a judge against humans, locate which step of a run broke, and read a leaderboard sceptically enough to be useful in a design review.",
    status: "live",
    lessonCount: 13,
  },
  {
    level: 14,
    title: "Trust, Safety and Multi-Tenancy",
    outcome:
      "Threat-model an agent system, explain why containment beats filtering, implement scoped delegation and permission-aware retrieval, and answer the compliance questions an enterprise buyer will actually ask.",
    status: "live",
    lessonCount: 14,
  },
  {
    level: 15,
    title: "Self-Improvement and Running It for Money",
    outcome:
      "Make an agent measurably better without touching weights, and defend the cost, durability and governance decisions that make an agent platform a business rather than a demo.",
    status: "live",
    lessonCount: 14,
  },
  {
    level: 16,
    title: "Operating Agents in Production",
    outcome:
      "Run agents that other people depend on: the learning loop, golden datasets and regression runs, simulations, the eval gate, prompt versions and experiments, jobs and schedules, the operator inbox and earned-autonomy briefs.",
    status: "live",
    lessonCount: 9,
  },
  {
    level: 17,
    title: "Running the Workspace — Admin, CRM & the Money Screens",
    outcome:
      "Operate the workspace itself: contacts and leads, the secrets vault, usage and quotas, users and access, SSO/SCIM, the audit and privacy surfaces, branding, and fraud operations.",
    status: "live",
    lessonCount: 11,
  },
  {
    level: 18,
    title: "Beyond the Console — the Platform's Other Doors",
    outcome:
      "Use every non-console door: skills, the marketplace, the App Builder, edge and on-device models, Jev, studio artifacts, sharing, the API surface, voice and the first-party MCP servers.",
    status: "live",
    lessonCount: 11,
  },
  {
    level: 19,
    title: "Frontier II — What the Research Is Actually Shipping",
    outcome:
      "Follow the frontier as it ships: reflexion, plan-and-execute search, computer-use agents, the RFT/RLHF pipeline end to end, and deep-research architectures.",
    status: "live",
    lessonCount: 7,
  },
  {
    level: 20,
    title: "Ship It, Run It, Wake Up at 3am — Operating VegaDūta",
    outcome:
      "Operate the platform itself: the service map, identity plumbing, compose staging, Helm and Terraform, migrations, zero-downtime deploys, and status and incident response.",
    status: "live",
    lessonCount: 8,
  },
  {
    level: 21,
    title: "Build Agents in Code",
    outcome:
      "Write every moving part of an agent by hand in Python and TypeScript — the API call, messages and streaming, tools, the loop, memory, RAG, an MCP server, orchestration, evals, guardrails, cost and shipping.",
    status: "live",
    lessonCount: 14,
  },
  {
    level: 22,
    title: "Reading the Numbers — Metrics, X-Rays and the Agent Lab",
    outcome:
      "Read every instrument the console shows: the agent Reliability panel, per-turn metrics and the Request X-ray, the Agent Lab's three instruments, workflow eval badges and run history, expressions and pins, usage and spend, and the quality screens — then run a whole monitoring round.",
    status: "live",
    lessonCount: 9,
  },
] as const;

/** Lessons teachable in the Academy today (288 as of 2026-10-03). */
export const LIVE_LESSON_COUNT = CURRICULUM.filter((l) => l.status === "live").reduce(
  (sum, l) => sum + l.lessonCount,
  0
);

/** Lessons on the agenda but not yet seeded (0 as of 2026-10-01 — the whole
 * listed curriculum is live). */
export const IN_DESIGN_LESSON_COUNT = CURRICULUM.filter((l) => l.status === "in-design").reduce(
  (sum, l) => sum + l.lessonCount,
  0
);

// ---------------------------------------------------------------------------
// Academy link builders
//
// Mirror image of the Academy's own consoleLink() (vegaduta-webapp/src/
// config.ts), which stamps `utm_source=academy` + `academy_return` on every
// link INTO this console. Going the other way we stamp `utm_source=console`
// and `console_from=<console path>`, so Academy-side analytics can see which
// screen sent a learner and a future "back to the console" chip has the path
// it needs. Nothing in the Academy requires these params today — they are
// inert extra query, not a contract we are asserting exists.
//
// The base URL ALWAYS comes from lib/academyUrl.ts (NEXT_PUBLIC_ACADEMY_URL,
// else inferred from the current hostname). Never hardcode a host here: the
// platform forbids any browser-facing URL pointing at localhost, and
// NEXT_PUBLIC_* is baked at build time, so academyUrl() is the single place
// that decision is allowed to live.
// ---------------------------------------------------------------------------

function withConsoleAttribution(path: string, fromConsolePath?: string): string {
  const params = new URLSearchParams({ utm_source: "console" });
  if (fromConsolePath) params.set("console_from", fromConsolePath);
  return `${academyUrl()}${path}?${params.toString()}`;
}

/**
 * Deep link to one Academy lesson. `lessonId` is an Academy content-unit id
 * such as "l3-2" — the Academy route is /lesson/:id (ui/src/App.tsx).
 *
 * Caveat worth knowing before you add a call site: /lesson/:id sits inside the
 * Academy's authenticated PortalLayout, and its api() helper hard-redirects a
 * 401 to /auth/login, whose callback lands on /home — it does not preserve the
 * originally requested lesson. So a learner with no Academy session follows
 * this link, signs in, and arrives at the Academy home rather than the lesson.
 * That is why every visible affordance says "opens the Academy" rather than
 * promising the exact lesson screen.
 */
export function academyLessonUrl(lessonId: string, fromConsolePath?: string): string {
  return withConsoleAttribution(`/lesson/${encodeURIComponent(lessonId)}`, fromConsolePath);
}

/** The Academy's Journey Map — the guided path across every level. */
export function academyJourneyUrl(fromConsolePath?: string): string {
  return withConsoleAttribution("/journey", fromConsolePath);
}

/** The Academy's Concept Library — every concept, browsable regardless of
 * progress (GET /api/concepts is explicitly not lock-gated). */
export function academyConceptsUrl(fromConsolePath?: string): string {
  return withConsoleAttribution("/concepts", fromConsolePath);
}
