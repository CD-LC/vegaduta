// Pure view-model for the trust ladder. Promotion is only offered when core
// says eligible AND every evidence line holds AND the target is exactly the
// next rung: the UI never rounds a "nearly" up. Demotion is always one rung
// down and never conditional on evidence.

import { nextRung } from "./logic";
import type { Rung, TrustEvent, TrustEvidenceLine, TrustProposal } from "./types";
import { RUNGS } from "./types";

export function prevRung(rung: Rung): Rung | null {
  const i = RUNGS.indexOf(rung);
  return i > 0 ? RUNGS[i - 1] : null;
}

export type StepState = "past" | "current" | "future";

export function ladderSteps(current: Rung): { rung: Rung; state: StepState }[] {
  const at = RUNGS.indexOf(current);
  return RUNGS.map((rung, i) => ({ rung, state: i < at ? "past" : i === at ? "current" : "future" }));
}

export type PromoteGate = "needsAdmin" | "notEligible" | "wrongTarget" | null;

export interface ProposalView {
  /** none = core made no proposal (see reason); pending = evidence shown, not yet enough. */
  state: "none" | "pending" | "ready";
  target: Rung | null;
  /** Core sends no separate reason; the evidence lines explain it (plan, applied, source lines when a precondition fails). */
  reason: string | null;
  evidence: TrustEvidenceLine[];
  metCount: number;
  /** Plain sentences on what the promotion changes in the running envoy. */
  effects: string[];
  /** Why Promote is disabled, or null when it can be pressed. */
  gate: PromoteGate;
}

export function proposalView(proposal: TrustProposal | null | undefined, current: Rung, isAdmin: boolean): ProposalView {
  if (!proposal || proposal.targetRung == null) {
    return { state: "none", target: null, reason: null, evidence: proposal?.evidence ?? [], metCount: 0, effects: [], gate: "notEligible" };
  }
  const evidence = proposal.evidence;
  const metCount = evidence.filter((e) => e.met).length;
  const allMet = evidence.length > 0 && metCount === evidence.length;
  const target = proposal.targetRung;
  const ready = proposal.eligible && allMet && target === nextRung(current);
  let gate: PromoteGate = null;
  if (!ready) gate = target !== nextRung(current) ? "wrongTarget" : "notEligible";
  else if (!isAdmin) gate = "needsAdmin";
  return {
    state: ready ? "ready" : "pending",
    target,
    reason: null,
    evidence,
    metCount,
    effects: proposal.effects ?? [],
    gate,
  };
}

export interface DemoteView {
  target: Rung | null;
  gate: "needsAdmin" | "atBottom" | null;
}

export function demoteView(current: Rung, isAdmin: boolean): DemoteView {
  const target = prevRung(current);
  return { target, gate: target == null ? "atBottom" : !isAdmin ? "needsAdmin" : null };
}

export interface EventView {
  id: string;
  kind: TrustEvent["kind"];
  from: Rung | null;
  to: Rung | null;
  /** Core acted on its own (strike or gate breach), not a person. */
  auto: boolean;
  reason: string | null;
  evidence: TrustEvidenceLine[];
  at: string;
  version?: number | null;
}

/** core stores evidence as `{ lines: EvidenceLine[], note?, snapshot? }`. */
export function trustEventEvidence(evidence: unknown): { lines: TrustEvidenceLine[]; note: string | null } {
  const e = (evidence && typeof evidence === "object" ? evidence : {}) as { lines?: unknown; note?: unknown };
  const lines = Array.isArray(e.lines)
    ? (e.lines as any[])
        .filter((l) => l && typeof l.key === "string" && typeof l.label === "string")
        .map((l) => ({ key: l.key, label: l.label, met: l.met === true, detail: typeof l.detail === "string" ? l.detail : null }))
    : [];
  return { lines, note: typeof e.note === "string" && e.note.trim() ? e.note.trim() : null };
}

/** Newest first. An automatic demotion is flagged so it is never mistaken for a person's choice. */
export function eventViews(events: readonly TrustEvent[] | null | undefined): EventView[] {
  return (events ?? [])
    .map((e, i) => ({ e, i }))
    .sort((a, b) => Date.parse(b.e.at) - Date.parse(a.e.at) || b.i - a.i)
    .map(({ e, i }) => {
      const { lines, note } = trustEventEvidence(e.evidence);
      const trigger = lines.find((l) => l.key === "trigger");
      return {
        id: `${e.at}-${i}`,
        kind: e.kind,
        from: e.fromRung,
        to: e.toRung,
        auto: e.actor === "system",
        reason: note ?? trigger?.detail?.trim() ?? null,
        evidence: lines,
        at: e.at,
        version: e.version,
      };
    });
}
