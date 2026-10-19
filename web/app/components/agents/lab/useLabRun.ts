"use client";

// Agent Lab - the fire-and-poll lifecycle shared by Prompt Knockout and the
// Consistency check. POST /lab/runs answers 202 with a run id, then this hook
// polls GET /lab/runs/{id} until the run is terminal.
//
// The run id and the panel's own `context` (whatever it needs to read the
// results back: section list, item list, ...) are kept in sessionStorage per
// agent + kind, so closing the overlay - which unmounts every panel - or
// reloading the tab picks the same run up again instead of orphaning it
// (core allows one live run per user, so an orphan would 409 the next start
// until it finished). `context` must therefore be JSON-serialisable.
import { useCallback, useEffect, useRef, useState } from "react";
import { labApi, type LabRun, type LabRunKind, type LabRunRequest, type LabRunStatus } from "./labApi";

export type LabRunPhase = "idle" | "starting" | "polling" | "done" | "lost" | "expired" | "error";
export interface UseLabRun<C> {
  run: LabRun | null; context: C | null; phase: LabRunPhase; error: string | null; cancelling: boolean;
  start: (request: LabRunRequest, context: C) => Promise<void>;
  cancel: () => Promise<void>;   // while live or "lost": ask the server to stop the run
  resume: () => void;   // from "lost": poll again
  reset: () => void;    // forget the run and its stored context (from "lost": also asks the server to stop it)
}

const STORAGE_PREFIX = "agentic:lab:run:";
const DEFAULT_POLL_MS = 1500;
/** Consecutive failed polls (other than a 404) before the run counts as lost. */
const MAX_POLL_FAILURES = 5;

const storageKey = (agentId: string, kind: LabRunKind) => `${STORAGE_PREFIX}${agentId}:${kind}`;

function readStored<C>(agentId: string, kind: LabRunKind): { runId: string; context: C } | null {
  try {
    const raw = window.sessionStorage.getItem(storageKey(agentId, kind));
    if (!raw) return null;
    const parsed = JSON.parse(raw) as { runId?: unknown; context?: unknown } | null;
    if (!parsed || typeof parsed.runId !== "string" || !parsed.runId) return null;
    return { runId: parsed.runId, context: (parsed.context ?? null) as C };
  } catch {
    return null;
  }
}

function writeStored(agentId: string, kind: LabRunKind, value: { runId: string; context: unknown }) {
  try {
    window.sessionStorage.setItem(storageKey(agentId, kind), JSON.stringify(value));
  } catch {
    // Best-effort - storage full/disabled only costs the resume-after-reopen.
  }
}

function clearStored(agentId: string, kind: LabRunKind) {
  try {
    window.sessionStorage.removeItem(storageKey(agentId, kind));
  } catch {
    // Best-effort, same as writeStored.
  }
}

function isTerminal(status: LabRunStatus): boolean {
  return status === "SUCCEEDED" || status === "FAILED" || status === "CANCELLED";
}

function statusOf(err: unknown): number | undefined {
  const status = (err as { status?: unknown } | null)?.status;
  return typeof status === "number" ? status : undefined;
}

function messageOf(err: unknown): string {
  const message = (err as { message?: unknown } | null)?.message;
  return typeof message === "string" && message ? message : String(err);
}

export function useLabRun<C>(agentId: string | null, kind: LabRunKind, opts?: { pollMs?: number }): UseLabRun<C> {
  const [run, setRun] = useState<LabRun | null>(null);
  const [context, setContext] = useState<C | null>(null);
  const [phase, setPhaseState] = useState<LabRunPhase>("idle");
  const [error, setError] = useState<string | null>(null);
  const [cancelling, setCancelling] = useState(false);

  // What the async poll loop reads. `epoch` is bumped whenever the current
  // run is abandoned (agent switch, reset, a new start, unmount), so a
  // response that lands afterwards is dropped instead of resurrecting it.
  const epochRef = useRef(0);
  const timerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const runIdRef = useRef<string | null>(null);
  const failuresRef = useRef(0);
  const phaseRef = useRef<LabRunPhase>("idle");
  const pollMsRef = useRef(opts?.pollMs ?? DEFAULT_POLL_MS);
  useEffect(() => {
    pollMsRef.current = opts?.pollMs ?? DEFAULT_POLL_MS;
  }, [opts?.pollMs]);

  const setPhase = useCallback((next: LabRunPhase) => {
    phaseRef.current = next;
    setPhaseState(next);
  }, []);

  const clearTimer = useCallback(() => {
    if (timerRef.current != null) {
      clearTimeout(timerRef.current);
      timerRef.current = null;
    }
  }, []);

  // One poll, then schedule the next. A timeout chain rather than an
  // interval, so a slow response can never overlap the following request.
  const poll = useCallback(
    function pollOnce(targetAgentId: string, runId: string, epoch: number): void {
      const again = () => {
        timerRef.current = setTimeout(() => pollOnce(targetAgentId, runId, epoch), pollMsRef.current);
      };
      labApi.getLabRun(targetAgentId, runId).then(
        (next) => {
          if (epochRef.current !== epoch) return;
          failuresRef.current = 0;
          setRun(next);
          if (isTerminal(next.status)) {
            setCancelling(false);
            setPhase("done");
            return;
          }
          // A restored run may already have a cancel in flight.
          if (next.cancelRequested) setCancelling(true);
          again();
        },
        (err: unknown) => {
          if (epochRef.current !== epoch) return;
          if (statusOf(err) === 404) {
            // Unknown or past its TTL: nothing left to resume, so the stored
            // pointer goes too. The last snapshot (if any) stays on screen.
            clearStored(targetAgentId, kind);
            setCancelling(false);
            setPhase("expired");
            return;
          }
          failuresRef.current += 1;
          if (failuresRef.current >= MAX_POLL_FAILURES) {
            setPhase("lost");
            return;
          }
          again();
        }
      );
    },
    [kind, setPhase]
  );

  // On mount and on every agent switch: drop the previous agent's run from
  // view, then restore this agent's stored run (if any) and resume polling.
  useEffect(() => {
    const epoch = ++epochRef.current;
    clearTimer();
    failuresRef.current = 0;
    setRun(null);
    setError(null);
    setCancelling(false);
    const stored = agentId ? readStored<C>(agentId, kind) : null;
    if (agentId && stored) {
      runIdRef.current = stored.runId;
      setContext(stored.context);
      setPhase("polling");
      poll(agentId, stored.runId, epoch);
    } else {
      runIdRef.current = null;
      setContext(null);
      setPhase("idle");
    }
    return () => {
      epochRef.current += 1;
      clearTimer();
    };
  }, [agentId, kind, poll, clearTimer, setPhase]);

  const resume = useCallback(() => {
    const runId = runIdRef.current;
    if (!agentId || !runId || phaseRef.current !== "lost") return;
    clearTimer();
    failuresRef.current = 0;
    setPhase("polling");
    poll(agentId, runId, epochRef.current);
  }, [agentId, poll, clearTimer, setPhase]);

  const start = useCallback(
    async (request: LabRunRequest, nextContext: C): Promise<void> => {
      if (!agentId) return;
      // A double click must not fire a second POST: core would answer 409 and
      // this hook would then show an error over a run that did start.
      if (phaseRef.current === "starting" || phaseRef.current === "polling") return;
      // "lost" is a run that may well still be going. Starting over it would
      // be refused (one live run per user) and would drop the only pointer to
      // it, so it is looked at again instead.
      if (phaseRef.current === "lost") {
        resume();
        return;
      }
      const epoch = ++epochRef.current;
      clearTimer();
      runIdRef.current = null;
      failuresRef.current = 0;
      setRun(null);
      setContext(nextContext);
      setError(null);
      setCancelling(false);
      setPhase("starting");
      try {
        const started = await labApi.startLabRun(agentId, request);
        // Stored before the epoch check on purpose: if the overlay closed
        // while the POST was in flight the run is still live server-side,
        // and the next mount must be able to find it.
        writeStored(agentId, kind, { runId: started.runId, context: nextContext });
        if (epochRef.current !== epoch) return;
        runIdRef.current = started.runId;
        setRun(started);
        if (isTerminal(started.status)) {
          setPhase("done");
          return;
        }
        setPhase("polling");
        timerRef.current = setTimeout(() => poll(agentId, started.runId, epoch), pollMsRef.current);
      } catch (err) {
        if (epochRef.current !== epoch) return;
        // 409: a run is already live for this user. If it is the one this
        // panel still holds a pointer to, go back to it rather than end on an
        // error whose only action ("Clear results") would forget that run.
        // The POST stored nothing, so the pointer is still the old run's.
        if (statusOf(err) === 409) {
          const stored = readStored<C>(agentId, kind);
          if (stored) {
            let existing: LabRun | null = null;
            try {
              existing = await labApi.getLabRun(agentId, stored.runId);
            } catch {
              existing = null;
            }
            if (epochRef.current !== epoch) return;
            if (existing && !isTerminal(existing.status)) {
              runIdRef.current = stored.runId;
              setContext(stored.context);
              setRun(existing);
              if (existing.cancelRequested) setCancelling(true);
              setPhase("polling");
              timerRef.current = setTimeout(() => poll(agentId, stored.runId, epoch), pollMsRef.current);
              return;
            }
          }
        }
        setError(messageOf(err));
        setPhase("error");
      }
    },
    [agentId, kind, poll, resume, clearTimer, setPhase]
  );

  const cancel = useCallback(async (): Promise<void> => {
    const runId = runIdRef.current;
    if (!agentId || !runId) return;
    const epoch = epochRef.current;
    setCancelling(true);
    try {
      // Polling carries on: the run only counts as stopped once the server
      // reports a terminal status, which is also what clears `cancelling`.
      await labApi.cancelLabRun(agentId, runId);
      // From "lost" nothing is polling, so nothing would ever see that
      // terminal status: the cancel got through, which means the server is
      // reachable again - look at the run.
      if (epochRef.current === epoch && phaseRef.current === "lost") resume();
    } catch {
      if (epochRef.current === epoch) setCancelling(false);
    }
  }, [agentId, resume]);

  const reset = useCallback(() => {
    // Forgetting a run that may still be going ("lost") would leave it
    // spending with nothing pointing at it, and every new start refused until
    // it ended. Ask the server to stop it first; best effort, since the
    // reason it is lost may be that the server cannot be reached.
    const lostRunId = phaseRef.current === "lost" ? runIdRef.current : null;
    if (agentId && lostRunId) {
      void Promise.resolve()
        .then(() => labApi.cancelLabRun(agentId, lostRunId))
        .catch(() => {});
    }
    epochRef.current += 1;
    clearTimer();
    runIdRef.current = null;
    failuresRef.current = 0;
    if (agentId) clearStored(agentId, kind);
    setRun(null);
    setContext(null);
    setError(null);
    setCancelling(false);
    setPhase("idle");
  }, [agentId, kind, clearTimer, setPhase]);

  return { run, context, phase, error, cancelling, start, cancel, resume, reset };
}
