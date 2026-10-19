import { apiFetch, toJson } from "../api";

// Cross-tenant audit search (PlatformAuditSearchController,
// GET /api/platform/audit/search). Every filter is optional and ANDed.

export interface AuditSearchFilters {
  tenantId?: string;
  actorId?: string;
  /** Exact action type, or a trailing "*" for a prefix match (e.g. "platform.user.*"). */
  actionType?: string;
  targetType?: string;
  outcome?: string;
  /** ISO-8601 instants. */
  from?: string;
  to?: string;
  limit?: number;
}

export interface AuditSearchRow {
  tenantId: string;
  tenantName: string;
  id: string;
  actorId: string | null;
  actionType: string;
  targetType: string | null;
  targetId: string | null;
  outcome: string | null;
  detail: string | null;
  createdAt: string | null;
}

export interface AuditSearchResult {
  rows: AuditSearchRow[];
  truncated: boolean;
  tenantsScanned: number;
  tenantsInScope: number;
  tenantsFailed: number;
  rowLimit: number;
  maxTenants: number;
}

export async function searchAudit(filters: AuditSearchFilters): Promise<AuditSearchResult> {
  const qs = new URLSearchParams();
  for (const [k, v] of Object.entries(filters)) {
    if (v !== undefined && v !== null && String(v).trim() !== "") qs.set(k, String(v).trim());
  }
  const q = qs.toString();
  return toJson(await apiFetch(`/api/platform/audit/search${q ? `?${q}` : ""}`));
}

/** RFC-4180 CSV of a result set, for the "download" button. */
export function auditRowsToCsv(rows: AuditSearchRow[]): string {
  const cols: (keyof AuditSearchRow)[] = [
    "createdAt", "tenantId", "tenantName", "actorId", "actionType", "targetType", "targetId", "outcome", "detail", "id",
  ];
  const esc = (v: unknown) => {
    let s = v === null || v === undefined ? "" : String(v);
    // Neutralise spreadsheet formula injection from attacker-influenced fields.
    if (/^[=+\-@\t\r]/.test(s)) s = `'${s}`;
    return /[",\n\r]/.test(s) ? `"${s.replace(/"/g, '""')}"` : s;
  };
  return [cols.join(","), ...rows.map((r) => cols.map((c) => esc(r[c])).join(","))].join("\n");
}
