// Expression lint plumbing for the canvas lint chip / LintPanel. Pure: collects
// every expression-enabled field of the graph, runs the client-only quick
// checks, and folds server diagnostics (POST /expressions/lint) into the
// unified lint issue shape lintMerge already merges and sorts.
import type { ExpressionDiagnostic, LintItem } from "../../lib/api/workflowExpressions";
import { BARE_PREFIX_LEN, quickCheckTemplate, wrapBare, findSegments } from "./expressionLang";
import { EXPRESSION_FIELDS, isBareExpressionField } from "./nodeDefs";
import { sha256, sortLint, type UnifiedLintIssue } from "./lintMerge";

type NodeLike = { id: string; type?: string; data?: { label?: unknown; config?: unknown } | Record<string, unknown> };

export type ExprItem = LintItem & { nodeLabel: string; bare: boolean };

/** Max items per lint request; a graph past this is linted in its first N fields. */
export const MAX_LINT_ITEMS = 200;

/** Reads every string a field spec ("a.b", "list[].value") addresses, with its concrete field path. */
export function readFieldStrings(config: unknown, spec: string): { field: string; value: string }[] {
  const out: { field: string; value: string }[] = [];
  const parts = spec.split(".");
  const walk = (node: unknown, i: number, path: string) => {
    if (node == null || typeof node !== "object") return;
    const part = parts[i];
    const isArr = part.endsWith("[]");
    const key = isArr ? part.slice(0, -2) : part;
    const child = (node as Record<string, unknown>)[key];
    const here = path ? `${path}.${key}` : key;
    if (isArr) {
      if (!Array.isArray(child)) return;
      child.forEach((el, idx) => {
        if (i === parts.length - 1) {
          if (typeof el === "string") out.push({ field: `${here}[${idx}]`, value: el });
        } else walk(el, i + 1, `${here}[${idx}]`);
      });
    } else if (i === parts.length - 1) {
      if (typeof child === "string") out.push({ field: here, value: child });
    } else walk(child, i + 1, here);
  };
  walk(config, 0, "");
  return out;
}

/** Every non-empty expression-enabled string in the graph. */
export function collectExpressionItems(nodes: NodeLike[], max = MAX_LINT_ITEMS): ExprItem[] {
  const items: ExprItem[] = [];
  for (const n of nodes) {
    const specs = (n.type && EXPRESSION_FIELDS[n.type]) || [];
    if (!specs.length) continue;
    const data = (n.data || {}) as { label?: unknown; config?: unknown };
    for (const spec of specs) {
      for (const { field, value } of readFieldStrings(data.config, spec)) {
        const bare = isBareExpressionField(n.type, spec);
        // Fixed text with no {{ }} cannot contain an expression error; bare fields are always expressions.
        if (!value.trim() || (!bare && findSegments(value).length === 0)) continue;
        items.push({ nodeId: n.id, field, template: value, nodeLabel: typeof data.label === "string" ? data.label : "", bare });
        if (items.length >= max) return items;
      }
    }
  }
  return items;
}

export type ItemDiagnostic = {
  nodeId: string;
  field: string;
  severity: "error" | "warning";
  code: string;
  message: string;
  start: number;
  end: number;
  source: "client" | "server";
};

/** Client-only syntax checks across the items, in stored-text coordinates. */
export function quickDiagnostics(items: ExprItem[]): ItemDiagnostic[] {
  const out: ItemDiagnostic[] = [];
  for (const it of items) {
    const text = it.bare ? wrapBare(it.template) : it.template;
    const shift = it.bare ? BARE_PREFIX_LEN : 0;
    for (const d of quickCheckTemplate(text)) {
      // The synthetic wrapper's own braces are never the author's problem.
      if (it.bare && (d.code === "EMPTY_EXPRESSION" || d.code === "UNCLOSED_BRACES")) continue;
      out.push({
        nodeId: it.nodeId,
        field: it.field,
        severity: d.severity,
        code: d.code,
        message: d.message,
        start: Math.max(0, d.start - shift),
        end: Math.max(0, d.end - shift),
        source: "client",
      });
    }
  }
  return out;
}

export type ServerExprLint = {
  diagnostics: ExpressionDiagnostic[];
  /** Template text each item had when it was sent, keyed by nodeId + field, to detect staleness. */
  sent: Record<string, string>;
};

export const itemKey = (nodeId: string, field: string) => `${nodeId}\u0000${field}`;

/** The request body for the server lint, plus the snapshot needed to validate its answer later. */
export function buildLintRequest(items: ExprItem[]): { body: LintItem[]; sent: Record<string, string> } {
  const sent: Record<string, string> = {};
  const body = items.map((it) => {
    sent[itemKey(it.nodeId, it.field)] = it.template;
    // A bare field (condition.expression) has no braces in storage; the server only lints {{ }} segments.
    return { nodeId: it.nodeId, field: it.field, template: it.bare ? wrapBare(it.template) : it.template };
  });
  return { body, sent };
}

function issueId(d: ItemDiagnostic): string {
  const bytes = sha256(new TextEncoder().encode(`expr|${d.code}|${d.nodeId}|${d.field}|${d.start}|${d.end}|${d.message}`));
  let hex = "";
  for (const b of bytes) hex += b.toString(16).padStart(2, "0");
  return `x${hex.slice(0, 15)}`;
}

function toIssue(d: ItemDiagnostic): UnifiedLintIssue {
  return {
    id: issueId(d),
    code: `expression-${d.code.toLowerCase().replace(/_/g, "-")}`,
    severity: d.severity,
    nodeId: d.nodeId,
    edgeId: null,
    message: `Field "${d.field}": ${d.message}`,
    fix: { kind: "open-config", nodeId: d.nodeId, field: d.field },
    source: d.source,
  };
}

/**
 * Client quick checks + server diagnostics for the CURRENT items. A server
 * answer is dropped for any item whose text changed since it was sent (its
 * positions no longer mean anything); and where the server already reports an
 * error for an item, the client's cruder syntax errors for it are dropped so
 * one mistake is not listed twice.
 */
export function mergeExpressionLint(items: ExprItem[], server: ServerExprLint | null): UnifiedLintIssue[] {
  const current = new Map(items.map((i) => [itemKey(i.nodeId, i.field), i.template]));
  const bareKeys = new Set(items.filter((i) => i.bare).map((i) => itemKey(i.nodeId, i.field)));
  const serverDiags: ItemDiagnostic[] = [];
  const serverErrorKeys = new Set<string>();
  if (server) {
    for (const d of server.diagnostics) {
      const key = itemKey(d.nodeId, d.field);
      if (!current.has(key) || server.sent[key] !== current.get(key)) continue;
      // Server positions refer to the wrapped text; move them back onto the stored expression.
      const shift = bareKeys.has(key) ? BARE_PREFIX_LEN : 0;
      serverDiags.push({
        ...d,
        start: Math.max(0, d.start - shift),
        end: Math.max(0, d.end - shift),
        source: "server",
      });
      if (d.severity === "error") serverErrorKeys.add(key);
    }
  }
  const quick = quickDiagnostics(items).filter((d) => !serverErrorKeys.has(itemKey(d.nodeId, d.field)));
  return sortLint([...serverDiags, ...quick].map(toIssue)).filter(
    (issue, i, all) => all.findIndex((o) => o.id === issue.id) === i,
  );
}
