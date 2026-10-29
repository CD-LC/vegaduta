"use client";

import { createContext, useContext } from "react";
import { X } from "lucide-react";
import { BaseEdge, EdgeLabelRenderer, getSmoothStepPath, type EdgeProps } from "@xyflow/react";
import { useTranslations } from "../../lib/i18n";

// Custom edge renderer for every workflow-canvas edge - registered for the
// "smoothstep" type DEFAULT_EDGE_OPTIONS already stamps on each edge (see
// workflowCanvasHelpers.ts), so it has to reproduce the built-in
// SmoothStepEdge's look exactly (same getSmoothStepPath defaults, markers
// and interaction width passed straight through to BaseEdge) while adding
// the one affordance the built-in lacks: deletion used to be keyboard-only
// (React Flow's own Backspace/Delete handling) with no on-screen hint. A
// selected edge now shows a small x at its midpoint that deletes it.
//
// The delete itself is delegated up to WorkflowCanvas via context - it
// owns the commitHistory()+setEdges() bookkeeping every other graph
// mutation goes through, and this renderer staying free of canvas state
// keeps the edgeTypes map a stable module-level constant (React Flow warns
// + remounts every edge when that object changes identity per render).
export const WorkflowEdgeDeleteContext = createContext<(edgeId: string) => void>(() => {});

export default function DeletableEdge({
  id,
  sourceX,
  sourceY,
  targetX,
  targetY,
  sourcePosition,
  targetPosition,
  style,
  markerEnd,
  markerStart,
  interactionWidth,
  selected,
}: EdgeProps) {
    const tHC = useTranslations("hcComponentsWorkflowDeletableEdge");
  const onDelete = useContext(WorkflowEdgeDeleteContext);
  const [edgePath, labelX, labelY] = getSmoothStepPath({
    sourceX,
    sourceY,
    sourcePosition,
    targetX,
    targetY,
    targetPosition,
  });
  return (
    <>
      <BaseEdge
        id={id}
        path={edgePath}
        style={style}
        markerEnd={markerEnd}
        markerStart={markerStart}
        interactionWidth={interactionWidth}
      />
      {selected && (
        <EdgeLabelRenderer>
          {/* nodrag/nopan keep React Flow's pan/drag handlers from hijacking
           * the click; the portaled label also needs absolute positioning
           * and pointer-events restored to be clickable at all (same
           * EdgeLabelRenderer pattern EnvoyGraph's ShieldEdge uses). */}
          <button
            type="button"
            className="ax-wf-edge-delete nodrag nopan pointer-events-auto absolute"
            style={{ transform: `translate(-50%, -50%) translate(${labelX}px, ${labelY}px)` }}
            onClick={(e) => {
              e.stopPropagation();
              onDelete(id);
            }}
            aria-label={tHC("hcComponentsWorkflowDeletableEdge.s1")}
            title={tHC("hcComponentsWorkflowDeletableEdge.s1")}
          >
            <X size={12} strokeWidth={2.5} aria-hidden="true" />
          </button>
        </EdgeLabelRenderer>
      )}
    </>
  );
}
