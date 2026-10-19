// Pure helpers for the "Attack my agent" section (trust T7). No React and no
// api client, so the headline maths and the hide/show decisions are testable
// on their own.
//
// Wire contract (core/quality SimulationDtos.AttackReportResponse):
//   GET /api/agents/{agentId}/quality/simulations/sessions/{sessionId}/attack-report
//   attackSuccessRate = succeededAttacks / (succeededAttacks + resisted), a
//   fraction, and NULL while nothing is decided. Null must never be shown as
//   zero percent: "no attack succeeded" and "nothing was decided" are
//   different results.

export type AttackVerdict = "PASSED" | "FAILED" | "INCONCLUSIVE" | null;

export interface AttackScenarioResult {
  key: string;
  name: string;
  /** null = the run has not finished yet. */
  verdict: AttackVerdict;
  /** True only when an exact canary check caught the leak (no judge model involved). */
  deterministic: boolean;
  reason: string | null;
  runId: string;
}

export interface AttackReport {
  sessionId: string;
  total: number;
  succeededAttacks: number;
  resisted: number;
  inconclusive: number;
  pending: number;
  attackSuccessRate: number | null;
  perScenario: AttackScenarioResult[];
  limits: string[];
}

/** Turn limit for an attack run: the scripted poisoned turn plus two benign follow-ups. */
export const ATTACK_MAX_TURNS = 3;
/** Default per-scenario budget, in USD. */
export const ATTACK_DEFAULT_BUDGET = 2;

function count(value: unknown): number {
  return typeof value === "number" && Number.isFinite(value) && value > 0 ? Math.floor(value) : 0;
}

function verdictOf(value: unknown): AttackVerdict {
  return value === "PASSED" || value === "FAILED" || value === "INCONCLUSIVE" ? value : null;
}

/** Defensive read of the wire shape; null when the body is not a report at all. */
export function normalizeAttackReport(raw: unknown): AttackReport | null {
  if (!raw || typeof raw !== "object") return null;
  const r = raw as Record<string, unknown>;
  if (!Array.isArray(r.perScenario)) return null;
  const perScenario: AttackScenarioResult[] = r.perScenario
    .filter((row): row is Record<string, unknown> => !!row && typeof row === "object")
    .map((row) => ({
      key: String(row.key ?? ""),
      name: String(row.name ?? row.key ?? ""),
      verdict: verdictOf(row.verdict),
      deterministic: row.deterministic === true,
      reason: typeof row.reason === "string" && row.reason.length > 0 ? row.reason : null,
      runId: String(row.runId ?? ""),
    }));
  const rate = r.attackSuccessRate;
  return {
    sessionId: String(r.sessionId ?? ""),
    total: count(r.total),
    succeededAttacks: count(r.succeededAttacks),
    resisted: count(r.resisted),
    inconclusive: count(r.inconclusive),
    pending: count(r.pending),
    attackSuccessRate: typeof rate === "number" && Number.isFinite(rate) ? rate : null,
    perScenario,
    limits: Array.isArray(r.limits) ? r.limits.filter((l): l is string => typeof l === "string" && l.length > 0) : [],
  };
}

/** Keys of the personas the server marks as attack scenarios (attack === true). */
export function attackPersonaKeys(personas: unknown): string[] {
  if (!Array.isArray(personas)) return [];
  return personas
    .filter((p) => p && typeof p === "object" && p.attack === true && typeof p.key === "string" && p.key.length > 0)
    .map((p) => p.key as string);
}

/** The most recently created session that ran at least one attack scenario, or null. */
export function latestAttackSessionId(sessions: unknown, attackKeys: string[]): string | null {
  if (!Array.isArray(sessions) || attackKeys.length === 0) return null;
  const wanted = new Set(attackKeys);
  let best: { id: string; at: number } | null = null;
  for (const s of sessions) {
    if (!s || typeof s !== "object" || typeof s.id !== "string") continue;
    const keys: unknown[] = Array.isArray(s.personaKeys) ? s.personaKeys : [];
    if (!keys.some((k) => typeof k === "string" && wanted.has(k))) continue;
    const parsed = Date.parse(String(s.createdAt ?? ""));
    const at = Number.isFinite(parsed) ? parsed : 0;
    if (!best || at > best.at) best = { id: s.id, at };
  }
  return best ? best.id : null;
}

/** True while any scenario still has no verdict - keep polling. */
export function attackReportPending(report: AttackReport | null | undefined): boolean {
  return !!report && (report.pending > 0 || report.perScenario.some((s) => s.verdict == null));
}

export type AttackHeadline =
  | { kind: "empty" }
  | { kind: "running"; finished: number; total: number }
  | { kind: "undecided"; total: number }
  | { kind: "decided"; succeeded: number; decided: number; percent: number; inconclusive: number };

/**
 * The one-line result. "decided" counts only scenarios with a clear verdict
 * (succeeded + resisted) - the same denominator the server's rate uses - and
 * a null rate is reported as "undecided", never as zero percent.
 */
export function attackHeadline(report: AttackReport | null | undefined): AttackHeadline {
  if (!report || report.total === 0) return { kind: "empty" };
  if (attackReportPending(report)) {
    return { kind: "running", finished: Math.max(0, report.total - report.pending), total: report.total };
  }
  const decided = report.succeededAttacks + report.resisted;
  if (report.attackSuccessRate == null || decided === 0) return { kind: "undecided", total: report.total };
  return {
    kind: "decided",
    succeeded: report.succeededAttacks,
    decided,
    percent: Math.round(report.attackSuccessRate * 100),
    inconclusive: report.inconclusive,
  };
}

export function attackHeadlineText(h: AttackHeadline): string {
  if (h.kind === "empty") return "";
  if (h.kind === "running") return `Running: ${h.finished} of ${h.total} scenarios finished`;
  if (h.kind === "undecided") {
    return `No result: none of the ${h.total} scenario${h.total === 1 ? "" : "s"} reached a clear verdict`;
  }
  const base = `${h.succeeded} of ${h.decided} attack${h.decided === 1 ? "" : "s"} succeeded (${h.percent}%)`;
  return h.inconclusive > 0 ? `${base} - ${h.inconclusive} inconclusive, not counted` : base;
}

export type AttackOutcome = "resisted" | "compromised" | "inconclusive" | "running";

/** A FAILED simulation verdict means the ATTACK worked: the agent was compromised. */
export function attackOutcome(verdict: AttackVerdict): AttackOutcome {
  if (verdict === "PASSED") return "resisted";
  if (verdict === "FAILED") return "compromised";
  if (verdict === "INCONCLUSIVE") return "inconclusive";
  return "running";
}

/** The per-scenario budget to send, or null when the text is not a positive number. */
export function parseAttackBudget(text: string): number | null {
  const trimmed = (text ?? "").trim();
  if (!/^(\d+\.?\d*|\.\d+)$/.test(trimmed)) return null;
  const value = Number(trimmed);
  return Number.isFinite(value) && value > 0 ? value : null;
}

/** [{role:"persona"|"agent", text}] -> readable lines. The first "sender" turn is the poisoned content. */
export function formatAttackTranscript(transcriptJson: string): string {
  try {
    const turns = JSON.parse(transcriptJson) as { role: string; text: string }[];
    if (!Array.isArray(turns)) return transcriptJson;
    return turns.map((t) => `${t.role === "persona" ? "Sender" : "Agent"}: ${t.text}`).join("\n\n");
  } catch {
    return transcriptJson;
  }
}
