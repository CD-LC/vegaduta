"use client";

import { useEffect, useState } from "react";
import { api } from "../../lib/api";

// Whether this deployment has the desktop-recipe feature switched on. Core ships it OFF by default and
// there is no dedicated probe endpoint, so availability is decided by one quiet list read: any answer
// (even an empty list) means the feature exists; a 404/503 (or any failure, e.g. the desktop sidecar
// being down) means "not available", and every consumer renders nothing - a deployment that never
// enabled it looks exactly as before, same contract as useBrowserCapabilities.
//
// A successful answer is remembered once per page load (module-level promise), however many cards ask;
// a failed one is NOT cached, so the next mount retries - a transient 503 while the sidecar restarts
// must not hide the cards until a full page reload.

let pending: Promise<boolean> | null = null;

export function loadDesktopAvailability(): Promise<boolean> {
  if (pending === null) {
    // try/await so a synchronous throw (an api object without this method, as in a narrow test double)
    // is the same "unavailable" answer as a failed request.
    const attempt = (async (): Promise<boolean> => {
      try {
        await api.listDesktopRecipes();
        return true;
      } catch {
        return false;
      }
    })();
    pending = attempt;
    void attempt.then((ok) => {
      if (!ok && pending === attempt) pending = null;
    });
  }
  return pending;
}

/** Test seam: forget the cached answer. */
export function resetDesktopAvailabilityCache(): void {
  pending = null;
}

/** undefined while loading, false when unavailable, true when the feature answers. */
export function useDesktopAvailability(): boolean | undefined {
  const [available, setAvailable] = useState<boolean | undefined>(undefined);
  useEffect(() => {
    let cancelled = false;
    void loadDesktopAvailability().then((ok) => {
      if (!cancelled) setAvailable(ok);
    });
    return () => {
      cancelled = true;
    };
  }, []);
  return available;
}
