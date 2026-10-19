"use client";

import { createContext, useContext, useSyncExternalStore, type ReactNode } from "react";
import { usePathname, useSelectedLayoutSegment } from "next/navigation";
import { splitLocalePrefix } from "../lib/i18n-config";
import { auth, ADMIN_ROLE, PLATFORM_ADMIN_ROLE, matchesPublicRoute } from "../lib/auth";
import GenesisExperience from "./genesis/GenesisExperience";
import SignInRedirect from "../platform-admin/components/SignInRedirect";
import { isLocalAuth } from "../lib/authMode";
import OnboardingGate from "./OnboardingGate";
import IdleLogout from "./IdleLogout";
import { ANONYMOUS_RENDER_ROUTES, OPTIMISTIC_ROUTES } from "./AuthProvider/initPolicy";
import { useAuthSession } from "./AuthProvider/useAuthSession";
import { InteractionRequiredScreen, LoginFailedScreen, SigningInSplash } from "./AuthProvider/AuthScreens";

const AuthContext = createContext(null);

// Exported (in addition to useAuth() below) for AppChromeProvider, which is
// mounted at the root layout ABOVE the public/protected route split - it
// needs to read auth state on EVERY route including public ones (see its own
// header comment), and useAuth()'s hard throw-if-no-provider is correct for
// everywhere else in the app (a real bug: a component using auth state that
// isn't actually under this provider) but wrong there: on a public route (see
// isPublicRoute below) AuthProvider renders `children` with NO
// AuthContext.Provider at all, by design - nothing else has ever needed
// useAuth() on those routes before. useContext(AuthContext) never throws; it
// just resolves to the `null` default, which is exactly "no session" -
// correct for a public route.
export { AuthContext };

export function useAuth() {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error("useAuth() must be used inside <AuthProvider>");
  return ctx;
}

// Phase 0 Stage 5: /signup must be reachable by a brand-new visitor who by
// definition has no Keycloak session. Now that auth.init() uses "check-sso"
// instead of "login-required" (see lib/auth.js), it no longer forces a
// redirect either way - this exemption is now purely a perf/simplicity
// optimization (skip Keycloak's iframe SSO check entirely on a page that
// has no use for the result) rather than a correctness requirement.
//
// /explore (task #477 - AgenticAiLanding.js) is the same kind of route:
// the audience-segmented "Agentic AI Landing" public page, reachable by a
// visitor with no session, same exemption reasoning as /signup above.
//
// /billing (#957 - the pricing explainer page) is the same again: linked
// from Landing's nav/footer and reachable before signup, so it needs to
// render with zero Keycloak round-trip too. See billing/page.js's own
// comment for why it deliberately doesn't try to use AuthContext at all.
//
// /login (AXON rebuild, "Log In Pro.dc.html") - the pixel-matched sign-in
// screen, same exemption reasoning: a logged-out visitor by definition has
// no session to check yet, and its own CTA is what kicks off the real
// Keycloak redirect (auth.login()), not a form this page submits to.
// /ui-kit (AuthKit living style guide, PR-1) is the same kind of route as the
// others here: a public design-system reference that renders its own primitives
// with no Keycloak session to check. Exempted so it renders standalone (and so
// it's reachable while the backend is down during design work).
//
// PUBLIC_ROUTES itself now lives in lib/auth.ts (imported above) rather than
// as a local const here - auth.js's own getToken() needs the same list (to
// stop a background token-refresh failure on one of these routes from
// firing a login redirect - see that file's javadoc), and importing one
// shared array beats keeping two lists in sync by hand.
//
// OPTIMISTIC_ROUTES, ANONYMOUS_RENDER_ROUTES and the init retry policy live in
// AuthProvider/initPolicy.ts; the Keycloak session lifecycle (init, retries,
// watchdog, vd_authed hint) lives in AuthProvider/useAuthSession.ts.

// Top-level auth gate for the rest of the console (wrapped around
// {children} in layout.js). Nothing renders until Keycloak's init()
// resolves; the three possible outcomes now are: an existing session found
// (status "ready", render the app), no session found (status
// "unauthenticated", render GenesisExperience - Keycloak's own hosted login
// only appears once the person clicks "Sign in" there, or the pre-Keycloak
// custom theme now shown at that point - see deploy/keycloak/themes/
// agentic-ai), or a genuine failure (status "error" - wrong realm/client
// id, Keycloak unreachable, etc).
//
// GenesisExperience (components/genesis/) replaced MarketingHome.js here -
// a full creative reinvention (seven-act scroll narrative around one
// persistent WebGL entity) rather than another conventional landing page,
// per explicit feedback that a hero/feature-cards/testimonials/pricing
// structure fails regardless of how it's skinned. MarketingHome.tsx (+
// MarketingNav/MarketingFooter and the ~18 section components under
// components/marketing/) WAS left in place, unused, for a long time; the
// 2026-07-29 dead-code pass finally deleted it - nothing imported it, and
// git history is a better archive than a live directory nobody builds
// against. Only Reveal.tsx and AnimatedCounter.tsx survive from that folder,
// because the LIVE landings (demo-landing/DemoLanding and genesis/
// AgenticAiLanding) import them. (CORRECTED
// 2026-07-27 nav audit: LoginLanding.js is NOT unused - it's the live
// component rendered at /login, see web/app/login/page.tsx. A prior version
// of this comment claimed otherwise; don't delete it based on that.)
// GenesisNav.js's single "Sign in" control carries the exact
// `data-testid="login-landing-button"` the whole Playwright suite's login
// fixtures click through - originally moved over from the old MarketingNav,
// same testid, unchanged contract.
//
// isAdmin is derived once here rather than recomputed in every page that
// cares about it - single source of truth for "does this user land on the
// admin control panel."
export default function AuthProvider({
  children,
  authedHint = false,
}: {
  children: ReactNode;
  // The "was signed in" hint as the SERVER saw it: RootLayout reads the
  // vd_authed cookie (the same hint this component mirrors from localStorage
  // when a session resolves) and passes it down here. This is what makes the
  // "/" optimistic-route splash hydration-safe: server and first client
  // render must produce identical markup, so the splash-vs-landing decision
  // on the FIRST client render can only use values the server also knew -
  // a render-time window.localStorage read produced the real React #418
  // hydration mismatch on "/" (server rendered GenesisExperience, the client
  // rendered the splash). Anything localStorage/the callback URL knows that
  // the cookie didn't (a first-ever login's `?code=` return) is layered on
  // after mount via the `mounted` flag - one extra frame, no mismatch.
  authedHint?: boolean;
}) {
  // Locale-prefixed URLs (multilingual SEO, 2026-09-19): the browser is on
  // "/hi/billing" while the app routed "/billing" (proxy.js rewrite), and
  // usePathname() reports the browser's. Every route decision below is about
  // the ROUTE, so the prefix comes off first - otherwise "/hi/billing" matches
  // nothing public and a crawler gets the login wall instead of the page.
  const pathname = splitLocalePrefix(usePathname()).pathname;
  // Segment-aware matching shared with lib/auth.ts's isPublicPathname — see
  // matchesPublicRoute there for the rule ("/ai" must not match "/ai-models").
  const isPublicRoute = matchesPublicRoute(pathname);
  // Unknown URL (SE-22): Next resolves an unmatched path to the internal
  // "/_not-found" segment under the root layout. Such a path has no real page, so
  // it is not a console route to gate: render the 404 page (app/not-found.tsx)
  // immediately instead of the signed-out landing (~830 KB, still HTTP 404).
  const isNotFoundRoute = useSelectedLayoutSegment() === "/_not-found";
  // Exact match, not startsWith - "/" would otherwise match every route.
  const isOptimisticRoute = OPTIMISTIC_ROUTES.includes(pathname);
  // Exact match too - see ANONYMOUS_RENDER_ROUTES' comment above for why
  // "/help"/"/features" specifically must not use startsWith.
  const isAnonymousRenderRoute = ANONYMOUS_RENDER_ROUTES.includes(pathname);
  // The merged platform-admin section (was the standalone web-admin app) has
  // no public surface: every route is an internal admin tool gated on the
  // platform-admin realm role. An unauthenticated visitor here gets a
  // Keycloak sign-in redirect (the old app's "login-required" behaviour), not
  // the marketing landing the rest of the console falls back to.
  const isPlatformAdminRoute = pathname === "/platform-admin" || pathname.startsWith("/platform-admin/");

  const {
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
  } = useAuthSession({ isPublicRoute, isOptimisticRoute, isAnonymousRenderRoute, authedHint });
  // False during SSR AND the first client render, true once hydration is
  // done (getServerSnapshot is what React uses while hydrating - same
  // convention AppShell uses for its isDesktop media query). Anything derived
  // from window/localStorage in the "/" optimistic branch is gated on this so
  // that first render always matches the server (see authedHint above).
  const mounted = useSyncExternalStore(
    () => () => {},
    () => true,
    () => false
  );

  // No Keycloak lifecycle at all for a public route - render immediately,
  // no AuthContext (the signup page has no use for it and never calls
  // useAuth()).
  if (isPublicRoute || isNotFoundRoute) {
    return children;
  }

  if (status === "initializing") {
    // /help and /features (ANONYMOUS_RENDER_ROUTES): can't skip straight to
    // isPublicRoute's zero-lifecycle render above - a signed-in visitor still
    // needs the real Keycloak round-trip to reach "ready" and get their live
    // plan/entitlements (features/page.tsx calls useAuth() for exactly that).
    // But nothing about THAT requires a first-time or logged-out visitor to
    // sit through the full wait first: the Keycloak silent-SSO probe includes
    // a third-party-cookie-probe timeout that can run several seconds (see
    // tryTopLevelSsoRecovery's comment above), and the generic splash below
    // has no idea these two routes have a perfectly good anonymous view ready
    // to go - the same one the "unauthenticated" branch further down already
    // renders once Keycloak confirms there's no session. The 2026-07-30 SEO
    // audit fixed the post-resolution case; this closes the same gap during
    // the wait itself - which is what a fast automated check (or an
    // impatient visitor) actually sees first. Root-caused 2026-08-30 after a
    // Google for Startups Cloud Program rejection: both URLs, reachable from
    // the public sitemap, rendered nothing but "Signing you in…" when probed
    // before Keycloak resolved - indistinguishable from a broken or
    // login-gated page to anything that doesn't wait out the full init().
    if (isAnonymousRenderRoute) {
      return (
        <AuthContext.Provider
          value={{
            username: null,
            firstName: null,
            roles: [],
            isAdmin: false,
            isPlatformAdmin: false,
            permissions: [],
            permissionsLoaded: true,
            hasPermission: () => false,
            hasAnyPermission: () => false,
            hasAdminScope: false,
            logout: auth.logout,
            accountUrl: auth.accountUrl,
          }}
        >
          {children}
        </AuthContext.Provider>
      );
    }
    // See OPTIMISTIC_ROUTES' comment above - "/" doesn't block on the
    // Keycloak round-trip, it renders immediately and swaps to the real
    // Dashboard if/when init() resolves authenticated.
    if (isOptimisticRoute) {
      // A returning, previously-signed-in visitor (hint set on a past "ready")
      // gets a quiet signing-in splash rather than the marketing landing - so
      // refreshing the dashboard no longer flickers landing -> dashboard. A
      // brand-new / logged-out visitor still sees the landing immediately.
      // The splash respects AXON theme awareness via the auth-shell/auth-card
      // CSS classes (globals.css): data-mode switches between light
      // (--surface-page #f7f8fa) and dark (--surface-page #0d0f17) modes.
      //
      // Direct feedback (2026-08-12): "after login, it comes back to landing
      // and then gets redirect - it should be smooth." A visitor's FIRST-EVER
      // login has no vd_authed hint yet - that's only written once init()
      // resolves "ready" a little further down - so this branch used to
      // render the marketing landing for however long the Keycloak code
      // exchange took, then swap to the dashboard: a visible landing-then-
      // redirect flash on every fresh login, not just the returning-visitor
      // case the hint already covers. auth.isAuthCallbackUrl() answers a
      // narrower, more reliable question than the hint - "did this exact
      // page load come back from Keycloak with a code/error in the URL" -
      // true for that whole first-login window regardless of history.
      //
      // HYDRATION-SAFE (React #418, 2026-09-25): the first client render must
      // match the server exactly, and the server cannot see localStorage or
      // the fragment part of a callback URL - it sees only the vd_authed
      // COOKIE, which RootLayout passes in as `authedHint`. So the render-time
      // decision uses only that prop; the localStorage hint and
      // isAuthCallbackUrl() are layered on once `mounted` flips (one frame
      // after hydration). Before this, a returning signed-in visitor hitting
      // "/" always mismatched (server: landing, client: splash) and React
      // discarded the entire SSR'd tree - the very flash this branch exists
      // to prevent, plus a console error.
      const likelyAuthed = authedHint || (mounted && (auth.hasAuthedHint() || auth.isAuthCallbackUrl()));
      if (!likelyAuthed) {
        // A personal install (local auth) has no public site and sign-up is closed: the marketing
        // landing with its "Sign up" buttons is a dead end there, so go straight to sign-in.
        if (isLocalAuth()) return <SignInRedirect />;
        return <GenesisExperience />;
      }
      return <SigningInSplash />;
    }
    // Fallback splash for non-optimistic routes (e.g. /agents, /workflows) or
    // when Keycloak init takes longer than expected to resolve.
    return <SigningInSplash />;
  }

  if (status === "interaction_required") {
    // /help and /features (ANONYMOUS_RENDER_ROUTES) keep rendering their own
    // content - an interstitial over a public help page helps nobody, and the
    // anonymous branch just below already handles them. Everywhere else gets
    // the recovery screen INSTEAD of the marketing landing.
    if (!isAnonymousRenderRoute) {
      return <InteractionRequiredScreen detail={interactionRequired} />;
    }
  }

  if (status === "unauthenticated" || status === "interaction_required") {
    // See ANONYMOUS_RENDER_ROUTES above - render the page's own real content
    // (wrapped in an anonymous AuthContext so AppShell's useAuth() still
    // resolves instead of throwing) rather than swapping in the homepage.
    if (isAnonymousRenderRoute) {
      return (
        <AuthContext.Provider
          value={{
            username: null,
            firstName: null,
            roles: [],
            isAdmin: false,
            isPlatformAdmin: false,
            permissions: [],
            permissionsLoaded: true,
            hasPermission: () => false,
            hasAnyPermission: () => false,
            hasAdminScope: false,
            logout: auth.logout,
            accountUrl: auth.accountUrl,
          }}
        >
          {children}
        </AuthContext.Provider>
      );
    }
    // /platform-admin/*: no anonymous/marketing fallback exists for this
    // section - go straight to Keycloak's hosted login (see
    // isPlatformAdminRoute above and SignInRedirect's own comment).
    if (isPlatformAdminRoute) {
      return <SignInRedirect />;
    }
    return <GenesisExperience />;
  }

  if (status === "error") {
    // Only reached after INIT_RETRY_DELAYS_MS's bounded retries are exhausted
    // and no live session exists - a genuine inability to establish auth
    // (misconfigured realm/client, Keycloak down), never a transient blip.
    // `error` is always a real, human-readable string (describeInitError) -
    // never the literal "undefined".
    return <LoginFailedScreen error={error} onRetry={retry} />;
  }

  const isAdmin = roles.includes(ADMIN_ROLE);
  // Cross-tenant super-admin flag - separate from isAdmin (see
  // PLATFORM_ADMIN_ROLE's comment in lib/auth.js). A tenant-admin does NOT
  // automatically get this; it's a distinct realm role a person must hold in
  // addition to (or instead of) tenant-admin.
  const isPlatformAdmin = roles.includes(PLATFORM_ADMIN_ROLE);

  // Granular-scope helpers for nav items and admin pages. These only drive
  // WHAT THE UI SHOWS - the authoritative check is SecurityConfig's PERM_*
  // matchers, so a stale or forged client-side answer can at most reveal a
  // link that 403s, never grant access.
  const hasPermission = (name: string) => permissions.includes(name);
  const hasAnyPermission = (...names: string[]) => names.some((n) => permissions.includes(n));
  // "Has any administration scope at all" - drives whether the admin nav
  // section itself appears for a scoped (non-tenant-admin) user.
  const hasAdminScope = isAdmin || permissions.length > 0;

  // Social-login onboarding gate: a user who signed in via an identity provider
  // (e.g. Google) arrives authenticated but with NO tenant - Keycloak's IdP
  // first-login created the user without ever running /api/signup, so they have
  // no organization/tenant_id. Route them into the one-time "create your
  // organization" step instead of a broken, tenant-less dashboard. Platform
  // admins are tenant-less BY DESIGN (cross-tenant) and must never be gated.
  // /platform-admin/* is also exempt for NON-platform-admins: the section's
  // own PlatformAdminGate renders the "Access restricted" denial there -
  // onboarding an intruder into creating a tenant would be worse UX and
  // grant them something on a screen they were never meant to reach.
  if (!isPlatformAdmin && !isPlatformAdminRoute && !tenantId) {
    return <OnboardingGate firstName={firstName} onProvisioned={handleProvisioned} />;
  }

  return (
    <AuthContext.Provider
      value={{
        username,
        firstName,
        roles,
        isAdmin,
        isPlatformAdmin,
        permissions,
        permissionsLoaded,
        hasPermission,
        hasAnyPermission,
        hasAdminScope,
        logout: auth.logout,
        accountUrl: auth.accountUrl,
      }}
    >
      <IdleLogout />
      {children}
    </AuthContext.Provider>
  );
}
