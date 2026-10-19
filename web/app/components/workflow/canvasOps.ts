// Pure canvas operations for the workflow editor: layout, snapping/guides,
// clipboard, alignment and graph linting. No React, no DOM, no randomness -
// every function is deterministic for the same input (ids break ties, never
// array order) so results are testable and undo/redo stays predictable.
import type { Edge, Node } from "@xyflow/react";
import { renameNodeLabelInExpression, renameNodeLabelInTemplate } from "./expressionLang";

export type XY = { x: number; y: number };
export type PositionMap = Record<string, XY>;

export const NODE_WIDTH = 232;
export const NODE_HEIGHT = 96;

// Same string as nodeDefs.ts NODE_DEFS trigger `type`. Duplicated (not
// imported) so this module stays free of the icon/React imports nodeDefs pulls in.
const TRIGGER_TYPE = "trigger";
// Nodes that expose the red "error" port a failure route hangs off.
const ERROR_ROUTED_TYPES = new Set(["agent", "http", "tool"]);
const ERROR_HANDLE = "error";

type AnyNode = Node<Record<string, unknown>>;
type AnyEdge = Edge<Record<string, unknown>>;

function sizeOf(n: AnyNode): { w: number; h: number } {
  return {
    w: n.width ?? n.measured?.width ?? NODE_WIDTH,
    h: n.height ?? n.measured?.height ?? NODE_HEIGHT,
  };
}

const byId = (a: string, b: string) => (a < b ? -1 : a > b ? 1 : 0);
const clean = (v: number) => (v === 0 ? 0 : v); // never emit -0

// ---------------------------------------------------------------- autoLayout

export type AutoLayoutOptions = {
  columnGap?: number;
  rowGap?: number;
  nodeWidth?: number;
  nodeHeight?: number;
  origin?: XY;
};

/**
 * Layered left-to-right layout. Longest-path layering over the DAG left after
 * dropping DFS back-edges (loops), barycenter sweeps to cut crossings, and
 * disconnected components stacked vertically. Returns positions only.
 */
export function autoLayout(nodes: AnyNode[], edges: AnyEdge[], opts: AutoLayoutOptions = {}): PositionMap {
  const columnGap = opts.columnGap ?? 120;
  const rowGap = opts.rowGap ?? 40;
  const defW = opts.nodeWidth ?? NODE_WIDTH;
  const defH = opts.nodeHeight ?? NODE_HEIGHT;
  const origin = opts.origin ?? { x: 0, y: 0 };

  const ids = nodes.map((n) => n.id).sort(byId);
  const nodeById = new Map(nodes.map((n) => [n.id, n] as const));
  const dims = (id: string) => {
    const n = nodeById.get(id)!;
    return { w: n.width ?? n.measured?.width ?? defW, h: n.height ?? n.measured?.height ?? defH };
  };

  // Unique, valid, non-self edges as sorted adjacency (order-independent).
  const succ = new Map<string, string[]>(ids.map((id): [string, string[]] => [id, []]));
  const seen = new Set<string>();
  for (const e of edges) {
    if (e.source === e.target || !succ.has(e.source) || !succ.has(e.target)) continue;
    const key = `${e.source}\u0000${e.target}`;
    if (seen.has(key)) continue;
    seen.add(key);
    succ.get(e.source)!.push(e.target);
  }
  for (const list of succ.values()) list.sort(byId);

  // Roots first so a loop's back-edge is the one closing onto its entry node.
  const indeg = new Map<string, number>(ids.map((id) => [id, 0]));
  for (const list of succ.values()) for (const t of list) indeg.set(t, indeg.get(t)! + 1);
  const dfsOrder = [...ids.filter((id) => indeg.get(id) === 0), ...ids.filter((id) => indeg.get(id) !== 0)];

  const state = new Map<string, 0 | 1 | 2>(); // 1 = on stack, 2 = done
  const dag = new Map<string, string[]>(ids.map((id): [string, string[]] => [id, []]));
  const visit = (root: string) => {
    // Iterative DFS: deep chains must not blow the call stack.
    const stack: { id: string; i: number }[] = [{ id: root, i: 0 }];
    state.set(root, 1);
    while (stack.length) {
      const top = stack[stack.length - 1];
      const out = succ.get(top.id)!;
      if (top.i >= out.length) {
        state.set(top.id, 2);
        stack.pop();
        continue;
      }
      const t = out[top.i++];
      const st = state.get(t);
      if (st === 1) continue; // back-edge: dropped for layering
      dag.get(top.id)!.push(t);
      if (st === undefined) {
        state.set(t, 1);
        stack.push({ id: t, i: 0 });
      }
    }
  };
  for (const id of dfsOrder) if (!state.has(id)) visit(id);

  // Components over the undirected graph (union-find, smallest id as root).
  const parent = new Map<string, string>(ids.map((id) => [id, id]));
  const find = (a: string): string => {
    while (parent.get(a) !== a) {
      parent.set(a, parent.get(parent.get(a)!)!);
      a = parent.get(a)!;
    }
    return a;
  };
  for (const [s, list] of succ) {
    for (const t of list) {
      const a = find(s);
      const b = find(t);
      if (a !== b) parent.set(a < b ? b : a, a < b ? a : b);
    }
  }
  const components = new Map<string, string[]>();
  for (const id of ids) {
    const r = find(id);
    if (!components.has(r)) components.set(r, []);
    components.get(r)!.push(id);
  }

  const result: PositionMap = {};
  let cursorY = origin.y;

  for (const compIds of [...components.values()].sort((a, b) => byId(a[0], b[0]))) {
    const inComp = new Set(compIds);
    const preds = new Map<string, string[]>(compIds.map((id): [string, string[]] => [id, []]));
    for (const id of compIds) for (const t of dag.get(id)!) preds.get(t)!.push(id);

    // Longest path layering via Kahn on the DAG.
    const layerOf = new Map<string, number>(compIds.map((id) => [id, 0]));
    const remaining = new Map<string, number>(compIds.map((id) => [id, preds.get(id)!.length]));
    const queue = compIds.filter((id) => remaining.get(id) === 0);
    for (let qi = 0; qi < queue.length; qi++) {
      const id = queue[qi];
      for (const t of dag.get(id)!) {
        if (!inComp.has(t)) continue;
        layerOf.set(t, Math.max(layerOf.get(t)!, layerOf.get(id)! + 1));
        remaining.set(t, remaining.get(t)! - 1);
        if (remaining.get(t) === 0) queue.push(t);
      }
    }

    const layerCount = Math.max(...compIds.map((id) => layerOf.get(id)!)) + 1;
    const layers: string[][] = Array.from({ length: layerCount }, (): string[] => []);
    for (const id of compIds) layers[layerOf.get(id)!].push(id); // compIds already id-sorted

    // Barycenter sweeps. Ties keep previous order (stable sort), seeded by id.
    const rank = new Map<string, number>();
    const reindex = () => layers.forEach((l) => l.forEach((id, i) => rank.set(id, i)));
    reindex();
    const succInDag = (id: string) => dag.get(id)!;
    const sortLayer = (layer: string[], neighbours: (id: string) => string[]) => {
      const bary = new Map<string, number>();
      for (const id of layer) {
        const ns = neighbours(id);
        bary.set(id, ns.length ? ns.reduce((s, n) => s + rank.get(n)!, 0) / ns.length : rank.get(id)!);
      }
      layer.sort((a, b) => bary.get(a)! - bary.get(b)! || rank.get(a)! - rank.get(b)! || byId(a, b));
      layer.forEach((id, i) => rank.set(id, i));
    };
    for (let sweep = 0; sweep < 4; sweep++) {
      for (let l = 1; l < layerCount; l++) sortLayer(layers[l], (id) => preds.get(id)!);
      for (let l = layerCount - 2; l >= 0; l--) sortLayer(layers[l], succInDag);
    }

    // Coordinates: column x from widest node per layer, columns centred vertically.
    const colHeights = layers.map((l) => l.reduce((s, id) => s + dims(id).h, 0) + rowGap * Math.max(0, l.length - 1));
    const compHeight = Math.max(...colHeights);
    let x = origin.x;
    layers.forEach((layer, li) => {
      let y = cursorY + (compHeight - colHeights[li]) / 2;
      for (const id of layer) {
        result[id] = { x: clean(x), y: clean(y) };
        y += dims(id).h + rowGap;
      }
      x += Math.max(...layer.map((id) => dims(id).w)) + columnGap;
    });
    cursorY += compHeight + rowGap * 2;
  }

  return result;
}

// ------------------------------------------------------------ snap & guides

export function snapPosition(pos: XY, grid = 10): XY {
  if (!(grid > 0)) return { x: pos.x, y: pos.y };
  return { x: clean(Math.round(pos.x / grid) * grid), y: clean(Math.round(pos.y / grid) * grid) };
}

export type Rect = { x: number; y: number; width?: number; height?: number };
export type AlignGuide = {
  // "x" = a vertical line at x = value; "y" = a horizontal line at y = value.
  axis: "x" | "y";
  value: number;
  kind: "start" | "center" | "end";
  // Extent along the other axis, spanning dragged + matching nodes.
  from: number;
  to: number;
};

const rw = (r: Rect) => r.width ?? NODE_WIDTH;
const rh = (r: Rect) => r.height ?? NODE_HEIGHT;

/**
 * Snaps a dragged rect to edge/centre lines of other nodes within `threshold`
 * and reports which lines matched so the UI can draw them.
 */
export function computeAlignGuides(
  dragged: Rect,
  others: Rect[],
  threshold = 6,
): { position: XY; guides: AlignGuide[] } {
  const kinds = ["start", "center", "end"] as const;
  const anchors = (start: number, size: number) => [start, start + size / 2, start + size];

  const solve = (axis: "x" | "y") => {
    const size = axis === "x" ? rw(dragged) : rh(dragged);
    const start = axis === "x" ? dragged.x : dragged.y;
    const mine = anchors(start, size);
    let best: number | null = null;
    for (const o of others) {
      const theirs = anchors(axis === "x" ? o.x : o.y, axis === "x" ? rw(o) : rh(o));
      for (const a of mine) for (const b of theirs) {
        const d = b - a;
        if (Math.abs(d) <= threshold && (best === null || Math.abs(d) < Math.abs(best))) best = d;
      }
    }
    const snapped = start + (best ?? 0);
    const guides: AlignGuide[] = [];
    if (best !== null) {
      const mineSnapped = anchors(snapped, size);
      const seenKey = new Set<string>();
      mineSnapped.forEach((a, ai) => {
        for (const o of others) {
          const os = axis === "x" ? o.x : o.y;
          const osz = axis === "x" ? rw(o) : rh(o);
          if (!anchors(os, osz).some((b) => Math.abs(b - a) < 1e-6)) continue;
          const key = `${ai}:${a}`;
          const cross = axis === "x" ? [o.y, o.y + rh(o)] : [o.x, o.x + rw(o)];
          const own = axis === "x" ? [dragged.y, dragged.y + rh(dragged)] : [dragged.x, dragged.x + rw(dragged)];
          const existing = guides.find((g) => `${kinds.indexOf(g.kind)}:${g.value}` === key);
          const lo = Math.min(...cross, ...own);
          const hi = Math.max(...cross, ...own);
          if (existing) {
            existing.from = Math.min(existing.from, lo);
            existing.to = Math.max(existing.to, hi);
          } else if (!seenKey.has(key)) {
            seenKey.add(key);
            guides.push({ axis, value: clean(a), kind: kinds[ai], from: lo, to: hi });
          }
        }
      });
    }
    return { snapped: clean(snapped), guides };
  };

  const sx = solve("x");
  const sy = solve("y");
  return { position: { x: sx.snapped, y: sy.snapped }, guides: [...sx.guides, ...sy.guides] };
}

// ------------------------------------------------------------- clipboard

export type ClipboardPayload<N extends AnyNode = AnyNode, E extends AnyEdge = AnyEdge> = {
  version: 1;
  nodes: N[];
  edges: E[];
};

const clone = <T,>(v: T): T => JSON.parse(JSON.stringify(v));

/** Selected nodes plus only the edges whose both ends are selected. */
export function copySelection<N extends AnyNode, E extends AnyEdge>(nodes: N[], edges: E[]): ClipboardPayload<N, E> {
  const picked = nodes.filter((n) => n.selected);
  const ids = new Set(picked.map((n) => n.id));
  return {
    version: 1,
    nodes: clone(picked),
    edges: clone(edges.filter((e) => ids.has(e.source) && ids.has(e.target))),
  };
}

function freshId(type: string, taken: Set<string>): string {
  // Deterministic counter (not Math.random) keeps paste pure and testable.
  for (let n = 1; ; n++) {
    const id = `${type || "node"}-c${n.toString(36)}`;
    if (!taken.has(id)) {
      taken.add(id);
      return id;
    }
  }
}

/**
 * Turns a clipboard payload into new, selected nodes/edges with fresh ids.
 * Trigger nodes are copied like any other node (multi-trigger graphs are valid).
 */
export function pasteClipboard<N extends AnyNode, E extends AnyEdge>(
  payload: ClipboardPayload<N, E>,
  existingIds: Iterable<string>,
  offset: XY = { x: 40, y: 40 },
): { nodes: N[]; edges: E[]; idMap: Record<string, string> } {
  const taken = new Set(existingIds);
  const idMap: Record<string, string> = {};
  const outNodes: N[] = [];
  for (const n of payload.nodes) {
    const id = freshId(n.type ?? "node", taken);
    idMap[n.id] = id;
    outNodes.push({
      ...clone(n),
      id,
      position: { x: n.position.x + offset.x, y: n.position.y + offset.y },
      selected: true,
    });
  }
  const usedEdgeIds = new Set<string>();
  const outEdges: E[] = [];
  for (const e of payload.edges) {
    const s = idMap[e.source];
    const t = idMap[e.target];
    if (!s || !t) continue;
    const base = `edge-${s}-${e.sourceHandle ?? "out"}-${t}-${e.targetHandle ?? "in"}`;
    let id = base;
    for (let i = 2; usedEdgeIds.has(id); i++) id = `${base}-${i}`;
    usedEdgeIds.add(id);
    outEdges.push({ ...clone(e), id, source: s, target: t, selected: true });
  }
  return { nodes: outNodes, edges: outEdges, idMap };
}

/** Copy + paste of the current selection in one step; returns only the new items. */
export function duplicateSelection<N extends AnyNode, E extends AnyEdge>(
  nodes: N[],
  edges: E[],
  offset: XY = { x: 40, y: 40 },
) {
  return pasteClipboard(copySelection(nodes, edges), nodes.map((n) => n.id), offset);
}

// ------------------------------------------------------------- alignment

export type AlignMode = "left" | "center" | "right" | "top" | "middle" | "bottom";

/** New positions for selected nodes (empty unless 2+ are selected). */
export function alignSelection(nodes: AnyNode[], mode: AlignMode): PositionMap {
  const sel = nodes.filter((n) => n.selected);
  if (sel.length < 2) return {};
  const boxes = sel.map((n) => ({ n, ...sizeOf(n) }));
  const minX = Math.min(...boxes.map((b) => b.n.position.x));
  const maxX = Math.max(...boxes.map((b) => b.n.position.x + b.w));
  const minY = Math.min(...boxes.map((b) => b.n.position.y));
  const maxY = Math.max(...boxes.map((b) => b.n.position.y + b.h));
  const out: PositionMap = {};
  for (const { n, w, h } of boxes) {
    let { x, y } = n.position;
    if (mode === "left") x = minX;
    else if (mode === "right") x = maxX - w;
    else if (mode === "center") x = (minX + maxX) / 2 - w / 2;
    else if (mode === "top") y = minY;
    else if (mode === "bottom") y = maxY - h;
    else y = (minY + maxY) / 2 - h / 2;
    out[n.id] = { x: clean(x), y: clean(y) };
  }
  return out;
}

/** Equalises the gaps between selected nodes along an axis (needs 3+). */
export function distributeSelection(nodes: AnyNode[], axis: "horizontal" | "vertical"): PositionMap {
  const sel = nodes.filter((n) => n.selected);
  if (sel.length < 3) return {};
  const horiz = axis === "horizontal";
  const items = sel
    .map((n) => ({ n, ...sizeOf(n) }))
    .sort((a, b) => {
      const d = horiz ? a.n.position.x - b.n.position.x : a.n.position.y - b.n.position.y;
      return d || byId(a.n.id, b.n.id);
    });
  const size = (i: { w: number; h: number }) => (horiz ? i.w : i.h);
  const pos = (i: { n: AnyNode }) => (horiz ? i.n.position.x : i.n.position.y);
  const first = items[0];
  const last = items[items.length - 1];
  const span = pos(last) + size(last) - pos(first);
  const gap = (span - items.reduce((s, i) => s + size(i), 0)) / (items.length - 1);
  const out: PositionMap = {};
  let cursor = pos(first);
  for (const it of items) {
    out[it.n.id] = horiz ? { x: clean(cursor), y: it.n.position.y } : { x: it.n.position.x, y: clean(cursor) };
    cursor += size(it) + gap;
  }
  return out;
}

// ------------------------------------------------------------------ lint

export type LintIssue = {
  nodeId?: string;
  severity: "warn" | "error";
  code:
    | "no-trigger"
    | "disconnected-node"
    | "unreachable-from-trigger"
    | "missing-error-route"
    | "loop-no-max-iterations";
  message: string;
};

const labelOf = (n: AnyNode) => (n.data?.label as string) || n.type || n.id;

export function lintGraph(nodes: AnyNode[], edges: AnyEdge[]): LintIssue[] {
  const issues: LintIssue[] = [];
  const ids = new Set(nodes.map((n) => n.id));
  const valid = edges.filter((e) => ids.has(e.source) && ids.has(e.target));
  const triggers = nodes.filter((n) => n.type === TRIGGER_TYPE);

  if (nodes.length > 0 && triggers.length === 0) {
    issues.push({ severity: "error", code: "no-trigger", message: "Add a Trigger node so the workflow has a starting point." });
  }

  const touched = new Set<string>();
  const out = new Map<string, string[]>();
  for (const e of valid) {
    touched.add(e.source);
    touched.add(e.target);
    if (!out.has(e.source)) out.set(e.source, []);
    out.get(e.source)!.push(e.target);
  }

  const reach = new Set<string>();
  const stack = triggers.map((t) => t.id);
  while (stack.length) {
    const id = stack.pop()!;
    if (reach.has(id)) continue;
    reach.add(id);
    for (const t of out.get(id) ?? []) stack.push(t);
  }

  for (const n of nodes) {
    const name = labelOf(n);
    if (nodes.length > 1 && !touched.has(n.id)) {
      issues.push({ nodeId: n.id, severity: "warn", code: "disconnected-node", message: `"${name}" is not connected to anything.` });
    } else if (triggers.length > 0 && touched.has(n.id) && !reach.has(n.id)) {
      issues.push({ nodeId: n.id, severity: "warn", code: "unreachable-from-trigger", message: `"${name}" can never run: no path leads to it from a Trigger.` });
    }
    if (n.type && ERROR_ROUTED_TYPES.has(n.type) && !valid.some((e) => e.source === n.id && e.sourceHandle === ERROR_HANDLE)) {
      issues.push({ nodeId: n.id, severity: "warn", code: "missing-error-route", message: `"${name}" has no error route; a failure will stop the run.` });
    }
    if (n.type === "loop") {
      const max = (n.data?.config as { maxIterations?: unknown } | undefined)?.maxIterations;
      if (typeof max !== "number" || !Number.isFinite(max) || max <= 0) {
        issues.push({ nodeId: n.id, severity: "warn", code: "loop-no-max-iterations", message: `"${name}" has no max iterations; a long list could run away.` });
      }
    }
  }

  const rank = { error: 0, warn: 1 } as const;
  return issues.sort(
    (a, b) => rank[a.severity] - rank[b.severity] || byId(a.code, b.code) || byId(a.nodeId ?? "", b.nodeId ?? ""),
  );
}

// ------------------------------------------------------------ label rename

/**
 * Deep-rewrites `$node["Old"]` references in every string of a config value.
 * Strings are templates ({{ }} segments); the condition node's `expression`
 * key is a bare expression. Untouched sub-trees keep their identity so callers
 * (and undo snapshots) can compare by reference.
 */
export function renameLabelInConfig<T>(value: T, oldLabel: string, newLabel: string, key?: string): T {
  if (typeof value === "string") {
    const next =
      key === "expression"
        ? renameNodeLabelInExpression(value, oldLabel, newLabel)
        : renameNodeLabelInTemplate(value, oldLabel, newLabel);
    return next as unknown as T;
  }
  if (Array.isArray(value)) {
    let changed = false;
    const out = value.map((v) => {
      const n = renameLabelInConfig(v, oldLabel, newLabel);
      if (n !== v) changed = true;
      return n;
    });
    return (changed ? out : value) as unknown as T;
  }
  if (value && typeof value === "object") {
    let changed = false;
    const out: Record<string, unknown> = {};
    for (const [k, v] of Object.entries(value as Record<string, unknown>)) {
      const n = renameLabelInConfig(v, oldLabel, newLabel, k);
      if (n !== v) changed = true;
      out[k] = n;
    }
    return (changed ? out : value) as T;
  }
  return value;
}

/**
 * Renames a node and rewrites every `$node["Old"]` reference in ALL nodes'
 * config in one pure step, so the caller applies it with one setNodes and the
 * rename plus its rewrites are a single undo entry. `oldLabelOverride` lets a
 * caller bridge a transient empty label while the author retypes it.
 *
 * References are left alone when the old or new label is blank, or when
 * another node still carries the old label (the reference is ambiguous, and
 * the linter reports AMBIGUOUS_LABEL for it).
 */
export function renameNodeInGraph<N extends AnyNode>(
  nodes: N[],
  nodeId: string,
  newLabel: string,
  oldLabelOverride?: string,
): N[] {
  const target = nodes.find((n) => n.id === nodeId);
  if (!target) return nodes;
  const oldLabel = ((target.data?.label as string) || oldLabelOverride || "").trim();
  const rewrite =
    oldLabel !== "" && newLabel.trim() !== "" && oldLabel !== newLabel &&
    !nodes.some((n) => n.id !== nodeId && ((n.data?.label as string) || "").trim() === oldLabel);
  return nodes.map((n) => {
    let data = n.data;
    if (n.id === nodeId && data?.label !== newLabel) data = { ...data, label: newLabel };
    if (rewrite && data?.config !== undefined) {
      const config = renameLabelInConfig(data.config, oldLabel, newLabel);
      if (config !== data.config) data = { ...data, config };
    }
    return data === n.data ? n : { ...n, data };
  });
}

/**
 * The author clears a label to retype it: `$node["Old"]` references must follow
 * the eventual new label, not be lost while the field is blank. Given the
 * node's current label, the label being set and the bridge remembered so far,
 * returns the `oldLabelOverride` for renameNodeInGraph and the bridge to keep.
 */
export function nextLabelBridge(
  currentLabel: string,
  newLabel: string,
  bridge: string | undefined,
): { override: string | undefined; bridge: string | undefined } {
  const before = currentLabel.trim();
  const override = before === "" ? bridge : undefined;
  if (before !== "" && newLabel.trim() === "") return { override, bridge: before };
  if (newLabel.trim() !== "") return { override, bridge: undefined };
  return { override, bridge };
}
