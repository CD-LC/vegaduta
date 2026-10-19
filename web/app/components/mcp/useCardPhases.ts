"use client";

import { useEffect, useRef, useState } from "react";

/**
 * Per-card transitional state ("adding" | "connecting" | "enabling" | "testing") shown on the grid
 * tile. "testing" is cleared as soon as a fresh servers/catalog fetch shows the card left its
 * transient state, or by a safety timeout so a card never sticks on "Testing connection...".
 */
export function useCardPhases(cards: any[], servers: any[], catalog: any[]) {
  const [cardPhase, setCardPhase] = useState<Record<string, string>>({});
  const phaseTimers = useRef<Record<string, any>>({});
  useEffect(
    () => () => {
      Object.values(phaseTimers.current).forEach(clearTimeout);
    },
    []
  );

  function markPhase(selKey: string | null | undefined, phase: string) {
    if (!selKey) return;
    setCardPhase((prev) => ({ ...prev, [selKey]: phase }));
  }

  function clearPhase(selKey: string | null | undefined) {
    if (!selKey) return;
    if (phaseTimers.current[selKey]) {
      clearTimeout(phaseTimers.current[selKey]);
      delete phaseTimers.current[selKey];
    }
    setCardPhase((prev) => {
      if (!(selKey in prev)) return prev;
      const next = { ...prev };
      delete next[selKey];
      return next;
    });
  }

  // "testing" is the phase between a successful register/connect call and
  // the async connection attempt actually landing on the registration - the
  // effect below clears it as soon as a fresh fetch shows the real outcome;
  // this timeout is only the fallback for a core that never updates the
  // status (older core, or a connection attempt that hangs indefinitely).
  function markTesting(selKey: string | null | undefined) {
    if (!selKey) return;
    markPhase(selKey, "testing");
    if (phaseTimers.current[selKey]) clearTimeout(phaseTimers.current[selKey]);
    phaseTimers.current[selKey] = setTimeout(() => clearPhase(selKey), 9000);
  }

  // Once a fresh servers/catalog fetch lands, drop any card still marked
  // "testing" whose real connection state is no longer transient (dynamic
  // servers report CONNECTING while the attempt is in flight; once that
  // flips to CONNECTED/ERROR, or the card gained a usageStatus at all for a
  // non-dynamic registration, the steady-state dot/ReadinessBadge already
  // covers it and the transitional badge would be redundant).
  useEffect(() => {
    setCardPhase((prev) => {
      const testingKeys = Object.keys(prev).filter((k) => prev[k] === "testing");
      if (testingKeys.length === 0) return prev;
      let changed = false;
      const next = { ...prev };
      testingKeys.forEach((selKey) => {
        const card = cards.find((c: any) => c.selKey === selKey);
        if (!card || card.kind !== "mcp") return;
        const s = card.data;
        const stillConnecting = s.dynamic && s.connectionStatus === "CONNECTING";
        if (!stillConnecting) {
          delete next[selKey];
          changed = true;
          if (phaseTimers.current[selKey]) {
            clearTimeout(phaseTimers.current[selKey]);
            delete phaseTimers.current[selKey];
          }
        }
      });
      return changed ? next : prev;
    });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [servers, catalog]);

  return { cardPhase, markPhase, clearPhase, markTesting };
}
