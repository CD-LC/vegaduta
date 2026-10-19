// Browser agent persistence and replay (core tools/browseruse): saved login profiles, recorded recipes
// and the capability probe. Metadata only - nothing here can carry a saved cookie or a secret.
// The calls live in ./browserAutomation.ts (which re-exports these names).
export interface BrowserCapabilities {
  profilesEnabled: boolean;
  recipesEnabled: boolean;
  totpEnabled: boolean;
  // False when this deployment has no headless browser (older cores omit the field: treat as available).
  browserAvailable?: boolean;
}

export type BrowserProfileScope = "TENANT" | "USER";

export interface BrowserProfile {
  id: string;
  name: string;
  scope: BrowserProfileScope;
  ownedByMe: boolean;
  allowedDomains: string[];
  hasState: boolean;
  stateBytes: number;
  stateEntries: number;
  ttlDays: number;
  expiresAt: string | null;
  expired: boolean;
  lastSavedAt: string | null;
  lastUsedAt: string | null;
  createdAt: string;
  attachedAgentIds: string[];
}

export interface CreateBrowserProfileInput {
  name: string;
  allowedDomains: string[];
  scope?: BrowserProfileScope;
  ttlDays?: number;
}

export interface BrowserRecipeSummary {
  id: string;
  agentId: string | null;
  name: string;
  description: string | null;
  allowedDomains: string[];
  parameters: string[];
  stepCount: number;
  runCount: number;
  failCount: number;
  lastRunAt: string | null;
  lastFailure: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface BrowserRecipeStep {
  action: "navigate" | "click" | "type" | "select" | "wait_for" | string;
  url?: string;
  selector?: string;
  text?: string;
  value?: string;
  expect?: { urlHost?: string; urlPathPrefix?: string; urlContains?: string; textContains?: string; selector?: string };
  timeoutMs?: number;
  irreversible?: boolean;
}

export interface BrowserRecipeSpec {
  version: number;
  allowedDomains: string[];
  params: { name: string; required: boolean }[];
  steps: BrowserRecipeStep[];
}

export interface BrowserRecipeDetail {
  summary: BrowserRecipeSummary;
  spec: BrowserRecipeSpec;
  warnings: string[];
}

export interface SaveBrowserRunInput {
  agentId: string;
  sessionId: string;
  name: string;
  description?: string;
  /** Parameter name to the value the user supplied in this run; the value is used only for substitution and never stored. */
  parameters: Record<string, string>;
  allowLiteralText?: boolean;
}
