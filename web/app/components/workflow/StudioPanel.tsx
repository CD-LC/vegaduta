"use client";

import { useCallback, useRef } from "react";
import type { KeyboardEvent, PointerEvent, ReactNode } from "react";
import { PanelLeftClose, PanelLeftOpen, PanelRightClose, PanelRightOpen } from "lucide-react";
import { IconAction } from "./StudioControls";

/**
 * A floating glass panel of the workflow designer's studio layout (see
 * useStudioLayout). Three modes:
 *
 * - `open`: the full panel, resizable from its inner edge (drag, or focus the
 *   handle and use the arrow keys; double-click resets);
 * - `rail`: a slim icon rail (`rail` slot) - quick actions stay one click
 *   away without covering the canvas;
 * - `hidden`: nothing rendered but the children, so a panel's own mobile
 *   drawer keeps working.
 *
 * Below 900px the wrapper is `display: contents` (workflow-studio.css) and
 * the children fall back to their existing off-canvas drawer behaviour -
 * the glass/backdrop-filter would otherwise become the containing block of
 * their position:fixed drawers.
 */
export interface StudioPanelProps {
  side: "left" | "right";
  mode: "hidden" | "rail" | "open";
  width: number;
  minWidth: number;
  maxWidth: number;
  defaultWidth: number;
  onResize: (width: number) => void;
  onToggle: () => void;
  /** Accessible name of the panel region, e.g. "Node library". */
  label: string;
  collapseLabel: string;
  expandLabel: string;
  resizeLabel: string;
  /** Header content shown next to the collapse button when open. */
  header?: ReactNode;
  /** Icon buttons shown when collapsed to a rail. */
  rail?: ReactNode;
  children: ReactNode;
}

const KEY_STEP = 16;

export default function StudioPanel({
  side,
  mode,
  width,
  minWidth,
  maxWidth,
  defaultWidth,
  onResize,
  onToggle,
  label,
  collapseLabel,
  expandLabel,
  resizeLabel,
  header,
  rail,
  children,
}: StudioPanelProps) {
  const drag = useRef<{ startX: number; startWidth: number } | null>(null);

  const onPointerDown = useCallback(
    (e: PointerEvent<HTMLDivElement>) => {
      if (e.button !== 0) return;
      e.preventDefault();
      e.currentTarget.setPointerCapture(e.pointerId);
      drag.current = { startX: e.clientX, startWidth: width };
    },
    [width],
  );
  const onPointerMove = useCallback(
    (e: PointerEvent<HTMLDivElement>) => {
      if (!drag.current) return;
      const dx = e.clientX - drag.current.startX;
      onResize(drag.current.startWidth + (side === "left" ? dx : -dx));
    },
    [onResize, side],
  );
  const endDrag = useCallback((e: PointerEvent<HTMLDivElement>) => {
    if (!drag.current) return;
    drag.current = null;
    if (e.currentTarget.hasPointerCapture(e.pointerId)) e.currentTarget.releasePointerCapture(e.pointerId);
  }, []);
  const onHandleKey = useCallback(
    (e: KeyboardEvent<HTMLDivElement>) => {
      const grow = side === "left" ? "ArrowRight" : "ArrowLeft";
      const shrink = side === "left" ? "ArrowLeft" : "ArrowRight";
      if (e.key === grow) {
        e.preventDefault();
        onResize(width + KEY_STEP);
      } else if (e.key === shrink) {
        e.preventDefault();
        onResize(width - KEY_STEP);
      } else if (e.key === "Home") {
        e.preventDefault();
        onResize(minWidth);
      } else if (e.key === "End") {
        e.preventDefault();
        onResize(maxWidth);
      }
    },
    [maxWidth, minWidth, onResize, side, width],
  );

  const CollapseIcon = side === "left" ? PanelLeftClose : PanelRightClose;
  // Tooltips open toward the canvas, away from the screen edge.
  const tipSide = side === "left" ? "right" : "left";
  const ExpandIcon = side === "left" ? PanelLeftOpen : PanelRightOpen;

  // One stable tree for every mode: the body (and so the panel's own state -
  // search text, collapsed categories, inspector tab) never remounts when the
  // panel collapses to a rail, hides for zen, or expands again. Head, rail
  // and resize handle are conditional siblings that keep their slots.
  return (
    <section className={`wf-studio-panel wf-studio-${side}`} data-mode={mode} aria-label={label}>
      {mode === "rail" && (
        <div className="wf-studio-rail">
          <IconAction scale="sm" label={expandLabel} side="right" onClick={onToggle}>
            <ExpandIcon size={16} aria-hidden="true" />
          </IconAction>
          <span className="wf-studio-rail-sep" aria-hidden="true" />
          <div className="wf-studio-rail-items">{rail}</div>
        </div>
      )}
      {mode === "open" && (
        <div className="wf-studio-head">
          <div className="wf-studio-head-main">{header}</div>
          <IconAction scale="sm" label={collapseLabel} side={tipSide} onClick={onToggle}>
            <CollapseIcon size={16} aria-hidden="true" />
          </IconAction>
        </div>
      )}
      {/* `data-collapsed` rather than the hidden attribute: below 900px the
          children are off-canvas drawers that must still work whatever the
          desktop panel state is (workflow-studio.css only hides this body
          at >=900px). */}
      <div className="wf-studio-body" data-collapsed={mode !== "open" ? "true" : undefined}>
        {children}
      </div>
      {mode === "open" && (
        // eslint-disable-next-line jsx-a11y/no-noninteractive-element-interactions -- separator is a focusable splitter (tabIndex set below); keys act on it
        <div
          className="wf-studio-resize"
          role="separator"
          aria-orientation="vertical"
          aria-label={resizeLabel}
          aria-valuemin={minWidth}
          aria-valuemax={maxWidth}
          aria-valuenow={width}
          tabIndex={0}
          onPointerDown={onPointerDown}
          onPointerMove={onPointerMove}
          onPointerUp={endDrag}
          onPointerCancel={endDrag}
          onKeyDown={onHandleKey}
          onDoubleClick={() => onResize(defaultWidth)}
        />
      )}
    </section>
  );
}
