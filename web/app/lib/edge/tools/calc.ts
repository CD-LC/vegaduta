// local_calculate: the exact arithmetic parser/evaluator (no eval). Split out of ../localTools.ts.
// Pure computation: runtime imports are limited to sibling pure modules.

import { MAX_PAREN_DEPTH, refuse } from "./common";

// ---------------------------------------------------------------------------
// local_calculate — recursive-descent arithmetic
//
// NO eval, NO new Function. The expression is a string an LLM produced from
// text a stranger typed; handing it to a JS evaluator would make every local
// tool a script-injection surface, and no amount of pre-filtering makes that
// safe. A parser over a fixed token set can only ever produce a number.
//
// Grammar (unary binds looser than ^, so -2^2 is -(2^2) = -4, the convention
// every maths textbook and spreadsheet uses):
//   expr    := term (("+" | "-") term)*
//   term    := unary (("*" | "/" | "%") unary)*
//   unary   := ("-" | "+") unary | power
//   power   := primary ("^" unary)?            // right-associative
//   primary := number | ident | ident "(" expr ("," expr)* ")" | "(" expr ")"
// ---------------------------------------------------------------------------

export type Token =
  | { kind: "num"; value: number }
  | { kind: "ident"; value: string }
  | { kind: "op"; value: string };

export const CONSTANTS: Record<string, number> = {
  pi: Math.PI,
  e: Math.E,
};

/** Arity is checked, so a mistyped call refuses instead of quietly reading
 * NaN off a missing argument. -1 = variadic (at least one). */
export const FUNCTIONS: Record<string, { arity: number; fn: (args: number[]) => number }> = {
  abs: { arity: 1, fn: (a) => Math.abs(a[0]) },
  sqrt: { arity: 1, fn: (a) => Math.sqrt(a[0]) },
  round: { arity: 1, fn: (a) => Math.round(a[0]) },
  floor: { arity: 1, fn: (a) => Math.floor(a[0]) },
  ceil: { arity: 1, fn: (a) => Math.ceil(a[0]) },
  ln: { arity: 1, fn: (a) => Math.log(a[0]) },
  log10: { arity: 1, fn: (a) => Math.log10(a[0]) },
  exp: { arity: 1, fn: (a) => Math.exp(a[0]) },
  pow: { arity: 2, fn: (a) => Math.pow(a[0], a[1]) },
  min: { arity: -1, fn: (a) => Math.min(...a) },
  max: { arity: -1, fn: (a) => Math.max(...a) },
};

export const CALC_VOCABULARY =
  "+ - * / % ^, parentheses, abs sqrt round floor ceil ln log10 exp pow min max, pi e";

export function tokenizeExpression(source: string): Token[] {
  const tokens: Token[] = [];
  let i = 0;
  while (i < source.length) {
    const ch = source[i];
    if (ch === " " || ch === "\t" || ch === "\n" || ch === "\r") {
      i += 1;
      continue;
    }
    if (ch >= "0" && ch <= "9") {
      const match = /^\d+(\.\d+)?([eE][+-]?\d+)?/.exec(source.slice(i));
      if (!match) refuse(`Could not read a number at position ${i} of the expression.`);
      tokens.push({ kind: "num", value: Number(match[0]) });
      i += match[0].length;
      continue;
    }
    if (ch === ".") {
      const match = /^\.\d+([eE][+-]?\d+)?/.exec(source.slice(i));
      if (!match) refuse(`Stray "." at position ${i} of the expression.`);
      tokens.push({ kind: "num", value: Number(match[0]) });
      i += match[0].length;
      continue;
    }
    if (/[a-zA-Z_]/.test(ch)) {
      const match = /^[a-zA-Z_][a-zA-Z_0-9]*/.exec(source.slice(i));
      // The class test above guarantees a match; the guard is for the reader.
      const name = match ? match[0] : ch;
      tokens.push({ kind: "ident", value: name.toLowerCase() });
      i += name.length;
      continue;
    }
    if ("+-*/%^(),".includes(ch)) {
      tokens.push({ kind: "op", value: ch });
      i += 1;
      continue;
    }
    refuse(`"${ch}" is not something this calculator understands. Supported: ${CALC_VOCABULARY}.`);
  }
  return tokens;
}

export function evaluateExpression(source: string): number {
  const tokens = tokenizeExpression(source);
  if (tokens.length === 0) refuse("The expression is empty.");
  let pos = 0;
  let depth = 0;

  const peek = (): Token | null => (pos < tokens.length ? tokens[pos] : null);
  const isOp = (value: string): boolean => {
    const token = peek();
    return token !== null && token.kind === "op" && token.value === value;
  };

  function parseExpr(): number {
    let left = parseTerm();
    for (;;) {
      if (isOp("+")) {
        pos += 1;
        left += parseTerm();
      } else if (isOp("-")) {
        pos += 1;
        left -= parseTerm();
      } else {
        return left;
      }
    }
  }

  function parseTerm(): number {
    let left = parseUnary();
    for (;;) {
      if (isOp("*")) {
        pos += 1;
        left *= parseUnary();
      } else if (isOp("/")) {
        pos += 1;
        left /= parseUnary();
      } else if (isOp("%")) {
        pos += 1;
        left %= parseUnary();
      } else {
        return left;
      }
    }
  }

  function parseUnary(): number {
    if (isOp("-")) {
      pos += 1;
      return -parseUnary();
    }
    if (isOp("+")) {
      pos += 1;
      return parseUnary();
    }
    return parsePower();
  }

  function parsePower(): number {
    const base = parsePrimary();
    if (isOp("^")) {
      pos += 1;
      // Right-associative, and the exponent goes through unary so 2^-1 works.
      return Math.pow(base, parseUnary());
    }
    return base;
  }

  function parsePrimary(): number {
    const token = peek();
    if (token === null) refuse("The expression ends where a number was expected.");
    if (token.kind === "num") {
      pos += 1;
      return token.value;
    }
    if (token.kind === "ident") {
      pos += 1;
      const name = token.value;
      if (isOp("(")) {
        const fn = FUNCTIONS[name];
        if (!fn) {
          refuse(`"${name}" is not a function this calculator has. Supported: ${CALC_VOCABULARY}.`);
        }
        pos += 1;
        depth += 1;
        if (depth > MAX_PAREN_DEPTH) refuse(`The expression nests deeper than ${MAX_PAREN_DEPTH} levels.`);
        const args: number[] = [parseExpr()];
        while (isOp(",")) {
          pos += 1;
          args.push(parseExpr());
        }
        if (!isOp(")")) refuse(`Missing ")" after the arguments to ${name}().`);
        pos += 1;
        depth -= 1;
        if (fn.arity === -1) {
          if (args.length < 1) refuse(`${name}() needs at least one argument.`);
        } else if (args.length !== fn.arity) {
          refuse(`${name}() takes ${fn.arity} argument(s), got ${args.length}.`);
        }
        return fn.fn(args);
      }
      if (name in CONSTANTS) return CONSTANTS[name];
      if (FUNCTIONS[name]) refuse(`"${name}" is a function — write ${name}(x).`);
      refuse(`"${name}" is not a name this calculator knows. Supported: ${CALC_VOCABULARY}.`);
    }
    if (token.kind === "op" && token.value === "(") {
      pos += 1;
      depth += 1;
      if (depth > MAX_PAREN_DEPTH) refuse(`The expression nests deeper than ${MAX_PAREN_DEPTH} levels.`);
      const value = parseExpr();
      if (!isOp(")")) refuse('Missing ")".');
      pos += 1;
      depth -= 1;
      return value;
    }
    // Only an operator token can reach here — num and ident both returned or
    // refused above — so its value is already the character to quote.
    refuse(`Unexpected "${token.value}" in the expression.`);
  }

  const result = parseExpr();
  if (pos < tokens.length) {
    const extra = tokens[pos];
    refuse(`Unexpected "${extra.kind === "op" ? extra.value : String(extra.value)}" after a complete expression.`);
  }
  // NaN and ±Infinity are answers a model will happily repeat as fact. 1/0 and
  // sqrt(-1) are user-visible mistakes, not tool failures, so they refuse.
  if (!Number.isFinite(result)) {
    refuse(
      Number.isNaN(result)
        ? "That expression is not a number (check for sqrt of a negative, or 0/0)."
        : "That expression is infinite (check for division by zero)."
    );
  }
  return result;
}

