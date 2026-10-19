// Pure logic for the tuning coach: which 3-5 things to suggest, and where each
// one deep-links. Kept out of CoachDrawer.tsx so it is unit-testable. A card is
// only ever produced from a score the ledger actually reported or from the
// planner's own "if results are wrong" note; with neither, there are no cards.

import { deepLink } from "./logic";
import type { Brief, BriefAgent, BriefBinding, BriefLedger, EvalDimension } from "./types";

export type CoachTarget = "prompt" | "maxTokens" | "tools" | "knowledge";

/** data-tour ids on the real Workbench controls (AgentWorkbench.tsx / CollapsibleSection tourId). */
export const COACH_FIELD_ID: Record<CoachTarget, string> = {
  prompt: "workbench-system-prompt",
  maxTokens: "workbench-max-tokens",
  tools: "workbench-tools",
  knowledge: "workbench-knowledge",
};

/** Which control most plausibly moves a weak score. */
export const DIMENSION_TARGET: Record<EvalDimension, CoachTarget> = {
  helpfulness: "prompt",
  tone: "prompt",
  safety: "prompt",
  accuracy: "knowledge",
  relevance: "tools",
  length: "maxTokens",
};

/** A dimension scoring below this is worth a card. */
export const WEAK_SCORE = 0.75;
export const MIN_CARDS = 3;
export const MAX_CARDS = 5;

export interface CoachCard {
  id: string;
  agentRef: string;
  agentName: string;
  target: CoachTarget;
  /** The weak dimension that produced the card, or null for a narrative-only card. */
  dimension: EvalDimension | null;
  score: number | null;
  /** Accuracy is weak and the agent has nothing to read from: the fix is uploading, not tuning. */
  knowledgeEmpty: boolean;
  /** Planner's "if results are wrong" note for this agent, as written. */
  hint: string | null;
  /** Deep link into the editor; null until the envoy is applied (no entity to open). */
  href: string | null;
}

type BindingsLike = readonly BriefBinding[] | null | undefined;

export function bindingEntityId(bindings: BindingsLike, ref: string): string | null {
  return bindings?.find((b) => b.ref === ref)?.entityId ?? null;
}

function agentKnowledgeEmpty(brief: Brief, agent: BriefAgent): boolean {
  const refs = agent.knowledgeRefs ?? [];
  if (refs.length === 0) return true;
  // Referenced, but every source is still "upload later": nothing to read yet.
  return refs.every((r) => {
    const k = brief.knowledge?.find((x) => x.ref === r);
    return !k || k.sources.every((s) => s.kind === "UPLOAD_LATER");
  });
}

function hrefFor(target: CoachTarget, entityId: string | null, knowledgeEmpty: boolean): string | null {
  if (target === "knowledge" && knowledgeEmpty) return deepLink("/knowledge", "knowledge-toggle");
  if (!entityId) return null;
  return deepLink(`/agents?agentId=${encodeURIComponent(entityId)}`, COACH_FIELD_ID[target]);
}

/**
 * 3-5 coach cards from the ledger's per-agent breakdown, topped up from each
 * agent's narrative.tune when the breakdown gives fewer than three. Weakest
 * score first; one card per (agent, control). A locked or absent ledger gives
 * no cards at all rather than made-up advice.
 */
export function coachCards(brief: Brief, ledger: BriefLedger | null | undefined, bindings?: BindingsLike): CoachCard[] {
  // LOCKED / NOT_APPLIED / NO_EVAL_SET carry no numbers: no advice either.
  if (!ledger || (ledger.state !== "COLLECTING" && ledger.state !== "READY")) return [];
  const cards: CoachCard[] = [];
  const seen = new Set<string>();
  const agentOf = (ref: string) => brief.agents.find((a) => a.ref === ref);
  const hintOf = (ref: string) => brief.narrative.perNode[ref]?.tune?.trim() || null;

  const weak = (ledger.breakdown ?? [])
    .filter((b) => Number.isFinite(b.score) && b.score < WEAK_SCORE && agentOf(b.agentRef))
    .sort((a, b) => a.score - b.score);

  for (const b of weak) {
    if (cards.length >= MAX_CARDS) break;
    const agent = agentOf(b.agentRef)!;
    const target = DIMENSION_TARGET[b.dimension] ?? "prompt";
    const key = `${agent.ref}:${target}`;
    if (seen.has(key)) continue;
    seen.add(key);
    const knowledgeEmpty = target === "knowledge" && agentKnowledgeEmpty(brief, agent);
    cards.push({
      id: `${key}:${b.dimension}`,
      agentRef: agent.ref,
      agentName: agent.name,
      target,
      dimension: b.dimension,
      score: b.score,
      knowledgeEmpty,
      hint: hintOf(agent.ref),
      href: hrefFor(target, bindingEntityId(bindings, agent.ref), knowledgeEmpty),
    });
  }

  if (cards.length < MIN_CARDS) {
    for (const agent of brief.agents) {
      if (cards.length >= MIN_CARDS) break;
      const hint = hintOf(agent.ref);
      const key = `${agent.ref}:prompt`;
      if (!hint || seen.has(key)) continue;
      seen.add(key);
      cards.push({
        id: `${key}:narrative`,
        agentRef: agent.ref,
        agentName: agent.name,
        target: "prompt",
        dimension: null,
        score: null,
        knowledgeEmpty: false,
        hint,
        href: hrefFor("prompt", bindingEntityId(bindings, agent.ref), false),
      });
    }
  }
  return cards;
}
