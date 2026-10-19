// Fire-and-forget onboarding beacon: the existing landing beacon, narrowed to
// the closed /start vocabulary (events.ts). No PII, no free text, no cookies -
// the endpoint stores +1 on a per-event per-UTC-day counter and nothing else,
// and works for an anonymous caller. Never throws (sendLandingEvent swallows
// every failure), so analytics can never break the flow.

import { sendLandingEvent } from "../components/demo-landing/landingBeacon";
import { isOnboardingEvent, type OnboardingEvent } from "./events";

export function sendOnboardingEvent(event: OnboardingEvent): void {
  // Belt and braces: a name outside the vocabulary would be 204-dropped by
  // core anyway; not sending it keeps the network quiet and the intent clear.
  if (!isOnboardingEvent(event)) return;
  sendLandingEvent(event);
}
