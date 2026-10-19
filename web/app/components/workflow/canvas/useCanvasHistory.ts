"use client";

import { useCallback, useRef, useState, type Dispatch, type MutableRefObject, type SetStateAction } from "react";
import { CommandHistory } from "./commands";

type Setter = Dispatch<SetStateAction<any[]>>;

/**
 * Undo/redo for the canvas on top of CommandHistory. History is NOT React
 * state (an entry is only read at undo/redo time); only a tick re-renders so
 * the toolbar's disabled state refreshes. `historyRef.current` keeps the
 * `{past, future}` shape the topbar reads.
 */
export function useCanvasHistory(opts: {
  nodesRef: MutableRefObject<any[]>;
  edgesRef: MutableRefObject<any[]>;
  setNodes: Setter;
  setEdges: Setter;
  observingRef: MutableRefObject<boolean>;
}) {
  const { nodesRef, edgesRef, setNodes, setEdges, observingRef } = opts;
  const historyRef = useRef<CommandHistory>(null as unknown as CommandHistory);
  if (historyRef.current === null) historyRef.current = new CommandHistory();
  const [, setTick] = useState(0);

  const commitHistory = useCallback(
    (coalesceMs = 0) => {
      historyRef.current.commit({ nodes: nodesRef.current, edges: edgesRef.current }, "edit", coalesceMs);
    },
    [nodesRef, edgesRef]
  );

  const apply = useCallback(
    (g: { nodes: any[]; edges: any[] }) => {
      nodesRef.current = g.nodes;
      edgesRef.current = g.edges;
      setNodes(g.nodes);
      setEdges(g.edges);
      setTick((v) => v + 1);
    },
    [nodesRef, edgesRef, setNodes, setEdges]
  );

  // Both guard on observe themselves: any caller (topbar, palette, shortcut)
  // must not rewrite the graph under a replay.
  const undo = useCallback(() => {
    if (observingRef.current) return;
    const g = historyRef.current.undo({ nodes: nodesRef.current, edges: edgesRef.current });
    if (g) apply(g);
  }, [observingRef, nodesRef, edgesRef, apply]);

  const redo = useCallback(() => {
    if (observingRef.current) return;
    const g = historyRef.current.redo({ nodes: nodesRef.current, edges: edgesRef.current });
    if (g) apply(g);
  }, [observingRef, nodesRef, edgesRef, apply]);

  /** Fresh load: empty history (undoing into another document would leak). */
  const resetHistory = useCallback(() => {
    historyRef.current.reset();
    setTick((v) => v + 1);
  }, []);

  return { historyRef, commitHistory, undo, redo, resetHistory };
}
