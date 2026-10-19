// Pure helpers that turn a parsed research report into the pieces the report
// screen needs beyond plain rendering: an outline (for the table of contents
// and scrollspy), reading time, follow-up questions, de-duplicated sources
// grouped by domain, citation-check counts, and a footnoted Markdown export.
// No React, no fetch - unit-tested in reportStructure.test.ts.
//
// Only fields that really exist on the result are used (title, reportMarkdown,
// sources[{id,url,title,quotes[{quote,claim,verdict}]}], unanswered, partial).

import type { ResearchQuote, ResearchResult, ResearchSource } from "../../lib/api/types";
import { normalizeSourceId, safeSourceUrl, sourceUrlMap, transformCitations } from "./citeLinks";
import { MAX_QUESTION_CHARS, hostOf, verdictTone, type VerdictTone } from "./researchRun";
import type { ExportLabels } from "./exportReport";

// ---------------------------------------------------------------- outline

export interface ReportSection {
  /** DOM id, unique within the report. */
  id: string;
  level: 1 | 2 | 3;
  /** Plain text: markup and citation markers removed. */
  heading: string;
  /** Markdown under the heading, up to the next section heading. */
  body: string;
}

export interface ReportOutline {
  /** Markdown before the first heading. */
  intro: string;
  sections: ReportSection[];
}

/** Heading text as plain text: links -> label, emphasis/code stripped, [S1] markers dropped. */
export function cleanHeading(text: string): string {
  return text
    .replace(/\[(S\s?\d+(?:\s*[,;]\s*S\s?\d+)*)\](?!\()/gi, "")
    .replace(/\[([^\]]+)\]\(([^)\s]+)\)/g, "$1")
    .replace(/`([^`]+)`/g, "$1")
    .replace(/\*\*([^*]+)\*\*/g, "$1")
    .replace(/\*([^*]+)\*/g, "$1")
    .replace(/\s+/g, " ")
    .trim();
}

export function slugify(text: string): string {
  const slug = text
    .normalize("NFKC")
    .toLowerCase()
    .replace(/[^\p{L}\p{N}]+/gu, "-")
    .replace(/^-+|-+$/g, "")
    .slice(0, 60);
  return slug || "section";
}

const HEADING = /^(#{1,3})[ \t]+(.+?)[ \t]*#*[ \t]*$/;

/**
 * Split report markdown into an intro and sections at #, ## and ### headings
 * (fenced code is respected). A leading H1 that repeats the report title is
 * dropped - the title is already shown above the body. Deeper headings stay in
 * their section's body, where the markdown renderer handles them.
 */
export function splitReport(markdown: string | null | undefined, title?: string | null): ReportOutline {
  const lines = (markdown ?? "").replace(/\r\n/g, "\n").split("\n");
  const sections: ReportSection[] = [];
  const used = new Set<string>();
  let intro: string[] = [];
  let cur: { level: 1 | 2 | 3; heading: string; lines: string[] } | null = null;
  let inFence = false;
  let sawContent = false;

  const flush = () => {
    if (!cur) return;
    let id = `rs-${slugify(cur.heading)}`;
    for (let n = 2; used.has(id); n++) id = `rs-${slugify(cur.heading)}-${n}`;
    used.add(id);
    sections.push({ id, level: cur.level, heading: cur.heading, body: cur.lines.join("\n").trim() });
    cur = null;
  };

  const titleKey = cleanHeading(title ?? "").toLowerCase();
  for (const line of lines) {
    if (/^```/.test(line)) inFence = !inFence;
    const m = inFence ? null : HEADING.exec(line);
    if (m) {
      const heading = cleanHeading(m[2]);
      const level = m[1].length as 1 | 2 | 3;
      if (!heading) continue;
      if (!sawContent && level === 1 && titleKey && heading.toLowerCase() === titleKey) continue;
      flush();
      sawContent = true;
      cur = { level, heading, lines: [] };
      continue;
    }
    if (line.trim()) sawContent = true;
    if (cur) cur.lines.push(line);
    else intro.push(line);
  }
  flush();
  return { intro: intro.join("\n").trim(), sections };
}

// ---------------------------------------------------------- reading time

/** Whole minutes to read `text` at `wpm`, never less than 1. */
export function readingMinutes(text: string | null | undefined, wpm = 220): number {
  const words = (text ?? "").trim().split(/\s+/).filter(Boolean).length;
  return Math.max(1, Math.ceil(words / wpm));
}

// ------------------------------------------------------------ follow-ups

export interface FollowUp {
  label: string;
  /** Full question to put in the composer. */
  prompt: string;
  kind: "unanswered" | "section";
}

const GENERIC_HEADINGS = /^(summary|overview|introduction|conclusion|conclusions|sources|references|key takeaways|tl;?dr|abstract)$/i;

function clip(text: string, max: number): string {
  return text.length <= max ? text : `${text.slice(0, max - 1).trimEnd()}…`;
}

/**
 * Questions worth asking next: whatever the sources left unanswered first,
 * then a "go deeper" for each substantive section heading. Nothing here is
 * invented text about the topic - every prompt only quotes the user's own
 * question and the report's own headings.
 */
export function deriveFollowUps(
  outline: ReportOutline,
  unanswered: ReadonlyArray<string> | null | undefined,
  question: string,
  max = 6
): FollowUp[] {
  const out: FollowUp[] = [];
  const q = question.trim();
  for (const u of unanswered ?? []) {
    const text = u.trim();
    if (!text) continue;
    const prompt = clip(q ? `${text}\n\n(Context: ${q})` : text, MAX_QUESTION_CHARS);
    out.push({ label: clip(text, 90), prompt, kind: "unanswered" });
  }
  const seen = new Set<string>();
  for (const s of outline.sections) {
    if (out.length >= max) break;
    if (s.level === 1 || GENERIC_HEADINGS.test(s.heading) || s.body.length < 80) continue;
    const key = s.heading.toLowerCase();
    if (seen.has(key)) continue;
    seen.add(key);
    const prompt = clip(q ? `Go deeper on "${s.heading}" for this question: ${q}` : `Go deeper on "${s.heading}"`, MAX_QUESTION_CHARS);
    out.push({ label: clip(s.heading, 90), prompt, kind: "section" });
  }
  return out.slice(0, max);
}

// -------------------------------------------------- sources: group, count

/** Same page, different spelling: lower-cased host, no www, no fragment, no tracking params, no trailing slash. */
export function urlKey(url: string): string {
  const safe = safeSourceUrl(url);
  if (!safe) return `raw:${(url ?? "").trim()}`;
  const u = new URL(safe);
  const params = [...u.searchParams.entries()]
    .filter(([k]) => !/^(utm_|fbclid$|gclid$|mc_cid$|mc_eid$|ref$)/i.test(k))
    .sort(([a], [b]) => a.localeCompare(b));
  const qs = params.map(([k, v]) => `${k}=${v}`).join("&");
  const path = u.pathname.replace(/\/+$/, "");
  return `${u.protocol}//${u.hostname.replace(/^www\./i, "").toLowerCase()}${path}${qs ? `?${qs}` : ""}`;
}

export const NOT_A_WEB_ADDRESS = "(not a web address)";

export interface SourceGroup {
  /** First source's own url, unmodified. */
  url: string;
  title?: string;
  domain: string;
  /** Every source id that points at this page, in report order. */
  ids: string[];
  quotes: ResearchQuote[];
}

/** Collapse sources that point at the same page into one group, keeping all their ids and quotes. */
export function groupSources(sources: ReadonlyArray<ResearchSource> | null | undefined): SourceGroup[] {
  const groups = new Map<string, SourceGroup>();
  for (const s of sources ?? []) {
    const key = urlKey(s.url);
    const href = safeSourceUrl(s.url);
    let g = groups.get(key);
    if (!g) {
      g = { url: s.url, title: s.title, domain: href ? hostOf(href).toLowerCase() : NOT_A_WEB_ADDRESS, ids: [], quotes: [] };
      groups.set(key, g);
    }
    if (!g.title && s.title) g.title = s.title;
    if (!g.ids.includes(s.id)) g.ids.push(s.id);
    for (const q of s.quotes ?? []) {
      if (!g.quotes.some((x) => x.quote === q.quote && x.claim === q.claim)) g.quotes.push(q);
    }
  }
  return [...groups.values()];
}

export interface DomainCount {
  domain: string;
  /** Distinct pages on the domain. */
  count: number;
  ids: string[];
}

export function domainSummary(groups: ReadonlyArray<SourceGroup>): DomainCount[] {
  const map = new Map<string, DomainCount>();
  for (const g of groups) {
    const d = map.get(g.domain) ?? { domain: g.domain, count: 0, ids: [] };
    d.count += 1;
    d.ids.push(...g.ids);
    map.set(g.domain, d);
  }
  const rank = (d: DomainCount) => (d.domain === NOT_A_WEB_ADDRESS ? 1 : 0);
  return [...map.values()].sort((a, b) => rank(a) - rank(b) || b.count - a.count || a.domain.localeCompare(b.domain));
}

export type ToneCounts = Record<VerdictTone, number>;

/** How the citation check judged each quote: counts per bucket. A quote with no verdict counts as unknown. */
export function quoteToneCounts(groups: ReadonlyArray<SourceGroup>): ToneCounts {
  const counts: ToneCounts = { supported: 0, weak: 0, unsupported: 0, unknown: 0 };
  for (const g of groups) for (const q of g.quotes) counts[verdictTone(q.verdict)] += 1;
  return counts;
}

export interface SourceFilter {
  domain?: string | null;
  tone?: VerdictTone | null;
}

/**
 * Apply the panel's filters. A tone filter keeps only the groups that have a
 * quote of that tone and shows only those quotes; a domain filter keeps that
 * domain's groups.
 */
export function filterGroups(groups: ReadonlyArray<SourceGroup>, filter: SourceFilter): SourceGroup[] {
  const out: SourceGroup[] = [];
  for (const g of groups) {
    if (filter.domain && g.domain !== filter.domain) continue;
    if (filter.tone) {
      const quotes = g.quotes.filter((q) => verdictTone(q.verdict) === filter.tone);
      if (quotes.length === 0) continue;
      out.push({ ...g, quotes });
    } else {
      out.push(g);
    }
  }
  return out;
}

/** Source id -> index of the group that holds it (ids compared in normalised form). */
export function groupIndexById(groups: ReadonlyArray<SourceGroup>): Map<string, number> {
  const map = new Map<string, number>();
  groups.forEach((g, i) => {
    for (const id of g.ids) {
      const n = normalizeSourceId(id);
      if (n && !map.has(n)) map.set(n, i);
    }
  });
  return map;
}

// ------------------------------------------------- footnoted markdown

function oneLine(text: string): string {
  return text.replace(/\s+/g, " ").trim();
}

/**
 * The report as Markdown with real footnotes: every [S1] marker in the body
 * becomes a [^S1] reference and each source gets a footnote definition with
 * its title, address and quoted passages. Unknown markers are left as they
 * were. Renders properly on GitHub, Obsidian, Typora and most Markdown tools.
 */
export function toFootnotedMarkdown(result: ResearchResult, labels: ExportLabels): string {
  const out: string[] = [];
  out.push(`# ${result.title || labels.fallbackTitle}`, "");
  if (result.partial) out.push(`> ${labels.partialNote}`, "");

  const known = new Set(sourceUrlMap(result.sources).keys());
  // Footnote labels cover every source id with a usable address OR not: a
  // source with an unsafe url still gets its definition (shown, not linked).
  for (const s of result.sources) {
    const id = normalizeSourceId(s.id);
    if (id) known.add(id);
  }
  const body = transformCitations(result.reportMarkdown.replace(/\r\n/g, "\n").trim(), known, (ids) =>
    ids.map((id) => (known.has(id) ? `[^${id}]` : `[${id}]`)).join("")
  );
  out.push(body, "");

  if (result.unanswered?.length) {
    out.push(`## ${labels.unanswered}`, "");
    for (const u of result.unanswered) out.push(`- ${oneLine(u)}`);
    out.push("");
  }

  const defined = new Set<string>();
  for (const s of result.sources) {
    const id = normalizeSourceId(s.id);
    if (!id || defined.has(id)) continue;
    defined.add(id);
    const head = s.title ? `${oneLine(s.title)}. ${s.url}` : s.url;
    const quotes = (s.quotes ?? []).map((q) => `"${oneLine(q.quote)}"${q.verdict ? ` (${oneLine(q.verdict)})` : ""}`);
    out.push(`[^${id}]: ${head}${quotes.length ? ` Quoted: ${quotes.join("; ")}` : ""}`);
  }
  out.push("");
  return out.join("\n");
}
