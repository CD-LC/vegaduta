"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { assistantApi, type AssistantWorkspace } from "../../lib/api/assistant";

const STORAGE_KEY = "vd.assistant.folder";

function readStored(): string | null {
  try {
    return window.localStorage.getItem(STORAGE_KEY);
  } catch {
    return null;
  }
}

function writeStored(name: string | null) {
  try {
    if (name) window.localStorage.setItem(STORAGE_KEY, name);
    else window.localStorage.removeItem(STORAGE_KEY);
  } catch {
    /* storage blocked: the choice lasts for this page only */
  }
}

/**
 * The projects folder (GET /api/assistant/workspace) and the folder attached in the picker. The
 * attachment is remembered in this browser (a convenience: the server keeps no such state) and
 * dropped when that folder no longer exists. `latest` is read at send time by the dispatcher.
 */
export function useWorkspaceFolder(ready: boolean) {
  const [workspace, setWorkspace] = useState<AssistantWorkspace | null>(null);
  const [folder, setFolder] = useState<string | null>(null);
  const latest = useRef<string | null>(null);
  latest.current = folder;

  const refresh = useCallback(async () => {
    const ws = await assistantApi.getWorkspace().catch((): null => null);
    setWorkspace(ws);
    const stored = readStored();
    const exists = !!ws?.available && !!stored && ws.folders.some((f) => f.name === stored);
    setFolder(exists ? stored : null);
    if (!exists && stored && ws?.available) writeStored(null);
    return ws;
  }, []);

  useEffect(() => {
    if (ready) void refresh();
  }, [ready, refresh]);

  const attach = useCallback((name: string | null) => {
    setFolder(name);
    writeStored(name);
  }, []);

  const create = useCallback(
    async (name: string) => {
      const made = await assistantApi.createWorkspaceFolder(name);
      await refresh();
      attach(made.name);
    },
    [refresh, attach]
  );

  return { workspace, folder, latest, attach, create, refresh };
}
