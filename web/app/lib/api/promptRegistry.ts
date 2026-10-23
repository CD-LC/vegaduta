import { apiFetch, toJson, jsonBody } from "./core";

// The prompt registry (core `prompts` module, /api/prompts): every piece of text the platform writes for a
// model, with its shipped default and overrides at platform, tenant and agent scope, each save versioned.
// Plus the agent prompt preview (POST /api/agents/{id}/prompt-preview). Wire shapes mirror the Java records
// (PromptOverrideService.PromptView, PromptHistoryEntry, ...); enums arrive as their names.

export type PromptRiskClass = "STEERS_EVERY_TURN" | "SECONDARY_CALL" | "USER_VISIBLE" | "TOOL_DESCRIPTION";
export type PromptScope = "AGENT" | "TENANT" | "PLATFORM";
export type PromptSource = "DEFAULT" | "PLATFORM" | "TENANT" | "AGENT" | "FALLBACK";
/** The scope segment of the admin routes. */
export type PromptScopePath = "platform" | "tenant" | "agent";

export interface PromptPlaceholder {
  name: string;
  description: string | null;
  required: boolean;
}

export interface PromptDefinition {
  key: string;
  title: string;
  description: string | null;
  riskClass: PromptRiskClass;
  securityCritical: boolean;
  disableable: boolean;
  retired: boolean;
  strictPlaceholders: boolean;
  placeholders: PromptPlaceholder[];
  minLength: number;
  maxLength: number;
}

export interface ResolvedPrompt {
  key: string;
  /** Null when the prompt is switched off (or unknown). */
  text: string | null;
  source: PromptSource;
  scope: PromptScope | null;
  version: number;
  locale: string | null;
  enabled: boolean;
  known: boolean;
  hash: string;
  estimatedTokens: number;
}

export interface PromptOverrideView {
  scope: PromptScope;
  scopeId: string;
  locale: string;
  text: string | null;
  enabled: boolean;
  version: number;
  createdBy: string | null;
  createdAt: string | null;
}

export interface PromptView {
  definition: PromptDefinition;
  defaultText: string | null;
  effective: ResolvedPrompt;
  overrides: PromptOverrideView[];
}

export interface PromptHistoryEntry {
  id: number;
  scope: PromptScope;
  scopeId: string;
  key: string;
  locale: string;
  version: number;
  /** CREATE | UPDATE | REVERT | MIGRATE */
  action: string;
  text: string | null;
  enabled: boolean;
  previousText: string | null;
  previousEnabled: boolean;
  reason: string | null;
  createdBy: string | null;
  createdAt: string | null;
}

export interface PromptValidation {
  valid: boolean;
  problems: string[];
  securityCritical: boolean;
  requiresAcknowledgement: boolean;
  length: number;
}

export interface PromptApplied {
  override: PromptOverrideView | null;
  history: PromptHistoryEntry | null;
  /** True when nothing changed (the text already was the default and no override existed). */
  unchanged: boolean;
}

export interface SavePromptOverrideRequest {
  /** Null/blank with `enabled: false` switches a disableable prompt off at this scope. */
  text: string | null;
  enabled: boolean;
  acknowledgeRisk: boolean;
  reason?: string | null;
  /** The version the editor saw (0 = no override at this scope); a stale one is a 409. */
  expectedVersion?: number | null;
}

export interface PromptListFilter {
  agentId?: string | null;
  locale?: string | null;
  family?: string | null;
  q?: string | null;
}

export interface PromptScopeTarget {
  scope: PromptScopePath;
  /** Required for the agent scope. */
  agentId?: string | null;
  locale?: string | null;
}

/** One block of an assembled prompt, tagged with where its wording came from. */
export interface PromptPreviewBlock {
  message: string;
  id: string;
  key: string | null;
  source: string;
  scope: string | null;
  version: number | null;
  hash: string | null;
  estimatedTokens: number;
  text: string;
}

export interface PromptPreviewTool {
  name: string;
  description: string | null;
  inputSchema: string | null;
  estimatedTokens: number;
}

export interface PromptPreview {
  agentId: string;
  smallModel: boolean;
  systemPrompt: string;
  memoryBlock: string | null;
  blocks: PromptPreviewBlock[];
  tools: PromptPreviewTool[];
  promptEstimatedTokens: number;
  toolsEstimatedTokens: number;
  tokenBudget: number;
  overBudget: boolean;
  tokenEstimateNote: string | null;
  notes: string[];
}

export interface PromptPreviewRequest {
  message?: string;
  sessionId?: string;
  smallModel?: boolean;
  includeMemoryRecall?: boolean;
}

const BASE = "/api/prompts";

function query(params: Record<string, string | number | null | undefined>): string {
  const qs = new URLSearchParams();
  for (const [k, v] of Object.entries(params)) {
    if (v !== null && v !== undefined && String(v) !== "") qs.set(k, String(v));
  }
  const s = qs.toString();
  return s ? `?${s}` : "";
}

function scopeQuery(target: PromptScopeTarget): string {
  return query({ agentId: target.scope === "agent" ? target.agentId : null, locale: target.locale });
}

export const promptRegistryApi = {
  listPrompts: (filter: PromptListFilter = {}) =>
    apiFetch(`${BASE}${query({ agentId: filter.agentId, locale: filter.locale, family: filter.family, q: filter.q })}`).then(
      toJson<PromptView[]>
    ),
  getPrompt: (key: string, filter: PromptListFilter = {}) =>
    apiFetch(`${BASE}/${encodeURIComponent(key)}${query({ agentId: filter.agentId, locale: filter.locale })}`).then(
      toJson<PromptView>
    ),
  getPromptHistory: (key: string, target: PromptScopeTarget, limit?: number) =>
    apiFetch(
      `${BASE}/${encodeURIComponent(key)}/history${query({
        scope: target.scope,
        agentId: target.scope === "agent" ? target.agentId : null,
        locale: target.locale,
        limit,
      })}`
    ).then(toJson<PromptHistoryEntry[]>),
  validatePrompt: (key: string, body: { text: string | null; enabled: boolean }) =>
    apiFetch(`${BASE}/${encodeURIComponent(key)}/validate`, { method: "POST", ...jsonBody(body) }).then(
      toJson<PromptValidation>
    ),
  savePromptOverride: (key: string, target: PromptScopeTarget, body: SavePromptOverrideRequest) =>
    apiFetch(`${BASE}/${encodeURIComponent(key)}/overrides/${target.scope}${scopeQuery(target)}`, {
      method: "PUT",
      ...jsonBody(body),
    }).then(toJson<PromptApplied>),
  revertPromptOverride: (key: string, target: PromptScopeTarget, opts: { reason?: string | null; expectedVersion?: number | null } = {}) =>
    apiFetch(
      `${BASE}/${encodeURIComponent(key)}/overrides/${target.scope}${query({
        agentId: target.scope === "agent" ? target.agentId : null,
        locale: target.locale,
        reason: opts.reason,
        expectedVersion: opts.expectedVersion,
      })}`,
      { method: "DELETE" }
    ).then(toJson<PromptApplied>),
  restorePromptVersion: (
    key: string,
    target: PromptScopeTarget,
    body: { toVersion: number; acknowledgeRisk: boolean; reason?: string | null }
  ) =>
    apiFetch(`${BASE}/${encodeURIComponent(key)}/overrides/${target.scope}/restore${scopeQuery(target)}`, {
      method: "POST",
      ...jsonBody(body),
    }).then(toJson<PromptApplied>),
  previewAgentPrompt: (agentId: string, body: PromptPreviewRequest = {}) =>
    apiFetch(`/api/agents/${encodeURIComponent(agentId)}/prompt-preview`, { method: "POST", ...jsonBody(body) }).then(
      toJson<PromptPreview>
    ),
};
