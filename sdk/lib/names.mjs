// Identifier helpers shared by the TypeScript and Python emitters. Pure functions, no I/O.

/** Split any identifier-ish string into lower-case words ("X-Trace" -> ["x","trace"]). */
export function words(input) {
  return String(input)
    .replace(/([a-z0-9])([A-Z])/g, "$1 $2")
    .replace(/([A-Z]+)([A-Z][a-z])/g, "$1 $2")
    .split(/[^A-Za-z0-9]+/)
    .filter(Boolean)
    .map((w) => w.toLowerCase());
}

export function camel(input) {
  return words(input)
    .map((w, i) => (i === 0 ? w : w[0].toUpperCase() + w.slice(1)))
    .join("");
}

export function pascal(input) {
  return words(input)
    .map((w) => w[0].toUpperCase() + w.slice(1))
    .join("");
}

export function snake(input) {
  return words(input).join("_");
}

export const TS_RESERVED = new Set([
  "break", "case", "catch", "class", "const", "continue", "debugger", "default", "delete", "do", "else",
  "enum", "export", "extends", "false", "finally", "for", "function", "if", "import", "in", "instanceof",
  "new", "null", "return", "super", "switch", "this", "throw", "true", "try", "typeof", "var", "void",
  "while", "with", "yield", "let", "static", "implements", "interface", "package", "private", "protected",
  "public", "await", "async", "of", "type", "arguments", "eval",
]);

export const PY_KEYWORDS = new Set([
  "False", "None", "True", "and", "as", "assert", "async", "await", "break", "class", "continue", "def",
  "del", "elif", "else", "except", "finally", "for", "from", "global", "if", "import", "in", "is", "lambda",
  "nonlocal", "not", "or", "pass", "raise", "return", "try", "while", "with", "yield",
]);

/** A valid (non-reserved) JS identifier derived from `name`, or `_name` when needed. */
export function safeIdent(name, reserved) {
  let n = String(name);
  if (n === "") n = "_";
  if (/^[0-9]/.test(n)) n = `_${n}`;
  return reserved.has(n) ? `${n}_` : n;
}

/** Type/class name for an OpenAPI component key ("Outer.Inner-x" -> "Outer_Inner_x"). */
export function typeName(componentKey) {
  let n = String(componentKey).replace(/[^A-Za-z0-9_]/g, "_");
  if (/^[0-9]/.test(n)) n = `_${n}`;
  return n;
}

/** True when `name` can be written bare as an object key / class attribute. */
export function isPlainIdent(name) {
  return /^[A-Za-z_$][A-Za-z0-9_$]*$/.test(name);
}

/** Make `candidate` unique inside `used` (a Set), appending 2, 3, ... and recording it. */
export function uniqueName(candidate, used) {
  let name = candidate;
  let n = 2;
  while (used.has(name)) name = `${candidate}${n++}`;
  used.add(name);
  return name;
}
