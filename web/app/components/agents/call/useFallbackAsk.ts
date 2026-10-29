// The "ask" fallback policy's inline confirm/cancel prompt: a message the UI
// shows (null when nothing is pending), the ref that holds the resolver for
// the Promise askToFallback is currently awaiting, and the asker itself. The
// resolver lives in a ref, not state, so a later button click (or the
// timeout) can settle that Promise without re-running any async function.

import { useRef, useState } from "react";
import { ASK_FALLBACK_TIMEOUT_MS } from "../CallPanel/constants";

export function useFallbackAsk() {
  const [pendingFallbackAsk, setPendingFallbackAsk] = useState<string | null>(null);
  const fallbackAskResolverRef = useRef<((confirmed: boolean) => void) | null>(null);

  /** Shows the inline confirm/cancel prompt and resolves once the person
   * answers (or ASK_FALLBACK_TIMEOUT_MS elapses, which resolves to `false` -
   * silence reads as Cancel, never as consent). Only ever called when the
   * fallback policy is "ask". */
  function askToFallback(message: string): Promise<boolean> {
    return new Promise((resolve) => {
      fallbackAskResolverRef.current = (confirmed: boolean) => {
        fallbackAskResolverRef.current = null;
        setPendingFallbackAsk(null);
        resolve(confirmed);
      };
      setPendingFallbackAsk(message);
      setTimeout(() => {
        fallbackAskResolverRef.current?.(false);
      }, ASK_FALLBACK_TIMEOUT_MS);
    });
  }

  return { pendingFallbackAsk, setPendingFallbackAsk, fallbackAskResolverRef, askToFallback };
}
