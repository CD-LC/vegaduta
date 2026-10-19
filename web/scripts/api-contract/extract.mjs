// Static extraction of the core REST calls the web console makes (ADR-0025).
//
// Parses every non-test .ts/.tsx file under web/app with the TypeScript parser
// (syntax only, no type checker, so it is fast enough for a vitest) and returns
// one record per call site of the console's fetch helpers:
//
//   apiFetch / apiFetchOnce (app/lib/api/core.ts, app/platform-admin/lib/apiCore.ts)
//   fetchPaged              (both pagedFetch.ts helpers; always GET)
//   fetchWithRetry / fetch  (only when the URL starts with ${API_BASE}, ${SERVER_API_BASE} or "/api/")
//   prefetchApiGets([...])  (GETs)
//
// A call whose URL is not a string or template literal (a variable, a helper's
// `path` parameter) cannot be resolved statically; it is returned with
// `dynamic: true` so the coverage gap is counted rather than hidden.
//
// Path normalisation: every `${expr}` that fills a whole path segment becomes
// `{}`; the query string (from the first literal `?`) is dropped; an expression
// glued to the end of the last segment (`/api/audit${qs}`) is dropped as a
// query suffix and recorded in `suffix`. Contract files use OpenAPI parameter
// names; compare the two with normalizeTemplate(), which maps `{anything}` to `{}`.
import fs from "node:fs";
import path from "node:path";
import ts from "typescript";

export const PATH_CALLERS = new Set(["apiFetch", "apiFetchOnce", "fetchPaged"]);
export const URL_CALLERS = new Set(["fetch", "fetchWithRetry"]);
const BASE_IDENT = /^(SERVER_)?API_BASE$|^apiBase$/;
// Source text of an expression glued after the last path segment that reads as a query string.
const QUERYISH = /query|qs\b|params|search|range|filter|paging|page|\bsuffix\b|\?/i;
const PLACEHOLDER = "\u0000";

/** Maps every `{name}` in an OpenAPI-style template to `{}`. */
export function normalizeTemplate(p) {
  return p.replace(/\{[^/{}]*\}/g, "{}");
}

/**
 * True when an extracted path (`{}` placeholders) can address a contract or
 * OpenAPI path: same segment count, and each segment is equal, or either side
 * is a whole-segment placeholder. An extracted `{}` therefore also matches a
 * literal route segment (`/purposes/${resource}/x` reaches `/purposes/agents/{id}`).
 */
export function pathMatches(extracted, template) {
  const a = extracted.split("/");
  const b = normalizeTemplate(template).split("/");
  if (a.length !== b.length) return false;
  for (let i = 0; i < a.length; i++) {
    if (a[i] === b[i] || a[i] === "{}" || b[i] === "{}") continue;
    // Spec segments such as `{id}:cancel` or `logo.{ext}`.
    if (b[i].includes("{}")) {
      const re = new RegExp(
        "^" +
          b[i]
            .split("{}")
            .map((s) => s.replace(/[.*+?^$()|[\]\\]/g, "\\$&"))
            .join("[^/]+") +
          "$"
      );
      if (re.test(a[i].replace(/\{\}/g, "x"))) continue;
    }
    return false;
  }
  return true;
}

/** Lists the source files the extractor reads, relative to webRoot, sorted. */
export function listSourceFiles(webRoot) {
  const out = [];
  const walk = (dir) => {
    for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
      if (entry.name === "node_modules" || entry.name === ".next") continue;
      const full = path.join(dir, entry.name);
      if (entry.isDirectory()) {
        walk(full);
      } else if (
        /\.(ts|tsx)$/.test(entry.name) &&
        !/\.test\.(ts|tsx)$/.test(entry.name) &&
        !entry.name.endsWith(".d.ts")
      ) {
        out.push(path.relative(webRoot, full).split(path.sep).join("/"));
      }
    }
  };
  walk(path.join(webRoot, "app"));
  return out.sort();
}

export function calleeName(expr) {
  if (ts.isIdentifier(expr)) return expr.text;
  if (ts.isPropertyAccessExpression(expr)) return expr.name.text;
  return null;
}

function isLiteralish(node) {
  return (
    node &&
    (ts.isStringLiteral(node) ||
      ts.isNoSubstitutionTemplateLiteral(node) ||
      ts.isTemplateExpression(node) ||
      ts.isConditionalExpression(node) ||
      ts.isBinaryExpression(node))
  );
}

/** The return expressions of a function body (not descending into nested functions), or null when one is not a value. */
function returnsOf(body) {
  const out = [];
  let ok = true;
  const visit = (n) => {
    if (ts.isFunctionLike(n) && n !== body) return;
    if (ts.isReturnStatement(n)) {
      if (n.expression && isLiteralish(n.expression)) out.push(n.expression);
      else ok = false;
    }
    ts.forEachChild(n, visit);
  };
  visit(body);
  return ok && out.length > 0 ? out : null;
}

/** `const NAME = <literal>` statements directly in a block: the function-scoped constants a builder's returns may use. */
function blockConsts(block) {
  const consts = new Map();
  for (const st of block.statements) {
    if (!ts.isVariableStatement(st) || !(st.declarationList.flags & ts.NodeFlags.Const)) continue;
    for (const d of st.declarationList.declarations)
      if (ts.isIdentifier(d.name) && d.initializer && isLiteralish(d.initializer))
        consts.set(d.name.text, d.initializer);
  }
  return consts;
}

/**
 * Collects the file's `const NAME = "<literal>"` / template constants and the
 * path builders (`const base = (id) => `/api/x/${id}``, or a function whose
 * returns are all literal templates, possibly via constants local to its body or
 * a conditional), so `${BASE}/x` and `${base(id)}/y` resolve to literal paths.
 * A builder name declared twice in the file is dropped (ambiguous).
 * `global` is the registry's cross-file map of exported builders.
 */
export function collectLocals(sourceFile) {
  const consts = new Map();
  const builders = new Map();
  const seen = new Set();
  const addBuilder = (name, fn) => {
    const returns = ts.isBlock(fn.body) ? returnsOf(fn.body) : isLiteralish(fn.body) ? [fn.body] : null;
    if (seen.has(name)) builders.delete(name);
    else if (returns) builders.set(name, { returns, consts: ts.isBlock(fn.body) ? blockConsts(fn.body) : new Map() });
    seen.add(name);
  };
  const visit = (node) => {
    if (ts.isVariableDeclaration(node) && ts.isIdentifier(node.name) && node.initializer) {
      const list = node.parent;
      const isConst = list && ts.isVariableDeclarationList(list) && (list.flags & ts.NodeFlags.Const) !== 0;
      const init = node.initializer;
      if (isConst && isLiteralish(init)) consts.set(node.name.text, init);
      if (isConst && (ts.isArrowFunction(init) || ts.isFunctionExpression(init)) && init.body)
        addBuilder(node.name.text, init);
    }
    if (ts.isFunctionDeclaration(node) && node.name && node.body) addBuilder(node.name.text, node);
    ts.forEachChild(node, visit);
  };
  visit(sourceFile);
  return { consts, builders, global: new Map() };
}

/** `${wf ? "workflows" : "agents"}`: the literal path segments a conditional picks between, else null. */
function literalAlternatives(expr) {
  let e = expr;
  while (ts.isParenthesizedExpression(e)) e = e.expression;
  if (!ts.isConditionalExpression(e)) return null;
  const out = [];
  for (const branch of [e.whenTrue, e.whenFalse]) {
    if (ts.isConditionalExpression(branch)) {
      const nested = literalAlternatives(branch);
      if (!nested) return null;
      out.push(...nested);
    } else if (
      (ts.isStringLiteral(branch) || ts.isNoSubstitutionTemplateLiteral(branch)) &&
      /^[A-Za-z0-9._-]+$/.test(branch.text)
    ) {
      out.push(branch.text);
    } else {
      return null;
    }
  }
  return out;
}

/** Expands `alts` / `variants` tokens into one literal token list per combination (at most 16). */
function expandAlternatives(tokens) {
  let combos = [[]];
  for (const t of tokens) {
    if (t.alts) {
      combos = combos.flatMap((c) => t.alts.map((a) => [...c, { lit: a }]));
    } else if (t.variants) {
      const each = t.variants.flatMap((v) => expandAlternatives(v));
      combos = combos.flatMap((c) => each.map((v) => [...c, ...v]));
    } else {
      combos = combos.map((c) => [...c, t]);
    }
    if (combos.length > 16) {
      return [tokens.map((x) => (x.alts || x.variants ? { expr: x.expr ?? { getText: () => "variant" } } : x))];
    }
  }
  return combos;
}

/** Flattens a URL expression into literal / placeholder tokens; null when not literal-based. */
export function urlTokens(node, locals, depth = 0) {
  if (depth > 6) return null;
  const sub = (n) => {
    if (!locals) return null;
    if (ts.isIdentifier(n) && locals.consts.has(n.text)) return urlTokens(locals.consts.get(n.text), locals, depth + 1);
    if (ts.isCallExpression(n) && ts.isIdentifier(n.expression)) {
      const name = n.expression.text;
      const own = locals.builders.get(name);
      const shared = own ? null : locals.global.get(name);
      const entry = own ?? shared?.entry;
      if (!entry) return null;
      const scope = own ? locals : shared.locals;
      const inner = { ...scope, consts: new Map([...scope.consts, ...entry.consts]) };
      const variants = entry.returns.map((r) => urlTokens(r, inner, depth + 1));
      if (variants.some((v) => v == null)) return null;
      return variants.length === 1 ? variants[0] : [{ variants }];
    }
    return null;
  };
  if (ts.isParenthesizedExpression(node)) return urlTokens(node.expression, locals, depth);
  if (ts.isConditionalExpression(node)) {
    // `allowed ? \`/api/x\` : null`: the null branch makes no request.
    const branches = [node.whenTrue, node.whenFalse].filter(
      (b) => !(b.kind === ts.SyntaxKind.NullKeyword || (ts.isIdentifier(b) && b.text === "undefined"))
    );
    const variants = branches.map((b) => urlTokens(b, locals, depth + 1));
    if (variants.length === 0 || variants.some((v) => v == null)) return null;
    return variants.length === 1 ? variants[0] : [{ variants }];
  }
  if (ts.isStringLiteral(node) || ts.isNoSubstitutionTemplateLiteral(node)) return [{ lit: node.text }];
  const direct = sub(node);
  if (direct) return direct;
  if (ts.isTemplateExpression(node)) {
    const tokens = [{ lit: node.head.text }];
    for (const span of node.templateSpans) {
      const inner = sub(span.expression);
      const alts = literalAlternatives(span.expression);
      const picked =
        !inner && !alts && ts.isConditionalExpression(span.expression)
          ? urlTokens(span.expression, locals, depth + 1)
          : null;
      if (inner) tokens.push(...inner);
      else if (alts) tokens.push({ alts, expr: span.expression });
      else if (picked) tokens.push(...picked);
      else tokens.push({ expr: span.expression });
      tokens.push({ lit: span.literal.text });
    }
    return tokens;
  }
  if (ts.isBinaryExpression(node) && node.operatorToken.kind === ts.SyntaxKind.PlusToken) {
    const left = urlTokens(node.left, locals, depth + 1);
    const right = urlTokens(node.right, locals, depth + 1);
    if (left == null && right == null) return null;
    return [...(left ?? [{ expr: node.left }]), ...(right ?? [{ expr: node.right }])];
  }
  // `override || \`/api/...\`` and `x ?? "/api/..."`: the literal fallback is the default route.
  if (
    ts.isBinaryExpression(node) &&
    (node.operatorToken.kind === ts.SyntaxKind.BarBarToken ||
      node.operatorToken.kind === ts.SyntaxKind.QuestionQuestionToken)
  ) {
    return urlTokens(node.right, locals, depth + 1);
  }
  return null;
}

function isBaseExpr(expr) {
  return (
    (ts.isIdentifier(expr) && BASE_IDENT.test(expr.text)) ||
    (ts.isCallExpression(expr) && /ApiBase$/.test(calleeName(expr.expression) ?? ""))
  );
}

/**
 * Turns URL tokens into a normalised path. Returns null when the URL is not a
 * core API path (an absolute external URL, a relative Next.js route).
 */
function tokensToPath(tokens, requireBase) {
  const parts = tokens.filter((t) => !(t.lit === ""));
  let hadBase = false;
  if (parts.length > 0 && parts[0].expr && isBaseExpr(parts[0].expr)) {
    parts.shift();
    hadBase = true;
  }
  if (parts.length === 0) return null;
  if (requireBase && !hadBase) return null;
  if (parts[0].expr) return { dynamic: true };
  if (!parts[0].lit.startsWith("/")) return null;
  let raw = "";
  const exprs = [];
  for (const part of parts) {
    if (part.lit != null) {
      raw += part.lit;
    } else {
      raw += PLACEHOLDER;
      exprs.push(part.expr);
    }
  }
  const cut = raw.search(/[?#&]/);
  if (cut >= 0) raw = raw.slice(0, cut);
  let suffix;
  // Expressions glued to the end of a non-empty last segment (`/api/audit${qs}`)
  // are a query-string suffix when their source reads like one; anything else
  // (`/api/demo/studio${path}`) is a dynamic sub-path the extractor cannot see.
  const m = /[^/\u0000](\u0000+)$/.exec(raw);
  if (m) {
    const before = raw.slice(0, raw.length - m[1].length).split(PLACEHOLDER).length - 1;
    const texts = exprs.slice(before, before + m[1].length).map((e) => e.getText());
    if (!texts.every((t) => QUERYISH.test(t))) return { dynamic: true };
    raw = raw.slice(0, raw.length - m[1].length);
    suffix = texts.join("");
  }
  if (raw.length > 1 && raw.endsWith("/")) raw = raw.slice(0, -1);
  const normalized = raw.replace(/\u0000+/g, "{}");
  return { path: normalized, suffix };
}

function objectLiteralOf(node) {
  if (!node) return null;
  if (ts.isParenthesizedExpression(node)) return objectLiteralOf(node.expression);
  return ts.isObjectLiteralExpression(node) ? node : null;
}

function propName(prop) {
  if (!prop.name) return null;
  if (ts.isIdentifier(prop.name) || ts.isStringLiteral(prop.name)) return prop.name.text;
  return null;
}

/**
 * Follows an identifier that names a parameter of a followed wrapper to the
 * caller's argument: env maps a parameter name to {node, env} (the argument and
 * the environment it is itself written in). Returns {node, env, viaParam}, or
 * null when the parameter was not passed.
 */
function resolveRef(node, env) {
  let cur = { node, env, viaParam: false };
  while (cur.node && ts.isParenthesizedExpression(cur.node)) cur = { ...cur, node: cur.node.expression };
  while (cur.node && ts.isIdentifier(cur.node) && cur.env?.has(cur.node.text)) {
    const next = cur.env.get(cur.node.text);
    if (!next?.node) return null;
    cur = { node: next.node, env: next.env, viaParam: true };
  }
  return cur.node ? cur : null;
}

/** 'undef' / 'def' for an argument the caller left out, wrote as `undefined`, or wrote as a literal value; null when unknowable. */
function definedness(node, env) {
  const ref = resolveRef(node, env);
  if (!ref) return "undef";
  const n = ref.node;
  if (ts.isIdentifier(n) && n.text === "undefined") return "undef";
  if (ts.isConditionalExpression(n)) {
    const a = definedness(n.whenTrue, ref.env);
    return a !== null && a === definedness(n.whenFalse, ref.env) ? a : null;
  }
  const literal =
    ts.isObjectLiteralExpression(n) ||
    ts.isArrayLiteralExpression(n) ||
    ts.isStringLiteral(n) ||
    ts.isNumericLiteral(n) ||
    ts.isTemplateExpression(n) ||
    ts.isNoSubstitutionTemplateLiteral(n) ||
    ts.isNewExpression(n);
  return literal ? "def" : null;
}

/**
 * The HTTP method an options `method` expression yields: a string literal, or
 * `body === undefined ? "GET" : "POST"` with `body` bound to the caller's argument.
 */
function methodOf(node, env) {
  const ref = resolveRef(node, env);
  const n = ref?.node;
  if (!n) return null;
  if (ts.isStringLiteral(n) || ts.isNoSubstitutionTemplateLiteral(n)) return n.text.toUpperCase();
  const c = ts.isConditionalExpression(n) ? n.condition : null;
  if (
    c &&
    ts.isBinaryExpression(c) &&
    [ts.SyntaxKind.EqualsEqualsEqualsToken, ts.SyntaxKind.ExclamationEqualsEqualsToken].includes(c.operatorToken.kind)
  ) {
    const left =
      ts.isIdentifier(c.right) && c.right.text === "undefined"
        ? c.left
        : ts.isIdentifier(c.left) && c.left.text === "undefined"
          ? c.right
          : null;
    const def = left && definedness(left, ref.env);
    if (!def) return null;
    const isUndef = def === "undef";
    const takeTrue = c.operatorToken.kind === ts.SyntaxKind.EqualsEqualsEqualsToken ? isUndef : !isUndef;
    return methodOf(takeTrue ? n.whenTrue : n.whenFalse, ref.env);
  }
  return null;
}

/**
 * Reads `method` and the jsonBody/JSON.stringify payload keys from a fetch options
 * literal. With `env` (a wrapper's parameters bound to a call site's arguments) a
 * parameter used as the options object, spread into it, or given as `method` is
 * read from the caller's argument; one the caller passes as a non-literal makes
 * the method dynamic rather than silently GET.
 */
export function readOptions(optionsNode, env) {
  const result = { method: "GET", requestFields: undefined, methodDynamic: false };
  const walk = (node, nodeEnv) => {
    const ref = resolveRef(node, nodeEnv);
    if (!ref) return;
    const obj = objectLiteralOf(ref.node);
    if (!obj) {
      result.optionsOpaque = true;
      if (ref.viaParam) result.methodDynamic = true;
      return;
    }
    for (const prop of obj.properties) {
      if (
        ts.isSpreadAssignment(prop) &&
        !(ts.isCallExpression(prop.expression) && calleeName(prop.expression.expression) === "jsonBody")
      ) {
        if (resolveRef(prop.expression, ref.env)?.viaParam) walk(prop.expression, ref.env);
        continue;
      }
      const methodInit =
        ts.isPropertyAssignment(prop) && propName(prop) === "method"
          ? prop.initializer
          : ts.isShorthandPropertyAssignment(prop) && prop.name.text === "method"
            ? prop.name
            : null;
      if (methodInit) {
        const m = methodOf(methodInit, ref.env);
        if (m) result.method = m;
        else if (resolveRef(methodInit, ref.env)) result.methodDynamic = true;
      }
      let payload = null;
      if (
        ts.isSpreadAssignment(prop) &&
        ts.isCallExpression(prop.expression) &&
        calleeName(prop.expression.expression) === "jsonBody"
      ) {
        payload = prop.expression.arguments[0];
      }
      if (ts.isPropertyAssignment(prop) && propName(prop) === "body" && ts.isCallExpression(prop.initializer)) {
        const call = prop.initializer;
        const callee = call.expression;
        if (
          ts.isPropertyAccessExpression(callee) &&
          callee.name.text === "stringify" &&
          ts.isIdentifier(callee.expression) &&
          callee.expression.text === "JSON"
        ) {
          payload = call.arguments[0];
        }
      }
      const payloadObj = objectLiteralOf(payload);
      if (payloadObj) {
        const keys = [];
        for (const p of payloadObj.properties) {
          if (ts.isShorthandPropertyAssignment(p)) keys.push(p.name.text);
          else if (ts.isPropertyAssignment(p)) {
            const n = propName(p);
            if (n) keys.push(n);
          }
        }
        result.requestFields = keys;
      }
    }
  };
  if (optionsNode) {
    const top = resolveRef(optionsNode, env);
    if (top && !objectLiteralOf(top.node) && !top.viaParam) result.optionsOpaque = true;
    else walk(optionsNode, env);
  }
  return result;
}

/**
 * Resolves URL tokens to the core API paths they can produce (one per literal
 * alternative), `{dynamic: true}` for a core URL the extractor cannot read, or
 * null for a URL that is not a core API call.
 */
export function resolvePaths(tokens, requireBase) {
  if (tokens == null) return null;
  const paths = [];
  let suffix;
  let dynamic = false;
  for (const combo of expandAlternatives(tokens)) {
    const p = tokensToPath(combo, requireBase);
    if (!p) continue;
    if (p.dynamic) dynamic = true;
    else {
      paths.push(p.path);
      suffix = suffix ?? p.suffix;
    }
  }
  if (paths.length > 0) return { paths: [...new Set(paths)], suffix };
  return dynamic ? { dynamic: true } : null;
}

/** Reads every interaction of the *.contract.json files in a consumer directory, tagged with its file name. */
export function readContractInteractions(contractsDir) {
  const out = [];
  if (!fs.existsSync(contractsDir)) return out;
  for (const name of fs.readdirSync(contractsDir).sort()) {
    if (!name.endsWith(".contract.json")) continue;
    const doc = JSON.parse(fs.readFileSync(path.join(contractsDir, name), "utf8"));
    for (const it of doc.interactions ?? []) out.push({ ...it, file: name });
  }
  return out;
}
