import type { BrowserCapabilities, BrowserProfile } from "../../lib/api/browserAutomation";
import type { BrowserTakeoverCapabilities } from "../../lib/api/browserTakeover";

// What the Assistant's Browser button shows, decided from the two capability probes core answers.
//   hidden       - nothing to offer (still loading, or a non-personal install with the feature simply off:
//                  looks exactly as before, like the panel in the chat surfaces)
//   unavailable  - this install cannot do it: say so, with how to get it, instead of hiding silently
//   ready        - core has takeover on; the pane can be opened
export type BrowserAvailability = "hidden" | "unavailable" | "ready";

/**
 * `takeover` / `browser`: undefined while loading, null when the probe failed or the feature is off.
 * An install is only called "lacking" on evidence: the headless browser is reported absent, or this is the
 * personal install and core answered (so the failure is not just a dropped request) with takeover off.
 */
export function browserAvailability(
  takeover: BrowserTakeoverCapabilities | null | undefined,
  browser: BrowserCapabilities | null | undefined,
  personal: boolean,
): BrowserAvailability {
  if (takeover === undefined || browser === undefined) return "hidden";
  if (browser?.browserAvailable === false) return "unavailable";
  if (takeover) return "ready";
  return personal && browser !== null ? "unavailable" : "hidden";
}

/** The saved login the agent runs with, if any (the server allows one per agent). */
export function profileFor(profiles: BrowserProfile[], agentId: string): BrowserProfile | null {
  return profiles.find((p) => p.attachedAgentIds.includes(agentId)) ?? null;
}
