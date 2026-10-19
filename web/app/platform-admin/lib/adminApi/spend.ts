import { apiFetch, toJson } from "../api";

// Platform-wide spend reports (PlatformSpendController, /api/platform/spend/**).
// Two figures are always kept apart:
//   allProviders = estimated cost of every recorded LLM call, incl. tenants' own
//                  BYOK keys (usage, not platform outlay)
//   hostedPool   = PLATFORM_HOSTED only = what the platform itself pays for
// Both are estimates from stored per-call figures, not provider invoices.

export interface SpendMetrics {
  totalCalls: number;
  errorCount: number;
  promptTokens: number;
  completionTokens: number;
  totalTokens: number;
  estimatedCostUsd: number;
  avgLatencyMs: number | null;
}

export interface SpendMeta {
  scope: string;
  from: string;
  to: string;
  tenantsTotal: number;
  tenantsScanned: number;
  tenantsFailed: number;
  truncated: boolean;
  includesLiveToday: boolean;
}

export interface SpendSummary {
  meta: SpendMeta;
  allProviders: SpendMetrics;
  hostedPool: SpendMetrics;
}
export interface SpendDayRow {
  date: string;
  allProviders: SpendMetrics;
  hostedCalls: number;
  hostedCostUsd: number;
}
export interface SpendTenantRow {
  tenantId: string;
  tenantName: string;
  allProviders: SpendMetrics;
  hostedPool: SpendMetrics;
}
export interface SpendModelRow {
  model: string;
  modality: string;
  allProviders: SpendMetrics;
}
export interface SpendProviderRow {
  provider: string;
  hostedPool: boolean;
  metrics: SpendMetrics;
}

export type SpendGroup = "tenant" | "day" | "model" | "provider";

export interface SpendRange {
  /** yyyy-mm-dd, UTC. Server default: trailing 30 days; max span 92 days. */
  from?: string;
  to?: string;
  /** Optional: narrow every report to one tenant (UUID). Omitted = platform-wide. */
  tenantId?: string;
}

function qs(range: SpendRange, extra: Record<string, string> = {}): string {
  const p = new URLSearchParams();
  if (range.from) p.set("from", range.from);
  if (range.to) p.set("to", range.to);
  if (range.tenantId) p.set("tenantId", range.tenantId);
  for (const [k, v] of Object.entries(extra)) p.set(k, v);
  const s = p.toString();
  return s ? `?${s}` : "";
}

export async function getSpendSummary(range: SpendRange): Promise<SpendSummary> {
  return toJson(await apiFetch(`/api/platform/spend/summary${qs(range)}`));
}

export async function getSpendByDay(range: SpendRange): Promise<{ meta: SpendMeta; rows: SpendDayRow[] }> {
  return toJson(await apiFetch(`/api/platform/spend/by-day${qs(range)}`));
}

export async function getSpendByTenant(range: SpendRange): Promise<{ meta: SpendMeta; rows: SpendTenantRow[] }> {
  return toJson(await apiFetch(`/api/platform/spend/by-tenant${qs(range)}`));
}

export async function getSpendByModel(
  range: SpendRange
): Promise<{ meta: SpendMeta; totalRows: number; rows: SpendModelRow[] }> {
  return toJson(await apiFetch(`/api/platform/spend/by-model${qs(range)}`));
}

export async function getSpendByProvider(range: SpendRange): Promise<{ meta: SpendMeta; rows: SpendProviderRow[] }> {
  return toJson(await apiFetch(`/api/platform/spend/by-provider${qs(range)}`));
}

/**
 * Authenticated CSV download: the fetch carries the Bearer token (a plain link
 * cannot), then the body is handed to the browser as a Blob download.
 */
export async function downloadSpendCsv(group: SpendGroup, range: SpendRange): Promise<string> {
  const res = await apiFetch(`/api/platform/spend/export.csv${qs(range, { groupBy: group })}`);
  if (!res.ok) {
    await toJson(res); // throws with the server's message (and toasts)
  }
  const blob = await res.blob();
  const cd = res.headers.get("content-disposition") || "";
  const name = /filename="?([^";]+)"?/.exec(cd)?.[1] || `platform-spend-${group}.csv`;
  const url = URL.createObjectURL(blob);
  const a = document.createElement("a");
  a.href = url;
  a.download = name;
  document.body.appendChild(a);
  a.click();
  a.remove();
  URL.revokeObjectURL(url);
  return name;
}
