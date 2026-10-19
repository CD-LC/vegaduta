import { apiFetch, toJson } from "../api";

// Per-tenant monthly spend caps + read-only "view as" (impersonation).
// Contract agreed with core-3 (camelCase, ISO timestamps, USD as JSON numbers):
//
//   GET  /api/platform/tenants/{tenantId}/spend-cap
//   PUT  /api/platform/tenants/{tenantId}/spend-cap   {capUsd|null}   X-Master-Key
//   POST /api/platform/impersonation   {tenantId, userId, reason}       X-Master-Key
//   DELETE /api/platform/impersonation/{id}
//   GET  /api/platform/impersonation?active=true|false&page=N
//
// Caps are calendar-month (UTC), hosted-pool LLM spend only - BYOK is never
// counted or blocked. Master-key writes put X-Master-Key and Content-Type in ONE
// headers object (jsonBody()'s own `headers` would clobber the key - the trap
// api.deletePlatformTenant documents).

export type SpendCapAlertStage = 0 | 80 | 100;

export interface TenantSpendCap {
  tenantId: string;
  capUsd: number | null;
  monthToDateUsd: number | null;
  percentUsed: number | null;
  alertStage: SpendCapAlertStage | number;
  blocked: boolean;
  monthStart: string | null;
}

export interface ImpersonationStart {
  id: string;
  tenantId: string;
  tenantName: string | null;
  userId: string;
  userEmail: string | null;
  reason: string;
  startedAt: string;
  expiresAt: string;
  grant: string;
  viewUrl: string;
}

export interface ImpersonationSession {
  id: string;
  tenantId: string;
  tenantName: string | null;
  userId: string;
  userEmail: string | null;
  adminEmail: string | null;
  reason: string;
  startedAt: string;
  expiresAt: string;
  endedAt: string | null;
  requestCount: number | null;
}

export interface ImpersonationPage {
  items: ImpersonationSession[];
  total: number;
}

function masterKeyJson(masterKey: string, payload: unknown) {
  return {
    headers: { "Content-Type": "application/json", "X-Master-Key": masterKey },
    body: JSON.stringify(payload),
  };
}

export const governanceApi = {
  getSpendCap: (tenantId: string): Promise<TenantSpendCap> =>
    apiFetch(`/api/platform/tenants/${encodeURIComponent(tenantId)}/spend-cap`).then(toJson),
  setSpendCap: (tenantId: string, capUsd: number | null, masterKey: string): Promise<TenantSpendCap> =>
    apiFetch(`/api/platform/tenants/${encodeURIComponent(tenantId)}/spend-cap`, {
      method: "PUT",
      ...masterKeyJson(masterKey, { capUsd }),
    }).then(toJson),

  startImpersonation: (
    body: { tenantId: string; userId: string; reason: string },
    masterKey: string
  ): Promise<ImpersonationStart> =>
    apiFetch(`/api/platform/impersonation`, { method: "POST", ...masterKeyJson(masterKey, body) }).then(toJson),
  endImpersonation: (id: string): Promise<null> =>
    apiFetch(`/api/platform/impersonation/${encodeURIComponent(id)}`, { method: "DELETE" }).then(toJson),
  listImpersonation: (params: { active: boolean; page?: number; limit?: number }): Promise<ImpersonationPage> => {
    const q = new URLSearchParams({ active: String(params.active) });
    if (params.page != null) q.set("page", String(params.page));
    if (params.limit != null) q.set("limit", String(params.limit));
    return apiFetch(`/api/platform/impersonation?${q.toString()}`).then(toJson);
  },
};
