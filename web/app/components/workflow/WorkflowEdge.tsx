"use client";

import { memo, useCallback, useContext, useState } from "react";
import { BaseEdge, EdgeLabelRenderer, getSmoothStepPath, type Edge, type EdgeProps } from "@xyflow/react";
import { WorkflowEdgeDeleteContext } from "./DeletableEdge";
import "./workflow-edges.css";
import "./run-overlay.css";
import { useTranslations } from "../../lib/i18n";

export type EdgeRunState = "pending" | "flowing" | "done" | "failed" | "skipped" | "awaiting-approval";

export type WorkflowEdgeData = {
  runState?: EdgeRunState;
  /** Branch name shown on the pill (e.g. "true", "route: billing"). */
  label?: string;
  /** Items that travelled the edge in the last run; used when no label is set. */
  itemCount?: number;
  /** Times the run on screen travelled this edge (loops count every pass). */
  passes?: number;
  /** Canvas lens paint (lensStyles.lensEdgeStyle); className sits on the path. */
  lens?: { className: string; intensity: number; dim: boolean; description?: string };
  /** Observe mode: no insert/delete affordances. */
  readOnly?: boolean;
  onInsert?: (edgeId: string, position: { x: number; y: number }) => void;
  onLabelClick?: (edgeId: string) => void;
};

export type WorkflowEdgeType = Edge<WorkflowEdgeData, "smoothstep">;

// Pure so tests can pin the state -> class mapping without rendering.
export function edgeStateClass(state?: EdgeRunState | null): string {
  switch (state) {
    case "done":
      return "wf-edge-done";
    case "flowing":
      return "wf-edge-flowing";
    case "failed":
      return "wf-edge-failed";
    case "skipped":
      return "wf-edge-skipped";
    case "awaiting-approval":
      return "wf-edge-paused";
    case "pending":
      return "wf-edge-pending";
    default:
      return "wf-edge-flowing";
  }
}

function pillText(data?: WorkflowEdgeData): string | null {
  // A loop's edge says how many passes it carried, beside any branch name.
  const passes = typeof data?.passes === "number" && data.passes > 1 ? `×${data.passes}` : "";
  if (data?.label) return passes ? `${data.label} ${passes}` : data.label;
  if (passes) return passes;
  if (typeof data?.itemCount === "number") {
    return `${data.itemCount} ${data.itemCount === 1 ? "item" : "items"}`;
  }
  return null;
}

function pillLabel(data: WorkflowEdgeData | undefined, text: string): string {
  const n = data?.passes ?? 0;
  return n > 1 ? `${data?.label ? `${data.label}, ` : ""}taken ${n} times` : text;
}

const PAUSE_GLYPH = "⏸";

function WorkflowEdgeImpl({
  id,
  sourceX,
  sourceY,
  targetX,
  targetY,
  sourcePosition,
  targetPosition,
  markerEnd,
  markerStart,
  selected,
  data,
}: EdgeProps<WorkflowEdgeType>) {
    const tHC = useTranslations("hcComponentsWorkflowWorkflowEdge");
  const onDelete = useContext(WorkflowEdgeDeleteContext);
  const [hovered, setHovered] = useState(false);
  const [focused, setFocused] = useState(false);
  const [path, labelX, labelY] = getSmoothStepPath({
    sourceX,
    sourceY,
    sourcePosition,
    targetX,
    targetY,
    targetPosition,
  });

  const state = data?.runState;
  const text = pillText(data);
  const paused = state === "awaiting-approval";
  const readOnly = !!data?.readOnly;
  const showTools = hovered || focused || !!selected;
  const showDelete = !readOnly && (!!selected || focused);
  const lens = data?.lens?.className ? data.lens : undefined;

  const onFocus = useCallback(() => setFocused(true), []);
  const onBlur = useCallback((e: React.FocusEvent<HTMLElement>) => {
    // Moving between the + and x buttons must not flicker the toolbar.
    if (!e.currentTarget.contains(e.relatedTarget as Node | null)) setFocused(false);
  }, []);

  const cls = ["wf-edge", edgeStateClass(state), "wf-edge-path", lens?.className ?? "", selected ? "wf-edge-selected" : "", hovered ? "wf-edge-hot" : ""]
    .filter(Boolean)
    .join(" ");
  const lensStyle = lens ? ({ "--wfx-lens-i": lens.intensity.toFixed(3) } as React.CSSProperties) : undefined;
  // Only a run in progress (runState set by the canvas) gets the travelling
  // pulse; the default build-mode look is the plain marching stroke.
  const pulse = state === "flowing";

  return (
    <>
      <g onMouseEnter={() => setHovered(true)} onMouseLeave={() => setHovered(false)}>
        <BaseEdge
          id={id}
          path={path}
          className={cls}
          style={lensStyle}
          markerEnd={markerEnd}
          markerStart={markerStart}
          interactionWidth={24}
        />
        {pulse && (
          <circle r={3.5} className={`wf-edge ${edgeStateClass(state)} wf-edge-pulse`} aria-hidden="true">
            <animateMotion dur="1.1s" repeatCount="indefinite" path={path} />
          </circle>
        )}
      </g>
      <EdgeLabelRenderer>
        <div
          className={`wf-edge wf-edge-tools nodrag nopan ${edgeStateClass(state)}${lens?.dim ? " wfx-lens-dim" : ""}`}
          style={{ transform: `translate(-50%, -50%) translate(${labelX}px, ${labelY}px)` }}
          onMouseEnter={() => setHovered(true)}
          onMouseLeave={() => setHovered(false)}
          onFocus={onFocus}
          onBlur={onBlur}
        >
          {text &&
            (data?.onLabelClick ? (
              <button
                type="button"
                className="wf-edge-pill"
                title={text}
                aria-label={`Edge label: ${text}`}
                onClick={(e) => {
                  e.stopPropagation();
                  data.onLabelClick?.(id);
                }}
              >
                {paused && <span aria-hidden="true">{PAUSE_GLYPH}</span>}
                {text}
              </button>
            ) : (
              <span className="wf-edge-pill" title={pillLabel(data, text)} aria-label={pillLabel(data, text)}>
                {paused && <span aria-hidden="true">{PAUSE_GLYPH}</span>}
                {text}
              </span>
            ))}
          {!text && paused && (
            <span className="wf-edge-pill" role="status">
              <span aria-hidden="true">{PAUSE_GLYPH}</span> {tHC("hcComponentsWorkflowWorkflowEdge.s4")}
            </span>
          )}
          {showTools && data?.onInsert && (
            <button
              type="button"
              className="wf-edge-btn"
              aria-label={tHC("hcComponentsWorkflowWorkflowEdge.s3")}
              title={tHC("hcComponentsWorkflowWorkflowEdge.s2")}
              onClick={(e) => {
                e.stopPropagation();
                data.onInsert?.(id, { x: labelX, y: labelY });
              }}
            >
              +
            </button>
          )}
          {showDelete && (
            <button
              type="button"
              className="wf-edge-btn wf-edge-btn-delete"
              aria-label={tHC("hcComponentsWorkflowWorkflowEdge.s1")}
              title={tHC("hcComponentsWorkflowWorkflowEdge.s1")}
              onClick={(e) => {
                e.stopPropagation();
                onDelete(id);
              }}
            >
              {"×"}
            </button>
          )}
        </div>
      </EdgeLabelRenderer>
    </>
  );
}

export const WorkflowEdge = memo(WorkflowEdgeImpl);
export default WorkflowEdge;
