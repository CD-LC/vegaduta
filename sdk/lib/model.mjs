// Turns an OpenAPI 3.x document into a small, language-neutral model that both emitters consume:
//   { schemas: [{ key, name, schema }], typeNames: Map, groups: [{ tag, ... operations: [...] }] }
// Pure: no I/O, no globals.

import { camel, pascal, snake, safeIdent, typeName, uniqueName, TS_RESERVED, PY_KEYWORDS } from "./names.mjs";

export const VERBS = ["get", "put", "post", "delete", "patch", "options", "head", "trace"];
const REF_PREFIX = "#/components/schemas/";
// Reserved words are legal as method and property names (client.agent.delete(id)); only digits need guarding.
const NO_RESERVED = new Set();

/** Resolve a local "#/a/b" JSON pointer inside the spec. */
export function resolveRef(spec, ref) {
  if (typeof ref !== "string" || !ref.startsWith("#/")) {
    throw new Error(`unsupported $ref: ${ref}`);
  }
  let cur = spec;
  for (const part of ref.slice(2).split("/")) {
    cur = cur?.[part.replace(/~1/g, "/").replace(/~0/g, "~")];
  }
  if (cur === undefined) throw new Error(`unresolved $ref: ${ref}`);
  return cur;
}

export function buildModel(spec) {
  const schemaKeys = Object.keys(spec.components?.schemas ?? {}).sort();
  const usedTypeNames = new Set();
  const typeNames = new Map();
  for (const key of schemaKeys) {
    typeNames.set(key, uniqueName(safeTypeName(key), usedTypeNames));
  }
  const schemas = schemaKeys.map((key) => ({
    key,
    name: typeNames.get(key),
    schema: spec.components.schemas[key],
  }));

  const operations = [];
  for (const path of Object.keys(spec.paths ?? {}).sort()) {
    const item = spec.paths[path] ?? {};
    for (const verb of VERBS) {
      if (item[verb]) {
        operations.push(parseOperation(spec, path, verb, item[verb], item.parameters ?? []));
      }
    }
  }

  // Group by tag; assign client property / class names and per-tag unique method names.
  const byTag = new Map();
  for (const op of operations) {
    if (!byTag.has(op.tag)) byTag.set(op.tag, []);
    byTag.get(op.tag).push(op);
  }
  const usedProps = new Set(["http"]);
  const usedClasses = new Set();
  const groups = [];
  for (const tag of [...byTag.keys()].sort()) {
    const ops = byTag.get(tag);
    const base = camel(tag) || "default";
    const tsProp = uniqueName(safeIdent(base, NO_RESERVED), usedProps);
    const pyAttr = safeIdent(snake(tag) || "default", PY_KEYWORDS);
    const className = uniqueName(`${pascal(tag) || "Default"}Api`, usedClasses);
    const usedTs = new Set(["http", "constructor"]);
    const usedPy = new Set(["self"]);
    for (const op of ops) {
      const method = methodPart(op);
      op.tsName = uniqueName(safeIdent(camel(method) || op.verb, NO_RESERVED), usedTs);
      op.pyName = uniqueName(safeIdent(snake(method) || op.verb, PY_KEYWORDS), usedPy);
      nameParams(op);
    }
    groups.push({ tag, tsProp, pyAttr, className, operations: ops });
  }
  // Python attribute names must stay unique across groups too.
  const usedPyAttrs = new Set(["http"]); // "http" is the client's own transport attribute
  for (const g of groups) g.pyAttr = uniqueName(g.pyAttr, usedPyAttrs);

  return { spec, schemas, typeNames, groups, info: spec.info ?? {} };
}

// Component names that would shadow a global or an import of the generated files.
const SHADOWED_TYPE_NAMES = new Set([
  "Array", "Blob", "Boolean", "Date", "FormData", "Headers", "Map", "Number", "Object", "Partial", "Promise",
  "Record", "Request", "Response", "Set", "String", "Any", "Callable", "Dict", "Iterator", "List", "Literal",
  "Optional", "Tuple", "TypedDict", "Union",
]);

/** Type name for a component key; "Error" becomes "ErrorBody", other shadowing names get a "Model" suffix. */
function safeTypeName(key) {
  const n = typeName(key);
  if (n === "Error") return "ErrorBody";
  return SHADOWED_TYPE_NAMES.has(n) ? `${n}Model` : n;
}

/** The controller-method part of "<Controller>_<method>[_n]". */
function methodPart(op) {
  const id = String(op.id ?? "");
  const i = id.indexOf("_");
  const rest = i >= 0 ? id.slice(i + 1) : id;
  return rest || op.verb;
}

function nameParams(op) {
  const tsUsed = new Set(["body", "options"]);
  const pyUsed = new Set(["self", "body", "m", "quote_path"]);
  for (const p of op.pathParams) {
    p.ts = uniqueName(safeIdent(camel(p.name) || "arg", TS_RESERVED), tsUsed);
    p.py = uniqueName(safeIdent(snake(p.name) || "arg", PY_KEYWORDS), pyUsed);
  }
  for (const p of [...op.queryParams, ...op.headerParams]) {
    p.py = uniqueName(safeIdent(snake(p.name) || "arg", PY_KEYWORDS), pyUsed);
  }
}

function parseOperation(spec, path, verb, op, sharedParams) {
  const merged = new Map();
  for (const raw of [...sharedParams, ...(op.parameters ?? [])]) {
    const p = raw.$ref ? resolveRef(spec, raw.$ref) : raw;
    merged.set(`${p.in}:${p.name}`, p);
  }
  const pathParams = [];
  const queryParams = [];
  const headerParams = [];
  for (const p of merged.values()) {
    const param = { name: p.name, in: p.in, required: p.required === true || p.in === "path", schema: p.schema ?? {} };
    if (p.in === "path") pathParams.push(param);
    else if (p.in === "query") queryParams.push(param);
    else if (p.in === "header") headerParams.push(param);
  }
  const order = [...path.matchAll(/\{([^}]+)\}/g)].map((m) => m[1]);
  pathParams.sort((a, b) => order.indexOf(a.name) - order.indexOf(b.name));
  return {
    id: op.operationId ?? `${verb}_${path}`,
    tag: (op.tags && op.tags[0]) || "Default",
    verb,
    path,
    summary: op.summary ?? "",
    description: op.description ?? "",
    deprecated: op.deprecated === true,
    pathParams,
    queryParams,
    headerParams,
    body: parseBody(spec, op.requestBody),
    response: parseResponse(op.responses),
  };
}

function parseBody(spec, requestBody) {
  if (!requestBody) return null;
  const rb = requestBody.$ref ? resolveRef(spec, requestBody.$ref) : requestBody;
  const content = rb.content ?? {};
  const types = Object.keys(content);
  if (types.length === 0) return null;
  const mediaType =
    types.find((t) => t === "application/json") ??
    types.find((t) => /json/.test(t)) ??
    types.find((t) => t === "multipart/form-data") ??
    types[0];
  const kind = /json/.test(mediaType)
    ? "json"
    : mediaType.startsWith("multipart/")
      ? "multipart"
      : mediaType.startsWith("text/")
        ? "text"
        : "raw";
  return { kind, mediaType, required: rb.required === true, schema: content[mediaType].schema ?? {} };
}

function parseResponse(responses = {}) {
  const codes = Object.keys(responses)
    .filter((c) => /^2\d\d$/.test(c))
    .sort();
  if (codes.length === 0) return { status: null, kind: "json", schema: {} };
  const status = codes[0];
  const content = responses[status]?.content ?? {};
  const types = Object.keys(content);
  if (types.includes("text/event-stream")) return { status, kind: "sse", schema: { type: "string" } };
  if (types.length === 0) return { status, kind: "void", schema: null };
  const json = types.find((t) => /json/.test(t));
  if (json) return { status, kind: "json", schema: content[json].schema ?? {} };
  if (types.some((t) => t.startsWith("text/"))) return { status, kind: "text", schema: { type: "string" } };
  return { status, kind: "blob", schema: { type: "string", format: "binary" } };
}

/** Component name referenced by a "$ref", mapped through the model's sanitized type names. */
export function refKey(ref) {
  if (typeof ref === "string" && ref.startsWith(REF_PREFIX)) return ref.slice(REF_PREFIX.length);
  throw new Error(`unsupported $ref (only #/components/schemas/* is generated): ${ref}`);
}

/** Single-line text safe to embed in a comment. */
export function oneLine(text) {
  return String(text ?? "")
    .replace(/\*\//g, "* /")
    .replace(/\s+/g, " ")
    .trim();
}
