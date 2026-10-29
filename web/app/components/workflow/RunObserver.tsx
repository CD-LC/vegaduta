"use client";

// Observe-mode chrome for the workflow canvas: the "viewing run" banner, the
// flight log rail (+ run story), the lens bar and step inspector floating over
// the graph, and the flight recorder dock. Every piece reads the single
// useRunObserver() state, so the canvas stays one source of truth: the
// playhead position drives the graph, the waterfall, the story and the
// inspector together.

import { useCallback, useEffect, useId, useMemo, useRef, useState } from "react";
import { useRouter } from "next/navigation";
import { useReactFlow, useStore } from "@xyflow/react";
import {
  AlertTriangle,
  BookOpen,
  CheckCircle2,
  ChevronDown,
  Circle,
  Hand,
  Pencil,
  Radio,
  X,
  XCircle,
  type LucideIcon,
} from "lucide-react";
import RunFlightRecorder from "./RunFlightRecorder";
import RunHistoryRail, { type RunSummary as RailRun } from "./RunHistoryRail";
import RunStepInspector from "./RunStepInspector";
import CanvasLensBar from "./CanvasLensBar";
import { formatCostUsd, formatDuration, narrateLines, parseRunStatus, type NarrationLine, type NarrationTone, type RunEdge } from "./runModel";
import { entryToolCalls, runTraceEntries, toInspectorVisit, wireRunDurationMs } from "./runOverlay";
import ApprovalPauseCard, { type RunAwaiting } from "./ApprovalPauseCard";
import type { RunObserver } from "./useRunObserver";
import { NODE_WIDTH } from "./canvasOps";
import { useReducedMotionSafe } from "../../lib/useReducedMotionSafe";
import "./run-observer.css";
import { useTranslations } from "../../lib/i18n";

const NODE_HEIGHT_GUESS = 96;
// Mirrors run-inspector.css: below 640px the step inspector is a sheet over
// the bottom ~55% of the canvas stage.
const SHEET_QUERY = "(max-width: 639.98px)";
const SHEET_FRACTION = 0.55;
const selectPaneHeight = (s: { height: number }) => s.height;

type Labeler = (nodeId: string) => string;

// ---------------------------------------------------------------------------
// Actions shared by canvas clicks, waterfall bars, story lines and the inspector
// ---------------------------------------------------------------------------

export function useObserveActions(obs: RunObserver) {
  const { getInternalNode, setCenter, getZoom } = useReactFlow();
  const paneHeight = useStore(selectPaneHeight);
  const reduced = useReducedMotionSafe();
  const { model, setSelectedVisit, setPosition, setPlaying } = obs;

  /**
   * Centre a node in view. `aboveSheet`: the step inspector is about to open,
   * so on phones the node is centred in the band ABOVE the bottom sheet
   * rather than behind it.
   */
  const focusNode = useCallback(
    (nodeId: string, aboveSheet = false) => {
      const n = getInternalNode(nodeId);
      if (!n) return;
      const p = n.internals.positionAbsolute;
      const w = n.measured.width ?? NODE_WIDTH;
      const h = n.measured.height ?? NODE_HEIGHT_GUESS;
      const zoom = Math.max(getZoom(), 0.8);
      const sheet = aboveSheet && typeof window !== "undefined" && !!window.matchMedia?.(SHEET_QUERY).matches;
      // Moving the viewport centre DOWN by half the sheet lifts the node into the visible band.
      const dy = sheet ? (paneHeight * SHEET_FRACTION) / 2 / zoom : 0;
      void setCenter(p.x + w / 2, p.y + h / 2 + dy, { zoom, duration: reduced ? 0 : 420 });
    },
    [getInternalNode, setCenter, getZoom, paneHeight, reduced],
  );

  /** Open the inspector on one visit and park the playhead at its outcome. */
  const openVisit = useCallback(
    (nodeId: string, visitIndex: number, seek: "end" | "keep" = "end") => {
      const v = model?.byNode[nodeId]?.[visitIndex];
      if (!model || !v) return;
      setPlaying(false);
      setSelectedVisit({ nodeId, visitIndex });
      if (seek === "end") setPosition((v.endMs ?? v.startMs) - model.t0);
    },
    [model, setPlaying, setSelectedVisit, setPosition],
  );

  /** Canvas node click: the visit on screen at this moment, else the node's last. */
  const openNode = useCallback(
    (nodeId: string) => {
      const visits = model?.byNode[nodeId];
      if (!model || !visits?.length) {
        setSelectedVisit(null);
        return false;
      }
      const onScreen = obs.display?.frame.nodes[nodeId]?.activeVisit;
      if (onScreen) openVisit(nodeId, onScreen.visitIndex, "keep");
      else openVisit(nodeId, visits.length - 1);
      return true;
    },
    [model, obs.display, openVisit, setSelectedVisit],
  );

  return { focusNode, openVisit, openNode };
}

// ---------------------------------------------------------------------------
// Focus handoff
// ---------------------------------------------------------------------------

/**
 * The controls that leave observe mode (banner "Back to editing", the dock's
 * close) unmount themselves, which would drop keyboard focus to <body>. After
 * the mode flips, focus lands on the toolbar toggle that brings observe back.
 * `[data-runs-toggle]` is the stable hook; the toolbar's first button is the
 * fallback until the canvas zone carries it.
 */
export function focusRunsToggle(): void {
  if (typeof window === "undefined") return;
  window.requestAnimationFrame(() => {
    const el =
      document.querySelector<HTMLElement>("[data-runs-toggle]") ??
      document.querySelector<HTMLElement>(".wf-toolbar > button[aria-pressed]:first-child");
    el?.focus();
  });
}

/** Wraps an exit-observe action so focus survives the controls unmounting. */
export function withFocusHandoff(exit: () => void): () => void {
  return () => {
    exit();
    focusRunsToggle();
  };
}

// ---------------------------------------------------------------------------
// Banner
// ---------------------------------------------------------------------------

const STATUS_WORD: Record<string, string> = {
  running: "In flight",
  completed: "Completed",
  failed: "Failed",
  cancelled: "Cancelled",
  paused: "Paused",
  awaiting: "Waiting on a person",
  unknown: "Unknown",
};

export function ObserveBanner({
  obs,
  onBackToEdit,
  liveCostUsd,
  streaming = false,
  onDecide,
}: {
  obs: RunObserver;
  onBackToEdit: () => void;
  /** Set while the run on screen is parked on a person: shows "Decide", which focuses the decision card. */
  onDecide?: () => void;
  /** Running total from the live stream (node.completed runTotals). */
  liveCostUsd?: number | null;
  /** Live stream open for the run on screen (vs. polling). */
  streaming?: boolean;
}) {
    const tHC = useTranslations("hcComponentsWorkflowRunObserver");
  const { model, selectedRunId, selectedRaw, compareRunId, runs, flightNumbers, following, selectedLive } = obs;
  const num = selectedRunId ? flightNumbers[selectedRunId] : undefined;
  const status = parseRunStatus(selectedRaw?.status);
  const compareRaw = compareRunId ? runs.find((r) => String(r.id) === compareRunId) : undefined;
  const mine = selectedRaw ? wireRunDurationMs(selectedRaw) : null;
  const theirs = compareRaw ? wireRunDurationMs(compareRaw) : null;
  const delta = mine != null && theirs != null ? mine - theirs : null;
  const Icon: LucideIcon = selectedLive ? Radio : status === "failed" ? XCircle : status === "completed" ? CheckCircle2 : Circle;
  const titleRef = useRef<HTMLSpanElement>(null);
  const statusWord = selectedRaw ? STATUS_WORD[status] ?? status : "";
  const jumpToLive = () => {
    obs.setPosition(Number.POSITIVE_INFINITY);
    // The button unmounts once following; keep focus on the banner it lived in.
    titleRef.current?.focus();
  };
  const backToEdit = () => {
    onBackToEdit();
    focusRunsToggle();
  };

  return (
    <div className="rob-banner" role="region" aria-label={tHC("hcComponentsWorkflowRunObserver.s9")} data-tone={selectedLive ? "live" : status}>
      <span className="rob-banner-state">
        <Icon size={14} aria-hidden="true" className={selectedLive ? "rob-live-icon" : undefined} />
        <span className="rob-banner-title" ref={titleRef} tabIndex={-1}>
          {selectedRunId ? (
            <>
              {selectedLive ? "Live" : "Viewing"} run{num ? ` #${num}` : ""}
            </>
          ) : (
            "Observing runs"
          )}
        </span>
      </span>
      {selectedRaw && (
        // Only the status word is announced: the duration ticks every
        // half-second on a live run and would flood a screen reader.
        <span className="rob-banner-meta">
          <span aria-hidden="true">{statusWord}</span>
          {model && <span className="rob-mono">{formatDuration(model.totalMs)}</span>}
          {model?.run.workflowVersion != null && <span className="rob-mono">v{model.run.workflowVersion}</span>}
          {liveCostUsd != null && Number.isFinite(liveCostUsd) && (
            <span className="rob-mono" title={tHC("hcComponentsWorkflowRunObserver.s8")}>
              <span className="rob-sr">{tHC("hcComponentsWorkflowRunObserver.s7")} </span>
              {formatCostUsd(liveCostUsd)}
            </span>
          )}
          {selectedLive && streaming && (
            <span className="rob-mono" title={tHC("hcComponentsWorkflowRunObserver.s6")}>
              streaming
            </span>
          )}
        </span>
      )}
      {compareRaw && (
        <span className="rob-compare">
          vs #{flightNumbers[String(compareRaw.id)] ?? "?"}
          {delta != null && Math.abs(delta) >= 1 ? (
            <strong>
              {" "}
              {formatDuration(Math.abs(delta))} {delta < 0 ? "faster" : "slower"}
            </strong>
          ) : delta != null ? (
            <strong> {tHC("hcComponentsWorkflowRunObserver.s5")}</strong>
          ) : null}
          <button type="button" className="rob-icon-btn" aria-label={tHC("hcComponentsWorkflowRunObserver.s4")} onClick={() => obs.toggleCompare(compareRunId as string)}>
            <X size={12} aria-hidden="true" />
          </button>
        </span>
      )}
      <span className="rob-sr" role="status" aria-live="polite">
        {statusWord}
      </span>
      <span className="rob-banner-spacer" />
      {onDecide && (
        <button type="button" className="rob-btn" onClick={onDecide}>
          <Hand size={13} aria-hidden="true" /> Decide
        </button>
      )}
      {selectedLive && !following && (
        <button type="button" className="rob-btn" onClick={jumpToLive}>
          <Radio size={13} aria-hidden="true" /> {tHC("hcComponentsWorkflowRunObserver.s3")}
        </button>
      )}
      <button type="button" className="rob-btn rob-btn-primary" onClick={backToEdit}>
        <Pencil size={13} aria-hidden="true" /> {tHC("hcComponentsWorkflowRunObserver.s2")}
      </button>
    </div>
  );
}

// ---------------------------------------------------------------------------
// Flight log rail + run story
// ---------------------------------------------------------------------------

const TONE_ICON: Record<NarrationTone, LucideIcon> = {
  neutral: Circle,
  good: CheckCircle2,
  warn: AlertTriangle,
  bad: XCircle,
};
const TONE_WORD: Record<NarrationTone, string> = { neutral: "", good: "Success: ", warn: "Note: ", bad: "Problem: " };

export function RunStory({
  lines,
  position,
  onPick,
}: {
  lines: NarrationLine[];
  position: number;
  onPick: (line: NarrationLine) => void;
}) {
    const tHC = useTranslations("hcComponentsWorkflowRunObserver");
  const [open, setOpen] = useState(false);
  const bodyId = useId();
  // The line being "read" is the latest one the playhead has passed, so the
  // story follows along while a replay plays.
  let current = -1;
  lines.forEach((l, i) => {
    if (l.atMs == null || l.atMs <= position + 1) current = i;
  });
  const preview = lines[lines.length - 1];
  if (!lines.length) return null;
  return (
    <section className="rob-story" data-open={open ? "true" : "false"}>
      <button type="button" className="rob-story-head" aria-expanded={open} aria-controls={bodyId} onClick={() => setOpen((o) => !o)}>
        <BookOpen size={14} aria-hidden="true" />
        <span className="rob-story-title">{tHC("hcComponentsWorkflowRunObserver.s1")}</span>
        {!open && preview && <span className="rob-story-preview">{preview.text}</span>}
        <ChevronDown size={14} aria-hidden="true" className="rob-story-chev" />
      </button>
      {open && (
        <ol className="rob-story-list" id={bodyId}>
          {lines.map((l, i) => {
            const ToneIcon = TONE_ICON[l.tone];
            const clickable = l.atMs != null || l.nodeId != null;
            const body = (
              <>
                <ToneIcon size={12} aria-hidden="true" className="rob-story-ico" />
                <span>
                  <span className="rob-sr">{TONE_WORD[l.tone]}</span>
                  {l.text}
                </span>
              </>
            );
            return (
              <li key={i} data-tone={l.tone} data-told={i <= current ? "true" : undefined} aria-current={i === current ? "step" : undefined}>
                {clickable ? (
                  <button type="button" className="rob-story-line" onClick={() => onPick(l)}>
                    {body}
                  </button>
                ) : (
                  <span className="rob-story-line">{body}</span>
                )}
              </li>
            );
          })}
        </ol>
      )}
    </section>
  );
}

export function ObserveRail({
  obs,
  nodeLabel,
  runEdges,
  onRunNow,
}: {
  obs: RunObserver;
  nodeLabel: Labeler;
  runEdges: readonly RunEdge[];
  onRunNow?: () => void;
}) {
  const { focusNode } = useObserveActions(obs);
  const railRuns: RailRun[] = useMemo(
    () =>
      obs.runs.map((r) => ({
        id: String(r.id ?? ""),
        status: String(r.status ?? ""),
        startedAt: typeof r.startedAt === "string" ? r.startedAt : null,
        completedAt: typeof r.completedAt === "string" ? r.completedAt : null,
        requestedBy: r.requestedBy ?? null,
        workflowVersion: r.workflowVersion == null ? null : Number(r.workflowVersion),
      })),
    [obs.runs],
  );
  const lines = useMemo(() => (obs.model ? narrateLines(obs.model, nodeLabel, { edges: runEdges }) : []), [obs.model, nodeLabel, runEdges]);
  const { setPlaying, setPosition, model } = obs;
  const onPick = useCallback(
    (l: NarrationLine) => {
      setPlaying(false);
      if (l.atMs != null) setPosition(l.atMs);
      if (l.nodeId) focusNode(l.nodeId);
    },
    [setPlaying, setPosition, focusNode],
  );

  return (
    <div className="rob-rail-slot">
      <RunHistoryRail
        runs={railRuns}
        selectedRunId={obs.selectedRunId}
        onSelectRun={obs.selectRun}
        loading={obs.runsLoading}
        onRefresh={obs.refreshRuns}
        compareRunId={obs.compareRunId}
        onCompare={obs.toggleCompare}
        onRunNow={onRunNow}
        footer={model ? <RunStory lines={lines} position={obs.position} onPick={onPick} /> : null}
      />
    </div>
  );
}

// ---------------------------------------------------------------------------
// Overlay inside the canvas zone: lens bar + step inspector
// ---------------------------------------------------------------------------

const selectTransform = (s: { transform: [number, number, number] }) => s.transform;

export function ObserveOverlay({
  obs,
  workflowId,
  nodeLabel,
  awaiting = null,
  onAwaitingResolved,
  canDecide = true,
  decideRequest = 0,
}: {
  obs: RunObserver;
  workflowId: string;
  nodeLabel: Labeler;
  /** The selected run is parked on a person (stream `run.awaiting` or polled status). */
  awaiting?: RunAwaiting | null;
  onAwaitingResolved?: () => void;
  canDecide?: boolean;
  /** Bump to open (if dismissed) and focus the decision card - ObserveBanner's "Decide". */
  decideRequest?: number;
}) {
  const router = useRouter();
  const { getInternalNode } = useReactFlow();
  const [tx, ty, zoom] = useStore(selectTransform);
  const { model, selectedVisit, setSelectedVisit, runs, selectedRaw, selectedRunId } = obs;
  const { openVisit } = useObserveActions(obs);

  const visit = selectedVisit && model ? model.byNode[selectedVisit.nodeId]?.[selectedVisit.visitIndex] ?? null : null;
  // Anchor in the zone's own pixels: React Flow fills the zone, so flow ->
  // zone coordinates are just the viewport transform (no DOM reads needed).
  const anchorOf = (nodeId: string | undefined) => {
    const internal = nodeId ? getInternalNode(nodeId) : undefined;
    return internal
      ? {
          x: internal.internals.positionAbsolute.x * zoom + tx,
          y: internal.internals.positionAbsolute.y * zoom + ty,
          width: (internal.measured.width ?? NODE_WIDTH) * zoom,
          height: (internal.measured.height ?? NODE_HEIGHT_GUESS) * zoom,
        }
      : null;
  };
  const anchor = anchorOf(visit?.nodeId);

  // A run switch can leave a stale selection pointing at nothing.
  useEffect(() => {
    if (selectedVisit && !visit) setSelectedVisit(null);
  }, [selectedVisit, visit, setSelectedVisit]);

  const close = useCallback(() => setSelectedVisit(null), [setSelectedVisit]);
  // Tool calls live on the raw trace entry (the run model does not carry them).
  const visitEntry = visit ? runTraceEntries(selectedRaw)[visit.traceIndex] : undefined;
  const inspectorVisit = useMemo(
    () => (visit ? toInspectorVisit(visit, nodeLabel(visit.nodeId), entryToolCalls(visitEntry)) : null),
    [visit, nodeLabel, visitEntry],
  );

  // Decision card: one per (run, parked node). Closing it hides it until the
  // run parks somewhere else; opening the step inspector yields the space.
  const awaitKey = awaiting && selectedRunId ? `${selectedRunId}:${awaiting.nodeId}:${awaiting.status}` : null;
  const [dismissed, setDismissed] = useState<string | null>(null);
  const showCard = !!awaiting && !!awaitKey && !!selectedRunId && dismissed !== awaitKey && !visit;
  const dismissCard = useCallback(() => setDismissed(awaitKey), [awaitKey]);

  // Keyboard route to the card (banner "Decide"): reopen it if dismissed,
  // yield the inspector's space, then move focus in once it is mounted.
  const [focusCard, setFocusCard] = useState(false);
  const lastDecideRef = useRef(decideRequest);
  useEffect(() => {
    if (decideRequest === lastDecideRef.current) return;
    lastDecideRef.current = decideRequest;
    setDismissed(null);
    setSelectedVisit(null);
    setFocusCard(true);
  }, [decideRequest, setSelectedVisit]);
  useEffect(() => {
    if (!focusCard || !showCard) return undefined;
    const t = setTimeout(() => setFocusCard(false), 0);
    return () => clearTimeout(t);
  }, [focusCard, showCard]);

  // Focus leaving an unmounted card lands on the parked node, else the banner.
  const parkedNodeId = awaiting?.nodeId;
  const focusAfterCard = useCallback(() => {
    const esc = typeof CSS !== "undefined" && typeof CSS.escape === "function" ? CSS.escape : (s: string) => s;
    const node = parkedNodeId ? document.querySelector<HTMLElement>(`.react-flow__node[data-id="${esc(parkedNodeId)}"]`) : null;
    const target = node ?? document.querySelector<HTMLElement>(".rob-banner-title");
    if (target) {
      if (!target.hasAttribute("tabindex")) target.tabIndex = -1;
      target.focus();
    }
  }, [parkedNodeId]);

  return (
    <>
      <CanvasLensBar
        lens={obs.lens}
        onLensChange={obs.setLens}
        runsWindow={obs.runsWindow}
        onRunsWindowChange={obs.setRunsWindow}
        legend={obs.legend}
        disabledReason={runs.length === 0 ? "Run the workflow once to unlock lenses" : undefined}
        lensDisabledReasons={selectedRaw ? undefined : { critical: "Select a run first" }}
        loadedRuns={obs.loadedWindowRuns}
      />
      {visit && inspectorVisit && model && (
        <RunStepInspector
          visit={inspectorVisit}
          visitCount={visit.visitCount}
          onPrevVisit={() => openVisit(visit.nodeId, visit.visitIndex - 1)}
          onNextVisit={() => openVisit(visit.nodeId, visit.visitIndex + 1)}
          onClose={close}
          onOpenFullRun={
            model.run.id ? () => router.push(`/workflows/${workflowId}/runs/${model.run.id}`) : undefined
          }
          anchor={anchor}
          runSpan={{ startMs: model.t0, endMs: model.live ? null : model.t1 }}
          pinRunId={model.run.id || null}
          workflowId={workflowId}
        />
      )}
      {showCard && awaiting && selectedRunId && (
        <ApprovalPauseCard
          key={awaitKey ?? undefined}
          awaiting={awaiting}
          workflowId={workflowId}
          runId={selectedRunId}
          canDecide={canDecide}
          anchor={anchorOf(awaiting.nodeId)}
          onClose={dismissCard}
          autoFocus={focusCard}
          onFocusFallback={focusAfterCard}
          // Stays up with its "done" line until the run leaves the parked
          // state (stream run.resumed / next poll), which unmounts it.
          onResolved={() => onAwaitingResolved?.()}
        />
      )}
    </>
  );
}

// ---------------------------------------------------------------------------
// Bottom dock
// ---------------------------------------------------------------------------

export function ObserveDock({
  obs,
  nodeLabel,
  runEdges,
  onClose,
}: {
  obs: RunObserver;
  nodeLabel: Labeler;
  runEdges: readonly RunEdge[];
  onClose: () => void;
}) {
  const { openVisit, focusNode } = useObserveActions(obs);
  const closeDock = useMemo(() => withFocusHandoff(onClose), [onClose]);
  // Phones start with the 40px mini-strip so the graph keeps its height. Safe
  // to read at mount: the dock only exists after a client-side observe action.
  const [startCollapsed] = useState(() => typeof window !== "undefined" && !!window.matchMedia?.("(max-width: 640px)").matches);
  const onSelectVisit = useCallback(
    (nodeId: string, visitIndex: number) => {
      openVisit(nodeId, visitIndex, "keep");
      focusNode(nodeId, true);
    },
    [openVisit, focusNode],
  );
  if (!obs.model) {
    return (
      <div className="rob-dock-empty" role="status">
        {obs.runsLoading
          ? "Loading the flight log..."
          : obs.runs.length
            ? "Pick a run from the flight log to replay it here."
            : "No runs recorded yet. Run the workflow and its flight lands here."}
      </div>
    );
  }
  return (
    <RunFlightRecorder
      model={obs.model}
      position={obs.position}
      onPositionChange={obs.setPosition}
      playing={obs.playing}
      onPlayingChange={obs.setPlaying}
      speed={obs.speed}
      onSpeedChange={obs.setSpeed}
      onSelectVisit={onSelectVisit}
      nodeLabel={nodeLabel}
      onClose={closeDock}
      edges={runEdges}
      selected={obs.selectedVisit}
      defaultCollapsed={startCollapsed}
      className="rob-dock"
    />
  );
}
