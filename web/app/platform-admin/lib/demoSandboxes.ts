// Legacy demo-sandbox reclaim (GET/POST /api/platform/demo-sandboxes[/reclaim]).
//
// `shared` is the single tenant that serves the public /try demo - it is never
// reclaimable and the server refuses it regardless of what the client sends.
// `reclaimable` are per-visitor sandboxes from before the shared-tenant demo;
// each is a real tenant row + Postgres schema. The server erases at most
// RECLAIM_BATCH_LIMIT per call; the UI only ever sends another batch when the
// operator clicks again (never an automatic loop).

export const RECLAIM_BATCH_LIMIT = 50;
export const RECLAIM_CONFIRM_WORD = "RECLAIM";

export type SharedDemoTenant = { id: string; name: string; schemaName: string };
export type ReclaimableSandbox = {
  id: string;
  name: string;
  schemaName: string;
  status: string;
  createdAt: string;
  lastSessionAt: string | null;
};
export type DemoSandboxInventory = {
  shared: SharedDemoTenant | null;
  reclaimable: ReclaimableSandbox[];
  total: number;
};
export type ReclaimResult = {
  erased: string[];
  failed: { id: string; name: string; error: string }[];
  remaining: number;
};

/** Tolerant parse: an unexpected body is treated as an empty inventory with total = reclaimable.length. */
export function normalizeInventory(raw: any): DemoSandboxInventory {
  const reclaimable: ReclaimableSandbox[] = Array.isArray(raw?.reclaimable) ? raw.reclaimable : [];
  const shared = raw?.shared && typeof raw.shared === "object" && raw.shared.id ? raw.shared : null;
  const total = typeof raw?.total === "number" && Number.isFinite(raw.total) ? raw.total : reclaimable.length;
  return { shared, reclaimable, total };
}

/**
 * The ids one click may send: the operator's selection (minus anything that is
 * the shared tenant), capped at the server's batch limit. An empty selection
 * means "reclaim all" and is sent as no tenantIds (server picks the next batch).
 */
export function reclaimBatch(selected: string[], sharedId: string | null | undefined): string[] {
  return selected.filter((id) => id && id !== sharedId).slice(0, RECLAIM_BATCH_LIMIT);
}

export function reclaimSummary(r: ReclaimResult): string {
  const parts = [`${r.erased.length} erased`];
  if (r.failed.length) parts.push(`${r.failed.length} failed`);
  parts.push(`${r.remaining} remaining`);
  return parts.join(" · ");
}
