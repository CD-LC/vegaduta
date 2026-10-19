import { apiFetch, toJson, jsonBody } from "./core";

// Owner side of published Phone-App-Studio apps - /api/studio-apps on core.
// The PUBLIC half (/api/public/apps/{slug}: manifest, end-user signup/login,
// call/{alias}, ai/chat) is anonymous and deliberately NOT called through this
// apiFetch client - the runner page calls it from its own origin, the same
// separation sharing.ts already documents for the agent/workflow share
// endpoints.

// Core's StudioApp.AuthMode enum is NONE|OPTIONAL|REQUIRED; the studio UI
// calls its "no sign-in" mode "off". Translate at the wire boundary - the
// server rejects anything it can't parse with bad_auth_mode, so unknown
// values pass through untouched to fail loudly rather than silently
// downgrading a gated app to no-auth.
const AUTH_MODE_TO_SERVER: Record<string, string> = {
  off: "NONE",
  optional: "OPTIONAL",
  required: "REQUIRED",
};

const serverAuthMode = (mode: unknown): string | undefined =>
  AUTH_MODE_TO_SERVER[String(mode ?? "").trim().toLowerCase()] ??
  (typeof mode === "string" && mode.trim() ? mode.trim() : undefined);

export const studioAppsApi = {
  listStudioApps: () => apiFetch(`/api/studio-apps`).then(toJson),
  getStudioApp: (appId: string) => apiFetch(`/api/studio-apps/${appId}`).then(toJson),
  publishStudioApp: (payload: Record<string, unknown>) =>
    apiFetch(`/api/studio-apps/publish`, {
      method: "POST",
      ...jsonBody({ ...payload, authMode: serverAuthMode(payload?.authMode) }),
    }).then(toJson<{ appId: string; slug: string; publicUrl: string; publishedVersion: number }>),
  disableStudioApp: (appId: string) =>
    apiFetch(`/api/studio-apps/${appId}/disable`, { method: "POST" }).then((r) => r.ok),
  enableStudioApp: (appId: string) =>
    apiFetch(`/api/studio-apps/${appId}/enable`, { method: "POST" }).then((r) => r.ok),
  deleteStudioApp: (appId: string) =>
    apiFetch(`/api/studio-apps/${appId}`, { method: "DELETE" }).then((r) => r.ok),
  listStudioAppUsers: (appId: string) => apiFetch(`/api/studio-apps/${appId}/users`).then(toJson),
  disableStudioAppUser: (appId: string, userId: string) =>
    apiFetch(`/api/studio-apps/${appId}/users/${userId}/disable`, { method: "POST" }).then((r) => r.ok),
  enableStudioAppUser: (appId: string, userId: string) =>
    apiFetch(`/api/studio-apps/${appId}/users/${userId}/enable`, { method: "POST" }).then((r) => r.ok),
  deleteStudioAppUser: (appId: string, userId: string) =>
    apiFetch(`/api/studio-apps/${appId}/users/${userId}`, { method: "DELETE" }).then((r) => r.ok),
};
