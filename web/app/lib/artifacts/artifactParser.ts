// Artifacts pane - the fenced-block convention parser.
//
// Convention (taught to the model by the server-side artifacts directive,
// core ArtifactsDirective, only when that is switched on for the agent):
//
//   ```artifact:<type>:<title>
//   ...complete content...
//   ```
//
//   types: html | svg | markdown | code:<lang> | mermaid | csv
//   e.g.   ```artifact:html:Pricing page      ```artifact:code:python:Fizzbuzz
//
// Why a client-side fenced convention rather than a tool: it needs zero server
// work, survives every provider, streams naturally, and the transcript stays
// human-readable. The cost is that the model is untrusted - so this parser is
// PURE, total (never throws, linear time) and treats every byte of the body as
// hostile data: it only SPLITS the text, it never interprets the content.
//
// Fence rules follow CommonMark where the model is likely to rely on them:
//  - an opening fence is 3+ backticks (or tildes), up to 3 spaces of indent;
//  - it closes on a bare fence of the same character and at least the same
//    length (so a model that wraps a Markdown artifact containing ``` code in
//    a ```` four-backtick fence is handled exactly);
//  - ordinary (non-artifact) code blocks are tracked too, so an `artifact:`
//    line shown INSIDE a normal code block (a model explaining the convention)
//    is not mistaken for a real artifact.
// One deliberate deviation: for a 3-backtick artifact fence, an inner fence
// line that carries an info string (```js) opens a nested block and the next
// bare fence closes THAT block, not the artifact - models routinely nest 3-on-3.
// A nested block opened with a BARE ``` cannot be told apart from the closing
// fence and ends the artifact early; the directive tells models to use a longer
// outer fence when the content itself contains fences.
//
// Streaming: a block with no closing fence yet is returned with
// complete:false (the UI shows "drafting..."); a half-streamed closing fence
// ("``") or header line is never shown as content.

import {
  MAX_ARTIFACT_CHARS,
  MAX_TITLE_CHARS,
  normalizeKind,
  type ArtifactKind,
  type ParsedArtifact,
} from "./artifactTypes";

export type ArtifactSegment =
  | { kind: "text"; text: string }
  | { kind: "artifact"; artifact: ParsedArtifact; blockIndex: number };

/** Parsing is skipped (one text segment) beyond this many characters of message text. */
export const MAX_PARSE_CHARS = 4_000_000;
/** Artifacts honoured per message; further `artifact:` fences stay ordinary code blocks. */
export const MAX_ARTIFACTS_PER_MESSAGE = 20;

const FENCE_OPEN = /^ {0,3}(`{3,}|~{3,})([^\n]*)$/;
const FENCE_BARE = /^ {0,3}(`{3,}|~{3,})[ \t]*$/;
const FENCE_NESTED_OPEN = /^ {0,3}(`{3,})[ \t]*([^`\s][^\n]*)$/;
// eslint-disable-next-line no-control-regex
const CONTROL_CHARS = /[\u0000-\u001f\u007f-\u009f]/g;
// Bidi override/isolate/mark code points (U+202A-202E, U+2066-2069, U+200E-200F). A title using them
// could spoof a filename in the UI, so they are removed. Built from code points (not literals) so the
// source file itself carries no invisible characters.
const BIDI_CODEPOINTS = [0x202a, 0x202b, 0x202c, 0x202d, 0x202e, 0x2066, 0x2067, 0x2068, 0x2069, 0x200e, 0x200f];
const BIDI_CHARS = new RegExp("[" + BIDI_CODEPOINTS.map((c) => String.fromCharCode(c)).join("") + "]", "g");

const PARTIAL_OPENER_TAIL = /(^|\n) {0,3}[`~]{3,}[ \t]*artifact$/i;

/** Cheap pre-check so ordinary messages never pay for a parse. */
export function mightContainArtifact(text: unknown): boolean {
  return typeof text === "string" && text.length > 0 && /artifact:/i.test(text);
}

export function sanitizeTitle(raw: string, kind: ArtifactKind): string {
  const cleaned = String(raw || "")
    .replace(CONTROL_CHARS, " ")
    .replace(BIDI_CHARS, "")
    .replace(/\s+/g, " ")
    .trim()
    .slice(0, MAX_TITLE_CHARS)
    .trim();
  return cleaned || `Untitled ${kind}`;
}

const LANG_TOKEN = /^[A-Za-z0-9+#._-]{1,24}$/;

export function sanitizeLang(raw: string | null | undefined): string {
  const l = String(raw || "").trim();
  return LANG_TOKEN.test(l) ? l.toLowerCase() : "text";
}

export interface ArtifactHeader {
  kind: ArtifactKind;
  lang: string | null;
  title: string;
}

/** Parse the info string after the fence. Null when it is not an artifact header (unknown type => ordinary code block). */
export function parseArtifactHeader(info: string): ArtifactHeader | null {
  const trimmed = String(info || "").trim();
  if (!/^artifact:/i.test(trimmed)) return null;
  const parts = trimmed.slice("artifact:".length).split(":");
  const kind = normalizeKind(parts[0]);
  if (!kind) return null;
  if (kind === "code") {
    // Header drift: models often omit the language ("artifact:code:My script"). When the second field is
    // not a plausible language token it is the START OF THE TITLE (language falls back to "text"); dropping
    // it would give every such block the same "Untitled code" id and silently merge them as versions.
    if (parts.length > 1 && !LANG_TOKEN.test(parts[1].trim())) {
      return { kind, lang: "text", title: sanitizeTitle(parts.slice(1).join(":"), kind) };
    }
    const lang = sanitizeLang(parts[1]);
    return { kind, lang, title: sanitizeTitle(parts.slice(2).join(":"), kind) };
  }
  return { kind, lang: null, title: sanitizeTitle(parts.slice(1).join(":"), kind) };
}

/** An info string that could still grow into an artifact header: "", "art", "artifact:ht", "artifact:html". */
function isPartialHeader(info: string): boolean {
  const s = String(info || "").trim().toLowerCase();
  return s === "" || "artifact:".startsWith(s) || /^artifact:[a-z]*$/.test(s);
}

function isCloser(line: string, ch: string, len: number): boolean {
  const m = FENCE_BARE.exec(line);
  return m !== null && m[1][0] === ch && m[1].length >= len;
}

function flushText(out: ArtifactSegment[], buf: string[]): void {
  if (buf.length === 0) return;
  const text = buf.join("\n").replace(/^\n+|\n+$/g, "");
  buf.length = 0;
  if (text.trim() !== "") out.push({ kind: "text", text });
}

const PARSE_CACHE_ENTRIES = 24;
const PARSE_CACHE = new Map<string, ArtifactSegment[]>();

/**
 * Split message text into ordered text / artifact segments. Total: any string
 * (and any non-string) yields a result; never throws; O(n) in the text length.
 * When no artifact is present the result is a single text segment holding the
 * original text unchanged (so callers can render it exactly as before).
 */
export function parseArtifactSegments(input: unknown): ArtifactSegment[] {
  if (typeof input !== "string" || input === "") return [];
  // A stream cut right after "```artifact" (colon not typed yet) must not flash the raw fence either.
  if (input.length > MAX_PARSE_CHARS || !(mightContainArtifact(input) || PARTIAL_OPENER_TAIL.test(input))) {
    return [{ kind: "text", text: input }];
  }
  // The registry rebuild and the message body both parse the SAME text on every render, and a long
  // transcript re-renders per streamed token: memoise by content so each distinct text is split once.
  // Callers treat the result as read-only.
  const hit = PARSE_CACHE.get(input);
  if (hit) {
    PARSE_CACHE.delete(input);
    PARSE_CACHE.set(input, hit); // refresh recency
    return hit;
  }
  const parsed = parseUncached(input);
  PARSE_CACHE.set(input, parsed);
  if (PARSE_CACHE.size > PARSE_CACHE_ENTRIES) {
    const oldest = PARSE_CACHE.keys().next();
    if (!oldest.done) PARSE_CACHE.delete(oldest.value);
  }
  return parsed;
}

function parseUncached(input: string): ArtifactSegment[] {
  const lines = input.replace(/\r\n?/g, "\n").split("\n");
  const out: ArtifactSegment[] = [];
  const textBuf: string[] = [];
  let blockIndex = 0;
  let sawArtifact = false;
  let skippedHeader = false;
  let i = 0;

  while (i < lines.length) {
    const line = lines[i];
    const open = FENCE_OPEN.exec(line);
    // A backtick fence's info string may not contain a backtick (that is inline code, not a fence).
    if (!open || (open[1][0] === "`" && open[2].includes("`"))) {
      textBuf.push(line);
      i++;
      continue;
    }
    const fenceChar = open[1][0];
    const fenceLen = open[1].length;
    const header = blockIndex < MAX_ARTIFACTS_PER_MESSAGE ? parseArtifactHeader(open[2]) : null;

    if (!header) {
      // Streaming nicety: a header typed so far ("```artifact:ht") on the very last
      // line is the start of an artifact, not a code block - show nothing yet.
      if (i === lines.length - 1 && blockIndex < MAX_ARTIFACTS_PER_MESSAGE && isPartialHeader(open[2])) {
        skippedHeader = true;
        i++;
        continue;
      }
      // Ordinary fenced block: copy through verbatim so an `artifact:` line inside it stays inert.
      textBuf.push(line);
      i++;
      while (i < lines.length) {
        textBuf.push(lines[i]);
        const closed = isCloser(lines[i], fenceChar, fenceLen);
        i++;
        if (closed) break;
      }
      continue;
    }

    // Header streamed so far on the final line: the title may still be growing. Wait for the newline.
    if (i === lines.length - 1) {
      skippedHeader = true;
      i++;
      continue;
    }

    // Artifact block.
    const body: string[] = [];
    let bodyChars = 0;
    let truncated = false;
    let complete = false;
    let depth = 0;
    const nestable = fenceChar === "`" && fenceLen === 3;
    i++;
    while (i < lines.length) {
      const l = lines[i];
      if (nestable) {
        if (depth > 0) {
          if (FENCE_BARE.test(l)) depth--;
        } else if (FENCE_NESTED_OPEN.test(l)) {
          depth++;
        } else if (isCloser(l, fenceChar, fenceLen)) {
          complete = true;
          i++;
          break;
        }
      } else if (isCloser(l, fenceChar, fenceLen)) {
        complete = true;
        i++;
        break;
      }
      if (!truncated) {
        bodyChars += l.length + 1;
        if (bodyChars > MAX_ARTIFACT_CHARS) {
          truncated = true;
        } else {
          body.push(l);
        }
      }
      i++;
    }
    if (!complete && body.length > 0 && /^ {0,3}(`{1,2}|~{1,2})[ \t]*$/.test(body[body.length - 1])) {
      body.pop(); // half-streamed closing fence
    }
    flushText(out, textBuf);
    sawArtifact = true;
    out.push({
      kind: "artifact",
      blockIndex: blockIndex++,
      artifact: {
        kind: header.kind,
        lang: header.lang,
        title: header.title,
        content: body.join("\n"),
        complete,
        truncated,
      },
    });
  }

  if (!sawArtifact && !skippedHeader) return [{ kind: "text", text: input }];
  flushText(out, textBuf);
  return out;
}

/** Just the artifact blocks of a message, in order. */
export function parseArtifacts(input: unknown): ParsedArtifact[] {
  const result: ParsedArtifact[] = [];
  for (const s of parseArtifactSegments(input)) {
    if (s.kind === "artifact") result.push(s.artifact);
  }
  return result;
}
