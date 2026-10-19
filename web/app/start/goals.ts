// The goal catalogue for /start (the guided "first agent in a few minutes"
// flow). Pure data - no React, no network - so the unit tests and the
// dashboard "Start here" card can import it for free.
//
// Why six goals and not the 70-template gallery: the research behind this lane
// is that the top reason people give for not using an agent is "not sure how
// to use it effectively". A gallery answers "what could an agent be about";
// this answers "what do I want to get done", and maps each answer onto ONE
// existing template or ONE existing page. Nothing here invents a capability:
//
//   - agent goals point at an AGENT_TEMPLATES key (components/agentTemplates.ts)
//     and are created with the same helpers the workbench uses.
//   - route goals open a page that already exists and explains itself.
//
// All user-visible copy lives in en.json under "startFlow" (goal.<id>.*); this
// file holds only ids, keys and routes.

export const GOAL_IDS = ["learn", "research", "analyze", "automate", "draft", "build"] as const;

export type GoalId = (typeof GOAL_IDS)[number];

export type GoalKind = "agent" | "route";

export interface GoalDef {
  readonly id: GoalId;
  readonly kind: GoalKind;
  /** Agent goals: the AGENT_TEMPLATES key the one-click create uses. */
  readonly templateKey?: string;
  /** Route goals: the existing page the goal opens. */
  readonly href?: string;
  /**
   * Route goals whose page may be switched off on a deployment: the agent
   * template used instead when it is (the personal tutor is behind a server
   * flag). Absent = the route is always offered.
   */
  readonly fallbackTemplateKey?: string;
}

export const GOALS: readonly GoalDef[] = [
  // The personal tutor builds a whole multi-lesson course. It is behind a
  // server flag and says so itself when off, so /start probes it first and
  // falls back to the one-conversation tutor template when it is not there.
  { id: "learn", kind: "route", href: "/learn/tutor", fallbackTemplateKey: "explain-it-simply-tutor" },
  // Needs web search (built-in deep research OR the Brave connector) to look
  // things up; works from the person's own material and says so without it.
  { id: "research", kind: "agent", templateKey: "research-assistant" },
  // Needs Code execution (a tenant E2B credential) to compute anything.
  { id: "analyze", kind: "agent", templateKey: "data-analyst" },
  // The describe-it composer on /workflows turns one sentence into a draft.
  { id: "automate", kind: "route", href: "/workflows" },
  // Open on every plan and needs no setup: notes in, summary + actions + a
  // follow-up reply draft out.
  { id: "draft", kind: "agent", templateKey: "client-call-to-actions" },
  { id: "build", kind: "route", href: "/build" },
];

export function isGoalId(value: unknown): value is GoalId {
  return typeof value === "string" && (GOAL_IDS as readonly string[]).includes(value);
}

export function findGoal(id: string | null | undefined): GoalDef | null {
  if (!isGoalId(id)) return null;
  return GOALS.find((g) => g.id === id) ?? null;
}

/** Every template key any goal can create - the test holds these to real gallery entries. */
export function goalTemplateKeys(): string[] {
  const keys: string[] = [];
  for (const goal of GOALS) {
    if (goal.templateKey) keys.push(goal.templateKey);
    if (goal.fallbackTemplateKey) keys.push(goal.fallbackTemplateKey);
  }
  return keys;
}
