import { describe, expect, it } from "vitest";
import {
  alignSelection,
  autoLayout,
  computeAlignGuides,
  copySelection,
  distributeSelection,
  duplicateSelection,
  lintGraph,
  pasteClipboard,
  nextLabelBridge,
  renameNodeInGraph,
  snapPosition,
} from "./canvasOps";

type N = {
  id: string;
  type?: string;
  position: { x: number; y: number };
  data: Record<string, unknown>;
  selected?: boolean;
  width?: number;
  height?: number;
};
const node = (id: string, type = "agent", x = 0, y = 0, extra: Partial<N> = {}): N => ({
  id,
  type,
  position: { x, y },
  data: { label: id, config: {} },
  ...extra,
});
const edge = (source: string, target: string, sourceHandle?: string) => ({
  id: `${source}-${target}-${sourceHandle ?? ""}`,
  source,
  target,
  sourceHandle,
});

// Seeded Fisher-Yates so shuffles are reproducible.
const shuffle = <T,>(a: T[], seed: number) => {
  const r = [...a];
  let s = seed;
  for (let i = r.length - 1; i > 0; i--) {
    s = (s * 9301 + 49297) % 233280;
    const j = s % (i + 1);
    [r[i], r[j]] = [r[j], r[i]];
  }
  return r;
};

describe("autoLayout", () => {
  const nodes = [node("t", "trigger"), node("a"), node("b"), node("c"), node("d", "output")];
  const edges = [edge("t", "a"), edge("t", "b"), edge("a", "c"), edge("b", "c"), edge("c", "d")];

  it("layers left to right with the specified gaps", () => {
    const p = autoLayout(nodes, edges);
    expect(p.t.x).toBe(0);
    expect(p.a.x).toBe(232 + 120);
    expect(p.b.x).toBe(p.a.x);
    expect(p.c.x).toBe(2 * (232 + 120));
    expect(p.d.x).toBe(3 * (232 + 120));
    expect(Math.abs(p.b.y - p.a.y)).toBe(96 + 40);
  });

  it("is identical regardless of array order", () => {
    const base = autoLayout(nodes, edges);
    for (let seed = 1; seed < 8; seed++) {
      expect(autoLayout(shuffle(nodes, seed), shuffle(edges, seed + 3))).toEqual(base);
    }
  });

  it("survives a loop back-edge and a self-loop", () => {
    const g = [edge("t", "a"), edge("a", "b"), edge("b", "a"), edge("b", "b"), edge("b", "c")];
    const p = autoLayout(nodes.slice(0, 4), g);
    expect(p.t.x).toBeLessThan(p.a.x);
    expect(p.a.x).toBeLessThan(p.b.x);
    expect(p.b.x).toBeLessThan(p.c.x);
  });

  it("handles a pure cycle without hanging", () => {
    const p = autoLayout([node("x"), node("y")], [edge("x", "y"), edge("y", "x")]);
    expect(Object.keys(p).sort()).toEqual(["x", "y"]);
    expect(p.x.x).not.toBe(p.y.x);
  });

  it("stacks disconnected components without overlap", () => {
    const p = autoLayout([node("a"), node("b"), node("c")], [edge("a", "b")]);
    expect(p.c.y).toBeGreaterThanOrEqual(p.a.y + 96);
  });

  it("orders by barycenter to avoid an avoidable crossing", () => {
    const g = [node("r"), node("a"), node("b"), node("x"), node("y")];
    const e = [edge("r", "a"), edge("r", "b"), edge("a", "y"), edge("b", "x")];
    const p = autoLayout(g, e);
    // Children must sit in the same vertical order as their parents.
    expect(p.b.y > p.a.y).toBe(p.x.y > p.y.y);
  });

  it("ignores edges to unknown nodes and handles empty input", () => {
    expect(autoLayout([], [])).toEqual({});
    expect(autoLayout([node("a")], [edge("a", "ghost")])).toEqual({ a: { x: 0, y: 0 } });
  });
});

describe("snapPosition and computeAlignGuides", () => {
  it("snaps to the grid", () => {
    expect(snapPosition({ x: 14, y: 16 })).toEqual({ x: 10, y: 20 });
    expect(snapPosition({ x: 23, y: -4 }, 25)).toEqual({ x: 25, y: 0 });
    expect(Object.is(snapPosition({ x: -1, y: 0 }).x, -0)).toBe(false);
  });

  it("snaps left edges and reports a vertical guide", () => {
    const r = computeAlignGuides({ x: 103, y: 300 }, [{ x: 100, y: 0 }]);
    expect(r.position).toEqual({ x: 100, y: 300 });
    expect(r.guides).toContainEqual(expect.objectContaining({ axis: "x", value: 100, kind: "start" }));
  });

  it("snaps centres across different widths", () => {
    const other = { x: 200, y: 0, width: 200, height: 50 };
    const far = computeAlignGuides({ x: 0, y: 500, width: 100, height: 50 }, [other]);
    expect(far.position.x).toBe(0);
    const near = computeAlignGuides({ x: 246, y: 500, width: 100, height: 50 }, [other]);
    // dragged centre 296 -> 300
    expect(near.position.x).toBe(250);
    expect(near.guides.some((g) => g.axis === "x" && g.kind === "center" && g.value === 300)).toBe(true);
  });

  it("snaps y independently of x", () => {
    const r = computeAlignGuides({ x: 900, y: 205 }, [{ x: 0, y: 200 }]);
    expect(r.position).toEqual({ x: 900, y: 200 });
    expect(r.guides.every((g) => g.axis === "y")).toBe(true);
  });

  it("does nothing outside the threshold", () => {
    const r = computeAlignGuides({ x: 50, y: 50 }, [{ x: 300, y: 300 }], 6);
    expect(r.position).toEqual({ x: 50, y: 50 });
    expect(r.guides).toEqual([]);
  });
});

describe("clipboard", () => {
  const nodes = [
    node("trigger-1", "trigger", 0, 0, { selected: true }),
    node("agent-1", "agent", 100, 0, { selected: true }),
    node("out-1", "output", 200, 0, { selected: true }),
    node("other", "agent", 300, 0),
  ];
  const edges = [
    edge("trigger-1", "agent-1"),
    edge("agent-1", "out-1"),
    edge("out-1", "other"),
    edge("agent-1", "out-1", "error"),
  ];

  it("copies selected nodes and only fully-internal edges", () => {
    const c = copySelection(nodes, edges);
    expect(c.nodes.map((n) => n.id)).toEqual(["trigger-1", "agent-1", "out-1"]);
    expect(c.edges).toHaveLength(3);
    expect(c.edges.every((e) => e.target !== "other")).toBe(true);
  });

  it("pastes with fresh ids, remapped edges, offset, selected, triggers included", () => {
    const r = pasteClipboard(copySelection(nodes, edges), nodes.map((n) => n.id), { x: 50, y: 20 });
    expect(r.nodes.map((n) => n.type)).toEqual(["trigger", "agent", "output"]);
    const existing = new Set(nodes.map((n) => n.id));
    for (const n of r.nodes) {
      expect(existing.has(n.id)).toBe(false);
      expect(n.selected).toBe(true);
    }
    expect(r.nodes[0].position).toEqual({ x: 50, y: 20 });
    // trigger->agent plus both agent->out edges remapped.
    expect(r.edges).toHaveLength(3);
    const [t, a, o] = r.nodes.map((n) => n.id);
    expect(r.edges.some((e) => e.source === t && e.target === a)).toBe(true);
    expect(r.edges.filter((e) => e.source === a && e.target === o)).toHaveLength(2);
    expect(new Set(r.edges.map((e) => e.id)).size).toBe(3);
    expect(r.idMap["trigger-1"]).toBe(t);
  });

  it("never reuses ids across repeated pastes", () => {
    const c = copySelection(nodes, edges);
    const ids = nodes.map((n) => n.id);
    const first = pasteClipboard(c, ids);
    const second = pasteClipboard(c, [...ids, ...first.nodes.map((n) => n.id)]);
    const all = [...nodes, ...first.nodes, ...second.nodes].map((n) => n.id);
    expect(new Set(all).size).toBe(all.length);
  });

  it("does not mutate the source and deep-clones data", () => {
    const r = pasteClipboard(copySelection(nodes, edges), []);
    (r.nodes[0].data.config as Record<string, unknown>).x = 1;
    expect((nodes[1].data.config as Record<string, unknown>).x).toBeUndefined();
    expect(nodes[1].position).toEqual({ x: 100, y: 0 });
  });

  it("duplicateSelection returns only the new items", () => {
    const d = duplicateSelection(nodes, edges);
    expect(d.nodes).toHaveLength(3);
    expect(d.nodes[1].position).toEqual({ x: 140, y: 40 });
  });
});

describe("alignSelection and distributeSelection", () => {
  const ns = [
    node("a", "agent", 0, 0, { selected: true, width: 100, height: 40 }),
    node("b", "agent", 50, 100, { selected: true, width: 200, height: 60 }),
    node("c", "agent", 400, 300, { selected: true, width: 100, height: 40 }),
    node("z", "agent", 999, 999),
  ];

  it("aligns edges and centres, ignoring unselected nodes", () => {
    expect(alignSelection(ns, "left")).toEqual({ a: { x: 0, y: 0 }, b: { x: 0, y: 100 }, c: { x: 0, y: 300 } });
    expect(alignSelection(ns, "right").a.x).toBe(400);
    expect(alignSelection(ns, "center").b.x).toBe(150);
    expect(alignSelection(ns, "top").c.y).toBe(0);
    expect(alignSelection(ns, "bottom").a.y).toBe(300);
    expect(alignSelection(ns, "middle").a.y).toBe(170 - 20);
    expect(alignSelection(ns, "left").z).toBeUndefined();
  });

  it("needs two nodes to align and three to distribute", () => {
    expect(alignSelection([ns[0]], "left")).toEqual({});
    expect(distributeSelection(ns.slice(0, 2), "horizontal")).toEqual({});
  });

  it("distributes equal gaps and keeps the ends fixed", () => {
    const p = distributeSelection(ns, "horizontal");
    // span 0..500, widths total 400 => gap 50
    expect(p.a.x).toBe(0);
    expect(p.b.x).toBe(150);
    expect(p.c.x).toBe(400);
    const v = distributeSelection(ns, "vertical");
    // span 0..340, heights total 140 => gap 100
    expect(v.a.y).toBe(0);
    expect(v.b.y).toBe(140);
    expect(v.c.y).toBe(300);
  });
});

describe("lintGraph", () => {
  const codes = (i: ReturnType<typeof lintGraph>) => i.map((x) => `${x.code}:${x.nodeId ?? ""}`);

  it("flags a missing trigger as an error, listed first", () => {
    const r = lintGraph([node("a", "output"), node("b", "http")], []);
    expect(r[0]).toMatchObject({ severity: "error", code: "no-trigger" });
  });

  it("flags disconnected and unreachable nodes distinctly", () => {
    const ns = [node("t", "trigger"), node("o", "output"), node("lone", "output"), node("u1", "output"), node("u2", "output")];
    const r = codes(lintGraph(ns, [edge("t", "o"), edge("u1", "u2")]));
    expect(r).toContain("disconnected-node:lone");
    expect(r).toContain("unreachable-from-trigger:u1");
    expect(r).toContain("unreachable-from-trigger:u2");
    expect(r.some((c) => c.endsWith(":o"))).toBe(false);
  });

  it("requires an error edge on agent/http/tool nodes only", () => {
    const ns = [node("t", "trigger"), node("ag"), node("h", "http"), node("k", "tool"), node("co", "condition"), node("f", "output")];
    const e = [edge("t", "ag"), edge("ag", "h"), edge("h", "k"), edge("k", "co"), edge("co", "f"), edge("ag", "f", "error")];
    const r = codes(lintGraph(ns, e));
    expect(r).not.toContain("missing-error-route:ag");
    expect(r).toContain("missing-error-route:h");
    expect(r).toContain("missing-error-route:k");
    expect(r).not.toContain("missing-error-route:co");
  });

  it("flags loops without a positive maxIterations", () => {
    const mk = (cfg: Record<string, unknown>) => node("l", "loop", 0, 0, { data: { label: "l", config: cfg } });
    const run = (cfg: Record<string, unknown>) => codes(lintGraph([node("t", "trigger"), mk(cfg)], [edge("t", "l")]));
    expect(run({})).toContain("loop-no-max-iterations:l");
    expect(run({ maxIterations: 0 })).toContain("loop-no-max-iterations:l");
    expect(run({ maxIterations: 25 })).not.toContain("loop-no-max-iterations:l");
  });

  it("is clean for a healthy graph and order independent otherwise", () => {
    expect(lintGraph([node("t", "trigger"), node("o", "output")], [edge("t", "o")])).toEqual([]);
    const bad = [node("b", "http"), node("a", "http"), node("t", "trigger")];
    expect(lintGraph(bad, [])).toEqual(lintGraph([...bad].reverse(), []));
  });
});

describe("renameNodeInGraph", () => {
  const withConfig = (id: string, label: string, config: Record<string, unknown>, type = "agent"): N => ({
    id,
    type,
    position: { x: 0, y: 0 },
    data: { label, config },
  });
  const graph = () => [
    withConfig("a", "Fetch", {}, "http"),
    withConfig("b", "Send", {
      url: 'https://x/{{ $node["Fetch"].id }}',
      body: 'plain $node["Fetch"] text',
      headers: [{ name: "X", value: '{{ $node["Fetch"].token }}' }],
      nested: { deep: ['{{ $node["Other"].x }}', 'a {{ $node["Fetch"].y }}'] },
      retryAttempts: 2,
    }),
    withConfig("c", "Gate", { expression: '$node["Fetch"].total > 10 && $node["Other"].ok' }, "condition"),
  ];

  it("renames the node and rewrites references in all nodes in one pass", () => {
    const out = renameNodeInGraph(graph(), "a", "Load");
    expect(out[0].data.label).toBe("Load");
    const b = out[1].data.config as Record<string, unknown>;
    expect(b.url).toBe('https://x/{{ $node["Load"].id }}');
    expect((b.headers as { value: string }[])[0].value).toBe('{{ $node["Load"].token }}');
    expect((b.nested as { deep: string[] }).deep).toEqual(['{{ $node["Other"].x }}', 'a {{ $node["Load"].y }}']);
    expect(b.retryAttempts).toBe(2);
  });

  it("does not touch $node text outside {{ }} in a template field", () => {
    const out = renameNodeInGraph(graph(), "a", "Load");
    expect((out[1].data.config as { body: string }).body).toBe('plain $node["Fetch"] text');
  });

  it("treats the condition expression key as a bare expression", () => {
    const out = renameNodeInGraph(graph(), "a", "Load");
    expect((out[2].data.config as { expression: string }).expression).toBe('$node["Load"].total > 10 && $node["Other"].ok');
  });

  it("does not mutate its input, so an earlier snapshot stays a valid undo state", () => {
    const before = graph();
    const snapshot = JSON.stringify(before);
    const out = renameNodeInGraph(before, "a", "Load");
    expect(JSON.stringify(before)).toBe(snapshot);
    expect(out).not.toBe(before);
    expect(before[1].data.config).not.toBe(out[1].data.config);
  });

  it("keeps identity of nodes that need no change", () => {
    const before = [...graph(), withConfig("d", "Idle", { url: "static" })];
    const out = renameNodeInGraph(before, "a", "Load");
    expect(out[3]).toBe(before[3]);
  });

  it("changing only a label with no references still renames", () => {
    const before = [withConfig("a", "One", {}), withConfig("b", "Two", { url: "x" })];
    const out = renameNodeInGraph(before, "a", "Uno");
    expect(out[0].data.label).toBe("Uno");
    expect(out[1]).toBe(before[1]);
  });

  it("leaves references alone when another node shares the old label", () => {
    const before = [withConfig("a", "Fetch", {}), withConfig("a2", "Fetch", {}), withConfig("b", "Send", { url: '{{ $node["Fetch"].id }}' })];
    const out = renameNodeInGraph(before, "a", "Load");
    expect(out[0].data.label).toBe("Load");
    expect((out[2].data.config as { url: string }).url).toBe('{{ $node["Fetch"].id }}');
  });

  it("does not rewrite to or from a blank label", () => {
    const before = graph();
    const cleared = renameNodeInGraph(before, "a", "");
    expect(cleared[0].data.label).toBe("");
    expect(cleared[1]).toBe(before[1]);
  });

  it("bridges a transient blank label via the override", () => {
    const blanked = renameNodeInGraph(graph(), "a", "");
    const out = renameNodeInGraph(blanked, "a", "Load", "Fetch");
    expect((out[1].data.config as { url: string }).url).toBe('https://x/{{ $node["Load"].id }}');
  });

  it("escapes awkward new labels", () => {
    const out = renameNodeInGraph(graph(), "a", 'Say "hi"');
    expect((out[1].data.config as { url: string }).url).toBe('https://x/{{ $node["Say \\"hi\\""].id }}');
  });

  it("is a no-op for an unknown node id", () => {
    const before = graph();
    expect(renameNodeInGraph(before, "zzz", "X")).toBe(before);
  });

  it("does not rewrite when the label is unchanged", () => {
    const before = graph();
    const out = renameNodeInGraph(before, "a", "Fetch");
    expect(out.every((n, i) => n === before[i])).toBe(true);
  });
});

describe("nextLabelBridge", () => {
  it("remembers the old label when it is cleared", () => {
    expect(nextLabelBridge("Fetch", "", undefined)).toEqual({ override: undefined, bridge: "Fetch" });
  });

  it("hands the remembered label over when a new one is typed, then forgets it", () => {
    expect(nextLabelBridge("", "L", "Fetch")).toEqual({ override: "Fetch", bridge: undefined });
  });

  it("keeps the bridge while the label stays blank and ignores it for a normal rename", () => {
    expect(nextLabelBridge("", "", "Fetch")).toEqual({ override: "Fetch", bridge: "Fetch" });
    expect(nextLabelBridge("Fetch", "Load", "stale")).toEqual({ override: undefined, bridge: undefined });
  });

  it("composes with renameNodeInGraph across a clear-and-retype", () => {
    const start: N[] = [
      { id: "a", type: "http", position: { x: 0, y: 0 }, data: { label: "Fetch", config: {} } },
      { id: "b", type: "agent", position: { x: 0, y: 0 }, data: { label: "Send", config: { url: '{{ $node["Fetch"].id }}' } } },
    ];
    let bridge: string | undefined;
    let nodes = start;
    for (const label of ["", "L", "Lo", "Load"]) {
      const cur = (nodes[0].data.label as string) || "";
      const plan = nextLabelBridge(cur, label, bridge);
      bridge = plan.bridge;
      nodes = renameNodeInGraph(nodes, "a", label, plan.override);
    }
    expect((nodes[1].data.config as { url: string }).url).toBe('{{ $node["Load"].id }}');
  });
});
