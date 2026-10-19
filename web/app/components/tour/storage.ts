import { auth } from "../../lib/auth";
import { tourSuppressed } from "../../lib/overlayGate";
import type { TourAccess } from "./audience";

// Tour storage keys, timing constants and the pure helpers the provider uses.
// Split out of TourProvider.tsx; no React in here.

export const STORAGE_KEY = "vd_tour_status"; // "done" | "skipped" once the visitor has made a choice

// Resume key (v2). The tour is long enough that a reload in the middle of it
// is a real event — a link opened in the same tab, a dev rebuild, a phone
// locking and restoring. Before this, any reload dropped the visitor back to
// the first step or, worse, back to the welcome modal they had already
// answered, so a half-finished tour silently restarted. This key holds ONLY
// the in-progress index. It is progress, not a decision, so it never affects
// isTourPending(): it is written while status is "active" and removed the
// moment the visitor skips, finishes, or reaches the completion screen.
//
// v3 caveat, handled on read rather than on write: the index is an offset
// into the SELECTED list, and the selected list depends on who the visitor
// is and what is on screen. It is therefore only meaningful for the same
// person on the same kind of page — which is exactly the reload case it was
// built for. Everything else (a role change, a plan change, a member
// resuming a list that is three steps shorter) is absorbed by clamping the
// value against the freshly selected list at resume time. Storing the step
// ID instead would survive those cases, but at the cost of changing what
// this key means for every other reader of it; clamping is the contract.
export const STEP_KEY = "vd_tour_step";
/** Tour state is per USER, not per browser (2026-10-09, direct feedback: the
 *  tour must show on a user's first login). Keyed only on the browser, one
 *  person finishing or skipping it suppressed it for every later account on
 *  that browser - a shared computer, a second workspace, or someone who
 *  deleted their account and signed up again never saw it. Falls back to the
 *  bare key only if the token has no subject (not expected in the console). */
export function userScoped(key: string): string {
  let id: string | null = null;
  try {
    id = auth.getUserId();
  } catch {
    id = null;
  }
  return id ? `${key}:${id}` : key;
}

/** sessionStorage, not localStorage, and deliberately so. This answers one
 * narrow question — "did the tour end a moment ago, in THIS tab?" — for the
 * other first-run surfaces that auto-open (AgentsGuide, SetupChecklistAlert).
 *
 * Why it exists (completeness review, 2026-09): those components guard
 * correctly against OVERLAP, checking isTourPending() and status
 * welcome/active on mount. Nobody checked SUCCESSION. The completion modal's
 * CTA navigates to /agents and then finishes the tour, which writes
 * vd_tour_status=done and drops status to "idle" — so by the time AgentsGuide
 * mounts on /agents for the first time, isTourPending() is false and status
 * is "idle", both guards pass, and its six-step "How agents work" modal opens
 * to re-explain what the tour's agents step just covered. A new visitor's
 * real sequence was: language prompt, welcome hero, start step, the walk,
 * completion modal, AgentsGuide. Seven surfaces.
 *
 * localStorage would be wrong here — it would suppress AgentsGuide forever,
 * including for the person who wants it a week later. Session scope means
 * "not in the same breath as the tour", which is the actual complaint. */
export const SESSION_END_KEY = "vd_tour_ended";

/** CATCH-UP STEPS (2026-10-10). The "security" step was added to the catalogue
 *  after most existing users had already finished or skipped the tour, so they
 *  never saw it. Anyone whose vd_tour_status is "done" | "skipped" is shown
 *  that ONE step, once, as a single-step walk through the normal overlay.
 *
 *  - Recorded per user under userScoped(SECURITY_SEEN_KEY), and ONLY when the
 *    visitor ends the catch-up (its Finish, its Skip/close, Escape). Arming it
 *    is not seeing it: a load where the anchor is missing records nothing and
 *    the next load tries again (the same trade the first-run arming makes).
 *  - A FULL walk records it too, the moment the security card is on screen and
 *    when the full tour finishes, so nobody who walked the whole tour is shown
 *    the step a second time.
 *  - The catch-up never writes vd_tour_status or vd_tour_step: it is not the
 *    first-run tour, and isTourPending()'s meaning must not move under it.
 *  - Never for: an unset status (the full tour is still owed and includes the
 *    step), a platform-admin (not in the step's audiences), or a visitor
 *    mid-tour (that is an unset status too; status must also still be idle). */
export const SECURITY_STEP_ID = "security";
export const SECURITY_SEEN_KEY = "vd_tour_seen_security";

/** How long after the nav rail appears the tour arms. Unchanged from v1 — it
 *  is the beat that lets the shell finish painting before a modal lands on it. */
export const ARM_DELAY_MS = 900;

/** Longest the arming path will wait for AppChromeProvider's tenantSettings
 *  before selecting without them, and how often it re-checks. Bounded on
 *  purpose: a FAILED settings fetch is stored as null and is indistinguishable
 *  from a pending one, so an unbounded wait would delete the tour outright for
 *  anyone whose request 500s. See canCommitSelection(). */
export const SELECTION_WAIT_MS = 4000;
export const SELECTION_POLL_MS = 150;

/** While the person is typing or a turn is in flight (overlayGate), re-check
 *  this often, and stop trying for this page load after BUSY_WAIT_MS. Nothing
 *  is recorded on give-up, so the next load tries again. */
export const BUSY_POLL_MS = 1000;
export const BUSY_WAIT_MS = 5 * 60_000;

/** Longest the hosted-model question may stay open. The answer only decides
 *  whether ONE sentence appears on the opening step, so a slow or hung request
 *  must resolve to "unknown" (the sentence is simply not shown) rather than
 *  hold a promise open for the life of the page. */
export const HOSTED_CHECK_TIMEOUT_MS = 8000;

/** How long the "you can reopen this from the ? button" note stays up after a
 *  skip. Long enough to read twice, short enough that it is gone before the
 *  visitor's next click lands under it. */
export const REOPEN_HINT_MS = 9000;

/** localStorage in private mode / with site data blocked THROWS on access
 * rather than returning null. Every read and write below goes through these
 * three helpers so the failure mode is "the tour works fine, it just is not
 * remembered" instead of an exception thrown inside a mount effect. */
export function readStored(key: string): string | null {
  try {
    return window.localStorage.getItem(key);
  } catch {
    return null;
  }
}

export function writeStored(key: string, value: string): void {
  try {
    window.localStorage.setItem(key, value);
  } catch {
    /* storage unavailable — the tour still runs, nothing persists */
  }
}

export function clearStored(key: string): void {
  try {
    window.localStorage.removeItem(key);
  } catch {
    /* see writeStored */
  }
}

/** sessionStorage twin of writeStored — same throw-in-private-mode caveat. */
export function writeSession(key: string, value: string): void {
  try {
    window.sessionStorage.setItem(key, value);
  } catch {
    /* storage unavailable — succession suppression just does not apply */
  }
}

/** Keeps an index inside the SELECTED list's bounds — `length` is always
 * that list's length, never TOUR_STEPS.length, because the visitor never
 * walks the full catalogue (see selectSteps). Three callers need this and
 * all three can be out of range:
 *
 *  - goToStep's argument comes from UI the visitor drives (the progress dots
 *    / "go to step n");
 *  - a stored resume index can outlive the list it was written against — a
 *    tenant-admin mid-tour at step 8 who returns as a member, or a deploy
 *    that shortens the catalogue, would otherwise resume onto `undefined`
 *    and render an empty card;
 *  - the clamp effect below, for the case where the selected list shrinks
 *    while the tour is already running.
 *
 * An empty list clamps to 0, and currentStep resolves to null there rather
 * than to `undefined` — selectSteps' floor should make that unreachable, but
 * "the tour is one step off" beats "the tour is blank" and both beat a
 * crash. */
export function clampIndex(index: number, length: number): number {
  if (!Number.isFinite(index)) return 0;
  return Math.min(Math.max(0, length - 1), Math.max(0, Math.trunc(index)));
}

/** The access shape for a visitor we know nothing about — the useTour()
 * no-op fallback outside the provider, and the value the provider itself
 * carries until AuthContext and tenantSettings have both resolved. Written
 * as a literal rather than `resolveAccess({...})` so that module load order
 * cannot matter; the `TourAccess` annotation still makes tsc fail here the
 * moment audience.ts's shape changes. */
export const UNKNOWN_ACCESS: TourAccess = {
  audience: "member",
  plan: null,
  accountType: null,
  experienceMode: null,
  whatsappAllowed: false,
  byokAllowed: false,
  liveVisionAllowed: false,
};

/**
 * True while this browser has never decided on the tour - i.e. the welcome
 * modal WILL (or may still) fire on this visit. Other first-run affordances
 * (SetupChecklistAlert's auto-open popover, AgentsGuide's auto-open modal)
 * check this so they never stack on top of the tour: on a cold first login
 * three overlapping popups used to arrive within a second of each other
 * (funnel audit, 2026-09). Reading localStorage directly rather than
 * useTour().status because the welcome is armed on a 900ms timer after the
 * nav rail mounts, so at the moment those components' mount effects run the
 * status is still "idle" - the flag is the only reliable early signal.
 * Storage disabled/private mode -> false, so nothing is suppressed forever.
 *
 * Deliberately still keyed on STORAGE_KEY alone: STEP_KEY is progress, not a
 * decision, and folding it in here would change what every caller above
 * means by "pending".
 */
export function isTourPending(): boolean {
  if (tourSuppressed()) return false;
  try {
    return !window.localStorage.getItem(userScoped(STORAGE_KEY));
  } catch {
    return false;
  }
}

/**
 * True if the visitor finished or skipped the tour at any point in THIS
 * browsing session — see SESSION_END_KEY for why this is a separate signal
 * from isTourPending(). Companion to it, not a replacement: isTourPending()
 * answers "is the tour about to happen", this answers "did it just happen".
 * Any surface that auto-opens on mount should check both, plus status
 * "welcome" | "active" | "completed" — "completed" is easy to leave out of
 * that list, and is only harmless today because those effects are mount-only.
 *
 * Storage disabled / private mode -> false, matching every other helper here:
 * the failure mode is "a redundant modal appears", never a thrown exception
 * inside somebody's mount effect.
 */
export function tourEndedThisSession(): boolean {
  try {
    return window.sessionStorage.getItem(SESSION_END_KEY) === "1";
  } catch {
    return false;
  }
}
