// agentkit — the on-device model adapter: an AgentModel over lib/edge.
//
// READY-ONLY, like the rest of /code (lib/localcode/ask.ts resolveReadyTier is
// the model for this): it answers only for weights ALREADY on the device.
// Starting an agent run never starts a model download. It may load an
// already-downloaded tier-1 engine into GPU memory, which is the same warm-up
// asking a question pays.
//
// Privacy: the prompt assembled by context.ts contains the person's source. It
// is handed to a local session and nowhere else. What does cross the network on
// this path is what already does for /code and is documented in
// LocalCodePanel's header: the public edge model MANIFEST, and the engine's own
// weight loader. Neither carries the prompt.
//
// TIER 1 IS OPENED WITH `disableTools: true` (CreateTier1SessionOptions,
// 2026-09-19). A default tier-1 session runs its OWN tool loop: it prepends its
// own tool list (local_calculate, …) to the system prompt, constrains the reply
// with a structural tag, and parses every reply for <tool_call> or a bare
// {"name", "arguments"} envelope — which would swallow the calls meant for
// agentkit's loop and spend a 4k window on tool schemas this agent never uses.
// With the option set, tier1.ts documents that none of that happens and the
// model's text comes back verbatim. That is what lets this adapter ask for the
// <tool_call> delimiter Hermes- and Qwen-family models were fine-tuned on
// (TIER1_CALL_TAG below) instead of a made-up one.
// NOT verified against a real model: whether a given 1–3B model emits the
// envelope reliably is unmeasured; protocol.ts repairs and re-prompts when it
// does not.
//
// One fresh session per step, destroyed straight after: AgentModel is stateless
// by contract (types.ts), and destroy() only drops the conversation — the
// engine and its loaded weights stay warm for the next step.

import { detectCapabilities, edgeEnabled } from "../edge/capabilities";
import { Tier0Error, createTier0Session, tier0Availability } from "../edge/tier0";
import {
  Tier1Error,
  WEBLLM_FALLBACK_CONTEXT_WINDOW,
  computeTier1PromptBudget,
  createTier1Session,
  getTier1RunModel,
  resolveTier1ContextWindow,
} from "../edge/tier1";
import { AGENT_CALL_TAG } from "./protocol";
import type { AgentModel, ModelReply } from "./types";

/** The delimiter asked of tier-1 (WebLLM) models — see the header. */
export const TIER1_CALL_TAG = "tool_call";

export type EdgeModelUnavailable = {
  ok: false;
  /** Plain-language next step. */
  detail: string;
  reason: "edge-off" | "no-model" | "probe-failed";
};

function isAborted(err: unknown): boolean {
  if ((err instanceof Tier0Error || err instanceof Tier1Error) && err.code === "aborted") return true;
  return err instanceof DOMException && err.name === "AbortError";
}

function failureFrom(err: unknown, signal?: AbortSignal): ModelReply {
  if (isAborted(err) || signal?.aborted) return { ok: false, reason: "aborted", detail: "Stopped." };
  if (err instanceof Tier1Error && err.code === "context-window-exceeded") {
    return {
      ok: false,
      reason: "too-large",
      detail: "The on-device engine judged this step too large for its context window. Continue to retry with a tighter window, or use a larger model.",
    };
  }
  const message = err instanceof Error && err.message ? err.message : "";
  return {
    ok: false,
    reason: "failed",
    // Tier0Error/Tier1Error messages are already written for people. Anything
    // else gets a generic line — a raw engine error can quote the prompt.
    detail:
      err instanceof Tier0Error || err instanceof Tier1Error
        ? message || "The on-device model could not reply."
        : "The on-device model could not reply. Nothing was changed.",
  };
}

/**
 * The model that would run an agent step RIGHT NOW without downloading
 * anything, or a reason there is none. Never throws, never downloads.
 */
export async function resolveEdgeAgentModel(): Promise<{ ok: true; model: AgentModel } | EdgeModelUnavailable> {
  try {
    if (typeof window === "undefined" || !edgeEnabled()) {
      return { ok: false, reason: "edge-off", detail: "On-device AI is switched off for this deployment, so the agent cannot run here." };
    }
    const caps = await detectCapabilities();
    // The SAME complexity signal the session below is opened with: tier 1 picks
    // its model from it, and a window read from one model while another runs the
    // step would make every budget number here a lie.
    const tier1 = await getTier1RunModel(caps, "complex");
    if (tier1) {
      // The engine's OWN record of the window (admin manifest value → the
      // installed WebLLM model record → 4096) and its OWN prompt budget — the
      // same numbers its gate will check this prompt against. Never a guess.
      const contextWindow = resolveTier1ContextWindow(tier1);
      return {
        ok: true,
        model: {
          id: tier1.id,
          contextWindow,
          promptBudget: computeTier1PromptBudget(contextWindow),
          callTag: TIER1_CALL_TAG,
          async complete(system, user, signal): Promise<ModelReply> {
            try {
              if (signal?.aborted) return { ok: false, reason: "aborted", detail: "Stopped." };
              const session = await createTier1Session(caps, {
                systemPrompt: system,
                turnComplexity: "complex",
                // agentkit runs its own tool loop; tier 1 must not run a second one.
                disableTools: true,
              });
              try {
                const text = await session.prompt(user, undefined, signal);
                return text && text.trim() ? { ok: true, text } : { ok: false, reason: "empty", detail: "The on-device model returned an empty reply." };
              } finally {
                try {
                  session.destroy();
                } catch {
                  // Teardown only — the reply already exists.
                }
              }
            } catch (err) {
              return failureFrom(err, signal);
            }
          },
        },
      };
    }
    if ((await tier0Availability()) === "available") {
      // Chrome's Prompt API reports no context window to this codebase
      // (tier0.ts reads none), so there is no honest number for it. Budget
      // against tier 1's documented last-resort window, exactly as
      // lib/localcode/ask.ts does, and let Chrome's own quota error be the
      // backstop if that under-reads the real one.
      return {
        ok: true,
        model: {
          id: "chrome-built-in",
          contextWindow: WEBLLM_FALLBACK_CONTEXT_WINDOW,
          promptBudget: computeTier1PromptBudget(WEBLLM_FALLBACK_CONTEXT_WINDOW),
          // tier0.ts has no tool protocol of its own to collide with, and
          // nothing here records what delimiter Chrome's built-in model was
          // trained on, so it keeps agentkit's neutral default.
          callTag: AGENT_CALL_TAG,
          async complete(system, user, signal): Promise<ModelReply> {
            try {
              if (signal?.aborted) return { ok: false, reason: "aborted", detail: "Stopped." };
              const session = await createTier0Session(system);
              try {
                const text = await session.prompt(user, undefined, signal);
                return text && text.trim() ? { ok: true, text } : { ok: false, reason: "empty", detail: "The on-device model returned an empty reply." };
              } finally {
                try {
                  session.destroy();
                } catch {
                  // Teardown only.
                }
              }
            } catch (err) {
              return failureFrom(err, signal);
            }
          },
        },
      };
    }
    return {
      ok: false,
      reason: "no-model",
      detail: "No on-device model is downloaded yet. Open VegaDūta Edge (/edge) and download one — the agent never starts a download on its own.",
    };
  } catch {
    return { ok: false, reason: "probe-failed", detail: "This device could not be checked for an on-device model. Reload the page and try again." };
  }
}
