"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { REOPEN_HINT_MS } from "./storage";

/**
 * The "reopen it from the ? button" note shown after a visitor bails out of
 * the tour, for REOPEN_HINT_MS.
 */
export function useReopenHint() {
  const [reopenHintVisible, setReopenHintVisible] = useState(false);
  // Shows the "reopen it from the ? button" note for REOPEN_HINT_MS. One timer,
  // restarted on every call and cleared on unmount, so a fast skip -> replay ->
  // skip cannot leave two timers racing to hide a note the second one owns.
  const hintTimerRef = useRef<number | undefined>(undefined);
  const dismissReopenHint = useCallback(() => {
    window.clearTimeout(hintTimerRef.current);
    setReopenHintVisible(false);
  }, []);
  const showReopenHint = useCallback(() => {
    window.clearTimeout(hintTimerRef.current);
    setReopenHintVisible(true);
    hintTimerRef.current = window.setTimeout(() => setReopenHintVisible(false), REOPEN_HINT_MS);
  }, []);
  useEffect(() => () => window.clearTimeout(hintTimerRef.current), []);
  return { reopenHintVisible, dismissReopenHint, showReopenHint };
}
