// Syntax side of the expression editor: {{ }} segments, the tokenizer, the
// highlight spans of the mirror layer and the cheap client-side checks.
// Nothing here may throw on malformed input.
import { IDENT_PART, IDENT_START, KEYWORDS, ROOT_SET } from "./expressionLexicon";

// ------------------------------------------------------------------- segments

export type Segment = {
  /** Index of the opening "{{". */
  start: number;
  /** Index just past the closing "}}" (or the end of the string when unclosed). */
  end: number;
  innerStart: number;
  innerEnd: number;
  closed: boolean;
};

/**
 * Finds every {{ ... }} segment. Braces and "}}" inside string literals do not
 * close a segment, and object literals nest ({{ {a: {b: 1}} }} is one segment),
 * so `}}` only closes when no literal brace is open. Text outside segments is
 * literal, including stray "}}" (JSON bodies end in them all the time).
 */
export function findSegments(s: string): Segment[] {
  const out: Segment[] = [];
  let i = 0;
  while (i < s.length) {
    const open = s.indexOf("{{", i);
    if (open < 0) break;
    const innerStart = open + 2;
    let j = innerStart;
    let depth = 0;
    let quote: string | null = null;
    let closeAt = -1;
    while (j < s.length) {
      const c = s[j];
      if (quote) {
        if (c === "\\") j += 2;
        else {
          if (c === quote) quote = null;
          j++;
        }
        continue;
      }
      if (c === '"' || c === "'") quote = c;
      else if (c === "{") depth++;
      else if (c === "}") {
        if (depth > 0) depth--;
        else if (s[j + 1] === "}") {
          closeAt = j;
          break;
        }
      }
      j++;
    }
    if (closeAt >= 0) {
      out.push({ start: open, end: closeAt + 2, innerStart, innerEnd: closeAt, closed: true });
      i = closeAt + 2;
    } else {
      out.push({ start: open, end: s.length, innerStart, innerEnd: s.length, closed: false });
      break;
    }
  }
  return out;
}

/** True when the value carries at least one {{ }} segment (the Expression mode of the field toggle). */
export function hasExpression(value: string | null | undefined): boolean {
  return !!value && findSegments(value).length > 0;
}

/** The segment whose inside contains `pos` (caret between the braces, edges included). */
export function findSegmentAt(s: string, pos: number): Segment | null {
  for (const seg of findSegments(s)) {
    if (pos >= seg.innerStart && pos <= (seg.closed ? seg.innerEnd : seg.end)) return seg;
  }
  return null;
}

// ------------------------------------------------------------------ tokenizer

export type TokenKind =
  | "string"
  | "number"
  | "keyword"
  | "root"
  | "function"
  | "ident"
  | "operator"
  | "punct"
  | "error";

export type Token = { kind: TokenKind; start: number; end: number; text: string; unterminated?: boolean };

const OPERATORS = ["===", "!==", "==", "!=", "<=", ">=", "&&", "||", "??", "?.", "=>", "+", "-", "*", "/", "%", "<", ">", "!", "?", ":"];
const PUNCT = new Set(["(", ")", "[", "]", "{", "}", ",", "."]);

/** Tokenizes expression source (the inside of one segment). `offset` shifts positions into template space. */
export function tokenize(src: string, offset = 0): Token[] {
  const out: Token[] = [];
  let i = 0;
  const push = (kind: TokenKind, from: number, to: number, extra: Partial<Token> = {}) =>
    out.push({ kind, start: from + offset, end: to + offset, text: src.slice(from, to), ...extra });
  while (i < src.length) {
    const c = src[i];
    if (/\s/.test(c)) {
      i++;
      continue;
    }
    if (c === '"' || c === "'") {
      let j = i + 1;
      let closed = false;
      while (j < src.length) {
        if (src[j] === "\\") {
          j += 2;
          continue;
        }
        if (src[j] === c) {
          closed = true;
          j++;
          break;
        }
        j++;
      }
      j = Math.min(j, src.length);
      if (closed) push("string", i, j);
      else push("error", i, j, { unterminated: true });
      i = j;
      continue;
    }
    if (/\d/.test(c)) {
      const m = /^\d+(\.\d+)?([eE][+-]?\d+)?/.exec(src.slice(i))!;
      push("number", i, i + m[0].length);
      i += m[0].length;
      continue;
    }
    if (IDENT_START.test(c)) {
      let j = i + 1;
      while (j < src.length && IDENT_PART.test(src[j])) j++;
      const word = src.slice(i, j);
      let kind: TokenKind = "ident";
      if (ROOT_SET.has(word)) kind = "root";
      else if (KEYWORDS.has(word)) kind = "keyword";
      else if (/^\s*\(/.test(src.slice(j))) kind = "function";
      push(kind, i, j);
      i = j;
      continue;
    }
    const op = OPERATORS.find((o) => src.startsWith(o, i));
    if (op) {
      push("operator", i, i + op.length);
      i += op.length;
      continue;
    }
    if (PUNCT.has(c)) {
      push("punct", i, i + 1);
      i++;
      continue;
    }
    push("error", i, i + 1);
    i++;
  }
  return out;
}

// ------------------------------------------------------------------ highlight

export type HighlightKind = TokenKind | "text" | "brace" | "plain";
export type HighlightSpan = { kind: HighlightKind; start: number; end: number };

/**
 * Contiguous spans covering the whole template (gaps become "plain"/"text"),
 * for the highlighted mirror layer behind the textarea.
 */
export function highlightTemplate(template: string): HighlightSpan[] {
  const spans: HighlightSpan[] = [];
  let cursor = 0;
  const gap = (to: number, kind: HighlightKind) => {
    if (to > cursor) spans.push({ kind, start: cursor, end: to });
    cursor = Math.max(cursor, to);
  };
  for (const seg of findSegments(template)) {
    gap(seg.start, "text");
    spans.push({ kind: "brace", start: seg.start, end: seg.start + 2 });
    cursor = seg.start + 2;
    const inner = template.slice(seg.innerStart, seg.innerEnd);
    for (const tok of tokenize(inner, seg.innerStart)) {
      gap(tok.start, "plain");
      spans.push({ kind: tok.kind, start: tok.start, end: tok.end });
      cursor = tok.end;
    }
    gap(seg.innerEnd, "plain");
    if (seg.closed) {
      spans.push({ kind: "brace", start: seg.innerEnd, end: seg.end });
      cursor = seg.end;
    }
  }
  gap(template.length, "text");
  return spans;
}

/** Spans for a bare expression (no braces): the condition node's expression field. */
export function highlightExpression(src: string): HighlightSpan[] {
  const spans: HighlightSpan[] = [];
  let at = 0;
  for (const tok of tokenize(src)) {
    if (tok.start > at) spans.push({ kind: "plain", start: at, end: tok.start });
    spans.push({ kind: tok.kind, start: tok.start, end: tok.end });
    at = tok.end;
  }
  if (at < src.length) spans.push({ kind: "plain", start: at, end: src.length });
  return spans;
}

export type Marker = { start: number; end: number; severity: "error" | "warning" };
export type MarkedSpan = HighlightSpan & { marker?: "error" | "warning" };

/**
 * Splits highlight spans at marker boundaries and tags the covered pieces, so
 * the mirror layer can underline diagnostics. Errors win over warnings where
 * markers overlap; an empty marker is widened to one character.
 */
export function overlayMarkers(spans: HighlightSpan[], markers: Marker[], length: number): MarkedSpan[] {
  const norm = markers
    .map((m) => {
      const start = Math.max(0, Math.min(m.start, length));
      const end = Math.max(start, Math.min(m.end, length));
      return { start, end: end === start ? Math.min(start + 1, length) : end, severity: m.severity };
    })
    .filter((m) => m.end > m.start);
  if (!norm.length) return spans;
  const cuts = new Set<number>();
  for (const m of norm) {
    cuts.add(m.start);
    cuts.add(m.end);
  }
  const out: MarkedSpan[] = [];
  for (const sp of spans) {
    let from = sp.start;
    for (const c of [...cuts].sort((a, b) => a - b)) {
      if (c > from && c < sp.end) {
        out.push({ ...sp, start: from, end: c });
        from = c;
      }
    }
    out.push({ ...sp, start: from, end: sp.end });
  }
  for (const piece of out) {
    let marker: MarkedSpan["marker"];
    for (const m of norm) {
      if (piece.start >= m.start && piece.end <= m.end) {
        if (m.severity === "error") marker = "error";
        else if (!marker) marker = "warning";
      }
    }
    if (marker) piece.marker = marker;
  }
  return out;
}

// --------------------------------------------------------------- quick checks

export type QuickDiagnostic = {
  code: "UNCLOSED_BRACES" | "EMPTY_EXPRESSION" | "UNTERMINATED_STRING" | "UNEXPECTED_CHARACTER" | "UNBALANCED_BRACKET";
  severity: "error" | "warning";
  /** English fallback; the UI maps `code` through i18n. */
  message: string;
  start: number;
  end: number;
};

const CLOSER: Record<string, string> = { "(": ")", "[": "]", "{": "}" };

/**
 * Cheap client-side syntax checks that need no server: unclosed braces, empty
 * segments, unterminated strings, stray characters and unbalanced brackets.
 * Full parse errors, unknown functions and unknown nodes come from the server lint.
 */
export function quickCheckTemplate(template: string): QuickDiagnostic[] {
  const out: QuickDiagnostic[] = [];
  for (const seg of findSegments(template)) {
    if (!seg.closed) {
      out.push({ code: "UNCLOSED_BRACES", severity: "error", message: 'Missing closing "}}".', start: seg.start, end: Math.min(seg.start + 2, template.length) });
    }
    const inner = template.slice(seg.innerStart, seg.innerEnd);
    if (inner.trim() === "") {
      if (seg.closed) out.push({ code: "EMPTY_EXPRESSION", severity: "warning", message: "Empty expression.", start: seg.start, end: seg.end });
      continue;
    }
    const stack: Token[] = [];
    for (const tok of tokenize(inner, seg.innerStart)) {
      if (tok.kind === "error") {
        out.push(
          tok.unterminated
            ? { code: "UNTERMINATED_STRING", severity: "error", message: "Unterminated string.", start: tok.start, end: tok.end }
            : { code: "UNEXPECTED_CHARACTER", severity: "error", message: `Unexpected character "${tok.text}".`, start: tok.start, end: tok.end },
        );
      } else if (tok.kind === "punct" && CLOSER[tok.text]) {
        stack.push(tok);
      } else if (tok.kind === "punct" && (tok.text === ")" || tok.text === "]" || tok.text === "}")) {
        const open = stack.pop();
        if (!open || CLOSER[open.text] !== tok.text) {
          out.push({ code: "UNBALANCED_BRACKET", severity: "error", message: `Unmatched "${tok.text}".`, start: tok.start, end: tok.end });
          if (open) stack.push(open);
        }
      }
    }
    for (const open of stack) {
      out.push({ code: "UNBALANCED_BRACKET", severity: "error", message: `Unclosed "${open.text}".`, start: open.start, end: open.end });
    }
  }
  return out.sort((a, b) => a.start - b.start || a.end - b.end);
}

/** True when some segment reads data (a root, a bare name or a function over one), i.e. a preview needs sample data. */
export function referencesData(template: string): boolean {
  for (const seg of findSegments(template)) {
    for (const tok of tokenize(template.slice(seg.innerStart, seg.innerEnd))) {
      if (tok.kind === "root" || tok.kind === "ident") return true;
    }
  }
  return false;
}
