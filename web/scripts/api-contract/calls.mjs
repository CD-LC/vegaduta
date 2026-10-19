// Call-site extraction for the web contract (ADR-0025), including wrapper following.
//
// A "wrapper" is a function that forwards one of its parameters into the URL of a
// fetch helper (`async function call(path, init) { return apiFetch(path, init); }`,
// `const attempt = (base) => apiFetch(`${base}/${id}/test`, { method: "POST" })`,
// `fetch(`${API_BASE}/api/demo/studio${path}`)`). The wrapper's own inner call has no
// readable URL, but every call of the wrapper does, so the inner call is not counted as
// unreadable and each call of the wrapper becomes a call site: the wrapper's URL with
// the parameter replaced by the caller's argument, the method and body keys read from
// the options the wrapper builds, with its own parameters (`method`, `init`) bound to
// the caller's arguments.
//
// Scope rules keep this sound without a type checker: a function-local wrapper only
// answers calls inside its enclosing block (innermost declaration wins), an exported
// wrapper answers calls in files that import its name and only when the name is
// exported once. A call of a wrapper whose argument the extractor cannot read stays
// a dynamic call site at the caller, so the count never hides a call.
import fs from "node:fs";
import path from "node:path";
import ts from "typescript";
import {
  PATH_CALLERS,
  URL_CALLERS,
  calleeName,
  collectLocals,
  listSourceFiles,
  readOptions,
  resolvePaths,
  urlTokens,
} from "./extract.mjs";

/** Helpers whose own bodies forward their parameters; never discovered as wrappers. */
const BUILTIN = new Set([...PATH_CALLERS, ...URL_CALLERS, "prefetchApiGets"]);

function hasExport(node) {
  return !!(ts.canHaveModifiers(node) && ts.getModifiers(node)?.some((m) => m.kind === ts.SyntaxKind.ExportKeyword));
}

/** Named functions of a file: declarations and `const f = () => ...` / `function () {}`. */
function functionLikes(sourceFile) {
  const out = [];
  const add = (name, fn, statement, exported) => {
    const scope = statement.parent;
    const params = fn.parameters.map((p) => (ts.isIdentifier(p.name) ? p.name.text : ""));
    out.push({ name, fn, params, scope, exported, wrapper: null });
  };
  const visit = (node) => {
    if (ts.isFunctionDeclaration(node) && node.name && node.body) add(node.name.text, node, node, hasExport(node));
    if (
      ts.isVariableDeclaration(node) &&
      ts.isIdentifier(node.name) &&
      node.initializer &&
      (ts.isArrowFunction(node.initializer) || ts.isFunctionExpression(node.initializer))
    ) {
      const statement = node.parent?.parent;
      if (statement && ts.isVariableStatement(statement))
        add(node.name.text, node.initializer, statement, hasExport(statement));
    }
    ts.forEachChild(node, visit);
  };
  visit(sourceFile);
  return out;
}

function importedNames(sourceFile) {
  const names = new Set();
  for (const st of sourceFile.statements) {
    const clause = ts.isImportDeclaration(st) ? st.importClause : undefined;
    if (!clause) continue;
    if (clause.name) names.add(clause.name.text);
    const nb = clause.namedBindings;
    if (nb && ts.isNamedImports(nb)) for (const el of nb.elements) names.add(el.name.text);
  }
  return names;
}

const within = (outer, node) => outer.pos <= node.pos && node.end <= outer.end;

/** The wrapper a call of an identifier resolves to, or null. */
function resolveWrapper(call, ctx, reg) {
  if (!ts.isIdentifier(call.expression)) return null;
  const name = call.expression.text;
  const here = ctx.decls.filter((d) => d.name === name && within(d.scope, call));
  if (here.length > 0) return here.reduce((a, b) => (b.scope.pos >= a.scope.pos ? b : a)).wrapper;
  return ctx.imports.has(name) ? (reg.wrappers.get(name) ?? null) : null;
}

function targetOf(call, ctx, reg) {
  const name = calleeName(call.expression);
  if (name && (PATH_CALLERS.has(name) || URL_CALLERS.has(name)) && call.arguments.length > 0) return { builtin: name };
  const wrapper = resolveWrapper(call, ctx, reg);
  return wrapper ? { wrapper } : null;
}

/** URL tokens of a call of a helper or wrapper (the wrapper's template with the caller's argument spliced in). */
function tokensFor(call, target, locals) {
  const argTokens = (arg) => (arg ? (urlTokens(arg, locals) ?? [{ expr: arg }]) : null);
  if (target.builtin) {
    const tokens = argTokens(call.arguments[0]);
    return tokens && { tokens, requireBase: URL_CALLERS.has(target.builtin) };
  }
  const w = target.wrapper;
  const at = argTokens(call.arguments[w.paramIndex]);
  if (!at) return null;
  return { tokens: w.tokens.flatMap((t, i) => (i === w.slotIndex ? at : [t])), requireBase: w.requireBase };
}

function detectWrapper(decl, ctx, reg) {
  if (BUILTIN.has(decl.name)) return null;
  let found = null;
  const visit = (node) => {
    if (found) return;
    if (ts.isCallExpression(node)) {
      const target = targetOf(node, ctx, reg);
      if (target && target.wrapper?.decl !== decl) {
        const t = tokensFor(node, target, ctx.locals);
        const slots = (t?.tokens ?? [])
          .map((x, i) => (x.expr && ts.isIdentifier(x.expr) && decl.params.includes(x.expr.text) ? i : -1))
          .filter((i) => i >= 0);
        if (slots.length === 1) {
          const slotParam = t.tokens[slots[0]].expr.text;
          found = {
            decl,
            params: decl.params,
            slotParam,
            paramIndex: decl.params.indexOf(slotParam),
            slotIndex: slots[0],
            tokens: t.tokens,
            requireBase: t.requireBase,
            target,
            inner: node,
          };
        }
      }
    }
    ts.forEachChild(node, visit);
  };
  visit(decl.fn.body);
  return found;
}

/** Method and body keys of a wrapper call: the options its inner call builds, with parameters bound to `kargs`. */
function wrapperOptions(w, kargs, kenv) {
  const env = new Map();
  w.params.forEach((p, i) => {
    if (p) env.set(p, { node: kargs[i], env: kenv });
  });
  const t = w.target;
  if (t.builtin) return t.builtin === "fetchPaged" ? { method: "GET" } : readOptions(w.inner.arguments[1], env);
  return wrapperOptions(t.wrapper, w.inner.arguments, env);
}

/**
 * Indexes every file once: locals (constants, builders), function declarations, the
 * cross-file builder and wrapper maps. Wrappers are discovered in rounds so a wrapper
 * of a wrapper resolves.
 */
export function buildRegistry(files) {
  const reg = { files: new Map(), builders: new Map(), wrappers: new Map() };
  for (const { sf, file } of files) {
    const locals = collectLocals(sf);
    locals.global = reg.builders;
    reg.files.set(file, { sf, locals, decls: functionLikes(sf), imports: importedNames(sf) });
  }
  const builderOwners = new Map();
  for (const ctx of reg.files.values())
    for (const d of ctx.decls)
      if (d.exported && ctx.locals.builders.has(d.name)) {
        builderOwners.set(d.name, [
          ...(builderOwners.get(d.name) ?? []),
          { entry: ctx.locals.builders.get(d.name), locals: ctx.locals },
        ]);
      }
  for (const [name, owners] of builderOwners) if (owners.length === 1) reg.builders.set(name, owners[0]);
  for (let round = 0; round < 4; round++) {
    let changed = false;
    for (const ctx of reg.files.values())
      for (const d of ctx.decls) if (!d.wrapper && (d.wrapper = detectWrapper(d, ctx, reg))) changed = true;
    const owners = new Map();
    for (const ctx of reg.files.values())
      for (const d of ctx.decls) if (d.exported && d.wrapper) owners.set(d.name, owners.has(d.name) ? null : d.wrapper);
    reg.wrappers = owners;
    if (!changed) break;
  }
  for (const [name, w] of reg.wrappers) if (w === null) reg.wrappers.delete(name);
  return reg;
}

/** `apiFetch(...).then(toJson<T>)` or `.then(toJsonQuietOn<T>([..]))`: the T node, if any. */
function responseTypeNode(call) {
  const parent = call.parent;
  if (!parent || !ts.isPropertyAccessExpression(parent) || parent.name.text !== "then") return undefined;
  const thenCall = parent.parent;
  if (!thenCall || !ts.isCallExpression(thenCall) || thenCall.expression !== parent) return undefined;
  const arg = thenCall.arguments[0];
  if (!arg) return undefined;
  if (ts.isExpressionWithTypeArguments(arg) && /^toJson/.test(calleeName(arg.expression) ?? "")) {
    return arg.typeArguments?.[0];
  }
  if (ts.isCallExpression(arg) && /^toJson/.test(calleeName(arg.expression) ?? "")) {
    return arg.typeArguments?.[0];
  }
  return undefined;
}

/**
 * Extracts the call sites from one parsed source file. `registry` (buildRegistry over
 * all files) lets calls of helpers defined elsewhere resolve; without it only this
 * file's own wrappers and builders are known.
 * @returns {Array<{file: string, line: number, method: string, paths?: string[], dynamic?: boolean,
 *   suffix?: string, methodDynamic?: boolean, requestFields?: string[], responseType?: ts.TypeNode}>}
 */
export function extractFromSource(sourceFile, file, registry) {
  let reg = registry;
  if (!reg || reg.files.get(file)?.sf !== sourceFile) reg = buildRegistry([{ sf: sourceFile, file }]);
  const ctx = reg.files.get(file);
  const homes = (node) => {
    const out = [];
    for (let n = node.parent; n; n = n.parent) {
      const d = ctx.decls.find((x) => x.fn === n);
      if (d) out.push(d);
    }
    return out;
  };
  const calls = [];
  const visit = (node) => {
    if (ts.isCallExpression(node)) {
      const line = sourceFile.getLineAndCharacterOfPosition(node.getStart(sourceFile)).line + 1;
      if (
        calleeName(node.expression) === "prefetchApiGets" &&
        node.arguments[0] &&
        ts.isArrayLiteralExpression(node.arguments[0])
      ) {
        for (const el of node.arguments[0].elements) {
          const p = resolvePaths(urlTokens(el, ctx.locals), false);
          if (p && p.paths) calls.push({ file, line, method: "GET", paths: p.paths, suffix: p.suffix });
        }
      } else {
        const target = targetOf(node, ctx, reg);
        const t = target && tokensFor(node, target, ctx.locals);
        const p = t && resolvePaths(t.tokens, t.requireBase);
        // A helper forwarding its own parameter is covered by its callers.
        const forwarded =
          p &&
          homes(node).some((h) =>
            t.tokens.some(
              (x) =>
                x.expr &&
                ts.isIdentifier(x.expr) &&
                ((h.wrapper && x.expr.text === h.wrapper.slotParam) || (p.dynamic && BUILTIN.has(h.name)))
            )
          );
        if (forwarded) {
          // nothing to record
        } else if (p && p.dynamic) {
          calls.push({ file, line, method: "?", dynamic: true });
        } else if (p) {
          const opts = target.builtin
            ? target.builtin === "fetchPaged"
              ? { method: "GET" }
              : readOptions(node.arguments[1])
            : wrapperOptions(target.wrapper, node.arguments, undefined);
          calls.push({
            file,
            line,
            method: opts.method,
            methodDynamic: opts.methodDynamic || undefined,
            paths: p.paths,
            suffix: p.suffix,
            requestFields: opts.requestFields,
            responseType: responseTypeNode(node),
          });
        }
      }
    }
    ts.forEachChild(node, visit);
  };
  visit(sourceFile);
  return calls;
}

/** Parses and extracts every source file under webRoot/app. */
export function extractCalls(webRoot) {
  const files = listSourceFiles(webRoot).map((file) => {
    const text = fs.readFileSync(path.join(webRoot, file), "utf8");
    const kind = file.endsWith(".tsx") ? ts.ScriptKind.TSX : ts.ScriptKind.TS;
    return { file, sf: ts.createSourceFile(file, text, ts.ScriptTarget.ES2022, true, kind) };
  });
  const reg = buildRegistry(files);
  return files.flatMap(({ file, sf }) => extractFromSource(sf, file, reg));
}
