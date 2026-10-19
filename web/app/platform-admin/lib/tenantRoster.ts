// Tenant roster (GET /api/platform/tenants) - row type plus the pure
// filter/sort helpers the Tenants page uses. The roster includes non-ACTIVE
// tenants (suspended, provisioning, failed provisioning); erased tenants and
// anonymous /try demo sandboxes are never in it.
//
// Every nullable figure means "core could not measure it". Sorting therefore
// puts unavailable values LAST in both directions - an unreadable tenant must
// never masquerade as the cheapest or the most dormant one.

export type TenantStatus = "ACTIVE" | "SUSPENDED" | "PROVISIONING" | "PROVISIONING_FAILED";

export type PlatformTenantSummary = {
  tenantId: string;
  name: string;
  status: TenantStatus | string;
  plan: string;
  createdAt: string | null;
  ownerEmail: string | null;
  lastActivityAt: string | null;
  spend30dUsd: number | null;
  hostedSpend30dUsd: number | null;
  llmCalls30d: number | null;
  agentCount: number;
  workflowCount: number;
  userCount: number;
  metricsComplete: boolean;
  emergencyStopped?: boolean;
  dataResidencyRegion?: string | null;
  /** Monthly hosted-pool spend cap; null = no cap (or an older core). */
  spendCapUsd?: number | null;
  /** Calendar-month (UTC) hosted spend counted against the cap; null = unavailable. */
  capMonthToDateUsd?: number | null;
  /** Hosted LLM calls currently refused because the cap is reached. */
  capBlocked?: boolean | null;
};

export type CapFilter = "ALL" | "CAPPED" | "BLOCKED";

export const CAP_FILTER_LABEL: Record<CapFilter, string> = {
  ALL: "Any cap",
  CAPPED: "Capped",
  BLOCKED: "Blocked by cap",
};

/** Spend-cap filter: CAPPED = a cap is set, BLOCKED = currently blocked by it. */
export function filterByCap(rows: PlatformTenantSummary[], filter: CapFilter | string): PlatformTenantSummary[] {
  if (filter === "CAPPED") return rows.filter((t) => typeof t.spendCapUsd === "number");
  if (filter === "BLOCKED") return rows.filter((t) => t.capBlocked === true);
  return rows;
}

export function countCaps(rows: PlatformTenantSummary[]): { capped: number; blocked: number } {
  let capped = 0;
  let blocked = 0;
  for (const t of rows) {
    if (typeof t.spendCapUsd === "number") capped++;
    if (t.capBlocked === true) blocked++;
  }
  return { capped, blocked };
}

export const TENANT_STATUSES: TenantStatus[] = ["ACTIVE", "SUSPENDED", "PROVISIONING", "PROVISIONING_FAILED"];

export const TENANT_STATUS_LABEL: Record<string, string> = {
  ACTIVE: "Active",
  SUSPENDED: "Suspended",
  PROVISIONING: "Provisioning",
  PROVISIONING_FAILED: "Provisioning failed",
};

/** Badge tone per status: ok / warn / bad; unknown statuses read as warn. */
export function tenantStatusTone(status: string | null | undefined): "ok" | "warn" | "bad" {
  if (status === "ACTIVE") return "ok";
  if (status === "SUSPENDED" || status === "PROVISIONING_FAILED") return "bad";
  return "warn";
}

export type TenantSortKey = "name" | "spend" | "lastActivity" | "created";
export type SortDir = "asc" | "desc";

function timeOf(iso: string | null | undefined): number | null {
  if (!iso) return null;
  const t = new Date(iso).getTime();
  return Number.isNaN(t) ? null : t;
}

function numOf(v: number | null | undefined): number | null {
  return typeof v === "number" && Number.isFinite(v) ? v : null;
}

function sortValue(t: PlatformTenantSummary, key: TenantSortKey): number | string | null {
  switch (key) {
    case "spend":
      return numOf(t.spend30dUsd);
    case "lastActivity":
      return timeOf(t.lastActivityAt);
    case "created":
      return timeOf(t.createdAt);
    default:
      return (t.name || "").toLowerCase();
  }
}

/** Stable sort; null/unavailable values always last, ties broken by name. */
export function sortTenants(rows: PlatformTenantSummary[], key: TenantSortKey, dir: SortDir): PlatformTenantSummary[] {
  const sign = dir === "asc" ? 1 : -1;
  return rows
    .map((row, i) => ({ row, i }))
    .sort((a, b) => {
      const va = sortValue(a.row, key);
      const vb = sortValue(b.row, key);
      if (va == null && vb != null) return 1;
      if (vb == null && va != null) return -1;
      if (va != null && vb != null && va !== vb) {
        const cmp = typeof va === "string" ? va.localeCompare(vb as string) : (va as number) - (vb as number);
        if (cmp !== 0) return sign * cmp;
      }
      const byName = (a.row.name || "").localeCompare(b.row.name || "");
      return byName !== 0 ? byName : a.i - b.i;
    })
    .map(({ row }) => row);
}

/** Name substring (case-insensitive) + status ("" or "ALL" = every status). */
export function filterTenants(rows: PlatformTenantSummary[], query: string, status: string): PlatformTenantSummary[] {
  const q = query.trim().toLowerCase();
  return rows.filter(
    (t) =>
      (!status || status === "ALL" || t.status === status) &&
      (!q || t.name?.toLowerCase().includes(q) || t.ownerEmail?.toLowerCase().includes(q))
  );
}

/** Per-status counts for the filter dropdown labels. */
export function countByStatus(rows: PlatformTenantSummary[]): Record<string, number> {
  const out: Record<string, number> = {};
  for (const t of rows) out[t.status] = (out[t.status] || 0) + 1;
  return out;
}
