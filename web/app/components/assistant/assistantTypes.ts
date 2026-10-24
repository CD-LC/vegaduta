import type { ActivityStep } from "../agents/AgentActivityTrace";
import type { SentAttachment } from "../ChatAttachments";
import type { LlmRequestSnapshot } from "../agents/RequestInspector";
import type { Need } from "./needs";

// Shared shapes of the /assistant surface (docs/architecture/PERSONAL-ASSISTANT-SURFACE-2026-10-17.md,
// Phase 1). One transcript entry per user message or assistant reply; the activity trace of a reply
// (`steps`) is what WorkedForBlock folds into the "Working for ..." row.

export type AssistantTurnRole = "user" | "assistant";

/** Work the surface handed to something that outlives the turn (rungs 2 to 4 of the dispatch ladder). */
export type HandoffKind = "job" | "research" | "schedule";

export interface AssistantHandoff {
  kind: HandoffKind;
  /** Server id of the job, research run or scheduled task; null when the server did not return one. */
  id: string | null;
  /** The request as handed over (the goal, the question, the routine's prompt). */
  title: string;
  /** A short second line, e.g. the next run of a routine. */
  detail?: string | null;
  /** Where the person can follow it in the existing console. */
  href?: string | null;
}

/** A request held back because something it needs is not connected yet (needs.ts). */
export interface AssistantConnect {
  needs: Need[];
  /** The request as typed; sent as it is once the person connects, or chooses to go on without. */
  message: string;
  /** The user entry the request was echoed as; removed when the request is sent for real. */
  userTurnId: string;
  /** The person said they connected it but it still reads as not connected. */
  stillMissing?: boolean;
}

/** Where a Quick Task's result goes (core QuickTaskChannel); LOG needs no phone number. */
export type TaskChannel = "WHATSAPP" | "SMS" | "CALL" | "LOG";

/** What POST /api/quick-task/preview said it would do; fields are the ones the card shows. */
export interface TaskPreview {
  agentId?: string | null;
  agentName?: string | null;
  providerName?: string | null;
  model?: string | null;
  detectedTools?: { name: string }[] | null;
  thirdPartyNumber?: string | null;
}

/** A one-shot Quick Task carried by one assistant entry: the plan to confirm, then the run and its outcome. */
export interface AssistantTask {
  /** The request as typed. */
  message: string;
  /** The agent the person chose; null lets the server pick. */
  agentId: string | null;
  channel: TaskChannel;
  status: "previewing" | "confirm" | "running" | "done" | "failed";
  preview: TaskPreview | null;
  /** The task id once the run started (same id as /quick-task/history). */
  taskId?: string | null;
  resultText?: string | null;
  deliveryNote?: string | null;
  error?: string | null;
  /** The person has no phone number on file, so only LOG can deliver. */
  needsPhone?: boolean;
}

export interface AssistantTurn {
  id: string;
  role: AssistantTurnRole;
  content: string;
  /** Epoch ms when the entry was created on this client (or the server timestamp for a restored one). */
  at: number;
  /** Live activity trace (server step events), assistant turns only. */
  steps?: ActivityStep[];
  /** When the reply started streaming and when it settled; `endedAt` stays null while live. */
  startedAt?: number;
  endedAt?: number | null;
  /** While live: when the stream last said anything (a chunk, a step event or a heartbeat). */
  lastEventAt?: number | null;
  /** The reply failed before any text arrived; `content` then holds the plain-language reason. */
  failed?: boolean;
  /** Display copies of what the person attached (no file bytes kept). */
  attachments?: SentAttachment[];
  /** Client turn ref sent with the request (feedback, eval import). */
  ref?: string;
  /** Request X-ray: the snapshots core captured as the last step before the
   * provider adapter for this reply's model calls — the final prompt, the
   * sampling options and the tool list. Populated after the turn settles
   * (same getAgentSessionLlmRequests read the playground does). */
  requests?: LlmRequestSnapshot[];
  /** Provider-reported usage summed over this reply's model calls. */
  usage?: {
    promptTokens: number;
    completionTokens: number;
    totalTokens: number;
    estimatedCostUsd: number;
    /** Provider/model pairs that actually served the calls (fallbacks show). */
    poweredBy?: { providerName: string | null; model: string | null }[];
  };
  /** A quiet one-line note under the entry (a rung that degraded to a plain turn, a restored thread). */
  note?: string | null;
  /** Set on an assistant entry that is a hand-off card rather than a reply. */
  handoff?: AssistantHandoff;
  /** Set on an assistant entry that asks the person to connect something first. */
  connect?: AssistantConnect;
  /** Set on an assistant entry that is a Quick Task card (plan, run, result). */
  task?: AssistantTask;
}

/**
 * How the box sends what is typed (the merged Playground / Jobs / Quick Task surface, 2026-10-18):
 * `chat` is one turn with the selected agent; `background` hands the request to an agent job
 * (rung 2) at once; `task` is a one-shot Quick Task - the server picks the best agent unless one is
 * selected, previews a plan, and can deliver the result to a channel.
 */
export type ComposerMode = "chat" | "background" | "task";
export const COMPOSER_MODES: readonly ComposerMode[] = ["chat", "background", "task"];

export function parseComposerMode(value: string | null | undefined): ComposerMode {
  return value === "background" || value === "task" ? value : "chat";
}

/** A thread as the left rail lists it (threadIndex.ts owns the storage). */
export interface ThreadSummary {
  id: string;
  title: string;
  agentId: string;
  lastAt: number;
  pinned: boolean;
}
