import { describe, expect, it, vi } from "vitest";
import { act, renderHook } from "@testing-library/react";
import { useRef, useState } from "react";
import { useCanvasEditing } from "./useCanvasEditing";
import { useConnections } from "./useConnections";
import { useSelection } from "./useSelection";

const tNodeDefs = (key: string) => key;
const screenToFlowPosition = (p: { x: number; y: number }) => p;

function node(id: string, type: string, x: number, y = 0) {
  return { id, type, position: { x, y }, data: { label: id, config: {} } };
}

function setup(initialNodes: any[] = [], initialEdges: any[] = []) {
  const commit = vi.fn();
  const setCmdOpen = vi.fn();
  const hook = renderHook(() => {
    const [nodes, setNodes] = useState<any[]>(initialNodes);
    const [edges, setEdges] = useState<any[]>(initialEdges);
    const nodesRef = useRef(nodes);
    const edgesRef = useRef(edges);
    nodesRef.current = nodes;
    edgesRef.current = edges;
    const sel = useSelection({ nodes, nodesRef, setNodes, setCenter: vi.fn(), setInspectorOpen: vi.fn() });
    const editing = useCanvasEditing({
      nodesRef,
      setNodes,
      setEdges,
      setSelectedNodeId: sel.setSelectedNodeId,
      commitHistory: commit,
      tNodeDefs,
      screenToFlowPosition,
    });
    const wiring = useConnections({
      nodesRef,
      edgesRef,
      setNodes,
      setEdges,
      setSelectedNodeId: sel.setSelectedNodeId,
      setCmdOpen,
      commitHistory: commit,
      addNode: editing.addNode,
      addNodeByClick: editing.addNodeByClick,
      tNodeDefs,
      screenToFlowPosition,
    });
    return { nodes, edges, sel, editing, wiring };
  });
  return { hook, commit, setCmdOpen };
}

describe("canvas editing hooks", () => {
  it("adds a supported node as the only selection, one undo entry", () => {
    const { hook, commit } = setup([{ ...node("a", "trigger", 0), selected: true }]);
    let id: string | null = null;
    act(() => {
      id = hook.result.current.editing.addNode("agent", { x: 200, y: 0 });
    });
    expect(id).toMatch(/^agent-/);
    expect(commit).toHaveBeenCalledTimes(1);
    const { nodes, sel } = hook.result.current;
    expect(nodes.map((n) => n.selected)).toEqual([false, true]);
    expect(sel.selectedNodeId).toBe(id);
    expect(sel.selectedNode?.id).toBe(id);
  });

  it("refuses an unknown node type without touching history", () => {
    const { hook, commit } = setup();
    let id: string | null = "x";
    act(() => {
      id = hook.result.current.editing.addNode("no-such-type", { x: 0, y: 0 });
    });
    expect(id).toBeNull();
    expect(commit).not.toHaveBeenCalled();
    expect(hook.result.current.nodes).toHaveLength(0);
  });

  it("deleting a node drops its edges and clears the selection", () => {
    const { hook } = setup(
      [node("a", "trigger", 0), node("b", "agent", 300), node("c", "output", 600)],
      [
        { id: "e1", source: "a", target: "b" },
        { id: "e2", source: "b", target: "c" },
      ]
    );
    act(() => hook.result.current.sel.setSelectedNodeId("b"));
    act(() => hook.result.current.editing.deleteNode("b"));
    expect(hook.result.current.nodes.map((n) => n.id)).toEqual(["a", "c"]);
    expect(hook.result.current.edges).toEqual([]);
    expect(hook.result.current.sel.selectedNodeId).toBeNull();
  });

  it("coalesces config patches into one history window", () => {
    const { hook, commit } = setup([node("a", "agent", 0)]);
    act(() => hook.result.current.editing.updateNode("a", { config: { prompt: "hi" } }));
    expect(commit).toHaveBeenCalledWith(600);
    expect(hook.result.current.nodes[0].data.config).toEqual({ prompt: "hi" });
  });

  it("connects two handles with a deterministic edge id", () => {
    const { hook } = setup([node("a", "trigger", 0), node("b", "agent", 300)]);
    act(() => hook.result.current.wiring.onConnect({ source: "a", target: "b", sourceHandle: null }));
    expect(hook.result.current.edges.map((e) => e.id)).toEqual(["e-a-x-b"]);
  });

  it("inserting on an edge splits it into source -> new -> target", () => {
    const { hook, setCmdOpen, commit } = setup(
      [node("a", "trigger", 0), node("b", "output", 100)],
      [{ id: "e1", source: "a", target: "b" }]
    );
    act(() => hook.result.current.wiring.onInsertOnEdge("e1"));
    expect(setCmdOpen).toHaveBeenCalledWith(true);
    act(() => hook.result.current.wiring.handlePalettePick("agent"));
    const { nodes, edges } = hook.result.current;
    const created = nodes.find((n) => n.type === "agent")!;
    expect(created).toBeDefined();
    expect(commit).toHaveBeenCalledTimes(1);
    expect(edges).toHaveLength(2);
    expect(edges.some((e) => e.source === "a" && e.target === created.id)).toBe(true);
    expect(edges.some((e) => e.source === created.id && e.target === "b")).toBe(true);
    // the target was pushed right to make room
    expect(nodes.find((n) => n.id === "b")!.position.x).toBeGreaterThan(100);
  });

  it("clearSelection deselects in state and in React Flow", () => {
    const { hook } = setup([{ ...node("a", "agent", 0), selected: true }]);
    act(() => hook.result.current.sel.setSelectedNodeId("a"));
    act(() => hook.result.current.sel.clearSelection());
    expect(hook.result.current.sel.selectedNodeId).toBeNull();
    expect(hook.result.current.nodes[0].selected).toBe(false);
  });
});
