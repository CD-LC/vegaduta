import { apiFetch, toJson, jsonBody } from "./core";

// Tenant REST API keys (core/tenancy/apikey, /api/tenant/api-keys, V483) -
// machine credentials for headless callers (CI, partner systems, scripts)
// that call /api/** with "Authorization: Bearer vdak_..." instead of an
// OIDC login. Every route is PERM_MANAGE_USERS server-side; the key's
// scopes are a subset of the issuer's own permissions (enforced server-side
// too - the checkbox UI here is convenience, not the guard). The plaintext
// key exists only in the create/rotate response - it is hash-only at rest.
export type TenantApiKeyResponse = {
  id: string;
  name: string;
  keyPrefix: string;
  permissions: string[];
  createdBy: string | null;
  expiresAt: string | null;
  lastUsedAt: string | null;
  enabled: boolean;
  expired: boolean;
  createdAt: string;
  updatedAt: string;
};

export type TenantApiKeyCreatedResponse = {
  key: TenantApiKeyResponse;
  apiKey: string; // plaintext - shown exactly once, then unrecoverable
};

export const apiKeysApi = {
  listApiKeys: () => apiFetch(`/api/tenant/api-keys`).then(toJson<TenantApiKeyResponse[]>),
  createApiKey: (payload: { name: string; permissions: string[]; expiresAt?: string | null }) =>
    apiFetch(`/api/tenant/api-keys`, {
      method: "POST",
      ...jsonBody(payload),
    }).then(toJson<TenantApiKeyCreatedResponse>),
  rotateApiKey: (id: string) =>
    apiFetch(`/api/tenant/api-keys/${id}/rotate`, { method: "POST" }).then(toJson<TenantApiKeyCreatedResponse>),
  setApiKeyEnabled: (id: string, enabled: boolean) =>
    apiFetch(`/api/tenant/api-keys/${id}`, {
      method: "PATCH",
      ...jsonBody({ enabled }),
    }).then(toJson<TenantApiKeyResponse>),
  revokeApiKey: (id: string) =>
    apiFetch(`/api/tenant/api-keys/${id}`, { method: "DELETE" }).then(toJson),
};
