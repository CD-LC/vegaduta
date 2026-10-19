"use client";

import { useEffect, useState } from "react";
import { DEMO_TOKEN_KEY } from "../components/try/demoClient";

// Where this browser is on the Try -> Understand -> Implement path
// (conversion redesign, 2026-09-10).
//
// The landing used to greet every visitor identically - including the ones
// who had ALREADY opened a /try sandbox in this same browser and come back
// to the homepage without registering (the exact drop-off the funnel
// read-out showed: hero_try_click high, signup low). The only signal we
// have for "this person has tried" is the demo token /try persists in
// localStorage (demoClient.ts storeToken); this hook reads it and lets the
// hero, the path band and the closing CTA change their answer to "where do
// I start?" from "try it" to "keep your agent - create the free account".
//
// The signup href it returns carries that token (`/signup?demo=...`) - the
// same CONVERSION SEAM TrySandbox documents: core's DemoConversionService
// consumes it and copies the sandbox's agents into the new tenant, so
// "keep my agent" is a literal promise. A bare /signup would silently turn
// the conversion into an ordinary signup with nothing to copy.
//
// Hydration-safe: the first render (server and client alike) is always the
// "try" stage; the token is read in an effect, so there is never a server/
// client markup mismatch. A stale token (sandbox already gone) is harmless -
// signup treats an unlinkable demo as a plain registration.
export type JourneyStage = "try" | "keep";

export interface Journey {
  stage: JourneyStage;
  /** true once the token has actually been read (false on the first paint). */
  known: boolean;
  tryHref: string;
  signupHref: string;
}

export function useJourney(): Journey {
  const [token, setToken] = useState<string | null>(null);
  const [known, setKnown] = useState(false);

  useEffect(() => {
    try {
      setToken(window.localStorage.getItem(DEMO_TOKEN_KEY));
    } catch {
      setToken(null); // private browsing / storage disabled - stay on "try"
    }
    setKnown(true);
  }, []);

  if (token) {
    return {
      stage: "keep",
      known,
      tryHref: "/try",
      signupHref: `/signup?demo=${encodeURIComponent(token)}`,
    };
  }
  return { stage: "try", known, tryHref: "/try", signupHref: "/signup" };
}
