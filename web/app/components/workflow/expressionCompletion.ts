// Autocomplete for the expression editor: what can be completed at the caret,
// the ranked popup entries for that context, and applying a chosen entry.
import type { ExpressionFunctionDoc, UpstreamSchemaNode } from "../../lib/api/workflowExpressions";
import { IDENT_PART, KEYWORDS, NODE_META_MEMBERS, ROOT_DOCS, ROOT_MEMBERS, ROOTS, isIdentName, quoteString, scanString, unquoteString } from "./expressionLexicon";
import { childrenOf, parseChain } from "./expressionPaths";
import { findSegmentAt } from "./expressionSyntax";

// --------------------------------------------------------- completion context

export type CompletionContext =
  | { kind: "ident"; prefix: string; replaceStart: number; replaceEnd: number }
  | { kind: "member"; prefix: string; replaceStart: number; replaceEnd: number; chain: string | null; dotStart: number }
  | { kind: "node-label"; prefix: string; replaceStart: number; replaceEnd: number; quote: '"' | "'"; closed: boolean };


/** Index of the "(" / "[" matching the closer at `closeIdx`, skipping string literals; -1 when unbalanced. */
function matchBack(text: string, closeIdx: number): number {
  const close = text[closeIdx];
  const open = close === "]" ? "[" : "(";
  let depth = 0;
  for (let k = closeIdx; k >= 0; k--) {
    const ch = text[k];
    if (ch === '"' || ch === "'") {
      k--;
      while (k >= 0 && !(text[k] === ch && text[k - 1] !== "\\")) k--;
      continue;
    }
    if (ch === close) depth++;
    else if (ch === open && --depth === 0) return k;
  }
  return -1;
}

/**
 * Walks back from `end` (exclusive) over an access chain (`a.b[0]["x"].c`,
 * `$node.id("n7").x`) and returns its start index, or -1 when there is none.
 */
function chainStart(text: string, end: number): number {
  let i = end;
  for (;;) {
    let j = i;
    if (text[j - 1] === "]") {
      const open = matchBack(text, j - 1);
      if (open <= 0) return -1; // unbalanced, or a bare array literal
      i = open;
      // a bracket group hangs off an identifier, another group or a call
      if (!/[\p{L}\p{N}_$\])]/u.test(text[i - 1])) return -1;
      if (text[i - 1] === "]" || text[i - 1] === ")") {
        if (text[i - 1] === ")") {
          const o = matchBack(text, i - 1);
          if (o < 0) return -1;
          i = o;
        } else continue;
      }
      j = i;
      while (j > 0 && IDENT_PART.test(text[j - 1])) j--;
    } else if (text[j - 1] === ")") {
      const open = matchBack(text, j - 1);
      if (open < 0) return -1;
      j = open;
      const calleeEnd = j;
      while (j > 0 && IDENT_PART.test(text[j - 1])) j--;
      if (j === calleeEnd) return -1;
    } else {
      const identEnd = j;
      while (j > 0 && IDENT_PART.test(text[j - 1])) j--;
      if (j === identEnd) return -1;
    }
    if (text[j - 1] === ".") {
      i = text[j - 2] === "?" ? j - 2 : j - 1;
      continue;
    }
    return j;
  }
}

/**
 * What can be completed at `cursor`? Null when the caret is outside {{ }},
 * inside an ordinary string literal, right after a complete term (unless
 * `force`), or in the middle of a number.
 */
export function completionContextAt(template: string, cursor: number, force = false): CompletionContext | null {
  const seg = findSegmentAt(template, cursor);
  if (!seg) return null;
  const before = template.slice(seg.innerStart, cursor);
  const str = scanString(before);
  if (str.quote) {
    const head = before.slice(0, str.start);
    if (!/\$node\s*\[\s*$/.test(head)) return null;
    const quote = str.quote as '"' | "'";
    const contentStart = seg.innerStart + str.start + 1;
    // Existing closing quote (same segment) means the label is being edited in place.
    let closeIdx = -1;
    for (let i = cursor; i < (seg.closed ? seg.innerEnd : template.length); i++) {
      if (template[i] === "\\") i++;
      else if (template[i] === quote) {
        closeIdx = i;
        break;
      }
    }
    return {
      kind: "node-label",
      prefix: unquoteString(quote + template.slice(contentStart, cursor) + quote),
      replaceStart: contentStart,
      replaceEnd: closeIdx >= 0 ? closeIdx : cursor,
      quote,
      closed: closeIdx >= 0,
    };
  }
  const m = /[\p{L}\p{N}_$]*$/u.exec(before)!;
  const prefix = m[0];
  if (/^\d/.test(prefix)) return null;
  let replaceEnd = cursor;
  while (replaceEnd < template.length && replaceEnd < (seg.closed ? seg.innerEnd : template.length) && IDENT_PART.test(template[replaceEnd])) replaceEnd++;
  const prefixStart = before.length - prefix.length;
  let p = prefixStart - 1;
  while (p >= 0 && /\s/.test(before[p])) p--;
  const prev = p >= 0 ? before[p] : "";
  if (prev === ".") {
    const dotIdx = before[p - 1] === "?" ? p - 1 : p;
    const cs = chainStart(before, dotIdx);
    return {
      kind: "member",
      prefix,
      replaceStart: seg.innerStart + prefixStart,
      replaceEnd,
      chain: cs >= 0 ? before.slice(cs, dotIdx) : null,
      dotStart: seg.innerStart + dotIdx,
    };
  }
  // After a finished term we are at operator position: nothing to complete.
  if (!force && prefix === "" && prev && /[\p{L}\p{N}_$)\]"']/u.test(prev)) return null;
  return { kind: "ident", prefix, replaceStart: seg.innerStart + prefixStart, replaceEnd };
}

// ------------------------------------------------------------- completions

export type CompletionKind = "node" | "field" | "root" | "function" | "keyword";

export type CompletionItem = {
  /** What the popup shows. */
  label: string;
  /** What replaces the typed prefix. */
  insertText: string;
  kind: CompletionKind;
  detail?: string;
  doc?: string;
  example?: string;
  /** Caret moves this many characters back from the end of the inserted text. */
  caretBack?: number;
  /** Re-open the popup after applying (e.g. after `$node[""]`). */
  reopen?: boolean;
  /** Member insert that must swallow the preceding "." (bracket access). */
  replaceDot?: boolean;
  score: number;
};

export type CompletionSources = {
  nodes: UpstreamSchemaNode[];
  functions: ExpressionFunctionDoc[];
};

const KIND_PRIORITY: Record<CompletionKind, number> = { field: 0, node: 0, root: 1, function: 2, keyword: 3 };

/** Fuzzy match quality of `prefix` against `label`; 0 = no match. */
export function scoreMatch(prefix: string, label: string): number {
  if (!prefix) return 1;
  if (label.startsWith(prefix)) return 100 + Math.max(0, 20 - (label.length - prefix.length));
  const lp = prefix.toLowerCase();
  const ll = label.toLowerCase();
  if (ll.startsWith(lp)) return 90 + Math.max(0, 10 - (label.length - prefix.length));
  // word-boundary: "pr" matches "unitPrice", "unit_price", "$json.price"
  for (let i = 1; i < label.length; i++) {
    const c = label[i];
    const p = label[i - 1];
    const boundary = /[_\-\s.$]/.test(p) || (/[A-Z]/.test(c) && /[a-z\d]/.test(p));
    if (boundary && ll.startsWith(lp, i)) return 60;
  }
  if (ll.includes(lp)) return 40;
  let k = 0;
  for (const ch of ll) if (ch === lp[k]) k++;
  return k === lp.length ? 20 : 0;
}

export function rankCompletions(items: CompletionItem[], prefix: string, limit = 30): CompletionItem[] {
  return items
    .map((it) => ({ ...it, score: scoreMatch(prefix, it.label) }))
    .filter((it) => it.score > 0)
    .sort(
      (a, b) =>
        b.score - a.score ||
        KIND_PRIORITY[a.kind] - KIND_PRIORITY[b.kind] ||
        (a.label < b.label ? -1 : a.label > b.label ? 1 : 0),
    )
    .slice(0, limit);
}

function fnItem(f: ExpressionFunctionDoc): CompletionItem {
  return {
    label: f.name,
    insertText: `${f.name}()`,
    kind: "function",
    detail: f.signature,
    doc: f.doc,
    example: f.example,
    caretBack: 1,
    score: 0,
  };
}

function memberItem(name: string, type: string | null, extra: Partial<CompletionItem> = {}): CompletionItem {
  const plain = isIdentName(name);
  return {
    label: name,
    insertText: plain ? name : `[${quoteString(name)}]`,
    kind: "field",
    detail: type ?? undefined,
    replaceDot: !plain,
    score: 0,
    ...extra,
  };
}

/** Builds and ranks the popup entries for a context. */
export function buildCompletions(ctx: CompletionContext, src: CompletionSources, limit = 30): CompletionItem[] {
  const items: CompletionItem[] = [];
  if (ctx.kind === "node-label") {
    const seen = new Set<string>();
    for (const n of src.nodes) {
      if (!n.label || seen.has(n.label)) continue;
      seen.add(n.label);
      items.push({
        label: n.label,
        insertText: quoteString(n.label, ctx.quote).slice(1, -1),
        kind: "node",
        detail: n.source === "none" ? undefined : n.source,
        score: 0,
      });
    }
    return rankCompletions(items, ctx.prefix, limit);
  }

  if (ctx.kind === "ident") {
    for (const r of ROOTS) {
      items.push(
        r === "$node"
          ? { label: r, insertText: '$node[""]', kind: "root", doc: ROOT_DOCS[r], caretBack: 2, reopen: true, score: 0 }
          : { label: r, insertText: r, kind: "root", doc: ROOT_DOCS[r], score: 0 },
      );
    }
    for (const f of src.functions) items.push(fnItem(f));
    for (const k of KEYWORDS) items.push({ label: k, insertText: k, kind: "keyword", score: 0 });
    // Legacy bare names resolve against this node's input: offer the upstream top-level fields.
    const top = new Map<string, string | null>();
    for (const n of src.nodes) for (const c of childrenOf(n.paths, "")) if (!top.has(c.name) && isIdentName(c.name)) top.set(c.name, c.type);
    for (const [name, type] of top) items.push({ label: name, insertText: name, kind: "field", detail: type ?? undefined, score: 0 });
    return rankCompletions(items, ctx.prefix, limit);
  }

  // member
  const ref = ctx.chain ? parseChain(ctx.chain) : null;
  if (ref) {
    if (ref.root === "$node" && !ref.node) {
      items.push({ label: "id", insertText: 'id("")', kind: "field", detail: '$node.id("n7")', caretBack: 2, score: 0 });
    } else if (ref.root === "$node" && ref.node) {
      const nodes = src.nodes.filter((n) => (ref.node!.by === "id" ? n.nodeId === ref.node!.value : n.label === ref.node!.value));
      if (ref.path === "") for (const m of NODE_META_MEMBERS) items.push(memberItem(m, "string"));
      const seen = new Set<string>();
      for (const n of nodes) {
        for (const c of childrenOf(n.paths, ref.path)) {
          if (seen.has(c.name)) continue;
          seen.add(c.name);
          items.push(memberItem(c.name, c.type));
        }
      }
    } else if (ref.root === "$json" || ref.root === "$input" || !ref.root.startsWith("$")) {
      // $json = this node's input: the union of upstream shapes is the best a static editor can offer.
      const base = ref.root.startsWith("$") ? ref.path : [ref.root, ref.path].filter(Boolean).join(ref.path.startsWith("[") ? "" : ".");
      const seen = new Set<string>();
      for (const n of src.nodes) {
        for (const c of childrenOf(n.paths, base)) {
          if (seen.has(c.name)) continue;
          seen.add(c.name);
          items.push(memberItem(c.name, c.type));
        }
      }
    } else if (ROOT_MEMBERS[ref.root] && ref.path === "") {
      for (const m of ROOT_MEMBERS[ref.root]) items.push(memberItem(m, null));
    }
  }
  // `x.fn(args)` is sugar for `fn(x, args)`, so every function is valid after a dot.
  for (const f of src.functions) items.push(fnItem(f));
  return rankCompletions(items, ctx.prefix, limit);
}

export type ApplyResult = { value: string; cursor: number; reopen: boolean };

/** Applies a chosen completion to the template. */
export function applyCompletion(template: string, ctx: CompletionContext, item: CompletionItem): ApplyResult {
  if (ctx.kind === "node-label") {
    const tail = ctx.closed ? "" : `${ctx.quote}]`;
    const text = item.insertText + tail;
    // Closed already: step over the existing quote (and its "]") so the caret ends after the reference.
    const after = template.slice(ctx.replaceEnd);
    const skip = ctx.closed ? (after.startsWith(`${ctx.quote}]`) ? 2 : 1) : 0;
    const cursor = ctx.replaceStart + item.insertText.length + tail.length + skip;
    return { value: template.slice(0, ctx.replaceStart) + text + template.slice(ctx.replaceEnd), cursor, reopen: false };
  }
  let start = ctx.replaceStart;
  if (ctx.kind === "member" && item.replaceDot) start = ctx.dotStart;
  const text = item.insertText;
  return {
    value: template.slice(0, start) + text + template.slice(ctx.replaceEnd),
    cursor: start + text.length - (item.caretBack ?? 0),
    reopen: !!item.reopen,
  };
}
