// Edge relay (2026-10-14): the loop that makes THIS browser the model behind
// the tenant's SMS / WhatsApp numbers. Registers with core, long-polls for
// inbound messages, answers each one with the local tier ladder (routeTurn)
// and hands the text back; core's inbound thread then sends it through the
// approval gate. Nothing here sends to a channel and nothing here needs a
// provider key.
//
// Dependencies are injectable (api, runTurn, loadAgents, sleep) so the loop is
// unit-testable without a browser or a model; the console passes the real
// ones via defaultRelayDeps().

import type { AgentResponse } from "../api/types";
import { edgeRelayApi, type RelayChannel, type RelayJob } from "../api/edgeRelay";
import { api } from "../api";
import { routeTurn, type RouteTurnOptions, type RouteTurnResult } from "./tierRouter";

export type RelayStatus = "off" | "starting" | "online" | "stopped" | "error";

export interface RelayState {
  status: RelayStatus;
  relayId: string | null;
  channels: RelayChannel[];
  answered: number;
  failed: number;
  lastError: string | null;
  lastJobAt: number | null;
}

export interface RelayDeps {
  api: Pick<
    typeof edgeRelayApi,
    | "registerEdgeRelay"
    | "heartbeatEdgeRelay"
    | "unregisterEdgeRelay"
    | "nextEdgeRelayJob"
    | "replyEdgeRelayJob"
    | "failEdgeRelayJob"
  >;
  runTurn: (input: string, options: RouteTurnOptions) => Promise<RouteTurnResult>;
  loadAgents: () => Promise<AgentResponse[]>;
  sleep: (ms: number) => Promise<void>;
  /** Long-poll length asked of the server (seconds); the server clamps it. */
  pollSeconds?: number;
}

export interface RelayHandle {
  stop: () => Promise<void>;
}

const INITIAL: RelayState = {
  status: "off",
  relayId: null,
  channels: [],
  answered: 0,
  failed: 0,
  lastError: null,
  lastJobAt: null,
};

/** Fallthrough reasons that mean "this device has no model to answer with" - after a few of
 *  these in a row the relay stops itself instead of bouncing every message back to hosted. */
const NO_MODEL_REASONS = new Set(["no-local-tier", "edge-disabled", "awaiting-user-choice", "user-hosted-override"]);
const MAX_CONSECUTIVE_NO_MODEL = 3;
const MAX_CONSECUTIVE_TRANSPORT_ERRORS = 5;

let state: RelayState = INITIAL;
const listeners = new Set<(s: RelayState) => void>();
let current: RelayHandle | null = null;

export function getEdgeRelayState(): RelayState {
  return state;
}

export function subscribeEdgeRelayState(listener: (s: RelayState) => void): () => void {
  listeners.add(listener);
  return () => {
    listeners.delete(listener);
  };
}

function setState(patch: Partial<RelayState>) {
  state = { ...state, ...patch };
  listeners.forEach((l) => {
    try {
      l(state);
    } catch {
      // a listener must never break the loop
    }
  });
}

export function defaultRelayDeps(): RelayDeps {
  return {
    api: edgeRelayApi,
    runTurn: routeTurn,
    loadAgents: () => api.listAgents(),
    sleep: (ms) => new Promise((resolve) => setTimeout(resolve, ms)),
    pollSeconds: 20,
  };
}

/** The text handed to the local model for a job: the message, plus the server-side history the
 *  first time this browser sees the session (local sessions are append-only per sessionKey, so
 *  later jobs of the same session only need the new message). */
export function buildRelayInput(job: RelayJob, firstTimeForSession: boolean): string {
  if (!firstTimeForSession || !job.history || job.history.length === 0) return job.message;
  const lines = job.history.map((t) => `${t.role === "assistant" ? "Assistant" : "User"}: ${t.text}`);
  return `Earlier in this conversation:\n${lines.join("\n")}\n\nNew message from the user:\n${job.message}`;
}

/** Start answering `channels` from this browser. Stops any relay already running here. */
export function startEdgeRelay(
  channels: RelayChannel[],
  deps: RelayDeps = defaultRelayDeps(),
  labels: { modelLabel?: string | null; deviceLabel?: string | null } = {},
): RelayHandle {
  if (current) void current.stop();
  let stopped = false;
  let relayId: string | null = null;
  let heartbeatTimer: ReturnType<typeof setInterval> | null = null;
  const seenSessions = new Set<string>();
  let agents: AgentResponse[] | null = null;
  let consecutiveNoModel = 0;
  let consecutiveErrors = 0;

  setState({ ...INITIAL, status: "starting", channels: [...channels] });

  async function agentFor(agentId: string): Promise<AgentResponse | null> {
    if (!agents || !agents.some((a) => a.id === agentId)) {
      try {
        agents = await deps.loadAgents();
      } catch {
        agents = agents ?? [];
      }
    }
    return agents.find((a) => a.id === agentId) ?? null;
  }

  async function register(): Promise<boolean> {
    try {
      const reg = await deps.api.registerEdgeRelay(channels, labels.modelLabel ?? null, labels.deviceLabel ?? null);
      relayId = reg.relayId;
      if (heartbeatTimer) clearInterval(heartbeatTimer);
      heartbeatTimer = setInterval(() => {
        if (!relayId || stopped) return;
        deps.api.heartbeatEdgeRelay(relayId).catch(async (err: { status?: number }) => {
          // 404: the server pruned us (missed beats) - register again on the next loop turn.
          if (err?.status === 404) relayId = null;
        });
      }, Math.max(5, reg.heartbeatSeconds) * 1000);
      setState({ status: "online", relayId, lastError: null });
      return true;
    } catch (err) {
      setState({ status: "error", lastError: (err as Error)?.message || "register failed" });
      return false;
    }
  }

  async function handle(job: RelayJob) {
    const rid = relayId;
    if (!rid) return;
    const sessionKey = `relay:${job.sessionId}`;
    const first = !seenSessions.has(sessionKey);
    seenSessions.add(sessionKey);
    const agent = await agentFor(job.agentId);
    const systemPrompt = agent?.systemPrompt || (agent as { instructions?: string } | null)?.instructions || undefined;
    let result: RouteTurnResult;
    try {
      result = await deps.runTurn(buildRelayInput(job, first), {
        sessionKey,
        agentId: job.agentId,
        systemPrompt,
      });
    } catch (err) {
      await deps.api.failEdgeRelayJob(job.jobId, rid, (err as Error)?.message || "local turn failed").catch(() => {});
      setState({ failed: state.failed + 1, lastJobAt: Date.now() });
      return;
    }
    if (result.handled === true && result.text && result.text.trim()) {
      consecutiveNoModel = 0;
      await deps.api.replyEdgeRelayJob(job.jobId, rid, result.text.trim());
      setState({ answered: state.answered + 1, lastJobAt: Date.now(), lastError: null });
      return;
    }
    const reason = result.handled === true ? "empty-reply" : result.reason;
    await deps.api.failEdgeRelayJob(job.jobId, rid, reason).catch(() => {});
    setState({ failed: state.failed + 1, lastJobAt: Date.now() });
    if (NO_MODEL_REASONS.has(reason)) {
      consecutiveNoModel += 1;
      if (consecutiveNoModel >= MAX_CONSECUTIVE_NO_MODEL) {
        setState({ lastError: "no-local-model" });
        await stop("error");
      }
    } else {
      consecutiveNoModel = 0;
    }
  }

  async function loop() {
    if (!(await register())) return;
    while (!stopped) {
      if (!relayId) {
        if (!(await register())) return;
        continue;
      }
      try {
        const job = await deps.api.nextEdgeRelayJob(relayId, deps.pollSeconds ?? 20);
        consecutiveErrors = 0;
        if (stopped) break;
        if (job) await handle(job);
      } catch (err) {
        if (stopped) break;
        const status = (err as { status?: number })?.status;
        if (status === 404) {
          relayId = null; // pruned: re-register
          continue;
        }
        consecutiveErrors += 1;
        setState({ lastError: (err as Error)?.message || "poll failed" });
        if (consecutiveErrors >= MAX_CONSECUTIVE_TRANSPORT_ERRORS) {
          await stop("error");
          return;
        }
        await deps.sleep(Math.min(10_000, 1_000 * consecutiveErrors));
      }
    }
  }

  async function stop(finalStatus: RelayStatus = "stopped") {
    if (stopped) return;
    stopped = true;
    if (heartbeatTimer) clearInterval(heartbeatTimer);
    heartbeatTimer = null;
    const rid = relayId;
    relayId = null;
    if (rid) await deps.api.unregisterEdgeRelay(rid).catch(() => {});
    if (current === handle_) current = null;
    setState({ status: finalStatus, relayId: null });
  }

  const handle_: RelayHandle = { stop: () => stop("stopped") };
  current = handle_;
  void loop();
  return handle_;
}

export async function stopEdgeRelay(): Promise<void> {
  if (current) await current.stop();
}
