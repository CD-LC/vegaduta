"use client";

// React binding for liveRunStream: follows one run's SSE stream and exposes
// the folded live state. Feature-detecting by construction - `supported`
// turns false on an older core (404/405/...), a refused stream, or exhausted
// retries, and the caller keeps (or resumes) its 1.2s GET .../runs/{runId}
// poll. Nothing here throws into the canvas.
//
// Pass `clientRunId` BEFORE POST /run to watch a sync run from its first
// event; when the POST returns, pass the same id as `runId` (the server uses
// the client id as the run id) and the stream is not reopened.

import { useEffect, useMemo, useReducer, useRef, useState } from "react";
import { auth } from "../../lib/auth";
import { API_BASE } from "../../lib/api/core";
import {
  createLiveRunState,
  openRunStream,
  reduceLiveEvents,
  type LiveRunEvent,
  type LiveRunState,
  type RunStreamOutcome,
} from "./liveRunStream";

export interface UseLiveRunArgs {
  workflowId: string | null | undefined;
  runId: string | null | undefined;
  clientRunId?: string | null;
  /** Set false to keep the hook mounted but idle (e.g. edit mode). */
  enabled?: boolean;
}

export interface UseLiveRunResult {
  state: LiveRunState;
  /** null until the first connect settles; false = poll instead. */
  supported: boolean | null;
  /** The stream is open right now (false while reconnecting). */
  connected: boolean;
  /** How the last stream ended, null while it is still running. */
  outcome: RunStreamOutcome | null;
}

type Action = { kind: "reset"; runId: string | null } | { kind: "batch"; events: LiveRunEvent[] };

function reducer(state: LiveRunState, action: Action): LiveRunState {
  if (action.kind === "reset") return createLiveRunState(action.runId);
  return reduceLiveEvents(state, action.events);
}

// Tokens can arrive dozens per second. Each flush re-renders the whole canvas
// shell (WorkflowCanvas owns this state), so ~10 updates/s: still reads as
// live typing in a 3-line box, at half the render cost of per-frame.
const FLUSH_MS = 100;

export function useLiveRun({ workflowId, runId, clientRunId, enabled = true }: UseLiveRunArgs): UseLiveRunResult {
  const streamId = (runId || clientRunId || null) as string | null;
  const [state, dispatch] = useReducer(reducer, streamId, createLiveRunState);
  const [supported, setSupported] = useState<boolean | null>(null);
  const [connected, setConnected] = useState(false);
  const [outcome, setOutcome] = useState<RunStreamOutcome | null>(null);
  // Bumped per stream: callbacks from a previous run's stream check it and bail.
  const generation = useRef(0);

  useEffect(() => {
    const gen = ++generation.current;
    dispatch({ kind: "reset", runId: streamId });
    setConnected(false);
    setOutcome(null);
    if (!enabled || !workflowId || !streamId) {
      setSupported(null);
      return undefined;
    }
    setSupported(null);
    const ctrl = new AbortController();
    let queue: LiveRunEvent[] = [];
    let timer: ReturnType<typeof setTimeout> | null = null;
    const live = () => gen === generation.current && !ctrl.signal.aborted;
    const flush = () => {
      timer = null;
      if (!live() || !queue.length) return;
      const events = queue;
      queue = [];
      dispatch({ kind: "batch", events });
    };

    void openRunStream({
      workflowId,
      runId: streamId,
      baseUrl: API_BASE,
      // silent: a stream must never raise the "stay signed in?" prompt on its own.
      getToken: () => auth.getToken({ silent: true }),
      signal: ctrl.signal,
      onEvent: (ev) => {
        if (!live()) return;
        queue.push(ev);
        // Pauses, completions and endings land at once; tokens are coalesced.
        if (ev.type !== "node.token" && ev.type !== "node.agent_event") {
          if (timer) clearTimeout(timer);
          flush();
        } else if (!timer) {
          timer = setTimeout(flush, FLUSH_MS);
        }
      },
      onStatus: (s) => {
        if (!live()) return;
        if (s.kind === "open") {
          setConnected(true);
          setSupported(true);
        } else if (s.kind === "reconnecting" || s.kind === "connecting") {
          setConnected(false);
        } else if (s.kind === "unsupported") {
          setSupported(false);
        }
      },
    }).then((result) => {
      if (!live()) return;
      if (timer) clearTimeout(timer);
      flush();
      setConnected(false);
      setOutcome(result);
      if (result === "unsupported" || result === "gave-up" || result === "error") setSupported(false);
    });

    return () => {
      ctrl.abort();
      if (timer) clearTimeout(timer);
      queue = [];
    };
  }, [workflowId, streamId, enabled]);

  // The render between a run change and the reset effect must not show the old run.
  const stale = state.runId !== streamId;
  const shown = useMemo(() => (stale ? createLiveRunState(streamId) : state), [stale, state, streamId]);
  return stale
    ? { state: shown, supported: null, connected: false, outcome: null }
    : { state: shown, supported, connected, outcome };
}
