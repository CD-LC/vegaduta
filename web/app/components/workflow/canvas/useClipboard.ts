"use client";

import { useRef, type Dispatch, type MutableRefObject, type SetStateAction } from "react";
import { copySelection, duplicateSelection, pasteClipboard, type ClipboardPayload } from "../canvasOps";
import { toast } from "../../../lib/toastBus";

/** Copy / paste / duplicate over the canvas graph; every paste is one undo step. */
export function useClipboard(opts: {
  nodesRef: MutableRefObject<any[]>;
  edgesRef: MutableRefObject<any[]>;
  setNodes: Dispatch<SetStateAction<any[]>>;
  setEdges: Dispatch<SetStateAction<any[]>>;
  setSelectedNodeId: (id: any) => void;
  commitHistory: (coalesceMs?: number) => void;
}) {
  const { nodesRef, edgesRef, setNodes, setEdges, setSelectedNodeId, commitHistory } = opts;
  const clipboardRef = useRef<ClipboardPayload | null>(null);
  const pasteCountRef = useRef(0);

  // canvasOps returns only the NEW items; deselect what was selected before so
  // the pasted group is the selection, and land everything in one undo entry.
  function applyAdded(added: { nodes: any[]; edges: any[] }) {
    if (added.nodes.length === 0) {
      toast.info("Nothing to paste.");
      return;
    }
    commitHistory();
    setNodes((nds) => [...nds.map((n) => (n.selected ? { ...n, selected: false } : n)), ...added.nodes]);
    setEdges((eds) => [...eds.map((e) => (e.selected ? { ...e, selected: false } : e)), ...added.edges]);
    setSelectedNodeId(added.nodes.length === 1 ? added.nodes[0].id : null);
  }

  function copySelected() {
    const payload = copySelection(nodesRef.current, edgesRef.current);
    if (payload.nodes.length === 0) return;
    clipboardRef.current = payload;
    pasteCountRef.current = 0;
  }

  function pasteCopied() {
    if (!clipboardRef.current) return;
    pasteCountRef.current += 1;
    const step = 40 * pasteCountRef.current; // each repeat paste cascades instead of stacking
    applyAdded(
      pasteClipboard(
        clipboardRef.current,
        nodesRef.current.map((n) => n.id),
        { x: step, y: step }
      )
    );
  }

  function duplicateSelected() {
    if (!nodesRef.current.some((n) => n.selected)) return;
    applyAdded(duplicateSelection(nodesRef.current, edgesRef.current));
  }

  return { copySelected, pasteCopied, duplicateSelected };
}
