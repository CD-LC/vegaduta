import { apiFetch, toJson, toJsonQuiet, jsonBody } from "./apiCore";
import type {
  JsonObject,
  KillSwitchStatus,
  PlatformUsersPage,
  SiteWidePromoResult,
} from "./apiCore";
import type { PlatformTenantSummary } from "./tenantRoster";

// Platform-admin endpoints, part 2 (budgets, limits, credentials, ...). Split out of api.ts verbatim.
export const platformEndpointsB = {
  // Budgets & Limits (PLATFORM-ADMIN-GOVERNANCE-AND-CONTROLS.md §3/§4) - the
  // per-principal DOLLAR budget over a configurable window (amount + window
  // seconds + per-modality split), backed by PerUserBudgetService +
  // PlatformSetting runtime config. This endpoint is being built in parallel
  // by core-3 (roadmap P1); until it exists the GET below 404s and the
  // Budgets & Limits screen renders a labeled "coming soon" state instead of
  // erroring. Contract per the doc: GET returns the current global-default
  // config, PUT replaces it. If/when core exposes per-plan/per-tenant/per-user
  // overrides, add sibling calls here.
  getPerUserBudget: (): Promise<JsonObject> => apiFetch(`/api/platform/per-user-budget`).then(toJson),
  updatePerUserBudget: (payload: JsonObject): Promise<JsonObject> =>
    apiFetch(`/api/platform/per-user-budget`, {
      method: "PUT",
      ...jsonBody(payload),
    }).then(toJson),

  // The platform-hosted-pool TOKEN quota (separate gate from
  // getPerUserBudget/updatePerUserBudget above, which is the DOLLAR budget -
  // raising one does not raise the other). Backs the "monthly token limit
  // reached" error's actual admin control - a single rolling-30-day window.
  getFreeTierQuota: (): Promise<JsonObject> => apiFetch(`/api/platform/free-tier-quota`).then(toJson),
  updateFreeTierQuota: (payload: JsonObject): Promise<JsonObject> =>
    apiFetch(`/api/platform/free-tier-quota`, {
      method: "PUT",
      ...jsonBody(payload),
    }).then(toJson),
  // Per-tenant support action - gives one tenant a clean slate on its
  // current daily/monthly usage without changing the limit itself (that's
  // updateFreeTierQuota above). No response body, same shape as
  // stopTenant/resumeTenant.
  resetTenantUsageQuota: (tenantId: string): Promise<JsonObject> =>
    apiFetch(`/api/platform/tenants/${tenantId}/usage-quota/reset`, { method: "POST" }).then(toJson),

  // 2026-08-11 - chat-generation platform settings (previously hardcoded in
  // core's chat path, now operator-selectable): Gemini reasoning/thinking
  // effort for the hosted pool plus the conversation-history replay window
  // (turns + character cap), the main lever on per-turn token cost. GET
  // returns { geminiReasoningEffort, historyMaxTurns, historyMaxChars };
  // PUT accepts any SUBSET of those three fields (partial update - omitted
  // fields keep their current value, mirroring the plan-limits editor's
  // upsert semantics rather than a full replace).
  getChatGenerationSettings: (): Promise<JsonObject> =>
    apiFetch(`/api/platform/chat-generation-settings`).then(toJson),
  updateChatGenerationSettings: (payload: JsonObject): Promise<JsonObject> =>
    apiFetch(`/api/platform/chat-generation-settings`, {
      method: "PUT",
      ...jsonBody(payload),
    }).then(toJson),

  // Fallback observability follow-up (#956) - fleet-wide lifetime provider-
  // fallback totals + per-tenant breakdown vs. the configured alert
  // threshold. See PlatformObservabilityService#computeFallbackAlertStatus.
  getFallbackAlertStatus: (): Promise<JsonObject> => apiFetch(`/api/platform/alerts/fallback-status`).then(toJson),
  updateFallbackAlertThreshold: (threshold: number): Promise<JsonObject> =>
    apiFetch(`/api/platform/alerts/fallback-threshold`, {
      method: "PUT",
      ...jsonBody({ threshold }),
    }).then(toJson),

  // Plan limits editor (VegaDuta-PR-Prompts-A2-B1-C1.md task A2-PR1 task 4;
  // roadmap §8) - per-TenantPlan (FREE/PREMIUM/UNLIMITED) caps backing
  // PlanLimitsService/TenantLimitsServiceImpl (core/tenancy,
  // control_plane.plan_limits, migration V101__plan_limits.sql).
  //
  // 2026-07-19: core-business's own PlanLimits.java javadoc explicitly
  // flags this CRUD editor as web-admin-scoped, and a full grep of
  // core/platform's PlatformController.java confirms no plan-limits route
  // exists there yet - only PlanLimitsService's *reads* landed with A2-PR1,
  // not a write method or any REST surface. Asked core-platform/the maintainer in
  // docs/AGENT-COORDINATION-2026-07-18.md §10 to add
  // GET /api/platform/plan-limits + PUT /api/platform/plan-limits/{plan}
  // (mirroring the existing getPlatformSettings/updatePlatformSetting
  // pair's shape) plus a PlanLimitsService.update(TenantPlan, ...) write
  // method - unanswered after two follow-up checks, so per that thread's
  // own "if no answer by the next round of checks" commitment, wiring this
  // up now against that best-guess contract rather than blocking further.
  // If the real endpoint ships with a different path/shape, only this
  // block and PlanLimitsEditor's field list need to change.
  getPlatformPlanLimits: (): Promise<any[]> => apiFetch(`/api/platform/plan-limits`).then(toJson),
  updatePlatformPlanLimits: (plan: string, payload: JsonObject): Promise<JsonObject> =>
    apiFetch(`/api/platform/plan-limits/${plan}`, {
      method: "PUT",
      ...jsonBody(payload),
    }).then(toJson),

  // Newsletter / news broadcast - NewsletterController.java (core/platform).
  // Recipients are every active tenant with its resolved TENANT_ADMIN email
  // (nullable) and its control_plane.tenant.news_opt_in flag; sendNewsletter
  // still hard-filters to opted-in tenants server-side even if a non-opted-in
  // tenant id is included in recipientTenantIds - see NewsletterService.
  // sendNewsletter payload: { subject, bodyHtml, bodyText?, recipientTenantIds }.
  // bodyText (nullable) is the text/plain half of the multipart/alternative
  // send; when null the server derives it from bodyHtml.
  getNewsletterRecipients: (): Promise<any[]> => apiFetch(`/api/platform/newsletter/recipients`).then(toJson),
  sendNewsletter: (payload: JsonObject): Promise<JsonObject> =>
    apiFetch(`/api/platform/newsletter`, {
      method: "POST",
      ...jsonBody(payload),
    }).then(toJson),

  // Demo Sandbox (the anonymous "try it without registering" surface) -
  // DemoSandboxController (core/platform). Two independent reads:
  //
  //  - demo-settings: a typed, range-validated view over the demo.* rows in
  //    control_plane.platform_setting that core/tenancy/demo already reads at
  //    runtime (so a save takes effect on the next demo request, no redeploy).
  //    PUT is PARTIAL - send only the fields you changed; an absent field means
  //    "leave that knob alone". A bad value comes back as a 400 naming the
  //    field and its allowed range (GlobalExceptionHandler maps the service's
  //    IllegalArgumentException), so toJson's normal error toast is the entire
  //    error surface this screen needs.
  //  - demo-metrics: live aggregates over control_plane.demo_session. Nothing
  //    is pre-rolled, so days/limit just resize the query (both clamped
  //    server-side to 1..90 / 1..200). Figures that genuinely cannot be
  //    computed come back null with a sibling *UnavailableReason string -
  //    render the reason, never a 0.
  getDemoSettings: (): Promise<JsonObject> => apiFetch(`/api/platform/demo-settings`).then(toJson),
  updateDemoSettings: (payload: JsonObject): Promise<JsonObject> =>
    apiFetch(`/api/platform/demo-settings`, {
      method: "PUT",
      ...jsonBody(payload),
    }).then(toJson),
  getDemoMetrics: (days = 30, limit = 20): Promise<JsonObject> =>
    apiFetch(`/api/platform/demo-metrics?days=${days}&limit=${limit}`).then(toJson),

  // Landing funnel (LandingEventController). Per-UTC-day counts for every
  // whitelisted beacon event - the click events that have been accumulating
  // since 2026-07-28 plus the `sec_*` section-reach events added 2026-09-11.
  //
  // NOTE this endpoint had NO caller anywhere in the repo until the /funnel
  // screen was built: the counters ran for six weeks into a read-out nobody
  // had written. If you are adding a second caller, that is the history.
  getLandingMetrics: (days = 14): Promise<JsonObject> =>
    apiFetch(`/api/platform/landing-metrics?days=${days}`).then(toJson),

  // Community Templates moderation queue
  // (docs/COMMUNITY-TEMPLATES-CONTRACT-2026-07-30.md) - public submission of
  // executable agent/workflow config is real attack surface (see that doc's
  // ClawHavoc reference), so nothing a member submits is publicly visible
  // until an admin approves it here. Backend (core-3,
  // controlplane.community_template) confirmed live and verified 2026-07-30
  // (14/14 tests, read directly from source) - all five routes below are the
  // real, shipped admin surface. reject's `reason` is server-enforced
  // required; withdraw's `reason` is accepted but optional server-side.
  getAdminTemplatesPending: (): Promise<any[]> => apiFetch(`/api/admin/templates/pending`).then(toJson),
  // Nav/overview badge read of the same queue: quiet, because
  // /api/admin/templates/** is gated by PERM_MANAGE_TEMPLATES and a
  // platform-admin token can be refused there (see AGENT-COORDINATION.md).
  getAdminTemplatesPendingQuiet: (): Promise<any[]> => apiFetch(`/api/admin/templates/pending`).then(toJsonQuiet),
  verifyAdminTemplate: (id: string): Promise<JsonObject> =>
    apiFetch(`/api/admin/templates/${id}/verify`, { method: "POST" }).then(toJson),
  unverifyAdminTemplate: (id: string): Promise<JsonObject> =>
    apiFetch(`/api/admin/templates/${id}/unverify`, { method: "POST" }).then(toJson),
  // The admin-scoped approved list (TemplateSummary rows: id/kind/title/
  // summary/category/tags/importCount/version/createdAt/updatedAt - no
  // config, no submitter/tenant/status fields) - this is what drives the
  // Published tab's withdraw target picker, not the public gallery.
  // `any` (not any[]): templates/page.tsx defensively probes result.items /
  // result.content before treating it as an array, and neither exists on any[].
  getApprovedAdminTemplates: (): Promise<any> => apiFetch(`/api/admin/templates/approved`).then(toJson),
  approveAdminTemplate: (id: string): Promise<JsonObject> =>
    apiFetch(`/api/admin/templates/${id}/approve`, { method: "POST" }).then(toJson),
  rejectAdminTemplate: (id: string, reason: string): Promise<JsonObject> =>
    apiFetch(`/api/admin/templates/${id}/reject`, {
      method: "POST",
      ...jsonBody({ reason }),
    }).then(toJson),
  withdrawAdminTemplate: (id: string, reason?: string): Promise<JsonObject> =>
    apiFetch(`/api/admin/templates/${id}/withdraw`, {
      method: "POST",
      ...jsonBody({ reason: reason || undefined }),
    }).then(toJson),

  // Notification bell (NotificationController/Service - core/audit,
  // V176__notification.sql). Same tenant-scoped endpoints web/'s console
  // uses - falls under SecurityConfig's default anyRequest().authenticated(),
  // no platform-admin-only route involved. unread-count returns a bare
  // number (not an object), which toJson's JSON.parse(text) handles fine.
  // read/read-all return 200 with no body - toJson resolves those to null.
  getNotifications: (unreadOnly = false, limit = 50): Promise<any[]> =>
    apiFetch(
      `/api/notifications?${new URLSearchParams({ unreadOnly: String(unreadOnly), limit: String(limit) })}`
    ).then(toJsonQuiet),
  getUnreadNotificationCount: (): Promise<number> => apiFetch(`/api/notifications/unread-count`).then(toJsonQuiet),
  markNotificationRead: (id: string): Promise<null> =>
    apiFetch(`/api/notifications/${id}/read`, { method: "POST" }).then(toJson),
  markAllNotificationsRead: (): Promise<null> => apiFetch(`/api/notifications/read-all`, { method: "POST" }).then(toJson),

  // Platform-wide kill switch (PlatformController + TenantEmergencyControlService).
  // Stopping suspends EVERY authenticated request for every tenant at
  // TenantContextFilter plus the omnichannel webhook chat path - the console
  // itself keeps working because /api/platform/** is exempt, so this screen
  // is also where the platform gets resumed. Fully reversible; every action
  // is audited server-side. Returns PlatformKillSwitchStatus
  // { stopped, reason, stoppedAt, updatedBy }.
  getKillSwitchStatus: (): Promise<KillSwitchStatus> => apiFetch(`/api/platform/kill-switch`).then(toJson),
  stopPlatform: (reason: string): Promise<JsonObject> =>
    apiFetch(`/api/platform/kill-switch/stop`, {
      method: "POST",
      ...jsonBody({ reason }),
    }).then(toJson),
  resumePlatform: (): Promise<JsonObject> => apiFetch(`/api/platform/kill-switch/resume`, { method: "POST" }).then(toJson),

  // Smart AI governance (PlatformFeatureBudgetService) - the master switch +
  // combined daily USD ceiling over every AI-native platform feature (in-app
  // error explanations, LLM-call diagnosis, model capability summaries), plus
  // a per-feature enabled/dailyUsd override. Live on the next call, backed by
  // platform_setting rows - same posture as the other budget controls.
  // GlobalConfig: { enabled, windowSeconds, masterDailyUsd, defaultFeatureDailyUsd }
  // FeatureConfig: { featureKey, enabled, dailyUsd }
  getSmartAiConfig: (): Promise<JsonObject> => apiFetch(`/api/platform/smart-ai`).then(toJson),
  updateSmartAiConfig: (payload: JsonObject): Promise<JsonObject> =>
    apiFetch(`/api/platform/smart-ai`, {
      method: "PUT",
      ...jsonBody(payload),
    }).then(toJson),
  getSmartAiFeature: (key: string): Promise<JsonObject> =>
    apiFetch(`/api/platform/smart-ai/features/${encodeURIComponent(key)}`).then(toJson),
  updateSmartAiFeature: (key: string, payload: JsonObject): Promise<JsonObject> =>
    apiFetch(`/api/platform/smart-ai/features/${encodeURIComponent(key)}`, {
      method: "PUT",
      ...jsonBody(payload),
    }).then(toJson),

  // Fraud & Anomaly - AbuseMonitorController (/api/platform/abuse-monitor/**),
  // the real P5 surface behind the Fraud & Anomaly page: per-principal risk
  // scores (WATCH+ only), fingerprinted anomaly issues, currently-throttled
  // principals, and a read-only view of the effective thresholds (threshold
  // keys are edited through the generic settings surface on /settings).
  // Both clears are the documented REVERSIBLE un-throttle actions; a SUSPEND
  // tier is reversed via resumeTenant on the Tenants page.
  getAbuseScores: (): Promise<any[]> => apiFetch(`/api/platform/abuse-monitor/scores`).then(toJson),
  getAbusePrincipalScore: (tenantId: string, userId: string): Promise<JsonObject> =>
    apiFetch(`/api/platform/abuse-monitor/scores/${tenantId}/${encodeURIComponent(userId)}`).then(toJson),
  getAbuseTenantScore: (tenantId: string): Promise<JsonObject> =>
    apiFetch(`/api/platform/abuse-monitor/scores/${tenantId}`).then(toJson),
  getAbuseAnomalies: (): Promise<any[]> => apiFetch(`/api/platform/abuse-monitor/anomalies`).then(toJson),
  getAbuseThrottled: (): Promise<any[]> => apiFetch(`/api/platform/abuse-monitor/throttled`).then(toJson),
  getAbuseConfig: (): Promise<JsonObject> => apiFetch(`/api/platform/abuse-monitor/config`).then(toJson),
  clearAbuseThrottle: (tenantId: string, userId: string) =>
    apiFetch(`/api/platform/abuse-monitor/throttle/${tenantId}/${encodeURIComponent(userId)}/clear`, {
      method: "POST",
    }),
  clearAbuseTenantThrottle: (tenantId: string) =>
    apiFetch(`/api/platform/abuse-monitor/throttle/${tenantId}/clear`, { method: "POST" }),

  // Data residency (PlatformTenantDataResidencyService) - declares a tenant's
  // intended jurisdiction (DataResidencyRegion: UNSPECIFIED/INDIA/EU/US/OTHER).
  // Informational only, no data movement - see that service's javadoc. Returns
  // the same partial PlatformTenantSummary shape updateTenantPlan returns.
  updateTenantDataResidency: (tenantId: string, region: string): Promise<PlatformTenantSummary> =>
    apiFetch(`/api/platform/tenants/${tenantId}/data-residency`, {
      method: "PUT",
      ...jsonBody({ region }),
    }).then(toJson),

  // Site-wide promo (SiteWidePromoService) - one-time bulk grant of UNLIMITED
  // to every ACTIVE tenant for durationDays, auto-reverting via the hourly
  // CompGrantExpiryReverter. Idempotent (tenants already on UNLIMITED are
  // skipped); sendEmail is a separate opt-in flag on the same call. Returns
  // SiteWidePromoResult { grantedTenants: [{id,name}], skippedAlreadyUnlimitedTenantIds,
  // grantedAt, expiresAt, reason }.
  grantSiteWidePromo: (payload: JsonObject): Promise<SiteWidePromoResult> =>
    apiFetch(`/api/platform/promos/unlimited-14d`, {
      method: "POST",
      ...jsonBody(payload),
    }).then(toJson),

  // Cross-tenant user administration (PlatformUserController,
  // /api/platform/users/**). Roles are bounded server-side to the tenant-assignable
  // set; platform operators and the caller's own account are refused for every mutation.
  listPlatformUsers: (params: { q?: string; tenantId?: string; limit?: number; offset?: number } = {}): Promise<PlatformUsersPage> => {
    const qs = new URLSearchParams();
    if (params.q) qs.set("q", params.q);
    if (params.tenantId) qs.set("tenantId", params.tenantId);
    if (params.limit) qs.set("limit", String(params.limit));
    if (params.offset) qs.set("offset", String(params.offset));
    return apiFetch(`/api/platform/users${qs.toString() ? `?${qs}` : ""}`).then(toJson);
  },
  getPlatformUser: (id: string): Promise<JsonObject> =>
    apiFetch(`/api/platform/users/${encodeURIComponent(id)}`).then(toJson),
  setPlatformUserEnabled: (id: string, enabled: boolean): Promise<JsonObject> =>
    apiFetch(`/api/platform/users/${encodeURIComponent(id)}/enabled`, {
      method: "PUT",
      ...jsonBody({ enabled }),
    }).then(toJson),
  sendPlatformUserPasswordReset: (id: string): Promise<JsonObject> =>
    apiFetch(`/api/platform/users/${encodeURIComponent(id)}/password-reset-email`, { method: "POST" }).then(toJson),
  setPlatformUserRoles: (id: string, roles: string[]): Promise<JsonObject> =>
    apiFetch(`/api/platform/users/${encodeURIComponent(id)}/roles`, {
      method: "PUT",
      ...jsonBody({ roles }),
    }).then(toJson),

  // Safety & Moderation (ModerationController, /api/platform/moderation/**).
  // Packs: scope GLOBAL|PLAN|TENANT + key (plan name / tenant id). Enabling the
  // CSAM pack is refused server-side with a 409 (held for legal review).
  // Thresholds are per-pack 0-100 ints. The review queue is per-tenant-schema,
  // so a decision must send back the row's own tenantId.
  getModerationPolicyPacks: (scope = "GLOBAL", key = ""): Promise<JsonObject> =>
    apiFetch(`/api/platform/moderation/policy-packs?${new URLSearchParams({ scope, ...(key ? { key } : {}) })}`).then(
      toJson
    ),
  updateModerationPolicyPacks: (payload: JsonObject): Promise<JsonObject> =>
    apiFetch(`/api/platform/moderation/policy-packs`, { method: "PUT", ...jsonBody(payload) }).then(toJson),
  getModerationThresholds: (): Promise<JsonObject> => apiFetch(`/api/platform/moderation/thresholds`).then(toJson),
  updateModerationThresholds: (payload: JsonObject): Promise<JsonObject> =>
    apiFetch(`/api/platform/moderation/thresholds`, { method: "PUT", ...jsonBody(payload) }).then(toJson),
  getModerationReviewQueue: (status = "PENDING"): Promise<JsonObject> =>
    apiFetch(`/api/platform/moderation/review-queue?${new URLSearchParams({ status })}`).then(toJson),
  decideModerationReview: (id: string, payload: JsonObject): Promise<JsonObject> =>
    apiFetch(`/api/platform/moderation/review-queue/${id}/decision`, { method: "POST", ...jsonBody(payload) }).then(
      toJson
    ),
  getModerationBlocklists: (type = ""): Promise<JsonObject> =>
    apiFetch(`/api/platform/moderation/blocklists${type ? `?${new URLSearchParams({ type })}` : ""}`).then(toJson),
  upsertModerationBlocklistEntry: (payload: JsonObject): Promise<JsonObject> =>
    apiFetch(`/api/platform/moderation/blocklists`, { method: "PUT", ...jsonBody(payload) }).then(toJson),

  // Per-tool status refresh (PlatformToolCredentialController) - the list
  // endpoint already returns these same rows; this exists for re-checking a
  // single tool after a save without a full list reload.
  getPlatformToolCredentialStatus: (toolKey: string): Promise<JsonObject> =>
    apiFetch(`/api/platform/tool-credentials/${toolKey}/status`).then(toJson),
};
