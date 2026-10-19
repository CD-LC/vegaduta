"use client";

import { api } from "../../../lib/api";
import { errorMessage } from "../../../lib/errors";
import { isCancelledError } from "../../../lib/failedTurn";
import type { StagedFrame } from "../CameraPanel";
import type { LlmRequestSnapshot } from "../RequestInspector";
import { normalizeServerStep, type ActivityStep } from "../AgentActivityTrace";
import { announcePlaygroundTurn } from "./persistence";
import { framesForHistory } from "./format";
import type { PlaygroundContext } from "./sendContext";
import type { ChatAttachment, SessionLlmCall, ToolCallEntry, TurnMetrics } from "./types";

/** The hosted send: one streamChat call with live steps, tool-call trace, per-turn usage and request X-ray. Shared by the composer, the vision-fallback button, the watch loop and "get a smarter answer". */
export function useServerTurn(ctx: PlaygroundContext) {
  const { agent, activeAgentIdRef, stickToBottomRef, seenRequestIdsRef, turnSeq, setTurns, sessionId, setSessionId, setSending, setError, setChatController } = ctx;
  // The pre-existing server send, factored out of handleSend so three callers
  // share it byte-for-byte: the normal send above, the visionFallback
  // "send to the server instead" button, and CameraPanel's watch loop (via
  // runWatchTurn below, which adds visionSessionId + the watch badge and
  // needs the reply text + a rethrown error instead of the inline setError
  // handling being the end of it).
  async function sendServerTurn(
    userMessage: string,
    sentAttachment: ChatAttachment | null,
    sentFrames: StagedFrame[],
    opts: {
      visionSessionId?: string | null;
      watch?: boolean;
      rethrow?: boolean;
      edgeFallbackReason?: string | null;
    } = {}
  ) {
    const sentAgentId = agent?.id;
    const { visionSessionId = null, watch = false, rethrow = false, edgeFallbackReason = null } = opts;
    const turnStarted = Date.now();
    // Real "what did this turn actually do" trace - snapshot the tool-call
    // log before sending, diff against it after the reply lands, same
    // strategy ChatPanel's own refreshToolCalls() feeds off of.
    //
    // Both baseline reads run in PARALLEL (2026-09-25 latency audit): they
    // are independent GETs that used to add two serialized round trips to
    // every send's critical path before a byte was ever posted to /chat.
    // Per-turn LLM-usage baseline (AgentSessionLlmUsageController) - the
    // same before/after diff over the session's llm_call_log ledger. A null
    // sessionId means this send mints a fresh session, so the baseline is
    // trivially 0 and every row on the ledger afterward is this turn's.
    // null here means "unknowable" (the read failed, or the plan doesn't
    // include usage) and the metrics row then just omits tokens/cost rather
    // than fabricating them.
    let beforeCallIds = new Set<string>();
    let usageBaseline: number | null = sessionId ? null : 0;
    await Promise.all([
      (async () => {
        try {
          // Same untyped-api annotation as sendViaEdge's own snapshot above.
          const before: ToolCallEntry[] = await api.listToolCalls(agent.id);
          beforeCallIds = new Set(before.map((c) => c.id));
        } catch {
          // Trace becomes best-effort empty if this fails - never blocks sending.
        }
      })(),
      (async () => {
        if (!sessionId) return;
        try {
          const usageBefore: SessionLlmCall[] = await api.getAgentSessionLlmUsage(agent.id, sessionId);
          usageBaseline = Array.isArray(usageBefore) ? usageBefore.length : 0;
        } catch {
          // Entitlement (403, FREE plan) or transient failure - the send
          // proceeds without usage numbers either way.
        }
      })(),
    ]);

    if (activeAgentIdRef.current !== sentAgentId) {
      // The selected agent changed while that lookup was in flight (see
      // activeAgentIdRef's comment) - bail before adding any optimistic
      // turns, since they'd land in the NEW agent's just-loaded transcript.
      if (rethrow) throw new Error("Selected agent changed before this turn was sent.");
      return "";
    }

    const turnRef = `turn-${Date.now()}-${turnSeq.current++}`;
    // Sending is an explicit "I want to see what happens next", so re-arm
    // auto-scroll even if the person had scrolled up to read an older turn.
    // The window itself is left alone: if they expanded it to read history,
    // collapsing it out from under them here would be worse than a long page.
    stickToBottomRef.current = true;
    setTurns((t) => [
      ...t,
      { role: "user", content: userMessage, attachment: sentAttachment, frames: framesForHistory(sentFrames), watch },
    ]);
    setTurns((t) => [...t, { role: "assistant", content: "", trace: [], pending: true, ref: turnRef, watch }]);
    setSending(true);

    // Staged camera/screen frames ride as inline attachments with their
    // source, which is what the backend's per-user daily frame quota counts.
    const inlineAttachments =
      sentFrames && sentFrames.length > 0
        ? sentFrames.map((f) => ({
            fileName: f.fileName,
            mimeType: f.mimeType,
            dataBase64: f.dataBase64,
            source: f.source,
          }))
        : undefined;

    // Live "what's the agent doing right now" trace (direct user report,
    // 2026-08-13: "as of now it shows only connecting" - the RequestStatusIndicator
    // below only ever reflects "have bytes started flowing yet", so a slow tool
    // call BEFORE the model's first token - e.g. a Jira/Confluence MCP call - left
    // the turn looking frozen on "Connecting" for however long that call took,
    // with zero visibility into what was actually happening).
    //
    // 2026-09-09: the PRIMARY live source is now the SSE step-event side
    // channel (the `steps` argument to streamChat below - the same events
    // ChatPanel has rendered since 2026-08-17), which arrives in-band with
    // the tokens and costs zero extra requests. This 650ms listToolCalls
    // poll is kept ONLY as a fallback for a core that emits no step events
    // (older build, or stepEvents ignored): it runs until the first step
    // event lands and is then cleared, so a modern core never pays for a
    // poll it doesn't need. Same 600ms-ish cadence QuickTaskService.runStream's
    // server-side poller uses for the identical problem. Best-effort: a failed
    // poll tick is silently skipped, the next tick just catches up.
    let liveTrace: ToolCallEntry[] = [];
    let stepEventArrived = false;
    let turnSteps: ActivityStep[] = [];
    const pollLiveTrace = async () => {
      if (activeAgentIdRef.current !== sentAgentId) return;
      if (stepEventArrived) return; // the SSE side channel took over
      try {
        const after: ToolCallEntry[] = await api.listToolCalls(agent.id);
        const fresh = after.filter((c) => !beforeCallIds.has(c.id));
        if (fresh.length === liveTrace.length) return; // nothing new this tick
        liveTrace = fresh;
        if (activeAgentIdRef.current !== sentAgentId) return;
        setTurns((t) => {
          const copy = [...t];
          const last = copy[copy.length - 1];
          if (!last || last.ref !== turnRef) return t; // turn already finalized/replaced
          copy[copy.length - 1] = { ...last, trace: liveTrace };
          return copy;
        });
      } catch {
        // Best-effort - see comment above. Next tick tries again.
      }
    };
    const liveTraceTimer = setInterval(() => void pollLiveTrace(), 650);

    let assistantText = "";
    try {
      const newSessionId = await api.streamChat(
        agent.id,
        userMessage,
        sessionId,
        (chunk: string) => {
          // Selected agent changed mid-stream - see activeAgentIdRef's
          // comment; don't let a stale token overwrite the new agent's turn.
          if (activeAgentIdRef.current !== sentAgentId) return;
          assistantText += chunk;
          setTurns((t) => {
            const copy = [...t];
            copy[copy.length - 1] = { ...copy[copy.length - 1], content: assistantText };
            return copy;
          });
        },
        sentAttachment,
        turnRef,
        inlineAttachments,
        visionSessionId,
        { onController: setChatController },
        // Opt-in step events (sets stepEvents:true on the request) - the
        // live trace, attached to THIS turn by ref so a stale event from an
        // already-finalised turn can never land on the next one.
        // normalizeServerStep never throws: a newer core's event types
        // (run_started, plan, guardrail, handoff, ...) render generically.
        {
          onEvent: (event) => {
            if (activeAgentIdRef.current !== sentAgentId) return;
            if (!stepEventArrived) {
              stepEventArrived = true;
              clearInterval(liveTraceTimer); // fallback poll no longer needed
            }
            // Heartbeats are liveness beats (core emits one every
            // app.chat.sse.step-heartbeat-interval to keep the wire alive
            // through silent phases). They already did their job - this
            // dispatch reset the lifecycle's idle timer - so they stop the
            // fallback poll above but earn no row in the trace.
            if (event?.type === "heartbeat") return;
            turnSteps = [...turnSteps, normalizeServerStep(event, turnSteps.length + 1)];
            const snapshot = turnSteps;
            setTurns((t) => {
              const copy = [...t];
              const last = copy[copy.length - 1];
              if (!last || last.ref !== turnRef) return t;
              copy[copy.length - 1] = { ...last, steps: snapshot };
              return copy;
            });
          },
        }
      );

      // One last catch-up poll (fallback path only) - closes the small race
      // between the final tool call's DB write and the next interval tick,
      // same reasoning QuickTaskService.runStream's own catch-up poll
      // documents. The interval itself is cleared in `finally` below,
      // covering this path, the error path, AND the early stale-agent
      // returns uniformly.
      if (!stepEventArrived) await pollLiveTrace();

      if (activeAgentIdRef.current !== sentAgentId) {
        // The agent switched while this request was in flight. The reply
        // already streamed and is saved server-side under its own session -
        // only the local finalize-into-`turns` step is skipped, since `turns`
        // now belongs to the newly-selected agent's own transcript.
        return assistantText;
      }

      if (newSessionId) setSessionId(newSessionId);

      const elapsedMs = Date.now() - turnStarted;
      // Both post-turn reads in parallel (same audit as the baseline reads
      // above): the reply is complete, so these run off the critical path's
      // end but still gate the metrics/trace row landing.
      let trace: ToolCallEntry[] = [];
      // This turn's slice of the session ledger: every llm_call_log row
      // recorded since the baseline snapshot - which on CAREFUL/RIGOROUS/
      // PLANNED is all of them (consistency checks, Reflexion critiques,
      // tool round trips), exactly the hidden cost the metrics row exists
      // to surface. Best-effort like every read in this block.
      let turnUsage: Pick<
        TurnMetrics,
        "promptTokens" | "completionTokens" | "totalTokens" | "estimatedCostUsd" | "poweredBy"
      > | null = null;
      let turnRequests: LlmRequestSnapshot[] = [];
      await Promise.all([
        (async () => {
          // Request X-ray: turns are sequential, so "snapshots not seen yet"
          // is exactly this turn's model calls - no client/server clock compare.
          try {
            const all: LlmRequestSnapshot[] = await api.getAgentSessionLlmRequests(agent.id, newSessionId || sessionId);
            if (Array.isArray(all)) {
              turnRequests = all.filter((s) => !seenRequestIdsRef.current.has(s.id));
              for (const s of all) seenRequestIdsRef.current.add(s.id);
            }
          } catch {
            // Inspector is a bonus surface - never block or fail the turn on it.
          }
        })(),
        (async () => {
          try {
            const after: ToolCallEntry[] = await api.listToolCalls(agent.id);
            trace = after.filter((c) => !beforeCallIds.has(c.id));
          } catch {
            // Already-polled liveTrace is a fine fallback - the reply text itself streamed fine either way.
            trace = liveTrace;
          }
        })(),
        (async () => {
          if (usageBaseline == null) return;
          try {
            const usageAfter: SessionLlmCall[] = await api.getAgentSessionLlmUsage(agent.id, newSessionId || sessionId);
            if (Array.isArray(usageAfter)) {
              const delta = usageAfter.slice(usageBaseline);
              if (delta.length > 0) {
                // Unique provider/model pairs this turn actually burned -
                // on CAREFUL/RIGOROUS a turn can be several calls, and this
                // is the ACTUAL executed engine (fallback providers show up
                // here even when the agent is configured differently).
                const engines = new Map<string, { providerName: string | null; model: string | null }>();
                for (const c of delta) {
                  if (!c.providerName && !c.model) continue;
                  engines.set(`${c.providerName ?? ""}::${c.model ?? ""}`, {
                    providerName: c.providerName ?? null,
                    model: c.model ?? null,
                  });
                }
                turnUsage = {
                  promptTokens: delta.reduce((s, c) => s + (c.promptTokens || 0), 0),
                  completionTokens: delta.reduce((s, c) => s + (c.completionTokens || 0), 0),
                  totalTokens: delta.reduce((s, c) => s + (c.totalTokens || 0), 0),
                  estimatedCostUsd: delta.reduce((s, c) => s + (c.estimatedCostUsd || 0), 0),
                  poweredBy: engines.size > 0 ? [...engines.values()] : undefined,
                };
              }
            }
          } catch {
            // See the baseline read's comment - no numbers beats wrong numbers.
          }
        })(),
      ]);

      if (activeAgentIdRef.current !== sentAgentId) {
        return assistantText;
      }

      setTurns((t) => {
        const copy = [...t];
        copy[copy.length - 1] = {
          ...copy[copy.length - 1],
          content: assistantText,
          trace,
          steps: turnSteps,
          requests: turnRequests.length > 0 ? turnRequests : undefined,
          pending: false,
          metrics: { elapsedMs, toolCalls: trace.length, edgeFallbackReason, ...(turnUsage || {}) },
        };
        return copy;
      });
      announcePlaygroundTurn(sentAgentId);
    } catch (err) {
      if (activeAgentIdRef.current !== sentAgentId) {
        // Stale request from a since-abandoned agent - surfacing this error
        // against the newly-selected agent's screen would be misleading, and
        // mutating `turns` here would corrupt its transcript. Still rethrow
        // for the watch loop (err.status drives its own handling), just skip
        // the local UI side effects.
        if (rethrow) throw err;
        return assistantText;
      }
      // Direct feedback ("proper meaningful messages to consumer, not raw
      // http responses") - same fix as ChatPanel.js: err.message is already
      // a clean, plain-language sentence (see UpstreamProviderException
      // #getConsumerMessage()'s javadoc) - stop tacking a raw HTTP status
      // code back onto it.
      setError(errorMessage(err));
      // A mid-stream failure (api.ts's STREAM_ERROR_MARKER handling) means
      // assistantText may already hold real, useful partial content - only
      // drop the turn entirely when nothing ever streamed (the pre-existing
      // behavior for an immediate/pre-token failure). Losing text the user
      // already saw build up on screen just because the tail end failed
      // would be a regression, not a fix.
      // A failure with nothing streamed stays in the transcript as an error bubble ("Reply failed" + Try again), so
      // it is still there after a reload. Not for a cancel (the person asked to stop) and not for callers that
      // handle the failure themselves (watch loop, frames rescue: they add their own turn).
      const keepAsFailedBubble = !assistantText && !rethrow && !isCancelledError(err);
      setTurns((t) => {
        if (!assistantText) {
          if (!keepAsFailedBubble) return t.slice(0, -1);
          const copyFailed = [...t];
          copyFailed[copyFailed.length - 1] = {
            role: "assistant",
            content: errorMessage(err, ""),
            failed: true,
            pending: false,
          };
          return copyFailed;
        }
        const copy = [...t];
        copy[copy.length - 1] = { ...copy[copy.length - 1], content: assistantText, pending: false };
        return copy;
      });
      // The watch loop needs the failure itself (err.status drives its
      // 403/429/503-specific notices), not just the setError side effect.
      // streamedChars rides along for handleSend's edge-rescue check: a
      // failure AFTER real content already streamed keeps that partial turn
      // (finalized above), so a local retry would produce a confusing second
      // answer - the rescue only engages when nothing ever streamed.
      if (rethrow) {
        if (err && typeof err === "object") Object.assign(err, { streamedChars: assistantText.length });
        throw err;
      }
    } finally {
      clearInterval(liveTraceTimer);
      if (activeAgentIdRef.current === sentAgentId) setSending(false);
    }
    return assistantText;
  }

  return { sendServerTurn };
}
