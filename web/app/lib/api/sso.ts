import { apiFetch, jsonBody, toJson } from "./core";

// Per-tenant BYO-IdP SSO and SCIM tokens (core/admin sso, TenantSsoController): /api/tenant/sso/**.
// Needs MANAGE_TENANT_SETTINGS. Keycloak-only: under the Auth0 adapter the surface is off (404).
// Deliberately NOT spread into the `api` barrel: only /admin/sso reads this.

export type SsoProviderType = "OIDC" | "SAML";

export interface SsoStatusDto {
  configured: boolean;
  providerType: SsoProviderType | null;
  displayName: string | null;
  alias: string | null;
  /** null = not pushed to the realm (distinct from disabled). */
  realmEnabled: boolean | null;
  clientSecretSet: boolean;
  brokerRedirectUri: string | null;
}

export interface SsoUpsertRequest {
  providerType: SsoProviderType;
  displayName: string;
  enabled: boolean;
  oidcDiscoveryUrl?: string;
  oidcAuthorizationUrl?: string;
  oidcTokenUrl?: string;
  oidcUserInfoUrl?: string;
  oidcJwksUrl?: string;
  oidcIssuer?: string;
  oidcClientId?: string;
  /** Write-only: blank on update keeps the stored secret. */
  clientSecret?: string;
  samlEntityId?: string;
  samlSsoUrl?: string;
  samlX509Cert?: string;
  samlNameIdFormat?: string;
}

export interface ScimTokenDto {
  id: string;
  label: string;
  createdAt: string | null;
  lastUsedAt: string | null;
}

export interface MintedScimToken {
  id: string;
  /** Plaintext, returned exactly once. */
  token: string;
  label: string;
}

export const ssoApi = {
  status: () => apiFetch("/api/tenant/sso").then(toJson<SsoStatusDto>),
  save: (body: SsoUpsertRequest) =>
    apiFetch("/api/tenant/sso", { method: "PUT", ...jsonBody(body) }).then(toJson<SsoStatusDto>),
  remove: async (): Promise<void> => {
    const res = await apiFetch("/api/tenant/sso", { method: "DELETE" });
    if (!res.ok) await toJson(res);
  },
  listTokens: () => apiFetch("/api/tenant/sso/scim-tokens").then(toJson<ScimTokenDto[]>),
  mintToken: (label: string) =>
    apiFetch("/api/tenant/sso/scim-tokens", { method: "POST", ...jsonBody({ label }) }).then(
      toJson<MintedScimToken>,
    ),
  revokeToken: async (id: string): Promise<void> => {
    const res = await apiFetch(`/api/tenant/sso/scim-tokens/${encodeURIComponent(id)}`, { method: "DELETE" });
    if (!res.ok) await toJson(res);
  },
};
