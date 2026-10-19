"use client";

import { useEffect, useRef, useState } from "react";
import { createPortal } from "react-dom";
import { AnimatePresence, motion } from "motion/react";
import { auth } from "../lib/auth";
import { api } from "../lib/api";
import { useTranslations } from "../lib/i18n";
import { Button } from "./ui/button";

// Session guard. Direct requirement (2026-08-05): "user should be logged in
// till he clicks on logout or timeout message asking to extend."
//
// Two independent deadlines can end a session, and this component is the ONLY
// place either of them is allowed to become visible:
//
//   1. The idle policy - a platform setting (GET /api/session/config, keys
//      session.idle-timeout-seconds / session.idle-warning-seconds, editable on
//      the Ecosystem Maintenance "Platform Settings" screen). Now genuinely
//      DISABLEABLE: 0 means "never log out for idleness", which is what an
//      operator naturally types and what the previous `Number(x) || DEFAULT`
//      coercion silently turned back into 600 seconds.
//   2. The real session deadline - auth.getSessionExpiryMs(), i.e. the refresh
//      token's exp, which Keycloak sets to whichever of the SSO idle timeout /
//      SSO max lifespan comes first. This is server truth, not a client guess.
//      lib/auth.ts refreshes silently and proactively (onTokenExpired + a
//      keepalive + a forced refresh on tab-return/reconnect), so this deadline
//      normally slides forward forever and is never seen. It only surfaces when
//      the session genuinely cannot be renewed silently any more - exactly the
//      moment the requirement says to ask rather than act.
//
// Whichever deadline is nearer drives one countdown modal ("Still there? Your
// session expires in M:SS") with "Stay signed in" and "Log out". Nothing here
// ever ends a session on its own (2026-10-09: "don't throw the user out
// abruptly on session timeout"): when the idle countdown reaches zero the
// modal turns into "Your session timed out" with "Extend session" and "Log
// out", and waits. The page underneath stays as it was, so extending puts the
// person back exactly where they were.
//
// The previous implementation defaulted to 600s (9 minutes to the warning, 60
// more to a hard auth.logout()) with no way to switch it off, was per-tab with
// no cross-tab awareness (an idle background tab logged out the tab you were
// typing in), and used a setTimeout chain that a sleeping laptop skews into
// firing an instant logout on wake.
const DEFAULT_IDLE_TIMEOUT_SECONDS = 28800; // 8h - a defensible fallback if /api/session/config is unreachable
const DEFAULT_IDLE_WARNING_SECONDS = 60;
// How long before the REAL session deadline the modal appears. Longer than the
// idle warning: this one is not "are you still there", it is "your sign-in is
// about to run out", and a person may need to finish what they were typing.
const EXPIRY_WARNING_SECONDS = 120;
// Start trying to renew silently a minute before the modal would appear, and
// keep retrying on this cadence. If any attempt succeeds the deadline slides
// and the modal is never shown at all.
const EXPIRY_REFRESH_LEAD_SECONDS = EXPIRY_WARNING_SECONDS + 60;
const EXPIRY_REFRESH_RETRY_MS = 30_000;
const TICK_MS = 1000;
// A gap between ticks larger than this means we were not running normally -
// the machine slept, or the browser froze/throttled a background tab. Nobody
// could have seen (let alone answered) a countdown during that window, so the
// idle deadline is re-armed with the full warning period instead of firing.
const CLOCK_GAP_MS = 5_000;
// Cross-tab activity hint. Same posture as the existing "vd_authed" hint: a UI
// hint only, readable by anything on the origin, and never an input to an auth
// decision beyond re-arming a client-side timer.
const ACTIVITY_STORAGE_KEY = "vd_last_activity";
const ACTIVITY_WRITE_THROTTLE_MS = 5_000;
// "wheel" is separate from "scroll", not redundant with it: a "scroll" event
// only fires when a scrollable element's position actually changes. Someone
// already at the bottom of a chat log who keeps scrolling/using the wheel -
// clearly present - moves nothing, so no "scroll" event fires at all. "wheel"
// fires on every wheel/trackpad tick regardless, closing that gap.
const ACTIVITY_EVENTS = ["mousemove", "mousedown", "keydown", "touchstart", "scroll", "wheel", "click"];

type SessionIdleConfig = { idleTimeoutSeconds: number; idleWarningSeconds: number };
type WarningKind = "idle" | "expiry";
type Warning = { kind: WarningKind; seconds: number };

// Preserves 0 (= disabled / no policy) instead of coercing it back to the
// default the way `Number(x) || DEFAULT` did. Only a missing, non-numeric or
// negative value falls back.
function readSeconds(raw: unknown, fallback: number): number {
  const n = Number(raw);
  if (!Number.isFinite(n) || n < 0) return fallback;
  return Math.floor(n);
}

function formatCountdown(totalSeconds: number): string {
  const s = Math.max(0, Math.floor(totalSeconds));
  const mins = Math.floor(s / 60);
  const secs = s % 60;
  return `${mins}:${String(secs).padStart(2, "0")}`;
}

export default function IdleLogout() {
  const t = useTranslations("idleLogout");

  const [config, setConfig] = useState<SessionIdleConfig>({
    idleTimeoutSeconds: DEFAULT_IDLE_TIMEOUT_SECONDS,
    idleWarningSeconds: DEFAULT_IDLE_WARNING_SECONDS,
  });
  // non-null only while the countdown modal is showing.
  const [warning, setWarning] = useState<Warning | null>(null);
  const [staying, setStaying] = useState(false);
  // The idle countdown reached zero with nobody answering. The modal stays up
  // (no automatic sign-out) until they extend or log out.
  const [timedOut, setTimedOut] = useState(false);
  const timedOutRef = useRef(false);
  timedOutRef.current = timedOut;
  // Separate trigger from the countdown flow above: fires when lib/auth.ts
  // concludes the session is genuinely unrenewable (see promptReauth there) -
  // which can happen while the person is actively using the app, not just
  // idle. Same "ask before acting" principle, but no fixed deadline: retrying
  // is user-initiated, not timed.
  const [reauthNeeded, setReauthNeeded] = useState(false);
  const [reauthRetrying, setReauthRetrying] = useState(false);
  // True once a retry has actually failed - surfaces "Sign in again" as the
  // clearly-primary action instead of inviting another likely-doomed retry.
  const [reauthFailed, setReauthFailed] = useState(false);

  // Set inside the timer effect, called from the modal's buttons - a ref
  // because the buttons live in this component's render output, outside the
  // effect closure that owns the actual timers.
  const resetRef = useRef<() => void>(() => {});
  // Focus management for the modal (a11y audit follow-up): nothing previously
  // moved focus into the dialog on open, trapped Tab inside it, or returned
  // focus to wherever it came from on close - a keyboard/screen-reader user
  // got no indication the countdown had even appeared.
  const dialogRef = useRef<HTMLDivElement>(null);
  const previousFocusRef = useRef<HTMLElement | null>(null);
  // Read inside the tick without making it an effect dependency (which would
  // tear down and re-arm every timer on each state change).
  const reauthNeededRef = useRef(false);
  reauthNeededRef.current = reauthNeeded;

  useEffect(() => {
    let cancelled = false;
    api
      .getSessionConfig()
      .then((res: any) => {
        if (cancelled || !res) return;
        const idleTimeoutSeconds = readSeconds(res.idleTimeoutSeconds, DEFAULT_IDLE_TIMEOUT_SECONDS);
        const rawWarning = readSeconds(res.idleWarningSeconds, DEFAULT_IDLE_WARNING_SECONDS);
        // A warning window that is >= the timeout would mean the modal is
        // showing permanently; keep at least half the window as quiet time.
        const idleWarningSeconds =
          idleTimeoutSeconds > 0
            ? Math.min(Math.max(1, rawWarning), Math.max(1, Math.floor(idleTimeoutSeconds / 2)))
            : rawWarning;
        setConfig({ idleTimeoutSeconds, idleWarningSeconds });
      })
      .catch(() => {
        // Fetch failed - the state above is already a sane fallback, so this is
        // a silent no-op, not a degraded state.
      });
    return () => {
      cancelled = true;
    };
  }, []);

  useEffect(() => {
    if (typeof window === "undefined") return undefined;
    const { idleTimeoutSeconds, idleWarningSeconds } = config;
    const idleEnabled = idleTimeoutSeconds > 0;

    let lastActivity = Date.now();
    let lastActivityWrite = 0;
    let lastTick = Date.now();
    let lastExpiryRefreshAttempt = 0;
    let expiryRefreshInFlight = false;
    let stopped = false;

    const readSharedActivity = (): number => {
      try {
        const raw = window.localStorage.getItem(ACTIVITY_STORAGE_KEY);
        const n = raw ? Number(raw) : NaN;
        return Number.isFinite(n) ? n : 0;
      } catch {
        return 0;
      }
    };

    const markActivity = (persist: boolean) => {
      const now = Date.now();
      lastActivity = now;
      if (!persist) return;
      // Throttled: mousemove alone would otherwise write to localStorage
      // dozens of times a second in every open tab.
      if (now - lastActivityWrite < ACTIVITY_WRITE_THROTTLE_MS) return;
      lastActivityWrite = now;
      try {
        window.localStorage.setItem(ACTIVITY_STORAGE_KEY, String(now));
      } catch {
        // Private mode / quota - cross-tab sync degrades to per-tab, which is
        // the old behaviour and still safe.
      }
    };

    const onActivity = () => markActivity(true);
    // Another tab saw real activity - this session is being used, so this tab
    // must not count down (and must never log the ACTIVE tab out, which is
    // what the old per-tab timer did).
    const onStorage = (e: StorageEvent) => {
      if (e.key === ACTIVITY_STORAGE_KEY) markActivity(false);
    };

    const reset = () => {
      markActivity(true);
      lastTick = Date.now();
      lastExpiryRefreshAttempt = 0;
      setWarning(null);
      setTimedOut(false);
    };
    resetRef.current = reset;

    const tick = () => {
      if (stopped) return;
      const now = Date.now();
      const gap = now - lastTick;
      lastTick = now;
      // Sleep / background-throttle detection, checked before anything acts on
      // a deadline: setInterval does not run at all while the machine is
      // suspended and is throttled to >=1/min in a hidden tab, so a naive
      // "deadline passed" check on the first tick back would sign someone out
      // the instant they opened their laptop.
      const resumedFromGap = gap > CLOCK_GAP_MS;

      // The reauth modal owns the screen once it is up - user-answered, no
      // countdown to run underneath it.
      if (reauthNeededRef.current) return;
      // Same once timed out: the person answers, nothing runs underneath -
      // except that "Extend session" in ANOTHER tab (which writes fresh shared
      // activity) also clears it here.
      if (timedOutRef.current) {
        const sharedNow = readSharedActivity();
        if (sharedNow > lastActivity) {
          lastActivity = sharedNow;
          setTimedOut(false);
          setWarning(null);
        }
        return;
      }

      const shared = readSharedActivity();
      if (shared > lastActivity) lastActivity = shared;

      let deadline = Infinity;
      let warnAt = Infinity;
      let kind: WarningKind = "idle";
      let warnWindow = idleWarningSeconds;

      if (idleEnabled) {
        deadline = lastActivity + idleTimeoutSeconds * 1000;
        warnAt = deadline - idleWarningSeconds * 1000;
      }
      const sessionExpiry = auth.getSessionExpiryMs();
      if (sessionExpiry !== null && sessionExpiry < deadline) {
        deadline = sessionExpiry;
        warnAt = deadline - EXPIRY_WARNING_SECONDS * 1000;
        kind = "expiry";
        warnWindow = EXPIRY_WARNING_SECONDS;
      }

      if (deadline === Infinity) {
        // Idle policy disabled and no known session deadline: nothing to count
        // down. This is the "stays signed in until Logout is clicked" state.
        setWarning(null);
        return;
      }

      // Try to renew silently BEFORE bothering anyone. lib/auth.ts's keepalive
      // normally gets there first; this is the backstop for a tab that was
      // frozen through the keepalive's window.
      if (
        kind === "expiry" &&
        now >= deadline - EXPIRY_REFRESH_LEAD_SECONDS * 1000 &&
        !expiryRefreshInFlight &&
        now - lastExpiryRefreshAttempt >= EXPIRY_REFRESH_RETRY_MS
      ) {
        lastExpiryRefreshAttempt = now;
        expiryRefreshInFlight = true;
        void auth.refreshToken().finally(() => {
          expiryRefreshInFlight = false;
        });
      }

      const remainingMs = deadline - now;

      if (remainingMs <= 0) {
        if (kind === "expiry") {
          // The session deadline passed. One last forced refresh decides it:
          // if it works the deadline slides and nothing was lost; if it does
          // not, the session is genuinely over - show the reauth prompt rather
          // than a Keycloak logout redirect carrying a dead id_token_hint.
          if (!expiryRefreshInFlight) {
            expiryRefreshInFlight = true;
            void auth.refreshToken().then((ok) => {
              expiryRefreshInFlight = false;
              if (stopped) return;
              if (!ok) {
                setWarning(null);
                setReauthNeeded(true);
                setReauthFailed(true);
              }
            });
          }
          return;
        }
        if (resumedFromGap) {
          // Woken/unfrozen past the idle deadline - offer the full warning
          // window from now instead of signing out unannounced.
          lastActivity = now - Math.max(0, idleTimeoutSeconds - warnWindow) * 1000;
          setWarning({ kind: "idle", seconds: warnWindow });
          return;
        }
        // The countdown ran to zero, in a visible tab, with no interaction and
        // no other tab reporting activity. Say so and wait - never sign out
        // unannounced. Extend refreshes the real session (staySignedIn).
        setWarning({ kind: "idle", seconds: 0 });
        setTimedOut(true);
        return;
      }

      if (now >= warnAt) {
        const seconds = Math.max(0, Math.ceil(remainingMs / 1000));
        setWarning((prev) => (prev && prev.kind === kind && prev.seconds === seconds ? prev : { kind, seconds }));
      } else {
        setWarning((prev) => (prev === null ? prev : null));
      }
    };

    // capture: true is load-bearing, not defensive boilerplate - "scroll" does
    // NOT bubble (a `scroll` fired on some inner overflow:auto panel never
    // reaches a `window`-level bubble-phase listener). This app's real content
    // - chat logs, modals, side panels - is read by scrolling an INNER
    // container, never the window body, so without capture someone reading a
    // long conversation with the wheel alone was invisible to this listener.
    ACTIVITY_EVENTS.forEach((e) => window.addEventListener(e, onActivity, { passive: true, capture: true }));
    window.addEventListener("storage", onStorage);
    markActivity(true);
    const interval = setInterval(tick, TICK_MS);

    return () => {
      stopped = true;
      clearInterval(interval);
      ACTIVITY_EVENTS.forEach((e) => window.removeEventListener(e, onActivity, { capture: true }));
      window.removeEventListener("storage", onStorage);
    };
    // Re-arms whenever the fetched config changes (once, typically, right after
    // the mount-time fetch above resolves) so a slow config fetch never leaves
    // the timer running against stale defaults for the rest of the session.
     
  }, [config]);

  useEffect(() => {
    const onReauthNeeded = () => {
      setWarning(null);
      setReauthNeeded(true);
      setReauthFailed(false);
    };
    window.addEventListener("vd:session-needs-reauth", onReauthNeeded);
    return () => window.removeEventListener("vd:session-needs-reauth", onReauthNeeded);
  }, []);

  const modalOpen = warning !== null || reauthNeeded;

  // On open: remember what had focus, move focus into the dialog (the
  // heading, since which button is "primary" changes as reauthFailed/kind
  // change while the modal is already up), and trap Tab inside the card.
  // On close: return focus to whatever the person was doing before the
  // countdown interrupted them. Deliberately no Escape-to-dismiss - this
  // modal exists specifically so a session deadline can't be silently
  // ignored, so unlike a plain popover it requires one of the two buttons.
  useEffect(() => {
    if (!modalOpen) return undefined;
    previousFocusRef.current = document.activeElement as HTMLElement | null;
    const dialog = dialogRef.current;
    dialog?.querySelector<HTMLElement>("h2")?.focus();
    function onTabTrap(e: KeyboardEvent) {
      if (e.key !== "Tab" || !dialog) return;
      const focusable = Array.from(
        dialog.querySelectorAll<HTMLElement>('button, [href], input, select, textarea, [tabindex]:not([tabindex="-1"])')
      ).filter((el) => !el.hasAttribute("disabled"));
      if (focusable.length === 0) return;
      const first = focusable[0];
      const last = focusable[focusable.length - 1];
      if (e.shiftKey && document.activeElement === first) {
        e.preventDefault();
        last.focus();
      } else if (!e.shiftKey && document.activeElement === last) {
        e.preventDefault();
        first.focus();
      }
    }
    document.addEventListener("keydown", onTabTrap);
    return () => {
      document.removeEventListener("keydown", onTabTrap);
      previousFocusRef.current?.focus?.();
    };
  }, [modalOpen]);

  async function retryReauth() {
    setReauthRetrying(true);
    const ok = await auth.refreshToken();
    setReauthRetrying(false);
    if (ok) {
      auth.clearReauthPrompt();
      setReauthNeeded(false);
      setReauthFailed(false);
      // A successful refresh means they're genuinely back - restart the idle
      // window too rather than leaving a stale countdown armed against however
      // long the connection was actually interrupted for.
      resetRef.current();
    } else {
      setReauthFailed(true);
    }
  }

  // Back to this same page after signing in (auth.login returns to
  // origin+pathname), so a session that could not be extended costs one
  // sign-in, not the place they were working in.
  function signInAgainNow() {
    auth.clearReauthPrompt();
    void auth.login();
  }

  // Forces a real Keycloak refresh (updateToken(-1)) so the SERVER-side session
  // is genuinely extended, not just the client-side timer. The boolean is now
  // honoured: it used to be discarded, which reset the countdown against a
  // session that no longer existed and left the person to hit a confusing
  // failure on their next click instead of here, where the fix is one button.
  async function staySignedIn() {
    setStaying(true);
    const ok = await auth.refreshToken();
    setStaying(false);
    if (!ok) {
      setWarning(null);
      setTimedOut(false);
      setReauthNeeded(true);
      setReauthFailed(true);
      return;
    }
    // A refresh can SUCCEED without extending the session. The refresh token's
    // exp is bounded by the realm's ssoSessionMaxLifespan (Keycloak's 10h
    // default - agentic-ai-realm.json sets none): a session at that cap
    // refreshes fine but the deadline cannot slide. reset() would clear this
    // modal and the tick would re-show it a second later, making the button
    // look dead - the reported bug. Detect "refreshed but the deadline did
    // not move out of the warning window" and go straight to the reauth
    // prompt, the only honest answer when the server will not extend.
    if (warning?.kind === "expiry") {
      const expiry = auth.getSessionExpiryMs();
      if (expiry !== null && expiry - Date.now() <= EXPIRY_WARNING_SECONDS * 1000) {
        setWarning(null);
        setReauthNeeded(true);
        setReauthFailed(true);
        return;
      }
    }
    auth.clearReauthPrompt();
    // The deadline-tick may have fired its own terminal refresh while this one
    // was in flight and already raised the reauth prompt - a successful refresh
    // here proves the session alive, so clear any of that state too.
    setReauthNeeded(false);
    setReauthFailed(false);
    resetRef.current();
  }

  if (warning === null && !reauthNeeded) return null;

  return createPortal(
    <AnimatePresence>
      <motion.div
        className="fixed inset-0 z-[100] flex items-center justify-center bg-black/60 px-4 backdrop-blur-sm"
        initial={{ opacity: 0 }}
        animate={{ opacity: 1 }}
        exit={{ opacity: 0 }}
        transition={{ duration: 0.15 }}
        role="dialog"
        aria-modal="true"
        aria-label={reauthNeeded ? t("reconnectDialogAria") : t("expiringDialogAria")}
        ref={dialogRef}
      >
        <motion.div
          className="w-full max-w-sm rounded-xl border border-border bg-card p-6 text-foreground shadow-2xl"
          initial={{ opacity: 0, scale: 0.96, y: 8 }}
          animate={{ opacity: 1, scale: 1, y: 0 }}
          exit={{ opacity: 0, scale: 0.97, y: 6 }}
          transition={{ duration: 0.18, ease: [0.16, 1, 0.3, 1] }}
        >
          {reauthNeeded ? (
            <>
              <h2 className="text-sm font-semibold tracking-tight" tabIndex={-1}>{t("reconnectTitle")}</h2>
              <p className="mt-2 text-sm text-muted-foreground">
                {reauthFailed ? t("reconnectFailedBody") : t("reconnectBody")}
              </p>
              <div className="mt-5 flex justify-end gap-2">
                <Button type="button" variant="ghost" size="sm" onClick={signInAgainNow}>
                  {t("signInAgain")}
                </Button>
                {!reauthFailed && (
                  <Button type="button" size="sm" onClick={retryReauth} disabled={reauthRetrying}>
                    {reauthRetrying ? t("retrying") : t("retry")}
                  </Button>
                )}
              </div>
            </>
          ) : (
            <>
              <h2 className="text-sm font-semibold tracking-tight" tabIndex={-1}>
                {timedOut ? t("timedOutTitle") : t("stillThereTitle")}
              </h2>
              <p className="mt-2 text-sm text-muted-foreground">
                {timedOut ? t("timedOutBody") : warning?.kind === "expiry" ? t("expiryReason") : t("idleReason")}
              </p>
              {/* role="timer" defaults to aria-live="off" - deliberately NOT
                  polite: this text changes every second, and announcing it
                  every second would make the modal unusable with a screen
                  reader. The dialog's own aria-label announces on open. */}
              {!timedOut && (
                <p
                  className="mt-3 text-sm text-muted-foreground"
                  role="timer"
                  aria-label={t("countdownAria", { time: formatCountdown(warning?.seconds ?? 0) })}
                >
                  {t("expiresIn", { time: formatCountdown(warning?.seconds ?? 0) })}
                </p>
              )}
              <div className="mt-5 flex justify-end gap-2">
                <Button type="button" variant="ghost" size="sm" onClick={() => void auth.logout()}>
                  {t("logOut")}
                </Button>
                <Button type="button" size="sm" onClick={staySignedIn} disabled={staying}>
                  {staying ? t("staying") : timedOut ? t("extendSession") : t("staySignedIn")}
                </Button>
              </div>
            </>
          )}
        </motion.div>
      </motion.div>
    </AnimatePresence>,
    document.body
  );
}
