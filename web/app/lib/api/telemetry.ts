import { auth } from "../auth";
import { API_BASE, apiFetch, shareInflight, toJson, toJsonQuietOn } from "./core";
import type { TenantDataUsageResponse, TenantUsageQuotaResponse, SpendProtectionResponse, LlmCallDiagnosis, SystemStatusResponse, LlmTelemetryResponse, SessionLlmCallEntry } from "./types";

export const telemetryApi = {
  // System status (HUD dashboard rebuild) - backed by SystemStatusController.java.
  // Every field is a real, live reading (actuator health contributors, Hikari
  // pool MXBean, JVM management beans, MCP registry) - see that controller's
  // javadoc. Any authenticated tenant member can read it, same posture as
  // getTenantSettings above.
  getSystemStatus: () => apiFetch(`/api/system/status`).then(toJson<SystemStatusResponse>),
  // SILENT health probe for SystemHealthProvider. Deliberately bypasses
  // apiFetch/toJson: those raise the global HUD toast on every failure, and a
  // health poll that runs every ~20s would turn a real outage into a toast
  // flood (the very moment you least want extra noise on screen). Never throws:
  // resolves to a tagged result the provider interprets —
  //   { ok:true,  data }             → core reachable, `data` is SystemStatusResponse
  //   { ok:false, status }           → core responded non-2xx (status; 401/403 = auth, not an outage)
  //   { ok:false, status:0 }         → core unreachable (network error / process down)
  // Concurrent callers (the shell poller plus any page that asks on mount) share one request.
  getSystemHealthSilent: (): Promise<{ ok: boolean; status?: number; data?: SystemStatusResponse | null }> =>
    shareInflight("system-health-silent", () => telemetryApi.probeSystemHealth()),
  probeSystemHealth: async (): Promise<{ ok: boolean; status?: number; data?: SystemStatusResponse | null }> => {
    let token: string | null = null;
    try {
      // silent:true - this is a background poll, mounted at the root layout
      // and running on every route including public ones (/signup, /login).
      // A failed refresh here must never fire Keycloak's kc.login() redirect
      // (see auth.js's getToken javadoc) - just fall through unauthenticated,
      // same as the try/catch below already does for a thrown rejection.
      token = await auth.getToken({ silent: true });
    } catch {
      /* token refresh failed — still attempt the probe unauthenticated */
    }
    // Logged out (no token), probing /api/system/status could only ever
    // answer 401 - "core is up" proven by an error the browser then prints
    // to the console on EVERY public page load (/login, /signup, /docs...),
    // which the 2026-07-28 full-app audit flagged on all nine public
    // routes. /api/system/status/live is a purpose-built permitAll liveness
    // endpoint (SystemStatusController#live) answering a clean 200
    // {"status":"UP"} with no component detail - the right probe for a
    // visitor with no session. Authenticated tabs keep the full
    // /api/system/status read (sidecar state etc.) unchanged.
    try {
      if (!token) {
        const res = await fetch(`${API_BASE}/api/system/status/live`);
        return res.ok ? { ok: true, data: null } : { ok: false, status: res.status };
      }
      const res = await fetch(`${API_BASE}/api/system/status`, {
        headers: { Authorization: `Bearer ${token}` },
      });
      if (!res.ok) return { ok: false, status: res.status };
      const text = await res.text();
      return { ok: true, data: text ? JSON.parse(text) : null };
    } catch {
      return { ok: false, status: 0 };
    }
  },
  // Dashboard telemetry strip (direct feedback: "expectation is to really
  // plug the live data") - backed by SystemStatusController#llmTelemetry,
  // real aggregates (percentiles/model split/cost) over the existing
  // llm_call_log table. See LlmDashboardMetrics.java for field semantics.
  getLlmTelemetry: () => apiFetch(`/api/system/status/llm-telemetry`).then(toJson<LlmTelemetryResponse>),

  // Tenant-facing "how does our infrastructure use your data" transparency
  // page (/data-usage) - backed by TenantDataUsageController#getDataUsage.
  // One object per underlying system (Postgres, MinIO, Redis, Langfuse,
  // Kafka, Temporal, Keycloak) with a plain-language description, the
  // concrete isolation fact, an optional live stat, and a status
  // ("active" vs "available_not_engaged" for infra that's built but not yet
  // handling live traffic - e.g. Temporal). Any authenticated tenant member
  // can read it, same posture as getTenantSettings/getSystemStatus above.
  getTenantDataUsage: (): Promise<TenantDataUsageResponse> => apiFetch(`/api/tenant/data-usage`).then(toJson<TenantDataUsageResponse>),

  // Visible usage meter (/usage page + AppShell's compact pill/banner) -
  // used-vs-limit for the hosted-LLM-pool message/token windows
  // FreeTierUsageLimiterService enforces. Any authenticated tenant member
  // can read it (any member's chats consume the shared windows), same
  // posture as getTenantSettings above. Read-only - fetching it never
  // consumes quota.
  getUsageQuota: (): Promise<TenantUsageQuotaResponse> => apiFetch(`/api/tenant/usage-quota`).then(toJson<TenantUsageQuotaResponse>),

  // "What's protecting your spend" transparency panel - same read-only,
  // any-authenticated-member posture as getUsageQuota above (nothing more
  // sensitive than which cost-optimization mechanisms are on and a
  // real-data-only cache metric).
  getSpendProtection: (): Promise<SpendProtectionResponse> =>
    apiFetch(`/api/tenant/spend-protection`).then(toJson<SpendProtectionResponse>),

  // Direct feedback: "there should be an option to see the llm errors and
  // success logs and also, customer should be able to evaluate the price
  // cost per call or a task, and an outstanding balance of activated llm" -
  // backed by LlmCallLogController.java. Admin-only (/api/admin/**).
  // 403 is quiet on purpose: on FREE plans these endpoints answer 403
  // ("Premium feature") by design, and billing/LlmSpendSection polls them - see
  // toJsonQuietOn. The panel shows its own inline entitlement banner.
  getLlmCalls: (status = "all", limit = 50) =>
    apiFetch(`/api/admin/llm-usage/calls?status=${encodeURIComponent(status)}&limit=${limit}`).then(toJsonQuietOn([403])),
  getLlmUsageSummary: () => apiFetch(`/api/admin/llm-usage/summary`).then(toJsonQuietOn([403])),
  // "Why did this call fail?" analysis for one failed row in billing/LlmSpendSection's
  // call log - LlmCallDiagnosis above. Same quiet-403 posture as its
  // siblings (FREE plans answer 403 by design and the panel handles it
  // inline); 404 is quiet too since it's also an expected state (the row
  // was pruned between poll and click, or the call actually succeeded) -
  // the panel renders its own inline "could not analyze" text instead of a
  // global toast either way.
  diagnoseLlmCall: (id: string): Promise<LlmCallDiagnosis | null> =>
    apiFetch(`/api/admin/llm-usage/calls/${id}/diagnose`, { method: "POST" }).then(toJsonQuietOn([403, 404])),

  // "tenant admin should be able to see the llm used for ask me in tenant
  // space" - every call sharing one task/session id, in order. Quick Task's
  // sessionId === QuickTaskRun.taskId, so this is the exact linkage from a
  // task row to the provider/model that actually answered it. Admin-only
  // (/api/admin/**) - callers should gate this behind useAuth().isAdmin.
  getLlmUsageForTask: (taskId: string) => apiFetch(`/api/admin/llm-usage/tasks/${taskId}`).then(toJson<SessionLlmCallEntry[]>),
};
