"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { assistantApi } from "../../lib/api/assistant";
import type { StatusInput } from "../../connect/connectCatalog";

const UNKNOWN: StatusInput = { channels: null, googleConnected: null };

/**
 * What is connected right now: the same two reads the /connect page makes (channel status and the Google
 * connection), quiet when they fail (unknown never blocks a request). Re-read when the tab regains focus,
 * which is how a person who connected something in another tab is noticed. `refresh` reads fresh and
 * returns the answer for the caller that cannot wait for a render.
 */
export function useConnections(enabled: boolean) {
  const [status, setStatus] = useState<StatusInput>(UNKNOWN);
  const latest = useRef<StatusInput>(UNKNOWN);

  const refresh = useCallback(async (): Promise<StatusInput> => {
    const [channels, google] = await Promise.all([
      assistantApi.getChannelsStatusFresh().then((r) => r?.channels ?? null, (): null => null),
      assistantApi.isGoogleConnectedFresh().then((c) => c, (): null => null),
    ]);
    const next: StatusInput = { channels, googleConnected: google };
    latest.current = next;
    setStatus(next);
    return next;
  }, []);

  useEffect(() => {
    if (!enabled) return undefined;
    void refresh();
    const onFocus = () => {
      if (document.visibilityState === "visible") void refresh();
    };
    window.addEventListener("focus", onFocus);
    document.addEventListener("visibilitychange", onFocus);
    return () => {
      window.removeEventListener("focus", onFocus);
      document.removeEventListener("visibilitychange", onFocus);
    };
  }, [enabled, refresh]);

  return { status, latest, refresh };
}
