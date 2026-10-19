"use client";

import { useReactFlow } from "@xyflow/react";
import type { Dispatch, MutableRefObject, SetStateAction } from "react";
import { autoLayout } from "../canvasOps";

/** Fit-to-screen and tidy (auto-layout) over the React Flow viewport. */
export function useViewport(opts: {
  nodesRef: MutableRefObject<any[]>;
  edgesRef: MutableRefObject<any[]>;
  setNodes: Dispatch<SetStateAction<any[]>>;
  commitHistory: (coalesceMs?: number) => void;
  fitPaddingRef: MutableRefObject<any>;
}) {
  const { nodesRef, edgesRef, setNodes, commitHistory, fitPaddingRef } = opts;
  const { fitView } = useReactFlow();

  function fitToScreen() {
    void fitView({ maxZoom: 1, padding: fitPaddingRef.current, duration: 300 });
  }

  // Tidy = one undo entry, then refit so the re-laid-out graph is in view.
  function tidyLayout() {
    if (nodesRef.current.length < 2) return;
    const positions = autoLayout(nodesRef.current, edgesRef.current);
    commitHistory();
    setNodes((nds) => nds.map((n) => (positions[n.id] ? { ...n, position: positions[n.id] } : n)));
    setTimeout(() => void fitView({ maxZoom: 1, padding: fitPaddingRef.current, duration: 300 }), 60);
  }

  return { fitToScreen, tidyLayout };
}
