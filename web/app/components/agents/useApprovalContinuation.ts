"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { assistantApi } from "../../lib/api/assistant";
import {
  CONTINUATION_MAX_WAIT_MS,
  CONTINUATION_POLL_MS,
  CONTINUATION_START_WAIT_MS,
  locateContinuation,
  type StoredMessage,
} from "./approvalContinuation";

/** The agent's reply to a continuation, as the surface should append it to its transcript. */
export interface ApprovalContinuationReply {
  requestId: string;
  content: string;
  /** The turn broke; `content` is the plain-language reason (render it as a failed reply). */
  failed: boolean;
}

/** Why nothing is being waited for any more, when no reply arrived. */
export type ContinuationProblem =
  /** The task was not restarted by the platform; the person can tell the agent to carry on. */
  | "notStarted"
  /** The continuation started but no reply was stored in time. */
  | "timedOut";

/** How the last followed continuation ended: the agent replied, or its turn broke (the reply is the reason). */
export type ContinuationOutcome = "completed" | "failed";

/**
 * Where a running continuation is, as far as the browser can see. Core runs the turn detached and streams it to
 * nobody, so this is what the stored conversation tells: "waiting" until core's continuation message appears (the
 * agent is picking the task back up), "running" once it has (the agent is working on it).
 */
export type ContinuationPhase = "waiting" | "running";

export interface ApprovalContinuationWatch {
  /** A continuation is expected or running: the surface keeps its input off and shows the status line. */
  continuing: boolean;
  /** While continuing: when the newest followed continuation began (epoch ms), for an elapsed timer. */
  since?: number | null;
  /** While continuing: what the stored conversation shows of the newest followed continuation. */
  phase?: ContinuationPhase | null;
  problem: ContinuationProblem | null;
  /** How the last continuation ended; cleared by `dismiss`, by `retry` and when the next one starts. */
  outcome: ContinuationOutcome | null;
  /** Present only when the host supplied `onRetry`: asks the agent to carry on again (clears outcome and problem). */
  retry?: () => void;
  /** Start following the continuation of approval request `requestId`. A repeat call for one id is ignored. */
  watch: (requestId: string) => void;
  /** Hide the problem line. */
  dismiss: () => void;
}

export interface UseApprovalContinuationOptions {
  agentId: string | null | undefined;
  sessionId: string | null | undefined;
  /** Called once per decision, when the agent's reply to its continuation is stored. */
  onReply: (reply: ApprovalContinuationReply) => void;
  /** Reads the stored conversation (tests inject a fake; the default is the real transcript route). */
  fetchTranscript?: (agentId: string, sessionId: string) => Promise<{ messages?: StoredMessage[] } | null>;
  /** Host action that asks the agent to carry on (e.g. sends a "carry on" message). Enables `retry`. */
  onRetry?: () => void;
  pollMs?: number;
  startWaitMs?: number;
  maxWaitMs?: number;
}

/**
 * Makes the conversation visibly continue after a tool approval was decided. The platform runs the continuation
 * turn itself (see approvalContinuation.ts); this hook follows it through the stored conversation and hands the
 * agent's reply to the surface, which appends it. While it runs, `continuing` is true so the surface can hold the
 * input back - a message typed now would race the agent's own turn. Reads are quiet (no error toast): a failed
 * read is just "not there yet", and the watcher ends with a problem line instead of an alarm.
 *
 * One watcher per approval request id and per conversation: a second `watch` for the same id is ignored, and a
 * change of agent or session (or unmount) stops everything.
 */
export function useApprovalContinuation(options: UseApprovalContinuationOptions): ApprovalContinuationWatch {
  const { agentId, sessionId } = options;
  const [active, setActive] = useState<ReadonlySet<string>>(() => new Set());
  const [problem, setProblem] = useState<ContinuationProblem | null>(null);
  const [outcome, setOutcome] = useState<ContinuationOutcome | null>(null);
  const [live, setLive] = useState<LiveContinuation | null>(null);
  const optionsRef = useRef(options);
  optionsRef.current = options;
  // Per request id: the stop function of its loop. Ids stay in `seen` after they finish, so one is watched once.
  const stopsRef = useRef<Map<string, () => void>>(new Map());
  const seenRef = useRef<Set<string>>(new Set());
  const scopeRef = useRef("");
  scopeRef.current = agentId && sessionId ? `${agentId}::${sessionId}` : "";

  const finish = useCallback((requestId: string) => {
    stopsRef.current.delete(requestId);
    setActive((prev) => {
      if (!prev.has(requestId)) return prev;
      const next = new Set(prev);
      next.delete(requestId);
      return next;
    });
  }, []);

  const stopAll = useCallback(() => {
    for (const stop of stopsRef.current.values()) stop();
    stopsRef.current.clear();
    seenRef.current.clear();
    setActive(new Set());
    setProblem(null);
    setOutcome(null);
    setLive(null);
  }, []);

  // A different conversation (or none): nothing watched for the old one may land in the new one.
  useEffect(() => {
    return () => stopAll();
  }, [agentId, sessionId, stopAll]);

  const watch = useCallback(
    (requestId: string) => {
      const scope = scopeRef.current;
      const current = optionsRef.current;
      if (!scope || !current.agentId || !current.sessionId || !requestId) return;
      if (seenRef.current.has(requestId)) return;
      seenRef.current.add(requestId);
      setProblem(null);
      setOutcome(null);
      setActive((prev) => new Set(prev).add(requestId));

      const agent = current.agentId;
      const session = current.sessionId;
      const read = current.fetchTranscript ?? ((a: string, s: string) => assistantApi.getChatTranscript(a, s));
      const startedAt = Date.now();
      setLive({ id: requestId, since: startedAt, phase: "waiting" });
      // Progress of THIS request only: a newer watch owns the live line.
      const note = (phase: ContinuationPhase) =>
        setLive((prev) => (prev && prev.id === requestId && prev.phase !== phase ? { ...prev, phase } : prev));
      let timer: ReturnType<typeof setTimeout> | null = null;
      let stopped = false;
      let started = false;
      stopsRef.current.set(requestId, () => {
        stopped = true;
        if (timer) clearTimeout(timer);
      });

      const end = (why: ContinuationProblem | null) => {
        if (stopped || scopeRef.current !== scope) return;
        stopped = true;
        if (why) setProblem(why);
        finish(requestId);
      };

      const tick = async () => {
        if (stopped) return;
        let messages: StoredMessage[] | null = null;
        try {
          const transcript = await read(agent, session);
          messages = Array.isArray(transcript?.messages) ? transcript.messages : null;
        } catch {
          messages = null; // quiet by design: the next poll tries again
        }
        if (stopped || scopeRef.current !== scope) return;
        const progress = locateContinuation(messages, requestId);
        if (progress.phase === "replied") {
          stopped = true;
          const { onReply } = optionsRef.current;
          finish(requestId);
          setOutcome(progress.failed ? "failed" : "completed");
          onReply({ requestId, content: progress.content, failed: progress.failed });
          return;
        }
        if (progress.phase === "running") started = true;
        note(started ? "running" : "waiting");
        const waited = Date.now() - startedAt;
        const { startWaitMs = CONTINUATION_START_WAIT_MS, maxWaitMs = CONTINUATION_MAX_WAIT_MS } = optionsRef.current;
        if (!started && waited >= startWaitMs) return end("notStarted");
        if (waited >= maxWaitMs) return end("timedOut");
        timer = setTimeout(() => void tick(), optionsRef.current.pollMs ?? CONTINUATION_POLL_MS);
      };
      void tick();
    },
    [finish]
  );

  const dismiss = useCallback(() => {
    setProblem(null);
    setOutcome(null);
  }, []);

  const hasRetry = !!options.onRetry;
  const retry = useCallback(() => {
    setProblem(null);
    setOutcome(null);
    optionsRef.current.onRetry?.();
  }, []);

  const continuing = active.size > 0;
  return {
    continuing,
    since: continuing ? (live?.since ?? null) : null,
    phase: continuing ? (live?.phase ?? null) : null,
    problem,
    outcome,
    retry: hasRetry ? retry : undefined,
    watch,
    dismiss,
  };
}

interface LiveContinuation {
  id: string;
  since: number;
  phase: ContinuationPhase;
}
