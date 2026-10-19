import { publicApiBase } from "../../lib/env";
// Fire-and-forget funnel beacons for the /hire "Hiring Papers" landing.
// Same mechanism, endpoint and philosophy as demo-landing/landingBeacon.ts
// (core's LandingEventController: POST /api/public/landing-event increments a
// Redis counter per event per UTC day — no PII, no cookies, nothing stored
// about the visitor beyond +1). Never throws, never reports failure;
// keepalive:true so a click-through beacon survives the navigation it
// measures.
//
// Server-side vocabulary: LandingEventController.ALLOWED_EVENTS carries all
// six hire_* names below (added 2026-08-03, same commit as the workforce_*/
// automate_* events). Any NEW event added here must also be added there —
// the endpoint silently 204-drops unknown names by design.

const API_BASE = publicApiBase();

export type HireEvent =
  | "hire_view"
  | "hire_interview_answer"
  | "hire_stamp_click"
  | "hire_calc_interact"
  | "hire_sign_draw"
  | "hire_cta_click";

export function sendHireEvent(event: HireEvent): void {
  try {
    void fetch(`${API_BASE}/api/public/landing-event`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ event }),
      keepalive: true,
    }).catch(() => {});
  } catch {
    /* nothing to report — a lost analytics event is nobody's problem */
  }
}

/**
 * hire_view, at most once per browser session (sessionStorage guard) — same
 * remount-double-counting reasoning as landingBeacon's sendLandingViewOnce.
 */
export function sendHireViewOnce(): void {
  try {
    const KEY = "vd_hire_view_sent";
    if (window.sessionStorage.getItem(KEY) === "1") return;
    window.sessionStorage.setItem(KEY, "1");
  } catch {
    /* storage unavailable (private browsing) — count the view anyway */
  }
  sendHireEvent("hire_view");
}
