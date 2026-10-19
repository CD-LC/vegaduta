import type { ActivityStep } from "../components/agents/AgentActivityTrace";

/**
 * What a live chat turn is doing right now, derived from its step events (core's
 * AgentStepEventEmitter) and whether any reply text has arrived. One derivation shared by every
 * chat surface (assistant, playground, workbench lab) so a slow local model reads the same
 * everywhere: "Checking safety → Choosing a model → Gathering context → Model is working →
 * Thinking → Writing", with tool rounds in between.
 *
 * Pure: no React, no clock of its own (the caller passes `now`), never throws.
 */
export type TurnPhase =
  | "starting"
  | "guardrails"
  | "routing"
  | "context"
  | "model"
  | "thinking"
  | "planning"
  | "tool"
  | "afterTool"
  | "recovering"
  | "writing";

export interface TurnProgressState {
  phase: TurnPhase;
  /** The tool in flight, for phase "tool". */
  tool?: string;
  /** The model's reasoning so far (every trailing `thinking` delta joined), for phase "thinking". */
  thinking?: string;
  /** Time since the turn started, when the caller knows it; else the latest server-reported `ms`. */
  elapsedMs: number | null;
}

const SERVER_PHASES: ReadonlySet<string> = new Set(["guardrails", "routing", "context", "model"]);
const CLOSING = new Set(["tool_result", "error"]);

/** Phase names core sends that this build renders; anything else falls back to "model". */
function serverPhase(stage: string | undefined): TurnPhase {
  return stage && SERVER_PHASES.has(stage) ? (stage as TurnPhase) : "model";
}

export function deriveTurnProgress(
  steps: readonly ActivityStep[] | null | undefined,
  opts: { hasText: boolean; startedAt?: number | null; now: number }
): TurnProgressState {
  const list = steps ?? [];
  let elapsedMs: number | null = null;
  if (opts.startedAt != null && Number.isFinite(opts.startedAt)) {
    elapsedMs = Math.max(0, opts.now - opts.startedAt);
  } else {
    for (let i = list.length - 1; i >= 0; i--) {
      const ms = list[i].ms;
      if ((list[i].type === "heartbeat" || list[i].type === "phase") && typeof ms === "number") {
        elapsedMs = ms;
        break;
      }
    }
  }

  // An open tool call outranks everything else: the model is waiting on it, text or no text.
  const closed = new Set<number | string>();
  for (const s of list) if (CLOSING.has(s.type)) closed.add(s.id);
  const openCall = [...list].reverse().find((s) => s.type === "tool_call" && !closed.has(s.id));
  if (openCall) return { phase: "tool", tool: openCall.tool, elapsedMs };

  if (opts.hasText) return { phase: "writing", elapsedMs };

  // The freshest step that says something about where the turn is.
  for (let i = list.length - 1; i >= 0; i--) {
    const step = list[i];
    switch (step.type) {
      case "thinking": {
        let j = i;
        while (j > 0 && list[j - 1].type === "thinking") j--;
        const thinking = list
          .slice(j, i + 1)
          .map((s) => s.message ?? "")
          .join("");
        return { phase: "thinking", thinking: thinking || undefined, elapsedMs };
      }
      case "plan":
        return { phase: "planning", elapsedMs };
      case "tool_result":
        return { phase: "afterTool", elapsedMs };
      case "error":
        return { phase: "recovering", elapsedMs };
      case "phase":
        return { phase: serverPhase(step.stage), elapsedMs };
      case "run_started":
        return { phase: "model", elapsedMs };
      default:
        // heartbeat, memory, guardrail, hook, handoff...: keep looking back.
        break;
    }
  }
  return { phase: "starting", elapsedMs };
}

/** "12 s", "1 m 05 s", "" for unknown. Compact enough for an inline status line. */
export function formatElapsed(ms: number | null | undefined): string {
  if (ms == null || !Number.isFinite(ms)) return "";
  const total = Math.floor(ms / 1000);
  const m = Math.floor(total / 60);
  const s = total % 60;
  if (m > 0) return `${m} m ${String(s).padStart(2, "0")} s`;
  return `${s} s`;
}

/** The end of a reasoning stream, one line, freshest thought last. */
export function thinkingTail(text: string | undefined, max = 160): string {
  const clean = (text ?? "").replace(/\s+/g, " ").trim();
  if (!clean) return "";
  return clean.length <= max ? clean : `…${clean.slice(clean.length - max)}`;
}
