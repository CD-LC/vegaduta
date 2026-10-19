"use client";

import { useEffect, useRef, useState } from "react";
import { api } from "../../lib/api";
import type { TourAudience } from "./audience";
import { HOSTED_CHECK_TIMEOUT_MS } from "./storage";
import type { TourStatus } from "./context";

/**
 * Whether the server routes this tenant to the platform-hosted model pool,
 * for the tour's "no API key needed" line. `null` until answered and on any
 * failure (the tour then makes no claim).
 */
export function useHostedModelAvailable(status: TourStatus, audience: TourAudience): boolean | null {
  const [hostedModelAvailable, setHostedModelAvailable] = useState<boolean | null>(null);
  // Set false on unmount; read by the async hosted-model answer below.
  const mountedRef = useRef(true);
  useEffect(() => {
    mountedRef.current = true;
    return () => {
      mountedRef.current = false;
    };
  }, []);

  // IS THE HOSTED MODEL ON FOR THIS TENANT? Asked of the server, once, and only
  // when a tour is actually on screen (2026-09-19).
  //
  // The opening step used to key its "hosted model included, no API key" line
  // off the PLAN NAME, and a brand-new signup holds a complimentary grant, so
  // their plan reads as paid and they got the paid wording - which never makes
  // that claim. The one visitor the sentence exists for never saw it. That is
  // the same mistake lib/providerGate.ts was written to end (the client
  // re-deriving core's TenantLimits#usesPlatformPool and missing the grant
  // arm), so the tour asks the same endpoint instead of guessing.
  //
  // Fail-proof by construction: the request is fired lazily (never on a page
  // load where no tour shows, so first login pays for it only when the hero is
  // about to appear), raced against a timeout, and every failure - a 401, a
  // 404 from a core that predates the endpoint, a dropped connection - lands on
  // `null`, which means "do not make the claim". Nothing awaits it and nothing
  // renders differently while it is pending. A platform-admin may have no
  // tenant at all, and their walk makes no model claim, so they never ask.
  const hostedAskedRef = useRef(false);
  useEffect(() => {
    if (status === "idle" || hostedAskedRef.current) return undefined;
    if (audience === "platform-admin") return undefined;
    hostedAskedRef.current = true;
    let timer: number | undefined;
    const timeout = new Promise<null>((resolve) => {
      timer = window.setTimeout(() => resolve(null), HOSTED_CHECK_TIMEOUT_MS);
    });
    Promise.race([
      // Wrapped so a SYNCHRONOUS throw inside the wrapper is a rejection too.
      Promise.resolve().then(() => api.getProviderAvailability()),
      timeout,
    ])
      .then((res) => {
        if (!mountedRef.current) return;
        setHostedModelAvailable(res && typeof res === "object" ? res.platformHostedAvailable === true : null);
      })
      .catch(() => {
        if (mountedRef.current) setHostedModelAvailable(null);
      })
      .finally(() => window.clearTimeout(timer));
    // No per-effect cleanup on purpose: `status` changes on every step of the
    // walk, and a cleanup that cancelled the in-flight answer would throw it
    // away the moment the visitor pressed "Take the tour" - with the ref above
    // then refusing to ask again. mountedRef is the unmount guard instead.
    return undefined;
  }, [status, audience]);

  return hostedModelAvailable;
}
