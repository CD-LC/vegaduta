// Access paths of the expression language: parse and format schema paths,
// build data-picker references, insert them at the caret, rewrite
// $node["Label"] on rename and fold schema paths into the picker tree.
import { isIdentName, quoteString, scanString, unquoteString } from "./expressionLexicon";
import { findSegmentAt, findSegments, tokenize } from "./expressionSyntax";

// --------------------------------------------------------------- path parsing

export type PathSeg = { t: "key"; v: string } | { t: "idx"; v: number };

/** Parses a schema path such as `items[0].price` or `a["b c"].d`. Tolerant: never throws. */
export function parsePath(path: string): PathSeg[] {
  const segs: PathSeg[] = [];
  let i = 0;
  while (i < path.length) {
    const c = path[i];
    if (c === ".") {
      i++;
    } else if (c === "[") {
      const q = path[i + 1];
      if (q === '"' || q === "'") {
        let j = i + 2;
        while (j < path.length && path[j] !== q) j += path[j] === "\\" ? 2 : 1;
        segs.push({ t: "key", v: unquoteString(path.slice(i + 1, j + 1)) });
        i = path[j + 1] === "]" ? j + 2 : j + 1;
      } else {
        const close = path.indexOf("]", i);
        const raw = path.slice(i + 1, close < 0 ? path.length : close);
        segs.push(/^-?\d+$/.test(raw) ? { t: "idx", v: Number(raw) } : { t: "key", v: raw });
        i = close < 0 ? path.length : close + 1;
      }
    } else {
      let j = i;
      while (j < path.length && path[j] !== "." && path[j] !== "[") j++;
      segs.push({ t: "key", v: path.slice(i, j) });
      i = j;
    }
  }
  return segs;
}

/** Formats segments as an access chain, with a leading "." for identifier keys. */
export function formatAccess(segs: PathSeg[]): string {
  let out = "";
  for (const s of segs) {
    if (s.t === "idx") out += `[${s.v}]`;
    else out += isIdentName(s.v) ? `.${s.v}` : `[${quoteString(s.v)}]`;
  }
  return out;
}

/** `$node["Label"].items[0].price` for a data-picker leaf. */
export function buildPathExpression(label: string, path: string): string {
  return `$node[${quoteString(label)}]${formatAccess(parsePath(path))}`;
}

/** Reference to a node's whole output. */
export function buildNodeExpression(label: string): string {
  return `$node[${quoteString(label)}]`;
}

/** `$json.items[0].price` (bare-input form). */
export function buildJsonPathExpression(path: string): string {
  return `$json${formatAccess(parsePath(path))}`;
}

export type InsertResult = { value: string; cursor: number };

/**
 * Inserts an expression (no braces) at the caret. Inside a segment it goes in
 * as-is; inside a string literal within a segment it is concatenated in;
 * outside any segment it is wrapped as `{{ expr }}`. A selection is replaced.
 */
export function insertExpressionAt(template: string, selStart: number, selEnd: number, expr: string): InsertResult {
  const start = Math.max(0, Math.min(selStart, template.length));
  const end = Math.max(start, Math.min(selEnd, template.length));
  const seg = findSegmentAt(template, start);
  if (!seg) {
    const text = `{{ ${expr} }}`;
    return { value: template.slice(0, start) + text + template.slice(end), cursor: start + text.length };
  }
  const state = scanString(template.slice(seg.innerStart, start));
  if (state.quote) {
    const q = state.quote as '"' | "'";
    const text = `${q} + ${expr} + ${q}`;
    return { value: template.slice(0, start) + text + template.slice(end), cursor: start + text.length };
  }
  return { value: template.slice(0, start) + expr + template.slice(end), cursor: start + expr.length };
}

// ------------------------------------------------------------------- rename

/** Rewrites `$node["Old"]` to `$node["New"]` in bare expression source. Only real string tokens are touched. */
export function renameNodeLabelInExpression(expr: string, oldLabel: string, newLabel: string): string {
  if (!oldLabel || oldLabel === newLabel) return expr;
  const toks = tokenize(expr);
  const edits: { start: number; end: number; text: string }[] = [];
  for (let i = 0; i + 3 < toks.length; i++) {
    const [a, b, c, d] = [toks[i], toks[i + 1], toks[i + 2], toks[i + 3]];
    if (a.kind !== "root" || a.text !== "$node") continue;
    if (!(b.kind === "punct" && b.text === "[")) continue;
    if (c.kind !== "string" || unquoteString(c.text) !== oldLabel) continue;
    if (!(d.kind === "punct" && d.text === "]")) continue;
    edits.push({ start: c.start, end: c.end, text: quoteString(newLabel, c.text[0] as '"' | "'") });
  }
  let out = expr;
  for (const e of edits.reverse()) out = out.slice(0, e.start) + e.text + out.slice(e.end);
  return out;
}

/** Same, over a template: only the inside of {{ }} segments is rewritten. */
export function renameNodeLabelInTemplate(template: string, oldLabel: string, newLabel: string): string {
  if (!oldLabel || oldLabel === newLabel || !template.includes("$node")) return template;
  let out = template;
  for (const seg of findSegments(template).reverse()) {
    const inner = template.slice(seg.innerStart, seg.innerEnd);
    const next = renameNodeLabelInExpression(inner, oldLabel, newLabel);
    if (next !== inner) out = out.slice(0, seg.innerStart) + next + out.slice(seg.innerEnd);
  }
  return out;
}

// ------------------------------------------------------------- chain parsing

export type ChainRef = {
  root: string;
  /** Set when root is `$node` and the reference is by label or by id. */
  node?: { by: "label" | "id"; value: string };
  /** Remaining access path, normalized (`items[0].price`). */
  path: string;
};

/**
 * Parses an access chain like `$node["A"].items[0]` or `$json.a.b` or
 * `$node.id("n7").x`. Null for anything dynamic or not a plain chain.
 */
export function parseChain(chain: string): ChainRef | null {
  const toks = tokenize(chain);
  if (!toks.length || (toks[0].kind !== "root" && toks[0].kind !== "ident")) return null;
  const root = toks[0].text;
  let i = 1;
  let node: ChainRef["node"];
  const segs: PathSeg[] = [];
  if (root === "$node") {
    if (toks[i]?.text === "[" && toks[i + 1]?.kind === "string" && toks[i + 2]?.text === "]") {
      node = { by: "label", value: unquoteString(toks[i + 1].text) };
      i += 3;
    } else if (
      toks[i]?.text === "." && toks[i + 1]?.text === "id" && toks[i + 2]?.text === "(" &&
      toks[i + 3]?.kind === "string" && toks[i + 4]?.text === ")"
    ) {
      node = { by: "id", value: unquoteString(toks[i + 3].text) };
      i += 5;
    } else if (i < toks.length) {
      return null;
    }
  }
  while (i < toks.length) {
    const t = toks[i];
    if ((t.text === "." || t.text === "?.") && (toks[i + 1]?.kind === "ident" || toks[i + 1]?.kind === "root" || toks[i + 1]?.kind === "keyword")) {
      segs.push({ t: "key", v: toks[i + 1].text });
      i += 2;
    } else if (t.text === "[" && toks[i + 1]?.kind === "string" && toks[i + 2]?.text === "]") {
      segs.push({ t: "key", v: unquoteString(toks[i + 1].text) });
      i += 3;
    } else if (t.text === "[" && toks[i + 1]?.kind === "number" && toks[i + 2]?.text === "]") {
      segs.push({ t: "idx", v: Number(toks[i + 1].text) });
      i += 3;
    } else return null;
  }
  const f = formatAccess(segs);
  return { root, node, path: f.startsWith(".") ? f.slice(1) : f };
}

/** Immediate children of `base` among schema paths: `[{name, type}]` for object keys, deduped. */
export function childrenOf(
  paths: { path: string; type?: string }[],
  base: string,
): { name: string; type: string | null }[] {
  const baseSegs = parsePath(base);
  const seen = new Map<string, string | null>();
  for (const p of paths) {
    const segs = parsePath(p.path);
    if (segs.length <= baseSegs.length) continue;
    if (!baseSegs.every((b, i) => segs[i].t === b.t && segs[i].v === b.v)) continue;
    const next = segs[baseSegs.length];
    if (next.t !== "key" || !next.v) continue;
    const exact = segs.length === baseSegs.length + 1;
    if (exact) seen.set(next.v, p.type ?? null);
    else if (!seen.has(next.v)) seen.set(next.v, null);
  }
  return [...seen.entries()].map(([name, type]) => ({ name, type }));
}
// ------------------------------------------------------------ schema tree

export type SchemaTreeNode = {
  /** Label shown in the tree: `items`, `[0]`, `price`. */
  name: string;
  /** Full schema path, e.g. `items[0].price` - what buildPathExpression takes. */
  path: string;
  type: string | null;
  sample: string | null;
  children: SchemaTreeNode[];
};

/**
 * Folds the flat `paths` list of an upstream-schema node into a tree for the
 * data picker. Intermediate nodes the server did not list explicitly are
 * created on the way. Array indices keep their numeric order; keys keep the
 * server's order.
 */
export function buildSchemaTree(paths: { path: string; type?: string | null; sample?: string | null }[]): SchemaTreeNode[] {
  const roots: SchemaTreeNode[] = [];
  const index = new Map<string, SchemaTreeNode>();
  for (const p of paths) {
    const segs = parsePath(p.path);
    if (!segs.length) continue;
    let level = roots;
    for (let i = 0; i < segs.length; i++) {
      const prefix = segs.slice(0, i + 1);
      const path = formatAccess(prefix).replace(/^\./, "");
      let node = index.get(path);
      if (!node) {
        const s = prefix[i];
        node = { name: s.t === "idx" ? `[${s.v}]` : s.v, path, type: null, sample: null, children: [] };
        index.set(path, node);
        level.push(node);
      }
      if (i === segs.length - 1) {
        node.type = p.type ?? node.type;
        node.sample = p.sample ?? node.sample;
      }
      level = node.children;
    }
  }
  const order = (nodes: SchemaTreeNode[]) => {
    if (nodes.length > 1 && nodes.every((n) => /^\[-?\d+\]$/.test(n.name))) {
      nodes.sort((a, b) => Number(a.name.slice(1, -1)) - Number(b.name.slice(1, -1)));
    }
    for (const n of nodes) order(n.children);
  };
  order(roots);
  return roots;
}

/**
 * Reference to an upstream node's output, optionally into a path. Uses the
 * label form `$node["Label"]` normally, and the id form `$node.id("n7")` when
 * the node has no label or its label is shared (the linter flags those).
 */
export function buildNodeRef(node: { nodeId: string; label: string }, path = "", byId = false): string {
  const head = byId || !node.label ? `$node.id(${quoteString(node.nodeId)})` : buildNodeExpression(node.label);
  return head + (path ? formatAccess(parsePath(path)) : "");
}
