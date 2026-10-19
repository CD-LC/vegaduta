import { useEffect, useState } from "react";
import { api } from "../../lib/api";
import { auth } from "../../lib/auth";

/**
 * The tool-call log and memory list under the chat: state plus the fetchers
 * the turn path calls after a reply. Failures never surface as chat errors.
 */
export function useChatSideData(agent: { id: string } | null | undefined, sessionId: string | null) {
  const [toolCalls, setToolCalls] = useState<any[]>([]);
  const [memories, setMemories] = useState<any[]>([]);

  // With a real Keycloak session the user id is the subject key; otherwise the
  // conversation's sessionId (same "session-scoped until a stronger identity
  // exists" behaviour as the backend's AgentController).
  function currentSubjectKey() {
    return auth.getUserId() || sessionId;
  }

  async function refreshToolCalls() {
    if (!agent) return;
    try {
      const calls = await api.listToolCalls(agent.id);
      setToolCalls(calls);
    } catch {
      // Tool-call log is a nice-to-have panel - don't surface this as a chat error.
    }
  }

  async function refreshMemories() {
    const subjectKey = currentSubjectKey();
    if (!agent || !subjectKey) return;
    try {
      const rows = await api.listMemories(agent.id, subjectKey);
      setMemories(rows);
    } catch {
      // Same posture as refreshToolCalls - a missing/disabled memory feature
      // shouldn't surface as a chat error, it just shows "no memories yet".
    }
  }

  async function handleForget(memoryId: string) {
    if (!agent) return;
    try {
      await api.forgetMemory(agent.id, memoryId);
      setMemories((m) => m.filter((entry) => entry.id !== memoryId));
    } catch {
      // Leave the entry visible if the delete failed - refreshMemories() will
      // reconcile on the next chat turn either way.
    }
  }

  // With a real Keycloak session, subjectKey (the user id) is stable from the
  // moment an agent is selected - no need to wait for a sessionId to exist,
  // unlike tool-calls which are inherently tied to sessions that don't exist
  // until the first message is sent.
  useEffect(() => {
    void refreshMemories();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [agent?.id]);

  return { toolCalls, setToolCalls, memories, setMemories, refreshToolCalls, refreshMemories, handleForget };
}
