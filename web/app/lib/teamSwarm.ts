// Team / Swarm: pure logic behind the "Team" card in the agent editor.
//
// A "swarm" is not a separate object. It is an agent with 2 or more
// collaborators: the backend then adds `delegate_parallel` plus one
// `ask_agent_<slug>` tool per collaborator. This module only plans and applies
// "preset" teams on top of the existing create-agent and set-collaborators
// endpoints. Nothing here talks to the network except through injected deps.

/** Limits the backend enforces on delegation (facts, except the token estimate). */
export const SWARM_LIMITS = {
  maxParallelBranches: 8,
  branchTimeoutSeconds: 240,
  defaultRunBudgetUsd: 2,
  estTokensPerBranch: 8000,
} as const;

export interface TeamMemberSpec {
  /** Role name, appended to the leader's name: "<leader> · <role>". */
  role: string;
  systemPrompt: string;
}

export interface TeamPreset {
  id: "research" | "review" | "triage" | "modernization";
  members: TeamMemberSpec[];
}

export const TEAM_PRESETS: TeamPreset[] = [
  {
    id: "research",
    members: [
      {
        role: "Researcher",
        systemPrompt:
          "You are the Researcher on a small team. Given a question, gather the relevant facts, name the sources you rely on, and clearly separate what is established from what is uncertain. Reply with findings only, not a final write-up.",
      },
      {
        role: "Critic",
        systemPrompt:
          "You are the Critic on a small team. Given a draft or a set of findings, look for gaps, unsupported claims, contradictions and missing viewpoints. Reply with a short, prioritised list of concrete problems and how to fix each.",
      },
      {
        role: "Writer",
        systemPrompt:
          "You are the Writer on a small team. Given findings and critique, produce a clear, well-structured final text for the intended reader. Do not add facts that were not supplied to you.",
      },
    ],
  },
  {
    id: "review",
    members: [
      {
        role: "Author",
        systemPrompt:
          "You are the Author on a two-person team. Produce the requested piece of work as a complete first version, and state any assumptions you made.",
      },
      {
        role: "Reviewer",
        systemPrompt:
          "You are the Reviewer on a two-person team. Review the work you are given for correctness, clarity and risk. Reply with specific, actionable feedback, ordered from most to least important.",
      },
    ],
  },
  {
    id: "triage",
    members: [
      {
        role: "Classifier",
        systemPrompt:
          "You are the Classifier on a support team. Read an incoming request and reply with its category, urgency, and the one or two key details needed to handle it.",
      },
      {
        role: "Resolver",
        systemPrompt:
          "You are the Resolver on a support team. Given a classified request, draft the answer or the exact steps that resolve it. If it cannot be resolved with what you know, say so plainly.",
      },
      {
        role: "Escalator",
        systemPrompt:
          "You are the Escalator on a support team. Given a request that could not be resolved, write a short hand-off note for a human: what was asked, what was tried, and what is still needed.",
      },
    ],
  },
  {
    // Legacy modernization crew: one member reads the old system, one designs
    // the target, one argues for what must not change. The leader merges.
    id: "modernization",
    members: [
      {
        role: "Archaeologist",
        systemPrompt:
          "You are the Archaeologist on a modernization team. Given legacy code, schemas, JCL, ABAP, stored procedures or logs, reconstruct what the system actually does: entry points, data flows, the business rules hidden in conditionals and magic numbers, and the external systems it touches. Mark each finding 'from the code' or 'my guess'. Reply with findings and open questions only - no redesign.",
      },
      {
        role: "Architect",
        systemPrompt:
          "You are the Target Architect on a modernization team. Given the Archaeologist's findings, propose the modern design: target language or platform, service or module boundaries, data ownership and how the data moves, the first slice to carve out and the rollback plan if it fails. Prefer the smallest step that delivers value over the purest architecture, and keep an explicit 'leave it as is for now' option.",
      },
      {
        role: "Risk officer",
        systemPrompt:
          "You are the Risk Officer on a modernization team. Given the findings and the proposed design, list what could break: numeric precision and rounding, batch dependencies and cut-offs, regulatory or audit obligations, double-posting on rerun, performance cliffs, and anything that needs a human approval or a parallel-run period. For each risk give a concrete test or control that would catch it. Reply as a prioritised list; do not redesign the solution.",
      },
    ],
  },
];

export interface AgentRef {
  id: string;
  name: string;
}

export function memberName(leaderName: string, role: string): string {
  return `${leaderName.trim()} · ${role}`;
}

const norm = (s: string) => s.trim().toLowerCase();

export interface PresetPlan {
  /** Members that do not exist yet and will be created. */
  toCreate: { name: string; systemPrompt: string }[];
  /** Members that already exist by name and will be reused. */
  toReuse: AgentRef[];
  /** Final collaborator ids: existing first, then reused, never duplicated. */
  collaboratorIds: string[];
}

/**
 * Work out what applying `preset` would do, without doing it.
 * - an agent whose name matches (case-insensitive) is reused, never duplicated
 * - the leader is never added to its own team
 * - existing collaborators are always kept
 */
export function planPreset(
  preset: TeamPreset,
  leader: AgentRef,
  allAgents: AgentRef[],
  existingCollaboratorIds: string[]
): PresetPlan {
  const byName = new Map<string, AgentRef>();
  for (const a of allAgents) {
    if (a.id !== leader.id && !byName.has(norm(a.name))) byName.set(norm(a.name), a);
  }
  const ids: string[] = [];
  const push = (id: string) => {
    if (id !== leader.id && !ids.includes(id)) ids.push(id);
  };
  existingCollaboratorIds.forEach(push);
  const toCreate: PresetPlan["toCreate"] = [];
  const toReuse: AgentRef[] = [];
  for (const m of preset.members) {
    const name = memberName(leader.name, m.role);
    const found = byName.get(norm(name));
    if (found) {
      toReuse.push(found);
      push(found.id);
    } else {
      toCreate.push({ name, systemPrompt: m.systemPrompt });
    }
  }
  return { toCreate, toReuse, collaboratorIds: ids };
}

export interface ApplyDeps {
  createAgent: (payload: Record<string, unknown>) => Promise<{ id: string; name: string }>;
  setCollaborators: (agentId: string, ids: string[]) => Promise<unknown>;
}

export interface ApplyResult {
  ok: boolean;
  /** Agents created during this call (they exist even if the run failed later). */
  created: AgentRef[];
  /** Creations that failed, with the server's message. */
  failed: { name: string; error: string }[];
  /** Set when the final collaborator update failed. */
  linkError?: string;
  /** Collaborator ids written, when the link step succeeded. */
  collaboratorIds?: string[];
}

const errText = (e: unknown) => (e instanceof Error && e.message ? e.message : "Request failed");

/**
 * Create the missing members, then link everyone. Safe by construction:
 * - members are created one at a time; the first failure stops the run
 * - on any failure the collaborator list is NOT touched, so the team the user
 *   already had is exactly as before. Agents already created are reported, and
 *   running the preset again reuses them by name instead of duplicating.
 * - the link call always carries the existing collaborators plus the new ones
 */
export async function applyPreset(
  plan: PresetPlan,
  leaderId: string,
  basePayload: Record<string, unknown>,
  deps: ApplyDeps
): Promise<ApplyResult> {
  const created: AgentRef[] = [];
  const ids = [...plan.collaboratorIds];
  for (const m of plan.toCreate) {
    try {
      const r = await deps.createAgent({ ...basePayload, name: m.name, systemPrompt: m.systemPrompt });
      created.push({ id: r.id, name: r.name ?? m.name });
      if (r.id !== leaderId && !ids.includes(r.id)) ids.push(r.id);
    } catch (e) {
      return { ok: false, created, failed: [{ name: m.name, error: errText(e) }] };
    }
  }
  try {
    await deps.setCollaborators(leaderId, ids);
  } catch (e) {
    return { ok: false, created, failed: [], linkError: errText(e) };
  }
  return { ok: true, created, failed: [], collaboratorIds: ids };
}

export interface SwarmLimitsHint {
  members: number;
  active: boolean;
  parallelBranches: number;
  /** Members beyond the per-call parallel cap. */
  overCap: number;
  estTokensPerCall: number;
}

/** Client-side facts only: caps and a token estimate. Not a price. */
export function swarmLimitsHint(memberCount: number): SwarmLimitsHint {
  const branches = Math.min(memberCount, SWARM_LIMITS.maxParallelBranches);
  return {
    members: memberCount,
    active: memberCount >= 2,
    parallelBranches: branches,
    overCap: Math.max(0, memberCount - SWARM_LIMITS.maxParallelBranches),
    estTokensPerCall: branches * SWARM_LIMITS.estTokensPerBranch,
  };
}
