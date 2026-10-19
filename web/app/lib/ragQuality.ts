// Pure helpers behind the "Retrieval quality" panel (RagQualityPanel.tsx): parsing a pasted eval
// dataset, building the POST /api/knowledge/{id}/eval body, and reading the A/B result honestly.
// Kept free of React and I/O so the logic is unit-tested directly (ragQuality.test.ts).

export const MAX_EVAL_QUESTIONS = 200;
export const MAX_QUESTION_CHARS = 1000;

export const EVAL_PRESETS = [
  "baseline",
  "fusion",
  "parent-child",
  "fusion+parent-child",
  "rewrite-multi",
  "rewrite-hyde",
] as const;
export type EvalPreset = (typeof EVAL_PRESETS)[number];

/** i18n key (inside the ragQuality namespace) of each preset's label. */
const PRESET_LABEL_KEYS: Record<EvalPreset, string> = {
  baseline: "presetBaseline",
  fusion: "presetFusion",
  "parent-child": "presetParentChild",
  "fusion+parent-child": "presetFusionParentChild",
  "rewrite-multi": "presetRewriteMulti",
  "rewrite-hyde": "presetRewriteHyde",
};

/** i18n key (inside the ragQuality namespace) of each preset's one-line explanation. */
const PRESET_HINT_KEYS: Record<EvalPreset, string> = {
  baseline: "presetBaselineHint",
  fusion: "presetFusionHint",
  "parent-child": "presetParentChildHint",
  "fusion+parent-child": "presetFusionParentChildHint",
  "rewrite-multi": "presetRewriteMultiHint",
  "rewrite-hyde": "presetRewriteHydeHint",
};

export function presetLabelKey(preset: string): string {
  return PRESET_LABEL_KEYS[preset as EvalPreset] || "presetUnknown";
}

export function presetHintKey(preset: string): string {
  return PRESET_HINT_KEYS[preset as EvalPreset] || "presetUnknown";
}

export interface RagEvalQuestion {
  question: string;
  expectedDocumentIds?: string[];
  expectedSubstrings?: string[];
}

export type DatasetErrorCode = "invalidJson" | "notAnArray" | "emptyQuestion" | "tooLong" | "missingExpected" | "tooMany";

export interface DatasetError {
  /** 1-based line (line format) or 1-based entry number (JSON). 0 = the dataset as a whole. */
  line: number;
  code: DatasetErrorCode;
}

export interface ParsedDataset {
  questions: RagEvalQuestion[];
  errors: DatasetError[];
}

const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

/** Splits expected targets into document ids (UUID-shaped) and substrings (everything else). */
function splitTargets(parts: string[]): { ids: string[]; substrings: string[] } {
  const ids: string[] = [];
  const substrings: string[] = [];
  for (const raw of parts) {
    const part = raw.trim();
    if (!part) continue;
    if (UUID_RE.test(part)) ids.push(part.toLowerCase());
    else substrings.push(part);
  }
  return { ids, substrings };
}

function toQuestion(question: string, ids: string[], substrings: string[]): RagEvalQuestion {
  const q: RagEvalQuestion = { question };
  if (ids.length > 0) q.expectedDocumentIds = ids;
  if (substrings.length > 0) q.expectedSubstrings = substrings;
  return q;
}

/**
 * Parses a pasted dataset. Two formats:
 *  - JSON: an array of {question, expectedSubstrings?: string[], expectedDocumentIds?: string[]};
 *  - lines: `question || expected text ;; another expected text || ...` where an expected part shaped
 *    like a UUID is a document id and anything else is a substring the retrieved text must contain.
 *    Blank lines and lines starting with # are ignored.
 * Invalid entries are reported (never silently dropped) and excluded from `questions`.
 */
export function parseEvalDataset(text: string): ParsedDataset {
  const questions: RagEvalQuestion[] = [];
  const errors: DatasetError[] = [];
  const source = (text || "").trim();
  if (!source) return { questions, errors };

  if (source.startsWith("[")) {
    let parsed: unknown;
    try {
      parsed = JSON.parse(source);
    } catch {
      return { questions, errors: [{ line: 0, code: "invalidJson" }] };
    }
    if (!Array.isArray(parsed)) return { questions, errors: [{ line: 0, code: "notAnArray" }] };
    parsed.forEach((entry: any, index: number) => {
      const n = index + 1;
      const question = typeof entry?.question === "string" ? entry.question.trim() : "";
      if (!question) {
        errors.push({ line: n, code: "emptyQuestion" });
        return;
      }
      if (question.length > MAX_QUESTION_CHARS) {
        errors.push({ line: n, code: "tooLong" });
        return;
      }
      const rawIds: string[] = Array.isArray(entry.expectedDocumentIds) ? entry.expectedDocumentIds.map(String) : [];
      const rawSubs: string[] = Array.isArray(entry.expectedSubstrings) ? entry.expectedSubstrings.map(String) : [];
      const fromIds = splitTargets(rawIds);
      const fromSubs = splitTargets(rawSubs);
      const ids = [...fromIds.ids, ...fromSubs.ids];
      const substrings = [...fromIds.substrings, ...fromSubs.substrings];
      if (ids.length === 0 && substrings.length === 0) {
        errors.push({ line: n, code: "missingExpected" });
        return;
      }
      questions.push(toQuestion(question, ids, substrings));
    });
  } else {
    source.split(/\r?\n/).forEach((rawLine, index) => {
      const line = rawLine.trim();
      if (!line || line.startsWith("#")) return;
      const n = index + 1;
      const [questionPart, ...rest] = line.split("||");
      const question = questionPart.trim();
      if (!question) {
        errors.push({ line: n, code: "emptyQuestion" });
        return;
      }
      if (question.length > MAX_QUESTION_CHARS) {
        errors.push({ line: n, code: "tooLong" });
        return;
      }
      const parts = rest.join("||").split(";;");
      const { ids, substrings } = splitTargets(parts);
      if (ids.length === 0 && substrings.length === 0) {
        errors.push({ line: n, code: "missingExpected" });
        return;
      }
      questions.push(toQuestion(question, ids, substrings));
    });
  }

  if (questions.length > MAX_EVAL_QUESTIONS) {
    return { questions: questions.slice(0, MAX_EVAL_QUESTIONS), errors: [...errors, { line: 0, code: "tooMany" }] };
  }
  return { questions, errors };
}

export interface RagEvalRequestBody {
  questions: RagEvalQuestion[];
  configs: { name: string; preset: string }[];
  k: number;
}

/** The request body for the chosen presets, in canonical order and without duplicates; baseline is always included. */
export function buildEvalRequest(questions: RagEvalQuestion[], presets: string[], k: number): RagEvalRequestBody {
  const wanted = new Set<string>(["baseline", ...presets]);
  const ordered = EVAL_PRESETS.filter((p) => wanted.has(p));
  return { questions, configs: ordered.map((p) => ({ name: p, preset: p })), k };
}

export interface Interval {
  low: number;
  high: number;
}

export interface RagEvalConfigResult {
  name: string;
  preset: string;
  questions: number;
  k: number;
  hitRate: number;
  hitRateCi: Interval;
  recallAtK: number;
  mrr: number;
  avgReturnedChars: number;
  avgLatencyMs: number;
  failedQuestions: number;
}

export interface RagEvalResponse {
  collectionId: string;
  k: number;
  questions: number;
  childChunksInCollection: number;
  parentChunksInCollection: number;
  truncated: boolean;
  notes: string[];
  results: RagEvalConfigResult[];
}

export interface RagSettings {
  parentChildAvailable: boolean;
  parentChildEnabled: boolean;
  childTokens: number;
  parentTokens: number;
  parentChunkCount: number;
  fusionEnabled: boolean;
  queryRewriteMode: string;
  schemaReady: boolean;
}

export function intervalsOverlap(a: Interval, b: Interval): boolean {
  return a.low <= b.high && b.low <= a.high;
}

export type Verdict = "baseline" | "better" | "worse" | "noise";

/**
 * Reads a config against the baseline on hit-rate. Only non-overlapping 95% Wilson intervals count as a
 * difference; anything else is reported as noise, because at the dataset sizes a collection owner can
 * write by hand a gap of several points is routinely within chance.
 */
export function verdictAgainstBaseline(row: RagEvalConfigResult, baseline: RagEvalConfigResult | undefined): Verdict {
  if (!baseline || row.preset === baseline.preset) return "baseline";
  if (row.hitRateCi.low > baseline.hitRateCi.high) return "better";
  if (row.hitRateCi.high < baseline.hitRateCi.low) return "worse";
  return "noise";
}

export function formatPct(x: number): string {
  if (!Number.isFinite(x)) return "-";
  return `${Math.round(x * 100)}%`;
}

export function formatCi(ci: Interval): string {
  if (!ci) return "-";
  return `${Math.round(ci.low * 100)}-${Math.round(ci.high * 100)}%`;
}

export function formatMrr(x: number): string {
  return Number.isFinite(x) ? x.toFixed(2) : "-";
}

export function formatMs(x: number): string {
  if (!Number.isFinite(x)) return "-";
  return x >= 1000 ? `${(x / 1000).toFixed(1)} s` : `${Math.round(x)} ms`;
}

export function formatChars(x: number): string {
  return Number.isFinite(x) ? String(Math.round(x)) : "-";
}
