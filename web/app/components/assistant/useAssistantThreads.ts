"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { assistantApi } from "../../lib/api/assistant";
import { auth } from "../../lib/auth";
import type { ThreadSummary } from "./assistantTypes";
import {
  deleteThread,
  listThreads,
  mergeServerThreads,
  patchThread,
  recordThreadActivity,
  threadScope,
} from "./threadIndex";

/**
 * The left rail's data: the local thread index for the signed-in user, kept in sync with what the pane does.
 * The local index is read first (instant), then the server's list (GET /api/chat/threads, when this core
 * stores sessions) is merged into it once per scope, so history shows on a new browser or device.
 */
export function useAssistantThreads() {
  const [scope, setScope] = useState<string>(() => threadScope(auth.getUserId?.()));
  const [threads, setThreads] = useState<ThreadSummary[]>([]);
  const [loaded, setLoaded] = useState(false);
  const syncedScopeRef = useRef<string | null>(null);

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

  // Best-effort server merge, once per scope, after the local rows are showing. A core with branching
  // off answers 404 (listThreads resolves null): the local list is all there is, and that is fine.
  useEffect(() => {
    if (!loaded || syncedScopeRef.current === scope) return undefined;
    syncedScopeRef.current = scope;
    let cancelled = false;
    void (async () => {
      const page = await assistantApi.listThreads();
      if (!page || cancelled) return;
      const written = await mergeServerThreads(scope, page.threads);
      if (written > 0 && !cancelled) await reload();
    })();
    return () => {
      cancelled = true;
    };
  }, [loaded, scope, reload]);

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

  /** Removes the thread here and, when the server stores it, there too (quiet: a failure still removes it locally). */
  const remove = useCallback(
    async (id: string) => {
      const row = threads.find((t) => t.id === id);
      if (row?.agentId) await assistantApi.deleteServerThread(row.agentId, id);
      await deleteThread(scope, id);
      await reload();
    },
    [scope, threads, reload]
  );

  return { scope, threads, loaded, noteActivity, togglePinned, rename, remove, reload };
}
