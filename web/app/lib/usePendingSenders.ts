"use client";

import { useEffect, useState } from "react";
import { api } from "./api";

// Count of channel senders waiting for approval (GET /api/channels/pairing/
// pending). AppShell remounts on every route change, so the count is cached at
// module level for a minute instead of refetched per navigation. Nothing else
// notifies the owner that a new sender is waiting, so this feeds the nav badge.
const TTL_MS = 60_000;
let cached: { at: number; count: number } | null = null;

export function usePendingSenders(enabled: boolean): number {
  const [count, setCount] = useState<number>(cached?.count ?? 0);

  useEffect(() => {
    if (!enabled) return undefined;
    if (cached && Date.now() - cached.at < TTL_MS) {
      setCount(cached.count);
      return undefined;
    }
    let alive = true;
    api
      .listPendingPairings()
      .then((rows: unknown) => {
        const n = Array.isArray(rows) ? rows.length : 0;
        cached = { at: Date.now(), count: n };
        if (alive) setCount(n);
      })
      .catch(() => {});
    return () => {
      alive = false;
    };
  }, [enabled]);

  return enabled ? count : 0;
}
