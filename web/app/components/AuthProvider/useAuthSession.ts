"use client";

import { useCallback, useEffect, useState } from "react";
import { auth, type SilentAuthError } from "../../lib/auth";
import { api } from "../../lib/api";
import {
  INIT_RETRY_DELAYS_MS,
  INIT_WATCHDOG_MS,
  SSO_REDIRECT_ATTEMPTED,
  describeInitError,
  pendingInteractiveAuth,
  tryTopLevelSsoRecovery,
} from "./initPolicy";

/**
 * The Keycloak session lifecycle behind AuthProvider: init() with bounded
 * retries, the splash watchdog, the vd_authed hint mirror, and the in-place
 * refresh after onboarding provisions a tenant. AuthProvider owns the route
 * decisions and rendering; this hook owns the state they read.
 */
export function useAuthSession({
  isPublicRoute,
  isOptimisticRoute,
  isAnonymousRenderRoute,
  authedHint,
}: {
  isPublicRoute: boolean;
  isOptimisticRoute: boolean;
  isAnonymousRenderRoute: boolean;
  authedHint: boolean;
}) {
  // initializing | ready | unauthenticated | interaction_required | error
  const [status, setStatus] = useState("initializing");
  const [error, setError] = useState(null);
  const [username, setUsername] = useState(null);
  const [firstName, setFirstName] = useState(null);
  const [roles, setRoles] = useState([]);
  // The tenant_id claim off the CURRENT token, snapshotted whenever the token
  // changes in a way this component cares about (init resolving, and onboarding
  // provisioning a tenant). Held in state rather than read live at render time
  // because auth.getTenantId() reads the keycloak-js singleton, which React has
  // no way to observe - the gate below would otherwise never re-evaluate.
  const [tenantId, setTenantId] = useState<string | null>(null);
  const [interactionRequired, setInteractionRequired] = useState<SilentAuthError | null>(null);
  // Granular RBAC (2026-09-23): the caller's effective permission names from
  // GET /api/me - the same set SecurityConfig enforces server-side as PERM_*
  // authorities. Empty until the /api/me round-trip lands (and stays empty
  // if it fails - UI gates just fail closed, server enforcement unaffected).
  const [permissions, setPermissions] = useState<string[]>([]);
  // Whether the /api/me round-trip has settled yet this session. Scope gates
  // (useRequireScope) must NOT act on `permissions` before this flips - on a
  // hard load of /admin/* the effect fires while the fetch is still in flight
  // and would bounce every scoped page to "/" before the answer arrives.
  const [permissionsLoaded, setPermissionsLoaded] = useState(false);
  // Bumped by the error screen's "Try again" button to re-run the init effect
  // below from scratch - see INIT_RETRY_DELAYS_MS above.
  const [initRetryNonce, setInitRetryNonce] = useState(0);

  useEffect(() => {
    if (isPublicRoute) return undefined; // no Keycloak lifecycle needed on a route with no session to check
    // Anonymous landing fast path (2026-10-05): "/" renders GenesisExperience
    // either way (the optimistic branch below), so for a visitor who has
    // NEVER been signed in here the silent check-sso round-trip can only
    // ever answer "no session" - yet it still fires two hidden iframes at
    // Keycloak plus its third-party-cookie probe (up to the 3.5s
    // messageReceiveTimeout) on every cold load. With no vd_authed hint and
    // no ?code=/?error= callback in the URL there is no session this page
    // could act on, so skip init() entirely: zero Keycloak traffic on a cold
    // anonymous visit. The two exceptions stay armed - a returning signed-in
    // visitor (hint set on a past "ready") still gets the fast session
    // restore, and a Keycloak login callback still exchanges its code.
    if (isOptimisticRoute) {
      let mightHaveSession = authedHint || auth.isAuthCallbackUrl();
      try {
        mightHaveSession = mightHaveSession || auth.hasAuthedHint();
      } catch {
        /* storage blocked - treat as "no hint", the landing renders anyway */
      }
      if (!mightHaveSession) {
        setStatus("unauthenticated");
        return undefined;
      }
    }
    let cancelled = false;
    let retryTimer: ReturnType<typeof setTimeout> | null = null;
    // 0 = the first attempt still in flight or not yet started; retries count
    // up against INIT_RETRY_DELAYS_MS (1 initial attempt + 3 retries = 4
    // total, bounded, ~10s of extra delay worst case - during which the splash
    // simply stays up for a new visitor, and a console that was already
    // "ready" stays rendered, never torn down).
    let attempt = 0;

    // Shared by the resolve path and the catch-path "authenticated anyway"
    // guard - never duplicate the four setters + /api/me fetch.
    const enterReadyState = () => {
      setUsername(auth.getUsername());
      setFirstName(auth.getFirstName());
      setRoles(auth.getRoles());
      setTenantId(auth.getTenantId());
      setStatus("ready");
      // Best-effort, fire-and-forget: the effective-permissions read model.
      // Server-side this call is also what lazy-seeds a legacy user's grant
      // rows from their role bundle on first login post-enforcement.
      api.getMe()
        .then((me) => {
          if (!cancelled) setPermissions(me.permissions ?? []);
        })
        .catch(() => {
          /* fail closed: no permissions exposed, server still enforces */
        })
        .finally(() => {
          if (!cancelled) setPermissionsLoaded(true);
        });
    };

    const attemptInit = () => {
      const pending = attempt === 0 ? auth.init() : auth.retryInit();
      pending
        .then((kc) => {
          if (cancelled) return;
          if (!kc) {
            // "check-sso" resolving with no session is the normal, expected
            // case for any first-time or logged-out visitor - not an error.
            // BUT it's also what a signed-in visitor gets when the browser blocks
            // the iframe's storage, so give the top-level recovery a chance first
            // (no-ops for anyone who was never signed in).
            //
            // ...and it is ALSO what keycloak-js reports when Keycloak answered
            // "interaction_required" - a live session with a pending required
            // action. That one must never fall through to either branch below:
            // the top-level prompt=none recovery would come back with the same
            // error, and the landing page is a dead end. See
            // pendingInteractiveAuth().
            const needsInteraction = pendingInteractiveAuth();
            if (needsInteraction) {
              setInteractionRequired(needsInteraction);
              setStatus("interaction_required");
              return;
            }
            if (tryTopLevelSsoRecovery()) return;
            setStatus("unauthenticated");
            return;
          }
          enterReadyState();
        })
        .catch((err) => {
          if (cancelled) return;
          // keycloak-js frequently rejects init() with a plain object (e.g.
          // {error: "...", error_description: "..."}) instead of an Error
          // instance, a bare string, or literally undefined - describeInitError
          // normalises all of those to a human-readable message (never the
          // literal "undefined"/"[object Object]" that used to render).
          const message = describeInitError(err);

          // "Timeout when waiting for 3rd party check iframe message." is not
          // a real auth failure - it's keycloak-js's pre-flight probe for
          // whether third-party cookies/storage work at all (a hidden iframe
          // pointed at Keycloak's login-status-iframe.html, unrelated to the
          // actual silentCheckSsoRedirectUri iframe in public/silent-check-sso.html).
          // Modern browsers' third-party storage partitioning (Chrome's default
          // since third-party cookie deprecation, Safari ITP, Firefox ETP, and
          // most privacy extensions) blocks that probe outright rather than
          // answering it, so it hangs until keycloak-js's own timeout and
          // *rejects* init() - this happens regardless of the
          // silentCheckSsoFallback option (lib/auth.js), which only controls
          // what happens if the probe *resolves* with "not supported", not
          // what happens if it times out; a timeout is a different code path
          // that always rejects. There is no keycloak-js option to skip this
          // probe outright, so instead of surfacing it as a fatal "Login
          // failed" screen, treat it exactly like "no session found": every
          // visitor with a third-party-cookie-blocking browser (the current
          // majority) would otherwise be unable to load the app at all.
          const isBenignThirdPartyProbeTimeout =
            /3rd party check iframe|third.?party.*cookie/i.test(message);
          if (isBenignThirdPartyProbeTimeout) {
            // Same precedence as the resolve path above: if we DID manage to read
            // an "interaction_required" answer (top-level callback, or the silent
            // iframe's own message before the probe gave up), that is a live
            // session and outranks any fallback to "logged out".
            const needsInteraction = pendingInteractiveAuth();
            if (needsInteraction) {
              setInteractionRequired(needsInteraction);
              setStatus("interaction_required");
              return;
            }
            // The probe being blocked says NOTHING about whether a session
            // exists - so before falling back to "logged out", try the top-level
            // prompt=none redirect, which carries the SSO cookie the iframe
            // couldn't read. This is the path that fixes "refresh dumps a
            // signed-in user on the landing page".
            if (tryTopLevelSsoRecovery()) return;
            console.warn(
              "Keycloak silent-SSO probe could not run (likely third-party " +
                "cookie/storage blocking) - continuing as unauthenticated:",
              err
            );
            setStatus("unauthenticated");
            return;
          }

          // A rejected retry never discards a live session: if keycloak-js is
          // still holding an authenticated client (e.g. this effect re-ran
          // after route classification changed while a console was already
          // "ready"), honour it instead of tearing a working console down.
          if (auth.isAuthenticated()) {
            enterReadyState();
            return;
          }

          // Transient rejections (the bare-undefined keycloak-js failure that
          // motivated this, one flaky round-trip, a Cloudflare 1015 in front
          // of Keycloak) get bounded background retries with backoff rather
          // than an instant dead screen. While retrying, status stays exactly
          // where it was - "initializing" splash on a fresh load, or the live
          // "ready" console if it was already up.
          if (attempt < INIT_RETRY_DELAYS_MS.length) {
            const delay = INIT_RETRY_DELAYS_MS[attempt];
            attempt += 1;
            console.warn(
              `Keycloak init() failed (attempt ${attempt}/${INIT_RETRY_DELAYS_MS.length + 1}); retrying in ${delay}ms`,
              err
            );
            retryTimer = setTimeout(attemptInit, delay);
            return;
          }

          setError(message);
          setStatus("error");
          console.error("Keycloak init() failed:", err);
        });
    };
    attemptInit();
    return () => {
      cancelled = true;
      if (retryTimer) clearTimeout(retryTimer);
    };
  }, [isPublicRoute, isOptimisticRoute, authedHint, initRetryNonce]);

  // Watchdog (SE-01): keycloak-js can neither resolve nor reject when the
  // Keycloak URL is unreachable from the page (mixed content on an https
  // origin, a blocked iframe, a non-secure context), which left the splash up
  // forever. After INIT_WATCHDOG_MS of "initializing" on a route that has no
  // anonymous render, show the error screen with Retry and the failing
  // endpoint. A late init() success still wins: it sets "ready" afterwards.
  useEffect(() => {
    if (status !== "initializing" || isPublicRoute || isAnonymousRenderRoute) return undefined;
    const timer = setTimeout(() => {
      setError(`Sign-in did not finish within ${INIT_WATCHDOG_MS / 1000} seconds.`);
      setStatus("error");
    }, INIT_WATCHDOG_MS);
    return () => clearTimeout(timer);
  }, [status, isPublicRoute, isAnonymousRenderRoute, initRetryNonce]);

  // Persist a lightweight "this visitor was signed in" hint, read on the NEXT
  // cold load (in the initializing branch below) to decide landing-vs-splash.
  // This is what stops the landing->dashboard flicker on refresh; it is only a
  // UI hint (never a security decision - the real gate is a valid Keycloak
  // token). Cleared on logout (see auth.logout) and whenever check-sso resolves
  // with no session.
  //
  // The flow that eliminates post-signin flicker:
  // 1. Cold load "/" (authenticated but hint not yet set): GenesisExperience renders
  // 2. Keycloak init() resolves authenticated in background
  // 3. status changes to "ready" → localStorage hint set + real Dashboard renders
  // 4. Subsequent refresh "/" (hint is "1"): "Signing you in..." splash renders
  // 5. Keycloak init() resolves quickly (session cookie still valid)
  // 6. status changes to "ready" → real Dashboard renders
  //
  // The splash uses AXON theme-aware .auth-shell/.auth-card CSS classes
  // (globals.css), which respond to <html data-mode="light|dark"> and
  // render as:
  //   Light: --surface-page #f7f8fa (neutral), --surface-card #ffffff (white)
  //   Dark:  --surface-page #0d0f17 (slate blue), --surface-card #171b28 (slate)
  // Both modes include accent gradient overlays and text colors that pass WCAG AA.
  useEffect(() => {
    if (typeof window === "undefined") return;
    // Mirrored into a cookie so the SERVER can read the hint: the root layout
    // skips inlining the 115 KB marketing-landing translations for a visitor
    // with a session (app/lib/i18n-namespaces.ts). A hint only.
    // Both halves expire with the Keycloak session (see auth.markAuthedHint).
    if (status === "ready") {
      auth.markAuthedHint();
      // Session recovered (or established) - release the one-shot guard so a
      // later logout -> login -> refresh cycle can recover again in this tab.
      window.sessionStorage.removeItem(SSO_REDIRECT_ATTEMPTED);
      // Console typography density (see layout.tsx's NO_FLASH_DENSITY_SCRIPT)
      // - correct its pre-hydration guess now that the REAL Keycloak check
      // has settled. A no-op on every route that guess already got right
      // (everything outside PUBLIC_ROUTES, or "/" with a stale-but-correct
      // vd_authed hint); only actually flips anything for the one narrow
      // case that script's own comment documents: a visitor's very
      // first-ever load landing on "/" already authenticated.
      document.documentElement.setAttribute("data-density", "console");
    } else if (status === "unauthenticated") {
      // Deliberately NOT "any status other than ready": the
      // "interaction_required" status means the SSO session is ALIVE and the
      // person is one Keycloak screen away from being back in, so clearing the
      // hint there would be a lie - and worse, it would disarm
      // tryTopLevelSsoRecovery on the next load for the one visitor who most
      // needs it. Only a genuine "no session" answer clears it.
      auth.clearAuthedHint();
      // Same correction, the other direction - a stale vd_authed hint (or a
      // console-route URL reached directly, e.g. a stale bookmark) whose
      // session actually expired must not keep the marketing/console
      // density mismatched once the real check says otherwise.
      document.documentElement.removeAttribute("data-density");
    }
  }, [status]);

  // Called by OnboardingGate once POST /api/onboarding has provisioned the
  // tenant AND a forced token refresh has landed the new tenant_id claim, so
  // the gate below lifts in place.
  //
  // This exists because the old success path was `await auth.refreshToken();
  // window.location.reload()`, which was the FIRST half of "after creating the
  // organization it throws me out to the landing page": a reload throws away
  // the in-memory keycloak-js session and rebuilds it through
  // check-sso/prompt=none, and a user with a pending required action (see
  // pendingInteractiveAuth above) is answered interaction_required there, which
  // keycloak-js reports as "not authenticated" - marketing landing, live
  // session, no way forward. Nothing about provisioning needs a page reload:
  // the refreshed token is already in memory, so re-reading it and re-rendering
  // is both correct and faster.
  //
  // Roles are re-read too, not just the tenant: provisioning grants the creator
  // tenant-admin, so `roles` here is stale by exactly one token - and isAdmin
  // below (which "/" reads to send an admin on to /admin) is derived from it.
  const handleProvisioned = useCallback(() => {
    setUsername(auth.getUsername());
    setFirstName(auth.getFirstName());
    setRoles(auth.getRoles());
    setTenantId(auth.getTenantId());
    // Provisioning made this caller a fresh tenant-admin - the first /api/me
    // call after it is exactly the lazy-seed trigger that backfills their
    // grant rows, so refresh the effective permissions too, not just roles.
    api.getMe()
      .then((me) => setPermissions(me.permissions ?? []))
      .catch(() => { /* fail closed */ });
  }, []);

  // The error screen's "Try again": re-runs the init effect from scratch.
  const retry = useCallback(() => {
    setError(null);
    setStatus("initializing");
    setInitRetryNonce((n) => n + 1);
  }, []);

  return {
    status,
    error,
    username,
    firstName,
    roles,
    tenantId,
    interactionRequired,
    permissions,
    permissionsLoaded,
    handleProvisioned,
    retry,
  };
}
