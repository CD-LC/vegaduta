import type { EffectiveSetting, SettingCatalogEntry } from "../../lib/api/settings";
import {
  catalogDurationUnit,
  needsReason,
  numericValue,
  parseDraft,
  sameValue,
  type ValidationCode,
} from "../../components/settings/settingsModel";

// Pure model behind the export / import of workspace overrides. An export is the set of values an admin has
// changed in this workspace (source "tenant"); an import is validated against the catalog - the same bounds the
// editing form enforces - and turned into a plan the admin reviews before anything is written. No React, no i18n:
// problems carry codes and the components localize them.

export const OVERRIDES_FORMAT = "vegaduta.settings-overrides";
export const OVERRIDES_VERSION = 1;

export interface OverridesFile {
  format: typeof OVERRIDES_FORMAT;
  version: typeof OVERRIDES_VERSION;
  scope: "tenant";
  exportedAt: string;
  overrides: Record<string, unknown>;
}

const NUMERIC_TYPES = new Set(["int", "long", "decimal", "percent", "duration"]);

/** The value as it should be written in a file: numbers in the catalog's own unit, booleans as booleans. */
function portableValue(entry: SettingCatalogEntry, value: unknown): unknown {
  if (entry.type === "bool") return value === true || value === "true";
  if (NUMERIC_TYPES.has(entry.type)) return numericValue(entry, value) ?? value;
  return value == null ? "" : String(value);
}

/** Every tenant-scope setting that has a workspace override, keyed by setting key (stable, sorted order). */
export function buildExport(
  entries: SettingCatalogEntry[],
  values: Record<string, EffectiveSetting>,
  now: Date = new Date()
): OverridesFile {
  const overrides: Record<string, unknown> = {};
  [...entries]
    .filter((e) => e.scopes.includes("tenant") && values[e.key]?.source === "tenant")
    .sort((a, b) => a.key.localeCompare(b.key))
    .forEach((e) => {
      overrides[e.key] = portableValue(e, values[e.key].value);
    });
  return { format: OVERRIDES_FORMAT, version: OVERRIDES_VERSION, scope: "tenant", exportedAt: now.toISOString(), overrides };
}

export type FileErrorCode = "empty" | "invalidJson" | "wrongFormat" | "unsupportedVersion" | "noOverrides";

export type ParsedFile = { ok: true; overrides: Record<string, unknown> } | { ok: false; error: FileErrorCode };

/** Read the text of an overrides file. Only the format this page exports is accepted. */
export function parseOverridesText(text: string): ParsedFile {
  if (text.trim() === "") return { ok: false, error: "empty" };
  let raw: unknown;
  try {
    raw = JSON.parse(text);
  } catch {
    return { ok: false, error: "invalidJson" };
  }
  if (!raw || typeof raw !== "object" || Array.isArray(raw)) return { ok: false, error: "wrongFormat" };
  const file = raw as Record<string, unknown>;
  if (file.format !== OVERRIDES_FORMAT) return { ok: false, error: "wrongFormat" };
  if (file.version !== OVERRIDES_VERSION) return { ok: false, error: "unsupportedVersion" };
  const overrides = file.overrides;
  if (!overrides || typeof overrides !== "object" || Array.isArray(overrides)) return { ok: false, error: "noOverrides" };
  return { ok: true, overrides: overrides as Record<string, unknown> };
}

export type ProblemCode = "unknownKey" | "notWorkspaceSetting" | "locked" | "wrongType" | ValidationCode;

export interface ImportProblem {
  key: string;
  code: ProblemCode;
  min?: number;
  max?: number;
}

export interface ImportChange {
  entry: SettingCatalogEntry;
  /** The value in force now. */
  from: unknown;
  fromSource: EffectiveSetting["source"];
  /** The validated value from the file, in the form the API takes. */
  to: unknown;
}

export interface ImportReset {
  entry: SettingCatalogEntry;
  from: unknown;
}

export interface ImportPlan {
  changes: ImportChange[];
  /** Keys in the file whose value already is in force: nothing to do. */
  unchanged: string[];
  /** Overrides that exist now but are not in the file; only filled when resetMissing is asked for. */
  resets: ImportReset[];
  /** Entries of the file that will NOT be applied, and why. */
  problems: ImportProblem[];
}

type Checked = { ok: true; value: unknown } | { ok: false; code: ProblemCode; min?: number; max?: number };

function checkValue(entry: SettingCatalogEntry, raw: unknown): Checked {
  // The JSON type must already be right: "50" for a number would be accepted by the form but a file should be exact.
  switch (entry.type) {
    case "bool":
      return typeof raw === "boolean" ? { ok: true, value: raw } : { ok: false, code: "wrongType" };
    case "enum":
    case "string":
    case "text":
      if (typeof raw !== "string") return { ok: false, code: "wrongType" };
      break;
    default:
      if (typeof raw !== "number" || !Number.isFinite(raw)) return { ok: false, code: "wrongType" };
  }
  const parsed = parseDraft(entry, {
    value: String(raw),
    unit: entry.type === "duration" ? catalogDurationUnit(entry) : undefined,
  });
  return "error" in parsed
    ? { ok: false, code: parsed.error.code, min: parsed.error.min, max: parsed.error.max }
    : { ok: true, value: parsed.value };
}

/** Turn a parsed file into what would happen if it were applied now. Nothing is written. */
export function planImport(
  entries: SettingCatalogEntry[],
  values: Record<string, EffectiveSetting>,
  overrides: Record<string, unknown>,
  opts: { resetMissing: boolean }
): ImportPlan {
  const byKey = new Map(entries.map((e) => [e.key, e]));
  const plan: ImportPlan = { changes: [], unchanged: [], resets: [], problems: [] };
  for (const [key, raw] of Object.entries(overrides)) {
    const entry = byKey.get(key);
    if (!entry) {
      plan.problems.push({ key, code: "unknownKey" });
      continue;
    }
    if (!entry.scopes.includes("tenant")) {
      plan.problems.push({ key, code: "notWorkspaceSetting" });
      continue;
    }
    const current = values[key];
    if (current?.locked) {
      plan.problems.push({ key, code: "locked" });
      continue;
    }
    const checked = checkValue(entry, raw);
    if ("code" in checked) {
      plan.problems.push({ key, code: checked.code, min: checked.min, max: checked.max });
      continue;
    }
    const now = current ? current.value : entry.default;
    if (sameValue(entry, checked.value, now)) {
      plan.unchanged.push(key);
      continue;
    }
    plan.changes.push({ entry, from: now, fromSource: current?.source ?? "default", to: checked.value });
  }
  if (opts.resetMissing) {
    for (const entry of entries) {
      const current = values[entry.key];
      if (!entry.scopes.includes("tenant") || current?.source !== "tenant" || current.locked) continue;
      if (Object.prototype.hasOwnProperty.call(overrides, entry.key)) continue;
      plan.resets.push({ entry, from: current.value });
    }
  }
  plan.changes.sort((a, b) => a.entry.key.localeCompare(b.entry.key));
  plan.resets.sort((a, b) => a.entry.key.localeCompare(b.entry.key));
  return plan;
}

/** True when applying the plan needs the typed-reason confirmation (some change is high or security risk). */
export function planNeedsReason(plan: ImportPlan): boolean {
  return plan.changes.some((c) => needsReason(c.entry));
}

/** The order catalog groups are shown in; groups the list does not know follow, in catalog order. */
export const GROUP_ORDER = ["agent", "chat", "tools", "approvals", "quality", "guardrails", "memory", "knowledge", "delegation", "aux", "routing"];

export function orderGroups(groups: string[]): string[] {
  const rank = (g: string) => {
    const i = GROUP_ORDER.indexOf(g);
    return i === -1 ? GROUP_ORDER.length : i;
  };
  return groups.map((g, i) => ({ g, i })).sort((a, b) => rank(a.g) - rank(b.g) || a.i - b.i).map((x) => x.g);
}

/** A group is a security group when any of its settings carries the "security" risk. */
export function isSecurityGroup(entries: SettingCatalogEntry[]): boolean {
  return entries.some((e) => e.risk === "security");
}
