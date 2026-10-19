// Agent Lab - pure helpers over the playground transcript. No React, no
// network: every panel reads the transcript through these so "which turns
// form one exchange" is decided in exactly one place.
import type { LabAgent, LabTurn } from "./types";

export interface LabExchange {
  n: number;               // 1-based ordinal
  userIndex: number;       // index into turns, -1 when the reply has no preceding user turn
  assistantIndex: number;  // index into turns, -1 when the user turn has no reply
  user: LabTurn | null;
  assistant: LabTurn | null;
  isServer: boolean;       // assistant?.ref is set
  pending: boolean;        // assistant?.pending === true
}

/**
 * Pairs the flat transcript into exchanges. A user turn followed directly by
 * an assistant turn is one exchange; a user turn with no reply after it (the
 * send failed before an assistant entry was minted, or two user turns in a
 * row) and an assistant turn with no user turn before it (a watch-loop or
 * greeting reply) each stand alone, with the missing side's index at -1.
 */
export function listExchanges(turns: readonly LabTurn[]): LabExchange[] {
  const exchanges: LabExchange[] = [];
  const push = (userIndex: number, assistantIndex: number) => {
    const user = userIndex >= 0 ? turns[userIndex] : null;
    const assistant = assistantIndex >= 0 ? turns[assistantIndex] : null;
    exchanges.push({
      n: exchanges.length + 1,
      userIndex,
      assistantIndex,
      user,
      assistant,
      isServer: !!assistant?.ref,
      pending: assistant?.pending === true,
    });
  };
  let i = 0;
  while (i < turns.length) {
    const turn = turns[i];
    if (turn.role === "user") {
      const next = turns[i + 1];
      if (next && next.role === "assistant") {
        push(i, i + 1);
        i += 2;
      } else {
        push(i, -1);
        i += 1;
      }
    } else {
      push(-1, i);
      i += 1;
    }
  }
  return exchanges;
}

/** The prompt a lab run tests: the same precedence the playground shows. */
export function promptOf(agent: LabAgent): string {
  return agent.systemPrompt || agent.instructions || "";
}

/** Result length is always <= max. When cut: first max-1 characters plus "…". */
export function clampText(text: string, max: number): string {
  if (text.length <= max) return text;
  if (max <= 0) return "";
  return text.slice(0, max - 1) + "…";
}
