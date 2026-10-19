// Local-code index — structural chunker.
//
// This file is pure: no DOM, no storage, no worker, no I/O. It turns one
// source file into the LocalChunk[] the indexer embeds, and it is the reason a
// citation in the answer UI can be trusted — every chunk carries the REAL
// 1-based startLine/endLine of the lines its text was cut from, so "see
// src/foo.ts:120-158" opens on the code the model actually read.
//
// Why regex boundaries and not a parser: a real parser (tree-sitter/babel) is
// a multi-megabyte wasm or JS dependency per language, downloaded before the
// user can index anything — on a feature whose entire promise is "free, local,
// nothing downloads". The regexes below are deliberately a PERMISSION to split,
// never a mandate: a boundary only ends a chunk once the chunk is already at
// least minLinesForBoundary long, so a noisy matcher (the generic fallback
// matches any line starting in column 0) degrades to plain size-based chunking
// instead of producing a chunk per line. That property is what makes a
// one-regex-per-language-family table good enough here.
//
// SIZE TARGETS, and the number this header used to get wrong. It claimed ~40
// lines was "comfortably inside MiniLM's 256-token window once tokenized".
// That is false, and it was load-bearing. Measured over six real files in this
// repo (this file, indexer.ts, fsAccess.ts, edge/tier1.ts, LocalCodePanel.tsx
// and a 710-line Java service), a 40-line window averages 1510-2362 characters
// and peaks at 2946. At the ~2.5-3 characters per wordpiece token that source
// code typically costs, that is several times a 256-token budget — so the
// tokenizer truncates, the stored vector represents roughly the first half of
// the span, and searchLocalIndex still cites the WHOLE span. "The citation
// points at what the embedding actually read" is the one property this module
// exists to hold, so the fix is the sizes, not the sentence.
//
// The binding budget is therefore CHARACTERS — the only unit this file can
// measure without running a tokenizer it does not have. DEFAULT_MAX_CHARS
// (640) is a hard ceiling on a chunk's text; 640 characters is 256 tokens at
// 2.5 characters per token, the conservative end of that range. Pathological
// input (a line of pure punctuation) tokenizes denser than any such ratio and
// can still overflow — but the residual exposure is now the tail of a ~16-line
// chunk rather than half of a 40-line one, and the line numbers stay true
// either way. DEFAULT_TARGET_LINES (16) is the line-shaped ceiling that
// matches it: the same measurement puts a 16-line window at 609-933 characters
// mean, so the character cap closes the chunk on dense files (Java, long JSX)
// and the line count closes it on sparse ones.
//
// What is NOT claimed here: the model's real window. Nothing in this repo has
// measured all-MiniLM-L6-v2's max_seq_length on device, and this file cannot.
// 256 is the number the sentence-transformers card publishes for it, used as a
// conservative budget rather than as a measurement. If a later wave measures
// the real window (or swaps in a code-specific embedder), this comment and the
// three constants below are the whole change.
//
// 4 lines of overlap (a quarter of the target) keeps a declaration that
// straddles a size-driven cut retrievable from both sides.

import type { LocalChunk } from "../types";

export interface ChunkOptions {
  /** Soft ceiling on lines per chunk; a chunk is closed once it reaches this. */
  targetLines?: number;
  /** Lines replayed into the next chunk after a SIZE-driven cut (a cut at a
   * declaration boundary is a clean seam and gets no overlap — see below). */
  overlapLines?: number;
  /** Hard ceiling on a chunk's characters. This is the budget that keeps the
   * embedding and the citation describing the same lines (see the header), and
   * it is also what stops a minified or generated file where "16 lines" can
   * mean a megabyte. */
  maxChars?: number;
}

export const DEFAULT_TARGET_LINES = 16;
export const DEFAULT_OVERLAP_LINES = 4;
export const DEFAULT_MAX_CHARS = 640;

/** A boundary may only close a chunk once the chunk is at least this fraction
 * of targetLines long. Keeps a file of one-line declarations (a barrel export,
 * a constants file, anything the generic matcher over-matches on) from
 * becoming one chunk per line. Exported because it is the rule the chunker's
 * tests assert against, and a test that hard-codes 0.5 silently stops testing
 * the rule the moment someone tunes it. */
export const BOUNDARY_MIN_FRACTION = 0.5;

// ---------------------------------------------------------------------------
// Language → top-level-declaration matcher
// ---------------------------------------------------------------------------
//
// Each pattern is anchored and intentionally shallow-indent-tolerant where the
// language nests its declarations (Java/Kotlin/C# methods live one level in).
// They are tested with .test() and carry no /g flag — a /g regex would carry
// lastIndex between lines and silently skip every other match.

const JS_LIKE =
  /^(?:export\s+)?(?:default\s+)?(?:declare\s+)?(?:abstract\s+)?(?:async\s+)?(?:function|class|interface|type|enum|namespace|module|const|let|var)\b|^@[A-Za-z_$]|^(?:export\s+)?\{|^describe\(|^it\(|^test\(/;
const PYTHON_LIKE = /^(?:async\s+def|def|class)\b|^@[A-Za-z_]/;
const GO_LIKE = /^(?:func|type|var|const|package|import)\b/;
const RUST_LIKE =
  /^(?:pub(?:\([^)]*\))?\s+)?(?:async\s+)?(?:unsafe\s+)?(?:fn|struct|enum|trait|impl|mod|const|static|type|macro_rules!)\b|^#\[/;
const JVM_LIKE =
  /^\s{0,4}(?:@[A-Za-z_]|(?:public|private|protected|internal|open|final|abstract|static|sealed|data|override|suspend|companion)\s+|(?:class|interface|enum|object|record|fun|func|void|struct|extension|protocol)\b)/;
const RUBY_LIKE = /^\s{0,2}(?:def|class|module)\b/;
const PHP_LIKE = /^\s{0,4}(?:(?:public|private|protected|static|abstract|final)\s+)*(?:function|class|interface|trait|enum)\b/;
const SQL_LIKE = /^(?:CREATE|ALTER|DROP|INSERT|UPDATE|DELETE|WITH|SELECT|--\s*V\d)/i;
const MARKDOWN_LIKE = /^#{1,6}\s/;
const CSS_LIKE = /^(?:@[a-z-]+|[.#:\[a-zA-Z][^{};]*\{)/;
const SHELL_LIKE = /^(?:function\s+[A-Za-z_]|[A-Za-z_][\w-]*\s*\(\)\s*\{|#\s)/;
/** A YAML document's structure IS its column-0 keys. */
const YAML_LIKE = /^[A-Za-z_"'-]/;
/** Fallback: any line that starts in column 0 with an identifier-ish
 * character. Over-matches by design — see the header note on why that is safe
 * (it only ever *permits* a cut the size rule would soon force anyway). */
const GENERIC = /^[A-Za-z_$@#]/;

const BOUNDARY_BY_LANG: Record<string, RegExp> = {
  ts: JS_LIKE, tsx: JS_LIKE, typescript: JS_LIKE, typescriptreact: JS_LIKE,
  js: JS_LIKE, jsx: JS_LIKE, javascript: JS_LIKE, javascriptreact: JS_LIKE,
  mjs: JS_LIKE, cjs: JS_LIKE, mts: JS_LIKE, cts: JS_LIKE, vue: JS_LIKE, svelte: JS_LIKE,
  py: PYTHON_LIKE, python: PYTHON_LIKE, pyi: PYTHON_LIKE,
  go: GO_LIKE, golang: GO_LIKE,
  rs: RUST_LIKE, rust: RUST_LIKE,
  java: JVM_LIKE, kt: JVM_LIKE, kts: JVM_LIKE, kotlin: JVM_LIKE, scala: JVM_LIKE,
  cs: JVM_LIKE, csharp: JVM_LIKE, swift: JVM_LIKE, dart: JVM_LIKE,
  c: JVM_LIKE, h: JVM_LIKE, cpp: JVM_LIKE, cc: JVM_LIKE, hpp: JVM_LIKE, cxx: JVM_LIKE,
  rb: RUBY_LIKE, ruby: RUBY_LIKE,
  php: PHP_LIKE,
  sql: SQL_LIKE,
  md: MARKDOWN_LIKE, markdown: MARKDOWN_LIKE, mdx: MARKDOWN_LIKE,
  css: CSS_LIKE, scss: CSS_LIKE, sass: CSS_LIKE, less: CSS_LIKE,
  sh: SHELL_LIKE, bash: SHELL_LIKE, zsh: SHELL_LIKE, shell: SHELL_LIKE,
  yaml: YAML_LIKE, yml: YAML_LIKE,
};

/** Extension→lang fallback for when the caller has no LocalFileRef.lang to
 * pass. Deliberately NOT exported and deliberately trivial: fsAccess.ts's
 * languageForPath() is the authoritative classifier (it is what fills
 * LocalFileRef.lang, which the indexer passes straight through), and a second
 * exported one would just be a second source of truth to drift. Importing it
 * here instead would drag fsAccess's permission + IndexedDB plumbing into what
 * is otherwise a pure function. */
function langFromPath(path: string): string {
  const base = path.slice(path.lastIndexOf("/") + 1);
  const dot = base.lastIndexOf(".");
  return dot > 0 ? base.slice(dot + 1).toLowerCase() : "";
}

/** Resolves the boundary matcher for a file. Accepts either vocabulary
 * (extension-ish "ts" or full-name "typescript") since fsAccess owns `lang`
 * and this module must not care which spelling it settled on. */
function boundaryMatcherFor(path: string, lang: string | undefined): RegExp {
  const key = (lang || "").toLowerCase().trim();
  if (key && BOUNDARY_BY_LANG[key]) return BOUNDARY_BY_LANG[key];
  const ext = langFromPath(path);
  if (ext && BOUNDARY_BY_LANG[ext]) return BOUNDARY_BY_LANG[ext];
  return GENERIC;
}

function isBlank(line: string): boolean {
  return line.trim().length === 0;
}

// ---------------------------------------------------------------------------
// The chunker
// ---------------------------------------------------------------------------

/**
 * Splits one file into embeddable chunks.
 *
 * Invariant the tests pin, and the whole reason this function exists: for every
 * returned chunk, `chunk.text` is exactly `lines.slice(startLine - 1, endLine)`
 * rejoined — so a citation rendered from (path, startLine, endLine) shows the
 * same bytes the embedding was computed from. The ONE documented exception is a
 * chunk whose text exceeds maxChars (a minified or generated line): the text is
 * truncated to a prefix, while the line numbers stay true.
 *
 * Returns [] for empty or whitespace-only input. Never throws.
 */
export function chunkSource(
  path: string,
  text: string,
  lang?: string,
  options?: ChunkOptions
): LocalChunk[] {
  if (typeof text !== "string" || text.trim().length === 0) return [];
  const targetLines = Math.max(1, options?.targetLines ?? DEFAULT_TARGET_LINES);
  const overlapLines = Math.max(0, Math.min(targetLines - 1, options?.overlapLines ?? DEFAULT_OVERLAP_LINES));
  const maxChars = Math.max(1, options?.maxChars ?? DEFAULT_MAX_CHARS);
  const minLinesForBoundary = Math.max(1, Math.floor(targetLines * BOUNDARY_MIN_FRACTION));
  const boundary = boundaryMatcherFor(path, lang);

  // Normalize CRLF so line indexes (and therefore citations) match what an
  // editor shows on either platform.
  const lines = text.split(/\r?\n/);
  const chunks: LocalChunk[] = [];

  let start = 0;
  while (start < lines.length) {
    let end = start; // exclusive
    let chars = 0;
    let closedAtBoundary = false;
    while (end < lines.length) {
      const line = lines[end];
      const taken = end - start;
      if (taken > 0) {
        if (taken >= targetLines) break;
        if (chars + line.length + 1 > maxChars) break;
        if (taken >= minLinesForBoundary && boundary.test(line)) {
          closedAtBoundary = true;
          break;
        }
      }
      chars += line.length + 1;
      end++;
    }

    // Trim blank lines off both ends of the RANGE (not just the text) so the
    // citation's first line is real code rather than the gap above it.
    let from = start;
    let to = end; // exclusive
    while (from < to && isBlank(lines[from])) from++;
    while (to > from && isBlank(lines[to - 1])) to--;

    // Blank-line trimming can collapse an overlapping range onto the previous
    // chunk's exact span (a file with a long blank tail does this) — two chunks
    // with the same id would silently overwrite each other in the store and
    // inflate the chunk count, so drop the contained one.
    const previous = chunks.length > 0 ? chunks[chunks.length - 1] : null;
    const contained = previous != null && from + 1 >= previous.startLine && to <= previous.endLine;
    if (to > from && !contained) {
      let body = lines.slice(from, to).join("\n");
      if (body.length > maxChars) body = body.slice(0, maxChars);
      const startLine = from + 1;
      const endLine = to;
      chunks.push({
        id: `${path}#L${startLine}-L${endLine}`,
        path,
        startLine,
        endLine,
        text: body,
      });
    }

    if (end >= lines.length) break;
    // A cut at a declaration is a clean seam — replaying lines across it would
    // duplicate a whole signature in two chunks for no retrieval gain. A cut
    // forced by size lands mid-construct, so that one overlaps.
    const next = closedAtBoundary ? end : Math.max(start + 1, end - overlapLines);
    start = next > start ? next : start + 1; // forward progress is non-negotiable
  }

  return chunks;
}
