import { describe, expect, it } from "vitest";
import { applyEdgeDiff, applyNodeDiff } from "./diffDecorations";

const deco = {
  nodes: { a: "added", b: "changed", gone: "removed" },
  edges: { "x->y": "added", e2: "removed" },
} as const;

describe("diff decorations", () => {
  it("returns the same array without decorations", () => {
    const nodes = [{ id: "a" }];
    expect(applyNodeDiff(nodes, null)).toBe(nodes);
  });

  it("paints added/changed nodes only and keeps untouched identity", () => {
    const nodes = [{ id: "a" }, { id: "b", className: "k" }, { id: "c" }, { id: "gone" }];
    const out = applyNodeDiff(nodes, deco as any);
    expect(out[0].className).toBe("wf-diff-added");
    expect(out[1].className).toBe("k wf-diff-changed");
    expect(out[2]).toBe(nodes[2]);
    expect(out[3]).toBe(nodes[3]);
  });

  it("returns the same array when nothing matches", () => {
    const nodes = [{ id: "z" }];
    expect(applyNodeDiff(nodes, deco as any)).toBe(nodes);
  });

  it("matches edges by edgeKey", () => {
    const edges: { id?: string; source: string; target: string; className?: string }[] = [
      { source: "x", target: "y" },
      { id: "e2", source: "p", target: "q" },
    ];
    const out = applyEdgeDiff(edges, deco as any);
    expect(out[0].className).toBe("wf-diff-added");
    expect(out[1]).toBe(edges[1]);
  });
});
