import { chatBranchingApi } from "../../lib/api/chatBranching";

/**
 * Keeps the Assistant's conversations on the server. The conversation tree (core chat branching) learns
 * about turns lazily: a session is only recorded when something reads its active path, which the chat
 * page does and the Assistant did not - so with the flag on nothing of its chats was stored, and
 * GET /api/chat/threads had nothing to list. After each settled turn the Assistant now asks for the path
 * once, best-effort, purely so the turn is written down. The answer is never applied to the rendered turns.
 *
 * The capability is read once per agent for the page's life (quiet on 403/404: an older core, the flag
 * off, or no run access all mean "nothing to persist"). The read waits a moment first because the newest
 * reply reaches Redis just after the stream ends (useChatBranching's SYNC_RETRY_MS, same reason).
 */
export const PERSIST_DELAY_MS = 700;

const capabilityByAgent = new Map<string, Promise<boolean>>();

/** Whether this core records `agentId`'s sessions; cached per agent, never rejects. */
export function branchingEnabledFor(agentId: string): Promise<boolean> {
  let known = capabilityByAgent.get(agentId);
  if (known === undefined) {
    known = Promise.resolve()
      .then(() => chatBranchingApi.getChatBranchingCapabilities(agentId))
      .then((caps) => caps?.enabled === true)
      .catch(() => false);
    capabilityByAgent.set(agentId, known);
  }
  return known;
}

/**
 * Records the session's newest turns in the server tree. Resolves true when the read was made, false when
 * branching is off or anything failed; never throws and never surfaces an error.
 */
export async function persistTurnInBranchTree(agentId: string, sessionId: string, delayMs = PERSIST_DELAY_MS): Promise<boolean> {
  try {
    if (!agentId || !sessionId) return false;
    const enabled = await branchingEnabledFor(agentId);
    if (!enabled) return false;
    if (delayMs > 0) await new Promise((resolve) => setTimeout(resolve, delayMs));
    await chatBranchingApi.getBranchPath(agentId, sessionId);
    return true;
  } catch {
    return false;
  }
}

/** Test hook: forget the cached capabilities. */
export function resetBranchPersistForTests(): void {
  capabilityByAgent.clear();
}
