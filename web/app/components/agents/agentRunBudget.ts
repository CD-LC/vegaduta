// Pure helpers for the per-agent run cap field (trust T5). Kept free of React
// and of the api client so the validation that mirrors the server's rules is
// testable on its own.
//
// Wire contract (core AgentRunBudgetController / AgentRunBudgetResponse):
//   GET/PATCH /api/agents/{agentId}/run-budget. Money fields are JSON numbers.
//   The server REJECTS (400) rather than clamps: a value must be greater than
//   zero, have at most four decimal places, be no larger than 999999.9999 and
//   no larger than maxAgentCapUsd when that is non-null.

export interface AgentRunBudget {
  /** False = per-agent caps are switched off on this deployment; the field hides. */
  overrideEnabled: boolean;
  /** Platform cap per run; 0 = no platform cap. */
  globalCapUsd: number;
  /** The value stored on this agent; null = none. */
  agentCapUsd: number | null;
  /** What a turn actually runs under; 0 = no cap. */
  effectiveCapUsd: number;
  /** Highest value a save accepts; null = no upper bound. */
  maxAgentCapUsd: number | null;
  /** Estimated cost of one delegated sub-agent call. */
  perBranchEstimateUsd: number;
}

/** The absolute ceiling of the stored column (NUMERIC(10,4)). */
export const RUN_BUDGET_ABSOLUTE_MAX = 999999.9999;
export const RUN_BUDGET_MAX_DECIMALS = 4;

function num(value: unknown): number | null {
  if (typeof value === "number" && Number.isFinite(value)) return value;
  if (typeof value === "string" && value.trim() !== "" && Number.isFinite(Number(value))) return Number(value);
  return null;
}

/**
 * Defensive read of the wire shape. Returns null when the body is not a
 * run-budget response at all (the caller then hides the field) - a missing
 * overrideEnabled is treated as "off", never as "on".
 */
export function normalizeRunBudget(raw: unknown): AgentRunBudget | null {
  if (!raw || typeof raw !== "object") return null;
  const r = raw as Record<string, unknown>;
  if (typeof r.overrideEnabled !== "boolean") return null;
  return {
    overrideEnabled: r.overrideEnabled,
    globalCapUsd: num(r.globalCapUsd) ?? 0,
    agentCapUsd: num(r.agentCapUsd),
    effectiveCapUsd: num(r.effectiveCapUsd) ?? 0,
    maxAgentCapUsd: num(r.maxAgentCapUsd),
    perBranchEstimateUsd: num(r.perBranchEstimateUsd) ?? 0,
  };
}

export type RunBudgetInputError = "empty" | "notNumber" | "notPositive" | "tooManyDecimals" | "aboveMax" | "tooLarge";

export type RunBudgetInputResult = { ok: true; value: number } | { ok: false; error: RunBudgetInputError };

/**
 * Mirrors the server's checks so a bad value is caught before the PATCH.
 * Plain decimal notation only (no exponent, sign or thousands separator):
 * what the person typed is what gets judged.
 */
export function validateRunBudgetInput(text: string, maxAgentCapUsd: number | null): RunBudgetInputResult {
  const trimmed = (text ?? "").trim();
  if (trimmed === "") return { ok: false, error: "empty" };
  if (!/^(\d+\.?\d*|\.\d+)$/.test(trimmed)) return { ok: false, error: "notNumber" };
  const value = Number(trimmed);
  if (!Number.isFinite(value)) return { ok: false, error: "notNumber" };
  if (value <= 0) return { ok: false, error: "notPositive" };
  const dot = trimmed.indexOf(".");
  const decimals = dot < 0 ? 0 : trimmed.slice(dot + 1).replace(/0+$/, "").length;
  if (decimals > RUN_BUDGET_MAX_DECIMALS) return { ok: false, error: "tooManyDecimals" };
  if (maxAgentCapUsd != null && value > maxAgentCapUsd) return { ok: false, error: "aboveMax" };
  if (value > RUN_BUDGET_ABSOLUTE_MAX) return { ok: false, error: "tooLarge" };
  return { ok: true, value };
}

const USD = new Intl.NumberFormat("en-US", {
  style: "currency",
  currency: "USD",
  minimumFractionDigits: 2,
  maximumFractionDigits: RUN_BUDGET_MAX_DECIMALS,
});

/** Two to four decimals, so a small per-branch estimate does not round to nothing. */
export function formatRunBudgetUsd(value: number): string {
  return USD.format(value);
}

/** What the input shows for a stored cap: plain digits, no trailing zeros. */
export function runBudgetInputText(agentCapUsd: number | null): string {
  return agentCapUsd == null ? "" : String(Number(agentCapUsd.toFixed(RUN_BUDGET_MAX_DECIMALS)));
}
