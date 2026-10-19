import type { Dispatch, SetStateAction } from "react";
// Edge parity fix: ChatPanel (used by /playground, a route
// distinct from AgentWorkbench's own embedded AgentPlayground) never attempted
// on-device routing at all - the edge on/off preference set elsewhere had zero
// effect there. Same routeTurn seam AgentPlayground.tsx's sendViaEdge uses.
import {
  edgeEnabled,
  edgeFallbackLabel,
  resolveAutomaticEdgeFallback,
  getEdgeOptIn,
} from "../../lib/edge/capabilities";
import { routeTurn } from "../../lib/edge/tierRouter";
import { fetchEdgeAgentPolicy } from "../../lib/edge/agentPolicy";
import { appendEdgeToolEvent, type ActivityStep } from "../agents/AgentActivityTrace";
import { describeToolCall } from "./toolStatus";

/* eslint-disable @typescript-eslint/no-explicit-any */
export interface EdgeTurnDeps {
  agent: any;
  t: (key: string, values?: Record<string, unknown>) => string;
  setTurns: Dispatch<SetStateAction<any[]>>;
  setSending: (value: boolean) => void;
  setError: (message: string | null) => void;
  setLiveToolCall: (line: string | null) => void;
  /** Attaches an activity-trace snapshot to the last (assistant) turn. */
  publishSteps: (steps: ActivityStep[]) => void;
  beginEdgeAttempt: () => void;
  endEdgeAttempt: () => void;
  askToFallback: (message: string) => Promise<boolean>;
  /** A reply was produced on-device. */
  onReplied: () => void;
  refreshToolCalls: () => unknown;
  refreshMemories: () => unknown;
}

/**
 * ChatPanel's on-device leg of one turn. The caller has already appended the
 * user turn and an empty assistant turn. Returns the message to send on the
 * hosted path (the raw message, or the router's PII-scrubbed copy), or null
 * when the turn ended here: answered on-device, refused by an EDGE_ONLY
 * policy, or a hosted fallback the person declined.
 */
export async function tryEdgeTurn(
  userMessage: string,
  sentAttachments: readonly unknown[],
  deps: EdgeTurnDeps
): Promise<string | null> {
  const {
    agent,
    t,
    setTurns,
    setSending,
    setError,
    setLiveToolCall,
    publishSteps,
    beginEdgeAttempt,
    endEdgeAttempt,
    askToFallback,
    onReplied,
    refreshToolCalls,
    refreshMemories,
  } = deps;
  // Edge Wave 2 (EDGE-A): what actually gets sent on the hosted path — the
  // raw message unless routeTurn hands back a PII-scrubbed copy below.
  let hostedMessage = userMessage;
  // This attempt's on-device tool rounds (see ChatPanel's turnSteps).
  let edgeSteps: ActivityStep[] = [];

  // Try on-device first, same eligibility rule as AgentPlayground's
  // sendViaEdge: plain text only (attachments always take the server
  // path), gated on the edge flag + the person's stored/effective opt-in.
  // Any non-"handled" outcome discards local tokens and falls through to
  // the unchanged streamChat call below, unlike before where the reason
  // (and the fact an attempt happened at all) was invisible.
  //
  // Wave 2 (EDGE-B): an agent whose SERVER policy is EDGE_ONLY must reach
  // routeTurn even when this device's opt-in flag is off — otherwise this
  // pre-gate would silently send the turn hosted, the exact violation the
  // policy forbids (server policy outranks device preference; see
  // tierRouter's header). Cached lookup (60s TTL), so this adds no
  // meaningful latency to the common opted-in path, which short-circuits
  // before it.
  const mustAttemptEdgeForPolicy =
    edgeEnabled() &&
    !getEdgeOptIn() &&
    sentAttachments.length === 0 &&
    (await fetchEdgeAgentPolicy(agent.id))?.policy === "EDGE_ONLY";
  if (edgeEnabled() && (getEdgeOptIn() || mustAttemptEdgeForPolicy) && sentAttachments.length === 0) {
    // Finding #8: real stage feedback for this attempt, mirroring
    // AgentPlayground's sendViaEdge setEdgePhase("checking") entry - every
    // exit below (success, refused, or fallthrough to hosted) pairs with
    // an endEdgeAttempt() call, same as that function's own reset points.
    beginEdgeAttempt();
    let streamed = "";
    let edgeReason = "all-tiers-failed";
    try {
      const result = await routeTurn(userMessage, {
        sessionKey: agent.id,
        // Wave 2: explicit so the router's policy/guardrail lookup never
        // depends on the sessionKey-looks-like-a-UUID heuristic here.
        agentId: agent.id,
        systemPrompt: agent.systemPrompt || agent.instructions || undefined,
        onToken: (delta) => {
          streamed += delta;
          setTurns((t) => {
            const copy = [...t];
            copy[copy.length - 1] = { ...copy[copy.length - 1], content: streamed };
            return copy;
          });
        },
        // On-device tool rounds land in the same trace as server steps,
        // badged "on-device" - a tier-1 tool call executes through the
        // edge tool bridge and was previously invisible until the
        // post-turn refreshToolCalls() read caught up.
        onToolCall: (event) => {
          edgeSteps = appendEdgeToolEvent(edgeSteps, event);
          publishSteps(edgeSteps);
          // The trace is collapsed by default and only shows a finished
          // turn's shape; this is the live line for the round happening
          // right now, so a person is never watching an unexplained pause.
          setLiveToolCall(event.phase === "calling" ? describeToolCall(event, t) : null);
        },
      });
      // `=== true` (not truthiness): with this repo's strict:false
      // tsconfig, only the literal comparison narrows RouteTurnResult's
      // discriminated union so the tail below can read result.reason - see
      // AgentPlayground.tsx's sendViaEdge/sendFramesLocally for the same
      // documented gotcha.
      if (result.handled === true) {
        const steps = edgeSteps;
        setTurns((t) => {
          const copy = [...t];
          copy[copy.length - 1] = { role: "assistant", content: result.text, edgeTier: result.tier, steps };
          return copy;
        });
        setSending(false);
        endEdgeAttempt();
        if (typeof result.text === "string" && result.text.trim()) onReplied();
        // An edge tool call executes server-side (POST
        // /api/edge/tools/execute) and lands in the same tool_call_log rows
        // a hosted turn does - without this, a successful edge tool call is
        // indistinguishable in the UI from "no tool fired".
        void refreshToolCalls();
        void refreshMemories();
        return null;
      }
      edgeReason = result.reason;
      // Edge Wave 2 (EDGE-B): "edge-only-refused" is NOT a hosted
      // fallthrough — this agent's server policy forbids sending the turn
      // to the platform, so surface the refusal and stop. Re-sending
      // hosted here would be exactly the policy violation the mode exists
      // to prevent.
      if (result.reason === "edge-only-refused") {
        setError(edgeFallbackLabel(result.reason));
        setTurns((t) => t.slice(0, -1)); // drop the empty assistant turn
        setSending(false);
        endEdgeAttempt();
        return null;
      }
      // Edge Wave 2 (EDGE-A): prefer the router's PII-scrubbed copy of the
      // message for the hosted re-send when it provided one (only set for
      // guardrail-enabled agents — see RouteTurnResult.scrubbedInput).
      if (result.scrubbedInput) {
        hostedMessage = result.scrubbedInput;
      }
    } catch {
      // routeTurn's contract is to never throw - belt-and-braces only.
    }
    // Not handled: reset the assistant turn (drop any partial local
    // tokens AND any on-device tool rows - they belong to the attempt
    // that was abandoned, not the hosted reply about to replace it) and
    // record why, then fall through to the real server call.
    edgeSteps = [];
    setTurns((t) => {
      const copy = [...t];
      copy[copy.length - 1] = { role: "assistant", content: "", edgeFallbackReason: edgeReason };
      return copy;
    });
    // The on-device attempt is over either way from here (fallthrough to
    // hosted below, or the refusal branch just underneath) - the real
    // server-side RequestStatusIndicator/error path takes over next.
    endEdgeAttempt();

    // Fix #6 (P1): honor the same per-browser edge.fallbackPolicy
    // CallPanel's voice pipeline already does, before silently re-sending
    // hosted - "never" means "show an error instead" everywhere a person
    // can set it, not just on the voice surface it originally shipped on.
    const fallbackAllowed = await resolveAutomaticEdgeFallback(() => askToFallback(edgeFallbackLabel(edgeReason)));
    if (!fallbackAllowed) {
      setError(edgeFallbackLabel(edgeReason));
      setTurns((t) => t.slice(0, -1)); // drop the empty assistant turn
      setSending(false);
      return null;
    }
  }
  return hostedMessage;
}
