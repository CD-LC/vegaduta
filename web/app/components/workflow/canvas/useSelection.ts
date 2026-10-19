"use client";

import { useCallback, useMemo, useState, type Dispatch, type MutableRefObject, type SetStateAction } from "react";
import { NODE_WIDTH } from "../canvasOps";
import { NODE_HEIGHT_GUESS } from "./constants";

type Setter = Dispatch<SetStateAction<any[]>>;

/**
 * Which node the config drawer is bound to, plus the actions that move the
 * selection (lint chip / comments jump to a node, the inspector toggle clears it).
 */
export function useSelection(opts: {
  nodes: any[];
  nodesRef: MutableRefObject<any[]>;
  setNodes: Setter;
  setCenter: (x: number, y: number, o?: { zoom?: number; duration?: number }) => unknown;
  setInspectorOpen: (open: boolean) => void;
}) {
  const { nodes, nodesRef, setNodes, setCenter, setInspectorOpen } = opts;
  const [selectedNodeId, setSelectedNodeId] = useState<any>(null);
  const selectedNode = useMemo(() => nodes.find((n) => n.id === selectedNodeId) || null, [nodes, selectedNodeId]);

  // Multi-trigger graphs: validation needs at least one Trigger, so the config
  // drawer's delete button disables itself only for the LAST remaining one.
  const triggerNodeCount = useMemo(() => nodes.filter((n) => n.type === "trigger").length, [nodes]);

  // Lint chip -> select the offending node and bring it into view.
  const focusNodeFromLint = useCallback(
    (nodeId: string) => {
      const target = nodesRef.current.find((n) => n.id === nodeId);
      if (!target) return;
      setNodes((nds) => nds.map((n) => (n.selected === (n.id === nodeId) ? n : { ...n, selected: n.id === nodeId })));
      setSelectedNodeId(nodeId);
      setInspectorOpen(true);
      void setCenter(target.position.x + NODE_WIDTH / 2, target.position.y + NODE_HEIGHT_GUESS / 2, {
        zoom: 1,
        duration: 400,
      });
    },
    // eslint-disable-next-line react-hooks/exhaustive-deps -- setters and the ref are stable
    [setCenter]
  );

  /** Deselect in state AND in React Flow, or the node keeps its ring. */
  const clearSelection = useCallback(() => {
    setSelectedNodeId(null);
    setNodes((nds) =>
      nds.some((n) => n.selected) ? nds.map((n) => (n.selected ? { ...n, selected: false } : n)) : nds
    );
  }, [setNodes]);

  return { selectedNodeId, setSelectedNodeId, selectedNode, triggerNodeCount, focusNodeFromLint, clearSelection };
}
