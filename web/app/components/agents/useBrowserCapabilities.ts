"use client";

import { useEffect, useState } from "react";
import { api } from "../../lib/api";
import type { BrowserCapabilities } from "../../lib/api/browserAutomation";

// Which browser-automation features (saved logins, recorded tasks, TOTP) this deployment has switched
// on. All three are OFF by default in core, so every consumer treats "not loaded yet" and "unavailable"
// exactly like "off": it renders nothing, and a deployment that never enabled the feature looks the same
// as before. A successful answer is fetched once per page load (module-level promise), however many cards and
// run rows ask; a failed one is not cached, so the next mount retries.

let pending: Promise<BrowserCapabilities | null> | null = null;

export function loadBrowserCapabilities(): Promise<BrowserCapabilities | null> {
  if (pending === null) {
    // try/await so a synchronous throw (an api object without this method, as in a narrow test double) is
    // the same "unavailable" answer as a failed request.
    const attempt = (async (): Promise<BrowserCapabilities | null> => {
      try {
        const caps = await api.getBrowserCapabilities();
        // A deployment with no headless browser offers no browser_* tools, so none of the cards can work.
        // (Older cores omit the field: undefined counts as available.)
        if (caps && caps.browserAvailable === false) {
          return { ...caps, profilesEnabled: false, recipesEnabled: false, totpEnabled: false };
        }
        return caps ?? null;
      } catch {
        return null;
      }
    })();
    pending = attempt;
    // Only a successful answer is remembered: a transient 401/5xx (or a call made before the token was
    // ready) must not hide the cards until a full page reload - the next mount asks again.
    void attempt.then((caps) => {
      if (caps === null && pending === attempt) pending = null;
    });
  }
  return pending;
}

/** Test seam: forget the cached answer. */
export function resetBrowserCapabilitiesCache(): void {
  pending = null;
}

/** undefined while loading, null when unavailable, otherwise the flags. */
export function useBrowserCapabilities(): BrowserCapabilities | null | undefined {
  const [caps, setCaps] = useState<BrowserCapabilities | null | undefined>(undefined);
  useEffect(() => {
    let cancelled = false;
    void loadBrowserCapabilities().then((c) => {
      if (!cancelled) setCaps(c);
    });
    return () => {
      cancelled = true;
    };
  }, []);
  return caps;
}
