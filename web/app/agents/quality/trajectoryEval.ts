// Pure helpers for trajectory evals in the quality console: the expected-trajectory editor and the
// expected-vs-actual diff. Kept free of React and of the api client so the parsing / validation that mirrors
// the server's rules, and the shaping of the stored report for display, are testable on their own.
//
// Wire contract (core/quality TrajectoryEvalController, behind app.quality.trajectory-eval.enabled):
//   GET  /api/agents/{id}/quality/trajectory-eval/config
//   GET|PUT .../datasets/{datasetId}/cases/{caseId}        -> { caseId, trajectory, hasOtherGrader, datasetContentHash }
//   GET  .../datasets/{datasetId}/runs/{runId}             -> { runId, cases: [{ resultId, ..., report }] }
//   GET  .../datasets/{datasetId}/runs/{runId}/results/{resultId}/actual -> { steps, suggestedSpec }
//
// The expected trajectory is typed in the editor as ONE LINE PER STEP: a tool name, optionally followed by a JSON
// object of argument matchers, e.g.
//   search_knowledge_base {"query":{"regex":"refund"},"limit":5}
//   send_email
// (a matcher is a literal, {"regex":..}, {"present":true}, {"absent":true} or {"equals":..}).
//
// tsconfig is not strict: results are flat objects with a nullable `error`, never boolean-discriminated unions.

export type TrajectoryMode = "exact" | "in_order" | "any_order" | "f1";

export const TRAJECTORY_MODES: TrajectoryMode[] = ["exact", "in_order", "any_order", "f1"];

export interface TrajectoryConfig {
  enabled: boolean;
  modes: string[];
  maxSteps: number;
  maxArgsPerStep: number;
  maxActualSteps: number;
}

export interface TrajectoryStepSpec {
  tool: string;
  args?: Record<string, unknown>;
}

export interface TrajectorySpecWire {
  type: "trajectory";
  mode: TrajectoryMode;
  minScore: number;
  maxExtraCalls?: number;
  minEfficiency?: number;
  steps: TrajectoryStepSpec[];
}

function num(value: unknown): number | null {
  if (typeof value === "number" && Number.isFinite(value)) return value;
  return null;
}

/** Defensive read of the config body; null when it is not a config at all (the editor then hides). */
export function normalizeTrajectoryConfig(raw: unknown): TrajectoryConfig | null {
  if (!raw || typeof raw !== "object") return null;
  const r = raw as Record<string, unknown>;
  if (typeof r.enabled !== "boolean") return null;
  const modes: string[] = Array.isArray(r.modes) ? r.modes.filter((m): m is string => typeof m === "string") : [];
  return {
    enabled: r.enabled,
    modes: modes.length > 0 ? modes : TRAJECTORY_MODES.slice(),
    maxSteps: num(r.maxSteps) ?? 50,
    maxArgsPerStep: num(r.maxArgsPerStep) ?? 20,
    maxActualSteps: num(r.maxActualSteps) ?? 200,
  };
}

// ---------------------------------------------------------------------------
// Editor: steps text <-> steps
// ---------------------------------------------------------------------------

export type StepsError = "toolName" | "argsJson" | "argsObject" | "tooManySteps" | "tooManyArgs" | "argKey";

export interface StepsParseResult {
  steps: TrajectoryStepSpec[];
  /** Null when the text parsed. */
  error: StepsError | null;
  /** 1-based line the error is on (0 when there is no error). */
  line: number;
}

const TOOL_NAME = /^[A-Za-z0-9_.:-]{1,128}$/;
const ARG_PATH = /^[A-Za-z0-9_$-]+(\.[A-Za-z0-9_$-]+){0,7}$/;

/**
 * One step per non-blank line: `tool` or `tool {json object of argument matchers}`. Blank lines and lines
 * starting with `#` are ignored. Mirrors the server's checks (tool-name grammar, argument-path grammar, caps) so a
 * typo is caught before the PUT; the server stays the authority (it also compiles regexes).
 */
export function parseStepsText(text: string, maxSteps: number, maxArgsPerStep: number): StepsParseResult {
  const steps: TrajectoryStepSpec[] = [];
  const lines = (text || "").split(/\r?\n/);
  for (let i = 0; i < lines.length; i++) {
    const line = lines[i].trim();
    if (line === "" || line.startsWith("#")) continue;
    if (steps.length >= maxSteps) return { steps, error: "tooManySteps", line: i + 1 };
    const space = line.search(/\s/);
    const tool = space < 0 ? line : line.slice(0, space);
    const rest = space < 0 ? "" : line.slice(space).trim();
    if (!TOOL_NAME.test(tool)) return { steps, error: "toolName", line: i + 1 };
    if (rest === "") {
      steps.push({ tool });
      continue;
    }
    let parsed: unknown;
    try {
      parsed = JSON.parse(rest);
    } catch {
      return { steps, error: "argsJson", line: i + 1 };
    }
    if (!parsed || typeof parsed !== "object" || Array.isArray(parsed)) {
      return { steps, error: "argsObject", line: i + 1 };
    }
    const args = parsed as Record<string, unknown>;
    const keys = Object.keys(args);
    if (keys.length > maxArgsPerStep) return { steps, error: "tooManyArgs", line: i + 1 };
    for (const key of keys) {
      if (!ARG_PATH.test(key)) return { steps, error: "argKey", line: i + 1 };
    }
    steps.push(keys.length === 0 ? { tool } : { tool, args });
  }
  return { steps, error: null, line: 0 };
}

/** The inverse of {@link parseStepsText}: one line per step. */
export function stepsToText(steps: TrajectoryStepSpec[]): string {
  return (steps || [])
    .map((s) => {
      const hasArgs = s.args && Object.keys(s.args).length > 0;
      return hasArgs ? `${s.tool} ${JSON.stringify(s.args)}` : s.tool;
    })
    .join("\n");
}

// ---------------------------------------------------------------------------
// Editor: form <-> spec
// ---------------------------------------------------------------------------

export interface EditorForm {
  mode: TrajectoryMode;
  minScore: string;
  maxExtraCalls: string;
  minEfficiency: string;
  stepsText: string;
}

export const EMPTY_FORM: EditorForm = {
  mode: "in_order",
  minScore: "1",
  maxExtraCalls: "",
  minEfficiency: "",
  stepsText: "",
};

function isMode(value: unknown): value is TrajectoryMode {
  return typeof value === "string" && (TRAJECTORY_MODES as string[]).includes(value);
}

/** Form values for a stored spec (null / unreadable gives the empty form). */
export function formFromSpec(spec: unknown): EditorForm {
  if (!spec || typeof spec !== "object") return { ...EMPTY_FORM };
  const s = spec as Record<string, unknown>;
  const steps: TrajectoryStepSpec[] = [];
  if (Array.isArray(s.steps)) {
    for (const raw of s.steps) {
      if (!raw || typeof raw !== "object") continue;
      const r = raw as Record<string, unknown>;
      if (typeof r.tool !== "string") continue;
      const args = r.args && typeof r.args === "object" && !Array.isArray(r.args) ? (r.args as Record<string, unknown>) : undefined;
      steps.push(args ? { tool: r.tool, args } : { tool: r.tool });
    }
  }
  return {
    mode: isMode(s.mode) ? s.mode : "in_order",
    minScore: num(s.minScore) != null ? String(num(s.minScore)) : "1",
    maxExtraCalls: num(s.maxExtraCalls) != null ? String(num(s.maxExtraCalls)) : "",
    minEfficiency: num(s.minEfficiency) != null ? String(num(s.minEfficiency)) : "",
    stepsText: stepsToText(steps),
  };
}

export type FormError = StepsError | "minScore" | "maxExtraCalls" | "minEfficiency";

export interface BuildSpecResult {
  spec: TrajectorySpecWire | null;
  error: FormError | null;
  /** Line of a steps error (0 otherwise). */
  line: number;
}

function parseUnit(text: string): number | null {
  const t = text.trim();
  if (t === "" || !/^\d*\.?\d+$/.test(t)) return null;
  const n = Number(t);
  return Number.isFinite(n) && n >= 0 && n <= 1 ? n : null;
}

/**
 * Validates the form and builds the spec the PUT sends. `minScore` is required (0..1); `maxExtraCalls` (whole
 * number 0..1000) and `minEfficiency` (0..1) are optional and omitted when blank.
 */
export function buildSpec(form: EditorForm, config?: { maxSteps: number; maxArgsPerStep: number } | null): BuildSpecResult {
  const minScore = parseUnit(form.minScore);
  if (minScore == null) return { spec: null, error: "minScore", line: 0 };
  let maxExtraCalls: number | undefined;
  if (form.maxExtraCalls.trim() !== "") {
    const t = form.maxExtraCalls.trim();
    const n = /^\d{1,4}$/.test(t) ? Number(t) : NaN;
    if (!Number.isInteger(n) || n < 0 || n > 1000) return { spec: null, error: "maxExtraCalls", line: 0 };
    maxExtraCalls = n;
  }
  let minEfficiency: number | undefined;
  if (form.minEfficiency.trim() !== "") {
    const n = parseUnit(form.minEfficiency);
    if (n == null) return { spec: null, error: "minEfficiency", line: 0 };
    minEfficiency = n;
  }
  const parsed = parseStepsText(form.stepsText, config ? config.maxSteps : 50, config ? config.maxArgsPerStep : 20);
  if (parsed.error) return { spec: null, error: parsed.error, line: parsed.line };
  const spec: TrajectorySpecWire = { type: "trajectory", mode: form.mode, minScore, steps: parsed.steps };
  if (maxExtraCalls !== undefined) spec.maxExtraCalls = maxExtraCalls;
  if (minEfficiency !== undefined) spec.minEfficiency = minEfficiency;
  return { spec, error: null, line: 0 };
}

/** True when the form differs from the stored spec (so Save is meaningful). */
export function isFormDirty(form: EditorForm, stored: unknown): boolean {
  const base = formFromSpec(stored);
  return (
    form.mode !== base.mode ||
    form.minScore.trim() !== base.minScore ||
    form.maxExtraCalls.trim() !== base.maxExtraCalls ||
    form.minEfficiency.trim() !== base.minEfficiency ||
    form.stepsText.trim() !== base.stepsText.trim()
  );
}

export interface ActualStepWire {
  tool: string;
  args: unknown;
  ok: boolean;
}

/** Steps text made of a run's actual tool names (no matchers), the starting point for an expectation. */
export function stepsTextFromActual(actual: ActualStepWire[], maxSteps = 50): string {
  const steps: TrajectoryStepSpec[] = [];
  for (const a of actual || []) {
    if (steps.length >= maxSteps) break;
    if (a && typeof a.tool === "string" && TOOL_NAME.test(a.tool)) steps.push({ tool: a.tool });
  }
  return stepsToText(steps);
}

// ---------------------------------------------------------------------------
// The stored report -> display
// ---------------------------------------------------------------------------

export interface ReportExpectedRow {
  index: number;
  tool: string;
  status: string;
  actualIndex: number | null;
  detail: string | null;
}

export interface ReportActualRow {
  index: number;
  tool: string;
  ok: boolean;
  status: string;
  expectedIndex: number | null;
  args: string | null;
}

export interface ReportMetrics {
  exactMatch: boolean;
  inOrderMatch: boolean;
  anyOrderMatch: boolean;
  toolPrecision: number;
  toolRecall: number;
  toolF1: number;
  argMatchRate: number | null;
  extraCalls: number;
  extraCallPenalty: number;
  efficiency: number;
  failedCalls: number;
  expectedSteps: number;
  actualSteps: number;
}

export interface TrajectoryReportView {
  mode: string;
  score: number;
  minScore: number;
  passed: boolean;
  metrics: ReportMetrics;
  expected: ReportExpectedRow[];
  actual: ReportActualRow[];
  actualTruncated: boolean;
}

function str(value: unknown): string | null {
  return typeof value === "string" ? value : null;
}

/** Defensive read of a stored report; null when it is not one (the diff then shows nothing). */
export function normalizeReport(raw: unknown): TrajectoryReportView | null {
  if (!raw || typeof raw !== "object") return null;
  const r = raw as Record<string, unknown>;
  const m = r.metrics && typeof r.metrics === "object" ? (r.metrics as Record<string, unknown>) : null;
  if (!m || !Array.isArray(r.expected) || !Array.isArray(r.actual)) return null;
  const expected: ReportExpectedRow[] = [];
  for (const e of r.expected as unknown[]) {
    const row = e as Record<string, unknown>;
    if (!row || typeof row.tool !== "string") continue;
    expected.push({
      index: num(row.index) ?? expected.length,
      tool: row.tool,
      status: str(row.status) ?? "missing",
      actualIndex: num(row.actualIndex),
      detail: str(row.detail),
    });
  }
  const actual: ReportActualRow[] = [];
  for (const a of r.actual as unknown[]) {
    const row = a as Record<string, unknown>;
    if (!row || typeof row.tool !== "string") continue;
    actual.push({
      index: num(row.index) ?? actual.length,
      tool: row.tool,
      ok: row.ok !== false,
      status: str(row.status) ?? "extra",
      expectedIndex: num(row.expectedIndex),
      args: str(row.args),
    });
  }
  return {
    mode: str(r.mode) ?? "in_order",
    score: num(r.score) ?? 0,
    minScore: num(r.minScore) ?? 1,
    passed: r.passed === true,
    metrics: {
      exactMatch: m.exactMatch === true,
      inOrderMatch: m.inOrderMatch === true,
      anyOrderMatch: m.anyOrderMatch === true,
      toolPrecision: num(m.toolPrecision) ?? 0,
      toolRecall: num(m.toolRecall) ?? 0,
      toolF1: num(m.toolF1) ?? 0,
      argMatchRate: num(m.argMatchRate),
      extraCalls: num(m.extraCalls) ?? 0,
      extraCallPenalty: num(m.extraCallPenalty) ?? 0,
      efficiency: num(m.efficiency) ?? 0,
      failedCalls: num(m.failedCalls) ?? 0,
      expectedSteps: num(m.expectedSteps) ?? expected.length,
      actualSteps: num(m.actualSteps) ?? actual.length,
    },
    expected,
    actual,
    actualTruncated: r.actualTruncated === true,
  };
}

export type Tone = "success" | "warning" | "error" | "idle";

export interface StatusTone {
  tone: Tone;
  /** The state custom property to colour the row's edge and badge with (no hex values in components). */
  cssVar: string;
}

/**
 * Highlight for a row status: matched = success, found-but-out-of-order or right-tool-wrong-args = warning,
 * missing = error, an extra call = idle (informational unless the spec caps it).
 */
export function statusTone(status: string): StatusTone {
  switch (status) {
    case "match":
      return { tone: "success", cssVar: "var(--state-success)" };
    case "out_of_order":
    case "args_mismatch":
      return { tone: "warning", cssVar: "var(--state-warning)" };
    case "missing":
      return { tone: "error", cssVar: "var(--state-error)" };
    default:
      return { tone: "idle", cssVar: "var(--state-idle)" };
  }
}

/** Whole-number percent text, or an em dash for a missing value. */
export function pctText(value: number | null | undefined): string {
  if (value == null || !Number.isFinite(value)) return "—";
  return `${Math.round(value * 100)}%`;
}

export type MetricKind = "bool" | "pct" | "count";

export interface MetricEntry {
  key: string;
  kind: MetricKind;
  value: boolean | number | null;
}

/** The metrics in display order, each tagged with how to format it. */
export function metricEntries(m: ReportMetrics): MetricEntry[] {
  return [
    { key: "exactMatch", kind: "bool", value: m.exactMatch },
    { key: "inOrderMatch", kind: "bool", value: m.inOrderMatch },
    { key: "anyOrderMatch", kind: "bool", value: m.anyOrderMatch },
    { key: "toolPrecision", kind: "pct", value: m.toolPrecision },
    { key: "toolRecall", kind: "pct", value: m.toolRecall },
    { key: "toolF1", kind: "pct", value: m.toolF1 },
    { key: "argMatchRate", kind: "pct", value: m.argMatchRate },
    { key: "extraCalls", kind: "count", value: m.extraCalls },
    { key: "extraCallPenalty", kind: "pct", value: m.extraCallPenalty },
    { key: "efficiency", kind: "pct", value: m.efficiency },
    { key: "failedCalls", kind: "count", value: m.failedCalls },
  ];
}
