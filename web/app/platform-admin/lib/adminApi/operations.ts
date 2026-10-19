import { apiFetch, toJson, toJsonQuiet, jsonBody } from "../api";

// Operations console reads/writes (core PlatformOps* controllers, platform-admin
// only). Contract agreed with core-3 for the Operations group (/ops, /maintenance,
// /jobs, /migrations, /email-suppressions, session revocation):
//
//   GET  /api/platform/version
//   GET  /api/platform/maintenance            PUT (X-Master-Key)
//   GET  /api/platform/announcement           PUT
//   GET  /api/platform/jobs                   POST /jobs/{id}/run (X-Master-Key)
//   GET  /api/platform/migrations
//   POST /api/platform/users/{keycloakUserId}/logout
//   POST /api/platform/tenants/{tenantId}/logout-all
//   GET  /api/platform/email/suppressions     DELETE /email/suppressions/{email}
//
// Every nullable field means "core could not tell" and is rendered as such -
// never coerced to 0 / "ok" client-side.
//
// Master-key writes pass the key in X-Master-Key alongside Content-Type in ONE
// headers object - jsonBody() returns its own `headers`, which would silently
// clobber X-Master-Key if spread after it (same trap api.deletePlatformTenant
// documents).

export type ServiceStatus = "ok" | "unreachable" | "unknown";

export interface ServiceVersion {
  name: string;
  version: string | null;
  gitSha: string | null;
  buildTime: string | null;
  status: ServiceStatus;
}

export interface VersionsResponse {
  services: ServiceVersion[];
}

export interface MaintenanceState {
  enabled: boolean;
  message: string;
  endsAt: string | null;
  startedAt: string | null;
}

export type AnnouncementLevel = "info" | "warning" | "critical";

export interface AnnouncementState {
  enabled: boolean;
  message: string;
  level: AnnouncementLevel;
  linkUrl: string | null;
  expiresAt: string | null;
}

export type JobStatus = "SUCCESS" | "FAILED" | "RUNNING" | null;

export interface ScheduledJob {
  id: string;
  bean: string;
  method: string;
  schedule: string;
  lastStartedAt: string | null;
  lastFinishedAt: string | null;
  lastStatus: JobStatus;
  lastError: string | null;
  lastDurationMs: number | null;
  runNowAllowed: boolean;
}

export interface MigrationCounts {
  version: string | null;
  pending: number;
  failed: number;
}

export interface TenantMigration {
  tenantId: string;
  name: string;
  schema: string;
  version: string | null;
  pending: number;
  failed: number;
  status: string;
}

export interface MigrationsResponse {
  controlPlane: MigrationCounts;
  expectedTenantVersion: string | null;
  tenants: TenantMigration[];
}

export interface EmailSuppression {
  email: string;
  reason: string;
  source: string;
  createdAt: string;
}

export interface EmailSuppressionPage {
  items: EmailSuppression[];
  total: number;
}

function masterKeyJson(masterKey: string, payload?: unknown) {
  return {
    headers: { "Content-Type": "application/json", "X-Master-Key": masterKey },
    ...(payload === undefined ? {} : { body: JSON.stringify(payload) }),
  };
}

/** "?a=1&b=x" from the non-empty entries, "" when nothing is set. */
export function queryString(params: Record<string, string | number | null | undefined>): string {
  const qs = new URLSearchParams();
  for (const [k, v] of Object.entries(params)) {
    if (v === null || v === undefined || v === "") continue;
    qs.set(k, String(v));
  }
  const s = qs.toString();
  return s ? `?${s}` : "";
}

export const opsApi = {
  versions: (): Promise<VersionsResponse> => apiFetch(`/api/platform/version`, { quiet: true }).then(toJsonQuiet),

  maintenance: (): Promise<MaintenanceState> => apiFetch(`/api/platform/maintenance`).then(toJson),
  /** Quiet variant for the /ops hub, which shows its own "unavailable" card. */
  maintenanceQuiet: (): Promise<MaintenanceState> =>
    apiFetch(`/api/platform/maintenance`, { quiet: true }).then(toJsonQuiet),
  setMaintenance: (
    payload: { enabled: boolean; message: string; endsAt: string | null },
    masterKey: string
  ): Promise<MaintenanceState> =>
    apiFetch(`/api/platform/maintenance`, { method: "PUT", ...masterKeyJson(masterKey, payload) }).then(toJson),

  announcement: (): Promise<AnnouncementState> => apiFetch(`/api/platform/announcement`).then(toJson),
  announcementQuiet: (): Promise<AnnouncementState> =>
    apiFetch(`/api/platform/announcement`, { quiet: true }).then(toJsonQuiet),
  setAnnouncement: (payload: AnnouncementState): Promise<AnnouncementState> =>
    apiFetch(`/api/platform/announcement`, { method: "PUT", ...jsonBody(payload) }).then(toJson),

  jobs: (): Promise<ScheduledJob[]> => apiFetch(`/api/platform/jobs`).then(toJson),
  jobsQuiet: (): Promise<ScheduledJob[]> => apiFetch(`/api/platform/jobs`, { quiet: true }).then(toJsonQuiet),
  runJob: (id: string, masterKey: string): Promise<{ started: boolean }> =>
    apiFetch(`/api/platform/jobs/${encodeURIComponent(id)}/run`, { method: "POST", ...masterKeyJson(masterKey) }).then(
      toJson
    ),

  migrations: (): Promise<MigrationsResponse> => apiFetch(`/api/platform/migrations`).then(toJson),
  migrationsQuiet: (): Promise<MigrationsResponse> =>
    apiFetch(`/api/platform/migrations`, { quiet: true }).then(toJsonQuiet),

  logoutUser: (keycloakUserId: string): Promise<{ ok: boolean }> =>
    apiFetch(`/api/platform/users/${encodeURIComponent(keycloakUserId)}/logout`, { method: "POST" }).then(toJson),
  logoutTenant: (tenantId: string): Promise<{ usersLoggedOut: number }> =>
    apiFetch(`/api/platform/tenants/${encodeURIComponent(tenantId)}/logout-all`, { method: "POST" }).then(toJson),

  emailSuppressions: (params: { q?: string; page?: number; limit?: number }): Promise<EmailSuppressionPage> =>
    apiFetch(`/api/platform/email/suppressions${queryString(params)}`).then(toJson),
  removeEmailSuppression: (email: string): Promise<null> =>
    apiFetch(`/api/platform/email/suppressions/${encodeURIComponent(email)}`, { method: "DELETE" }).then(toJson),
};
