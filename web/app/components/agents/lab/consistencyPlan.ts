// Agent Lab - what a Consistency check sends. Pure: no React, no network.
// One question, asked k times with identical input; the server then has an
// LLM judge compare every pair of answers (labApi's LabSimilarity).
import {
  LAB_MAX_HISTORY_CHARS,
  LAB_MAX_HISTORY_MESSAGES,
  type LabCellRequest,
  type LabMessage,
  type LabRunRequest,
} from "./labApi";
import { clampText, listExchanges } from "./labTurns";
import type { LabTurn } from "./types";

/** Everything needed to rebuild the request and to read its results back.
 * Stored in sessionStorage by useLabRun, so it stays plain JSON. */
export interface ConsistencyContext {
  message: string;
  history: LabMessage[];
  k: number;
  /** The playground turn this question came from called a tool. Samples
   * never do, so the results carry a warning. */
  usedTools: boolean;
  /** The run answered with the agent's real tools, so the "samples are generated without tools" note does not apply. */
  withTools?: boolean;
}

/** k cells keyed k0..k{k-1}, all with the same message and history.
 * promptIndex null = the agent's stored prompt, hence no systemPrompts. */
export function buildConsistencyRequest(ctx: ConsistencyContext): LabRunRequest {
  const cells: LabCellRequest[] = [];
  for (let n = 0; n < ctx.k; n++) {
    cells.push({ key: `k${n}`, promptIndex: null, message: ctx.message, history: ctx.history });
  }
  return { kind: "CONSISTENCY", systemPrompts: [], cells, scoring: "NONE", similarity: "JUDGE" };
}

/**
 * The conversation before the user turn at `userIndex`, as the server would
 * have it: complete server exchanges only (a user message answered by an
 * assistant turn that has a server ref, has content and is not still
 * streaming), oldest first. On-device, unanswered and watch-loop turns never
 * reached the server session, so they are left out.
 *
 * Whole exchanges are kept, newest last, up to `maxMessages` messages, so the
 * history never opens on an answer with its question cut off. Each content is
 * clamped to `maxChars` - the same limits core applies (labApi LAB_MAX_*).
 */
export function historyBefore(
  turns: readonly LabTurn[],
  userIndex: number,
  maxMessages: number = LAB_MAX_HISTORY_MESSAGES,
  maxChars: number = LAB_MAX_HISTORY_CHARS
): LabMessage[] {
  const pairs: [LabMessage, LabMessage][] = [];
  for (const exchange of listExchanges(turns)) {
    const { user, assistant } = exchange;
    if (!user || !assistant) continue;
    if (exchange.assistantIndex >= userIndex) continue;
    if (!exchange.isServer || exchange.pending) continue;
    if (!user.content.trim() || !assistant.content.trim()) continue;
    pairs.push([
      { role: "user", content: clampText(user.content, maxChars) },
      { role: "assistant", content: clampText(assistant.content, maxChars) },
    ]);
  }
  const keepPairs = Math.max(0, Math.floor(maxMessages / 2));
  return keepPairs === 0 ? [] : pairs.slice(-keepPairs).flat();
}
