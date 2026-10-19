"use client";

import { useEffect, useState } from "react";
import { api } from "../../lib/api";
import { forgetMissing, isKnownMissing, rememberMissing } from "../../lib/api/negativeCache";
import type { BrowserTakeoverCapabilities } from "../../lib/api/browserTakeover";

// Whether core has browser takeover switched on (app.browser.takeover.enabled + browser v2, both OFF by default).
// A switched-off core answers 404, so "not loaded yet", "failed" and "off" are all the same answer to every
// consumer: render nothing, poll nothing. A positive answer is fetched once per page load (module-level
// promise); a failure is not cached, so the next mount asks again (a call made before the token was ready must
// not hide the panel until a full reload).
//
// The ONE failure that is an answer is 404 - "this deployment has takeover switched off". That used to be
// retried like any other failure, so every mount of the workbench or the playground probed again and the
// console collected a 404 per page load (prod pass 2026-10-05, bug 12). A 404 (or an explicit enabled:false)
// is now remembered for the browser session; 401s, 5xx and network errors still are not.

const OFF_KEY = "browser-takeover";

let pending: Promise<BrowserTakeoverCapabilities | null> | null = null;

export function loadBrowserTakeoverCapabilities(): Promise<BrowserTakeoverCapabilities | null> {
  if (isKnownMissing(OFF_KEY)) return Promise.resolve(null);
  if (pending === null) {
    // try/await so a synchronous throw (an api object without this method, as in a narrow test double) is the
    // same "unavailable" answer as a failed request.
    const attempt = (async (): Promise<BrowserTakeoverCapabilities | null> => {
      try {
        const caps = await api.getBrowserTakeoverCapabilities();
        if (caps && caps.enabled) return caps;
        if (caps && caps.enabled === false) rememberMissing(OFF_KEY);
        return null;
      } catch (err) {
        if ((err as { status?: number } | null)?.status === 404) rememberMissing(OFF_KEY);
        return null;
      }
    })();
    pending = attempt;
    void attempt.then((caps) => {
      if (caps === null && pending === attempt) pending = null;
    });
  }
  return pending;
}

/** Test seam: forget the cached answer. */
export function resetBrowserTakeoverCapabilitiesCache(): void {
  pending = null;
  forgetMissing(OFF_KEY);
}

/** undefined while loading, null when unavailable/off, otherwise the capabilities. */
export function useBrowserTakeoverCapabilities(): BrowserTakeoverCapabilities | null | undefined {
  const [caps, setCaps] = useState<BrowserTakeoverCapabilities | null | undefined>(undefined);
  useEffect(() => {
    let cancelled = false;
    void loadBrowserTakeoverCapabilities().then((c) => {
      if (!cancelled) setCaps(c);
    });
    return () => {
      cancelled = true;
    };
  }, []);
  return caps;
}
