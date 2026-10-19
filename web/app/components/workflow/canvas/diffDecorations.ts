import { edgeKey, type DiffDecorations } from "../versionDiff";

const NODE_CLASS = { added: "wf-diff-added", changed: "wf-diff-changed", removed: "wf-diff-removed" } as const;
const EDGE_CLASS = { added: "wf-diff-added", removed: "wf-diff-removed" } as const;

function join(base: unknown, extra: string): string {
  return typeof base === "string" && base ? `${base} ${extra}` : extra;
}

/**
 * Paints the version-diff "Show on canvas" overlay onto already-decorated
 * nodes. Returns the SAME array when there is nothing to paint, and the same
 * node object for nodes the diff does not mention (keeps React Flow memo hits).
 * "removed" entries are not on the canvas, so they never match.
 */
export function applyNodeDiff<N extends { id: string; className?: string }>(
  nodes: N[],
  deco: DiffDecorations | null
): N[] {
  if (!deco) return nodes;
  let touched = false;
  const out = nodes.map((n) => {
    const kind = deco.nodes[n.id];
    if (!kind || kind === "removed") return n;
    touched = true;
    return { ...n, className: join(n.className, NODE_CLASS[kind]) };
  });
  return touched ? out : nodes;
}

export function applyEdgeDiff<E extends { id?: string; source?: string; target?: string; sourceHandle?: string | null; className?: string }>(
  edges: E[],
  deco: DiffDecorations | null
): E[] {
  if (!deco) return edges;
  let touched = false;
  const out = edges.map((e) => {
    const kind = deco.edges[edgeKey(e)];
    if (!kind || kind === "removed") return e;
    touched = true;
    return { ...e, className: join(e.className, EDGE_CLASS[kind]) };
  });
  return touched ? out : edges;
}
