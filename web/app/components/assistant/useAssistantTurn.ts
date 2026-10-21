"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { assistantApi } from "../../lib/api/assistant";
import { api } from "../../lib/api";
import { requestServerCancel, type ReconnectingState } from "../../lib/api/chatResume";
import { errorMessage } from "../../lib/errors";
import { isCancelledError } from "../../lib/failedTurn";
import { useRequestLifecycle, type RequestLifecycleController } from "../../lib/requestLifecycle";
import { normalizeServerStep, type ActivityStep } from "../agents/AgentActivityTrace";
import type { LlmRequestSnapshot } from "../agents/RequestInspector";
import type { SessionLlmCall } from "../agents/playground/types";
import { splitForSend, type PendingAttachment } from "../ChatAttachments";
import type { StreamReconnectingState } from "../StreamReconnectingNotice";
import type { AssistantTurn } from "./assistantTypes";
import { persistTurnInBranchTree } from "./branchPersist";

/** What a settled turn tells the pane (thread index, work counts, background offer). */
export interface TurnOutcome {
  sessionId: string | null;
  text: string;
  steps: ActivityStep[];
  ok: boolean;
  cancelled: boolean;
}

let turnSeq = 0;
export function nextTurnId(prefix = "t"): string {
  return `${prefix}-${Date.now()}-${turnSeq++}`;
}

/**
 * The transcript and the one turn that may be in flight (rung 1 of the dispatch ladder). Streams
 * POST /api/agents/{id}/chat with stepEvents on, folds each step event into the live assistant entry
 * (WorkedForBlock renders it), and keeps the request lifecycle so the composer can show Stop. Resumable
 * streams are on, so a dropped connection resumes quietly; Stop therefore also tells the server to cancel
 * the detached turn (a client disconnect alone no longer stops generation).
 */
export function useAssistantTurn(agentId: string | null) {
  const [turns, setTurns] = useState<AssistantTurn[]>([]);
  const [sessionId, setSessionIdState] = useState<string | null>(null);
  const sessionIdRef = useRef<string | null>(null);
  const setSessionId = useCallback((id: string | null) => {
    sessionIdRef.current = id;
    setSessionIdState(id);
  }, []);
  const [sending, setSending] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [controller, setController] = useState<RequestLifecycleController<string | null> | null>(null);
  const { snapshot, cancel: cancelRequest } = useRequestLifecycle(controller);
  const [reconnecting, setReconnecting] = useState<StreamReconnectingState | null>(null);
  const [settledCount, setSettledCount] = useState(0);
  const liveAgentRef = useRef<string | null>(agentId);
  // Turns run sequentially, so "snapshots/ledger rows not seen yet" is exactly
  // this turn's model calls — the same dedupe-by-id read the playground does.
  const seenRequestIdsRef = useRef<Set<string>>(new Set());
  const seenUsageIdsRef = useRef<Set<string>>(new Set());
  useEffect(() => {
    liveAgentRef.current = agentId;
  }, [agentId]);

  const patchTurn = useCallback((id: string, patch: Partial<AssistantTurn>) => {
    setTurns((list) => list.map((t) => (t.id === id ? { ...t, ...patch } : t)));
  }, []);

  const appendTurns = useCallback((...entries: AssistantTurn[]) => {
    setTurns((list) => [...list, ...entries]);
  }, []);

  /**
   * After a turn settles, pull this turn's slice of the session's model-call
   * records: the Request X-ray snapshots (the exact prompt/options/tool list
   * core sent the provider) and the llm-usage ledger rows (real token/cost).
   * Best-effort like the playground's identical reads — 403/404 just means
   * the surface shows no inspector for that turn.
   */
  const enrichTurn = useCallback(
    async (enrichAgentId: string, sid: string, replyId: string) => {
      const [requests, usage] = await Promise.all([
        api.getAgentSessionLlmRequests(enrichAgentId, sid).catch((): null => null),
        api.getAgentSessionLlmUsage(enrichAgentId, sid).catch((): null => null),
      ]);
      const patch: Partial<AssistantTurn> = {};
      if (Array.isArray(requests)) {
        const fresh = (requests as LlmRequestSnapshot[]).filter((s) => !seenRequestIdsRef.current.has(s.id));
        for (const s of requests as LlmRequestSnapshot[]) seenRequestIdsRef.current.add(s.id);
        if (fresh.length > 0) patch.requests = fresh;
      }
      if (Array.isArray(usage)) {
        const delta = (usage as SessionLlmCall[]).filter((c) => !seenUsageIdsRef.current.has(c.id));
        for (const c of usage as SessionLlmCall[]) seenUsageIdsRef.current.add(c.id);
        if (delta.length > 0) {
          const engines = new Map<string, { providerName: string | null; model: string | null }>();
          for (const c of delta) {
            if (c.providerName || c.model) {
              engines.set(`${c.providerName ?? ""}::${c.model ?? ""}`, {
                providerName: c.providerName ?? null,
                model: c.model ?? null,
              });
            }
          }
          patch.usage = {
            promptTokens: delta.reduce((s, c) => s + (c.promptTokens || 0), 0),
            completionTokens: delta.reduce((s, c) => s + (c.completionTokens || 0), 0),
            totalTokens: delta.reduce((s, c) => s + (c.totalTokens || 0), 0),
            estimatedCostUsd: delta.reduce((s, c) => s + (c.estimatedCostUsd || 0), 0),
            poweredBy: engines.size > 0 ? [...engines.values()] : undefined,
          };
        }
      }
      if (Object.keys(patch).length > 0) patchTurn(replyId, patch);
    },
    [patchTurn]
  );

  /**
   * One user message in, one streamed reply out. `targetAgentId` (rung 5) sends the message to another
   * agent in a session of its own; the Assistant's own session is left untouched. `sendText` is what goes
   * to the server when it differs from what the person typed (the app's connection facts ride along).
   */
  const runTurn = useCallback(
    async (
      message: string,
      attachments: PendingAttachment[] = [],
      opts: { note?: string | null; targetAgentId?: string | null; replyNote?: string | null; sendText?: string | null } = {}
    ): Promise<TurnOutcome> => {
      const target = opts.targetAgentId ?? liveAgentRef.current;
      const outcome: TurnOutcome = { sessionId: sessionIdRef.current, text: "", steps: [], ok: false, cancelled: false };
      if (!target) return outcome;
      const now = Date.now();
      const userId = nextTurnId("u");
      const replyId = nextTurnId("a");
      const turnRef = `turn-${now}-${turnSeq++}`;
      appendTurns(
        {
          id: userId,
          role: "user",
          content: message,
          at: now,
          note: opts.note ?? null,
          attachments: attachments.map(({ fileName, mimeType, sizeBytes, previewUrl }) => ({ fileName, mimeType, sizeBytes, previewUrl })),
        },
        { id: replyId, role: "assistant", content: "", at: now, startedAt: now, endedAt: null, steps: [], note: opts.replyNote ?? null }
      );
      setSending(true);
      setError(null);
      let text = "";
      let steps: ActivityStep[] = [];
      const ownSession = !opts.targetAgentId;
      const split = splitForSend(attachments);
      try {
        const newSessionId = await assistantApi.streamAssistantTurn(
          target,
          opts.sendText ?? message,
          ownSession ? sessionIdRef.current : null,
          (chunk) => {
            text += chunk;
            patchTurn(replyId, { content: text });
          },
          (event) => {
            if (event?.type === "heartbeat") return;
            steps = [...steps, normalizeServerStep(event, steps.length + 1)];
            patchTurn(replyId, { steps });
          },
          {
            turnRef,
            attachment: split.stored,
            attachments: split.inline,
            lifecycle: {
              onController: setController,
              resumable: true,
              onReconnecting: (s: ReconnectingState | null) =>
                setReconnecting(s ? { attempt: s.attempt, max: s.max, cancel: s.cancel } : null),
              onReconcile: (serverText: string) => {
                text = serverText;
                patchTurn(replyId, { content: serverText });
              },
            },
          }
        );
        if (ownSession && newSessionId) setSessionId(newSessionId);
        patchTurn(replyId, { endedAt: Date.now(), ref: turnRef });
        const enrichSid = ownSession ? newSessionId || sessionIdRef.current : null;
        if (enrichSid) {
          void enrichTurn(target, enrichSid, replyId);
          // Persistence only (the thread list and reopening after 24h); the answer is never applied here.
          void persistTurnInBranchTree(target, enrichSid);
        }
        return { ...outcome, sessionId: ownSession ? newSessionId || sessionIdRef.current : null, text, steps, ok: true };
      } catch (err) {
        const cancelled = isCancelledError(err);
        const reason = errorMessage(err);
        if (!text && cancelled) {
          // The person stopped it before anything arrived: nothing to keep.
          setTurns((list) => list.filter((t) => t.id !== replyId));
        } else if (!text) {
          patchTurn(replyId, { failed: true, content: reason, endedAt: Date.now() });
        } else {
          patchTurn(replyId, { endedAt: Date.now(), steps });
        }
        if (!cancelled) setError(reason);
        return { ...outcome, sessionId: sessionIdRef.current, text, steps, cancelled };
      } finally {
        setSending(false);
        setReconnecting(null);
        setController(null);
        setSettledCount((n) => n + 1);
      }
    },
    [appendTurns, patchTurn, setSessionId, enrichTurn]
  );

  /** Stop: abort the stream and ask the server to cancel the (detached) turn. */
  const cancel = useCallback(() => {
    cancelRequest("Stopped");
    reconnecting?.cancel();
    if (liveAgentRef.current) requestServerCancel(liveAgentRef.current, sessionIdRef.current);
  }, [cancelRequest, reconnecting]);

  /** Drop the exchange ending at `index` and send its user message again. */
  const retry = useCallback(
    (index: number) => {
      const failed = turns[index];
      const user = turns[index - 1];
      if (!failed || failed.role !== "assistant" || !user || user.role !== "user" || sending) return;
      setTurns((list) => list.slice(0, index - 1));
      void runTurn(user.content, []);
    },
    [turns, sending, runTurn]
  );

  const reset = useCallback(
    (nextSessionId: string | null, nextTurns: AssistantTurn[] = []) => {
      setSessionId(nextSessionId);
      setTurns(nextTurns);
      setError(null);
    },
    [setSessionId]
  );

  return {
    turns,
    setTurns,
    appendTurns,
    sessionId,
    sending,
    error,
    setError,
    snapshot,
    reconnecting,
    settledCount,
    runTurn,
    cancel,
    retry,
    reset,
  };
}
