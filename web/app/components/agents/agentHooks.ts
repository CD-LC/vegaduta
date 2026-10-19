// Pure helpers for the agent Hooks card: the wire contract, the editor's draft shape, client-side validation that
// mirrors core's HookSchema, and the human-readable preview sentence. Kept free of React and of the api client so
// the rules are testable on their own.
//
// Wire contract (core AgentHooksController / AgentHookDtos):
//   GET/PUT /api/agents/{agentId}/hooks      body { hooks: [...] } - the whole list is replaced, [] clears it.
//   GET/PUT /api/settings/agent-hooks        workspace defaults (read-only here; shown as "inherited").
// The server is the authority: it re-validates everything and answers 400 with a field-level message. The checks
// below exist so an obviously bad hook is caught before the round trip, not instead of it.

export type HookPoint = "BEFORE_MODEL" | "AFTER_MODEL" | "BEFORE_TOOL" | "AFTER_TOOL" | "ON_ERROR" | "ON_RUN_END";
export type HookAction = "block" | "require_approval" | "redact" | "limit" | "append_context" | "webhook" | "log";
export type HookFailMode = "open" | "closed";

export const HOOK_POINTS: HookPoint[] = [
  "BEFORE_MODEL",
  "AFTER_MODEL",
  "BEFORE_TOOL",
  "AFTER_TOOL",
  "ON_ERROR",
  "ON_RUN_END",
];

/** Mirrors core HookActionType.allowedAt - which actions make sense at which point. */
export const ACTIONS_AT: Record<HookPoint, HookAction[]> = {
  BEFORE_MODEL: ["block", "limit", "append_context", "webhook", "log"],
  AFTER_MODEL: ["block", "redact", "webhook", "log"],
  BEFORE_TOOL: ["block", "require_approval", "redact", "limit", "webhook", "log"],
  AFTER_TOOL: ["block", "redact", "append_context", "webhook", "log"],
  ON_ERROR: ["webhook", "log"],
  ON_RUN_END: ["webhook", "log"],
};

export const BUILTIN_PATTERNS = ["email", "phone", "credit_card", "ssn", "aadhaar", "pan"];
export const TRUST_TIERS = ["TRUSTED_INTERNAL", "TENANT_SCOPED", "UNTRUSTED_EXTERNAL", "UNCLASSIFIED"];
export const SENSITIVITIES = ["READ_PUBLIC", "READ_TENANT", "WRITE_TENANT", "CREDENTIALED", "EGRESS", "UNCLASSIFIED"];

export const MAX_HOOKS = 20;
export const MAX_REASON_CHARS = 300;
export const MAX_NOTE_CHARS = 500;
export const MAX_LABEL_CHARS = 100;
export const MAX_REPLACEMENT_CHARS = 40;
export const MAX_REGEX_COUNT = 5;
export const MAX_REGEX_CHARS = 200;
export const MIN_TIMEOUT_MS = 100;
export const MAX_TIMEOUT_MS = 5000;
export const DEFAULT_TIMEOUT_MS = 2000;
export const MAX_LIMIT_PER_RUN = 1000;
export const MAX_LIMIT_PER_MINUTE = 10000;

const ID_PATTERN = /^[a-z0-9][a-z0-9_-]{0,39}$/;
const TOOL_GLOB_PATTERN = /^[A-Za-z0-9_.:*?-]{1,128}$/;

// ------------------------------------------------------------------------ wire

export interface HookWire {
  id: string;
  enabled?: boolean;
  point: HookPoint;
  match?: { tool?: string; trust?: string; sensitivity?: string; irreversible?: boolean };
  action: HookAction;
  params?: Record<string, unknown>;
  failMode?: HookFailMode;
  timeoutMs?: number;
}

export interface AgentHooksView {
  /** False = the deployment has hooks switched off: readable, not enforced, not savable. */
  enabled: boolean;
  hooks: HookWire[];
  /** Workspace default hooks that also apply to this agent (read-only here). */
  inherited: HookWire[];
  /** Hosts a webhook hook may call. */
  webhookHosts: string[];
  updatedAt: string | null;
}

function isPoint(v: unknown): v is HookPoint {
  return typeof v === "string" && (HOOK_POINTS as string[]).includes(v);
}

function isAction(v: unknown): v is HookAction {
  return typeof v === "string" && Object.values(ACTIONS_AT).some((list) => (list as string[]).includes(v));
}

function normalizeHook(raw: unknown): HookWire | null {
  if (!raw || typeof raw !== "object") return null;
  const r = raw as Record<string, unknown>;
  if (typeof r.id !== "string" || !isPoint(r.point) || !isAction(r.action)) return null;
  const hook: HookWire = { id: r.id, point: r.point, action: r.action };
  if (typeof r.enabled === "boolean") hook.enabled = r.enabled;
  if (r.match && typeof r.match === "object") hook.match = r.match as HookWire["match"];
  if (r.params && typeof r.params === "object") hook.params = r.params as Record<string, unknown>;
  if (r.failMode === "open" || r.failMode === "closed") hook.failMode = r.failMode;
  if (typeof r.timeoutMs === "number") hook.timeoutMs = r.timeoutMs;
  return hook;
}

function normalizeList(raw: unknown): HookWire[] {
  if (!Array.isArray(raw)) return [];
  const out: HookWire[] = [];
  for (const item of raw) {
    const h = normalizeHook(item);
    if (h) out.push(h);
  }
  return out;
}

/**
 * Defensive read of GET/PUT /api/agents/{id}/hooks. Null when the body is not a hooks response at all (an older
 * core, or an error body) - the card then hides rather than showing a broken editor. A missing `enabled` is "off".
 */
export function normalizeAgentHooks(raw: unknown): AgentHooksView | null {
  if (!raw || typeof raw !== "object") return null;
  const r = raw as Record<string, unknown>;
  if (typeof r.enabled !== "boolean" || !Array.isArray(r.hooks)) return null;
  return {
    enabled: r.enabled,
    hooks: normalizeList(r.hooks),
    inherited: normalizeList(r.inherited),
    webhookHosts: Array.isArray(r.webhookHosts) ? r.webhookHosts.filter((h): h is string => typeof h === "string") : [],
    updatedAt: typeof r.updatedAt === "string" ? r.updatedAt : null,
  };
}

// ----------------------------------------------------------------------- draft

/** The editor's working copy of one hook: every field a plain string/boolean so inputs bind directly. */
export interface HookDraft {
  id: string;
  enabled: boolean;
  point: HookPoint;
  action: HookAction;
  tool: string;
  trust: string;
  sensitivity: string;
  /** "" = either, "true" = irreversible only, "false" = reversible only. */
  irreversible: "" | "true" | "false";
  reason: string;
  note: string;
  label: string;
  url: string;
  includeData: boolean;
  patterns: string[];
  /** One regular expression per line. */
  regex: string;
  replacement: string;
  maxPerRun: string;
  maxPerMinute: string;
  message: string;
  /** "" = the default for the point (closed before the model/tool, open elsewhere). */
  failMode: "" | HookFailMode;
  timeoutMs: string;
}

export function emptyDraft(point: HookPoint = "BEFORE_TOOL"): HookDraft {
  return {
    id: "",
    enabled: true,
    point,
    action: ACTIONS_AT[point][0],
    tool: "",
    trust: "",
    sensitivity: "",
    irreversible: "",
    reason: "",
    note: "",
    label: "",
    url: "",
    includeData: false,
    patterns: [],
    regex: "",
    replacement: "",
    maxPerRun: "",
    maxPerMinute: "",
    message: "",
    failMode: "",
    timeoutMs: "",
  };
}

function str(v: unknown): string {
  return typeof v === "string" ? v : "";
}

function numText(v: unknown): string {
  return typeof v === "number" && Number.isFinite(v) ? String(v) : "";
}

export function draftFromWire(h: HookWire): HookDraft {
  const d = emptyDraft(h.point);
  const p = h.params ?? {};
  const m = h.match ?? {};
  d.id = h.id;
  d.enabled = h.enabled !== false;
  d.action = h.action;
  d.tool = str(m.tool);
  d.trust = str(m.trust);
  d.sensitivity = str(m.sensitivity);
  d.irreversible = m.irreversible === true ? "true" : m.irreversible === false ? "false" : "";
  d.reason = str(p.reason);
  d.note = str(p.note);
  d.label = str(p.label);
  d.url = str(p.url);
  d.includeData = p.includeData === true;
  d.patterns = Array.isArray(p.patterns) ? p.patterns.filter((x): x is string => typeof x === "string") : [];
  d.regex = Array.isArray(p.regex) ? p.regex.filter((x): x is string => typeof x === "string").join("\n") : "";
  d.replacement = str(p.replacement);
  d.maxPerRun = numText(p.maxPerRun);
  d.maxPerMinute = numText(p.maxPerMinute);
  d.message = str(p.message);
  d.failMode = h.failMode ?? "";
  d.timeoutMs = numText(h.timeoutMs);
  return d;
}

/** Hooks have a matcher at the tool points and at ON_ERROR (where it narrows to one tool's errors). */
export function hasMatcher(point: HookPoint): boolean {
  return point === "BEFORE_TOOL" || point === "AFTER_TOOL" || point === "ON_ERROR";
}

export function regexLines(text: string): string[] {
  return (text ?? "")
    .split("\n")
    .map((l) => l.trim())
    .filter((l) => l !== "");
}

function toInt(text: string): number | null {
  const t = (text ?? "").trim();
  if (!/^\d+$/.test(t)) return null;
  const n = Number(t);
  return Number.isSafeInteger(n) ? n : null;
}

/** The request shape for one hook: only the fields its action uses, so nothing stale is sent. */
export function wireFromDraft(d: HookDraft): HookWire {
  const hook: HookWire = { id: d.id.trim(), enabled: d.enabled, point: d.point, action: d.action };
  if (hasMatcher(d.point)) {
    const match: NonNullable<HookWire["match"]> = {};
    if (d.tool.trim()) match.tool = d.tool.trim();
    if (d.trust) match.trust = d.trust;
    if (d.sensitivity) match.sensitivity = d.sensitivity;
    if (d.irreversible) match.irreversible = d.irreversible === "true";
    if (Object.keys(match).length > 0) hook.match = match;
  }
  const params: Record<string, unknown> = {};
  switch (d.action) {
    case "block":
    case "require_approval":
      if (d.reason.trim()) params.reason = d.reason.trim();
      break;
    case "log":
      if (d.label.trim()) params.label = d.label.trim();
      break;
    case "append_context":
      params.note = d.note.trim();
      break;
    case "limit": {
      const perRun = toInt(d.maxPerRun);
      const perMinute = toInt(d.maxPerMinute);
      if (perRun != null && d.point !== "BEFORE_MODEL") params.maxPerRun = perRun;
      if (perMinute != null) params.maxPerMinute = perMinute;
      if (d.message.trim()) params.message = d.message.trim();
      break;
    }
    case "redact": {
      if (d.patterns.length > 0) params.patterns = [...d.patterns];
      const lines = regexLines(d.regex);
      if (lines.length > 0) params.regex = lines;
      if (d.replacement.trim()) params.replacement = d.replacement.trim();
      break;
    }
    case "webhook": {
      params.url = d.url.trim();
      if (d.includeData) params.includeData = true;
      if (d.failMode) hook.failMode = d.failMode;
      const timeout = toInt(d.timeoutMs);
      if (timeout != null) hook.timeoutMs = timeout;
      break;
    }
  }
  hook.params = params;
  return hook;
}

/** The whole PUT body. */
export function hooksPayload(drafts: HookDraft[]): { hooks: HookWire[] } {
  return { hooks: drafts.map(wireFromDraft) };
}

// ------------------------------------------------------------------ validation

/** Field -> error key (looked up under the `error.` group in the agentHooks namespace). */
export type DraftErrors = Partial<Record<DraftField, string>>;

export type DraftField =
  | "id"
  | "action"
  | "tool"
  | "reason"
  | "note"
  | "label"
  | "url"
  | "patterns"
  | "regex"
  | "replacement"
  | "limit"
  | "message"
  | "timeoutMs";

function regexProblem(source: string): string | null {
  if (source.length > MAX_REGEX_CHARS) return "regexTooLong";
  try {
    const re = new RegExp(source);
    if (re.test("")) return "regexEmpty";
  } catch {
    return "regexInvalid";
  }
  return null;
}

/**
 * Mirrors core HookSchema for one hook. `otherIds` are the ids of the OTHER hooks in the same list (the one being
 * edited is excluded by the caller).
 */
export function validateDraft(d: HookDraft, otherIds: string[]): DraftErrors {
  const errors: DraftErrors = {};
  const id = d.id.trim();
  if (id === "") errors.id = "idRequired";
  else if (!ID_PATTERN.test(id)) errors.id = "idFormat";
  else if (otherIds.includes(id)) errors.id = "idDuplicate";

  if (!ACTIONS_AT[d.point].includes(d.action)) errors.action = "actionNotAllowed";

  if (hasMatcher(d.point) && d.tool.trim() !== "" && !TOOL_GLOB_PATTERN.test(d.tool.trim())) errors.tool = "toolGlobFormat";

  switch (d.action) {
    case "block":
    case "require_approval":
      if (d.reason.trim().length > MAX_REASON_CHARS) errors.reason = "reasonTooLong";
      break;
    case "log":
      if (d.label.trim().length > MAX_LABEL_CHARS) errors.label = "labelTooLong";
      break;
    case "append_context":
      if (d.note.trim() === "") errors.note = "noteRequired";
      else if (d.note.trim().length > MAX_NOTE_CHARS) errors.note = "noteTooLong";
      break;
    case "limit": {
      const perRun = d.point === "BEFORE_MODEL" ? null : toInt(d.maxPerRun);
      const perMinute = toInt(d.maxPerMinute);
      const perRunGiven = d.point !== "BEFORE_MODEL" && d.maxPerRun.trim() !== "";
      const perMinuteGiven = d.maxPerMinute.trim() !== "";
      if (!perRunGiven && !perMinuteGiven) errors.limit = d.point === "BEFORE_MODEL" ? "limitNeedsPerMinute" : "limitNeedsNumber";
      else if (
        (perRunGiven && (perRun == null || perRun < 1 || perRun > MAX_LIMIT_PER_RUN)) ||
        (perMinuteGiven && (perMinute == null || perMinute < 1 || perMinute > MAX_LIMIT_PER_MINUTE))
      ) {
        errors.limit = "limitRange";
      }
      if (d.message.trim().length > MAX_REASON_CHARS) errors.message = "reasonTooLong";
      break;
    }
    case "redact": {
      const lines = regexLines(d.regex);
      if (d.patterns.length === 0 && lines.length === 0) errors.patterns = "patternsRequired";
      if (lines.length > MAX_REGEX_COUNT) errors.regex = "regexTooMany";
      else {
        for (const line of lines) {
          const problem = regexProblem(line);
          if (problem) {
            errors.regex = problem;
            break;
          }
        }
      }
      if (d.replacement.trim().length > MAX_REPLACEMENT_CHARS) errors.replacement = "replacementTooLong";
      break;
    }
    case "webhook": {
      const url = d.url.trim();
      if (url === "") errors.url = "urlRequired";
      else if (!/^https:\/\/[^\s/@]+(\/\S*)?$/i.test(url) || url.length > 2048) errors.url = "urlHttps";
      if (d.timeoutMs.trim() !== "") {
        const t = toInt(d.timeoutMs);
        if (t == null || t < MIN_TIMEOUT_MS || t > MAX_TIMEOUT_MS) errors.timeoutMs = "timeoutRange";
      }
      break;
    }
  }
  return errors;
}

export function hasErrors(errors: DraftErrors): boolean {
  return Object.keys(errors).length > 0;
}

/** Validates the whole list the way the server will (ids unique, at most MAX_HOOKS). */
export function validateAll(drafts: HookDraft[]): { tooMany: boolean; errors: DraftErrors[] } {
  return {
    tooMany: drafts.length > MAX_HOOKS,
    errors: drafts.map((d, i) =>
      validateDraft(
        d,
        drafts.filter((_, j) => j !== i).map((o) => o.id.trim())
      )
    ),
  };
}

/** A friendly id from free text: lower-case, hyphenated, at most 40 characters. */
export function slugifyId(text: string): string {
  return (text ?? "")
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, "-")
    .replace(/^-+|-+$/g, "")
    .slice(0, 40)
    .replace(/-+$/g, "");
}

/** An id not yet used in the list, based on the action ("block-2" ...). */
export function nextFreeId(base: string, taken: string[]): string {
  const root = slugifyId(base) || "hook";
  if (!taken.includes(root)) return root;
  for (let i = 2; i < 1000; i++) {
    const candidate = `${root.slice(0, 36)}-${i}`;
    if (!taken.includes(candidate)) return candidate;
  }
  return root;
}

// -------------------------------------------------------------------- preview

export type Translate = (key: string, values?: Record<string, string | number>) => string;

/** The host of a URL for display, or the raw text when it does not parse. */
export function hostOf(url: string): string {
  try {
    return new URL(url).host;
  } catch {
    return url;
  }
}

function matchPhrase(d: HookDraft, t: Translate): string {
  const parts: string[] = [];
  if (d.tool.trim()) parts.push(t("match.tool", { glob: d.tool.trim() }));
  if (d.trust) parts.push(t("match.trust", { value: t(`trustLabel.${d.trust}`) }));
  if (d.sensitivity) parts.push(t("match.sensitivity", { value: t(`sensitivityLabel.${d.sensitivity}`) }));
  if (d.irreversible === "true") parts.push(t("match.irreversible"));
  else if (d.irreversible === "false") parts.push(t("match.reversible"));
  if (parts.length === 0) return d.point === "ON_ERROR" ? t("match.anyError") : t("match.any");
  return parts.join(", ");
}

function limitPhrase(d: HookDraft, t: Translate): string {
  const perRun = toInt(d.maxPerRun);
  const perMinute = toInt(d.maxPerMinute);
  if (d.point === "BEFORE_MODEL") return t("do.limitModel", { perMinute: perMinute ?? "?" });
  if (perRun != null && perMinute != null) return t("do.limitBoth", { perRun, perMinute });
  if (perRun != null) return t("do.limitRun", { perRun });
  return t("do.limitMinute", { perMinute: perMinute ?? "?" });
}

function redactPhrase(d: HookDraft, t: Translate): string {
  const kinds: string[] = d.patterns.map((p) => t(`patternLabel.${p}`));
  const custom = regexLines(d.regex).length;
  if (custom > 0) kinds.push(t("do.redactCustom", { count: custom }));
  return t(`do.redact.${d.point}`, { kinds: kinds.join(", ") });
}

function actionPhrase(d: HookDraft, t: Translate): string {
  switch (d.action) {
    case "block": {
      const base = t(`do.block.${d.point}`);
      return d.reason.trim() ? t("do.withReason", { text: base, reason: d.reason.trim() }) : base;
    }
    case "require_approval": {
      const base = t("do.requireApproval");
      return d.reason.trim() ? t("do.withReason", { text: base, reason: d.reason.trim() }) : base;
    }
    case "redact":
      return redactPhrase(d, t);
    case "limit":
      return limitPhrase(d, t);
    case "append_context":
      return t(`do.appendContext.${d.point}`, { note: d.note.trim() });
    case "webhook": {
      const host = d.url.trim() ? hostOf(d.url.trim()) : t("do.noHost");
      if (d.point === "ON_ERROR" || d.point === "ON_RUN_END") return t("do.notify", { host });
      const mode = d.failMode || (d.point === "BEFORE_MODEL" || d.point === "BEFORE_TOOL" ? "closed" : "open");
      const timeout = toInt(d.timeoutMs) ?? DEFAULT_TIMEOUT_MS;
      return t("do.webhook", { host, mode: t(`failModeLabel.${mode}`), timeout });
    }
    case "log":
      return d.label.trim() ? t("do.logLabel", { label: d.label.trim() }) : t("do.log");
  }
  return "";
}

/**
 * One plain sentence saying what the hook does, built from the same draft the form edits - so the preview can never
 * disagree with what Save will send. Every phrase comes from the agentHooks translation namespace.
 */
export function describeDraft(d: HookDraft, t: Translate): string {
  const when = t(`when.${d.point}`);
  const action = actionPhrase(d, t);
  if (hasMatcher(d.point)) {
    return t("sentence.withMatch", { when, who: matchPhrase(d, t), action });
  }
  return t("sentence.noMatch", { when, action });
}

export function describeWire(h: HookWire, t: Translate): string {
  return describeDraft(draftFromWire(h), t);
}
