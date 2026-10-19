"use client";

// State owner for the canvas "observe" mode (the flight recorder): which run
// is on the canvas, where the replay playhead sits, which step is being
// inspected, and the lens aggregated over recent runs. WorkflowCanvas only
// feeds it the graph + its own live run and reads display data back, so the
// big canvas component does not grow another thousand lines of run state.

import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { api } from "../../lib/api";
import {
  normalizeRun,
  parseRunStatus,
  isLiveRunStatus,
  type RawWorkflowRun,
  type RunEdge,
  type RunModel,
} from "./runModel";
import {
  buildLensStats,
  latestRuns,
  legend as lensLegend,
  lensEdgeStyle,
  lensNodeStyle,
  EMPTY_LENS_STATS,
  type CanvasLens,
  type LensElementStyle,
  type LensLegend,
  type LensStats,
  type RunsWindow,
} from "./lensStyles";
import { computeRunDisplay, hasTrace, toLensRun, type RunDisplay } from "./runOverlay";
import type { RecorderSpeed } from "./RunFlightRecorder";

export type CanvasMode = "edit" | "observe";
export type VisitRef = { nodeId: string; visitIndex: number };

type FlowNodeLike = { id: string };
type FlowEdgeLike = { id: string; source: string; target: string; sourceHandle?: string | null };

const SEP = "\u0001";
const FIELD = "\u0002";
const LIVE_TICK_MS = 500;
const PARKED_TICK_MS = 5000;
const FOREIGN_POLL_MS = 2000;
const FETCH_CONCURRENCY = 4;
// Runs started elsewhere (schedule, webhook, another tab) reach the flight
// log through this slow list poll while observing; the selected run is
// followed faster by its own detail poll below.
const LIST_POLL_MS = 15000;

/**
 * Graph facts the run model needs (ids, edges, labels), identity-stable
 * across renders that only move/select nodes: the memo is keyed on a
 * signature string and rebuilt FROM that string, so dragging a node does not
 * hand the recorder and linkVisits' cache a "new" graph every frame.
 */
export function useStableGraph(
  nodes: readonly FlowNodeLike[],
  edges: readonly FlowEdgeLike[],
  labelOf: (n: FlowNodeLike) => string,
) {
  const sig = useMemo(
    () =>
      nodes.map((n) => `${n.id}${FIELD}${labelOf(n)}`).join(SEP) +
      "\u0003" +
      edges.map((e) => [e.id, e.source, e.target, e.sourceHandle ?? ""].join(FIELD)).join(SEP),
    [nodes, edges, labelOf],
  );
  return useMemo(() => {
    const [nodePart, edgePart] = sig.split("\u0003");
    const labels = new Map<string, string>();
    const nodeIds: string[] = [];
    for (const row of nodePart ? nodePart.split(SEP) : []) {
      const [id, label] = row.split(FIELD);
      nodeIds.push(id);
      labels.set(id, label ?? "");
    }
    const runEdges: RunEdge[] = (edgePart ? edgePart.split(SEP) : []).map((row) => {
      const [id, source, target, handle] = row.split(FIELD);
      return { id, source, target, sourceHandle: handle || null };
    });
    return { nodeIds, runEdges, labels };
  }, [sig]);
}

function runId(r: RawWorkflowRun | null | undefined): string {
  return r?.id == null ? "" : String(r.id);
}

function traceLength(r: RawWorkflowRun): number {
  const t = r.trace;
  if (Array.isArray(t)) return t.length;
  // A JSON-string trace is only compared, never parsed here: its length grows monotonically too.
  return typeof t === "string" ? t.length : 0;
}

function isTerminal(r: RawWorkflowRun): boolean {
  const s = parseRunStatus(r.status);
  return s !== "unknown" && !isLiveRunStatus(s);
}

export type StampedRun = { run: RawWorkflowRun; at: number };

/**
 * The most advanced of several snapshots of ONE run: a terminal status beats
 * a live one (runs never leave a terminal status), then the longer trace
 * (the engine only appends), then the most recently fetched copy - so a
 * parked run that was resumed elsewhere is not pinned to its paused copy.
 */
export function freshestSnapshot(copies: readonly (StampedRun | null | undefined)[]): RawWorkflowRun | null {
  let best: StampedRun | null = null;
  for (const c of copies) {
    if (!c) continue;
    if (!best) {
      best = c;
      continue;
    }
    const dt = Number(isTerminal(c.run)) - Number(isTerminal(best.run));
    const dl = traceLength(c.run) - traceLength(best.run);
    if (dt > 0 || (dt === 0 && (dl > 0 || (dl === 0 && c.at >= best.at)))) best = c;
  }
  return best?.run ?? null;
}

function startOf(r: RawWorkflowRun): number {
  const v = typeof r.startedAt === "string" ? Date.parse(r.startedAt) : typeof r.startedAt === "number" ? r.startedAt : NaN;
  return Number.isFinite(v) ? v : 0;
}

export interface RunObserverArgs {
  workflowId: string;
  nodeIds: readonly string[];
  runEdges: readonly RunEdge[];
  /** The canvas's own run (runResult) - fresher than any list row while it polls. */
  liveRun: RawWorkflowRun | null;
  runLive: boolean;
  /** Run folded from the SSE stream (useLiveRun), and which run it is. */
  streamRun?: RawWorkflowRun | null;
  streamRunId?: string | null;
  /** The stream is open right now: its copy wins and the detail poll stands down. */
  streaming?: boolean;
}

export function useRunObserver({
  workflowId,
  nodeIds,
  runEdges,
  liveRun,
  runLive,
  streamRun = null,
  streamRunId = null,
  streaming = false,
}: RunObserverArgs) {
  const [mode, setMode] = useState<CanvasMode>("edit");
  const [runs, setRuns] = useState<RawWorkflowRun[]>([]);
  const [runsAt, setRunsAt] = useState(0);
  const [runsLoading, setRunsLoading] = useState(false);
  const [runsLoaded, setRunsLoaded] = useState(false);
  const [details, setDetails] = useState<Record<string, StampedRun>>({});
  const [selectedRunId, setSelectedRunId] = useState<string | null>(null);
  const [compareRunId, setCompareRunId] = useState<string | null>(null);
  // null = pinned to the end (and, for a live run, following the live edge).
  const [position, setPositionState] = useState<number | null>(null);
  const [playing, setPlaying] = useState(false);
  const [speed, setSpeed] = useState<RecorderSpeed>(1);
  const [selectedVisit, setSelectedVisit] = useState<VisitRef | null>(null);
  const [lens, setLens] = useState<CanvasLens>("none");
  const [runsWindow, setRunsWindow] = useState<RunsWindow>(20);
  const [clock, setClock] = useState(() => Date.now());

  // --- run list -------------------------------------------------------------
  const listSeq = useRef(0);
  const runsLoadingRef = useRef(false);
  // quiet = background poll: no spinner, and it yields to a user refresh in flight.
  const loadRuns = useCallback(
    (quiet: boolean) => {
      if (quiet && runsLoadingRef.current) return;
      const seq = ++listSeq.current;
      if (!quiet) {
        runsLoadingRef.current = true;
        setRunsLoading(true);
      }
      api
        .listWorkflowRunsFresh(workflowId)
        .then((list: unknown) => {
          if (seq !== listSeq.current) return;
          setRuns(Array.isArray(list) ? (list as RawWorkflowRun[]) : []);
          setRunsAt(Date.now());
          setRunsLoaded(true);
        })
        .catch(() => {
          if (seq === listSeq.current) setRunsLoaded(true);
        })
        .finally(() => {
          if (seq !== listSeq.current) return;
          runsLoadingRef.current = false;
          setRunsLoading(false);
        });
    },
    [workflowId],
  );
  const refreshRuns = useCallback(() => loadRuns(false), [loadRuns]);

  // Stamp the canvas's own run when it changes, so it competes on fetch time too.
  const [liveAt, setLiveAt] = useState(0);
  useEffect(() => {
    if (liveRun) setLiveAt(Date.now());
  }, [liveRun]);
  const [streamAt, setStreamAt] = useState(0);
  useEffect(() => {
    if (streamRun) setStreamAt(Date.now());
  }, [streamRun]);

  // One row per run, from whichever snapshot is most advanced. While the
  // canvas is polling its own run that copy always wins (it is the freshest
  // by construction), and an OPEN stream wins for its run the same way; once
  // either stops - e.g. parked on an approval that is granted elsewhere -
  // list rows and detail polls can overtake them.
  const allRuns = useMemo(() => {
    const liveId = runId(liveRun);
    const liveStamp: StampedRun | null = liveRun ? { run: liveRun, at: liveAt } : null;
    const streamStamp: StampedRun | null = streamRun && streamRunId ? { run: streamRun, at: streamAt } : null;
    const pick = (id: string, row: StampedRun | null): RawWorkflowRun | null =>
      liveStamp && id === liveId && runLive
        ? liveStamp.run
        : streamStamp && streaming && id === streamRunId
          ? streamStamp.run
          : freshestSnapshot([row, details[id], id === liveId ? liveStamp : null, id === streamRunId ? streamStamp : null]);
    const merged: RawWorkflowRun[] = [];
    const have = new Set<string>();
    for (const r of runs) {
      const id = runId(r);
      const best = pick(id, { run: r, at: runsAt });
      if (best) merged.push(best);
      have.add(id);
    }
    for (const extra of [liveId, streamStamp ? (streamRunId as string) : ""]) {
      if (!extra || have.has(extra)) continue;
      const best = pick(extra, null);
      if (best) merged.push(best);
      have.add(extra);
    }
    // A run fetched on its own (newer than the last list fetch) still shows.
    for (const [id, d] of Object.entries(details)) if (!have.has(id)) merged.push(d.run);
    return merged.sort((a, b) => startOf(b) - startOf(a));
  }, [runs, runsAt, details, liveRun, liveAt, runLive, streamRun, streamRunId, streamAt, streaming]);

  // Entering observe loads the list; a finished live run refreshes it; while
  // observing it is re-read slowly so runs started elsewhere appear.
  useEffect(() => {
    if (mode === "observe" && !runsLoaded && !runsLoading) refreshRuns();
  }, [mode, runsLoaded, runsLoading, refreshRuns]);
  useEffect(() => {
    if (mode !== "observe") return undefined;
    const t = setInterval(() => {
      if (typeof document === "undefined" || !document.hidden) loadRuns(true);
    }, LIST_POLL_MS);
    return () => clearInterval(t);
  }, [mode, loadRuns]);
  const wasLive = useRef(runLive);
  useEffect(() => {
    if (wasLive.current && !runLive && runsLoaded) refreshRuns();
    wasLive.current = runLive;
  }, [runLive, runsLoaded, refreshRuns]);

  // Observe without a chosen run shows the newest one - once the list that
  // entering observe asked for has landed, not the stale one before it.
  useEffect(() => {
    if (mode === "observe" && !selectedRunId && !runsLoading && allRuns.length) setSelectedRunId(runId(allRuns[0]));
  }, [mode, selectedRunId, runsLoading, allRuns]);

  // --- detail fetches (deduped, capped) --------------------------------------
  const inflight = useRef(new Set<string>());
  const fetchDetails = useCallback(
    (ids: string[]) => {
      const queue = ids.filter((id) => id && !inflight.current.has(id));
      if (!queue.length) return;
      queue.forEach((id) => inflight.current.add(id));
      let cursor = 0;
      const worker = async () => {
        while (cursor < queue.length) {
          const id = queue[cursor++];
          try {
            const r = (await api.getWorkflowRun(workflowId, id)) as RawWorkflowRun;
            setDetails((prev) => ({ ...prev, [id]: { run: r, at: Date.now() } }));
          } catch {
            /* a missing detail only leaves that run out of the lens */
          } finally {
            inflight.current.delete(id);
          }
        }
      };
      for (let i = 0; i < Math.min(FETCH_CONCURRENCY, queue.length); i++) void worker();
    },
    [workflowId],
  );

  const liveId = runId(liveRun);
  const selectedRaw = useMemo(
    () => (selectedRunId ? allRuns.find((r) => runId(r) === selectedRunId) ?? null : null),
    [allRuns, selectedRunId],
  );
  // Only while the canvas is itself polling its run does it own the updates;
  // a run it started that is now parked is followed like anyone else's.
  const selectedIsOurs =
    !!selectedRunId && ((selectedRunId === liveId && runLive) || (streaming && selectedRunId === streamRunId));
  const selectedLive = !!selectedRaw && isLiveRunStatus(parseRunStatus(selectedRaw.status));
  const selectedExecuting = selectedLive && parseRunStatus(selectedRaw?.status) === "running";

  // Keyed on booleans, not the row itself: each poll replaces the row, and
  // re-running on that would turn the 2s poll into a back-to-back loop.
  const needsDetail = !selectedRaw || !hasTrace(selectedRaw);
  useEffect(() => {
    if (mode !== "observe" || !selectedRunId || selectedIsOurs) return undefined;
    if (needsDetail) fetchDetails([selectedRunId]);
    if (!selectedLive) return undefined;
    // A run started elsewhere (schedule, webhook, another tab) is followed by polling.
    const t = setInterval(() => fetchDetails([selectedRunId]), selectedExecuting ? FOREIGN_POLL_MS : PARKED_TICK_MS * 2);
    return () => clearInterval(t);
  }, [mode, selectedRunId, selectedIsOurs, needsDetail, selectedLive, selectedExecuting, fetchDetails]);

  // Live clock: only ticks while something on screen is still running. A run
  // parked on a person can wait for days, so it only ticks slowly (the
  // waiting bar still grows) instead of re-rendering the canvas twice a second.
  const executing = runLive || (mode === "observe" && selectedExecuting);
  const ticking = executing || (mode === "observe" && selectedLive);
  useEffect(() => {
    if (!ticking) return undefined;
    const t = setInterval(() => setClock(Date.now()), executing ? LIVE_TICK_MS : PARKED_TICK_MS);
    return () => clearInterval(t);
  }, [ticking, executing]);

  // --- models + frames --------------------------------------------------------
  const liveClock = selectedLive ? clock : 0;
  const model: RunModel | null = useMemo(
    () => (selectedRaw ? normalizeRun(selectedRaw, liveClock ? { nowMs: liveClock } : {}) : null),
    [selectedRaw, liveClock],
  );
  const pos = model ? Math.min(position ?? model.totalMs, model.totalMs) : 0;
  const following = position == null;

  const observeDisplay: RunDisplay | null = useMemo(
    () => (mode === "observe" && model ? computeRunDisplay(model, pos, runEdges, nodeIds) : null),
    [mode, model, pos, runEdges, nodeIds],
  );

  // Edit mode still reflects the canvas's own last run (chips + edges), at its end state.
  const editClock = runLive ? clock : 0;
  const editDisplay: RunDisplay | null = useMemo(() => {
    if (mode === "observe" || !liveRun) return null;
    const m = normalizeRun(liveRun, editClock ? { nowMs: editClock } : {});
    return computeRunDisplay(m, Infinity, runEdges, nodeIds);
  }, [mode, liveRun, editClock, runEdges, nodeIds]);

  // --- lenses -------------------------------------------------------------------
  const windowRuns = useMemo(
    () =>
      latestRuns(
        allRuns.map((raw) => ({ id: runId(raw), status: raw.status ?? null, startedAt: typeof raw.startedAt === "string" ? raw.startedAt : null, raw })),
        runsWindow,
      ).map((r) => r.raw),
    [allRuns, runsWindow],
  );
  useEffect(() => {
    if (mode !== "observe" || lens === "none") return;
    fetchDetails(windowRuns.filter((r) => !hasTrace(r)).map(runId));
  }, [mode, lens, windowRuns, fetchDetails]);

  const lensStats: LensStats = useMemo(() => {
    if (mode !== "observe" || lens === "none") return EMPTY_LENS_STATS;
    const withTrace = windowRuns.filter(hasTrace).map(toLensRun);
    return buildLensStats(withTrace, runEdges, selectedRaw ? toLensRun(selectedRaw) : null);
  }, [mode, lens, windowRuns, runEdges, selectedRaw]);

  const effectiveLens: CanvasLens = mode === "observe" ? lens : "none";
  const lensNodes = useMemo(() => {
    const out: Record<string, LensElementStyle> = {};
    if (effectiveLens === "none") return out;
    for (const id of nodeIds) out[id] = lensNodeStyle(effectiveLens, id, lensStats);
    return out;
  }, [effectiveLens, nodeIds, lensStats]);
  const lensEdges = useMemo(() => {
    const out: Record<string, LensElementStyle> = {};
    if (effectiveLens === "none") return out;
    for (const e of runEdges) out[e.id] = lensEdgeStyle(effectiveLens, e, lensStats);
    return out;
  }, [effectiveLens, runEdges, lensStats]);
  const legend: LensLegend = useMemo(() => lensLegend(lens, lensStats, { nodeIds }), [lens, lensStats, nodeIds]);

  // --- actions --------------------------------------------------------------------
  const setPosition = useCallback(
    (ms: number) => setPositionState(model && ms >= model.totalMs ? null : Math.max(0, ms)),
    [model],
  );

  const selectRun = useCallback((id: string) => {
    setSelectedRunId(id);
    setPositionState(null);
    setPlaying(false);
    setSelectedVisit(null);
    setCompareRunId((c) => (c === id ? null : c));
  }, []);

  // Without an id (the toolbar), observe always opens on the NEWEST run from
  // a freshly read list - not whatever was on screen last time.
  const enterObserve = useCallback(
    (id?: string) => {
      setMode("observe");
      setSelectedRunId(id ?? null);
      setPositionState(null);
      setPlaying(false);
      setSelectedVisit(null);
      if (!id) refreshRuns();
    },
    [refreshRuns],
  );

  const exitObserve = useCallback(() => {
    setMode("edit");
    setPlaying(false);
    setSelectedVisit(null);
  }, []);

  const toggleCompare = useCallback((id: string) => setCompareRunId((c) => (c === id ? null : id)), []);

  /** Re-read the selected run now (e.g. right after an approval was decided on the canvas). */
  const refreshSelected = useCallback(() => {
    if (selectedRunId) fetchDetails([selectedRunId]);
  }, [selectedRunId, fetchDetails]);

  const flightNumbers = useMemo(() => {
    const byAge = [...allRuns].sort((a, b) => startOf(a) - startOf(b));
    const out: Record<string, number> = {};
    byAge.forEach((r, i) => {
      out[runId(r)] = i + 1;
    });
    return out;
  }, [allRuns]);

  return {
    mode,
    enterObserve,
    exitObserve,
    runs: allRuns,
    runsLoading,
    refreshRuns,
    selectedRunId,
    selectRun,
    selectedRaw,
    selectedLive,
    refreshSelected,
    compareRunId,
    toggleCompare,
    model,
    position: pos,
    following,
    setPosition,
    playing,
    setPlaying,
    speed,
    setSpeed,
    selectedVisit,
    setSelectedVisit,
    display: mode === "observe" ? observeDisplay : editDisplay,
    lens,
    setLens,
    runsWindow,
    setRunsWindow,
    lensStats,
    lensNodes,
    lensEdges,
    legend,
    loadedWindowRuns: windowRuns.filter(hasTrace).length,
    flightNumbers,
  };
}

export type RunObserver = ReturnType<typeof useRunObserver>;
