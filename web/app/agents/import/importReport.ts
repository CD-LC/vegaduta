import type {
  ImportReport,
  ImportReportConverted,
  ImportReportNeedsManual,
  ImportReportUnsupported,
} from "../../lib/api/agentImport";

// Pure helpers for rendering the import report and classifying failures.
//
// The core contract says report `what`/`why`/`hint` are DISPLAY text only, not
// stable identifiers, so nothing here branches on their wording.

export type ReportSectionId = "converted" | "needsManual" | "unsupported";

export type ReportItem = {
  /** Stable within one report render: section id + index. */
  key: string;
  title: string;
  /** The explanation: `detail` for converted items, `why` otherwise. May be "". */
  body: string;
  /** What to do next; "" except for needs-attention items. */
  hint: string;
};

export type ReportSection = { id: ReportSectionId; items: ReportItem[] };

export type ReportCounts = { converted: number; needsManual: number; unsupported: number; warnings: number };

function str(value: unknown): string {
  return typeof value === "string" ? value : "";
}

function list<T>(value: unknown, map: (raw: any) => T): T[] {
  return Array.isArray(value) ? value.filter((v) => v && typeof v === "object").map(map) : [];
}

/** Defensive coercion: tolerate a missing list or odd entry from an older/newer core. */
export function normalizeReport(raw: unknown): ImportReport {
  const r: any = raw && typeof raw === "object" ? raw : {};
  return {
    converted: list<ImportReportConverted>(r.converted, (i) => ({ what: str(i.what), detail: str(i.detail) })),
    needsManual: list<ImportReportNeedsManual>(r.needsManual, (i) => ({
      what: str(i.what),
      why: str(i.why),
      hint: str(i.hint),
    })),
    unsupported: list<ImportReportUnsupported>(r.unsupported, (i) => ({ what: str(i.what), why: str(i.why) })),
    warnings: Array.isArray(r.warnings) ? r.warnings.filter((w: unknown) => typeof w === "string" && w) : [],
  };
}

export function reportCounts(report: ImportReport): ReportCounts {
  return {
    converted: report.converted.length,
    needsManual: report.needsManual.length,
    unsupported: report.unsupported.length,
    warnings: report.warnings.length,
  };
}

/** True when the person has something to do before the draft is usable. */
export function needsAttention(report: ImportReport): boolean {
  return report.needsManual.length > 0;
}

/**
 * Groups the report for display in the order Converted, Needs your attention,
 * Not supported. Empty sections are returned too (with no items) so the caller
 * decides whether to render an empty state; use `nonEmptySections` to skip them.
 */
export function groupReport(report: ImportReport): ReportSection[] {
  return [
    {
      id: "converted",
      items: report.converted.map((i, n) => ({ key: `converted-${n}`, title: i.what, body: i.detail, hint: "" })),
    },
    {
      id: "needsManual",
      items: report.needsManual.map((i, n) => ({ key: `needsManual-${n}`, title: i.what, body: i.why, hint: i.hint })),
    },
    {
      id: "unsupported",
      items: report.unsupported.map((i, n) => ({ key: `unsupported-${n}`, title: i.what, body: i.why, hint: "" })),
    },
  ];
}

export function nonEmptySections(sections: ReportSection[]): ReportSection[] {
  return sections.filter((s) => s.items.length > 0);
}

/** Shortens long text for the instruction preview without cutting a surrogate pair in half. */
export function truncateText(text: string, max: number): { text: string; truncated: boolean } {
  const value = text || "";
  if (value.length <= max) return { text: value, truncated: false };
  let cut = max;
  const code = value.charCodeAt(cut - 1);
  if (code >= 0xd800 && code <= 0xdbff) cut -= 1;
  return { text: value.slice(0, cut).trimEnd(), truncated: true };
}

export type ImportErrorKind = "unavailable" | "upgrade" | "too-large" | "invalid" | "network" | "other";

export type ImportErrorInfo = { kind: ImportErrorKind; message: string };

/**
 * Maps a thrown api error onto the page's inline states:
 *  - 404 (flag off, or an older core without the routes) -> calm "not available"
 *  - 403 (plan agent cap on create)                       -> inline upgrade card
 *  - 413 (body over the server's limit)                   -> too large
 *  - 400 (unparsable / undetectable / mismatched export)   -> the server's message
 */
export function classifyImportError(err: any): ImportErrorInfo {
  const message = typeof err?.message === "string" ? err.message : "";
  const status = typeof err?.status === "number" ? err.status : undefined;
  if (status === 404) return { kind: "unavailable", message };
  if (status === 403) return { kind: "upgrade", message };
  if (status === 413) return { kind: "too-large", message };
  if (status === 400) return { kind: "invalid", message };
  if (err?.isBackendUnreachable) return { kind: "network", message };
  return { kind: "other", message };
}
