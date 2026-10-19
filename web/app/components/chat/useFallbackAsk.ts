"use client";

import { useCallback, useRef, useState } from "react";
import { EDGE_FALLBACK_ASK_TIMEOUT_MS } from "../../lib/edge/capabilities";

/**
 * The "ask me first" edge.fallbackPolicy prompt (Fix #6, P1) - same
 * shape/timeout as CallPanel's own pendingFallbackAsk/askToFallback, so a
 * routeTurn fallthrough honors that per-browser preference instead of always
 * falling through to the platform silently.
 */
export function useFallbackAsk() {
  const [pendingFallbackAsk, setPendingFallbackAsk] = useState<string | null>(null);
  const fallbackAskResolverRef = useRef<((confirmed: boolean) => void) | null>(null);
  function askToFallback(message: string): Promise<boolean> {
    return new Promise((resolve) => {
      fallbackAskResolverRef.current = (confirmed: boolean) => {
        fallbackAskResolverRef.current = null;
        setPendingFallbackAsk(null);
        resolve(confirmed);
      };
      setPendingFallbackAsk(message);
      setTimeout(() => fallbackAskResolverRef.current?.(false), EDGE_FALLBACK_ASK_TIMEOUT_MS);
    });
  }
  const answerFallbackAsk = (confirmed: boolean) => fallbackAskResolverRef.current?.(confirmed);
  // Drops a pending prompt (it belongs to the previous agent's turn).
  const resetFallbackAsk = useCallback(() => {
    setPendingFallbackAsk(null);
    fallbackAskResolverRef.current = null;
  }, []);
  return { pendingFallbackAsk, askToFallback, answerFallbackAsk, resetFallbackAsk };
}
