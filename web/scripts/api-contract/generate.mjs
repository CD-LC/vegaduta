// Builds the web console's consumer contract (ADR-0025) from source.
//
//   method + path   every call site extractCalls() finds, matched to an
//                   operation of docs/api/openapi.json (exact template first,
//                   then a whole-segment placeholder match)
//   status          the operation's declared 2xx (the console's toJson accepts any 2xx)
//   request fields  top-level keys of an inline object passed to jsonBody(...) / JSON.stringify(...)
//   response fields the non-optional top-level properties of T in `.then(toJson<T>)`,
//                   read with the TypeScript type checker (one level deep; `$[*].x` for arrays)
//
// A field the console reads or sends that the provider schema does not declare
// is drift: it is NOT written into the contract (the provider test would fail on
// it immediately) but listed in drift.json next to the contracts, so it stays
// visible and can be fixed on whichever side is wrong. drift.json only shrinks
// in review, the same rule as the ArchUnit store.
import fs from "node:fs";
import path from "node:path";
import ts from "typescript";
import { buildRegistry, extractFromSource } from "./calls.mjs";
import { listSourceFiles, normalizeTemplate, pathMatches } from "./extract.mjs";

const VERBS = ["get", "put", "post", "delete", "patch", "options", "head", "trace"];

function deref(spec, schema, seen = new Set()) {
  let s = schema ?? {};
  while (s && s.$ref) {
    if (seen.has(s.$ref)) return {};
    seen.add(s.$ref);
    const name = s.$ref.replace(/^#\/components\/(schemas|responses)\//, "");
    s = s.$ref.includes("/responses/") ? spec.components?.responses?.[name] : spec.components?.schemas?.[name];
  }
  return s ?? {};
}

/** Splits `$.a.b[*].c` into ["a", "b", "[*]", "c"]. */
export function fieldTokens(field) {
  if (!field.startsWith("$")) throw new Error(`field must start with $: ${field}`);
  const out = [];
  const re = /\.([A-Za-z0-9_$@-]+)|\[\*\]/g;
  let m;
  let pos = 1;
  while ((m = re.exec(field)) !== null) {
    if (m.index !== pos) throw new Error(`cannot parse field ${field}`);
    out.push(m[1] ?? "[*]");
    pos = re.lastIndex;
  }
  if (pos !== field.length) throw new Error(`cannot parse field ${field}`);
  return out;
}

/**
 * Whether the schema declares the field. Mirrors ConsumerContractVerificationTest:
 * $ref and allOf/oneOf/anyOf are followed; a free-form object (no `properties`)
 * accepts any name, because nothing more can be checked against it.
 */
export function schemaHasField(spec, schema, tokens, depth = 0) {
  if (depth > 40) return true;
  const s = deref(spec, schema);
  if (tokens.length === 0) return true;
  for (const key of ["allOf", "oneOf", "anyOf"]) {
    if (Array.isArray(s[key]) && s[key].some((m) => schemaHasField(spec, m, tokens, depth + 1))) return true;
  }
  const [tok, ...rest] = tokens;
  const types = Array.isArray(s.type) ? s.type : s.type ? [s.type] : [];
  if (tok === "[*]") {
    if (types.includes("array") || s.items) return schemaHasField(spec, s.items ?? {}, rest, depth + 1);
    return types.length === 0 && !s.properties;
  }
  if (s.properties && Object.prototype.hasOwnProperty.call(s.properties, tok)) {
    return schemaHasField(spec, s.properties[tok], rest, depth + 1);
  }
  if (s.properties && Object.keys(s.properties).length > 0) return false;
  return (types.length === 0 || types.includes("object")) && !s.items && !s.allOf && !s.oneOf && !s.anyOf;
}

function bodySchema(spec, holder) {
  const h = deref(spec, holder);
  const content = h?.content;
  if (!content) return undefined;
  const json = content["application/json"] ?? Object.values(content)[0];
  return json?.schema ?? {};
}

function specIndex(spec) {
  const exact = new Map();
  const all = [];
  for (const [p, item] of Object.entries(spec.paths ?? {})) {
    const key = normalizeTemplate(p);
    if (!exact.has(key)) exact.set(key, []);
    exact.get(key).push(p);
    all.push(p);
  }
  return { exact, all };
}

/** Spec paths a call can address: the exact template when there is one, else every placeholder match (max 8). */
function resolveSpecPaths(spec, index, method, callPath) {
  const verb = method.toLowerCase();
  const has = (p) => spec.paths[p]?.[verb] != null;
  const exact = (index.exact.get(callPath) ?? []).filter(has);
  if (exact.length > 0) return exact;
  const fuzzy = index.all.filter((p) => has(p) && pathMatches(callPath, p));
  return fuzzy.length <= 8 ? fuzzy : [];
}

function successStatus(op) {
  const codes = Object.keys(op.responses ?? {})
    .filter((c) => /^2\d\d$/.test(c))
    .sort();
  return codes.length > 0 ? Number(codes[0]) : 200;
}

function typeFields(checker, typeNode) {
  const out = [];
  const walk = (type, prefix, depth) => {
    if (depth > 2) return;
    const t = checker.getNonNullableType(type);
    if (t.flags & (ts.TypeFlags.Any | ts.TypeFlags.Unknown)) return;
    if (checker.isArrayType(t)) {
      const [elem] = checker.getTypeArguments(t);
      if (elem) walk(elem, `${prefix}[*]`, depth + 1);
      return;
    }
    if (t.isUnion() || !(t.flags & ts.TypeFlags.Object)) return;
    for (const prop of checker.getPropertiesOfType(t)) {
      if (prop.flags & (ts.SymbolFlags.Optional | ts.SymbolFlags.Method)) continue;
      if (!/^[A-Za-z_$][A-Za-z0-9_$]*$/.test(prop.name)) continue;
      out.push(`${prefix}.${prop.name}`);
    }
  };
  walk(checker.getTypeFromTypeNode(typeNode), "$", 0);
  return out;
}

function slug(method, specPath) {
  const p = specPath
    .replace(/[{}]/g, "")
    .split("/")
    .filter(Boolean)
    .join("-")
    .replace(/[^A-Za-z0-9._-]+/g, "_");
  return `${method.toLowerCase()}-${p}`;
}

/** Contract file name for a path: the first segment after /api (and /api/v1), else the first segment. */
export function areaOf(specPath) {
  const segs = specPath.split("/").filter(Boolean);
  let i = 0;
  if (segs[i] === "api") i++;
  if (segs[i] === "v1") i++;
  const first = segs[i] ?? segs[0] ?? "root";
  // `/api/{resourceKind}/...`: a templated first segment has no area of its own.
  const area = (first.includes("{") ? "templated" : first).toLowerCase();
  return area.replace(/[^a-z0-9-]+/g, "-");
}

function createProgram(webRoot) {
  const configPath = path.join(webRoot, "tsconfig.json");
  const cfg = ts.readConfigFile(configPath, ts.sys.readFile);
  const parsed = ts.parseJsonConfigFileContent(cfg.config, ts.sys, webRoot);
  const files = listSourceFiles(webRoot).map((f) => path.join(webRoot, f));
  return ts.createProgram(files, { ...parsed.options, noEmit: true, incremental: false });
}

/**
 * Generates the web contract: { files: {name: doc}, drift, uncontracted }.
 * `spec` is the parsed docs/api/openapi.json.
 */
export function generate(webRoot, spec) {
  const program = createProgram(webRoot);
  const checker = program.getTypeChecker();
  const index = specIndex(spec);
  const sources = listSourceFiles(webRoot).map((file) => ({
    file,
    sf: program.getSourceFile(path.join(webRoot, file)),
  }));
  const registry = buildRegistry(sources.filter((s) => s.sf));
  const byKey = new Map();
  const drift = [];
  const unmatched = [];
  const dynamic = {};
  for (const file of listSourceFiles(webRoot)) {
    const sf = program.getSourceFile(path.join(webRoot, file));
    if (!sf) continue;
    for (const call of extractFromSource(sf, file, registry)) {
      if (call.dynamic || call.methodDynamic) {
        dynamic[file] = (dynamic[file] ?? 0) + 1;
        continue;
      }
      // A conditional segment yields several candidate paths; the call site is
      // covered when at least one of them is a real route.
      const targets = [...new Set(call.paths.flatMap((p) => resolveSpecPaths(spec, index, call.method, p)))];
      if (targets.length === 0) {
        for (const p of call.paths) unmatched.push({ method: call.method, path: p, file });
        continue;
      }
      const responseFields = call.responseType ? typeFields(checker, call.responseType) : [];
      for (const specPath of targets) {
        const op = spec.paths[specPath][call.method.toLowerCase()];
        const key = `${call.method} ${specPath}`;
        let it = byKey.get(key);
        if (!it) {
          it = {
            id: slug(call.method, specPath),
            method: call.method,
            path: specPath,
            status: successStatus(op),
            request: new Set(),
            response: new Set(),
          };
          byKey.set(key, it);
        }
        const status = String(it.status);
        const reqSchema = bodySchema(spec, op.requestBody);
        for (const name of call.requestFields ?? []) {
          const field = `$.${name}`;
          if (reqSchema !== undefined && schemaHasField(spec, reqSchema, fieldTokens(field))) it.request.add(field);
          else drift.push({ id: it.id, side: "request", field, file });
        }
        const resSchema = bodySchema(spec, op.responses?.[status]);
        for (const field of responseFields) {
          if (resSchema !== undefined && schemaHasField(spec, resSchema, fieldTokens(field))) it.response.add(field);
          else drift.push({ id: it.id, side: "response", field, file });
        }
      }
    }
  }
  const files = {};
  for (const it of [...byKey.values()].sort(
    (a, b) => a.path.localeCompare(b.path) || a.method.localeCompare(b.method)
  )) {
    const name = `${areaOf(it.path)}.contract.json`;
    files[name] ??= { consumer: "web", provider: "core", interactions: [] };
    const entry = { id: it.id, method: it.method, path: it.path, status: it.status };
    if (it.request.size > 0) entry.request = { requiredFields: [...it.request].sort() };
    entry.response = { requiredFields: [...it.response].sort() };
    files[name].interactions.push(entry);
  }
  const dedupe = (rows, keyOf) => [...new Map(rows.map((r) => [keyOf(r), r])).values()];
  return {
    files,
    drift: dedupe(drift, (d) => `${d.id} ${d.side} ${d.field} ${d.file}`).sort((a, b) =>
      `${a.id} ${a.side} ${a.field} ${a.file}`.localeCompare(`${b.id} ${b.side} ${b.field} ${b.file}`)
    ),
    uncontracted: {
      dynamicCallSites: Object.fromEntries(Object.entries(dynamic).sort(([a], [b]) => a.localeCompare(b))),
      unmatched: dedupe(unmatched, (u) => `${u.method} ${u.path} ${u.file}`).sort((a, b) =>
        `${a.path} ${a.method} ${a.file}`.localeCompare(`${b.path} ${b.method} ${b.file}`)
      ),
    },
  };
}

export { VERBS };
