"use client";

import { useCallback, useRef, type Dispatch, type MutableRefObject, type SetStateAction } from "react";
import { applyEdgeChanges, applyNodeChanges } from "@xyflow/react";
import { NODE_DEFS, newNodeId, nodeLabelKey } from "../nodeDefs";
import { NODE_WIDTH, nextLabelBridge, renameNodeInGraph } from "../canvasOps";
import { NODE_HEIGHT_GUESS, PLACE_GAP_X, PLACE_GAP_Y } from "./constants";

type Setter = Dispatch<SetStateAction<any[]>>;

/**
 * Node/edge mutations on the canvas: React Flow change handlers, adding nodes
 * (drag from the palette, click, command palette), renaming/patching and
 * deleting. Every discrete mutation is one undo entry via commitHistory.
 */
export function useCanvasEditing(opts: {
  nodesRef: MutableRefObject<any[]>;
  setNodes: Setter;
  setEdges: Setter;
  setSelectedNodeId: (id: any) => void;
  commitHistory: (coalesceMs?: number) => void;
  tNodeDefs: (key: string) => string;
  screenToFlowPosition: (p: { x: number; y: number }) => { x: number; y: number };
}) {
  const { nodesRef, setNodes, setEdges, setSelectedNodeId, commitHistory, tNodeDefs, screenToFlowPosition } = opts;

  const onNodesChange = useCallback(
    (changes: any) => {
      // Node add/remove is a discrete mutation worth an undo step; position/
      // select/dimension churn is covered by onNodeDragStart's single
      // pre-drag commit instead of one entry per mousemove.
      if (changes.some((c: any) => c.type === "remove" || c.type === "add")) {
        commitHistory();
      }
      setNodes((nds) => applyNodeChanges(changes, nds));
    },
    [commitHistory, setNodes]
  );
  const onEdgesChange = useCallback(
    (changes: any) => {
      if (changes.some((c: any) => c.type === "remove" || c.type === "add")) {
        commitHistory();
      }
      setEdges((eds) => applyEdgeChanges(changes, eds));
    },
    [commitHistory, setEdges]
  );
  const onNodeDragStart = useCallback(() => commitHistory(), [commitHistory]);

  const addNode = useCallback(
    (type: any, position: any) => {
      const def = NODE_DEFS.find((d) => d.type === type);
      if (!def || !def.supported) return null;
      commitHistory();
      const id = newNodeId(type);
      // The new node becomes THE selection, so the next click-add chains
      // off it instead of off an older still-selected node.
      setNodes((nds) => [
        ...nds.map((n) => (n.selected ? { ...n, selected: false } : n)),
        {
          id,
          type,
          position,
          selected: true,
          data: { label: tNodeDefs(nodeLabelKey(def.type)), config: { ...def.defaultConfig } },
        },
      ]);
      setSelectedNodeId(id);
      return id;
    },
    [tNodeDefs, commitHistory, setNodes, setSelectedNodeId]
  );

  const onDragOver = useCallback((event: any) => {
    event.preventDefault();
    event.dataTransfer.dropEffect = "move";
  }, []);

  const onDrop = useCallback(
    (event: any) => {
      event.preventDefault();
      const type = event.dataTransfer.getData("application/workflow-node-type");
      if (!type) return;
      const position = screenToFlowPosition({ x: event.clientX, y: event.clientY });
      addNode(type, position);
    },
    [addNode, screenToFlowPosition]
  );

  // Click-to-add lands to the right of the selected (else last) node with the
  // same column gap autoLayout uses, stepping down past anything already
  // occupying that slot.
  function nextNodePosition() {
    const list = nodesRef.current;
    if (list.length === 0) return { x: 80, y: 160 };
    const selected = list.filter((n) => n.selected);
    const anchor = selected.length > 0 ? selected[selected.length - 1] : list[list.length - 1];
    const x = anchor.position.x + (anchor.measured?.width ?? NODE_WIDTH) + PLACE_GAP_X;
    let y = anchor.position.y;
    for (
      let i = 0;
      i < 50 &&
      list.some((n) => Math.abs(n.position.x - x) < NODE_WIDTH && Math.abs(n.position.y - y) < NODE_HEIGHT_GUESS);
      i++
    ) {
      y += NODE_HEIGHT_GUESS + PLACE_GAP_Y;
    }
    return { x, y };
  }

  function addNodeByClick(type: any) {
    addNode(type, nextNodePosition());
  }

  // Last non-empty label of a node whose label field is momentarily blank while
  // the author retypes it, so $node["Old"] references can still follow the rename.
  const labelBridgeRef = useRef<Record<string, string>>({});

  function updateNode(nodeId: any, patch: any) {
    // Coalesced - the config drawer calls this per keystroke, and one
    // typed word should be one undo step, not fifteen.
    commitHistory(600);
    if (typeof patch?.label === "string") {
      // A label change also rewrites $node["Old"] references in every node's
      // config, in this same setNodes, so rename + rewrites are one undo entry.
      const { label, ...rest } = patch;
      const current = nodesRef.current.find((n) => n.id === nodeId);
      const plan = nextLabelBridge((current?.data?.label as string) || "", label, labelBridgeRef.current[nodeId]);
      if (plan.bridge === undefined) delete labelBridgeRef.current[nodeId];
      else labelBridgeRef.current[nodeId] = plan.bridge;
      const override = plan.override;
      setNodes((nds) => {
        const renamed = renameNodeInGraph(nds, nodeId, label, override);
        return Object.keys(rest).length
          ? renamed.map((n) => (n.id === nodeId ? { ...n, data: { ...n.data, ...rest } } : n))
          : renamed;
      });
      return;
    }
    setNodes((nds) => nds.map((n) => (n.id === nodeId ? { ...n, data: { ...n.data, ...patch } } : n)));
  }

  function deleteNode(nodeId: any) {
    commitHistory();
    setNodes((nds) => nds.filter((n) => n.id !== nodeId));
    setEdges((eds) => eds.filter((e) => e.source !== nodeId && e.target !== nodeId));
    setSelectedNodeId(null);
  }

  // The x DeletableEdge draws at a selected edge's midpoint calls this (via
  // WorkflowEdgeDeleteContext). Backspace/Delete lands on onEdgesChange's
  // "remove" branch; both paths share the same commitHistory bookkeeping.
  // useCallback: the context value must be identity-stable or every memo'd edge re-renders per drag frame.
  const deleteEdge = useCallback(
    (edgeId: string) => {
      commitHistory();
      setEdges((eds) => eds.filter((e) => e.id !== edgeId));
    },
    [commitHistory, setEdges]
  );

  return {
    onNodesChange,
    onEdgesChange,
    onNodeDragStart,
    addNode,
    addNodeByClick,
    onDragOver,
    onDrop,
    updateNode,
    deleteNode,
    deleteEdge,
  };
}
