// SEARCH/REPLACE edit blocks for the Phone app studio.
//
// Why edits and not rewrites: the on-device models (1-4B params, 4k context)
// can't re-emit a whole 8 KB app reliably - they drift, truncate, and drop
// features they weren't asked about. Asking for small anchored blocks keeps
// every edit inside the context budget and leaves untouched code untouched.
// The format is the aider-style one small coder models are trained on:
//
//   <<<<<<< SEARCH
//   exact existing lines
//   =======
//   replacement lines
//   >>>>>>> REPLACE
//
// Pure functions only - no DOM, no model calls - so it is unit-tested.

export interface EditBlock {
  search: string;
  replace: string;
}

export interface ApplyResult {
  html: string;
  applied: number;
  failed: EditBlock[];
}

// The separator and end markers are line-anchored (`\n=`/`\n>`, not
// `\n?=`/`\n?>`): without the anchor a comment divider a small model writes
// inside a block body - `// ====== Section ======` - falsely matches as the
// SEARCH/REPLACE separator, truncates the block mid-content, and can splice
// a raw `=======` line into the document ("Unexpected token '==='" inside
// <script>). Line-start markers are the documented contract anyway.
const BLOCK_RE =
  /<{5,9}\s*SEARCH[^\n]*\n([\s\S]*?)\n={5,9}[^\n]*\n([\s\S]*?)\n>{5,9}\s*REPLACE/g;

/** Tolerant fallback for small models that get the KEYWORDS right but the
 * marker widths or casing wrong - `<<< SEARCH`, `====`, `>>>>>>> replace`
 * all occur in real Gemini Nano / 1-3B output, and the strict pattern above
 * rejects them (previously: "the AI's answer didn't fit the app" on a reply
 * that was a few marker characters off). Only consulted when the strict
 * pattern finds nothing; the SEARCH/REPLACE keywords keep it specific. */
const BLOCK_RE_LOOSE =
  /<{3,}\s*SEARCH[^\n]*\n([\s\S]*?)\n[ \t]*={3,}[^\n]*\n([\s\S]*?)\n[ \t]*>{3,}\s*REPLACE/gi;

/** Extract every SEARCH/REPLACE block from a model reply. Tolerant of
 * surrounding prose and ``` fences (small models add both). */
export function parseEditBlocks(reply: string): EditBlock[] {
  const text = reply.replace(/\r\n/g, "\n");
  const out: EditBlock[] = [];
  for (const m of text.matchAll(BLOCK_RE)) {
    out.push({ search: stripFence(m[1]), replace: stripFence(m[2]) });
  }
  if (out.length === 0) {
    for (const m of text.matchAll(BLOCK_RE_LOOSE)) {
      out.push({ search: stripFence(m[1]), replace: stripFence(m[2]) });
    }
  }
  return out;
}

function stripFence(s: string): string {
  return s.replace(/^```[a-zA-Z]*\n/, "").replace(/\n```\s*$/, "");
}

/** The model's own one-sentence explanation, when it ended its reply with
 * the `NOTE:` line EDIT_SYSTEM asks for. Tolerant of case, spacing and the
 * note appearing anywhere (small models rarely respect "after the last
 * block" exactly); the LAST NOTE wins. Null when absent — callers fall
 * back to the mechanical "N changes applied" line. */
export function extractEditNote(reply: string): string | null {
  let note: string | null = null;
  for (const m of String(reply || "").matchAll(/^\s*NOTE\s*:\s*(.+?)\s*$/gim)) {
    note = m[1];
  }
  return note;
}

/** Pull a full HTML document out of a reply, or null. Used when the model
 * (or the caller) chose a full rewrite instead of edit blocks. */
export function extractHtmlDocument(reply: string): string | null {
  const text = reply.replace(/\r\n/g, "\n");
  const start = text.search(/<!doctype html|<html[\s>]/i);
  if (start < 0) return null;
  const endIdx = text.toLowerCase().lastIndexOf("</html>");
  if (endIdx >= start) return text.slice(start, endIdx + "</html>".length);
  // Salvage a document that lost only its TAIL (small on-device models hit
  // their output cap mid-way and the last thing lost is `</body></html>`).
  // Safe only when no <script>/<style> is still open - a half-written script
  // would be silently broken JS - and we cut at the last real closing tag
  // so trailing prose can't leak into the rendered page.
  const tail = text.slice(start);
  const bodyAt = tail.search(/<body[\s>]/i);
  if (bodyAt < 0) return null;
  const balanced = (open: RegExp, close: RegExp) =>
    (tail.match(open) || []).length === (tail.match(close) || []).length;
  if (!balanced(/<script[\s>]/gi, /<\/script>/gi)) return null;
  if (!balanced(/<style[\s>]/gi, /<\/style>/gi)) return null;
  // Must end inside the body - the last closing tag has to come AFTER
  // `<body`, otherwise the reply was cut before any app content exists and
  // fabricating `</body>` would just produce an empty page.
  const last = [...tail.matchAll(/<\/[a-zA-Z][^>]*>/g)].filter((c) => (c.index ?? 0) > bodyAt).pop();
  if (!last) return null;
  let doc = tail.slice(0, (last.index ?? 0) + last[0].length);
  if (!/<\/body>/i.test(doc)) doc += "\n</body>";
  doc += "\n</html>";
  return doc;
}

function normalizeLine(l: string): string {
  return l.trim().replace(/\s+/g, " ");
}

/** Find `search` in `html` ignoring per-line indentation / inner spacing.
 * Returns [start, end) char offsets in `html`, or null. */
function fuzzyLocate(html: string, search: string): [number, number] | null {
  const needle = search.split("\n").map(normalizeLine).filter((l) => l.length > 0);
  if (needle.length === 0) return null;
  const lines = html.split("\n");
  const offsets: number[] = [];
  let acc = 0;
  for (const l of lines) {
    offsets.push(acc);
    acc += l.length + 1;
  }
  const norm = lines.map(normalizeLine);
  for (let i = 0; i < norm.length; i++) {
    if (norm[i] !== needle[0]) continue;
    let j = i;
    let k = 0;
    while (j < norm.length && k < needle.length) {
      if (norm[j] === "") {
        j++;
        continue;
      }
      if (norm[j] !== needle[k]) break;
      j++;
      k++;
    }
    if (k === needle.length) {
      const start = offsets[i];
      const lastLine = j - 1;
      const end = offsets[lastLine] + lines[lastLine].length;
      return [start, end];
    }
  }
  return null;
}

/** Apply blocks in order. Exact match first, then an indentation-insensitive
 * line match. A block whose SEARCH is empty appends before </body> (the
 * model's way of saying "add this"). Blocks that can't be located are
 * returned in `failed` - never silently dropped. */
export function applyEditBlocks(html: string, blocks: EditBlock[]): ApplyResult {
  let doc = html.replace(/\r\n/g, "\n");
  let applied = 0;
  const failed: EditBlock[] = [];
  for (const b of blocks) {
    if (!b.search.trim()) {
      const at = doc.toLowerCase().lastIndexOf("</body>");
      if (at < 0) {
        failed.push(b);
        continue;
      }
      doc = doc.slice(0, at) + b.replace + "\n" + doc.slice(at);
      applied++;
      continue;
    }
    const exact = doc.indexOf(b.search);
    if (exact >= 0) {
      doc = doc.slice(0, exact) + b.replace + doc.slice(exact + b.search.length);
      applied++;
      continue;
    }
    const loc = fuzzyLocate(doc, b.search);
    if (loc) {
      doc = doc.slice(0, loc[0]) + b.replace + doc.slice(loc[1]);
      applied++;
      continue;
    }
    failed.push(b);
  }
  return { html: doc, applied, failed };
}

/** A real parser registered by the session once @babel/standalone has loaded
 * (app/lib/babel.ts); tests register their own. Until one is registered the
 * script check below is a no-op so a parser that fails to load never rejects
 * a good document. `new Function` is deliberately not the fallback: the
 * production CSP has no 'unsafe-eval', so it would throw on every script. */
type ScriptChecker = (body: string, opts: { module: boolean }) => void;
let scriptChecker: ScriptChecker | null = null;
export function setScriptChecker(checker: ScriptChecker | null): void {
  scriptChecker = checker;
}

/** Compile-checks each inline <script> body with the registered parser and
 * returns the first body that fails, or null when every script parses (or no
 * parser is registered). src= links, non-JS types (JSON, importmap) are
 * skipped; `type="module"` bodies are parsed as modules so import/export is
 * legal. A failure here is the exact "Uncaught SyntaxError ... Unexpected
 * token" class the preview reports: marker leakage the regexes missed, a
 * truncated block, a mangled `const`. */
export function firstBrokenScript(html: string): string | null {
  if (!scriptChecker) return null;
  for (const m of html.matchAll(/<script([^>]*)>([\s\S]*?)<\/script\s*>/gi)) {
    const attrs = m[1];
    const body = m[2];
    if (!body.trim()) continue;
    if (/\bsrc\s*=/i.test(attrs)) continue;
    const type = /\btype\s*=\s*["']?([^"'\s>]+)/i.exec(attrs)?.[1]?.toLowerCase() ?? "";
    if (type && type !== "text/javascript" && type !== "application/javascript" && type !== "module") continue;
    try {
      scriptChecker(body, { module: type === "module" });
    } catch {
      return body;
    }
  }
  return null;
}

/** Cheap sanity check before an edit is accepted as a new version: the
 * document still has its skeleton, <script>/<style> tags are balanced, no
 * SEARCH/REPLACE marker line leaked in, and - once a script checker is
 * registered - every inline script actually parses. A bare `=======` or a
 * mangled `const` inside <script> is "Unexpected token 'X'" at parse time -
 * the preview is dead on arrival and "Fix it with AI" used to ship another
 * broken document the same way. */
export function looksLikeValidApp(html: string): boolean {
  const lower = html.toLowerCase();
  if (!lower.includes("<body") || !lower.includes("</body>")) return false;
  const count = (re: RegExp) => (lower.match(re) || []).length;
  if (count(/<script[\s>]/g) !== count(/<\/script\s*>/g)) return false;
  if (count(/<style[\s>]/g) !== count(/<\/style\s*>/g)) return false;
  if (/^\s*(?:<{3,}\s*SEARCH|={5,}|>{3,}\s*REPLACE)\s*$/im.test(html)) return false;
  if (firstBrokenScript(html) != null) return false;
  return true;
}

/** Helpers every skeleton script block defines on ONE line at column 0. A
 * model that writes its own `const esc = ...` (or `function load`) at top
 * level collides with them: "Identifier 'esc' has already been declared"
 * kills the whole script and the user gets a blank app. */
const SKELETON_HELPERS = ["esc", "load", "save"] as const;

/** Drops the skeleton's one-line helper definition when the same document
 * redeclares that name at top level, so the model's own version wins. Only
 * column-0 declarations count - an indented `const esc` inside a function is
 * a different scope and needs no repair. Returns the input unchanged when
 * nothing collides. */
export function dedupeSkeletonHelpers(html: string): string {
  let out = html;
  for (const name of SKELETON_HELPERS) {
    const skeleton = new RegExp("^(?:const|let|var) " + name + " = .*$", "m");
    const m = skeleton.exec(out);
    if (!m) continue;
    let end = m.index + m[0].length;
    if (out[end] === String.fromCharCode(13)) end++;
    if (out[end] === String.fromCharCode(10)) end++;
    const rest = out.slice(0, m.index) + out.slice(end);
    const redeclared = new RegExp("^(?:const|let|var|function)[ ]+" + name + "(?![A-Za-z0-9_$])", "m");
    if (redeclared.test(rest)) out = rest;
  }
  return out;
}
