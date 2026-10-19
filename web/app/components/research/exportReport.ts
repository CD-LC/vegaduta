// Turning a research report into a file. Pure functions plus one small
// orchestration (generateAndFetch) whose network calls are injected, so all of
// it is unit-tested without a browser or a server.
//
//  - Markdown: built here and downloaded as a Blob. No server involved, so it
//    always works.
//  - PDF / DOCX: the report is reshaped into the document service's
//    DocumentContent ({title, sections:[{heading, paragraphs, bulletPoints}]})
//    and sent to POST /api/documentgen/documents. That service draws plain
//    text - it does not parse markdown - so inline markup is flattened first
//    and links are spelled out as "label (url)".

import type {
  DocumentContent,
  DocumentContentSection,
  DocumentGenerationJob,
  DocumentGenerationRequest,
  Page,
  ResearchResult,
} from "../../lib/api/types";

/** Headings the export adds around the model's own text - passed in so they can be translated. */
export interface ExportLabels {
  sources: string;
  unanswered: string;
  partialNote: string;
  fallbackTitle: string;
}

export const DEFAULT_EXPORT_LABELS: ExportLabels = {
  sources: "Sources",
  unanswered: "Not answered",
  partialNote: "Partial report: some sources could not be read or a limit was reached.",
  fallbackTitle: "Research report",
};

const MAX_FILENAME_CHARS = 80;

/**
 * A safe, extension-less file name from a report title: no path separators,
 * no characters Windows/macOS refuse, no leading dots, bounded length.
 * Letters of any script are kept (a Hindi title stays a Hindi file name).
 */
export function sanitizeFilename(title: string | null | undefined, fallback = "research-report"): string {
  const cleaned = (title ?? "")
    .normalize("NFC")
    // eslint-disable-next-line no-control-regex
    .replace(/[\u0000-\u001f\u007f<>:"/\\|?*]+/g, " ")
    .replace(/\s+/g, " ")
    .trim()
    .replace(/^\.+/, "")
    .replace(/[. ]+$/, "")
    .slice(0, MAX_FILENAME_CHARS)
    .trim()
    .replace(/ /g, "-");
  return cleaned || fallback;
}

function sourceLine(s: { id: string; url: string; title?: string }): string {
  return s.title ? `[${s.id}] ${s.title} - ${s.url}` : `[${s.id}] ${s.url}`;
}

function quoteLine(q: { quote: string; verdict?: string }): string {
  return q.verdict ? `"${q.quote}" (${q.verdict})` : `"${q.quote}"`;
}

/** The report as one Markdown document: title, body, unanswered list, numbered sources with quotes. */
export function toMarkdown(result: ResearchResult, labels: ExportLabels = DEFAULT_EXPORT_LABELS): string {
  const out: string[] = [];
  out.push(`# ${result.title || labels.fallbackTitle}`, "");
  if (result.partial) out.push(`> ${labels.partialNote}`, "");
  out.push(result.reportMarkdown.replace(/\r\n/g, "\n").trim(), "");
  if (result.unanswered?.length) {
    out.push(`## ${labels.unanswered}`, "");
    for (const u of result.unanswered) out.push(`- ${u}`);
    out.push("");
  }
  if (result.sources.length) {
    out.push(`## ${labels.sources}`, "");
    result.sources.forEach((s, i) => {
      out.push(`${i + 1}. ${sourceLine(s)}`);
      for (const q of s.quotes ?? []) out.push(`   - ${quoteLine(q)}`);
    });
    out.push("");
  }
  return out.join("\n");
}

/** Flatten inline markdown to plain text; links become "label (url)", [S n] markers are kept. */
export function plainInline(text: string): string {
  return text
    .replace(/!\[([^\]]*)\]\(([^)\s]+)\)/g, "$1")
    .replace(/\[([^\]]+)\]\(([^)\s]+)\)/g, (_m, label: string, url: string) => (label === url ? url : `${label} (${url})`))
    .replace(/`([^`]+)`/g, "$1")
    .replace(/\*\*([^*]+)\*\*/g, "$1")
    .replace(/\*([^*]+)\*/g, "$1")
    .trim();
}

const HEADING = /^(#{1,6})\s+(.*)$/;
const BULLET = /^\s*(?:[-*•]|\d+[.)])\s+(.*)$/;

/**
 * Split report markdown into document sections. A heading starts a section;
 * list items become bullet points; everything else becomes paragraphs (blank
 * line = new paragraph). The document service draws a section's paragraphs
 * before its bullets, so text that FOLLOWS a list starts a new, heading-less
 * section - otherwise it would be drawn above the list it came after.
 */
export function splitSections(markdown: string): DocumentContentSection[] {
  const sections: DocumentContentSection[] = [];
  let current: DocumentContentSection = { heading: null, paragraphs: [], bulletPoints: [] };
  let para: string[] = [];

  const flushPara = () => {
    if (!para.length) return;
    const text = plainInline(para.join(" "));
    para = [];
    if (!text) return;
    if (current.bulletPoints.length) pushSection();
    current.paragraphs.push(text);
  };
  const pushSection = () => {
    if (current.heading || current.paragraphs.length || current.bulletPoints.length) sections.push(current);
    current = { heading: null, paragraphs: [], bulletPoints: [] };
  };

  let inFence = false;
  for (const rawLine of markdown.replace(/\r\n/g, "\n").split("\n")) {
    if (/^```/.test(rawLine)) {
      flushPara();
      inFence = !inFence;
      continue;
    }
    if (inFence) {
      // Code keeps its line breaks: one paragraph per line, unflattened.
      if (rawLine.trim()) {
        if (current.bulletPoints.length) pushSection();
        current.paragraphs.push(rawLine);
      }
      continue;
    }
    const heading = HEADING.exec(rawLine);
    if (heading) {
      flushPara();
      pushSection();
      current.heading = plainInline(heading[2]);
      continue;
    }
    const bullet = BULLET.exec(rawLine);
    if (bullet) {
      flushPara();
      const text = plainInline(bullet[1]);
      if (text) current.bulletPoints.push(text);
      continue;
    }
    if (rawLine.trim() === "") {
      flushPara();
      continue;
    }
    // Table separator rows and horizontal rules carry no text.
    if (/^\s*[|:\- ]+\s*$/.test(rawLine) || /^\s*(\*{3,}|_{3,})\s*$/.test(rawLine)) {
      flushPara();
      continue;
    }
    para.push(rawLine.trim());
  }
  flushPara();
  pushSection();
  return sections;
}

/** The report in the document service's shape (for PDF / DOCX). */
export function toDocumentContent(result: ResearchResult, labels: ExportLabels = DEFAULT_EXPORT_LABELS): DocumentContent {
  const sections = splitSections(result.reportMarkdown);
  if (result.partial) sections.unshift({ heading: null, paragraphs: [labels.partialNote], bulletPoints: [] });
  if (result.unanswered?.length) {
    sections.push({ heading: labels.unanswered, paragraphs: [], bulletPoints: [...result.unanswered] });
  }
  if (result.sources.length) {
    const bullets: string[] = [];
    for (const s of result.sources) {
      bullets.push(sourceLine(s));
      for (const q of s.quotes ?? []) bullets.push(`${s.id}: ${quoteLine(q)}`);
    }
    sections.push({ heading: labels.sources, paragraphs: [], bulletPoints: bullets });
  }
  return { title: result.title || labels.fallbackTitle, sections, table: null, rawText: null, json: null };
}

// Written as escapes on purpose: several of these are invisible (no-break and
// thin spaces, zero-width characters, BOM) and a formatter or "normalise
// whitespace" pass would silently change raw literals.
const PUNCTUATION: Array<[RegExp, string]> = [
  [/[\u2018\u2019\u201a\u2032]/g, "'"],
  [/[\u201c\u201d\u201e\u2033]/g, '"'],
  [/[\u2010-\u2015\u2212]/g, "-"],
  [/\u2026/g, "..."],
  [/[\u2022\u25cf\u25e6]/g, "-"],
  [/[\u00a0\u2002-\u200a\u202f]/g, " "],
  [/[\u200b-\u200d\ufeff]/g, ""],
  [/\u2192/g, "->"],
];

/**
 * The PDF generator can only draw characters up to U+00FF and replaces the
 * rest with "?". Curly quotes, dashes and ellipses are above that line, so an
 * ordinary English report would come out full of question marks - swap them
 * for their plain equivalents before sending.
 */
export function asciiPunctuation(text: string): string {
  let out = text;
  for (const [re, rep] of PUNCTUATION) out = out.replace(re, rep);
  return out;
}

/** True when text still holds characters the PDF generator would turn into "?" (e.g. Hindi, Tamil, Chinese, emoji). */
export function pdfWouldDropCharacters(text: string): boolean {
  const plain = asciiPunctuation(text);
  for (let i = 0; i < plain.length; i++) {
    if (plain.charCodeAt(i) > 255) return true;
  }
  return false;
}

/** Everything a PDF of this report would contain, for the dropped-character check. */
export function reportText(result: ResearchResult): string {
  const parts = [result.title, result.reportMarkdown, ...(result.unanswered ?? [])];
  for (const s of result.sources) {
    parts.push(s.title ?? "");
    for (const q of s.quotes ?? []) parts.push(q.quote);
  }
  return parts.join("\n");
}

function mapContent(content: DocumentContent, fn: (s: string) => string): DocumentContent {
  return {
    ...content,
    title: content.title == null ? content.title : fn(content.title),
    sections: content.sections.map((s) => ({
      heading: s.heading == null ? s.heading : fn(s.heading),
      paragraphs: s.paragraphs.map(fn),
      bulletPoints: s.bulletPoints.map(fn),
    })),
  };
}

/** The exact POST /api/documentgen/documents body for this report. `filename` carries no extension - the server adds it. */
export function buildDocumentRequest(
  type: "PDF" | "DOCX",
  result: ResearchResult,
  labels: ExportLabels = DEFAULT_EXPORT_LABELS
): DocumentGenerationRequest {
  const content = toDocumentContent(result, labels);
  return {
    type,
    filename: sanitizeFilename(result.title, "research-report"),
    content: type === "PDF" ? mapContent(content, asciiPunctuation) : content,
  };
}

export interface ExportDeps {
  generate: (request: DocumentGenerationRequest) => Promise<DocumentGenerationJob>;
  list: (opts: { page?: number; size?: number }) => Promise<Page<DocumentGenerationJob>>;
  download: (id: string) => Promise<{ blob: Blob; filename: string }>;
  sleep: (ms: number) => Promise<void>;
}

export type ExportFailureReason = "failed" | "timeout" | "refused";

export class ExportFailedError extends Error {
  /**
   * "failed" = the generator reported failure; "timeout" = it never finished
   * while we waited; "refused" = the service turned the request itself down
   * (plan, switched off, quota, not on this deployment).
   */
  readonly reason: ExportFailureReason;
  /** HTTP status of the refused request, when there was one. */
  readonly status?: number;
  constructor(reason: ExportFailureReason, message: string, status?: number) {
    super(message);
    this.name = "ExportFailedError";
    this.reason = reason;
    this.status = status;
  }
}

/**
 * Statuses on the generate POST that mean "this service will not make
 * documents for you right now" rather than "something broke on the way".
 * 500 is here because core's document quota refusal (FREE plan, switched off,
 * daily/monthly cap) currently has no handler mapping and arrives as 500.
 */
export const EXPORT_REFUSAL_STATUSES: readonly number[] = [403, 404, 429, 500, 503];

/**
 * Ask the document service for a PDF/DOCX, wait for it, fetch the bytes.
 * Generation is asynchronous server-side: the POST answers with a job row and
 * the file appears a moment later, so the job is looked up in the caller's
 * document list until it is COMPLETE or FAILED.
 */
export async function generateAndFetch(
  deps: ExportDeps,
  request: DocumentGenerationRequest,
  opts: { pollMs?: number; maxPolls?: number } = {}
): Promise<{ blob: Blob; filename: string }> {
  const pollMs = opts.pollMs ?? 1500;
  const maxPolls = opts.maxPolls ?? 40;
  let job: DocumentGenerationJob;
  try {
    job = await deps.generate(request);
  } catch (err: any) {
    // Only a refusal of the request itself is reported as "refused". A network
    // failure (no status) or anything later in the flow (list, download) is
    // left as it is, so the caller never tells the user the service is
    // unavailable because of a passing fault.
    if (typeof err?.status === "number" && EXPORT_REFUSAL_STATUSES.includes(err.status)) {
      throw new ExportFailedError("refused", err?.message || "Document export is not available.", err.status);
    }
    throw err;
  }
  for (let i = 0; i < maxPolls && job.status !== "COMPLETE" && job.status !== "FAILED"; i++) {
    await deps.sleep(pollMs);
    const page = await deps.list({ page: 0, size: 20 });
    const found = (page?.content ?? []).find((j) => j.id === job.id);
    if (found) job = found;
  }
  if (job.status === "FAILED") throw new ExportFailedError("failed", job.errorMessage || "The document could not be generated.");
  if (job.status !== "COMPLETE") throw new ExportFailedError("timeout", "The document is taking longer than expected.");
  return deps.download(job.id);
}
