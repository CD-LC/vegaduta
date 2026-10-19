import { apiFetch } from "./api/core";

// Client for the personal-install add-on registry (core/providers, package ...providers.addons).
// Contract: GET /api/addons, GET /api/addons/{id}, POST /api/addons/{id}/enable and
// POST /api/addons/{id}/disable[?cascade=true]. The installer-only /api/addons/host/** surface is not
// used from the console (it takes a shared secret, not a person's token).
//
// Why this does not go through toJson: the add-on controller answers errors as
// { error: <code>, message, details }, but toJson reads `error` as the human sentence and would put
// the code ("required_by") in a global toast. The page renders its own inline errors and needs
// `details.dependents` for the cascade prompt, so errors are decoded here.

export type AddonCategory = "assistant" | "search" | "documents" | "models" | "media" | "tools";
export type AddonKind = "compose-profile" | "model-pull" | "mcp-server" | "planned";
export type AddonAvailability = "available" | "planned";

/** What the add-on is doing right now; see the contract, section 3. */
export type AddonStatus =
  | "off"
  | "enable_requested"
  | "applying"
  | "on"
  | "disable_requested"
  | "pulling"
  | "failed"
  | "unavailable";

/** Advice about this computer, never a block. */
export type AddonFit = "ok" | "low_memory" | "needs_gpu" | "unknown";
export type AddonBlockedReason = "planned" | "ollama_unreachable" | null;

export interface AddonView {
  id: string;
  name: string;
  description: string;
  category: AddonCategory;
  kind: AddonKind;
  availability: AddonAvailability;
  sizeMb: number;
  minRamMb: number;
  needsGpu: boolean;
  license: string;
  free: boolean;
  requires: string[];
  defaultOn: boolean;
  hostCommand: string;
  learnMore: string;
  unlocks: string[];
  model: string | null;
  /** The person's wish. Not the same as running: see `status`. */
  desired: boolean;
  status: AddonStatus;
  /** Failure reason, host message or progress text. */
  message: string | null;
  /** 0-99, only while `pulling`. */
  progress: number | null;
  /** Models only: present in Ollama. Null when Ollama is unreachable. */
  installed: boolean | null;
  desiredAt: string | null;
  reportedAt: string | null;
  fit: AddonFit;
  canEnable: boolean;
  blockedReason: AddonBlockedReason;
  /** Ids of ON add-ons that need this one. */
  requiredBy: string[];
}

/** What the host must do for the whole wanted set (contract, section 6). */
export interface AddonApply {
  /** POSIX launcher call, profiles in catalog order, then flags. */
  command: string;
  profiles: string[];
  flags: string[];
  /** Lines for deploy/compose/.env. Not part of `command`. */
  envSet: Record<string, string>;
  /** Env keys declared by add-ons that are not wanted: remove or blank them. */
  envUnset: string[];
  /** Host-managed ids waiting for the host (enable_requested or disable_requested). */
  pending: string[];
}

export interface AddonHardware {
  ramGb: number;
  cpuCores: number;
  gpu: { vendor: string; name: string; vramGb: number } | null;
  appleSilicon: boolean;
}

export interface AddonList {
  catalogSchema: number;
  hardware: AddonHardware;
  /** configured=false: no installer link, so show the copyable command only. */
  hostBridge: { configured: boolean };
  ollama: { reachable: boolean };
  apply: AddonApply;
  addons: AddonView[];
}

export interface AddonChange {
  addon: AddonView;
  /** Every add-on whose wish changed, requirements first. Empty when nothing changed. */
  changed: string[];
  /** True while any host-managed add-on touched or asked about still waits for the host. */
  needsHost: boolean;
  apply: AddonApply;
}

/** Stable error codes of this controller (contract, section 4). */
export type AddonErrorCode =
  | "unknown_addon"
  | "not_available"
  | "required_by"
  | "conflict"
  | "rate_limited"
  | "too_many_downloads"
  | "ollama_unreachable";

/** Thrown for every failed add-on call. `code` is the stable error code when core sent one. */
export class AddonApiError extends Error {
  readonly status: number;
  readonly code: string | null;
  readonly details: Record<string, unknown>;

  constructor(message: string, status: number, code: string | null, details: Record<string, unknown>) {
    super(message);
    this.name = "AddonApiError";
    this.status = status;
    this.code = code;
    this.details = details;
  }

  /** The add-ons that block a disable (409 required_by), or an empty list. */
  get dependents(): string[] {
    const d = this.details.dependents;
    return Array.isArray(d) ? d.filter((x): x is string => typeof x === "string") : [];
  }
}

// A controller code is lower snake case; the platform's generic handlers put a sentence in `error`.
const CODE_RE = /^[a-z][a-z0-9_]*$/;

async function decode<T>(res: Response): Promise<T> {
  const text = await res.text();
  let body: unknown = null;
  if (text) {
    try {
      body = JSON.parse(text);
    } catch {
      body = null;
    }
  }
  if (res.ok) return body as T;
  const rec = body && typeof body === "object" ? (body as Record<string, unknown>) : {};
  const error = typeof rec.error === "string" ? rec.error : "";
  const code = CODE_RE.test(error) ? error : null;
  const message =
    (typeof rec.message === "string" && rec.message) || (code === null && error) || `HTTP ${res.status}`;
  const details = rec.details && typeof rec.details === "object" ? (rec.details as Record<string, unknown>) : {};
  throw new AddonApiError(message, res.status, code, details);
}

const base = "/api/addons";

export interface AddonsApi {
  list(): Promise<AddonList>;
  get(id: string): Promise<AddonView>;
  enable(id: string): Promise<AddonChange>;
  disable(id: string, cascade?: boolean): Promise<AddonChange>;
}

export const addonsApi: AddonsApi = {
  list: () => apiFetch(base, { skipGetCache: true }).then((r) => decode<AddonList>(r)),
  get: (id) => apiFetch(`${base}/${encodeURIComponent(id)}`, { skipGetCache: true }).then((r) => decode<AddonView>(r)),
  enable: (id) => apiFetch(`${base}/${encodeURIComponent(id)}/enable`, { method: "POST" }).then((r) => decode<AddonChange>(r)),
  disable: (id, cascade = false) =>
    apiFetch(`${base}/${encodeURIComponent(id)}/disable${cascade ? "?cascade=true" : ""}`, { method: "POST" }).then((r) =>
      decode<AddonChange>(r)
    ),
};
