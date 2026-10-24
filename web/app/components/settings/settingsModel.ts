import type {
  EffectiveSetting,
  SettingCatalogEntry,
  SettingRole,
  SettingScope,
  SettingSource,
} from "../../lib/api/settings";

// Pure model behind the catalog-driven settings components: drafts, validation against the catalog's own
// bounds, duration unit conversion, display formatting and role checks. No React, no i18n - validation returns
// codes + params and the components localize them.

/** A value being edited. Bool uses a boolean; every other type edits text (an enum holds the option value). */
export interface Draft {
  value: string | boolean;
  /** Duration only: the unit the number is typed in (ms | s | min | h). Defaults to the catalog unit. */
  unit?: DurationUnit;
}

export type DurationUnit = "ms" | "s" | "min" | "h";
export const DURATION_UNITS: DurationUnit[] = ["ms", "s", "min", "h"];
const DURATION_MS: Record<DurationUnit, number> = { ms: 1, s: 1000, min: 60_000, h: 3_600_000 };

/** Minimum length of the typed reason for high/security changes. */
export const MIN_REASON_LENGTH = 3;

export type ValidationCode =
  | "required"
  | "notNumber"
  | "notInteger"
  | "belowMin"
  | "aboveMax"
  | "notAllowed"
  | "tooShort"
  | "tooLong"
  | "precision";

export interface ValidationError {
  code: ValidationCode;
  min?: number;
  max?: number;
}

export type ParseResult = { ok: true; value: unknown } | { ok: false; error: ValidationError };

export function catalogDurationUnit(entry: SettingCatalogEntry): DurationUnit {
  return entry.unit === "s" ? "s" : "ms";
}

/** Convert a duration between units, rounding away binary float noise (1.1 min -> 66000 ms, not 66000.00000000001). */
export function convertDuration(amount: number, from: DurationUnit, to: DurationUnit): number {
  return roundTo((amount * DURATION_MS[from]) / DURATION_MS[to], 6);
}

function roundTo(n: number, places: number): number {
  const f = 10 ** places;
  return Math.round(n * f) / f;
}

/** ISO-8601 time duration (PT15M, PT1H30M, P1D) -> milliseconds, or null when it is not one. */
export function parseIsoDurationMs(text: string): number | null {
  const m = /^P(?:(\d+(?:\.\d+)?)D)?(?:T(?:(\d+(?:\.\d+)?)H)?(?:(\d+(?:\.\d+)?)M)?(?:(\d+(?:\.\d+)?)S)?)?$/.exec(text.trim());
  if (!m || (!m[1] && !m[2] && !m[3] && !m[4])) return null;
  const [d, h, mi, s] = [m[1], m[2], m[3], m[4]].map((x) => (x ? Number(x) : 0));
  return Math.round(d * 86_400_000 + h * 3_600_000 + mi * 60_000 + s * 1000);
}

/** The setting's value as a plain number in the catalog's own unit (durations may arrive as ISO strings). */
export function numericValue(entry: SettingCatalogEntry, value: unknown): number | null {
  if (typeof value === "number" && Number.isFinite(value)) return value;
  if (typeof value === "string" && value.trim() !== "") {
    if (entry.type === "duration") {
      const ms = parseIsoDurationMs(value);
      if (ms != null) return convertDuration(ms, "ms", catalogDurationUnit(entry));
    }
    const n = Number(value);
    if (Number.isFinite(n)) return n;
  }
  return null;
}

/** Most readable duration unit for a value (so 90000 ms shows as 1.5 min). */
export function bestDurationUnit(entry: SettingCatalogEntry, value: unknown): DurationUnit {
  const base = catalogDurationUnit(entry);
  const n = numericValue(entry, value);
  if (n == null || n === 0) return base;
  const ms = convertDuration(n, base, "ms");
  for (const u of ["h", "min", "s", "ms"] as DurationUnit[]) {
    const v = ms / DURATION_MS[u];
    if (Math.abs(v) >= 1 && Number.isInteger(roundTo(v, 6)) && u !== "ms") return u;
  }
  return base === "s" ? "s" : "ms";
}

/** A fresh draft holding the given value, ready to be edited. */
export function draftFromValue(entry: SettingCatalogEntry, value: unknown, unit?: DurationUnit): Draft {
  switch (entry.type) {
    case "bool":
      return { value: value === true || value === "true" };
    case "duration": {
      const u = unit ?? catalogDurationUnit(entry);
      const n = numericValue(entry, value);
      return { value: n == null ? "" : String(convertDuration(n, catalogDurationUnit(entry), u)), unit: u };
    }
    default:
      return { value: value == null ? "" : String(value) };
  }
}

const NUMERIC = /^[+-]?(\d+\.?\d*|\.\d+)$/;

/** Ok(value) when n is inside the catalog's bounds, otherwise the matching bounds error. */
function inRange(n: number, entry: SettingCatalogEntry): ParseResult {
  if (entry.min != null && n < entry.min) return { ok: false, error: { code: "belowMin", min: entry.min, max: entry.max } };
  if (entry.max != null && n > entry.max) return { ok: false, error: { code: "aboveMax", min: entry.min, max: entry.max } };
  return { ok: true, value: n };
}

/** Validate and convert a draft into the value the API takes, using only the catalog's rules. */
export function parseDraft(entry: SettingCatalogEntry, draft: Draft): ParseResult {
  if (entry.type === "bool") return { ok: true, value: draft.value === true };
  const text = typeof draft.value === "string" ? draft.value : String(draft.value);
  const trimmed = text.trim();
  switch (entry.type) {
    case "enum": {
      const allowed = entry.enumValues ?? [];
      if (trimmed === "") return { ok: false, error: { code: "required" } };
      return allowed.includes(trimmed) ? { ok: true, value: trimmed } : { ok: false, error: { code: "notAllowed" } };
    }
    case "string":
    case "text": {
      // min/max are length bounds for text. A minimum of 1 or more means "must not be empty".
      const len = text.length;
      if (entry.min != null && entry.min >= 1 && trimmed === "") return { ok: false, error: { code: "required" } };
      if (entry.min != null && len < entry.min) return { ok: false, error: { code: "tooShort", min: entry.min, max: entry.max } };
      if (entry.max != null && len > entry.max) return { ok: false, error: { code: "tooLong", min: entry.min, max: entry.max } };
      return { ok: true, value: text };
    }
    case "int":
    case "long": {
      if (trimmed === "") return { ok: false, error: { code: "required" } };
      if (!NUMERIC.test(trimmed)) return { ok: false, error: { code: "notNumber" } };
      const n = Number(trimmed);
      if (!Number.isFinite(n)) return { ok: false, error: { code: "notNumber" } };
      if (!Number.isInteger(n)) return { ok: false, error: { code: "notInteger" } };
      return inRange(n, entry);
    }
    case "decimal":
    case "percent": {
      if (trimmed === "") return { ok: false, error: { code: "required" } };
      if (!NUMERIC.test(trimmed)) return { ok: false, error: { code: "notNumber" } };
      const n = Number(trimmed);
      if (!Number.isFinite(n)) return { ok: false, error: { code: "notNumber" } };
      return inRange(n, entry);
    }
    case "duration": {
      if (trimmed === "") return { ok: false, error: { code: "required" } };
      if (!NUMERIC.test(trimmed)) return { ok: false, error: { code: "notNumber" } };
      const typed = Number(trimmed);
      if (!Number.isFinite(typed)) return { ok: false, error: { code: "notNumber" } };
      const base = catalogDurationUnit(entry);
      const n = convertDuration(typed, draft.unit ?? base, base);
      if (!Number.isInteger(n)) return { ok: false, error: { code: "precision" } };
      return inRange(n, entry);
    }
    default:
      return { ok: true, value: text };
  }
}

/** True when two setting values are the same for the purpose of "has the user changed anything". */
export function sameValue(entry: SettingCatalogEntry, a: unknown, b: unknown): boolean {
  if (entry.type === "bool") return (a === true || a === "true") === (b === true || b === "true");
  if (["int", "long", "decimal", "percent", "duration"].includes(entry.type)) {
    const x = numericValue(entry, a);
    const y = numericValue(entry, b);
    return x != null && y != null && x === y;
  }
  return String(a ?? "") === String(b ?? "");
}

/**
 * Is this draft a real change relative to the current value? An invalid draft is always dirty (the user typed
 * something the server never saw); a valid one equal to the current value is not.
 */
export function isDirty(entry: SettingCatalogEntry, draft: Draft | undefined, current: unknown): boolean {
  if (!draft) return false;
  const parsed = parseDraft(entry, draft);
  if (!parsed.ok) return true;
  return !sameValue(entry, parsed.value, current);
}

// ---- display ----

/** "max_tool_steps"/"agent.limits.maxToolSteps" -> "Max tool steps" - the fallback when no translation exists. */
export function humanize(key: string): string {
  const last = key.includes(".") ? key.slice(key.lastIndexOf(".") + 1) : key;
  const words = last
    .replace(/[_-]+/g, " ")
    .replace(/([a-z0-9])([A-Z])/g, "$1 $2")
    .trim()
    .toLowerCase();
  return words ? words.charAt(0).toUpperCase() + words.slice(1) : key;
}

/** Number as shown to people: thousands separators, no trailing float noise. */
export function formatNumber(n: number, locale?: string): string {
  try {
    return new Intl.NumberFormat(locale, { maximumFractionDigits: 6 }).format(n);
  } catch {
    return String(n);
  }
}

// ---- roles ----

const ROLE_RANK: Record<SettingRole, number> = { VIEWER: 0, EDITOR: 1, TENANT_ADMIN: 2 };

export function roleAtLeast(role: SettingRole | undefined, needed: SettingRole): boolean {
  return role == null || ROLE_RANK[role] >= ROLE_RANK[needed];
}

/**
 * The role to apply when the caller did not pass one. At agent scope the page already proved the caller can reach the
 * agent's editor (the API refuses otherwise), so an explicit role is not needed to allow EDITOR keys - but the keys that
 * need a workspace admin (every security key) must not look editable to someone who is not one: the server answers 403
 * after the save. A tenant admin or platform admin is TENANT_ADMIN, anyone else EDITOR. With no signed-in context (a
 * test, a public page) nothing is assumed and the server decides.
 */
export function resolveSettingsRole(
  role: SettingRole | undefined,
  scope: SettingScope,
  auth: { isAdmin?: boolean; isPlatformAdmin?: boolean } | null | undefined
): SettingRole | undefined {
  if (role) return role;
  if (scope !== "agent" || !auth) return undefined;
  return auth.isAdmin || auth.isPlatformAdmin ? "TENANT_ADMIN" : "EDITOR";
}

export type ReadOnlyReason = "readOnly" | "locked" | "role" | "scope" | "none";

/** Whether the viewer may edit this entry at this scope, and if not, why (for the explanation text). */
export function editability(
  entry: SettingCatalogEntry,
  eff: EffectiveSetting | undefined,
  opts: { scope: SettingScope; role?: SettingRole; readOnly?: boolean }
): ReadOnlyReason {
  if (opts.readOnly) return "readOnly";
  if (eff?.locked) return "locked";
  if (!entry.scopes.includes(opts.scope)) return "scope";
  // Writing at tenant scope is a tenant-admin act whatever the entry's own floor says; agent scope needs EDITOR.
  const needed: SettingRole = opts.scope === "tenant" ? "TENANT_ADMIN" : "EDITOR";
  const floor = ROLE_RANK[entry.minRole] > ROLE_RANK[needed] ? entry.minRole : needed;
  return roleAtLeast(opts.role, floor) ? "none" : "role";
}

/** Which source means "an override exists at the scope being edited" (the only thing reset can remove). */
export function overrideSource(scope: SettingScope): SettingSource {
  return scope === "agent" ? "agent" : "tenant";
}

export function needsReason(entry: SettingCatalogEntry): boolean {
  return entry.risk === "high" || entry.risk === "security";
}
