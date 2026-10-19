// Pure helpers for the three workflow control-flow features the node drawer edits: the per-node error strategy
// (onError / retry), the Wait node and the hosted-form trigger. No React, no network - so the rules unit-test
// without a DOM. Every rule mirrors core/workflow (NodeErrorStrategy, WorkflowWaitService, WorkflowFormSchema);
// the server stays the authority and re-validates, this only avoids authoring something it would refuse.

// ---------------------------------------------------------------------------
// Error strategy (config keys: onError, retry, retryUnsafe)
// ---------------------------------------------------------------------------

export const ON_ERROR_MODES = ["fail", "continue", "branch"] as const;
export type OnErrorMode = (typeof ON_ERROR_MODES)[number];

export type RetryConfig = {
  maxAttempts: number;
  backoffMs: number;
  backoff: "fixed" | "exponential";
  retryOn: "all" | "transient";
};

export const RETRY_MAX_ATTEMPTS = 5;
export const RETRY_MAX_BACKOFF_MS = 60_000;
export const RETRY_DEFAULT: RetryConfig = { maxAttempts: 3, backoffMs: 1000, backoff: "fixed", retryOn: "all" };

// Node types whose re-execution could duplicate an external effect (NodeErrorStrategy.IRREVERSIBLE_TYPES, plus
// every non-read HTTP method). Such a node is never retried unless the node sets retryUnsafe.
const IRREVERSIBLE_TYPES = new Set(["bulk_email", "tool", "device_control", "crm_lead", "media", "output", "workflow"]);
const SAFE_HTTP_METHODS = new Set(["GET", "HEAD", "OPTIONS"]);

export function isIrreversibleNode(type: string | undefined, config: { method?: unknown } | undefined): boolean {
  const t = (type || "").toLowerCase();
  if (IRREVERSIBLE_TYPES.has(t)) return true;
  if (t === "http") {
    const method = String(config?.method ?? "").trim().toUpperCase() || "GET";
    return !SAFE_HTTP_METHODS.has(method);
  }
  return false;
}

// Node types the engine never runs through its error-strategy path: the entry trigger, the durable Wait, the
// container nodes (their branches run before the strategy code) and the human-gate nodes, where a halt for a
// person is not a failure.
const NO_ERROR_STRATEGY_TYPES = new Set([
  "trigger",
  "wait",
  "loop",
  "parallel",
  "fan_out",
  "sub_flow",
  "approval",
  "human_input",
]);

/** The node types that can carry an error strategy: the single-node steps the engine runs and that can fail. */
export function supportsErrorStrategy(type: string | undefined): boolean {
  const t = (type || "").toLowerCase();
  return !!t && !NO_ERROR_STRATEGY_TYPES.has(t);
}

export function clampInt(value: unknown, min: number, max: number, fallback: number): number {
  // Absent / blank means "not set" (Number("") is 0, which would silently become the minimum).
  if (value === null || value === undefined || (typeof value === "string" && value.trim() === "")) return fallback;
  const n = typeof value === "number" ? value : Number(String(value).trim());
  if (!Number.isFinite(n)) return fallback;
  return Math.max(min, Math.min(max, Math.trunc(n)));
}

export function parseOnError(value: unknown): OnErrorMode {
  const v = String(value ?? "").trim().toLowerCase();
  return v === "continue" || v === "branch" ? v : "fail";
}

/** The stored retry block normalised the way the server reads it, or null when there is none. */
export function normalizeRetry(raw: unknown): RetryConfig | null {
  if (!raw || typeof raw !== "object" || Array.isArray(raw)) return null;
  const r = raw as Record<string, unknown>;
  return {
    maxAttempts: clampInt(r.maxAttempts, 1, RETRY_MAX_ATTEMPTS, RETRY_DEFAULT.maxAttempts),
    backoffMs: clampInt(r.backoffMs, 0, RETRY_MAX_BACKOFF_MS, RETRY_DEFAULT.backoffMs),
    backoff: String(r.backoff ?? "").toLowerCase() === "exponential" ? "exponential" : "fixed",
    retryOn: String(r.retryOn ?? "").toLowerCase() === "transient" ? "transient" : "all",
  };
}

/** Config patch that turns the retry block on (defaults) or off (removes the key). */
export function retryPatch(enabled: boolean, current: unknown): { retry: RetryConfig | undefined } {
  return { retry: enabled ? normalizeRetry(current) || { ...RETRY_DEFAULT } : undefined };
}

/** Config patch for one retry field, normalised so an out-of-range number can never be stored. */
export function retryFieldPatch(current: unknown, patch: Partial<RetryConfig>): { retry: RetryConfig } {
  const base = normalizeRetry(current) || { ...RETRY_DEFAULT };
  return { retry: normalizeRetry({ ...base, ...patch }) as RetryConfig };
}

// ---------------------------------------------------------------------------
// Wait node (config keys: mode, duration, unit, until, timeout, timeoutUnit, onTimeout)
// ---------------------------------------------------------------------------

export const WAIT_MODES = ["duration", "until", "webhook"] as const;
export type WaitMode = (typeof WAIT_MODES)[number];
export const WAIT_UNITS = ["seconds", "minutes", "hours", "days"] as const;
export type WaitUnit = (typeof WAIT_UNITS)[number];

export function parseWaitMode(config: { mode?: unknown; until?: unknown } | undefined): WaitMode {
  const m = String(config?.mode ?? "").trim().toLowerCase();
  if (m === "duration" || m === "until" || m === "webhook") return m;
  return String(config?.until ?? "").trim() ? "until" : "duration";
}

export function parseWaitUnit(value: unknown, fallback: WaitUnit = "minutes"): WaitUnit {
  const u = String(value ?? "").trim().toLowerCase();
  return (WAIT_UNITS as readonly string[]).includes(u) ? (u as WaitUnit) : fallback;
}

/** True when the value is a {{ }} expression the server resolves at run time. */
export function isExpression(value: unknown): boolean {
  return typeof value === "string" && value.includes("{{");
}

export type WaitSummary =
  | { kind: "duration"; duration: string }
  | { kind: "until"; time: string }
  | { kind: "webhook" }
  | { kind: "unset" };

/** What the canvas subtitle says about a Wait node's config (language-neutral; the caller translates). */
export function waitSummary(config: Record<string, unknown> | undefined): WaitSummary {
  const mode = parseWaitMode(config as any);
  if (mode === "webhook") return { kind: "webhook" };
  if (mode === "until") {
    const until = String(config?.until ?? "").trim();
    return until ? { kind: "until", time: until.length > 24 ? `${until.slice(0, 22)}...` : until } : { kind: "unset" };
  }
  const duration = String(config?.duration ?? "").trim();
  if (!duration) return { kind: "unset" };
  // A bare number carries its unit; "90s" / "PT30M" / an expression already say it themselves.
  const bare = /^\d+(\.\d+)?$/.test(duration);
  return { kind: "duration", duration: bare ? `${duration} ${parseWaitUnit(config?.unit)}` : duration };
}

// ---------------------------------------------------------------------------
// Hosted-form trigger (config keys: triggerType, form)
// ---------------------------------------------------------------------------

export const FORM_FIELD_TYPES = ["text", "long_text", "number", "email", "select", "checkbox", "date"] as const;
export type FormFieldType = (typeof FORM_FIELD_TYPES)[number];

export type FormFieldDef = {
  key: string;
  label: string;
  type: FormFieldType;
  required: boolean;
  placeholder?: string;
  options?: string[];
  maxLength?: number;
  min?: number;
  max?: number;
};

export type FormDef = {
  title: string;
  description: string;
  submitLabel: string;
  successMessage: string;
  redirectUrl: string;
  fields: FormFieldDef[];
};

export const FORM_MAX_FIELDS = 40;
const FIELD_KEY = /^[A-Za-z][A-Za-z0-9_]{0,63}$/;

export function isFormTrigger(config: { triggerType?: unknown } | undefined): boolean {
  return String(config?.triggerType ?? "").trim().toLowerCase() === "form";
}

export function emptyFormDef(): FormDef {
  return {
    title: "",
    description: "",
    submitLabel: "",
    successMessage: "",
    redirectUrl: "",
    fields: [{ key: "name", label: "Name", type: "text", required: true }],
  };
}

function textOf(v: unknown): string {
  return v == null ? "" : String(v);
}

/** The stored `form` config as a well-typed definition (tolerant of anything an older/API-authored graph holds). */
export function readFormDef(raw: unknown): FormDef {
  const f = (raw && typeof raw === "object" && !Array.isArray(raw) ? raw : {}) as Record<string, unknown>;
  const fields: FormFieldDef[] = [];
  if (Array.isArray(f.fields)) {
    for (const entry of f.fields) {
      if (!entry || typeof entry !== "object") continue;
      const e = entry as Record<string, unknown>;
      const typeRaw = textOf(e.type).toLowerCase();
      const type = (typeRaw === "textarea" ? "long_text" : typeRaw) as FormFieldType;
      fields.push({
        key: textOf(e.key),
        label: textOf(e.label),
        type: (FORM_FIELD_TYPES as readonly string[]).includes(type) ? type : "text",
        required: e.required === true || textOf(e.required).toLowerCase() === "true",
        placeholder: textOf(e.placeholder) || undefined,
        options: Array.isArray(e.options) ? e.options.map(textOf) : undefined,
        maxLength: typeof e.maxLength === "number" ? e.maxLength : undefined,
        min: typeof e.min === "number" ? e.min : undefined,
        max: typeof e.max === "number" ? e.max : undefined,
      });
    }
  }
  return {
    title: textOf(f.title),
    description: textOf(f.description),
    submitLabel: textOf(f.submitLabel),
    successMessage: textOf(f.successMessage),
    redirectUrl: textOf(f.redirectUrl),
    fields,
  };
}

/** Next unused field key ("field1", "field2", ...). */
export function nextFieldKey(fields: FormFieldDef[]): string {
  const used = new Set(fields.map((f) => f.key));
  for (let i = 1; i <= FORM_MAX_FIELDS + 1; i++) {
    if (!used.has(`field${i}`)) return `field${i}`;
  }
  return `field${Date.now()}`;
}

export type FormIssues = { keys: Record<number, "invalid" | "duplicate">; redirect: boolean; tooManyFields: boolean };

/** Authoring problems the server would reject, by field index - shown inline so a save never surprises. */
export function formIssues(def: FormDef): FormIssues {
  const keys: Record<number, "invalid" | "duplicate"> = {};
  const seen = new Set<string>();
  def.fields.forEach((f, i) => {
    if (!FIELD_KEY.test(f.key)) keys[i] = "invalid";
    else if (seen.has(f.key)) keys[i] = "duplicate";
    seen.add(f.key);
  });
  return { keys, redirect: !!def.redirectUrl && !isHttpsUrl(def.redirectUrl), tooManyFields: def.fields.length > FORM_MAX_FIELDS };
}

export function isHttpsUrl(value: string): boolean {
  if (!value || value.length > 2000) return false;
  try {
    const u = new URL(value);
    return u.protocol === "https:" && !!u.hostname && !u.username && !u.password;
  } catch {
    return false;
  }
}

/** Options textarea <-> list: one option per line, blanks and duplicates dropped. */
export function parseOptions(text: string): string[] {
  const out: string[] = [];
  for (const line of text.split(/\r?\n/)) {
    const v = line.trim();
    if (v && !out.includes(v)) out.push(v.slice(0, 100));
  }
  return out.slice(0, 50);
}

/**
 * The public form link for a trigger node: the web app's own /forms/<token> page (same origin the editor is
 * served from - never a hardcoded host). Null until the node's webhook is enabled and has a token.
 */
export function formUrl(origin: string | undefined, token: string | null | undefined): string | null {
  if (!origin || !token) return null;
  return `${origin.replace(/\/+$/, "")}/forms/${encodeURIComponent(token)}`;
}
