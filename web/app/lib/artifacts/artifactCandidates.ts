// Artifacts pane - "Open as artifact" for a plain ```html / ```svg block.
//
// Prod signed-in pass 2026-10-05, bug 9: asked to "make it as an artifact", a
// model (deepseek-chat) answered with an ordinary ```html fence instead of the
// `artifact:html:<title>` header the directive teaches, so no chip appeared and
// the pane was unreachable for that reply. The convention is a request to an
// untrusted model, not a guarantee - so the web side offers the same pane for a
// block that is plainly a complete page or drawing.
//
// It is an OFFER, never automatic: the block stays an ordinary code block until
// the person clicks "Open as artifact". Clicking only rewrites that block's
// info string (in memory) to an artifact header, so everything after that is
// the existing pipeline - the same parser, registry, sandboxed iframe and
// "Run preview" gate as a real artifact. Nothing here renders or executes
// content; like artifactParser.ts it only SPLITS text. Pure, total, linear.

import { parseArtifactHeader, sanitizeTitle } from "./artifactParser";
import { MAX_ARTIFACT_CHARS, contentFingerprint } from "./artifactTypes";

export type CandidateKind = "html" | "svg";

export interface ArtifactCandidate {
  kind: CandidateKind;
  /** Raw, untrusted body of the fenced block. */
  content: string;
  /** Identity of this block within its message (see contentFingerprint). */
  fingerprint: string;
  /** Title the promoted artifact gets: the page's own <title>, else a generic one. */
  title: string;
}

export type CandidatePiece =
  | { type: "text"; text: string }
  | { type: "candidate"; text: string; candidate: ArtifactCandidate; fence: string };

/** Smaller blocks are snippets being discussed, not deliverables. */
export const MIN_CANDIDATE_CHARS = 200;
/** Parsing is skipped beyond this many characters of message text (same bound as the artifact parser). */
const MAX_SCAN_CHARS = 4_000_000;

const FENCE_OPEN = /^ {0,3}(`{3,}|~{3,})([^\n]*)$/;
const FENCE_BARE = /^ {0,3}(`{3,}|~{3,})[ \t]*$/;
const FENCE_NESTED_OPEN = /^ {0,3}(`{3,})[ \t]*([^`\s][^\n]*)$/;
const CANDIDATE_HINT = /(^|\n) {0,3}(`{3,}|~{3,})[ \t]*(html?|svg)\b/i;
const HTML_SHAPE = /<(!doctype\s+html|html|head|body|main|section|article|div|table|canvas|style|h1|svg)\b/i;
const SVG_SHAPE = /<svg\b/i;
const HTML_TITLE = /<title[^>]*>([^<]{1,200})<\/title>/i;

/** Cheap pre-check so ordinary messages never pay for a scan. */
export function mightContainCandidate(text: unknown): boolean {
  return typeof text === "string" && text.length >= MIN_CANDIDATE_CHARS && CANDIDATE_HINT.test(text);
}

function candidateKind(info: string): CandidateKind | null {
  const token = String(info || "").trim().split(/[\s{,:]/)[0].toLowerCase();
  if (token === "html" || token === "htm") return "html";
  if (token === "svg") return "svg";
  return null;
}

function isCloser(line: string, ch: string, len: number): boolean {
  const m = FENCE_BARE.exec(line);
  return m !== null && m[1][0] === ch && m[1].length >= len;
}

function titleFor(kind: CandidateKind, content: string): string {
  if (kind === "svg") return "SVG image";
  const m = HTML_TITLE.exec(content);
  // A backtick would end the fence's info string; sanitizeTitle handles the rest.
  const fromPage = m ? sanitizeTitle(m[1].replace(/`/g, "'"), "html") : "";
  return fromPage && fromPage !== "Untitled html" ? fromPage : "HTML page";
}

function asCandidate(kind: CandidateKind, body: string[]): ArtifactCandidate | null {
  const content = body.join("\n");
  const size = content.trim().length;
  if (size < MIN_CANDIDATE_CHARS || content.length > MAX_ARTIFACT_CHARS) return null;
  if (!(kind === "svg" ? SVG_SHAPE : HTML_SHAPE).test(content)) return null;
  return { kind, content, fingerprint: contentFingerprint(content), title: titleFor(kind, content) };
}

/**
 * Split message text into ordinary text and candidate blocks, in order.
 * Joining every piece's `text` with "\n" gives back the input (line endings
 * normalised to "\n"). Only a COMPLETE fenced block counts - one still
 * streaming, or cut off, is left as text. Blocks inside an `artifact:` fence
 * or inside any other code block are never candidates.
 */
export function splitCandidates(input: unknown): CandidatePiece[] {
  if (typeof input !== "string" || input === "") return [];
  if (input.length > MAX_SCAN_CHARS || !mightContainCandidate(input)) return [{ type: "text", text: input }];

  const lines = input.replace(/\r\n?/g, "\n").split("\n");
  const out: CandidatePiece[] = [];
  let buf: string[] = [];
  const flush = () => {
    if (buf.length > 0) out.push({ type: "text", text: buf.join("\n") });
    buf = [];
  };

  let i = 0;
  while (i < lines.length) {
    const line = lines[i];
    const open = FENCE_OPEN.exec(line);
    if (!open || (open[1][0] === "`" && open[2].includes("`"))) {
      buf.push(line);
      i++;
      continue;
    }
    const ch = open[1][0];
    const len = open[1].length;
    const isArtifact = parseArtifactHeader(open[2]) !== null;
    // An artifact fence nests 3-on-3 the way artifactParser.ts does, so its end is found the same way.
    const nestable = isArtifact && ch === "`" && len === 3;
    const body: string[] = [];
    let depth = 0;
    let closedAt = -1;
    let j = i + 1;
    for (; j < lines.length; j++) {
      const l = lines[j];
      if (nestable && depth > 0) {
        if (FENCE_BARE.test(l)) depth--;
      } else if (nestable && FENCE_NESTED_OPEN.test(l)) {
        depth++;
      } else if (isCloser(l, ch, len)) {
        closedAt = j;
        break;
      }
      body.push(l);
    }
    const end = closedAt >= 0 ? closedAt : lines.length - 1;
    const kind = isArtifact || closedAt < 0 ? null : candidateKind(open[2]);
    const candidate = kind ? asCandidate(kind, body) : null;
    if (candidate) {
      flush();
      out.push({ type: "candidate", text: lines.slice(i, end + 1).join("\n"), candidate, fence: open[1] });
    } else {
      for (let k = i; k <= end; k++) buf.push(lines[k]);
    }
    i = end + 1;
  }
  flush();
  return out;
}

/** The candidate blocks of a message, in order. */
export function findCandidates(input: unknown): ArtifactCandidate[] {
  const found: ArtifactCandidate[] = [];
  for (const p of splitCandidates(input)) if (p.type === "candidate") found.push(p.candidate);
  return found;
}

/**
 * `text` with every candidate the person chose to open rewritten into a real
 * `artifact:<kind>:<title>` block (only its opening fence line changes; the
 * body is untouched). Returns the input itself when nothing is promoted, so a
 * message nobody touched renders byte-for-byte as before.
 */
export function promoteCandidates(text: string, isPromoted: (fingerprint: string) => boolean): string {
  if (!mightContainCandidate(text)) return text;
  const pieces = splitCandidates(text);
  if (!pieces.some((p) => p.type === "candidate" && isPromoted(p.candidate.fingerprint))) return text;
  return pieces
    .map((p) => {
      if (p.type !== "candidate" || !isPromoted(p.candidate.fingerprint)) return p.text;
      const nl = p.text.indexOf("\n");
      return `${p.fence}artifact:${p.candidate.kind}:${p.candidate.title}${p.text.slice(nl)}`;
    })
    .join("\n");
}
