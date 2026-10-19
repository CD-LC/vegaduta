/**
 * Pure helpers for the judge-calibration panel (core/quality's JudgeCalibrationSetController): the CSV/TSV
 * row parser, the response types, and the kappa interpretation used by the badge. No React, no fetch, so every
 * branch is unit-testable.
 */

export interface CalibrationRowInput {
  input: string;
  outputA: string;
  outputB: string;
  humanLabel: "A" | "B";
  rationale?: string;
}

export interface ParsedRows {
  rows: CalibrationRowInput[];
  /** Human-readable problems, one per rejected line ("Line 4: ..."). Empty when everything parsed. */
  errors: string[];
}

/**
 * Splits pasted text into records of fields. Handles a tab- or comma-separated table (the delimiter is the one
 * that appears more often in the first non-empty line), double-quoted fields with embedded delimiters, newlines
 * and "" escapes. Blank lines are skipped. Returns the 1-based starting line of each record for error messages.
 */
export function splitRecords(text: string): { fields: string[]; line: number }[] {
  const src = text.replace(/\r\n/g, "\n").replace(/\r/g, "\n");
  const firstLine = src.split("\n").find((l) => l.trim().length > 0) ?? "";
  const tabs = (firstLine.match(/\t/g) ?? []).length;
  const commas = (firstLine.match(/,/g) ?? []).length;
  const delimiter = tabs > 0 && tabs >= commas ? "\t" : ",";

  const records: { fields: string[]; line: number }[] = [];
  let fields: string[] = [];
  let field = "";
  let inQuotes = false;
  let line = 1;
  let recordLine = 1;
  let sawContent = false;

  const endField = () => {
    fields.push(field);
    field = "";
  };
  const endRecord = () => {
    endField();
    if (sawContent && !(fields.length === 1 && fields[0].trim() === "")) {
      records.push({ fields, line: recordLine });
    }
    fields = [];
    sawContent = false;
  };

  for (let i = 0; i < src.length; i++) {
    const ch = src[i];
    if (inQuotes) {
      if (ch === '"') {
        if (src[i + 1] === '"') {
          field += '"';
          i++;
        } else {
          inQuotes = false;
        }
      } else {
        if (ch === "\n") line++;
        field += ch;
      }
      continue;
    }
    if (ch === '"' && field === "") {
      inQuotes = true;
      sawContent = true;
    } else if (ch === delimiter) {
      sawContent = true;
      endField();
    } else if (ch === "\n") {
      endRecord();
      line++;
      recordLine = line;
    } else {
      if (ch.trim() !== "") sawContent = true;
      field += ch;
    }
  }
  endRecord();
  return records;
}

const HEADER_WORDS = new Set(["input", "prompt", "question", "task"]);

/**
 * Parses pasted rows: input, outputA, outputB, humanLabel (A or B), optional rationale. A first line whose
 * first cell is input/prompt/question/task is treated as a header and skipped. Lines that cannot be used are
 * reported in `errors` and left out of `rows`; the caller decides whether to proceed.
 */
export function parseCalibrationRows(text: string): ParsedRows {
  const rows: CalibrationRowInput[] = [];
  const errors: string[] = [];
  const records = splitRecords(text);
  records.forEach((rec, index) => {
    const f = rec.fields.map((c) => c.trim());
    if (index === 0 && HEADER_WORDS.has(f[0]?.toLowerCase() ?? "")) return;
    if (f.length < 4) {
      errors.push(`Line ${rec.line}: expected input, outputA, outputB, humanLabel (got ${f.length} column${f.length === 1 ? "" : "s"}).`);
      return;
    }
    const [input, outputA, outputB, label, ...rest] = f;
    const humanLabel = label.toUpperCase();
    if (!input || !outputA || !outputB) {
      errors.push(`Line ${rec.line}: input, outputA and outputB must not be empty.`);
      return;
    }
    if (humanLabel !== "A" && humanLabel !== "B") {
      errors.push(`Line ${rec.line}: humanLabel must be A or B (got "${label}").`);
      return;
    }
    // An unquoted rationale containing the delimiter spills into extra columns; stitch it back together.
    const rationale = rest.join(", ").trim();
    rows.push({ input, outputA, outputB, humanLabel, ...(rationale ? { rationale } : {}) });
  });
  return { rows, errors };
}

// ---------------------------------------------------------------------------
// Response shapes (mirror JudgeCalibrationSetController / JudgeCalibrationService)
// ---------------------------------------------------------------------------

export interface CalibrationSetSummary {
  id: string;
  agentId: string;
  name: string;
  itemCount: number;
  createdBy: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface CalibrationResultRow {
  id: string;
  setId: string;
  judgeModel: string | null;
  agentModel: string | null;
  n: number;
  usable: number;
  consistent: number;
  kappa: number | null;
  orderSwapConsistency: number | null;
  rawAgreement: number | null;
  interpretation: string | null;
  selfPreferenceWarning: string | null;
  createdAt: string;
}

export interface CalibrationRunResponse {
  judgeModel: string | null;
  agentModel: string | null;
  selfPreferenceWarning: string | null;
  method: string;
  calibrationSetId?: string;
  resultId?: string;
  recordedAt?: string;
  report: {
    examples: number;
    usable: number;
    consistent: number;
    orderSwapConsistency: number | null;
    cohenKappa: number | null;
    rawAgreement: number | null;
    interpretation: string;
    warnings: string[];
  };
}

/** Mirrors core's EvalDatasetDtos.JudgeCalibrationInfo; absent unless calibration is enabled. */
export interface JudgeCalibrationInfo {
  status: "UNCALIBRATED" | "LOW" | "OK";
  kappa: number | null;
  n: number | null;
}

/** "0.71" for a number, an em dash for undefined (null is not zero). */
export function formatKappa(kappa: number | null | undefined): string {
  return kappa == null || Number.isNaN(kappa) ? "—" : kappa.toFixed(2);
}

export function formatRatio(value: number | null | undefined): string {
  return value == null || Number.isNaN(value) ? "—" : `${Math.round(value * 100)}%`;
}
