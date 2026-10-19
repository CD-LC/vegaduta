// Dify-style AI-native workflow nodes - pure model + helpers (no React, no i18n hooks), so the canvas node, the
// config drawer and the unit tests all share one definition.
//
//   question_classifier  picks exactly ONE author-defined class with a constrained model call and routes down the
//                        matching output port (the class `handle` is the edge's sourceHandle - the same mechanism
//                        condition / supervisor use).
//   parameter_extractor  typed JSON extraction; missing required values are listed under `_missing`.
//   variable_aggregator  no model: the first non-empty of N source values (usually {{ $node["Label"].x }}).
//   document_extractor   an uploaded file / page / text to plain text, with size, page and row caps.
//
// The limits and validation rules below MIRROR core's WorkflowAiNodeHandler (MAX_CLASSES, MAX_PARAMS, MAX_SOURCES,
// RESERVED_HANDLE, the parameter-name pattern): the server re-checks everything at save time, this only lets the
// drawer say so earlier.

export const AI_NODE_TYPES = ["question_classifier", "parameter_extractor", "variable_aggregator", "document_extractor"] as const;
export type AiNodeType = (typeof AI_NODE_TYPES)[number];

export function isAiNodeType(type: string | null | undefined): type is AiNodeType {
  return !!type && (AI_NODE_TYPES as readonly string[]).includes(type);
}

// Palette label/description keys of these four live in the "workflowNodesAi" namespace instead of "nodeDefs"
// (see useNodeDefTranslations); the key shape is still nodeLabelKey/nodeDescriptionKey's `${type}Label|Description`.
const AI_DEF_KEY = /^(question_classifier|parameter_extractor|variable_aggregator|document_extractor)(Label|Description)$/;

export function isAiNodeDefKey(key: string): boolean {
  return AI_DEF_KEY.test(key);
}

export const MAX_AI_CLASSES = 20;
export const MAX_AI_PARAMS = 30;
export const MAX_AI_SOURCES = 20;
export const MAX_AI_ENUM_OPTIONS = 50;
export const AI_HANDLE_MAX = 64;
/** The shared failure port every node has; a class may not use it as its handle. */
export const AI_RESERVED_HANDLE = "error";
export const AI_PARAM_NAME = /^[A-Za-z][A-Za-z0-9_]{0,63}$/;

export const AI_PARAM_TYPES = ["string", "number", "boolean", "array", "enum"] as const;
export type AiParamType = (typeof AI_PARAM_TYPES)[number];

export const AI_DOC_FORMATS = ["auto", "pdf", "docx", "xlsx", "csv", "txt", "md", "html"] as const;
export type AiDocFormat = (typeof AI_DOC_FORMATS)[number];

export type AiClassDef = { handle: string; description: string; label?: string };
export type AiParamDef = { name: string; type: AiParamType; description: string; required: boolean; options?: string[] };

// Every key any of the four nodes reads. `source` is shared with crm_lead's string `source`, `instruction` is the
// singular form (supervisor's is `instructions`), `text` is the optional expression-resolved input override.
export type AiNodeConfig = {
  // question_classifier
  classes?: AiClassDef[];
  defaultClass?: string;
  minConfidence?: number;
  // question_classifier + parameter_extractor
  text?: string;
  instruction?: string;
  provider?: string;
  model?: string;
  // question_classifier / document_extractor: "passthrough" | "result" / "text" | "json"
  outputMode?: string;
  // parameter_extractor
  parameters?: AiParamDef[];
  // variable_aggregator
  sources?: string[];
  fallback?: string;
  // document_extractor
  source?: string;
  format?: string;
  maxChars?: number;
  maxPages?: number;
  maxRows?: number;
};

export function aiNodeDefaultConfig(type: AiNodeType): AiNodeConfig {
  switch (type) {
    case "question_classifier":
      return {
        classes: [
          { handle: "class-1", description: "" },
          { handle: "class-2", description: "" },
        ],
        defaultClass: "",
        minConfidence: 0.5,
        text: "",
        instruction: "",
        outputMode: "passthrough",
      };
    case "parameter_extractor":
      return { parameters: [{ name: "value", type: "string", description: "", required: false }], text: "", instruction: "" };
    case "variable_aggregator":
      return { sources: ["", ""], fallback: "input" };
    case "document_extractor":
      return { source: "", format: "auto", outputMode: "text" };
  }
}

// ---------------------------------------------------------------------------
// Small immutable list helpers (the drawer never mutates stored config in place: every node of a type starts from the
// same defaultConfig object).
// ---------------------------------------------------------------------------

export function updateAt<T>(list: readonly T[], index: number, patch: Partial<T>): T[] {
  return list.map((item, i) => (i === index ? { ...item, ...patch } : item));
}

export function setAt<T>(list: readonly T[], index: number, item: T): T[] {
  return list.map((existing, i) => (i === index ? item : existing));
}

export function removeAt<T>(list: readonly T[], index: number): T[] {
  return list.filter((_, i) => i !== index);
}

export function replaceAt(list: readonly string[], index: number, value: string): string[] {
  return list.map((item, i) => (i === index ? value : item));
}

// ---------------------------------------------------------------------------
// question_classifier
// ---------------------------------------------------------------------------

export function readClasses(config: AiNodeConfig | undefined): AiClassDef[] {
  const raw = config?.classes;
  if (!Array.isArray(raw)) return [];
  return raw
    .filter((c) => c && typeof c === "object")
    .map((c): AiClassDef => ({ ...c, handle: typeof c.handle === "string" ? c.handle : "", description: typeof c.description === "string" ? c.description : "" }));
}

/** A handle-safe slug of free text: lowercase letters, digits and dashes, capped at the server's handle length. */
export function slugHandle(text: string): string {
  return (text || "")
    .trim()
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, "-")
    .replace(/^-+|-+$/g, "")
    .slice(0, AI_HANDLE_MAX);
}

/** `base` made distinct (case-insensitively) from `taken`, and never the reserved failure handle. */
export function uniqueHandle(base: string, taken: readonly string[]): string {
  const used = new Set(taken.map((h) => h.trim().toLowerCase()));
  const root = slugHandle(base) || "class";
  let candidate = root === AI_RESERVED_HANDLE ? `${root}-1` : root;
  for (let n = 2; used.has(candidate.toLowerCase()); n++) {
    candidate = `${root.slice(0, AI_HANDLE_MAX - 4)}-${n}`;
  }
  return candidate;
}

/** The next class a click on "Add class" appends. */
export function newClass(classes: readonly AiClassDef[]): AiClassDef {
  return { handle: uniqueHandle(`class-${classes.length + 1}`, classes.map((c) => c.handle)), description: "" };
}

export type AiPort = { id: string; title: string };

/**
 * One output port per usable class: `id` is the Handle id and therefore the sourceHandle an edge persists, which the
 * server matches (case-insensitively) against the chosen class. Blank, reserved ("error" is every node's failure
 * port) and duplicate handles are skipped so React Flow never sees two Handles with one id on a node.
 */
export function classifierPorts(config: AiNodeConfig | undefined): AiPort[] {
  const seen = new Set<string>();
  const ports: AiPort[] = [];
  for (const c of readClasses(config)) {
    const id = c.handle.trim();
    const key = id.toLowerCase();
    if (!id || key === AI_RESERVED_HANDLE || seen.has(key)) continue;
    seen.add(key);
    const hint = c.description.trim();
    ports.push({ id, title: hint ? `${id} - ${hint.length > 60 ? `${hint.slice(0, 59)}…` : hint}` : id });
  }
  return ports;
}

/** Edge pill text for an edge drawn off a classifier port; undefined when the handle names no class. */
export function classifierEdgeLabel(config: AiNodeConfig | undefined, handle: string): string | undefined {
  const port = classifierPorts(config).find((p) => p.id === handle);
  return port ? port.id : undefined;
}

// ---------------------------------------------------------------------------
// parameter_extractor
// ---------------------------------------------------------------------------

export function readParams(config: AiNodeConfig | undefined): AiParamDef[] {
  const raw = config?.parameters;
  if (!Array.isArray(raw)) return [];
  return raw
    .filter((p) => p && typeof p === "object")
    .map((p): AiParamDef => ({
      ...p,
      name: typeof p.name === "string" ? p.name : "",
      type: (AI_PARAM_TYPES as readonly string[]).includes(p.type) ? p.type : "string",
      description: typeof p.description === "string" ? p.description : "",
      required: p.required === true,
    }));
}

export function newParam(params: readonly AiParamDef[]): AiParamDef {
  const taken = params.map((p) => p.name.toLowerCase());
  let n = params.length + 1;
  while (taken.includes(`param_${n}`)) n++;
  return { name: `param_${n}`, type: "string", description: "", required: false };
}

/** Enum options from free text: split on commas / newlines, trimmed, de-duplicated case-insensitively, capped. */
export function parseOptionsText(text: string): string[] {
  const seen = new Set<string>();
  const out: string[] = [];
  for (const part of (text || "").split(/[,\n]/)) {
    const option = part.trim();
    const key = option.toLowerCase();
    if (!option || seen.has(key) || out.length >= MAX_AI_ENUM_OPTIONS) continue;
    seen.add(key);
    out.push(option);
  }
  return out;
}

export function optionsText(param: AiParamDef): string {
  return (param.options || []).join(", ");
}

/** Switching a parameter's type: options only make sense for enum, so they are dropped (not hidden) otherwise. */
export function withParamType(param: AiParamDef, type: AiParamType): AiParamDef {
  const next: AiParamDef = { ...param, type };
  if (type !== "enum") delete next.options;
  return next;
}

// ---------------------------------------------------------------------------
// variable_aggregator
// ---------------------------------------------------------------------------

export function readSources(config: AiNodeConfig | undefined): string[] {
  const raw = config?.sources;
  return Array.isArray(raw) ? raw.map((s) => (typeof s === "string" ? s : "")) : [];
}

// ---------------------------------------------------------------------------
// Validation (mirrors the server's save-time checks; codes map to en.json "problem_<code>")
// ---------------------------------------------------------------------------

export type AiProblem = { code: string; value?: string };

export function classifierProblems(config: AiNodeConfig | undefined): AiProblem[] {
  const problems: AiProblem[] = [];
  const classes = readClasses(config);
  const seen = new Set<string>();
  let usable = 0;
  for (const c of classes) {
    const handle = c.handle.trim();
    const key = handle.toLowerCase();
    if (!handle) {
      problems.push({ code: "emptyHandle" });
    } else if (handle.length > AI_HANDLE_MAX) {
      problems.push({ code: "handleTooLong", value: handle.slice(0, 20) });
    } else if (key === AI_RESERVED_HANDLE) {
      problems.push({ code: "reservedHandle", value: handle });
    } else if (seen.has(key)) {
      problems.push({ code: "duplicateHandle", value: handle });
    } else {
      seen.add(key);
      usable++;
    }
  }
  if (classes.length > MAX_AI_CLASSES) problems.push({ code: "tooManyClasses", value: String(MAX_AI_CLASSES) });
  if (usable < 2) problems.push({ code: "tooFewClasses" });
  const def = (config?.defaultClass || "").trim().toLowerCase();
  if (def && !seen.has(def)) problems.push({ code: "badDefaultClass", value: config?.defaultClass });
  return problems;
}

export function parameterProblems(config: AiNodeConfig | undefined): AiProblem[] {
  const problems: AiProblem[] = [];
  const params = readParams(config);
  const seen = new Set<string>();
  let usable = 0;
  for (const p of params) {
    const name = p.name.trim();
    const key = name.toLowerCase();
    if (!AI_PARAM_NAME.test(name)) {
      problems.push({ code: "invalidParamName", value: name });
    } else if (seen.has(key)) {
      problems.push({ code: "duplicateParamName", value: name });
    } else {
      // The name is reserved even when the parameter is otherwise unusable (as the server does), so a second
      // parameter with the same name is reported as a duplicate rather than silently accepted.
      seen.add(key);
      if (p.type === "enum" && (p.options || []).filter((o) => o.trim()).length === 0) {
        problems.push({ code: "enumNoOptions", value: name });
      } else {
        usable++;
      }
    }
  }
  if (params.length > MAX_AI_PARAMS) problems.push({ code: "tooManyParams", value: String(MAX_AI_PARAMS) });
  if (usable === 0) problems.push({ code: "noParameters" });
  return problems;
}

export function aggregatorProblems(config: AiNodeConfig | undefined): AiProblem[] {
  const sources = readSources(config);
  const problems: AiProblem[] = [];
  if (!sources.some((s) => s.trim())) problems.push({ code: "noSources" });
  if (sources.length > MAX_AI_SOURCES) problems.push({ code: "tooManySources", value: String(MAX_AI_SOURCES) });
  return problems;
}

export function aiNodeProblems(type: string | null | undefined, config: AiNodeConfig | undefined): AiProblem[] {
  switch (type) {
    case "question_classifier":
      return classifierProblems(config);
    case "parameter_extractor":
      return parameterProblems(config);
    case "variable_aggregator":
      return aggregatorProblems(config);
    default:
      return [];
  }
}

// ---------------------------------------------------------------------------
// Canvas subtitle
// ---------------------------------------------------------------------------

type Translator = (key: string, values?: Record<string, string | number>) => string;

/** The one-line subtitle of an AI node's card, from the "workflowNodesAi" namespace. */
export function aiNodeSummary(type: string, config: AiNodeConfig | undefined, t: Translator): string {
  switch (type) {
    case "question_classifier": {
      const count = classifierPorts(config).length;
      return count > 0 ? t("classifierSummary", { count }) : t("classifierNoClasses");
    }
    case "parameter_extractor": {
      const count = readParams(config).length;
      return count > 0 ? t("extractorSummary", { count }) : t("extractorNoParams");
    }
    case "variable_aggregator": {
      const count = readSources(config).filter((s) => s.trim()).length;
      return count > 0 ? t("aggregatorSummary", { count }) : t("aggregatorNoSources");
    }
    case "document_extractor": {
      const format = (config?.format || "auto").toLowerCase();
      return t("documentSummary", { format: format === "auto" ? t("documentAuto") : format.toUpperCase() });
    }
    default:
      return "";
  }
}

/** A numeric input's text to the config value: blank means "use the deployment default" (key left unset). */
export function optionalInt(text: string, min: number, max: number): number | undefined {
  const trimmed = text.trim();
  if (!trimmed) return undefined;
  const n = Number(trimmed);
  if (!Number.isFinite(n)) return undefined;
  return Math.max(min, Math.min(max, Math.round(n)));
}

/** Every problem code the validators above can emit; en.json carries a `problem_<code>` string for each (test-pinned). */
export const AI_PROBLEM_CODES = [
  "emptyHandle",
  "handleTooLong",
  "reservedHandle",
  "duplicateHandle",
  "tooManyClasses",
  "tooFewClasses",
  "badDefaultClass",
  "invalidParamName",
  "duplicateParamName",
  "enumNoOptions",
  "tooManyParams",
  "noParameters",
  "noSources",
  "tooManySources",
] as const;
