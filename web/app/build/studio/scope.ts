// What the studio sends the model for an edit, and how wide that edit is.
//
// Three jobs, all pure so they are unit-tested:
//
//   isWholePageRequest  spots "redesign the whole app"-style asks. Those are
//                       the edits small models get wrong most (dozens of
//                       SEARCH blocks, half of which miss), so the studio
//                       suggests tapping the part first and only continues
//                       on an explicit yes.
//   toModelView /       the app as the model sees it. The vd:connect block is
//   fromModelView       ~9 KB of studio-owned runtime plus every connection's
//                       shareId - the public credential for that agent or
//                       workflow. The model must never edit it (commit()
//                       re-splices it) and has no business reading the
//                       shareIds, so it is swapped for a one-line placeholder
//                       that names only the call aliases, and swapped back
//                       after the edit blocks are applied.
//   describeTarget      the tapped element as a short pointer (tag, id,
//                       classes, text), not its runtime outerHTML: that copy
//                       duplicated markup already in APP, came from the live
//                       DOM (so it often didn't match the source the SEARCH
//                       blocks run against) and was clipped mid-tag at 1800
//                       chars.

import { CONNECT_END, CONNECT_START, readAppBlock } from "./connect";
import { findInSource, type SelectedElement } from "./runtime";
import type { StudioConnection } from "./store";

// Explicit whole-page / whole-app scope. Deliberately narrow: "make it more
// colourful" or "add a search box" are ordinary edits and must not nag.
const WHOLE_PAGE_PATTERNS: RegExp[] = [
  /\b(entire|whole|full|complete)\s+(app|page|screen|site|ui|design|layout|look|thing|interface)\b(?!\s+(title|heading|header|name)s?\b)/i,
  // "redesign the app", "rebuild my whole ui", "revamp it" - but not
  // "rewrite the page title" or "redesign the button".
  /\b(re-?design|revamp|overhaul|re-?do|re-?build|re-?write|re-?make)(\s+(the|my|this))?(\s+(whole|entire))?\s+(app|page|screen|ui|design|layout|look|everything|it)\b(?!\s+(title|heading|header|name|text|label|button)s?\b)/i,
  /^\s*(re-?design|revamp|overhaul)(\s+it)?\s*[.!]?\s*$/i,
  /\b(change|update|restyle|modify|fix|improve)\s+(everything|all\s+(of\s+)?(it|the\s+(screens|pages|sections|elements)))\b/i,
  /\ball\s+(the\s+)?(screens|pages|sections)\b/i,
  /\bfrom\s+scratch\b/i,
  /\b(complete|total|full)\s+(makeover|redesign|rework|change)\b/i,
  /\bstart\s+over\b/i,
];

export function isWholePageRequest(request: string): boolean {
  const r = request.trim();
  if (!r) return false;
  return WHOLE_PAGE_PATTERNS.some((p) => p.test(r));
}

/** Prefix of the placeholder that stands in for the vd:connect block. */
export const CONNECT_PLACEHOLDER_PREFIX = "<!-- vd:connect managed by the studio - do not edit or copy into SEARCH";

export interface ModelView {
  /** The HTML the model reads and the SEARCH blocks are applied to. */
  html: string;
  /** The block the placeholder replaced, or null when there was none. */
  hidden: string | null;
  placeholder: string | null;
}

/** Swap the studio-owned vd:connect block for a one-line placeholder that
 * names what the app can call, without the runtime or any shareId. */
export function toModelView(html: string, connections: StudioConnection[] = []): ModelView {
  const s = html.indexOf(CONNECT_START);
  const e = html.indexOf(CONNECT_END);
  if (s < 0 || e < s) return { html, hidden: null, placeholder: null };
  const hidden = html.slice(s, e + CONNECT_END.length);
  const app = readAppBlock(html);
  const aliases = connections.map((c) => c.alias || c.name).filter(Boolean);
  const facts = [
    aliases.length ? `call names for window.vd.call: ${aliases.join(", ")}` : "no connections",
    app?.auth ? "sign-in on (window.vd.auth.*)" : null,
    app?.ai ? "hosted AI on (window.vd.ai.chat)" : null,
  ].filter(Boolean);
  // "--" would end the comment early; aliases are slugs but names may not be.
  const placeholder = `${CONNECT_PLACEHOLDER_PREFIX}; ${facts.join("; ").replace(/--/g, "-")} -->`;
  return { html: html.slice(0, s) + placeholder + html.slice(e + CONNECT_END.length), hidden, placeholder };
}

/** Put the hidden block back after edits were applied to the model view.
 * A rewrite that dropped the placeholder is left alone - commit() re-splices
 * the connections from project state in that case. */
export function fromModelView(edited: string, view: ModelView): string {
  if (!view.hidden || !view.placeholder) return edited;
  const i = edited.indexOf(view.placeholder);
  if (i < 0) return edited;
  return edited.slice(0, i) + view.hidden + edited.slice(i + view.placeholder.length);
}

function openingTag(el: Element): string {
  const attrs = Array.from(el.attributes)
    .filter((a) => !a.name.startsWith("on") && a.name !== "style")
    .map((a) => (a.value ? `${a.name}="${a.value.slice(0, 80)}"` : a.name))
    .join(" ");
  return `<${el.tagName.toLowerCase()}${attrs ? " " + attrs : ""}>`;
}

/** A short, accurate pointer to the tapped element for the prompt. */
export function describeTarget(sel: SelectedElement, sourceHtml: string): string {
  const text = sel.text.replace(/\s+/g, " ").trim().slice(0, 160);
  let el: Element | null = null;
  try {
    el = findInSource(new DOMParser().parseFromString(sourceHtml, "text/html"), sel);
  } catch {
    el = null;
  }
  const lines = [
    el ? `Element: ${openingTag(el)}` : `Element: <${sel.tag}>`,
    text ? `Its text: "${text}"` : null,
    el
      ? "It is in the APP markup above - find it there and copy SEARCH lines from APP, not from this note."
      : "It is NOT in the markup: the app's script creates it at runtime. Change the JavaScript (or CSS) that renders it.",
    "Change only this element (and CSS/JS that exists just for it) unless the request clearly needs more.",
  ];
  return lines.filter(Boolean).join("\n");
}

// ---------------------------------------------------------------------------
// "The app hit an error" -> "Fix it with AI".
//
// The preview reports line numbers in the srcdoc, which has the harness
// injected at the top (HARNESS_LINES extra lines), so the raw number points
// the model at the wrong code. Map every srcdoc frame back to the app source
// and hand the model the failing line itself, not a number.

export interface RuntimeErrorReport {
  message: string;
  /** srcdoc line from the error event, 0 when unknown. */
  line?: number;
  stack?: string;
}

export interface ErrorLocation {
  /** 1-based line in the app source, or null. */
  line: number | null;
  /** The failing line's text (trimmed), or null. */
  code: string | null;
  /** A few lines around it, as they appear in the source. */
  nearby: string | null;
  /** The top frame is inside the studio-owned vd:connect runtime. */
  inStudioRuntime: boolean;
}

function srcdocLines(report: RuntimeErrorReport): number[] {
  const out: number[] = [];
  if (report.line && report.line > 0) out.push(report.line);
  for (const m of (report.stack || "").matchAll(/(?:about:srcdoc|<anonymous>)?:(\d+):\d+\)?/g)) {
    const n = Number(m[1]);
    if (n > 0 && !out.includes(n)) out.push(n);
  }
  return out;
}

export function locateRuntimeError(html: string, report: RuntimeErrorReport, harnessLines: number): ErrorLocation {
  const lines = html.replace(/\r\n/g, "\n").split("\n");
  const cs = html.indexOf(CONNECT_START);
  const ce = html.indexOf(CONNECT_END);
  const lineOf = (offset: number) => html.slice(0, offset).split("\n").length;
  const [studioFrom, studioTo] = cs >= 0 && ce > cs ? [lineOf(cs), lineOf(ce)] : [0, -1];
  let inStudioRuntime = false;
  let first = true;
  for (const raw of srcdocLines(report)) {
    const line = raw - harnessLines;
    if (line < 1 || line > lines.length) {
      first = false;
      continue;
    }
    if (line >= studioFrom && line <= studioTo) {
      if (first) inStudioRuntime = true;
      first = false;
      continue;
    }
    const code = lines[line - 1].trim();
    if (!code) continue;
    const nearby = lines.slice(Math.max(0, line - 4), Math.min(lines.length, line + 3)).join("\n");
    return { line, code: code.slice(0, 300), nearby: nearby.slice(0, 1500), inStudioRuntime };
  }
  return { line: null, code: null, nearby: null, inStudioRuntime };
}

/** The edit request "Fix it with AI" sends. `repeated` = the same error came
 * back after an earlier fix, so the model is told to try something else. */
export function fixRequest(
  report: RuntimeErrorReport,
  where: ErrorLocation,
  repeated = false,
  brokenScript: string | null = null
): string {
  const parts = [`The app throws this error at runtime - fix the ROOT CAUSE: ${report.message.slice(0, 600)}`];
  if (/syntaxerror|unexpected token/i.test(report.message)) {
    // A parse error means nothing ran, so there is no stack line to point
    // at - name the likely culprit explicitly or the model just guesses.
    parts.push(
      "This is a PARSE error: the app's <script> fails to compile, so no line number exists. The usual cause is text that is not JavaScript inside the script - a leftover <<<<<<< SEARCH / ======= / >>>>>>> REPLACE marker line, a stray line of '=' characters, or un-commented prose. Find and remove that line."
    );
    if (brokenScript) {
      parts.push(
        `This <script> is the one that fails to compile (fix it, do not rewrite the whole app):\n${brokenScript.slice(0, 4000)}`
      );
    }
  }
  if (where.code) {
    parts.push(`It is thrown at this line of APP:\n${where.code}`);
    if (where.nearby) parts.push(`Surrounding code in APP (copy SEARCH lines from APP):\n${where.nearby}`);
  }
  if (where.inStudioRuntime) {
    parts.push(
      "The error surfaced inside the studio's hidden vd runtime, which is correct and must not be edited - the cause is how the app calls window.vd.* (check call names against the vd:connect placeholder, and await the calls inside try/catch with a visible failure message)."
    );
  }
  if (repeated) parts.push("An earlier fix did NOT resolve this error - take a different approach.");
  parts.push("Change only what the fix needs; keep every screen and feature.");
  return parts.join("\n\n");
}
