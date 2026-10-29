"use client";

/*
 * RunFlightRecorder - the bottom "flight recorder" dock for one workflow run.
 *
 * A swim-lane waterfall (one lane per node, one bar per VISIT - loops and
 * fan-out keep every visit) with a draggable playhead. As the playhead moves,
 * each bar "records": its solid fill grows to show how far the run had got
 * through that visit at that instant, so replaying a run reads like watching
 * the tape. The dock owns the requestAnimationFrame loop that advances the
 * position while playing; position/playing/speed are controlled by the parent
 * so the canvas can light up the node under the playhead.
 *
 * No data fetching here - the parent passes a normalized run model.
 */

import { useCallback, useEffect, useId, useMemo, useRef, useState } from "react";
import type { CSSProperties, KeyboardEvent as ReactKeyboardEvent, PointerEvent as ReactPointerEvent } from "react";
import { ChevronDown, ChevronUp, Pause, Play, RotateCcw, X } from "lucide-react";
import { formatDuration } from "./runModel";
import type { RunEdge, RunModel } from "./runModel";
import "./runRecorderStyles";
import { PinnedCountMeta, PinSelectedOutput } from "./PinRunSummary";
import { RecorderLane, RecorderTip, ToneIcon, useElementWidth, useReducedMotion } from "./RunRecorderParts";
import {
  buildView,
  chooseTickStep,
  formatClock,
  formatRelative,
  formatTick,
  RECORDER_SPEEDS,
  speedLabel,
  statusText,
  type Bar,
  type RecorderSpeed,
} from "./runRecorderView";
import { useTranslations } from "../../lib/i18n";

/* ------------------------------------------------------------------ types */

export { RECORDER_SPEEDS, type RecorderSpeed } from "./runRecorderView";

export interface RunFlightRecorderProps {
  model: RunModel;
  /** Playhead position in ms from run start. */
  position: number;
  onPositionChange: (ms: number) => void;
  playing: boolean;
  onPlayingChange: (playing: boolean) => void;
  speed: RecorderSpeed;
  onSpeedChange: (speed: RecorderSpeed) => void;
  /** visitIndex is the 0-based occurrence of nodeId in the trace. */
  onSelectVisit: (nodeId: string, visitIndex: number) => void;
  nodeLabel: (nodeId: string) => string;
  onClose: () => void;
  /** Canvas edges; when given, the critical path follows real hand-offs instead of timing alone. */
  edges?: readonly RunEdge[];
  /** Currently selected visit, for the outline. Optional. */
  selected?: { nodeId: string; visitIndex: number } | null;
  defaultCollapsed?: boolean;
  className?: string;
}
/* --------------------------------------------------------------- component */

// Stable default so the view memo does not recompute every render.
const NO_EDGES: readonly RunEdge[] = [];
const MIN_H = 120;
const MAX_H = 440;
const DEFAULT_H = 188;
/** Reduced-motion replay: one node boundary per step. */
const REDUCED_STEP_MS = 600;

export default function RunFlightRecorder({
  model,
  position,
  onPositionChange,
  playing,
  onPlayingChange,
  speed,
  onSpeedChange,
  onSelectVisit,
  nodeLabel,
  onClose,
  selected = null,
  edges = NO_EDGES,
  defaultCollapsed = false,
  className,
}: RunFlightRecorderProps) {
    const tHC = useTranslations("hcComponentsWorkflowRunFlightRecorder");
  const view = useMemo(() => buildView(model, nodeLabel, edges), [model, nodeLabel, edges]);
  const { total } = view;
  const reducedMotion = useReducedMotion();
  const uid = useId();

  const [collapsed, setCollapsed] = useState(defaultCollapsed);
  const [height, setHeight] = useState(DEFAULT_H);
  const [hover, setHover] = useState<{ key: string; x: number; y: number; below: boolean } | null>(null);
  const [announce, setAnnounce] = useState("");
  const [now, setNow] = useState(() => Date.now());

  const rootRef = useRef<HTMLDivElement | null>(null);
  const [axisRef, axisWidth] = useElementWidth<HTMLDivElement>();

  const pos = Math.min(Math.max(position, 0), total);
  const pct = (ms: number) => `${(Math.min(Math.max(ms, 0), total) / total) * 100}%`;

  // Latest values for the rAF loop without restarting it every frame.
  const posRef = useRef(pos);
  const cbRef = useRef({ onPositionChange, onPlayingChange });
  const boundariesRef = useRef(view.boundaries);
  useEffect(() => {
    posRef.current = pos;
    cbRef.current = { onPositionChange, onPlayingChange };
    boundariesRef.current = view.boundaries;
  });

  /* ---- playback loop ---- */
  useEffect(() => {
    if (!playing) return undefined;
    const { onPositionChange: setPos, onPlayingChange: setPlaying } = cbRef.current;
    // Replaying from the very end starts over, like any tape deck.
    const from = posRef.current >= total ? 0 : posRef.current;
    if (speed === "instant") {
      setPos(total);
      setPlaying(false);
      setAnnounce("Replay jumped to end of run");
      return undefined;
    }
    if (from !== posRef.current) setPos(from);
    // Reduced motion still replays - playback is something the user asked
    // for, not decoration - but as discrete steps from one node boundary to
    // the next instead of a continuously sweeping playhead.
    if (reducedMotion) {
      const stops = boundariesRef.current.filter((b) => b > from);
      let i = 0;
      const t = window.setInterval(() => {
        const next = stops[i++] ?? total;
        cbRef.current.onPositionChange(next);
        if (next >= total) {
          window.clearInterval(t);
          cbRef.current.onPlayingChange(false);
          setAnnounce("Replay finished");
        }
      }, REDUCED_STEP_MS);
      return () => window.clearInterval(t);
    }
    let raf = 0;
    let last: number | null = null;
    let cur = from;
    const tick = (ts: number) => {
      if (last !== null) {
        cur = Math.min(total, cur + (ts - last) * speed);
        cbRef.current.onPositionChange(cur);
      }
      last = ts;
      if (cur >= total) {
        cbRef.current.onPlayingChange(false);
        setAnnounce("Replay finished");
        return;
      }
      raf = requestAnimationFrame(tick);
    };
    raf = requestAnimationFrame(tick);
    return () => cancelAnimationFrame(raf);
  }, [playing, speed, total, reducedMotion]);

  // Keep the "started 3 minutes ago" label honest without re-rendering per frame.
  useEffect(() => {
    const t = window.setInterval(() => setNow(Date.now()), 30_000);
    return () => window.clearInterval(t);
  }, []);

  /* ---- seeking ---- */
  const seek = useCallback(
    (ms: number) => {
      const next = Math.min(Math.max(ms, 0), total);
      // Update eagerly so rapid key repeats step from the new spot, not a stale render.
      posRef.current = next;
      cbRef.current.onPositionChange(next);
    },
    [total],
  );

  const msFromClientX = useCallback(
    (clientX: number) => {
      const el = axisRef.current;
      if (!el) return 0;
      const r = el.getBoundingClientRect();
      if (r.width <= 0) return 0;
      return ((clientX - r.left) / r.width) * total;
    },
    [axisRef, total],
  );

  const dragging = useRef(false);
  // Capture can throw for pointers the browser no longer tracks; scrubbing still works without it.
  const capture = (e: ReactPointerEvent<HTMLElement>) => {
    try {
      e.currentTarget.setPointerCapture(e.pointerId);
    } catch {
      /* not capturable */
    }
  };
  const onScrubDown = (e: ReactPointerEvent<HTMLElement>) => {
    if (e.button !== 0) return;
    // Bars handle their own clicks; only empty track / axis starts a scrub.
    if ((e.target as HTMLElement).closest(".rfr-bar-visit")) return;
    e.preventDefault();
    dragging.current = true;
    capture(e);
    if (playing) onPlayingChange(false);
    seek(msFromClientX(e.clientX));
  };
  const onScrubMove = (e: ReactPointerEvent<HTMLElement>) => {
    if (dragging.current) seek(msFromClientX(e.clientX));
  };
  const onScrubUp = (e: ReactPointerEvent<HTMLElement>) => {
    dragging.current = false;
    if (e.currentTarget.hasPointerCapture(e.pointerId)) e.currentTarget.releasePointerCapture(e.pointerId);
  };

  // Collapsed mini strip scrubs against its own width.
  const miniRef = useRef<HTMLDivElement | null>(null);
  const miniSeek = (clientX: number) => {
    const el = miniRef.current;
    if (!el) return;
    const r = el.getBoundingClientRect();
    if (r.width > 0) seek(((clientX - r.left) / r.width) * total);
  };

  /* ---- keyboard ---- */
  const barAt = useCallback(
    (ms: number): Bar | undefined => view.bars.find((b) => b.start === ms) ?? view.bars.find((b) => b.end === ms),
    [view.bars],
  );

  const step = useCallback(
    (dir: 1 | -1) => {
      const eps = 0.5;
      const pos = posRef.current;
      const target =
        dir > 0
          ? view.boundaries.find((b) => b > pos + eps) ?? total
          : [...view.boundaries].reverse().find((b) => b < pos - eps) ?? 0;
      if (playing) onPlayingChange(false);
      seek(target);
      const b = barAt(target);
      setAnnounce(
        b
          ? `${formatClock(target)}: ${b.label}${b.visitCount > 1 ? `, visit ${b.visitIndex + 1} of ${b.visitCount}` : ""}, ${b.start === target ? "starts" : "ends"}, ${statusText(b.tone, b.awaiting)}`
          : formatClock(target),
      );
    },
    [view.boundaries, total, playing, onPlayingChange, seek, barAt],
  );

  const onKeyDown = (e: ReactKeyboardEvent<HTMLDivElement>) => {
    if (e.altKey || e.ctrlKey || e.metaKey) return;
    const t = e.target as HTMLElement;
    if (t.closest("input, textarea, select, [contenteditable='true']")) return;
    const inRadio = !!t.closest("[role='radiogroup']");
    const onButton = t.tagName === "BUTTON";
    switch (e.key) {
      case " ":
      case "Spacebar":
        if (onButton) return; // let the focused control activate
        e.preventDefault();
        onPlayingChange(!playing);
        return;
      case "ArrowLeft":
      case "ArrowRight":
        if (inRadio) return; // the speed group handles its own arrows
        e.preventDefault();
        step(e.key === "ArrowRight" ? 1 : -1);
        return;
      case "Home":
        e.preventDefault();
        if (playing) onPlayingChange(false);
        seek(0);
        setAnnounce("Start of run");
        return;
      case "End":
        e.preventDefault();
        if (playing) onPlayingChange(false);
        seek(total);
        setAnnounce("End of run");
        return;
      default:
    }
  };

  const onSpeedKey = (e: ReactKeyboardEvent<HTMLDivElement>) => {
    if (e.key !== "ArrowLeft" && e.key !== "ArrowRight") return;
    e.preventDefault();
    const i = RECORDER_SPEEDS.indexOf(speed);
    const next = RECORDER_SPEEDS[(i + (e.key === "ArrowRight" ? 1 : -1) + RECORDER_SPEEDS.length) % RECORDER_SPEEDS.length];
    onSpeedChange(next);
    const btn = e.currentTarget.querySelector<HTMLButtonElement>(`[data-speed="${String(next)}"]`);
    btn?.focus();
  };

  /* ---- resize ---- */
  const resizeStart = useRef<{ y: number; h: number } | null>(null);
  const onGripDown = (e: ReactPointerEvent<HTMLButtonElement>) => {
    resizeStart.current = { y: e.clientY, h: height };
    capture(e);
  };
  const onGripMove = (e: ReactPointerEvent<HTMLButtonElement>) => {
    const s = resizeStart.current;
    if (s) setHeight(Math.min(MAX_H, Math.max(MIN_H, s.h + (s.y - e.clientY))));
  };
  const onGripUp = () => {
    resizeStart.current = null;
  };
  const onGripKey = (e: ReactKeyboardEvent<HTMLButtonElement>) => {
    if (e.key !== "ArrowUp" && e.key !== "ArrowDown") return;
    e.preventDefault();
    e.stopPropagation();
    setHeight((h) => Math.min(MAX_H, Math.max(MIN_H, h + (e.key === "ArrowUp" ? 24 : -24))));
  };

  /* ---- tooltip ---- */
  const showTip = (b: Bar, el: HTMLElement) => {
    const root = rootRef.current;
    if (!root) return;
    const rr = root.getBoundingClientRect();
    const br = el.getBoundingClientRect();
    const x = Math.min(Math.max(br.left - rr.left + Math.min(br.width, 40) / 2, 8), Math.max(8, rr.width - 248));
    // The dock sits at the bottom of the canvas, so the tip floats above the bar (it may
    // rise past the dock's top edge); flip below only near the viewport top.
    const below = br.top < 130;
    setHover({ key: b.key, x, y: below ? br.bottom - rr.top + 6 : rr.bottom - br.top + 6, below });
  };
  const hideTip = useCallback(() => setHover(null), []);
  const hoverBar = hover ? view.bars.find((b) => b.key === hover.key) : undefined;

  const scrub = { onPointerDown: onScrubDown, onPointerMove: onScrubMove, onPointerUp: onScrubUp, onPointerCancel: onScrubUp };
  const pickBar = (b: Bar) => {
    if (playing) onPlayingChange(false);
    seek(b.start);
    onSelectVisit(b.nodeId, b.visitIndex);
  };

  /* ---- derived render data ---- */
  const tickStep = chooseTickStep(total, axisWidth);
  const ticks: number[] = [];
  for (let t = 0; t <= total + 0.001 && ticks.length < 200; t += tickStep) ticks.push(t);
  const events = view.bars.filter((b) => b.tone === "error" || b.tone === "paused");
  const atEnd = pos >= total;
  const visitCount = view.bars.length;
  const failed = view.bars.filter((b) => b.tone === "error").length;

  const rootStyle = { "--rfr-h": `${height}px` } as CSSProperties;
  const statusLabel = statusText(view.tone, view.runAwaiting);
  const pinnedCount = useMemo(() => model.visits.reduce((n, v) => (v.pinned ? n + 1 : n), 0), [model.visits]);

  return (
    // eslint-disable-next-line jsx-a11y/no-noninteractive-element-interactions -- region-level transport shortcuts bubble from focusable children
    <div
      ref={rootRef}
      className={`rfr${className ? ` ${className}` : ""}`}
      style={rootStyle}
      data-collapsed={collapsed}
      data-live={view.live && !reducedMotion}
      role="region"
      aria-label={tHC("hcComponentsWorkflowRunFlightRecorder.s9")}
      aria-roledescription="run timeline"
      tabIndex={-1}
      onKeyDown={onKeyDown}
    >
      {!collapsed && (
        <button
          type="button"
          className="rfr-grip"
          aria-label={tHC("hcComponentsWorkflowRunFlightRecorder.s8")}
          onPointerDown={onGripDown}
          onPointerMove={onGripMove}
          onPointerUp={onGripUp}
          onPointerCancel={onGripUp}
          onKeyDown={onGripKey}
        />
      )}

      <div className="rfr-bar">
        <button
          type="button"
          className="rfr-btn"
          aria-expanded={!collapsed}
          aria-controls={`${uid}-body`}
          aria-label={collapsed ? "Expand flight recorder" : "Collapse flight recorder"}
          title={collapsed ? "Expand" : "Collapse"}
          onClick={() => setCollapsed((c) => !c)}
        >
          {collapsed ? <ChevronUp size={15} aria-hidden /> : <ChevronDown size={15} aria-hidden />}
        </button>

        <span className="rfr-title">
          <span className="rfr-rec" aria-hidden />
          <span className="rfr-title-text">{tHC("hcComponentsWorkflowRunFlightRecorder.s7")}</span>
        </span>

        <button
          type="button"
          className="rfr-btn rfr-play"
          aria-pressed={playing}
          aria-label={playing ? "Pause replay (Space)" : atEnd ? "Replay from start (Space)" : "Play replay (Space)"}
          title={playing ? "Pause (Space)" : "Play (Space)"}
          onClick={() => onPlayingChange(!playing)}
          disabled={visitCount === 0}
        >
          {playing ? <Pause size={14} aria-hidden /> : atEnd ? <RotateCcw size={14} aria-hidden /> : <Play size={14} aria-hidden />}
        </button>

        {/* eslint-disable-next-line jsx-a11y/interactive-supports-focus -- roving tabindex lives on the radios; arrow keys bubble to the group */}
        <div className="rfr-speeds" role="radiogroup" aria-label={tHC("hcComponentsWorkflowRunFlightRecorder.s6")} onKeyDown={onSpeedKey}>
          {RECORDER_SPEEDS.map((s) => (
            <button
              key={String(s)}
              type="button"
              role="radio"
              className="rfr-chip"
              data-speed={String(s)}
              aria-checked={speed === s}
              tabIndex={speed === s ? 0 : -1}
              onClick={() => onSpeedChange(s)}
            >
              {speedLabel(s)}
            </button>
          ))}
        </div>
        <button
          type="button"
          className="rfr-btn rfr-speed-cycle"
          aria-label={`Replay speed ${speedLabel(speed)}. Activate to change.`}
          onClick={() => onSpeedChange(RECORDER_SPEEDS[(RECORDER_SPEEDS.indexOf(speed) + 1) % RECORDER_SPEEDS.length])}
        >
          {speed === "instant" ? "Inst" : speedLabel(speed)}
        </button>

        <span className="rfr-clock" aria-hidden>
          {formatClock(pos)}
          <span> / {formatClock(total)}</span>
        </span>

        {collapsed ? (
          <div
            ref={miniRef}
            className="rfr-mini"
            role="slider"
            tabIndex={0}
            aria-label={tHC("hcComponentsWorkflowRunFlightRecorder.s5")}
            aria-valuemin={0}
            aria-valuemax={Math.round(total)}
            aria-valuenow={Math.round(pos)}
            aria-valuetext={`${formatDuration(pos)} of ${formatDuration(total)}`}
            onPointerDown={(e) => {
              capture(e);
              dragging.current = true;
              if (playing) onPlayingChange(false);
              miniSeek(e.clientX);
            }}
            onPointerMove={(e) => dragging.current && miniSeek(e.clientX)}
            onPointerUp={() => {
              dragging.current = false;
            }}
          >
            {view.bars.map((b) => (
              <span
                key={b.key}
                className="rfr-mini-seg"
                data-tone={b.tone}
                style={{ left: pct(b.start), width: `${((b.end - b.start) / total) * 100}%` }}
              />
            ))}
            <span className="rfr-mini-head" style={{ left: pct(pos) }} />
          </div>
        ) : (
          <span className="rfr-spacer" />
        )}

        <span className="rfr-meta">
          <span className="rfr-pill" data-tone={view.tone} title={statusLabel}>
            <ToneIcon tone={view.tone} awaiting={view.runAwaiting} size={11} />
            <span className="rfr-pill-text">{statusLabel}</span>
          </span>
          <span className="rfr-meta-item" title={tHC("hcComponentsWorkflowRunFlightRecorder.s4")}>
            {formatDuration(total)}
          </span>
          {view.startedAtMs !== null && (
            <span
              className="rfr-meta-item rfr-meta-started"
              title={new Date(view.startedAtMs).toLocaleString()}
            >
              {formatRelative(view.startedAtMs, now)}
            </span>
          )}
          <span className="rfr-meta-item rfr-meta-visits">
            {visitCount} {visitCount === 1 ? "step" : "steps"}
            {failed > 0 ? `, ${failed} failed` : ""}
          </span>
          <PinnedCountMeta count={pinnedCount} className="rfr-meta-item wfx-pin-count" />
          <PinSelectedOutput
            visit={selected ? model.byNode[selected.nodeId]?.[selected.visitIndex] : undefined}
            runId={model.run.id}
          />
        </span>

        <button type="button" className="rfr-btn" aria-label={tHC("hcComponentsWorkflowRunFlightRecorder.s3")} title="Close" onClick={onClose}>
          <X size={15} aria-hidden />
        </button>
      </div>

      <div className="rfr-body" id={`${uid}-body`} hidden={collapsed}>
        {visitCount === 0 ? (
          <div className="rfr-empty">{tHC("hcComponentsWorkflowRunFlightRecorder.s2")}</div>
        ) : (
          <div className="rfr-scroll">
            <div className="rfr-inner">
              <div className="rfr-axis">
                <div className="rfr-axis-corner" aria-hidden>
                  Node
                </div>
                <div
                  ref={axisRef}
                  className="rfr-axis-track"
                  onPointerDown={onScrubDown}
                  onPointerMove={onScrubMove}
                  onPointerUp={onScrubUp}
                  onPointerCancel={onScrubUp}
                >
                  {ticks.map((t) => (
                    <span key={t} className="rfr-tick" style={{ left: pct(t) }} aria-hidden>
                      {/* Last label would clip against the right edge. */}
                      {t > 0 && t / total < 0.93 && <span className="rfr-tick-label">{formatTick(t, tickStep)}</span>}
                    </span>
                  ))}
                  {events.map((b) => (
                    <span
                      key={`ev-${b.key}`}
                      className="rfr-event"
                      data-tone={b.tone}
                      style={{ left: pct(b.tone === "error" ? b.end : b.start) }}
                      aria-hidden
                    />
                  ))}
                  <div
                    className="rfr-head-knob"
                    role="slider"
                    tabIndex={0}
                    aria-label={tHC("hcComponentsWorkflowRunFlightRecorder.s1")}
                    aria-valuemin={0}
                    aria-valuemax={Math.round(total)}
                    aria-valuenow={Math.round(pos)}
                    aria-valuetext={`${formatDuration(pos)} of ${formatDuration(total)}`}
                    style={{ left: `calc(${pct(pos)} - 7px)` }}
                  />
                </div>
              </div>
              {view.lanes.map((lane) => (
                <RecorderLane
                  key={lane.nodeId}
                  lane={lane}
                  pos={pos}
                  total={total}
                  axisWidth={axisWidth}
                  selected={selected}
                  scrub={scrub}
                  onPickBar={pickBar}
                  onShowTip={showTip}
                  onHideTip={hideTip}
                />
              ))}

              <div className="rfr-overlay" aria-hidden>
                <div className="rfr-playhead" style={{ left: pct(pos) }} />
              </div>
            </div>
          </div>
        )}
      </div>
      {hoverBar && hover && !collapsed && <RecorderTip bar={hoverBar} hover={hover} />}

      <div className="rfr-sr" aria-live="polite" aria-atomic="true">
        {announce}
      </div>
    </div>
  );
}
