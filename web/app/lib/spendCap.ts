// Monthly hosted-LLM spend cap (set per tenant by a platform admin in
// web-admin). Only platform-pool spend counts; BYOK is never counted or
// blocked. When a tenant is over its cap core refuses hosted LLM calls with
// HTTP 402 {error:"spend_cap_reached", message, capUsd, monthToDateUsd,
// resetsAt}; streaming endpoints send the same JSON as an SSE `error` event.
// These helpers turn either shape into one clear, non-generic message.

import { READ_ONLY_ERROR, READ_ONLY_MESSAGE, isReadOnlyBody } from "./viewAs";

export const SPEND_CAP_ERROR = "spend_cap_reached";

export interface SpendCapBlock {
  capUsd: number | null;
  monthToDateUsd: number | null;
  resetsAt: string | null;
  message: string | null;
}

/** GET /api/tenant/spend-cap (tenant admins, read-only). */
export interface TenantSpendCap {
  tenantId: string;
  capUsd: number | null;
  monthToDateUsd: number | null;
  percentUsed: number | null;
  alertStage: 0 | 80 | 100 | number;
  blocked: boolean;
  monthStart: string | null;
}

function num(v: unknown): number | null {
  if (v === null || v === undefined || v === "") return null;
  const n = typeof v === "number" ? v : Number(v);
  return Number.isFinite(n) ? n : null;
}

/** The 402 body (or SSE error data, already parsed) -> block info; null for any other body. */
export function parseSpendCapBody(body: any): SpendCapBlock | null {
  if (!body || typeof body !== "object" || body.error !== SPEND_CAP_ERROR) return null;
  return {
    capUsd: num(body.capUsd),
    monthToDateUsd: num(body.monthToDateUsd),
    resetsAt: typeof body.resetsAt === "string" && body.resetsAt ? body.resetsAt : null,
    message: typeof body.message === "string" && body.message ? body.message : null,
  };
}

/** Raw SSE `data` text -> block info; null when it is not a spend-cap payload. */
export function parseSpendCapPayload(text: string | null | undefined): SpendCapBlock | null {
  if (!text) return null;
  const trimmed = text.trim();
  if (!trimmed.startsWith("{")) return null;
  try {
    return parseSpendCapBody(JSON.parse(trimmed));
  } catch {
    return null;
  }
}

/** USD with a leading dollar sign (50 -> "50", 12.4 -> "12.40"); "—" when unavailable. */
export function formatCapUsd(v: unknown): string {
  const n = num(v);
  if (n == null) return "—";
  return Number.isInteger(n)
    ? `$${n.toLocaleString("en-US")}`
    : `$${n.toLocaleString("en-US", { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`;
}

/** Calendar date of the reset ("Nov 1, 2026"), in UTC because the cap month is UTC. Null when unknown. */
export function formatResetDate(iso: string | null | undefined, locale?: string): string | null {
  if (!iso) return null;
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return null;
  return d.toLocaleDateString(locale, { year: "numeric", month: "short", day: "numeric", timeZone: "UTC" });
}

/** First day of the next UTC month after `monthStart` (or after now) as ISO. */
export function nextMonthStartIso(monthStart: string | null | undefined, now: number = Date.now()): string {
  const base = monthStart ? new Date(monthStart) : new Date(now);
  const d = Number.isNaN(base.getTime()) ? new Date(now) : base;
  return new Date(Date.UTC(d.getUTCFullYear(), d.getUTCMonth() + 1, 1)).toISOString();
}

export function spendCapMessage(block: SpendCapBlock, locale?: string): string {
  const cap = block.capUsd != null ? ` (${formatCapUsd(block.capUsd)})` : "";
  const date = formatResetDate(block.resetsAt, locale);
  const reset = date ? ` It resets on ${date}.` : " It resets at the start of next month.";
  return `Your workspace reached its monthly AI spend limit${cap}.${reset} Contact your platform administrator.`;
}

/** Error carrying the friendly message plus .status/.code/.spendCap for callers that branch on it. */
export function spendCapError(block: SpendCapBlock, status = 402): Error {
  const err: any = new Error(spendCapMessage(block));
  err.status = status;
  err.code = SPEND_CAP_ERROR;
  err.spendCap = block;
  return err;
}

// TenantContextFilter's 403 for a JWT with no tenant_id. An EXPECTED state
// for the whole pre-onboarding window (OnboardingGate is its UI), not a
// failure - the body used to reach the global error toast on every
// tenant-scoped call, and its "sign out and back in" advice loops forever
// for an account whose onboarding simply hasn't run.
export const TENANT_MISSING_ERROR = "tenant_missing";
const TENANT_MISSING_MESSAGE =
  "Finish creating your organization to continue. If you already have one, sign out and back in.";
function isTenantMissingBody(status: number, body: any): boolean {
  return status === 403 && typeof body?.error === "string" && body.error.includes("not linked to an organization");
}

/**
 * Known guard refusals that must NOT read as a generic failure: the spend cap
 * (402), the view-as read-only refusal (403), and the tenant-less state above
 * (403). Returns the Error to throw, or null for anything else.
 */
export function knownGuardError(status: number, body: any): Error | null {
  const cap = status === 402 ? parseSpendCapBody(body) : null;
  if (cap) return spendCapError(cap, status);
  if (isReadOnlyBody(status, body)) {
    const err: any = new Error(READ_ONLY_MESSAGE);
    err.status = status;
    err.code = READ_ONLY_ERROR;
    err.readOnly = true;
    return err;
  }
  if (isTenantMissingBody(status, body)) {
    const err: any = new Error(TENANT_MISSING_MESSAGE);
    err.status = status;
    err.code = TENANT_MISSING_ERROR;
    err.tenantMissing = true;
    return err;
  }
  return null;
}

/** 0..1 share of the cap used, null without a cap. Not clamped above 1 (an overshoot is real). */
export function capUsageFraction(
  cap: Pick<TenantSpendCap, "capUsd" | "monthToDateUsd"> | null | undefined
): number | null {
  const c = num(cap?.capUsd);
  const used = num(cap?.monthToDateUsd);
  if (c == null || c <= 0 || used == null) return null;
  return used / c;
}
