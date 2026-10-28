"use client";

import { api } from "../../../lib/api";
import { errorMessage } from "../../../lib/errors";
import type { StagedFrame } from "../CameraPanel";
import { appendEdgeToolEvent, type ActivityStep } from "../AgentActivityTrace";
import { analyzeFramesLocally } from "../../../lib/edge/vision";
import { edgeFallbackLabel, resolveAutomaticEdgeFallback } from "../../../lib/edge/capabilities";
import { routeTurn, type HostedFallthroughReason } from "../../../lib/edge/tierRouter";
import { recordEdgeConversationFact } from "../../../lib/edge/toolBridge";
import { announcePlaygroundTurn } from "./persistence";
import { framesForHistory } from "./format";
import type { PlaygroundContext } from "./sendContext";
import type { EdgeSendResult, ToolCallEntry } from "./types";

/** The on-device sends: a plain text turn through the tier router (tier 0/1) and staged camera/screen frames through the local vision model. Both withdraw their optimistic turns and report why when they cannot answer. */
export function useEdgeTurns(ctx: PlaygroundContext) {
  const {
    agent,
    activeAgentIdRef,
    stickToBottomRef,
    setTurns,
    setSending,
    setError,
    setVisionFallback,
    setEdgePhase,
    setEdgeWarmingPercent,
    edgeAbortControllerRef,
    lastEdgeModelRef,
    askToFallback,
    matchRecognizedFace,
    withRecognizedName,
  } = ctx;
  // Try to answer a plain text turn on-device (Tier 0,
  // Chrome built-in model) via the tier router. Returns {handled:true} only
  // when the turn was fully handled locally; ANY other outcome (router
  // fall-through, partial stream then failure, unexpected throw) withdraws
  // the optimistic turns and returns {handled:false, reason} so handleSend
  // continues down the EXISTING server path completely unchanged - per the
  // router's caller contract, tokens already rendered from a failed local
  // attempt are discarded. The reason travels with the fallback (previously
  // dropped entirely) so the eventual hosted reply can say WHY it didn't run
  // on-device instead of the person just seeing "still uses platform llm"
  // with nothing to go on.
  async function sendViaEdge(userMessage: string): Promise<EdgeSendResult> {
    const sentAgentId = agent?.id;
    const turnStarted = Date.now();
    stickToBottomRef.current = true;
    setTurns((t) => [...t, { role: "user", content: userMessage }]);
    // No `ref` on purpose: TurnFeedback keys its RL reward signal to a server
    // turn, and an on-device turn never reached the server.
    // edgeAttempt: true - the ONLY signal the render below uses to decide
    // whether this pending turn shows the real edgePhase state machine
    // (warming/generating) instead of the generic hosted "thinking" label.
    setTurns((t) => [
      ...t,
      { role: "assistant", content: "", trace: [], pending: true, edgeAttempt: true, startedAt: Date.now() },
    ]);
    setSending(true);
    setEdgePhase("checking");
    lastEdgeModelRef.current = null;
    setEdgeWarmingPercent(null);
    // See edgeAbortControllerRef's own comment above - gives this turn a
    // real Cancel button instead of only tier0/tier1's own internal
    // timeouts (30s/60s) being the sole way it ever resolves.
    const abortController = new AbortController();
    edgeAbortControllerRef.current = abortController;
    let streamed = "";
    // Seeded with routeTurn's own catch-all reason: it only applies if the
    // try below throws before `fallback` is reassigned (routeTurn's contract
    // is to never throw, so this is the same belt-and-braces as that catch).
    let fallback: { reason: HostedFallthroughReason; detail: string | undefined } = {
      reason: "all-tiers-failed",
      detail: undefined,
    };
    let scrubbedInput: string | undefined;
    // Same before/after diff sendServerTurn uses (api.listToolCalls) - an
    // edge tool call executes server-side via POST /api/edge/tools/execute
    // and lands in the SAME tool_call_log rows (session id prefixed "edge:"),
    // so this is the real trace, not a parallel bookkeeping scheme.
    let beforeCallIds = new Set<string>();
    try {
      // api.listToolCalls is untyped (Promise<any>) - the annotation applies
      // ToolCallEntry, the slice of ToolCallLogView this file reads.
      const before: ToolCallEntry[] = await api.listToolCalls(agent.id);
      beforeCallIds = new Set(before.map((c) => c.id));
    } catch {
      // Trace becomes best-effort empty if this fails - never blocks sending.
    }
    // Live on-device tool rounds, badged "on-device" in the shared trace.
    // Reassigned (never mutated) so each snapshot is a distinct array.
    let edgeSteps: ActivityStep[] = [];
    try {
      const result = await routeTurn(userMessage, {
        sessionKey: agent.id,
        systemPrompt: agent.systemPrompt || agent.instructions || undefined,
        signal: abortController.signal,
        onToken: (delta) => {
          // Selected agent changed mid-stream - see activeAgentIdRef's
          // comment; don't let a stale token land on the new agent's turns.
          if (activeAgentIdRef.current !== sentAgentId) return;
          streamed += delta;
          setTurns((t) => {
            const copy = [...t];
            copy[copy.length - 1] = { ...copy[copy.length - 1], content: streamed };
            return copy;
          });
        },
        onToolCall: (event) => {
          if (activeAgentIdRef.current !== sentAgentId) return;
          edgeSteps = appendEdgeToolEvent(edgeSteps, event);
          const snapshot = edgeSteps;
          setTurns((t) => {
            const copy = [...t];
            copy[copy.length - 1] = { ...copy[copy.length - 1], steps: snapshot };
            return copy;
          });
        },
      });
      if (activeAgentIdRef.current !== sentAgentId) {
        // The agent switched while routeTurn was in flight. The optimistic
        // turns this call added already belong to a transcript that's gone
        // (replaced by the newly-selected agent's own) - finalizing or
        // withdrawing them here would instead corrupt the NEW agent's
        // turns array. Report unhandled without touching any shared state;
        // the caller's own switch-triggered re-render makes this result moot.
        return { handled: false, reason: "agent-switched", detail: undefined };
      }
      // `=== true` (not truthiness): with this repo's strict:false tsconfig,
      // only the literal comparison narrows RouteTurnResult's discriminated
      // union so the tail below can read result.reason/.detail - same gotcha
      // sendFramesLocally's own comment documents for the identical pattern.
      if (result.handled === true) {
        const elapsedMs = Date.now() - turnStarted;
        let trace: ToolCallEntry[] = [];
        try {
          const after: ToolCallEntry[] = await api.listToolCalls(agent.id);
          trace = after.filter((c) => !beforeCallIds.has(c.id));
        } catch {
          // Leave trace empty - the reply text itself already streamed fine.
        }
        if (activeAgentIdRef.current !== sentAgentId) {
          return { handled: false, reason: "agent-switched", detail: undefined };
        }
        setTurns((t) => {
          const copy = [...t];
          copy[copy.length - 1] = {
            ...copy[copy.length - 1],
            content: result.text,
            trace,
            steps: edgeSteps,
            pending: false,
            metrics: {
              elapsedMs,
              toolCalls: trace.length,
              edgeTier: result.tier,
              edgeDowngradeDetail: result.downgradedFrom?.detail ?? null,
              edgeModel: result.tier === 0 ? null : lastEdgeModelRef.current,
            },
          };
          return copy;
        });
        setSending(false);
        announcePlaygroundTurn(sentAgentId);
        // Opt-in only (off by default - see recordEdgeConversationFact's own
        // comment); fire-and-forget, never awaited, never surfaced.
        void recordEdgeConversationFact(agent?.id, userMessage, result.text);
        setEdgePhase(null);
        setEdgeWarmingPercent(null);
        return { handled: true };
      }
      fallback = { reason: result.reason, detail: result.detail };
      scrubbedInput = result.scrubbedInput;
    } catch {
      // routeTurn's contract is to never throw - this is belt-and-braces.
    } finally {
      // Covers every exit above (success, agent-switched, fallback) - a
      // stale controller left in the ref could have cancelEdgeTurn() abort
      // a FUTURE turn's fetch instead of doing nothing, once this one is
      // already over.
      if (edgeAbortControllerRef.current === abortController) {
        edgeAbortControllerRef.current = null;
      }
    }
    if (activeAgentIdRef.current !== sentAgentId) {
      return { handled: false, reason: "agent-switched", detail: undefined };
    }
    // Edge Wave 2 (EDGE-B): "edge-only-refused" is NOT a hosted fallthrough
    // - this agent's server policy forbids sending the turn to the platform,
    // so surface the refusal and stop (same handling ChatPanel/CallPanel
    // already have). Checked before the fallback-policy prompt below: asking
    // "fall back to hosted?" for a turn policy forbids from ever going hosted
    // would be the wrong question.
    if (fallback.reason === "edge-only-refused") {
      setTurns((t) => t.slice(0, -2));
      setSending(false);
      setEdgePhase(null);
      setEdgeWarmingPercent(null);
      setError(edgeFallbackLabel(fallback.reason));
      return { handled: false, reason: "fallback-refused", detail: fallback.detail };
    }
    // Fix #6 (P1): honor the same per-browser edge.fallbackPolicy CallPanel's
    // voice pipeline already does, before silently re-sending hosted -
    // "aborted" (the person hit Cancel) skips this: that's already a stop,
    // not a fallback decision to gate.
    let fallbackAllowed = true;
    if (fallback.reason !== "aborted") {
      fallbackAllowed = await resolveAutomaticEdgeFallback(() => askToFallback(edgeFallbackLabel(fallback.reason)));
      if (activeAgentIdRef.current !== sentAgentId) {
        return { handled: false, reason: "agent-switched", detail: undefined };
      }
    }
    // Unhandled: withdraw the two optimistic turns (user + pending assistant)
    // so the server path below re-adds them exactly as it always has.
    setTurns((t) => t.slice(0, -2));
    setSending(false);
    setEdgePhase(null);
    setEdgeWarmingPercent(null);
    if (!fallbackAllowed) {
      setError(edgeFallbackLabel(fallback.reason));
      return { handled: false, reason: "fallback-refused", detail: fallback.detail };
    }
    return { handled: false, reason: fallback.reason, detail: fallback.detail, hostedMessage: scrubbedInput };
  }

  // Edge SNAPSHOT path - staged frames analyzed by the local VLM.
  // Mirrors sendViaEdge's optimistic-turns-then-withdraw shape: a handled
  // result finalizes in place; anything else withdraws both turns and parks
  // the payload in visionFallback so the person decides what happens next.
  async function sendFramesLocally(
    userMessage: string,
    sentFrames: StagedFrame[],
    // rescueFrom carries sendServerTurn's rethrown Error, which that path
    // decorates with streamedChars (see its rethrow tail) - typed as the
    // intersection rather than a bare Error so the decoration is visible.
    opts: { rescueFrom?: (Error & { streamedChars?: number }) | null } = {}
  ) {
    // rescueFrom: the server-turn failure this local analysis is recovering
    // from (handleSend's edge-rescue path). The user turn already exists in
    // the transcript (sendServerTurn's no-text failure path withdraws only
    // the assistant turn), so only the assistant turn is appended here, and
    // a local failure surfaces the ORIGINAL server error rather than the
    // visionFallback "send to the server instead" offer - the server is
    // exactly what just failed.
    const { rescueFrom = null } = opts;
    const sentAgentId = agent?.id;
    const turnStarted = Date.now();
    // Face recognition (2026-08-06) - resolved BEFORE the optimistic turn
    // below is created (not after), so the recognized-name splice is part
    // of the turn's content from the moment it first renders, rather than
    // the turn appearing then silently changing text underneath it.
    // Budget-capped (FACE_MATCH_BUDGET_MS) so this never meaningfully
    // delays "Show" -> send.
    const recognizedName = sentFrames[0] ? await matchRecognizedFace(sentFrames[0].dataUrl) : null;
    const effectiveMessage = withRecognizedName(userMessage, recognizedName);
    stickToBottomRef.current = true;
    if (!rescueFrom) {
      setTurns((t) => [...t, { role: "user", content: effectiveMessage, frames: framesForHistory(sentFrames) }]);
    }
    // No `ref`: TurnFeedback keys its RL reward to a server turn, and an
    // on-device turn never reached the server (same rule as sendViaEdge).
    setTurns((t) => [
      ...t,
      {
        role: "assistant",
        content: "",
        trace: [],
        pending: true,
        onDevice: true,
        rescuedFromServer: !!rescueFrom,
        startedAt: Date.now(),
      },
    ]);
    setSending(true);
    try {
      const result = await analyzeFramesLocally(
        sentFrames.map((f) => ({ dataUrl: f.dataUrl })),
        effectiveMessage
      );
      if (activeAgentIdRef.current !== sentAgentId) {
        // Agent switched mid-analysis - see activeAgentIdRef's comment. The
        // optimistic turns above already belong to a transcript that's gone;
        // finalizing OR withdrawing them here would corrupt the newly
        // selected agent's own turns array, so touch nothing further.
        return;
      }
      // `=== true` (not truthiness): with this repo's strict:false tsconfig,
      // only the literal comparison narrows the contract's discriminated
      // union, so the tail below can read `result.reason`.
      if (result.handled === true) {
        const elapsedMs = Date.now() - turnStarted;
        setTurns((t) => {
          const copy = [...t];
          copy[copy.length - 1] = {
            ...copy[copy.length - 1],
            content: result.text,
            pending: false,
            metrics: { elapsedMs, toolCalls: 0 },
          };
          return copy;
        });
        announcePlaygroundTurn(sentAgentId);
        return;
      }
      if (rescueFrom) {
        // Both directions failed (server first, now local). Withdraw only
        // the assistant turn this rescue added - the user turn predates the
        // rescue - and re-surface the ORIGINAL server failure; offering
        // "send to the server instead" here would offer the thing that
        // already failed.
        setTurns((t) => t.slice(0, -1));
        setError(rescueFrom?.message || result.reason);
        return;
      }
      setTurns((t) => t.slice(0, -2));
      setVisionFallback({ message: userMessage, frames: sentFrames, reason: result.reason });
    } catch (err) {
      if (activeAgentIdRef.current !== sentAgentId) return;
      if (rescueFrom) {
        setTurns((t) => t.slice(0, -1));
        setError(rescueFrom?.message || errorMessage(err, "on-device analysis failed"));
        return;
      }
      setTurns((t) => t.slice(0, -2));
      setVisionFallback({
        message: userMessage,
        frames: sentFrames,
        reason: errorMessage(err, "on-device analysis failed"),
      });
    } finally {
      if (activeAgentIdRef.current === sentAgentId) setSending(false);
    }
  }

  return { sendViaEdge, sendFramesLocally };
}
