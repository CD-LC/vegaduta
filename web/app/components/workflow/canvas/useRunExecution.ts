"use client";

import { useCallback, useEffect, useRef, useState, type Dispatch, type MutableRefObject, type SetStateAction } from "react";
import { api } from "../../../lib/api";
import { errorMessage } from "../../../lib/errors";
import { randomUuid } from "../../../lib/randomId";
import { toast } from "../../../lib/toastBus";
import type { WorkflowNodeRunTrace } from "../WorkflowNode";
import { startCanvasRun } from "../canvasRun";
import { finiteNumber, sameTrace, validationErrorsOf } from "../canvasHelpers";
import { freshestSnapshot } from "../useRunObserver";
import { DRY_RUN_MODE } from "../workflowPlan";
import type { RawWorkflowRun } from "../runModel";

type T = (key: string, values?: Record<string, unknown>) => string;

/**
 * Starting, watching, stopping, pausing and resuming a run from the canvas.
 * A single poll-to-terminal loop exists at a time (claimWatch / releaseWatch);
 * an open live stream replaces GET polling whenever it is available.
 */
export function useRunExecution(opts: {
  workflowId: any;
  t: T;
  tDescribe: T;
  runResult: any;
  setRunResult: (r: any) => void;
  runLive: boolean;
  setRunLive: (v: boolean) => void;
  setBusy: (b: boolean) => void;
  setError: (e: any) => void;
  setValidationErrors: (e: string[] | null) => void;
  setInvalidNodeIds: (s: ReadonlySet<string>) => void;
  persistDraftUnlessClean: () => Promise<unknown>;
  // run modal
  simulateSends: boolean;
  buildRunInputPayload: () => string;
  closeRunModal: () => void;
  // inspector panel
  setExpandedTrace: Dispatch<SetStateAction<Record<number, boolean>>>;
  setInspectorTab: (tab: string) => void;
  setRunHistory: (h: null) => void;
  // observation / stream
  ownRunIdRef: MutableRefObject<string | null>;
  streamRef: MutableRefObject<{ runId: string | null; open: boolean; finished: boolean; run: RawWorkflowRun | null }>;
  streamTarget: string | null;
  setStreamTarget: (id: string | null) => void;
  streamOpen: boolean;
  liveRunObj: any;
  enterObserve: (id?: string) => void;
  refreshSelected: () => void;
  obsRunId: string | null | undefined;
}) {
  const {
    workflowId,
    t,
    tDescribe,
    runResult,
    setRunResult,
    runLive,
    setRunLive,
    setBusy,
    setError,
    setValidationErrors,
    setInvalidNodeIds,
    persistDraftUnlessClean,
    simulateSends,
    buildRunInputPayload,
    closeRunModal,
    setExpandedTrace,
    setInspectorTab,
    setRunHistory,
    ownRunIdRef,
    streamRef,
    streamTarget,
    setStreamTarget,
    streamOpen,
    liveRunObj,
    enterObserve,
    refreshSelected,
    obsRunId,
  } = opts;

  // Per-node run status keyed by node id - the client-side merge source for
  // each node card's trace overlay.
  const [trace, setTrace] = useState<Record<string, WorkflowNodeRunTrace>>({});
  // True only while the cancel / pause / resume request itself is in flight,
  // not for the (possibly longer) time the run takes to observe the signal.
  const [cancelBusy, setCancelBusy] = useState(false);
  const [pauseBusy, setPauseBusy] = useState(false);

  // Shared by the live poll tick and the final result, so the trace-by-node
  // map is built the same way in both places.
  function applyRunResult(result: any) {
    setRunResult(result);
    const incoming: Record<string, WorkflowNodeRunTrace> = {};
    for (const entry of result.trace || []) {
      incoming[entry.nodeId] = {
        status: entry.status,
        summary: entry.summary,
        // Metrics are optional on the wire; the node footer chips only
        // render for values that are actually present.
        durationMs: finiteNumber(entry.durationMs),
        tokens: finiteNumber(entry.tokens ?? entry.totalTokens),
        costUsd: finiteNumber(entry.costUsd ?? entry.cost),
      };
    }
    // Reuse the previous entry when nothing about it changed, so a 1.2s poll
    // that only advanced one node re-renders that node, not the whole graph.
    setTrace((prev) => {
      const byNode: Record<string, WorkflowNodeRunTrace> = {};
      let changed = Object.keys(prev).length !== Object.keys(incoming).length;
      for (const id of Object.keys(incoming)) {
        if (sameTrace(prev[id], incoming[id])) byNode[id] = prev[id];
        else {
          byNode[id] = incoming[id];
          changed = true;
        }
      }
      return changed ? byNode : prev;
    });
  }

  // The stream's run replaces polling results for the run this canvas owns
  // (runResult feeds edit-mode chips, the Stop/Pause inspector and the
  // observer's liveRun), including after a parked run resumes.
  useEffect(() => {
    if (!liveRunObj || !streamTarget || streamTarget !== ownRunIdRef.current) return;
    applyRunResult(liveRunObj);
    // eslint-disable-next-line react-hooks/exhaustive-deps -- applyRunResult only sets state
  }, [liveRunObj]);

  // One poll loop at a time: every path that (re)attaches to a run claims this.
  // The token lets a newer loop (a fresh Run) supersede an older one without
  // the old loop's late results or `finally` clobbering the new run's state.
  const watchingRef = useRef(false);
  const watchTokenRef = useRef(0);
  function claimWatch(): number {
    watchingRef.current = true;
    watchTokenRef.current += 1;
    return watchTokenRef.current;
  }
  function releaseWatch(token: number): boolean {
    if (watchTokenRef.current !== token) return false;
    watchingRef.current = false;
    setBusy(false);
    setRunLive(false);
    return true;
  }

  // Shared 1200ms poll-to-terminal loop; `result` is the latest snapshot the
  // caller already has and this returns the last polled snapshot. PAUSED and
  // the AWAITING_* statuses count as terminal alongside AWAITING_APPROVAL:
  // they are "parked on a human" states with their own resume path. The
  // 30-minute ceiling is a UI safety valve, not a run limit.
  // While the SSE stream for this run is open (or has delivered its end) each
  // tick reads the stream's copy instead of GETting; the GET poll resumes on
  // its own when the stream is absent, refused or reconnecting.
  // `graceMs`: right after a canvas decision the stream may still say
  // AWAITING_* for a moment; those states do not end the loop until then.
  // `token`: the watch this loop belongs to. A superseded loop stops at its
  // next tick and never applies results or toasts again.
  async function pollRunToTerminal(runId: any, result: any, graceMs = 0, token = watchTokenRef.current) {
    const TERMINAL = [
      "COMPLETED",
      "FAILED",
      "CANCELLED",
      "AWAITING_APPROVAL",
      "PAUSED",
      "AWAITING_INPUT",
      "AWAITING_VOICE_INPUT",
    ];
    const HARD_TERMINAL = ["COMPLETED", "FAILED", "CANCELLED"];
    const deadline = Date.now() + 30 * 60_000;
    const graceUntil = Date.now() + graceMs;
    const current = () => watchTokenRef.current === token;
    let consecutiveErrors = 0;
    let fromStream = result === streamRef.current.run;
    const settled = (s: unknown) =>
      typeof s === "string" && TERMINAL.includes(s) && (HARD_TERMINAL.includes(s) || Date.now() >= graceUntil);
    while (!settled(result.status) && Date.now() < deadline) {
      await new Promise((resolve) => setTimeout(resolve, 1200));
      if (!current()) return result;
      const s = streamRef.current;
      if (s.runId != null && s.runId === String(runId) && s.run && (s.open || s.finished)) {
        result = s.run;
        fromStream = true;
        consecutiveErrors = 0;
        continue;
      }
      try {
        const polled = await api.getWorkflowRun(workflowId, runId);
        if (!current()) return result;
        result = polled;
        fromStream = false;
        applyRunResult(result);
        consecutiveErrors = 0;
      } catch (pollErr) {
        // One flaky poll (network blip, transient 5xx) is not a failed
        // run - the row is durable server-side. Only give up after a
        // sustained streak.
        consecutiveErrors += 1;
        if (consecutiveErrors >= 5) throw pollErr;
      }
    }
    if (!current()) return result;
    // The stream's run.finished carries only {status, error}: one GET brings
    // back the server-only fields (outputFileName, outputData, version...).
    if (fromStream && HARD_TERMINAL.includes(result.status)) {
      try {
        const full = await api.getWorkflowRun(workflowId, runId);
        if (!current()) return result;
        // A row not yet persisted as terminal must not undo the stream's end.
        if (full && HARD_TERMINAL.includes(full.status)) {
          result = full;
          applyRunResult(full);
        }
      } catch {
        /* the stream's copy stands; the runs list still has the full row */
      }
    }
    if (result.status === "FAILED") {
      toast.error(t("runFailedError", { reason: result.errorMessage || t("runFailedReasonFallback") }));
    } else if (result.status === "AWAITING_APPROVAL") {
      toast.info(t("runAwaitingApproval"));
    } else if (result.status === "CANCELLED") {
      // Stop Run sends the signal in a separate request (see stopRun());
      // this is the poll observing that the engine actually acted on it.
      toast.info(t("runCancelledSuccess"));
    } else if (
      result.status === "PAUSED" ||
      result.status === "AWAITING_INPUT" ||
      result.status === "AWAITING_VOICE_INPUT"
    ) {
      // Parked-on-a-human states: PAUSED was already toasted by pauseRun() and
      // the awaiting-input pair surfaces through the input requests flow; the
      // branch only keeps them out of the "Run completed." fallthrough below.
    } else if (result.status === "RUNNING") {
      // Deadline elapsed while still running - leave it running server-side.
      toast.info(t("runStillRunning"));
    } else {
      toast.success(t("runCompletedSuccess"));
    }
    return result;
  }

  // Re-attach to a run this canvas owns after it leaves a parked state (a
  // decision on the canvas card, or granted elsewhere and seen on the stream).
  // `busy` keeps Run disabled so a second run cannot race this loop.
  function resumeWatching(runId: string, graceMs: number) {
    if (watchingRef.current) return;
    const token = claimWatch();
    setBusy(true);
    setRunLive(true);
    pollRunToTerminal(runId, { ...(runResult ?? {}), id: runId, status: "RUNNING" }, graceMs, token)
      .catch((err) => {
        if (watchTokenRef.current === token) toast.error(errorMessage(err, t("runError")));
      })
      .finally(() => releaseWatch(token));
  }

  // Own run resumed while nothing was watching it (approved in another tab):
  // the stream shows RUNNING again, so Stop/elapsed/outcome toasts come back.
  // Only a parked -> RUNNING transition counts; otherwise the 30-minute poll
  // valve ending on a still-RUNNING run would restart itself forever.
  const liveRunStatus = liveRunObj ? String(liveRunObj.status ?? "").toUpperCase() : "";
  const prevLiveRunStatusRef = useRef("");
  useEffect(() => {
    const prev = prevLiveRunStatusRef.current;
    prevLiveRunStatusRef.current = liveRunStatus;
    if (runLive || !streamOpen || !streamTarget || streamTarget !== ownRunIdRef.current) return;
    const wasParked = prev === "PAUSED" || prev.startsWith("AWAITING_");
    if (wasParked && liveRunStatus === "RUNNING") resumeWatching(streamTarget, 0);
    // eslint-disable-next-line react-hooks/exhaustive-deps -- resumeWatching reads fresh state via refs
  }, [liveRunStatus, runLive, streamOpen, streamTarget]);

  async function runWithRuntimeInputs() {
    const token = claimWatch();
    setBusy(true);
    setRunLive(true);
    setError(null);
    setValidationErrors(null);
    setInvalidNodeIds(new Set());
    let clientRunId = "";
    let started = false;
    // Modal closes as soon as the run is actually dispatched, not once it
    // finishes - the point of live polling is watching it happen in the run
    // inspector panel, not staring at a spinner in a modal.
    closeRunModal();
    setExpandedTrace({});
    setInspectorTab("steps");
    setRunHistory(null); // this run isn't in the cached history list yet

    try {
      // A client-generated id (real-time log viewer, #393) - kept even though
      // background mode returns the run id anyway, so the inspector's identity
      // for this run never depends on the POST having resolved.
      // randomUuid works on plain-http origins, where crypto.randomUUID does not exist (SE-02).
      clientRunId = randomUuid();
      // Live stream contract: subscribe with the client id BEFORE POST /run so
      // the very first node's tokens are not missed (core accepts a run id that
      // has no row yet and starts sending once it does).
      ownRunIdRef.current = clientRunId;
      setStreamTarget(clientRunId);
      // Saves the draft first; the run modal's footer hint + the topbar tooltip disclose it.
      await persistDraftUnlessClean();
      // background: true - the POST returns the freshly persisted RUNNING row
      // immediately and the traversal continues server-side (one blocking
      // request used to be killed by Cloudflare with a 524 at ~100s while the
      // run kept executing).
      // mode "draft": the engine honours data pins for canvas runs only; every other entry point runs production.
      const result = await startCanvasRun(
        api.runWorkflow,
        workflowId,
        buildRunInputPayload(),
        clientRunId,
        simulateSends
      );
      started = true;
      // A core that does not know simulateSends ignores it and runs an ordinary draft run with REAL sends.
      // The button is only offered when the server looked current (plan.testRunSupported), so this is the
      // backstop: say so loudly instead of letting the "simulated" wording stand.
      if (simulateSends && result?.mode !== DRY_RUN_MODE) toast.error(tDescribe("planTestRunNotSimulated"));
      const runKey = String(result?.id ?? clientRunId);
      if (runKey !== clientRunId) {
        // Defensive: core has always adopted the client id; follow the real one if not.
        ownRunIdRef.current = runKey;
        setStreamTarget(runKey);
      }
      // The stream may already be ahead of this (RUNNING, empty-trace) response.
      const s = streamRef.current;
      const best =
        freshestSnapshot([{ run: result, at: 0 }, s.runId === runKey && s.run ? { run: s.run, at: 1 } : null]) ??
        result;
      applyRunResult(best);
      // The canvas becomes the flight recorder for this run until "Back to editing".
      enterObserve(runKey);
      // A throw here is a sustained poll failure or a rejected /run POST,
      // both handled by the catch below.
      await pollRunToTerminal(runKey, best, 0, token);
    } catch (err) {
      if (watchTokenRef.current !== token) return;
      if (!started && clientRunId && ownRunIdRef.current === clientRunId) {
        // No run exists: drop the pre-opened stream.
        ownRunIdRef.current = null;
        setStreamTarget(null);
      }
      const rejected = validationErrorsOf(err);
      if (rejected) {
        setValidationErrors(rejected);
      } else {
        toast.error(errorMessage(err, t("runError")));
      }
    } finally {
      releaseWatch(token);
    }
  }

  // Stop Run - sends the cancellation signal and applies whatever status the
  // backend hands back (see WorkflowService#cancelRun: it returns as soon as
  // the signal is sent, so the response may still read RUNNING). The attached
  // poll picks up the eventual CANCELLED on its own - this "asks", not "waits".
  async function stopRun() {
    if (!runResult) return;
    setCancelBusy(true);
    try {
      const updated = await api.cancelWorkflowRun(workflowId, runResult.id);
      applyRunResult(updated);
      if (updated.status === "CANCELLED") {
        setRunLive(false);
        toast.success(t("runCancelledSuccess"));
      } else if (updated.status === "RUNNING") {
        toast.info(t("runCancelRequestedHint"));
      } else {
        // Already finished by the time this reached the server - a no-op, not an error.
        setRunLive(false);
        toast.info(t("runCancelAlreadyFinished"));
      }
    } catch (err) {
      toast.error(errorMessage(err, t("runCancelError")));
    } finally {
      setCancelBusy(false);
    }
  }

  // Pause Run - same "ask, don't wait" contract as stopRun: the response may
  // still read RUNNING because the park is only observed between node
  // executions server-side. The attached poll exits on PAUSED and stays silent
  // so this function's own success toast isn't doubled.
  async function pauseRun() {
    if (!runResult) return;
    setPauseBusy(true);
    try {
      const updated = await api.pauseWorkflowRun(workflowId, runResult.id);
      applyRunResult(updated);
      if (updated.status === "PAUSED") {
        setRunLive(false);
        toast.success(t("runPausedSuccess"));
      } else {
        // Still RUNNING: the engine hasn't reached a node boundary yet; a
        // terminal status: it finished first. Either way a no-op, not an error.
        if (updated.status !== "RUNNING") setRunLive(false);
        toast.info(t("runPauseNoop"));
      }
    } catch (err) {
      toast.error(errorMessage(err, t("runPauseError")));
    } finally {
      setPauseBusy(false);
    }
  }

  // Resume Run - a PAUSED run flips back to RUNNING and continues from the
  // node it parked on. After the accepted signal the shared poll is re-attached
  // fire-and-forget so the inspector goes live again.
  async function resumeRun() {
    if (!runResult) return;
    setPauseBusy(true);
    try {
      const updated = await api.resumeWorkflowRun(workflowId, runResult.id);
      applyRunResult(updated);
      if (updated.status === "RUNNING") {
        setRunLive(true);
        ownRunIdRef.current = String(updated.id);
        setStreamTarget(String(updated.id));
        enterObserve(String(updated.id));
        toast.success(t("runResumedSuccess"));
        if (!watchingRef.current) {
          const token = claimWatch();
          // Run stays disabled while this loop watches (see resumeWatching).
          setBusy(true);
          pollRunToTerminal(String(updated.id), updated, 0, token)
            .catch((err) => {
              // Sustained poll failure (the run row is durable server-side).
              if (watchTokenRef.current === token) toast.error(errorMessage(err, t("runError")));
            })
            .finally(() => releaseWatch(token));
        }
      } else {
        // Wasn't paused anymore by the time this landed - a no-op, not an error.
        setRunLive(false);
        toast.info(t("runResumeNoop"));
      }
    } catch (err) {
      toast.error(errorMessage(err, t("runResumeError")));
    } finally {
      setPauseBusy(false);
    }
  }

  const onAwaitingResolved = useCallback(() => {
    refreshSelected();
    const id = obsRunId;
    // A run this canvas owns goes back under the poll/stream loop; a grace
    // window keeps a still-AWAITING stream copy from ending it at once.
    if (id && id === ownRunIdRef.current) resumeWatching(id, 5000);
    // eslint-disable-next-line react-hooks/exhaustive-deps -- resumeWatching reads fresh state via refs
  }, [refreshSelected, obsRunId]);

  return {
    trace,
    cancelBusy,
    pauseBusy,
    runWithRuntimeInputs,
    stopRun,
    pauseRun,
    resumeRun,
    onAwaitingResolved,
  };
}
