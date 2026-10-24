"use client";

import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { api } from "../../lib/api";
import { assistantApi } from "../../lib/api/assistant";
import { useTranslations } from "../../lib/i18n";
import {
  CONTINUATION_MAX_WAIT_MS,
  CONTINUATION_POLL_MS,
  CONTINUATION_START_WAIT_MS,
  locateContinuation,
  type StoredMessage,
} from "../agents/approvalContinuation";
import ApprovalContinuationStatus from "../agents/ApprovalContinuationStatus";
import type { ApprovalContinuationWatch, ContinuationOutcome, ContinuationProblem } from "../agents/useApprovalContinuation";
import type { ApprovalRequestItem } from "./approvalsModel";
import { isToolCallRequest, requesterIds } from "./approvalsQueueModel";

type FetchTranscript = (agentId: string, sessionId: string) => Promise<{ messages?: StoredMessage[] } | null>;

/** Past this age a decision's continuation is no longer reported on. */
const STALE_AFTER_MS = 24 * 3_600_000;

type Phase = "idle" | "starting" | "running" | "completed" | "failed" | "notStarted" | "timedOut";

/**
 * What became of the agent after one decided tool-call request, read from the stored conversation it belongs to
 * (core starts a "carry on" turn itself after every decision; see agents/approvalContinuation.ts). Works for a
 * request decided a moment ago and for an older one: a decision older than the start window with no continuation
 * message is reported as "not restarted", never as "running".
 */
export function useRequestContinuation(
  item: ApprovalRequestItem | null,
  options: { fetchTranscript?: FetchTranscript; pollMs?: number; startWaitMs?: number; maxWaitMs?: number } = {}
): { phase: Phase; ids: { agentId: string; sessionId: string } | null} {
  const ids = item && isToolCallRequest(item) && item.status !== "PENDING" ? requesterIds(item.requestedBy) : null;
  const requestId = item?.id ?? "";
  const decidedAt = item?.decidedAt ? new Date(item.decidedAt).getTime() : NaN;
  const [phase, setPhase] = useState<Phase>("idle");
  const optionsRef = useRef(options);
  optionsRef.current = options;
  const agentId = ids?.agentId ?? "";
  const sessionId = ids?.sessionId ?? "";

  useEffect(() => {
    if (!agentId || !sessionId) {
      setPhase("idle");
      return undefined;
    }
    // A decision older than a day is history, not something to retry: say nothing about it.
    if (Number.isFinite(decidedAt) && Date.now() - decidedAt > STALE_AFTER_MS) {
      setPhase("idle");
      return undefined;
    }
    let stopped = false;
    let timer: ReturnType<typeof setTimeout> | null = null;
    setPhase("starting");
    const tick = async () => {
      if (stopped) return;
      const opts = optionsRef.current;
      const read: FetchTranscript = opts.fetchTranscript ?? ((a, s) => assistantApi.getChatTranscript(a, s));
      let messages: StoredMessage[] | null = null;
      try {
        const transcript = await read(agentId, sessionId);
        messages = Array.isArray(transcript?.messages) ? transcript!.messages! : null;
      } catch {
        messages = null;
      }
      if (stopped) return;
      const progress = locateContinuation(messages, requestId);
      if (progress.phase === "replied") {
        setPhase(progress.failed ? "failed" : "completed");
        return;
      }
      const age = Number.isFinite(decidedAt) ? Date.now() - decidedAt : Number.POSITIVE_INFINITY;
      const startWait = opts.startWaitMs ?? CONTINUATION_START_WAIT_MS;
      const maxWait = opts.maxWaitMs ?? CONTINUATION_MAX_WAIT_MS;
      if (progress.phase === "running") {
        if (age >= maxWait) {
          setPhase("timedOut");
          return;
        }
        setPhase("running");
      } else if (age >= startWait) {
        setPhase("notStarted");
        return;
      } else {
        setPhase("starting");
      }
      timer = setTimeout(() => void tick(), opts.pollMs ?? CONTINUATION_POLL_MS);
    };
    void tick();
    return () => {
      stopped = true;
      if (timer) clearTimeout(timer);
    };
  }, [agentId, sessionId, requestId, decidedAt]);

  return { phase, ids };
}

/**
 * The queue's continuation state for the selected request: "the agent is continuing", "finished", or "could not
 * carry on" with a Retry that asks the agent to carry on in the same conversation. Renders nothing for requests
 * that are not decided tool calls.
 */
export default function ApprovalContinuationPanel({
  item,
  fetchTranscript,
  pollMs,
  startWaitMs,
  maxWaitMs,
}: {
  item: ApprovalRequestItem;
  fetchTranscript?: FetchTranscript;
  pollMs?: number;
  startWaitMs?: number;
  maxWaitMs?: number;
}) {
  const t = useTranslations("approvalsQueue");
  const { phase, ids } = useRequestContinuation(item, { fetchTranscript, pollMs, startWaitMs, maxWaitMs });
  const [retry, setRetry] = useState<"idle" | "running" | "done" | "failed">("idle");
  const [dismissed, setDismissed] = useState(false);

  // A different request: forget the previous one's retry and dismissal.
  useEffect(() => {
    setRetry("idle");
    setDismissed(false);
  }, [item.id]);

  const sendRetry = useCallback(() => {
    if (!ids) return;
    setRetry("running");
    setDismissed(false);
    Promise.resolve()
      .then(() => api.streamChat(ids.agentId, t("retryMessage"), ids.sessionId, () => undefined))
      .then(() => setRetry("done"))
      .catch(() => setRetry("failed"));
  }, [ids, t]);

  const watch = useMemo((): Pick<ApprovalContinuationWatch, "continuing" | "problem" | "dismiss"> & {
    outcome: ContinuationOutcome | null;
    retry?: () => void;
  } => {
    const retrying = retry === "running";
    let problem: ContinuationProblem | null = null;
    let outcome: ContinuationOutcome | null = null;
    if (!retrying && !dismissed) {
      // What the person just asked for outranks what the stored conversation said before it.
      if (retry === "done") outcome = "completed";
      else if (retry === "failed" || phase === "failed") outcome = "failed";
      else if (phase === "notStarted") problem = "notStarted";
      else if (phase === "timedOut") problem = "timedOut";
      else if (phase === "completed") outcome = "completed";
    }
    return {
      continuing: retrying || (!dismissed && (phase === "starting" || phase === "running") && retry !== "failed"),
      problem,
      outcome,
      retry: ids ? sendRetry : undefined,
      dismiss: () => setDismissed(true),
    };
  }, [phase, retry, dismissed, ids, sendRetry]);

  if (!ids || phase === "idle") return null;
  return (
    <div data-testid="approval-continuation-panel" className="grid gap-1">
      <ApprovalContinuationStatus continuation={watch} />
    </div>
  );
}
