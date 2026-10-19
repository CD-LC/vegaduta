// GET /api/tenant/data-usage response shape - see getTenantDataUsage below.
export interface TenantDataUsageSystem {
  id: string;
  name: string;
  vendor: string;
  role: string;
  description: string;
  isolationFact: string;
  liveStat: { label: string; value: number | string } | null;
  status: "active" | "available_not_engaged";
}

export interface TenantDataUsageResponse {
  generatedAt: string;
  tenantId: string;
  systems: TenantDataUsageSystem[];
}

// GET /api/tenant/usage-quota response shape (TenantUsageQuotaController,
// core/app) - the visible usage meter's data source. `plan` is the TenantPlan
// enum name; `usesPlatformPool` is false for paid BYOK tenants (the meter UI
// hides itself entirely in that case - the window isn't enforced against
// their chats). 2026-09-26: the quota is a single rolling-30-day token
// window - message-count and daily-token windows were removed.
// `tokensMonthlyLimit` is the plan-scaled effective value enforcement
// actually uses (FREE 1x, PREMIUM 2x, UNLIMITED 3x by default).
export interface TenantUsageQuotaResponse {
  plan: string;
  usesPlatformPool: boolean;
  tokensUsedMonth: number;
  tokensMonthlyLimit: number;
  /** False when billing is off (personal mode): usage is counted but no limit applies. */
  limitEnforced?: boolean;
}

// GET /api/tenant/spend-protection response shape (TenantUsageQuotaController,
// core/app) - the "what's protecting your spend" transparency panel, additive
// to the usage meter above. `caching` is enumerated server-side from the
// shared ProviderCachingRegistry (never a hardcoded provider list) - whichever
// providers come back are the ones actually caching right now. Per-provider
// `cacheReadTokenShare`/`callsWithCacheHitShare` are `null` when this tenant
// had zero calls to that provider in the window (real absence of data, never
// a fabricated 0%). The three CostMechanismStatus fields deliberately carry
// no dollar/percent savings number - only `active` + a plain-language
// `description`, plus an optional `detail` string when a real configured
// number exists to show (e.g. the tool-cap, the approval-pattern count).
export interface SpendProtectionCachingProviderStatus {
  provider: string;
  active: boolean;
  callsInWindow: number;
  cacheReadTokenShare: number | null;
  callsWithCacheHitShare: number | null;
}

export interface SpendProtectionMechanismStatus {
  id: string;
  active: boolean;
  description: string;
  detail: string | null;
}

export interface SpendProtectionResponse {
  generatedAt: string;
  cacheWindowDays: number;
  caching: SpendProtectionCachingProviderStatus[];
  costAwareRouting: SpendProtectionMechanismStatus;
  toolSchemaMinimization: SpendProtectionMechanismStatus;
  approvalGates: SpendProtectionMechanismStatus;
}

// ===================================================================
// Notification bell (web/'s tenant-facing header) - backed by the already-
// built/compiled NotificationController/NotificationService/Notification
// entity (core/audit, V176__notification.sql). Field names/enums mirror
// NotificationResponse exactly (camelCase on the wire, enums UPPERCASE).
// -------------------------------------------------------------------

export type NotificationCategory = "SYSTEM" | "TOOL_FAILURE" | "BILLING" | "SAFETY" | "WORKFLOW" | "AGENT";
export type NotificationSeverity = "INFO" | "WARNING" | "ERROR";

export interface NotificationResponse {
  id: string;
  category: NotificationCategory;
  title: string;
  body: string;
  severity: NotificationSeverity;
  read: boolean;
  createdAt: string;
  link: string | null;
  relatedIssueId: string | null;
}

// -------------------------------------------------------------------
// Ambient digest (GET /api/ambient/digest) - the "while you were away"
// aggregate behind AmbientDigestCard: sentinel outcomes, parked
// agent.proposal approvals, job tallies and AGENT-category bell items in
// one call (AmbientDigestController, core/app).
// -------------------------------------------------------------------
