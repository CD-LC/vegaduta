import { apiFetch, toJson, jsonBody } from "../api";

// Per-tenant control page (PlatformTenantDetailController, read-only) plus the
// EXISTING emergency-stop/resume endpoints in PlatformController - emergency
// stop is the only per-tenant lifecycle switch the request path enforces
// (TenantStatusGuardImpl: status == ACTIVE && !emergencyStopped).

export type TenantRef = {
  tenantId: string;
  name: string;
  status: string;
  emergencyStopped: boolean;
};

export type TenantDetail = {
  tenantId: string;
  name: string;
  schemaName: string;
  status: string;
  plan: string;
  accountType: string | null;
  dataResidencyRegion: string | null;
  createdAt: string | null;
  emergencyStopped: boolean;
  emergencyStopReason: string | null;
  emergencyStoppedAt: string | null;
  accessState: "ALLOWED" | "BLOCKED_STATUS" | "BLOCKED_EMERGENCY_STOP";
  subscriptionStatus: string | null;
  pastDue: boolean;
  compPlanExpiresAt: string | null;
  userCount: number | null;
  agentCount: number | null;
  workflowCount: number | null;
  quota: { tokensUsedMonth: number; tokensMonthlyLimit: number; usesPlatformLlm: boolean } | null;
  lastLlmCallAt: string | null;
  lastLlmCallKnown: boolean;
  /** Latest sign-in / LLM call / API activity core could see; null = unavailable. */
  lastActivityAt?: string | null;
  /** Estimated all-provider LLM spend over the trailing 30 days; null = unavailable. */
  spend30dUsd?: number | null;
  unavailable: string[];
};

export const tenantDetailApi = {
  list: (): Promise<TenantRef[]> => apiFetch("/api/platform/tenant-detail").then(toJson),
  get: (tenantId: string): Promise<TenantDetail> =>
    apiFetch(`/api/platform/tenant-detail/${encodeURIComponent(tenantId)}`).then(toJson),
  stop: (tenantId: string, reason: string) =>
    apiFetch(`/api/platform/tenants/${encodeURIComponent(tenantId)}/emergency-stop`, {
      method: "POST",
      ...jsonBody({ reason }),
    }).then(toJson),
  resume: (tenantId: string) =>
    apiFetch(`/api/platform/tenants/${encodeURIComponent(tenantId)}/emergency-resume`, { method: "POST" }).then(toJson),
};
