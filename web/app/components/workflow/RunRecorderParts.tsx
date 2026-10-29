"use client";

// Presentational pieces and small hooks of the run flight recorder: the tone
// icon, one swim lane of visit bars, the hover tooltip, and the reduced-motion
// and element-width hooks.
import { useEffect, useRef, useState } from "react";
import type { CSSProperties, PointerEventHandler, RefObject } from "react";
import { Ban, CircleCheck, CircleDashed, CircleX, Hand, LoaderCircle, Pause, SkipForward } from "lucide-react";
import type { AwaitReason } from "./runModel";
import { formatCostUsd, formatDuration } from "./runModel";
import { formatClock, intFmt, statusText, type Bar, type Lane, type Tone } from "./runRecorderView";
import { useTranslations } from "../../lib/i18n";

export function useReducedMotion(): boolean {
  const [reduced, setReduced] = useState(false);
  useEffect(() => {
    if (typeof window === "undefined" || !window.matchMedia) return undefined;
    const mq = window.matchMedia("(prefers-reduced-motion: reduce)");
    const sync = () => setReduced(mq.matches);
    sync();
    mq.addEventListener("change", sync);
    return () => mq.removeEventListener("change", sync);
  }, []);
  return reduced;
}

export function useElementWidth<T extends HTMLElement>(): [RefObject<T | null>, number] {
  const ref = useRef<T | null>(null);
  const [w, setW] = useState(480);
  useEffect(() => {
    const el = ref.current;
    if (!el || typeof ResizeObserver === "undefined") return undefined;
    const ro = new ResizeObserver((entries) => {
      const cr = entries[0]?.contentRect;
      if (cr) setW(cr.width);
    });
    ro.observe(el);
    return () => ro.disconnect();
  }, []);
  return [ref, w];
}

export function ToneIcon({ tone, awaiting = null, size = 12 }: { tone: Tone; awaiting?: AwaitReason | null; size?: number }) {
  const p = { size, "aria-hidden": true, strokeWidth: 2.4 } as const;
  switch (tone) {
    case "ok":
      return <CircleCheck {...p} />;
    case "error":
      return <CircleX {...p} />;
    case "paused":
      return awaiting === "approval" ? <Hand {...p} /> : <Pause {...p} />;
    case "running":
      return <LoaderCircle {...p} />;
    case "skipped":
      return <SkipForward {...p} />;
    case "cancelled":
      return <Ban {...p} />;
    default:
      return <CircleDashed {...p} />;
  }
}

type ScrubHandlers = {
  onPointerDown: PointerEventHandler<HTMLElement>;
  onPointerMove: PointerEventHandler<HTMLElement>;
  onPointerUp: PointerEventHandler<HTMLElement>;
  onPointerCancel: PointerEventHandler<HTMLElement>;
};

type RecorderLaneProps = {
  lane: Lane;
  /** Playhead position in ms from run start (already clamped). */
  pos: number;
  total: number;
  axisWidth: number;
  selected: { nodeId: string; visitIndex: number } | null;
  /** Empty track starts a scrub; bars handle their own clicks. */
  scrub: ScrubHandlers;
  onPickBar: (b: Bar) => void;
  onShowTip: (b: Bar, el: HTMLElement) => void;
  onHideTip: () => void;
};

/** One node's swim lane: a bar per visit whose fill "records" up to the playhead. */
export function RecorderLane({ lane, pos, total, axisWidth, selected, scrub, onPickBar, onShowTip, onHideTip }: RecorderLaneProps) {
  const pct = (ms: number) => `${(Math.min(Math.max(ms, 0), total) / total) * 100}%`;
  const active = lane.bars.some((b) => pos >= b.start && pos < b.end);
  return (
    <div className="rfr-lane" data-active={active}>
      <div className="rfr-lane-label" title={lane.label}>
        <span>{lane.label}</span>
        {lane.bars.length > 1 && (
          <span className="rfr-lane-count" aria-label={`${lane.bars.length} visits`}>
            x{lane.bars.length}
          </span>
        )}
      </div>
      <div className="rfr-lane-track" {...scrub}>
        {lane.bars.map((b) => {
          const d = b.end - b.start;
          const phase = pos < b.start ? "future" : pos >= b.end ? "past" : "live";
          const fill = d > 0 ? Math.min(1, Math.max(0, (pos - b.start) / d)) : pos >= b.start ? 1 : 0;
          const isSel = !!selected && selected.nodeId === b.nodeId && selected.visitIndex === b.visitIndex;
          const widthPct = (d / total) * 100;
          const showIcon = b.tone !== "ok";
          const status = statusText(b.tone, b.awaiting);
          return (
            <button
              key={b.key}
              type="button"
              className="rfr-bar-visit"
              data-tone={b.tone}
              data-status={b.tone}
              data-phase={phase}
              data-critical={b.critical}
              data-timing={b.timing}
              data-selected={isSel}
              aria-pressed={isSel}
              aria-label={`${b.label}${b.visitCount > 1 ? `, visit ${b.visitIndex + 1} of ${b.visitCount}` : ""}: ${status}, ${b.durationMs == null ? "duration unknown" : formatDuration(b.durationMs)}, starts at ${formatClock(b.start)}${b.critical ? ", on critical path" : ""}`}
              style={{ left: pct(b.start), width: `${widthPct}%`, ["--rfr-fill" as string]: fill } as CSSProperties}
              onClick={() => onPickBar(b)}
              onPointerEnter={(e) => onShowTip(b, e.currentTarget)}
              onPointerLeave={onHideTip}
              onFocus={(e) => onShowTip(b, e.currentTarget)}
              onBlur={onHideTip}
            >
              <span className="rfr-bar-fill" aria-hidden />
              {showIcon && (
                <span className="rfr-bar-icon" aria-hidden>
                  <ToneIcon tone={b.tone} awaiting={b.awaiting} size={10} />
                </span>
              )}
              {(widthPct / 100) * axisWidth >= (showIcon ? 54 : 38) && (
                <span className="rfr-bar-text" aria-hidden>
                  {formatDuration(b.durationMs)}
                </span>
              )}
            </button>
          );
        })}
      </div>
    </div>
  );
}

type RecorderTipProps = {
  bar: Bar;
  hover: { x: number; y: number; below: boolean };
};

/** Hover/focus tooltip for one visit bar, floated above it (below near the viewport top). */
export function RecorderTip({ bar: hoverBar, hover }: RecorderTipProps) {
    const tHC = useTranslations("hcComponentsWorkflowRunRecorderParts");
  return (
    <div
      className="rfr-tip"
      role="tooltip"
      data-tone={hoverBar.tone}
      style={hover.below ? { left: hover.x, top: hover.y } : { left: hover.x, bottom: hover.y }}
    >
      <div className="rfr-tip-head">
        <ToneIcon tone={hoverBar.tone} awaiting={hoverBar.awaiting} size={13} />
        <span>
          {hoverBar.label}
          {hoverBar.visitCount > 1 ? ` - visit ${hoverBar.visitIndex + 1} of ${hoverBar.visitCount}` : ""}
        </span>
      </div>
      <div className="rfr-tip-sub">
        {statusText(hoverBar.tone, hoverBar.awaiting)} &middot; {formatDuration(hoverBar.durationMs)} &middot; at{" "}
        {formatClock(hoverBar.start)}
        {hoverBar.tokensIn !== null || hoverBar.tokensOut !== null
          ? ` · ${intFmt.format(hoverBar.tokensIn ?? 0)} in / ${intFmt.format(hoverBar.tokensOut ?? 0)} out tok`
          : ""}
        {hoverBar.costUsd !== null ? ` · ${formatCostUsd(hoverBar.costUsd)}` : ""}
      </div>
      {hoverBar.summary && <div className="rfr-tip-sum">{hoverBar.summary}</div>}
      {hoverBar.timing === "estimated" && <div className="rfr-tip-crit">{tHC("hcComponentsWorkflowRunRecorderParts.s2")}</div>}
      {hoverBar.critical && <div className="rfr-tip-crit">{tHC("hcComponentsWorkflowRunRecorderParts.s1")}</div>}
    </div>
  );
}
