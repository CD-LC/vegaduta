"use client";

import { useCallback, useEffect, useState } from "react";
import { auth } from "../../lib/auth";
import type { ThreadSummary } from "./assistantTypes";
import { deleteThread, listThreads, patchThread, recordThreadActivity, threadScope } from "./threadIndex";

/** The left rail's data: the local thread index for the signed-in user, kept in sync with what the pane does. */
export function useAssistantThreads() {
  const [scope, setScope] = useState<string>(() => threadScope(auth.getUserId?.()));
  const [threads, setThreads] = useState<ThreadSummary[]>([]);
  const [loaded, setLoaded] = useState(false);

  // The user id resolves after Keycloak settles; re-scope once it does (checked on each render of the
  // pane, which re-renders as the session loads; the state only changes when the scope really moves).
  useEffect(() => {
    const next = threadScope(auth.getUserId?.());
    if (next !== scope) setScope(next);
  }, [scope]);

  const reload = useCallback(async () => {
    const rows = await listThreads(scope);
    setThreads(rows);
    setLoaded(true);
  }, [scope]);

  useEffect(() => {
    void reload();
  }, [reload]);

  /** Called when a turn settles with a known session id. */
  const noteActivity = useCallback(
    async (sessionId: string, agentId: string, firstUserLine: string, at = Date.now()) => {
      await recordThreadActivity(scope, sessionId, agentId, firstUserLine, at);
      await reload();
    },
    [scope, reload]
  );

  const togglePinned = useCallback(
    async (id: string) => {
      const row = threads.find((t) => t.id === id);
      if (!row) return;
      await patchThread(scope, id, { pinned: !row.pinned });
      await reload();
    },
    [scope, threads, reload]
  );

  const rename = useCallback(
    async (id: string, title: string) => {
      await patchThread(scope, id, { title });
      await reload();
    },
    [scope, reload]
  );

  const remove = useCallback(
    async (id: string) => {
      await deleteThread(scope, id);
      await reload();
    },
    [scope, reload]
  );

  return { scope, threads, loaded, noteActivity, togglePinned, rename, remove, reload };
}
