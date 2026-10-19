// Shared lexicon of the workflow expression language: reserved roots, keywords,
// identifier classes and string-literal quoting. Used by the syntax, path and
// completion modules; re-exported publicly through ./expressionLang.
// ------------------------------------------------------------------ constants

export const ROOTS = [
  "$json",
  "$input",
  "$node",
  "$trigger",
  "$run",
  "$workflow",
  "$vars",
  "$item",
  "$index",
  "$items",
  "$now",
] as const;
export const ROOT_SET: ReadonlySet<string> = new Set(ROOTS);

/** One-line descriptions of the reserved roots (spec section 3.2), shown in autocomplete. */
export const ROOT_DOCS: Record<string, string> = {
  $json: "This node's input, parsed if it is JSON",
  $input: "This node's raw input text",
  $node: "Output of an upstream node",
  $trigger: "The trigger payload",
  $run: "id, startedAt, mode, requestedBy",
  $workflow: "id, name, version",
  $vars: "Workflow variables",
  $item: "Current item inside a loop or fan-out",
  $index: "Current position inside a loop or fan-out",
  $items: "All items of the loop or fan-out",
  $now: "The evaluation instant",
};

export const ROOT_MEMBERS: Record<string, string[]> = {
  $run: ["id", "startedAt", "mode", "requestedBy"],
  $workflow: ["id", "name", "version"],
};
// Members every `$node["X"]` reference offers regardless of the output's shape.
export const NODE_META_MEMBERS = ["output", "status", "summary"];

export const KEYWORDS: ReadonlySet<string> = new Set(["true", "false", "null"]);

/** Prefix length added by wrapBare, i.e. how far to shift positions the server reports. */
export const BARE_PREFIX_LEN = 3;

/** A bare expression (condition node) sent to the server as a one-segment template. */
export function wrapBare(expr: string): string {
  return `{{ ${expr} }}`;
}

export const IDENT_START = /[\p{L}_$]/u;
export const IDENT_PART = /[\p{L}\p{N}_$]/u;

// -------------------------------------------------------------------- strings

/** Quotes `s` as an expression string literal. */
export function quoteString(s: string, quote: '"' | "'" = '"'): string {
  let out = quote;
  for (const ch of s) {
    if (ch === "\\") out += "\\\\";
    else if (ch === quote) out += `\\${quote}`;
    else if (ch === "\n") out += "\\n";
    else if (ch === "\r") out += "\\r";
    else if (ch === "\t") out += "\\t";
    else out += ch;
  }
  return out + quote;
}

/** Inverse of quoteString for a complete literal (quotes included). Lenient about unknown escapes. */
export function unquoteString(literal: string): string {
  if (literal.length < 2) return "";
  const body = literal.slice(1, literal.endsWith(literal[0]) ? -1 : undefined);
  let out = "";
  for (let i = 0; i < body.length; i++) {
    const ch = body[i];
    if (ch !== "\\" || i === body.length - 1) {
      out += ch;
      continue;
    }
    const next = body[++i];
    if (next === "n") out += "\n";
    else if (next === "r") out += "\r";
    else if (next === "t") out += "\t";
    else if (next === "u" && /^[0-9a-fA-F]{4}$/.test(body.slice(i + 1, i + 5))) {
      out += String.fromCharCode(parseInt(body.slice(i + 1, i + 5), 16));
      i += 4;
    } else out += next;
  }
  return out;
}

export const isIdentName = (s: string) => /^[\p{L}_$][\p{L}\p{N}_$]*$/u.test(s) && !KEYWORDS.has(s);

/** Is the end of `text` inside an unterminated string literal? Where did it start? */
export function scanString(text: string): { quote: string | null; start: number } {
  let quote: string | null = null;
  let start = -1;
  for (let i = 0; i < text.length; i++) {
    const c = text[i];
    if (quote) {
      if (c === "\\") i++;
      else if (c === quote) quote = null;
    } else if (c === '"' || c === "'") {
      quote = c;
      start = i;
    }
  }
  return { quote, start: quote ? start : -1 };
}
