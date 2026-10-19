import { apiFetch, toJson, jsonBody } from "./core";
import {
  MeResponseSchema,
  TenantSettingsResponseSchema,
  parseAtBoundary,
  type MeResponse,
  type TenantSettingsResponse,
} from "./schemas";
import type { AdminUserResponse, AiModelPreferenceResponse } from "./types";

export const adminApi = {
  // The caller's own identity + effective granular scopes (granular RBAC,
  // 2026-09-23) - backed by MeController.java. This is the read model every
  // permission-aware screen/nav guard uses; it answers with the same data
  // the server enforces, so UI and backend can never disagree.
  // Validated at the boundary (./schemas): a shape mismatch is logged and
  // degraded (valid fields kept), never thrown - callers keep `?? []` guards.
  getMe: (): Promise<MeResponse | null> =>
    apiFetch(`/api/me`)
      .then(toJson)
      .then((data) => parseAtBoundary(MeResponseSchema, data, "GET /api/me")),

  // Admin user/role management (Phase 0 Stage 3) - backed by
  // UserAdminController.java, gated server-side to PERM_MANAGE_USERS
  // (granular RBAC, 2026-09-23 - was blanket ROLE_tenant-admin).
  listAdminUsers: () => apiFetch(`/api/admin/users`).then(toJson<AdminUserResponse[]>),
  createAdminUser: (payload: object) =>
    apiFetch(`/api/admin/users`, {
      method: "POST",
      ...jsonBody(payload),
    }).then(toJson<AdminUserResponse>),
  // Local auth (no mail server): a fresh one-time invite link for someone who has not joined yet.
  reissueInviteLink: (userId: string) =>
    apiFetch(`/api/admin/users/${userId}/invite-link`, { method: "POST" }).then(toJson<AdminUserResponse>),
  setUserRoles: (userId: string, roles: string[]) =>
    apiFetch(`/api/admin/users/${userId}/roles`, {
      method: "PUT",
      ...jsonBody({ roles }),
    }).then(toJson<AdminUserResponse>),
  // Fine-grained permissions layered on top of roles (2026-08-13) - see
  // Permission.java's javadoc for the catalog and UserAdminController#setPermissions.
  setUserPermissions: (userId: string, permissions: string[]) =>
    apiFetch(`/api/admin/users/${userId}/permissions`, {
      method: "PUT",
      ...jsonBody({ permissions }),
    }).then(toJson<AdminUserResponse>),
  // Direct feedback: "provide disable option on created user" - suspends
  // (enabled: false) or restores (enabled: true) a user's ability to sign
  // in, without deleting the account.
  setUserEnabled: (userId: string, enabled: boolean) =>
    apiFetch(`/api/admin/users/${userId}/enabled`, {
      method: "PUT",
      ...jsonBody({ enabled }),
    }).then(toJson<AdminUserResponse>),

  // Tenant settings (account type + Simple/Advanced default) - backed by
  // TenantSettingsController.java. GET is readable by any signed-in tenant
  // member; PUT (changing experienceMode) is tenant-admin only server-side.
  // Read right after sign-in (AppChromeProvider) for plan/entitlements -
  // validated at the boundary like getMe above.
  getTenantSettings: (): Promise<TenantSettingsResponse | null> =>
    apiFetch(`/api/tenant/settings`)
      .then(toJson)
      .then((data) => parseAtBoundary(TenantSettingsResponseSchema, data, "GET /api/tenant/settings")),
  setTenantExperienceMode: (experienceMode: string) =>
    apiFetch(`/api/tenant/settings`, {
      method: "PUT",
      ...jsonBody({ experienceMode }),
    }).then(toJson<TenantSettingsResponse>),
  // #1085 - "every non free tenant should get an option to select use
  // platform llm." UpdateExperienceModeRequest requires experienceMode on
  // every PUT (it's @NotNull server-side), so this wrapper takes the
  // current experienceMode plus the desired usePlatformLlm value - callers
  // (the Billing/Providers-page toggle) already have the current
  // experienceMode from a prior getTenantSettings() call, same "read
  // current state, send the whole settings object back" shape
  // setTenantExperienceMode's own callers already follow.
  setTenantUsePlatformLlm: (experienceMode: string, usePlatformLlm: boolean) =>
    apiFetch(`/api/tenant/settings`, {
      method: "PUT",
      ...jsonBody({ experienceMode, usePlatformLlm }),
    }).then(toJson<TenantSettingsResponse>),
  // White-label branding (brand_display_name / brand_logo_url, V162) -
  // tenant-admin only, and 403 unless the plan carries the white_label
  // entitlement. Same "send the whole settings object back" shape as the two
  // wrappers above, for the same reason: experienceMode is @NotNull on
  // UpdateExperienceModeRequest, so every PUT has to carry the current one.
  //
  // Per-field convention, enforced server-side in TenantSettingsController:
  // undefined/null = leave unchanged, "" = clear. So pass "" to remove a
  // brand name, not null.
  setTenantBranding: (experienceMode: string, brandDisplayName: string, brandLogoUrl: string) =>
    apiFetch(`/api/tenant/settings`, {
      method: "PUT",
      ...jsonBody({ experienceMode, brandDisplayName, brandLogoUrl }),
    }).then(toJson<TenantSettingsResponse>),

  // Logo UPLOAD (TenantBrandingLogoController) - the alternative to setting
  // brandLogoUrl by hand, which requires the tenant to already be hosting
  // their logo on a public https URL. Posts the raw picked file (PNG/JPEG/
  // WebP, <=2MB; SVG is refused server-side because it can carry scripts),
  // and the server stores it and writes the resulting public URL into
  // brandLogoUrl itself - so the response is a full refreshed
  // TenantSettingsResponse, exactly like the PUT wrappers above, and the
  // caller never constructs the URL.
  //
  // `file` is the File straight from an <input type="file">, NOT a canvas
  // blob: uploadAccountAvatar crops to a 256x256 square because an avatar is
  // rendered in a circle, whereas a logo is rendered at its own aspect ratio
  // (h-7 w-auto on the share page), so cropping it would be wrong.
  uploadTenantBrandingLogo: (file: File) => {
    const formData = new FormData();
    formData.append("file", file, file?.name || "logo");
    return apiFetch(`/api/tenant/branding/logo`, {
      method: "POST",
      body: formData,
    }).then(toJson<TenantSettingsResponse>);
  },
  // Deletes the stored object and clears brandLogoUrl. Idempotent server-side
  // (clearing an already-clear brand is a 200), and returns the refreshed
  // settings like the upload does.
  removeTenantBrandingLogo: () =>
    apiFetch(`/api/tenant/branding/logo`, { method: "DELETE" }).then(toJson<TenantSettingsResponse>),

  // "AI Model Control" settings page - backed by
  // TenantAiModelPreferenceController.java (core/tenancy). GET is readable
  // by any signed-in tenant member; PUT/DELETE (changing a feature's
  // provider/model override) is tenant-admin only server-side. All three
  // return AiModelPreferenceResponse[] - one row per known AiModelFeature
  // (providerName/model both null when no override is set), same
  // "return-the-whole-list" shape the controller uses for every mutation so
  // the page never needs a second round-trip after a save.
  getAiModelPreferences: () => apiFetch(`/api/tenant/ai-model-preferences`).then(toJson<AiModelPreferenceResponse[]>),
  // Both providerName and model are required by the backend (400 if either
  // is missing) - use clearAiModelPreference to remove an override instead
  // of sending nulls here, same "PUT is never ambiguous about intent"
  // posture the controller's javadoc documents.
  setAiModelPreference: (featureKey: string, providerName: string, model: string) =>
    apiFetch(`/api/tenant/ai-model-preferences/${encodeURIComponent(featureKey)}`, {
      method: "PUT",
      ...jsonBody({ providerName, model }),
    }).then(toJson<AiModelPreferenceResponse[]>),
  clearAiModelPreference: (featureKey: string) =>
    apiFetch(`/api/tenant/ai-model-preferences/${encodeURIComponent(featureKey)}`, {
      method: "DELETE",
    }).then(toJson<AiModelPreferenceResponse[]>),
};
