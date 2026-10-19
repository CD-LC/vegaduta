"use client";

import { useCallback, useState, type Dispatch, type MutableRefObject, type SetStateAction } from "react";
import { addEdge } from "@xyflow/react";
import { NODE_DEFS, newNodeId, nodeLabelKey } from "../nodeDefs";
import { NODE_WIDTH } from "../canvasOps";
import { DEFAULT_EDGE_OPTIONS } from "../workflowCanvasHelpers";
import { toast } from "../../../lib/toastBus";
import { PLACE_GAP_X, primarySourceHandle } from "./constants";

type Setter = Dispatch<SetStateAction<any[]>>;

/**
 * Wiring: connect handles, drop-to-create (release a connection on the empty
 * pane), insert-on-edge ("+" on an edge) and the command-palette pick that
 * resolves both. Every edit is one undo entry.
 */
export function useConnections(opts: {
  nodesRef: MutableRefObject<any[]>;
  edgesRef: MutableRefObject<any[]>;
  setNodes: Setter;
  setEdges: Setter;
  setSelectedNodeId: (id: any) => void;
  setCmdOpen: (open: boolean) => void;
  commitHistory: (coalesceMs?: number) => void;
  addNode: (type: any, position: any) => string | null;
  addNodeByClick: (type: any) => void;
  tNodeDefs: (key: string) => string;
  screenToFlowPosition: (p: { x: number; y: number }) => { x: number; y: number };
}) {
  const {
    nodesRef,
    edgesRef,
    setNodes,
    setEdges,
    setSelectedNodeId,
    setCmdOpen,
    commitHistory,
    addNode,
    addNodeByClick,
    tNodeDefs,
    screenToFlowPosition,
  } = opts;

  const onConnect = useCallback(
    (connection: any) => {
      commitHistory();
      setEdges((eds) =>
        addEdge(
          {
            ...connection,
            id: `e-${connection.source}-${connection.sourceHandle || "x"}-${connection.target}`,
            ...DEFAULT_EDGE_OPTIONS,
          },
          eds
        )
      );
    },
    [commitHistory, setEdges]
  );

  // Drop-to-create: releasing a connection drag on the empty pane opens the
  // command palette; picking a node then creates it at the drop point AND
  // auto-wires it from the source handle.
  const [pendingConnection, setPendingConnection] = useState<any>(null);
  const onConnectEnd = useCallback(
    (event: any, connectionState: any) => {
      // A valid drop onto a real handle is already handled by onConnect.
      if (!connectionState || connectionState.isValid) return;
      const source = connectionState.fromNode?.id;
      if (!source) return;
      const point = event.changedTouches?.length ? event.changedTouches[0] : event;
      const position = screenToFlowPosition({ x: point.clientX, y: point.clientY });
      setPendingConnection({ source, sourceHandle: connectionState.fromHandle?.id ?? null, position });
      setCmdOpen(true);
    },
    [screenToFlowPosition, setCmdOpen]
  );

  // "Insert on edge": the "+" on an edge opens the same palette in this mode;
  // the pick then splits that edge into source -> new -> target.
  const [insertEdgeId, setInsertEdgeId] = useState<string | null>(null);
  const onInsertOnEdge = useCallback(
    (edgeId: string) => {
      setPendingConnection(null);
      setInsertEdgeId(edgeId);
      setCmdOpen(true);
    },
    [setCmdOpen]
  );
  // The zone calls this with null when the palette closes; it must clear both
  // palette modes or an abandoned insert would hijack the next ordinary pick.
  const clearPaletteMode = useCallback((_: null) => {
    setPendingConnection(null);
    setInsertEdgeId(null);
  }, []);

  function insertNodeOnEdge(type: string, edgeId: string): boolean {
    const def = NODE_DEFS.find((d) => d.type === type);
    const edge = edgesRef.current.find((e) => e.id === edgeId);
    if (!def || !def.supported || !edge) return false;
    // A Trigger has no input port, so it cannot sit mid-edge.
    if (def.type === "trigger") {
      toast.info("A Trigger starts a flow, so it can't be inserted between two nodes.");
      return true;
    }
    const list = nodesRef.current;
    const src = list.find((n) => n.id === edge.source);
    const tgt = list.find((n) => n.id === edge.target);
    if (!src || !tgt) return false;

    const srcW = src.measured?.width ?? NODE_WIDTH;
    // Make room: when the gap is tighter than one node plus two column gaps,
    // push the target and everything downstream of it right by the shortfall.
    const gap = tgt.position.x - (src.position.x + srcW);
    // Back-edge (loop): target sits left of source, so shifting right would tear the layout apart.
    const isBackEdge = tgt.position.x <= src.position.x;
    const need = isBackEdge ? 0 : NODE_WIDTH + PLACE_GAP_X * 2 - gap;
    const shift = new Set<string>();
    if (need > 0) {
      const queue = [tgt.id];
      while (queue.length) {
        const id = queue.shift() as string;
        if (shift.has(id) || id === src.id) continue;
        shift.add(id);
        for (const e of edgesRef.current) if (e.source === id) queue.push(e.target);
      }
    }
    const x = isBackEdge ? (src.position.x + tgt.position.x) / 2 : src.position.x + srcW + PLACE_GAP_X;
    const y = (src.position.y + tgt.position.y) / 2;
    const id = newNodeId(type);
    const label = tNodeDefs(nodeLabelKey(def.type));
    const outHandle = primarySourceHandle(def.type, def.defaultConfig);

    commitHistory(); // one undo entry for the whole split
    setNodes((nds) => [
      ...nds.map((n) => {
        const moved = shift.has(n.id) ? { ...n, position: { x: n.position.x + need, y: n.position.y } } : n;
        return moved.selected ? { ...moved, selected: false } : moved;
      }),
      { id, type, position: { x, y }, selected: true, data: { label, config: { ...def.defaultConfig } } },
    ]);
    setEdges((eds) => [
      ...eds.filter((e) => e.id !== edgeId),
      {
        ...DEFAULT_EDGE_OPTIONS,
        id: `e-${edge.source}-${edge.sourceHandle || "x"}-${id}`,
        source: edge.source,
        sourceHandle: edge.sourceHandle,
        target: id,
      },
      {
        ...DEFAULT_EDGE_OPTIONS,
        id: `e-${id}-${outHandle || "x"}-${edge.target}`,
        source: id,
        sourceHandle: outHandle,
        target: edge.target,
        targetHandle: edge.targetHandle,
      },
    ]);
    setSelectedNodeId(id);
    return true;
  }

  // Command-palette pick: from a dropped connection the new node lands at the
  // release point and is wired up; otherwise it goes to a tidy default spot.
  function handlePalettePick(type: any) {
    if (insertEdgeId) {
      const edgeId = insertEdgeId;
      setInsertEdgeId(null);
      if (insertNodeOnEdge(type, edgeId)) return;
    }
    if (pendingConnection) {
      const id = addNode(type, pendingConnection.position);
      if (id) {
        setEdges((eds) =>
          addEdge(
            {
              ...DEFAULT_EDGE_OPTIONS,
              source: pendingConnection.source,
              sourceHandle: pendingConnection.sourceHandle,
              target: id,
            },
            eds
          )
        );
      }
      setPendingConnection(null);
    } else {
      addNodeByClick(type);
    }
  }

  return {
    onConnect,
    onConnectEnd,
    onInsertOnEdge,
    clearPaletteMode,
    handlePalettePick,
    setPendingConnection,
    setInsertEdgeId,
  };
}
