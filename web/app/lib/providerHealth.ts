// Per-credential provider health, as core reports it on GET /api/providers (and the availability read): a key the
// provider has been rejecting shows `health: "rejected"` with `recentFailures` and `lastError`. Prod re-verification
// 2026-10-05, R4b: a rejected Vertex key stayed selectable with no warning, so the first chat answered "rejected our
// credentials". The field names are core's not-yet-final contract, so everything here is defensive: a missing or
// unrecognised value means "ok" (no warning), never a guess.

export interface ProviderHealth {
  rejected: boolean;
  recentFailures: number;
  lastError: string | null;
}

interface MaybeHealthRow {
  providerName?: unknown;
  health?: unknown;
  recentFailures?: unknown;
  lastError?: unknown;
}

function asState(v: unknown): string {
  if (typeof v === "string") return v.trim().toLowerCase();
  if (v && typeof v === "object") {
    const o = v as { status?: unknown; state?: unknown; health?: unknown };
    return asState(o.status ?? o.state ?? o.health);
  }
  return "";
}

/** The health of one provider row; null when the row says nothing (older core, or no recent calls). */
export function providerHealthOf(row: MaybeHealthRow | null | undefined): ProviderHealth | null {
  if (!row || typeof row !== "object" || row.health == null) return null;
  const nested = typeof row.health === "object" ? (row.health as MaybeHealthRow) : null;
  const failures = Number(row.recentFailures ?? nested?.recentFailures);
  const lastError = row.lastError ?? nested?.lastError;
  return {
    rejected: asState(row.health) === "rejected",
    recentFailures: Number.isFinite(failures) && failures > 0 ? Math.floor(failures) : 0,
    lastError: typeof lastError === "string" && lastError.trim() ? lastError.trim() : null,
  };
}

/** True when the named provider's credential is being rejected. A missing provider or health is "not rejected". */
export function isProviderRejected(
  rows: ReadonlyArray<MaybeHealthRow> | null | undefined,
  providerName: string | null | undefined,
): boolean {
  if (!Array.isArray(rows) || !providerName) return false;
  const row = rows.find((r) => r && r.providerName === providerName);
  return providerHealthOf(row)?.rejected === true;
}
