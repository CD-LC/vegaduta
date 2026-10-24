import { apiFetch, jsonBody } from "./core";
import type { ApiFetchOptions } from "./core";

// Typed client for the settings catalog API (core `settings` module). The catalog says WHICH settings exist and
// what values they accept (type, unit, bounds, risk, who may change them); /effective says what each one is
// right now and where that value comes from; tenant/agent writes override it and DELETE falls back to the
// inherited value. Every bound, unit and option the UI shows comes from the catalog, never from the components.
//
// Errors are parsed here, NOT through toJson: a rejected value is an inline field error, so it must not raise
// the global error toast toJson fires for every failed call. See SettingsApiError / toFieldError below.

export type SettingType = "int" | "long" | "decimal" | "duration" | "bool" | "enum" | "string" | "text" | "percent";
export type SettingUnit = "chars" | "tokens" | "ms" | "s" | "count" | "usd" | "percent";
export type SettingRisk = "low" | "medium" | "high" | "security";
export type SettingScope = "tenant" | "agent";
export type SettingRole = "VIEWER" | "EDITOR" | "TENANT_ADMIN";
export type SettingSource = "default" | "platform" | "tenant" | "agent";
/** For a security key: which direction is stricter (a tenant or agent value may only move that way). */
export type SettingStrictness = "higher_is_stricter" | "lower_is_stricter" | "enum_order";

export interface SettingCatalogEntry {
  key: string;
  group: string;
  labelKey: string;
  descriptionKey: string;
  helpKey?: string;
  type: SettingType;
  unit?: SettingUnit;
  min?: number;
  max?: number;
  step?: number;
  enumValues?: string[];
  risk: SettingRisk;
  scopes: SettingScope[];
  default: unknown;
  minRole: SettingRole;
  restartRequired: boolean;
  docsAnchor?: string;
  /** Security keys only; absent for every other setting. */
  strictness?: SettingStrictness;
}

export interface SettingsCatalog {
  entries: SettingCatalogEntry[];
}

export interface EffectiveSetting {
  value: unknown;
  source: SettingSource;
  locked: boolean;
  lockedReason?: string;
}

export interface EffectiveSettings {
  values: Record<string, EffectiveSetting>;
}

export interface SettingHistoryItem {
  version: number;
  value: unknown;
  previousValue: unknown;
  actor: string;
  at: string;
  reason?: string | null;
}

/** One row of the all-keys history: a history item plus the key and where it was changed. */
export interface SettingHistoryRow extends SettingHistoryItem {
  key: string;
  scope: SettingScope;
  /** Set for scope "agent". */
  agentId?: string;
  action: string;
}

export interface SettingHistoryPage {
  entries: SettingHistoryRow[];
  /** Rows matching the filters, before paging. */
  total: number;
  offset: number;
  limit: number;
  hasMore: boolean;
}

export interface SettingHistoryQuery {
  /** "all" (default): the workspace's own changes and every agent's; needs a workspace admin. */
  scope?: "all" | SettingScope;
  /** With scope "agent": that agent only (EDITOR on it); without: every agent's changes (workspace admin). */
  agentId?: string;
  /** ISO-8601 instant: only changes at or after it. */
  since?: string;
  limit?: number;
  offset?: number;
}

export interface SettingWrite {
  value: unknown;
  reason?: string;
}

/** What a UI needs to know about a failed settings call, already split by cause. */
export type SettingsFieldErrorKind =
  | "invalid" // 400 SETTING_INVALID: the value is outside what the catalog allows
  | "locked" // 409 SETTING_LOCKED: an operator pinned it
  | "notFound" // 404: unknown key
  | "forbidden" // 401/403: this role may not change it
  | "reasonRequired" // 400 asking for the typed reason (risk high/security)
  | "network"
  | "other";

export interface SettingsFieldError {
  kind: SettingsFieldErrorKind;
  /** Server's own sentence (English); the UI prefixes it with a localized explanation. */
  message: string;
  key?: string;
  min?: number;
  max?: number;
  enumValues?: string[];
}

/** The Error every SettingsClient method rejects with. */
export class SettingsApiError extends Error {
  status: number;
  code?: string;
  key?: string;
  min?: number;
  max?: number;
  enumValues?: string[];
  constructor(
    message: string,
    init: { status: number; code?: string; key?: string; min?: number; max?: number; enumValues?: string[] }
  ) {
    super(message);
    this.name = "SettingsApiError";
    this.status = init.status;
    this.code = init.code;
    this.key = init.key;
    this.min = init.min;
    this.max = init.max;
    this.enumValues = init.enumValues;
  }
}

const num = (v: unknown): number | undefined => (typeof v === "number" && Number.isFinite(v) ? v : undefined);

/** Build the error for a non-2xx settings response. Never throws; a non-JSON body just yields a generic message. */
export async function parseSettingsError(res: Response): Promise<SettingsApiError> {
  let body: Record<string, unknown> = {};
  try {
    const raw: unknown = await res.json();
    if (raw && typeof raw === "object") body = raw as Record<string, unknown>;
  } catch {
    // no JSON body (proxy 502, route missing): fall through to the status-only error
  }
  const message =
    (typeof body.message === "string" && body.message) ||
    (typeof body.error === "string" && body.error) ||
    (typeof body.detail === "string" && body.detail) ||
    `HTTP ${res.status}`;
  return new SettingsApiError(message, {
    status: res.status,
    code: typeof body.code === "string" ? body.code : undefined,
    key: typeof body.key === "string" ? body.key : undefined,
    min: num(body.min),
    max: num(body.max),
    enumValues: Array.isArray(body.enumValues) ? body.enumValues.filter((v): v is string => typeof v === "string") : undefined,
  });
}

/** Classify any rejection from a SettingsClient call into something a field can render. */
export function toFieldError(err: unknown): SettingsFieldError {
  if (err instanceof SettingsApiError) {
    const base = { message: err.message, key: err.key, min: err.min, max: err.max, enumValues: err.enumValues };
    if (err.code === "SETTING_LOCKED" || err.status === 409) return { kind: "locked", ...base };
    if (err.code === "SETTING_INVALID") return { kind: "invalid", ...base };
    if (err.code === "SETTING_REASON_REQUIRED") return { kind: "reasonRequired", ...base };
    if (err.status === 404) return { kind: "notFound", ...base };
    if (err.status === 401 || err.status === 403) return { kind: "forbidden", ...base };
    if (err.status === 400) return { kind: "invalid", ...base };
    return { kind: "other", ...base };
  }
  const message = err instanceof Error ? err.message : String(err ?? "");
  return { kind: "network", message };
}

// ---- response normalisation (the wire is trusted for shape, not for completeness) ----

const SCOPES: SettingScope[] = ["tenant", "agent"];
const STRICTNESS: SettingStrictness[] = ["higher_is_stricter", "lower_is_stricter", "enum_order"];

function normalizeEntry(raw: unknown): SettingCatalogEntry | null {
  if (!raw || typeof raw !== "object") return null;
  const r = raw as Record<string, unknown>;
  if (typeof r.key !== "string" || !r.key || typeof r.type !== "string") return null;
  const scopes = Array.isArray(r.scopes) ? r.scopes.filter((s): s is SettingScope => SCOPES.includes(s as SettingScope)) : [];
  return {
    key: r.key,
    group: typeof r.group === "string" && r.group ? r.group : r.key.split(".")[0],
    labelKey: typeof r.labelKey === "string" ? r.labelKey : `settings.${r.key}.label`,
    descriptionKey: typeof r.descriptionKey === "string" ? r.descriptionKey : `settings.${r.key}.description`,
    helpKey: typeof r.helpKey === "string" ? r.helpKey : undefined,
    type: r.type as SettingType,
    unit: typeof r.unit === "string" ? (r.unit as SettingUnit) : undefined,
    min: num(r.min),
    max: num(r.max),
    step: num(r.step),
    enumValues: Array.isArray(r.enumValues) ? r.enumValues.map(String) : undefined,
    risk: (["low", "medium", "high", "security"] as const).includes(r.risk as SettingRisk) ? (r.risk as SettingRisk) : "low",
    scopes: scopes.length ? scopes : SCOPES,
    default: r.default,
    minRole: (["VIEWER", "EDITOR", "TENANT_ADMIN"] as const).includes(r.minRole as SettingRole)
      ? (r.minRole as SettingRole)
      : "TENANT_ADMIN",
    restartRequired: r.restartRequired === true,
    docsAnchor: typeof r.docsAnchor === "string" ? r.docsAnchor : undefined,
    strictness: STRICTNESS.includes(r.strictness as SettingStrictness) ? (r.strictness as SettingStrictness) : undefined,
  };
}

export function normalizeCatalog(raw: unknown): SettingsCatalog {
  const list = raw && typeof raw === "object" && Array.isArray((raw as { entries?: unknown }).entries)
    ? ((raw as { entries: unknown[] }).entries)
    : [];
  return { entries: list.map(normalizeEntry).filter((e): e is SettingCatalogEntry => e !== null) };
}

export function normalizeEffective(raw: unknown): EffectiveSettings {
  const values: Record<string, EffectiveSetting> = {};
  const src = raw && typeof raw === "object" ? (raw as { values?: unknown }).values : null;
  if (src && typeof src === "object") {
    for (const [key, v] of Object.entries(src as Record<string, unknown>)) {
      if (!v || typeof v !== "object") continue;
      const e = v as Record<string, unknown>;
      const source = (["default", "platform", "tenant", "agent"] as const).includes(e.source as SettingSource)
        ? (e.source as SettingSource)
        : "default";
      values[key] = {
        value: e.value,
        source,
        locked: e.locked === true,
        lockedReason: typeof e.lockedReason === "string" ? e.lockedReason : undefined,
      };
    }
  }
  return { values };
}

function normalizeItem(h: Record<string, unknown>): SettingHistoryItem {
  return {
    version: typeof h.version === "number" ? h.version : 0,
    value: h.value,
    previousValue: h.previousValue,
    actor: typeof h.actor === "string" ? h.actor : "",
    at: typeof h.at === "string" ? h.at : "",
    reason: typeof h.reason === "string" ? h.reason : null,
  };
}

function normalizeHistory(raw: unknown): SettingHistoryItem[] {
  if (!Array.isArray(raw)) return [];
  return raw.filter((h): h is Record<string, unknown> => !!h && typeof h === "object").map(normalizeItem);
}

/** The all-keys history page; a row without a key cannot be shown or merged and is dropped. */
export function normalizeHistoryPage(raw: unknown): SettingHistoryPage {
  const r = raw && typeof raw === "object" ? (raw as Record<string, unknown>) : {};
  const list = Array.isArray(r.entries) ? r.entries : [];
  const entries: SettingHistoryRow[] = [];
  for (const e of list) {
    if (!e || typeof e !== "object") continue;
    const h = e as Record<string, unknown>;
    if (typeof h.key !== "string" || !h.key) continue;
    entries.push({
      ...normalizeItem(h),
      key: h.key,
      scope: h.scope === "agent" ? "agent" : "tenant",
      agentId: typeof h.agentId === "string" ? h.agentId : undefined,
      action: typeof h.action === "string" ? h.action : "SET",
    });
  }
  const total = num(r.total);
  const offset = num(r.offset) ?? 0;
  return {
    entries,
    total: total ?? offset + entries.length,
    offset,
    limit: num(r.limit) ?? entries.length,
    hasMore: r.hasMore === true,
  };
}

// ---- client ----

/** The injectable boundary every settings component talks through (tests pass a fake). */
export interface SettingsClient {
  getCatalog(): Promise<SettingsCatalog>;
  /** Resolved values; with agentId, the agent-level view (agent overrides on top of tenant). */
  getEffective(agentId?: string): Promise<EffectiveSettings>;
  put(scope: SettingScope, key: string, write: SettingWrite, agentId?: string): Promise<void>;
  /**
   * Drop the override at this scope so the inherited value applies again. A high or security key needs a typed
   * reason, exactly like a write; it is recorded in the history and the audit log.
   */
  reset(scope: SettingScope, key: string, agentId?: string, reason?: string): Promise<void>;
  getHistory(key: string, scope: SettingScope, agentId?: string): Promise<SettingHistoryItem[]>;
}

/** The client of the workspace change-history screen: everything above plus the all-keys history in one request. */
export interface SettingsHistoryClient extends SettingsClient {
  getHistoryPage(query?: SettingHistoryQuery): Promise<SettingHistoryPage>;
}

type Fetcher = (path: string, options?: ApiFetchOptions) => Promise<Response>;

const enc = encodeURIComponent;

/**
 * @param fetcher defaults to the authenticated apiFetch; tests inject a stub at this boundary.
 */
export function createSettingsClient(fetcher: Fetcher = apiFetch): SettingsHistoryClient {
  async function json(path: string, options?: ApiFetchOptions): Promise<unknown> {
    const res = await fetcher(path, options);
    if (!res.ok) throw await parseSettingsError(res);
    if (res.status === 204) return null;
    try {
      return await res.json();
    } catch {
      return null;
    }
  }
  const settingPath = (scope: SettingScope, key: string, agentId?: string): string => {
    if (scope === "agent") {
      if (!agentId) throw new Error("agentId is required for agent-scoped settings");
      return `/api/agents/${enc(agentId)}/settings/${enc(key)}`;
    }
    return `/api/settings/tenant/${enc(key)}`;
  };
  return {
    getCatalog: async () => normalizeCatalog(await json("/api/settings/catalog")),
    getEffective: async (agentId) =>
      normalizeEffective(await json(`/api/settings/effective${agentId ? `?agentId=${enc(agentId)}` : ""}`)),
    put: async (scope, key, write, agentId) => {
      const body: SettingWrite = write.reason ? { value: write.value, reason: write.reason } : { value: write.value };
      await json(settingPath(scope, key, agentId), { method: "PUT", ...jsonBody(body) });
    },
    reset: async (scope, key, agentId, reason) => {
      const q = reason ? `?reason=${enc(reason)}` : "";
      await json(`${settingPath(scope, key, agentId)}${q}`, { method: "DELETE" });
    },
    getHistory: async (key, scope, agentId) => {
      const q = `scope=${scope}${scope === "agent" && agentId ? `&agentId=${enc(agentId)}` : ""}`;
      return normalizeHistory(await json(`/api/settings/${enc(key)}/history?${q}`));
    },
    getHistoryPage: async (query = {}) => {
      const params: string[] = [];
      const scope = query.scope ?? "all";
      // One agent's changes live under the agent's own route (EDITOR on the agent is enough there); every agent's
      // together, like the workspace's own, are on the workspace route and need a workspace admin.
      const oneAgent = scope === "agent" && query.agentId;
      if (!oneAgent) params.push(`scope=${scope}`);
      if (query.since) params.push(`since=${enc(query.since)}`);
      if (query.limit != null) params.push(`limit=${query.limit}`);
      if (query.offset != null) params.push(`offset=${query.offset}`);
      const base = oneAgent ? `/api/agents/${enc(query.agentId as string)}/settings/history` : "/api/settings/history";
      return normalizeHistoryPage(await json(params.length ? `${base}?${params.join("&")}` : base));
    },
  };
}

/** The default client used by the console. */
export const settingsApi: SettingsHistoryClient = createSettingsClient();
