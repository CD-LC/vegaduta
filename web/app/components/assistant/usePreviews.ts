"use client";

import { useCallback, useEffect, useState } from "react";
import { assistantApi, type WorkspacePreview } from "../../lib/api/assistant";

const POLL_MS = 5_000;

/**
 * The dev servers the Assistant has running (GET /api/assistant/workspace/previews): read at once when
 * a turn settles (`refreshKey`) and every few seconds while the tab is visible, only where the build
 * terminal exists. Quiet, so an install without one simply has no Preview pane.
 */
export function usePreviews(enabled: boolean, refreshKey: unknown) {
  const [previews, setPreviews] = useState<WorkspacePreview[]>([]);

  const refresh = useCallback(async () => {
    setPreviews((await assistantApi.listPreviews().catch((): WorkspacePreview[] => [])).filter((p) => p.running));
  }, []);

  useEffect(() => {
    if (!enabled) return undefined;
    void refresh();
    const timer = setInterval(() => {
      if (document.visibilityState === "visible") void refresh();
    }, POLL_MS);
    return () => clearInterval(timer);
  }, [enabled, refreshKey, refresh]);

  const stop = useCallback(
    async (port: number) => {
      await assistantApi.stopPreview(port).catch((): void => undefined);
      await refresh();
    },
    [refresh]
  );

  return { previews, stop, refresh };
}
