// Pure data + helpers for the eight data nodes (set, filter, sort, aggregate, limit, dedupe, switch, merge) -
// the n8n node-pack parity set. No React and no icon imports, so everything here is unit-testable (see
// dataNodes.test.ts) and nodeDefs.ts / WorkflowNode.tsx / the config drawer all read ONE definition.
//
// Mirrors core's WorkflowDataOps / WorkflowDataNodeValidator: the operator, aggregate-operation, merge-mode and
// set-type lists below are the exact vocabularies the server accepts, and `dataNodeMissing` is the client twin of
// the save-time checks (so the canvas can nudge before Validate does).

export const DATA_NODE_TYPES = ["set", "filter", "sort", "aggregate", "limit", "dedupe", "switch", "merge"] as const;
export type DataNodeType = (typeof DATA_NODE_TYPES)[number];

export function isDataNodeType(type: string | undefined | null): type is DataNodeType {
  return !!type && (DATA_NODE_TYPES as readonly string[]).includes(type);
}

/** Palette category the data nodes live under (label key "categoryData" in the workflowNodesData namespace). */
export const DATA_CATEGORY = "Data";

/** The fixed Switch port that receives the run when no case matches (core: WorkflowDataOps.SWITCH_DEFAULT_HANDLE). */
export const SWITCH_DEFAULT_HANDLE = "default";
/** Handles a Switch case may not use: its fixed default port and every node's shared failure port. */
export const SWITCH_RESERVED_HANDLES: readonly string[] = [SWITCH_DEFAULT_HANDLE, "error"];

export const CONDITION_OPERATORS = [
  "eq", "neq", "gt", "lt", "gte", "lte", "contains", "starts", "ends", "empty", "notEmpty", "regex",
] as const;
/** Operators that ignore their value box. */
export const OPERATORS_WITHOUT_VALUE: readonly string[] = ["empty", "notEmpty"];
export const COMBINATORS = ["and", "or"] as const;
export const SET_VALUE_TYPES = ["auto", "string", "number", "boolean", "json", "null"] as const;
export const SORT_TYPES = ["auto", "string", "number", "date"] as const;
export const SORT_DIRECTIONS = ["asc", "desc"] as const;
export const AGGREGATE_OPERATIONS = ["count", "sum", "avg", "min", "max", "first", "last", "concat", "collect"] as const;
export const MERGE_MODES = ["append", "join", "first_non_empty"] as const;
export const JOIN_TYPES = ["left", "inner", "outer"] as const;
export const KEEP_CHOICES = ["first", "last"] as const;
/** Server cap on a regex pattern (WorkflowDataOps.MAX_REGEX_PATTERN_CHARS). */
export const MAX_REGEX_PATTERN_CHARS = 200;

export type DataCondition = { field: string; operator: string; value: string };
export type SwitchCase = { name: string; combinator?: string; conditions: DataCondition[] };
export type SetAssignment = { name: string; value: string; type: string };
export type SortKey = { field: string; direction: string; type: string };
export type AggregateSpec = { operation: string; field: string; as: string; separator?: string };

/** The config keys the eight data nodes use (all optional: a node only carries its own type's keys). */
export type DataNodeConfig = {
  // set
  assignments?: SetAssignment[];
  keepOnlySet?: boolean;
  // filter + switch (cases[].conditions has the same shape)
  conditions?: DataCondition[];
  combinator?: string;
  ignoreCase?: boolean;
  // sort
  sortKeys?: SortKey[];
  caseSensitive?: boolean;
  // aggregate
  aggregations?: AggregateSpec[];
  groupBy?: string;
  // limit (count may be an expression, hence string | number)
  count?: number | string;
  // dedupe (not "fields": human_input already uses that key for a different shape)
  dedupeBy?: string;
  keep?: string;
  // switch
  cases?: SwitchCase[];
  // merge
  key?: string;
  joinType?: string;
  sources?: string[];
  minInputs?: number | string;
};

/** What the helpers read: the data keys plus the two shared with other node types. */
export type DataConfigLike = DataNodeConfig & { mode?: string; itemsField?: string };

type Translator = (key: string, values?: Record<string, string | number>) => string;

// ---------------------------------------------------------------------------
// Row factories + list helpers (the drawer edits plain arrays; nothing is re-keyed)
// ---------------------------------------------------------------------------

export function newCondition(): DataCondition {
  return { field: "", operator: "eq", value: "" };
}

export function newAssignment(): SetAssignment {
  return { name: "", value: "", type: "auto" };
}

export function newSortKey(): SortKey {
  return { field: "", direction: "asc", type: "auto" };
}

export function newAggregate(): AggregateSpec {
  return { operation: "count", field: "", as: "" };
}

/** A fresh case named "case-N" with the smallest N not already taken (case-insensitively, reserved names included). */
export function newCase(existing: readonly SwitchCase[] | undefined): SwitchCase {
  const taken = new Set([...(existing || []).map((c) => (c?.name || "").trim().toLowerCase()), ...SWITCH_RESERVED_HANDLES]);
  let n = (existing || []).length + 1;
  while (taken.has(`case-${n}`)) n += 1;
  return { name: `case-${n}`, combinator: "and", conditions: [newCondition()] };
}

export function patchAt<T>(list: readonly T[] | undefined, index: number, patch: Partial<T>): T[] {
  return (list || []).map((item, i) => (i === index ? { ...item, ...patch } : item));
}

export function removeAt<T>(list: readonly T[] | undefined, index: number): T[] {
  return (list || []).filter((_, i) => i !== index);
}

/** Whether an operator ignores its value box (so the form can hide it). */
export function operatorTakesValue(operator: string | undefined): boolean {
  return !OPERATORS_WITHOUT_VALUE.includes(operator || "eq");
}

/** Comma separated paths -> trimmed, blank-free list (what core's stringList does with a string). */
export function splitFieldList(raw: string | undefined): string[] {
  return (raw || "")
    .split(",")
    .map((s) => s.trim())
    .filter(Boolean);
}

// ---------------------------------------------------------------------------
// Switch ports
// ---------------------------------------------------------------------------

/** Usable case names in author order: trimmed, non-blank, not reserved, first spelling wins (case-insensitive). */
export function switchCaseNames(config: DataConfigLike | undefined): string[] {
  const seen = new Set<string>(SWITCH_RESERVED_HANDLES);
  const names: string[] = [];
  for (const c of config?.cases || []) {
    const name = (c?.name || "").trim();
    const key = name.toLowerCase();
    if (!name || seen.has(key)) continue;
    seen.add(key);
    names.push(name);
  }
  return names;
}

export type SwitchPort = { id: string; title: string };

/**
 * Output ports of a Switch node: one per usable case, then the fixed default. `id` is the Handle id, i.e. the
 * sourceHandle an edge drawn off it persists - core matches the chosen case name against it case-insensitively
 * (the same mechanism as condition true/false and supervisor route handles).
 */
export function switchPorts(config: DataConfigLike | undefined, t: Translator): SwitchPort[] {
  const ports: SwitchPort[] = switchCaseNames(config).map((name) => ({ id: name, title: name }));
  ports.push({ id: SWITCH_DEFAULT_HANDLE, title: t("switchDefaultPortTitle") });
  return ports;
}

/** The handle a freshly inserted Switch should expose as its primary output (see WorkflowCanvas.primarySourceHandle). */
export function switchPrimaryHandle(config: DataConfigLike | undefined): string {
  return switchCaseNames(config)[0] || SWITCH_DEFAULT_HANDLE;
}

// ---------------------------------------------------------------------------
// "What is missing" - the client twin of WorkflowDataNodeValidator (the cheap subset)
// ---------------------------------------------------------------------------

function hasExpression(s: unknown): boolean {
  return typeof s === "string" && s.includes("{{");
}

function conditionsProblem(conditions: readonly DataCondition[] | undefined): "noConditions" | "badOperator" | "badRegex" | null {
  if (!conditions || conditions.length === 0) return "noConditions";
  for (const c of conditions) {
    const op = c?.operator || "eq";
    if (!(CONDITION_OPERATORS as readonly string[]).includes(op)) return "badOperator";
    if (op === "regex" && !hasExpression(c.value)) {
      // Length only: the server compiles with java.util.regex, whose syntax (inline flags, possessive quantifiers)
      // differs from JavaScript's, so a client-side compile would flag valid patterns.
      if ((c.value || "").length > MAX_REGEX_PATTERN_CHARS) return "badRegex";
    }
  }
  return null;
}

export type DataNodeProblem =
  | "noFields" | "noConditions" | "badOperator" | "badRegex" | "noSortKey" | "noAggregation" | "noCount"
  | "noCases" | "caseNoName" | "caseDuplicate" | "caseReserved" | "caseNoConditions" | "noJoinKey";

/** The first thing the server's Validate would reject for this node, or null when it looks runnable. */
export function dataNodeMissing(type: string | undefined, config: DataConfigLike | undefined): DataNodeProblem | null {
  const c: DataConfigLike = config || {};
  switch (type) {
    case "set":
      return (c.assignments || []).some((a) => (a?.name || "").trim()) ? null : "noFields";
    case "filter":
      return conditionsProblem(c.conditions);
    case "sort":
      return (c.sortKeys || []).length > 0 ? null : "noSortKey";
    case "aggregate":
      return (c.aggregations || []).length > 0 ? null : "noAggregation";
    case "limit":
      return c.count === undefined || c.count === null || String(c.count).trim() === "" ? "noCount" : null;
    case "switch": {
      const cases = c.cases || [];
      if (cases.length === 0) return "noCases";
      const seen = new Set<string>();
      for (const sc of cases) {
        const name = (sc?.name || "").trim();
        if (!name) return "caseNoName";
        const key = name.toLowerCase();
        if (SWITCH_RESERVED_HANDLES.includes(key)) return "caseReserved";
        if (seen.has(key)) return "caseDuplicate";
        seen.add(key);
        const p = conditionsProblem(sc.conditions);
        if (p === "noConditions") return "caseNoConditions";
        if (p) return p;
      }
      return null;
    }
    case "merge":
      return (c.mode || "append") === "join" && !(c.key || "").trim() ? "noJoinKey" : null;
    default:
      return null;
  }
}

// ---------------------------------------------------------------------------
// Canvas card subtitle
// ---------------------------------------------------------------------------

function clip(s: string, max: number): string {
  return s.length <= max ? s : `${s.slice(0, max - 1)}…`;
}

/** One-line summary under the node title. Strings live in the "workflowNodesData" en.json namespace. */
export function summarizeDataNode(type: string, config: DataConfigLike | undefined, t: Translator): string {
  const c: DataConfigLike = config || {};
  const problem = dataNodeMissing(type, c);
  switch (type) {
    case "set": {
      if (problem) return t("summarySetNone");
      const count = (c.assignments || []).filter((a) => (a?.name || "").trim()).length;
      return t(c.keepOnlySet ? "summarySetOnly" : "summarySet", { count });
    }
    case "filter":
      return problem ? t("summaryFilterNone") : t("summaryFilter", { count: (c.conditions || []).length });
    case "sort": {
      const first = (c.sortKeys || [])[0];
      if (problem || !first) return t("summarySortNone");
      return t("summarySort", { field: clip((first.field || "").trim() || t("summaryWholeItem"), 24), direction: first.direction === "desc" ? "↓" : "↑" });
    }
    case "aggregate":
      return problem ? t("summaryAggregateNone") : t("summaryAggregate", { count: (c.aggregations || []).length });
    case "limit":
      return problem ? t("summaryLimitNone") : t(c.mode === "last" ? "summaryLimitLast" : "summaryLimitFirst", { count: String(c.count) });
    case "dedupe": {
      const fields = splitFieldList(c.dedupeBy);
      return fields.length ? t("summaryDedupeBy", { fields: clip(fields.join(", "), 28) }) : t("summaryDedupeWhole");
    }
    case "switch":
      return problem === "noCases" ? t("summarySwitchNone") : t("summarySwitch", { count: switchCaseNames(c).length });
    case "merge": {
      const mode = (MERGE_MODES as readonly string[]).includes(c.mode || "") ? (c.mode as string) : "append";
      if (problem === "noJoinKey") return t("summaryMergeNoKey");
      return mode === "join" ? t("summaryMergeJoin", { key: clip((c.key || "").trim(), 24) }) : t(`summaryMerge_${mode}`);
    }
    default:
      return "";
  }
}

// ---------------------------------------------------------------------------
// Palette strings: nodeLabelKey()/nodeDescriptionKey()/categoryLabelKey() for these types resolve from the
// "workflowNodesData" namespace (useNodeDefTranslations), keeping the whole feature in one en.json block.
// ---------------------------------------------------------------------------

const DATA_NODE_DEF_KEYS = new Set<string>([
  ...DATA_NODE_TYPES.flatMap((type) => [`${type}Label`, `${type}Description`]),
  `category${DATA_CATEGORY}`,
]);

export function isDataNodeDefKey(key: string): boolean {
  return DATA_NODE_DEF_KEYS.has(key);
}

// ---------------------------------------------------------------------------
// Form helpers
// ---------------------------------------------------------------------------

/** What is wrong with the name of case `index`, for an inline warning (blank is not flagged: the row is just new). */
export function caseNameProblem(cases: readonly SwitchCase[] | undefined, index: number): "reserved" | "duplicate" | null {
  const list = cases || [];
  const name = (list[index]?.name || "").trim().toLowerCase();
  if (!name) return null;
  if (SWITCH_RESERVED_HANDLES.includes(name)) return "reserved";
  return list.slice(0, index).some((c) => (c?.name || "").trim().toLowerCase() === name) ? "duplicate" : null;
}

/** The Limit count box: digits become a real number, anything else (an expression, a half-typed value) stays text. */
export function parseCountInput(raw: string): number | string {
  const t = raw.trim();
  return /^\d{1,9}$/.test(t) ? Number(t) : raw;
}
