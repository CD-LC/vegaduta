import { apiFetch, toJson, toJsonQuietOn, jsonBody } from "./core";

// Agent Trust & Commerce surface - delegation grants/tokens, the signed
// Know-Your-Agent manifest, the public agent directory, agent wallets, AP2
// commerce mandates, and x402 pay-per-call offers (core/agents/trust,
// core/agents/directory, core/payments/wallet, core/payments/commerce,
// core/agents-tools tools/x402). All amounts are integer minor units end to end.
// Reads are quiet on 403/404/405 so an older core degrades to "not enabled",
// same posture as payments.ts.

// ---------------------------------------------------------------- delegation

export interface CreateDelegationGrantRequest {
  audience: string;
  scopes: string[];
  maxTokenTtlSeconds?: number | null;
  expiresAt: string; // ISO-8601
  walletId?: string | null;
  /** Lifetime spend cap in minor units across all tokens; pay:* only. */
  spendCapMinor?: number | null;
}

export interface DelegationGrant {
  id: string;
  agentId: string;
  grantedBy: string;
  audience: string;
  scopes: string[];
  maxTokenTtlSeconds: number;
  expiresAt: string;
  status: "ACTIVE" | "REVOKED" | "EXPIRED";
  walletId?: string | null;
  spendCapMinor?: number | null;
  spentMinor: number;
  createdAt: string;
  revokedAt?: string | null;
  revokeReason?: string | null;
}

export interface MintedDelegationToken {
  token: string;
  jti: string;
  expiresAt: string;
  grantId: string;
}

export interface DelegationIntrospection {
  active: boolean;
  sub?: string;
  agent_id?: string;
  act?: { sub?: string };
  tenant_id?: string;
  aud?: string;
  scope?: string;
  exp?: number;
  jti?: string;
  grant_id?: string;
  token_use?: string;
  reason?: string;
}

// ----------------------------------------------------------------- directory

export interface DirectoryEntry {
  agentId: string;
  displayName?: string | null;
  description?: string | null;
  capabilities: string[];
  homepageUrl?: string | null;
  status: "PUBLISHED" | "UNPUBLISHED";
  publishedAt?: string | null;
  updatedAt?: string | null;
  card?: Record<string, any> | null;
  manifest?: Record<string, any> | null;
}

export interface PublishDirectoryRequest {
  displayName?: string | null;
  description?: string | null;
  capabilities?: string[] | null;
  homepageUrl?: string | null;
}

// ------------------------------------------------------------------- wallets

export interface AgentWallet {
  id: string;
  agentId: string;
  currency: string;
  balanceMinor: number;
  status: "ACTIVE" | "FROZEN" | "CLOSED";
  createdAt: string;
  updatedAt: string;
}

export interface WalletLedgerEntry {
  id: number;
  walletId: string;
  entryKind: string;
  amountMinor: number;
  balanceAfter: number;
  idempotencyKey: string;
  referenceType?: string | null;
  referenceId?: string | null;
  note?: string | null;
  createdBy?: string | null;
  createdAt: string;
}

// ------------------------------------------------------------------ commerce

export interface CreateIntentMandateRequest {
  agentId: string;
  intentText?: string | null;
  categories?: string[] | null;
  maxTotalMinor: number;
  currency: string;
  expiresAt: string;
}

export interface CreateCartMandateRequest {
  items: Array<Record<string, any>>;
  payee?: string | null;
  expiresAt?: string | null;
}

export interface CommerceMandate {
  id: string;
  kind: "INTENT" | "CART";
  intentMandateId?: string | null;
  agentId: string;
  buyerUserId?: string | null;
  currency: string;
  maxTotalMinor?: number | null;
  cartTotalMinor?: number | null;
  items?: any;
  payee?: string | null;
  categories?: any;
  intentText?: string | null;
  status: string;
  canonicalHashSha256?: string | null;
  keyId?: string | null;
  signatureValid?: boolean;
  buyerAuthJson?: string | null;
  authorizedAt?: string | null;
  walletDebitRef?: number | null;
  expiresAt?: string | null;
  createdAt: string;
  revokedAt?: string | null;
  revokeReason?: string | null;
}

export interface CommerceEvent {
  id: number;
  eventType: string;
  actor?: string | null;
  detail?: string | null;
  createdAt: string;
}

// -------------------------------------------------------------------- x402

export interface CreateX402OfferRequest {
  slug: string;
  title: string;
  description?: string | null;
  priceMinor: number;
  currency: string;
  walletId?: string | null;
  evmPriceAtomic?: number | null;
  evmAsset?: string | null;
  evmNetwork?: string | null;
  evmAssetName?: string | null;
  evmAssetVersion?: string | null;
  payTo?: string | null;
  resourcePayload?: string | null;
}

export interface X402Offer {
  id: string;
  slug: string;
  url: string;
  agentId: string;
  walletId: string;
  title: string;
  priceMinor: number;
  currency: string;
  evm: boolean;
  status: "ACTIVE" | "DISABLED";
  createdAt: string;
}

const QUIET = [403, 404, 405];

export const agentTrustApi = {
  // ---- delegation grants + tokens (tenant surface) ----
  createDelegationGrant: (agentId: string, req: CreateDelegationGrantRequest): Promise<DelegationGrant> =>
    apiFetch(`/api/agents/${encodeURIComponent(agentId)}/delegations`, {
      method: "POST", ...jsonBody(req),
    }).then(toJson<DelegationGrant>),

  mintDelegationToken: (agentId: string, grantId: string, ttlSeconds?: number): Promise<MintedDelegationToken> =>
    apiFetch(`/api/agents/${encodeURIComponent(agentId)}/delegations/${encodeURIComponent(grantId)}/tokens`, {
      method: "POST", ...jsonBody({ ttlSeconds: ttlSeconds ?? null }),
    }).then(toJson<MintedDelegationToken>),

  listDelegationGrants: (agentId: string): Promise<DelegationGrant[]> =>
    apiFetch(`/api/agents/${encodeURIComponent(agentId)}/delegations`).then(toJsonQuietOn(QUIET)),

  revokeDelegationGrant: (agentId: string, grantId: string, reason?: string): Promise<DelegationGrant> =>
    apiFetch(`/api/agents/${encodeURIComponent(agentId)}/delegations/${encodeURIComponent(grantId)}/revoke`, {
      method: "POST", ...jsonBody({ reason: reason ?? null }),
    }).then(toJson<DelegationGrant>),

  introspectDelegationToken: (token: string): Promise<DelegationIntrospection> =>
    apiFetch(`/api/agents/delegations/introspect`, {
      method: "POST", ...jsonBody({ token }),
    }).then(toJson<DelegationIntrospection>),

  // Signed Know-Your-Agent capability manifest (RUNNER-gated).
  getAgentManifest: (agentId: string): Promise<Record<string, any>> =>
    apiFetch(`/api/agents/${encodeURIComponent(agentId)}/manifest`).then(toJson),

  // ---- public directory (management calls are tenant-scoped) ----
  publishToDirectory: (agentId: string, req: PublishDirectoryRequest): Promise<DirectoryEntry> =>
    apiFetch(`/api/agents/${encodeURIComponent(agentId)}/directory/publish`, {
      method: "POST", ...jsonBody(req),
    }).then(toJson<DirectoryEntry>),

  unpublishFromDirectory: (agentId: string): Promise<{ agentId: string; status: string }> =>
    apiFetch(`/api/agents/${encodeURIComponent(agentId)}/directory/unpublish`, {
      method: "POST",
    }).then(toJson<{ agentId: string; status: string; }>),

  // This tenant's directory entries, any status.
  myDirectoryEntries: (): Promise<DirectoryEntry[]> =>
    apiFetch(`/api/agents/directory/mine`).then(toJsonQuietOn(QUIET)),

  // Public browse - permitAll on the backend; safe to call signed-out.
  listDirectory: (q?: string, limit = 50): Promise<{ entries: DirectoryEntry[]; count: number }> =>
    apiFetch(`/api/directory/agents?limit=${limit}${q ? `&q=${encodeURIComponent(q)}` : ""}`)
      .then(toJsonQuietOn([403, 404, 405, 401])),

  getDirectoryEntry: (agentId: string): Promise<DirectoryEntry> =>
    apiFetch(`/api/directory/agents/${encodeURIComponent(agentId)}`).then(toJson<DirectoryEntry>),

  // ---- agent wallets ----
  openWallet: (agentId: string, currency: string): Promise<AgentWallet> =>
    apiFetch(`/api/wallets`, {
      method: "POST", ...jsonBody({ agentId, currency }),
    }).then(toJson<AgentWallet>),

  listWallets: (agentId: string): Promise<AgentWallet[]> =>
    apiFetch(`/api/wallets?agentId=${encodeURIComponent(agentId)}`).then(toJsonQuietOn(QUIET)),

  getWallet: (walletId: string): Promise<AgentWallet> =>
    apiFetch(`/api/wallets/${encodeURIComponent(walletId)}`).then(toJsonQuietOn(QUIET)),

  creditWallet: (walletId: string, amountMinor: number, idempotencyKey: string, note?: string): Promise<WalletLedgerEntry> =>
    apiFetch(`/api/wallets/${encodeURIComponent(walletId)}/credit`, {
      method: "POST", ...jsonBody({ amountMinor, idempotencyKey, note: note ?? null }),
    }).then(toJson<WalletLedgerEntry>),

  walletLedger: (walletId: string, limit = 50): Promise<WalletLedgerEntry[]> =>
    apiFetch(`/api/wallets/${encodeURIComponent(walletId)}/ledger?limit=${limit}`).then(toJsonQuietOn(QUIET)),

  // ---- AP2 commerce mandates ----
  createIntentMandate: (req: CreateIntentMandateRequest): Promise<CommerceMandate> =>
    apiFetch(`/api/commerce/mandates/intent`, { method: "POST", ...jsonBody(req) }).then(toJson<CommerceMandate>),

  createCartMandate: (intentId: string, req: CreateCartMandateRequest): Promise<CommerceMandate> =>
    apiFetch(`/api/commerce/mandates/${encodeURIComponent(intentId)}/cart`, {
      method: "POST", ...jsonBody(req),
    }).then(toJson<CommerceMandate>),

  authorizeCart: (cartId: string): Promise<CommerceMandate> =>
    apiFetch(`/api/commerce/mandates/${encodeURIComponent(cartId)}/authorize`, { method: "POST" }).then(toJson<CommerceMandate>),

  captureCart: (cartId: string): Promise<CommerceMandate> =>
    apiFetch(`/api/commerce/mandates/${encodeURIComponent(cartId)}/capture`, { method: "POST" }).then(toJson<CommerceMandate>),

  refundCart: (cartId: string, reason?: string): Promise<CommerceMandate> =>
    apiFetch(`/api/commerce/mandates/${encodeURIComponent(cartId)}/refund`, {
      method: "POST", ...jsonBody({ reason: reason ?? null }),
    }).then(toJson<CommerceMandate>),

  revokeCommerceMandate: (id: string, reason?: string): Promise<CommerceMandate> =>
    apiFetch(`/api/commerce/mandates/${encodeURIComponent(id)}/revoke`, {
      method: "POST", ...jsonBody({ reason: reason ?? null }),
    }).then(toJson<CommerceMandate>),

  listCommerceMandates: (agentId: string): Promise<CommerceMandate[]> =>
    apiFetch(`/api/commerce/mandates?agentId=${encodeURIComponent(agentId)}`).then(toJsonQuietOn(QUIET)),

  getCommerceMandate: (id: string): Promise<CommerceMandate> =>
    apiFetch(`/api/commerce/mandates/${encodeURIComponent(id)}`).then(toJsonQuietOn(QUIET)),

  listIntentCarts: (intentId: string): Promise<CommerceMandate[]> =>
    apiFetch(`/api/commerce/mandates/${encodeURIComponent(intentId)}/carts`).then(toJsonQuietOn(QUIET)),

  listCommerceEvents: (id: string): Promise<CommerceEvent[]> =>
    apiFetch(`/api/commerce/mandates/${encodeURIComponent(id)}/events`).then(toJsonQuietOn(QUIET)),

  // ---- x402 offers (paid endpoints the tenant publishes) ----
  createX402Offer: (agentId: string, req: CreateX402OfferRequest): Promise<X402Offer> =>
    apiFetch(`/api/agents/${encodeURIComponent(agentId)}/x402/offers`, {
      method: "POST", ...jsonBody(req),
    }).then(toJson<X402Offer>),

  listX402Offers: (agentId: string): Promise<X402Offer[]> =>
    apiFetch(`/api/agents/${encodeURIComponent(agentId)}/x402/offers`).then(toJsonQuietOn(QUIET)),

  disableX402Offer: (agentId: string, offerId: string): Promise<X402Offer> =>
    apiFetch(`/api/agents/${encodeURIComponent(agentId)}/x402/offers/${encodeURIComponent(offerId)}/disable`, {
      method: "POST",
    }).then(toJson<X402Offer>),
};
