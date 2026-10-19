// Live run stream client for the agent-native canvas: GET
// /api/workflows/{id}/runs/{runId}/events (core WorkflowRunStreamHub) read as
// Server-Sent Events over fetch - EventSource cannot carry the bearer header.
// Framework-free: an incremental SSE parser, a reconnecting client, and a pure
// reducer that folds events into what the canvas draws (streamed text inside a
// node, tool calls as child rows, the approval pause, running cost).
//
// Wire facts this leans on (see WorkflowRunStreamHub's javadoc):
// - every event carries {seq,type,runId,nodeId?,ts,data}; SSE id = seq.
// - `snapshot` has NO seq and NO SSE id: it is the full stored run, sent first
//   on every connect and again by the server's poll fallback on change.
// - reconnecting with Last-Event-ID replays buffered events after it, AFTER a
//   fresh snapshot - so a node.completed can arrive for a visit the snapshot
//   already holds (merged by visit identity, never appended twice).
// - a terminal snapshot or run.finished ends the stream; `error` frames
//   ({type:"error",message}, no data) are followed by the server closing.
// Core older than this endpoint answers 404/405: callers must then fall back
// to polling GET .../runs/{runId}, which is what `unsupported` reports.
//
// The SSE parser and event shape live in liveRunEvents.ts and the reducer in
// liveRunReducer.ts; this module holds the reconnecting client and re-exports
// both so every import path stays stable.

import { applyViewAsHeader } from "../../lib/viewAs";
import { parseLiveEvent, SseParser, type LiveRunEvent, type SseFrame } from "./liveRunEvents";
import { isTerminalStatus } from "./liveRunReducer";

export {
  parseLiveEvent,
  parseSseChunk,
  parseTs,
  SseParser,
  type LiveRunEvent,
  type LiveRunEventType,
  type SseFrame,
} from "./liveRunEvents";
export {
  createLiveRunState,
  LIVE_TEXT_CAP,
  liveRunReducer,
  reduceLiveEvents,
  type LiveAgentEvent,
  type LiveAwaiting,
  type LiveNodeBuffer,
  type LiveRunState,
  type LiveRunTotals,
  type LiveToolCall,
  type LiveToolCallStatus,
} from "./liveRunReducer";

// ---------------------------------------------------------------------------
// Client
// ---------------------------------------------------------------------------

export type RunStreamStatus =
  | { kind: "connecting"; attempt: number }
  | { kind: "open" }
  | { kind: "heartbeat" }
  | { kind: "reconnecting"; attempt: number; delayMs: number }
  | { kind: "unsupported"; unsupported: true; httpStatus: number | null }
  | { kind: "closed"; reason: RunStreamOutcome };

/**
 * How the stream ended. `unsupported` = this core has no stream endpoint;
 * `gave-up` = retries exhausted; `error` = the server refused (403, 400, an
 * `error` frame). Every outcome but `finished`/`aborted` means "poll instead".
 */
export type RunStreamOutcome = "finished" | "aborted" | "unsupported" | "error" | "gave-up";

export interface OpenRunStreamOptions {
  workflowId: string;
  runId: string;
  getToken: () => Promise<string | null>;
  lastEventId?: string | null;
  signal?: AbortSignal;
  onEvent: (event: LiveRunEvent) => void;
  onStatus?: (status: RunStreamStatus) => void;
  baseUrl?: string;
  fetchImpl?: typeof fetch;
  /** Consecutive failed attempts before giving up (default 5). */
  maxRetries?: number;
  /** Total reconnects per run, however healthy each connection looked. */
  maxReconnects?: number;
  backoffMs?: (attempt: number) => number;
  /** No bytes (not even a heartbeat) for this long = dead connection. */
  idleTimeoutMs?: number;
}

const UNSUPPORTED_STATUSES = new Set([404, 405, 406, 501]);
const FATAL_STATUSES = new Set([400, 403]);
const DEFAULT_IDLE_MS = 45_000;

export function defaultBackoffMs(attempt: number): number {
  return Math.min(8000, 500 * 2 ** Math.max(0, attempt - 1));
}

function sleep(ms: number, signal?: AbortSignal): Promise<void> {
  return new Promise((resolve) => {
    if (signal?.aborted) return resolve();
    const t = setTimeout(done, ms);
    function done() {
      clearTimeout(t);
      signal?.removeEventListener("abort", done);
      resolve();
    }
    signal?.addEventListener("abort", done, { once: true });
  });
}

type AttemptResult =
  | { kind: "finished" }
  | { kind: "unsupported"; httpStatus: number | null }
  | { kind: "fatal" }
  | { kind: "retry"; progressed: boolean };

/**
 * Opens the run's event stream and keeps it open until the run finishes, the
 * signal aborts, or retries run out. Never throws; resolves with the outcome.
 */
export async function openRunStream(opts: OpenRunStreamOptions): Promise<RunStreamOutcome> {
  const {
    workflowId,
    runId,
    getToken,
    signal,
    onEvent,
    onStatus,
    baseUrl = "",
    maxRetries = 5,
    maxReconnects = 40,
    backoffMs = defaultBackoffMs,
    idleTimeoutMs = DEFAULT_IDLE_MS,
  } = opts;
  const doFetch = opts.fetchImpl ?? (typeof fetch === "function" ? fetch.bind(globalThis) : null);
  const status = (s: RunStreamStatus) => {
    try {
      onStatus?.(s);
    } catch {
      /* a listener must never break the stream */
    }
  };
  const end = (reason: RunStreamOutcome): RunStreamOutcome => {
    status({ kind: "closed", reason });
    return reason;
  };
  if (!doFetch) {
    status({ kind: "unsupported", unsupported: true, httpStatus: null });
    return end("unsupported");
  }

  const url = `${baseUrl}/api/workflows/${encodeURIComponent(workflowId)}/runs/${encodeURIComponent(runId)}/events`;
  let lastEventId: string | null = opts.lastEventId ?? null;
  let failures = 0;
  let reconnects = 0;

  const attempt = async (): Promise<AttemptResult> => {
    const ctrl = new AbortController();
    const onAbort = () => ctrl.abort();
    signal?.addEventListener("abort", onAbort, { once: true });
    let idle: ReturnType<typeof setTimeout> | null = null;
    const armIdle = () => {
      if (idle) clearTimeout(idle);
      idle = setTimeout(() => ctrl.abort(), idleTimeoutMs);
    };
    let progressed = false;
    let finished = false;
    let fatal = false;
    try {
      let token: string | null = null;
      try {
        token = await getToken();
      } catch {
        token = null;
      }
      const headers: Record<string, string> = { Accept: "text/event-stream" };
      if (token) headers.Authorization = `Bearer ${token}`;
      applyViewAsHeader(headers);
      if (lastEventId != null) headers["Last-Event-ID"] = lastEventId;
      armIdle();
      const res = await doFetch(url, { method: "GET", headers, cache: "no-store", signal: ctrl.signal });
      if (UNSUPPORTED_STATUSES.has(res.status)) return { kind: "unsupported", httpStatus: res.status };
      if (FATAL_STATUSES.has(res.status)) return { kind: "fatal" };
      if (!res.ok) return { kind: "retry", progressed: false };
      const ctype = res.headers.get("content-type") ?? "";
      // An old core's SPA/HTML fallback or a JSON error body: not our stream.
      if (!ctype.toLowerCase().includes("text/event-stream")) return { kind: "unsupported", httpStatus: res.status };
      status({ kind: "open" });

      const parser = new SseParser(lastEventId);
      const handle = (frames: SseFrame[], commentsBefore: number) => {
        if (parser.commentCount > commentsBefore) status({ kind: "heartbeat" });
        for (const f of frames) {
          const ev = parseLiveEvent(f);
          if (!ev) continue;
          if (f.id != null) lastEventId = f.id;
          // Core sends a snapshot on EVERY connect, so it proves nothing about
          // the connection's health; only live events reset the failure count.
          if (ev.type !== "snapshot") progressed = true;
          if (ev.type === "run.finished") finished = true;
          else if (ev.type === "snapshot" && isTerminalStatus(ev.data.status)) finished = true;
          else if (ev.type === "error") fatal = true;
          try {
            onEvent(ev);
          } catch {
            /* consumer bugs must not kill the stream */
          }
        }
      };

      const body = res.body;
      if (!body || typeof body.getReader !== "function") {
        const text = await res.text();
        handle(parser.push(text), 0);
      } else {
        const reader = body.getReader();
        const decoder = new TextDecoder();
        try {
          for (;;) {
            const { done, value } = await reader.read();
            if (done) break;
            armIdle();
            const before = parser.commentCount;
            handle(parser.push(decoder.decode(value, { stream: true })), before);
            if (finished || fatal) break;
          }
          const tail = decoder.decode();
          if (tail) handle(parser.push(tail), parser.commentCount);
        } finally {
          try {
            await reader.cancel();
          } catch {
            /* already closed */
          }
        }
      }
      if (fatal) return { kind: "fatal" };
      if (finished) return { kind: "finished" };
      return { kind: "retry", progressed };
    } catch {
      if (fatal) return { kind: "fatal" };
      if (finished) return { kind: "finished" };
      return { kind: "retry", progressed };
    } finally {
      if (idle) clearTimeout(idle);
      signal?.removeEventListener("abort", onAbort);
    }
  };

  for (;;) {
    if (signal?.aborted) return end("aborted");
    status({ kind: "connecting", attempt: failures + 1 });
    const r = await attempt();
    if (signal?.aborted) return end("aborted");
    if (r.kind === "finished") return end("finished");
    if (r.kind === "fatal") return end("error");
    if (r.kind === "unsupported") {
      status({ kind: "unsupported", unsupported: true, httpStatus: r.httpStatus });
      return end("unsupported");
    }
    // A connection that delivered events was healthy: only back-to-back
    // failures count toward giving up (the server closes idle emitters at 30 min).
    failures = r.progressed ? 1 : failures + 1;
    reconnects += 1;
    // The total cap bounds a proxy that cuts every long response after a few
    // live events: each reconnect costs core a DB read of the run.
    if (failures > maxRetries || reconnects > maxReconnects) return end("gave-up");
    const delayMs = backoffMs(failures);
    status({ kind: "reconnecting", attempt: failures, delayMs });
    await sleep(delayMs, signal);
  }
}
