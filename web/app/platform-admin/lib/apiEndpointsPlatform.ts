import { apiFetch, toJson, jsonBody } from "./apiCore";
import type {
  JsonObject,
  IssuesOpenCount,
  McpRegistrySearchResult,
  PluginArtifactUploadResult,
  WebSearchProvidersStatus,
  HostedLlmModelDiscovery,
} from "./apiCore";
import type { PlatformTenantSummary } from "./tenantRoster";

// Platform-admin endpoints, part 1 (LLM tasks, settings, flags, cost). Split out of api.ts verbatim.
export const platformEndpointsA = {
  // System status (shared SystemStatusPanel) - same SystemStatusResponse
  // shape the tenant console's Dashboard reads from getSystemStatus(); this
  // app instead calls the platform-wide getPlatformHealth() below for the
  // page's own "Shared infrastructure" section, but getSystemStatus is kept
  // here too in case a future screen wants the non-platform-scoped reading.
  getSystemStatus: (): Promise<JsonObject> => apiFetch(`/api/system/status`).then(toJson),

  // Ecosystem Maintenance (cross-tenant super-admin console) - backed by
  // PlatformController.java, gated server-side to ROLE_platform-admin. Every
  // call below is platform-wide, never scoped to a single tenant.
  getPlatformLlmTasks: (): Promise<any[]> => apiFetch(`/api/platform/llm-tasks`).then(toJson),
  updatePlatformLlmTask: (task: string, payload: JsonObject): Promise<JsonObject> =>
    apiFetch(`/api/platform/llm-tasks/${task}`, {
      method: "PUT",
      ...jsonBody(payload),
    }).then(toJson),
  // Central "set all these values" operational settings control (direct
  // feedback after hitting SignupRateLimiter's hardcoded knobs) - generic
  // key-value list, see PlatformSettingsService's javadoc.
  getPlatformSettings: (): Promise<any[]> => apiFetch(`/api/platform/settings`).then(toJson),
  updatePlatformSetting: (key: string, value: unknown): Promise<JsonObject> =>
    apiFetch(`/api/platform/settings/${encodeURIComponent(key)}`, {
      method: "PUT",
      ...jsonBody({ value }),
    }).then(toJson),
  // Edge AI model catalog editor (edge-models/page.tsx). READ side is the
  // public /api/edge/manifest/full (effective + in-code defaults + whether a
  // stored override exists - see EdgeAgentsConfigController); the WRITE side
  // deliberately reuses updatePlatformSetting("edge.model-manifest", json)
  // above rather than a bespoke endpoint.
  getEdgeManifestFull: (): Promise<JsonObject> => apiFetch(`/api/edge/manifest/full`).then(toJson),
  // "Browse official registry" discovery panel on the same screen - curated
  // WebLLM candidates not yet in the catalog (EdgeAgentsConfigController's
  // REGISTRY_CATALOG). Admin clicks "Add", which folds the candidate into
  // the same enabled-map + updatePlatformSetting save path above - no
  // separate write endpoint.
  getEdgeRegistry: (): Promise<any[]> => apiFetch(`/api/edge/manifest/registry`).then(toJson),
  // First-party IDE/browser plugin distribution (plugins/page.tsx). READ is
  // the same public catalog the web marketplace page serves
  // (PluginCatalogController - the `plugins.catalog` platform setting over an
  // in-code seeded default); the WRITE side deliberately reuses the generic
  // platform-settings PUT rather than a bespoke endpoint - same posture as
  // the edge manifest editor above.
  // `any` (not JsonObject): plugins/page.tsx assigns the result straight to
  // its local `PluginCatalog` type, and a Record isn't assignable to a
  // required-prop interface.
  getPluginCatalog: (): Promise<any> => apiFetch(`/api/plugins/catalog`).then(toJson),
  savePluginCatalog: (catalogJson: string): Promise<JsonObject> =>
    apiFetch(`/api/platform/settings/${encodeURIComponent("plugins.catalog")}`, {
      method: "PUT",
      ...jsonBody({ value: catalogJson }),
    }).then(toJson),
  // Artifact upload (PluginArtifactController, POST /api/platform/plugins/
  // artifacts). Multipart, so deliberately NO Content-Type header - the
  // browser must set its own multipart boundary; jsonBody() here would break
  // the request. Returns { pluginId, version, artifact: { fileName,
  // sizeBytes, sha256 } } - the exact fragment the plugins screen merges into
  // the catalog entry before savePluginCatalog above.
  uploadPluginArtifact: (pluginId: string, version: string, file: File | Blob): Promise<PluginArtifactUploadResult> => {
    const form = new FormData();
    form.append("pluginId", pluginId);
    form.append("version", version);
    form.append("file", file);
    return apiFetch(`/api/platform/plugins/artifacts`, { method: "POST", body: form }).then(toJson);
  },
  // Platform-admin-settable credentials for tool-capability providers with no
  // prior admin path (ElevenLabs voice, E2B code execution) - 2026-08-03. One
  // encrypted row per tool (PlatformToolCredentialController), never echoes
  // the decrypted key back - status responses only.
  // MCP registry visibility (McpRegistryController - core/mcp). The catalog
  // is a fixed, hand-maintained list shared by every tenant (not persisted,
  // not admin-editable here - see McpCatalogService's javadoc); this is a
  // read-only view so platform admins can see what every tenant's "Browse
  // catalog" tab offers without reading Java source. /search is the same
  // live pass-through to the public MCP Registry that web/'s RegistryBrowser
  // uses, reused here for the same "what's out there" visibility.
  getMcpCatalog: (): Promise<any[]> => apiFetch(`/api/mcp/registry/catalog`).then(toJson),
  searchMcpRegistry: (q: string, cursor: string | null): Promise<McpRegistrySearchResult> =>
    apiFetch(
      `/api/mcp/registry/search?${new URLSearchParams({ ...(q ? { q } : {}), ...(cursor ? { cursor } : {}) })}`
    ).then(toJson),
  // MCP-STDIO-SANDBOXING review queue (McpRegistryReviewController -
  // core/agents, /api/platform/mcp-review/**) - the platform-staff sign-off
  // gate self-service custom/registry-browsed STDIO registrations must clear
  // before they're ever allowed to spawn (McpReviewStatus.java's javadoc).
  // Genuinely cross-tenant (unlike getMcpCatalog above, which is the same
  // fixed list for everyone) - each pending row carries its own tenantId,
  // required to target approve/reject at the right tenant schema since a
  // registration's UUID is only unique within its own tenant.
  listPendingMcpReviews: (): Promise<any[]> => apiFetch(`/api/platform/mcp-review/pending`).then(toJson),
  approveMcpReview: (tenantId: string, id: string): Promise<JsonObject> =>
    apiFetch(`/api/platform/mcp-review/${tenantId}/${id}/approve`, { method: "POST" }).then(toJson),
  rejectMcpReview: (tenantId: string, id: string, reason?: string): Promise<JsonObject> =>
    apiFetch(`/api/platform/mcp-review/${tenantId}/${id}/reject`, {
      method: "POST",
      ...jsonBody({ reason: reason || null }),
    }).then(toJson),
  // Store compliance (Play AI-Generated Content policy) - user reports of
  // offensive AI output from every tenant, filed via POST /api/ai-reports in
  // the apps. AiContentReportController (core/tenancy), ROLE_platform-admin.
  // status: OPEN | REVIEWED | ACTIONED | DISMISSED, or empty for all.
  listAiReports: (status?: string, limit = 100, offset = 0): Promise<JsonObject> => {
    const params = new URLSearchParams({ limit: String(limit) });
    if (offset > 0) params.set("offset", String(offset));
    if (status) params.set("status", status);
    return apiFetch(`/api/platform/ai-reports?${params.toString()}`).then(toJson);
  },
  reviewAiReport: (id: string, status: string, reviewNote?: string): Promise<any> =>
    apiFetch(`/api/platform/ai-reports/${encodeURIComponent(id)}`, {
      method: "PATCH",
      ...jsonBody({ status, reviewNote: reviewNote || null }),
    }).then(toJson),
  listPlatformToolCredentials: (): Promise<any[]> => apiFetch(`/api/platform/tool-credentials`).then(toJson),
  activatePlatformToolCredential: (toolKey: string, apiKey: string, extraConfig?: string | null): Promise<JsonObject> =>
    apiFetch(`/api/platform/tool-credentials/${toolKey}`, {
      method: "POST",
      ...jsonBody({ apiKey, extraConfig }),
    }).then(toJson),
  deactivatePlatformToolCredential: (toolKey: string) =>
    apiFetch(`/api/platform/tool-credentials/${toolKey}`, { method: "DELETE" }),
  // Read-only web_search provider picture (WebSearchProvidersController): which providers are configured,
  // which is primary/fallback, which would answer now. "Configured" = key/URL present, never a live check.
  getWebSearchProviders: (): Promise<WebSearchProvidersStatus> =>
    apiFetch(`/api/platform/web-search/providers`).then(toJson),
  // Master Roadmap 2026 item #13 (A/B testing & feature flags, §B) - CRUD
  // over FeatureFlag rows, plus a per-flag assignment list (which tenants
  // landed in which variant) - see PlatformController's javadoc.
  getFeatureFlags: (): Promise<any[]> => apiFetch(`/api/platform/feature-flags`).then(toJson),
  upsertFeatureFlag: (key: string, payload: JsonObject): Promise<JsonObject> =>
    apiFetch(`/api/platform/feature-flags/${encodeURIComponent(key)}`, {
      method: "PUT",
      ...jsonBody(payload),
    }).then(toJson),
  deleteFeatureFlag: (key: string) =>
    apiFetch(`/api/platform/feature-flags/${encodeURIComponent(key)}`, { method: "DELETE" }),
  getFeatureFlagAssignments: (key: string): Promise<any[]> =>
    apiFetch(`/api/platform/feature-flags/${encodeURIComponent(key)}/assignments`).then(toJson),
  getPlatformTranslationJob: (): Promise<JsonObject> => apiFetch(`/api/platform/translation-job`).then(toJson),
  updatePlatformTranslationJob: (payload: JsonObject): Promise<JsonObject> =>
    apiFetch(`/api/platform/translation-job`, {
      method: "PUT",
      ...jsonBody(payload),
    }).then(toJson),
  runPlatformTranslationJobNow: (): Promise<JsonObject> =>
    apiFetch(`/api/platform/translation-job/run-now`, { method: "POST" }).then(toJson),
  // "Write a pipeline or job like the translator to do this" - the model
  // catalog's own manual run-now, same shape as the translation job's above.
  // ProviderModelCatalogService already refreshes daily at 03:00 UTC; this
  // just doesn't make an admin wait for that window after e.g. hand-editing
  // SEED_MODELS or wanting a fresh OpenRouter pull.
  refreshPlatformModelCatalogNow: (): Promise<JsonObject> =>
    apiFetch(`/api/platform/model-catalog/refresh-now`, { method: "POST" }).then(toJson),
  // Same cached-catalog dropdown data the tenant BYOK model picker
  // (web/app/components/ProvidersPanel.tsx) reads - instant, no API key
  // needed. Used by the hosted-pool form's "generic provider" mode so an
  // admin sees real model ids for OPENAI/GROK/OPENROUTER/GEMINI/META without a key.
  listProviderModels: (providerName: string): Promise<any[]> =>
    apiFetch(`/api/providers/${providerName}/models`).then(toJson),
  getPlatformTenants: (): Promise<PlatformTenantSummary[]> => apiFetch(`/api/platform/tenants`).then(toJson),
  // Registered tenants vs anonymous /try sandboxes as separate figures (the
  // roster above lists registered tenants only). Run the result through
  // lib/tenantCounts.ts - a core that predates the endpoint 404s.
  getPlatformTenantCounts: (): Promise<JsonObject> => apiFetch(`/api/platform/tenant-counts`).then(toJson),
  getPlatformHealth: (): Promise<JsonObject> => apiFetch(`/api/platform/health`).then(toJson),

  // Subscription Plan management page - super-admin override of a tenant's
  // TenantPlan (FREE/PREMIUM/UNLIMITED). Returns a partial PlatformTenantSummary
  // (metricsComplete: false, counts zeroed - see PlatformTenantPlanService's
  // javadoc) - the caller should merge tenantId/name/status/plan into its
  // existing row from getPlatformTenants() rather than replace the whole row.
  updateTenantPlan: (tenantId: string, plan: string): Promise<PlatformTenantSummary> =>
    apiFetch(`/api/platform/tenants/${tenantId}/plan`, {
      method: "PUT",
      ...jsonBody({ plan }),
    }).then(toJson),

  // Fail-safe / per-tenant kill switch (TenantEmergencyControlService,
  // already shipped server-side - this is the first UI wiring for it).
  // Blocks every authenticated request from that tenant's users at
  // TenantContextFilter, effectively making the app unusable for them
  // immediately after they sign in, without touching any of their data.
  // Fully reversible via resumeTenant.
  stopTenant: (tenantId: string, reason: string): Promise<JsonObject> =>
    apiFetch(`/api/platform/tenants/${tenantId}/emergency-stop`, {
      method: "POST",
      ...jsonBody({ reason }),
    }).then(toJson),
  resumeTenant: (tenantId: string): Promise<JsonObject> =>
    apiFetch(`/api/platform/tenants/${tenantId}/emergency-resume`, { method: "POST" }).then(toJson),

  // Platform-admin "delete tenant completely" control (2026-08-07,
  // TenantDeletionController) - irreversible. masterKey is never persisted
  // (the caller holds it in memory only, for the current browser session)
  // and is re-sent on every call - the server re-validates it every time,
  // this is not a one-time unlock. jsonBody() isn't used here because its
  // returned `headers` key would silently clobber X-Master-Key in the
  // later object-spread merge below - build the header set explicitly instead.
  deletePlatformTenant: (
    tenantId: string,
    { confirmTenantName, masterKey }: { confirmTenantName: string; masterKey: string }
  ): Promise<JsonObject> =>
    apiFetch(`/api/platform/tenants/${tenantId}`, {
      method: "DELETE",
      headers: { "Content-Type": "application/json", "X-Master-Key": masterKey },
      body: JSON.stringify({ confirmTenantName }),
    }).then(toJson),

  // Legacy per-visitor demo sandboxes (see lib/demoSandboxes.ts). GET returns
  // { shared, reclaimable, total }; the shared /try tenant is never reclaimable.
  // Reclaim is irreversible and gated exactly like tenant delete: the same
  // in-memory master key in X-Master-Key, plus a literal confirm word. No
  // tenantIds = "server picks the next batch". Server caps 50 per call.
  getDemoSandboxes: (): Promise<JsonObject> => apiFetch(`/api/platform/demo-sandboxes`).then(toJson),
  reclaimDemoSandboxes: ({
    tenantIds,
    masterKey,
    confirm,
  }: {
    tenantIds?: string[];
    masterKey: string;
    confirm: string;
  }): Promise<JsonObject> =>
    apiFetch(`/api/platform/demo-sandboxes/reclaim`, {
      method: "POST",
      headers: { "Content-Type": "application/json", "X-Master-Key": masterKey },
      body: JSON.stringify({ ...(tenantIds && tenantIds.length ? { tenantIds } : {}), confirm }),
    }).then(toJson),

  // Platform-hosted LLM provider pool (FREE-tier pool) - apiKey is
  // write-only: never returned by GET, and omitted/blank on an update leaves
  // the stored key untouched (see PlatformHostedLlmProvider#apply).
  getPlatformHostedLlmProviders: (): Promise<any[]> =>
    apiFetch(`/api/platform/hosted-llm-providers`).then(toJson),
  createPlatformHostedLlmProvider: (payload: JsonObject): Promise<JsonObject> =>
    apiFetch(`/api/platform/hosted-llm-providers`, {
      method: "POST",
      ...jsonBody(payload),
    }).then(toJson),
  updatePlatformHostedLlmProvider: (id: string, payload: JsonObject): Promise<JsonObject> =>
    apiFetch(`/api/platform/hosted-llm-providers/${id}`, {
      method: "PUT",
      ...jsonBody(payload),
    }).then(toJson),
  deletePlatformHostedLlmProvider: (id: string) =>
    apiFetch(`/api/platform/hosted-llm-providers/${id}`, { method: "DELETE" }),

  // "Intelligently list all available models from the provider" (2026-07-26
  // feedback) - live probe of a raw baseUrl+key's real GET {baseUrl}/v1/models
  // endpoint, for the hosted-pool form's model dropdown when the row is a
  // custom/preset baseUrl (Groq, Together AI, ...) rather than one of the
  // known ProviderName values. Never throws server-side - resolves to
  // { models: [] } on any upstream failure, so callers can treat an empty
  // list as "couldn't discover, fall back to manual entry" rather than an error.
  discoverHostedLlmModels: (
    baseUrl: string,
    apiKey: string,
    completionsPath?: string | null
  ): Promise<HostedLlmModelDiscovery> =>
    apiFetch(`/api/platform/hosted-llm-providers/discover-models`, {
      method: "POST",
      ...jsonBody({ baseUrl, apiKey, completionsPath: completionsPath || null }),
    }).then(toJson),

  // Master switch for the whole hosted-LLM pool - pauses FREE-tier chat/
  // Ask-AI/AI-workflow-generator without touching individual provider rows.
  getHostedLlmPoolSettings: (): Promise<JsonObject> =>
    apiFetch(`/api/platform/hosted-llm-providers/settings`).then(toJson),
  setHostedLlmPoolEnabled: (enabled: boolean): Promise<JsonObject> =>
    apiFetch(`/api/platform/hosted-llm-providers/settings`, {
      method: "PUT",
      ...jsonBody({ enabled }),
    }).then(toJson),

  // Platform Observability page - PlatformObservabilityService's three
  // cross-tenant read endpoints (tickets/errors, LLM usage/cost per tenant,
  // recent client activity). See that service's javadoc for the exact
  // per-tenant/merged row caps and the "execution"/"platform usage" scope
  // boundary.
  getPlatformIssues: (): Promise<any[]> => apiFetch(`/api/platform/issues`).then(toJson),
  // { open, failedTenants } - server-side count of OPEN issues, so the
  // overview no longer downloads the full capped issue list to count it.
  getPlatformIssuesOpenCount: (): Promise<IssuesOpenCount> =>
    apiFetch(`/api/platform/issues/open-count`).then(toJson),
  getPlatformLlmUsage: (): Promise<any[]> => apiFetch(`/api/platform/llm-usage`).then(toJson),
  getPlatformActivity: (): Promise<any[]> => apiFetch(`/api/platform/activity`).then(toJson),
  // #973 - "consider the price, charges to get the open source llm from
  // open router or any other platform" - PLATFORM_HOSTED-only cost totals
  // (what FREE-tier traffic actually costs the platform), distinct from
  // getPlatformLlmUsage above which blends every provider a tenant uses.
  getPlatformHostedLlmCost: (): Promise<JsonObject> => apiFetch(`/api/platform/hosted-llm-cost`).then(toJson),
  // Cost-dashboard trend line (day-bucketed PLATFORM_HOSTED totals across
  // every active tenant) + the admin-configurable all-time budget line the
  // "remaining balance" figure is derived from.
  getPlatformHostedLlmCostByDay: (days = 30): Promise<any[]> =>
    apiFetch(`/api/platform/hosted-llm-cost/by-day?days=${days}`).then(toJson),
  updatePlatformHostedLlmBudget: (budgetUsd: number): Promise<JsonObject> =>
    apiFetch(`/api/platform/hosted-llm-cost/budget`, {
      method: "PUT",
      ...jsonBody({ budgetUsd }),
    }).then(toJson),
};
