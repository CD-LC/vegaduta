// Registered tenants vs anonymous /try sandboxes - GET /api/platform/tenant-counts.
//
// The two are never summed. A sandbox is a real tenant row and a real Postgres
// schema, but nobody registered for it; adding it to "active tenants" is what
// made that figure climb with landing-page traffic.
//
// normalizeTenantCounts returns null for anything that is not the expected
// shape - in particular for a core that predates the endpoint (404 -> the
// caller's catch -> null). The pages then say nothing about sandboxes rather
// than showing a zero that was never measured.

export type TenantCounts = {
  registeredActive: number;
  demoLive: number;
  demoExpiredUnreclaimed: number;
};

const FIELDS = ["registeredActive", "demoLive", "demoExpiredUnreclaimed"] as const;

export function normalizeTenantCounts(raw: unknown): TenantCounts | null {
  if (!raw || typeof raw !== "object") return null;
  const out = {} as TenantCounts;
  for (const f of FIELDS) {
    const v = (raw as Record<string, unknown>)[f];
    if (typeof v !== "number" || !Number.isFinite(v) || v < 0) return null;
    out[f] = v;
  }
  return out;
}

/** "17 live · 230 expired" - the overview tile and the roster sub-heading share it. */
export function demoSandboxSummary(counts: TenantCounts): string {
  return `${counts.demoLive} live · ${counts.demoExpiredUnreclaimed} expired`;
}
