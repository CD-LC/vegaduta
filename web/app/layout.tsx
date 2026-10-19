import { isOssSite } from "./lib/siteMode";
import { cookies, headers } from "next/headers";
// Fonts are committed woff2 files (app/fonts/, built by scripts/dev/build-web-fonts.py), so
// `next build` never downloads from Google and cannot fail on fonts.gstatic.com.
import { dmMono, geist, geistMono, jakarta, notoSansDevanagari, yatraOne } from "./root-fonts";
import "./globals.css";
// Public-funnel design layer ("Aurora", 2026-09-12). Imported AFTER globals so
// its unlayered `.marketing-root` rules win ties against that file's own
// unlayered marketing block (the type scale + the h1/h2 face). Every selector
// inside is scoped to `.marketing-root`, so the console is untouched by it.
import "./styles/marketingSystemStyles";
// Tier 1 "paper & ink" token layer — must stay the LAST stylesheet import.
import "./styles/tokensStyles";
import "./styles/publicStyles";
import "./styles/consoleStyles";
import "./auth.css";
import AuthProvider from "./components/AuthProvider";
import { PUBLIC_ROUTES } from "./lib/auth";
import { AppChromeProvider } from "./components/AppChromeProvider";
import { SystemHealthProvider } from "./components/SystemHealthProvider";
import { PlatformModelChangeNotice } from "./components/PlatformModelChangeNotice";
import PlatformStatusBanner from "./components/PlatformStatusBanner";
import ViewAsBanner from "./components/ViewAsBanner";
import ThemeProvider, { NO_FLASH_SCRIPT } from "./components/ThemeProvider";
import ToastHost from "./components/ToastHost";
import RouteTransition from "./components/RouteTransition";
import { TourProvider } from "./components/TourProvider";
import TourOverlay from "./components/tour/TourOverlay";
import WeatherThemeEngine from "./components/WeatherThemeEngine";
import SessionAccentEngine from "./components/SessionAccentEngine";
import { I18nProvider } from "./lib/i18n";
import { SUPPORTED_LOCALES, DEFAULT_LOCALE, LOCALE_COOKIE, INDEXABLE_LOCALES } from "./lib/i18n-config";
import { GeoProvider } from "./lib/geo";
import { resolveLandingVariant } from "./lib/geo-config";
// Locale bundles are loaded lazily, one per request — see lib/messages-loader.ts
// for why they must never become static imports again.
import { loadMessages, messagesVersion } from "./lib/messages-loader";
import { getPublicEnv, publicEnvScript } from "./lib/env";
import { AUTHED_HINT_COOKIE, hasAuthedHint, namespacesForRequest, pruneBundle } from "./lib/i18n-namespaces";
import JsonLd from "./components/JsonLd";
import { NONCE_HEADER } from "./lib/csp";
import { OSS_DESCRIPTION, OSS_TITLE, buildMetadata } from "./root-metadata";
import { STRUCTURED_DATA } from "./root-structured-data";

const LOCALE_META = Object.fromEntries(SUPPORTED_LOCALES.map((l) => [l.code, l]));

// Console typography density, no-flash script (direct feedback, 2026-08-03) -
// see the <head> comment where this is injected for the full "why". Two
// signals, checked synchronously before paint, no async/localStorage-only
// wait:
//   1. Pathname: PUBLIC_ROUTES (imported, not duplicated, from lib/auth.ts -
//      the SAME list AuthProvider itself gates on) covers every route a
//      logged-out visitor can reach. Anything else (e.g. /agents,
//      /knowledge, /workflows) is a console-only route by construction - a
//      logged-out visitor is never actually rendering real content there
//      (AuthProvider redirects/gates first) - so console density applies
//      immediately, zero flash, even on a visitor's very first-ever request.
//   2. "/" is inherently ambiguous (DemoLanding for logged-out, the
//      authenticated dashboard for logged-in - same URL, see
//      AuthProvider's OPTIMISTIC_ROUTES) and "/help"/"/features" are public
//      but outside PUBLIC_ROUTES (ANONYMOUS_RENDER_ROUTES in
//      AuthProvider.tsx) - for those, fall back to the "vd_authed"
//      localStorage hint AuthProvider already writes/clears, giving a
//      RETURNING authenticated visitor no flash on "/" either. Only a
//      visitor whose very first-ever load ever resolves authenticated
//      AND lands directly on "/" still gets one corrective reflow once
//      AuthProvider's real Keycloak check settles - narrower than before,
//      not eliminated, since no earlier signal exists for that one case.
const NO_FLASH_DENSITY_SCRIPT = `
(function() {
  try {
    var publicRoutes = ${JSON.stringify(PUBLIC_ROUTES)};
    var anonymousRenderRoutes = ["/help", "/features"];
    var prefixLocales = ${JSON.stringify([DEFAULT_LOCALE, ...INDEXABLE_LOCALES])};
    var path = window.location.pathname;
    // "/hi/billing" is "/billing" in Hindi (multilingual SEO, 2026-09-19) -
    // mirrors lib/i18n-config.ts splitLocalePrefix.
    var localePrefix = /^\\/([a-z]{2})(?=\\/|$)/.exec(path);
    if (localePrefix && prefixLocales.indexOf(localePrefix[1]) !== -1) {
      path = path.slice(localePrefix[0].length) || "/";
    }
    var isKnownPublic = path === "/" || anonymousRenderRoutes.indexOf(path) !== -1 ||
      publicRoutes.some(function(r) {
        // Mirrors lib/auth.ts matchesPublicRoute (segment-aware): "/ai" must
        // not classify "/ai-models" as public.
        if (r.charAt(r.length - 1) === "/") return path.indexOf(r) === 0;
        return path === r || path.indexOf(r + "/") === 0;
      });
    var likelyConsole = !isKnownPublic || localStorage.getItem('vd_authed') === '1';
    if (likelyConsole) {
      document.documentElement.setAttribute('data-density', 'console');
    }
  } catch (e) {}
})();
`;

export async function generateMetadata() {
  const meta = await buildMetadata();
  if (!isOssSite()) return meta;
  return {
    ...meta,
    title: OSS_TITLE,
    description: OSS_DESCRIPTION,
    openGraph: { ...meta.openGraph, title: OSS_TITLE, description: OSS_DESCRIPTION },
  };
}

export const viewport = {
  // #f3f3f4 is --surface-page from the LIGHT block of app/tokens.css, the
  // surface the app paints by default (ThemeProvider's DEFAULT_MODE is "light"
  // since Tier 1, 2026-09-24). It is only the SSR fallback: NO_FLASH_SCRIPT rolls
  // light/dark once per anonymous session and explicit Dark users always paint
  // #0e0e12, so ThemeProvider (syncThemeColor) must rewrite this meta to #0e0e12
  // whenever data-mode resolves dark — a static value cannot follow that roll.
  // manifest.webmanifest carries the same light value so an installed window and
  // a browser tab do not tint differently on the default paint.
  themeColor: "#f3f3f4",
};

// Every route in this app is behind Keycloak login EXCEPT /signup (Phase 0
// Stage 5) - see AuthProvider's PUBLIC_ROUTES exemption for why that one
// route needs to render before any Keycloak session can possibly exist. The
// gate still lives here at the root layout, not per-page - AuthProvider
// itself decides whether to actually enforce it for the current route, and
// (visual redesign pass) now renders its own branded LoginLanding screen for
// an unauthenticated visitor rather than forcing an immediate redirect to
// Keycloak's hosted login page (AuthProvider uses onLoad: "check-sso", not
// "login-required" - see lib/auth.js).
export default async function RootLayout({ children }: { children: React.ReactNode }) {
  // Locale decided by proxy.js (geo/Accept-Language on first visit,
  // the NEXT_LOCALE cookie on every visit after - including an explicit
  // reset back to English via LanguageSwitcher). Read here, server-side,
  // so the very first HTML response is already in the right language -
  // no client-side flash of English before a translated version swaps in.
  //
  // Cookie first, then the x-locale request header proxy.js sets on
  // every request as a fallback. The header matters specifically on a
  // visitor's very first request: proxy's response.cookies.set(...)
  // only reaches the browser via Set-Cookie - it can't retroactively add
  // itself to THIS request's cookies() read, since that request already
  // left the browser without it. Without the header fallback, a brand-new
  // visitor's first page would render in English even when proxy.js
  // just correctly detected e.g. Hindi, correcting itself only on the next
  // navigation. The header closes that one-request gap.
  const cookieStore = await cookies();
  const headerStore = await headers();
  // Per-request CSP nonce minted by proxy.js (lib/csp.ts). Every inline
  // <script> below must carry it or the enforced policy blocks it; Next.js
  // stamps its own script tags from the same request's CSP header.
  const nonce = headerStore.get(NONCE_HEADER) || undefined;
  // 2026-09-19: a locale-prefixed URL ("/hi/billing") outranks the cookie -
  // x-url-locale is set by proxy.js only on those rewrites, and is stripped
  // from every other request so a client cannot forge it.
  const requestedLocale =
    headerStore.get("x-url-locale") || cookieStore.get(LOCALE_COOKIE)?.value || headerStore.get("x-locale");
  const locale = LOCALE_META[requestedLocale] ? requestedLocale : DEFAULT_LOCALE;
  // Strip the translation pipeline's _sourceHash bookkeeping (one 16-char
  // hash per key, ~40% of a fully-translated locale file) before the object
  // is serialized into the RSC payload - it exists only for
  // translate-messages.mjs's staleness diffing and would otherwise ship
  // ~150KB of dead metadata to every non-English visitor.
  // Two awaits, not one lookup: `messages` is the active locale and `en` is the
  // fallback bundle I18nProvider needs for any key the active locale is missing.
  // When the visitor is already on English these resolve to the same chunk, and
  // the dynamic-import cache makes the second call free.
  const [activeBundle, enBundle] = await Promise.all([loadMessages(locale), loadMessages(DEFAULT_LOCALE)]);
  const { _sourceHash: _dropSourceHash, ...fullMessages } = activeBundle;
  // Ship only the namespaces this route can reach (prod latency pass,
  // 2026-09-24): the full catalog was ~70% of every page's HTML. x-pathname
  // is set by proxy.js on every request (the bare, un-prefixed path for a
  // locale-prefixed URL); the vd_authed cookie is AuthProvider's "has a
  // session" hint, which lets a signed-in visitor skip the 115 KB landing
  // namespace everywhere but "/". An unknown pathname ships everything.
  // I18nProvider lazily completes a pruned catalog from /api/i18n/<locale>.
  // See app/lib/i18n-namespaces.ts.
  // The same hint is also handed to <AuthProvider> as a prop (authedHint): the
  // "/" optimistic-route splash-vs-landing decision happens during render, so
  // it must use a value the server also knew - reading localStorage there
  // produced a React #418 hydration mismatch for every signed-in visitor.
  const authedHint = hasAuthedHint(cookieStore.get(AUTHED_HINT_COOKIE)?.value);
  const inlineNamespaces = namespacesForRequest(headerStore.get("x-pathname"), authedHint);
  const messages = pruneBundle(fullMessages, inlineNamespaces);
  const enMessages = pruneBundle(enBundle, inlineNamespaces);
  const catalogComplete = inlineNamespaces === null;
  const catalogVersion = catalogComplete ? undefined : await messagesVersion(locale);
  const dir = LOCALE_META[locale]?.dir || "ltr";

  // Geo-targeting for the logged-out public landing (GenesisExperience ->
  // DemoLanding, see components/demo-landing/): Cloudflare sets CF-IPCountry
  // on every request that passes through its edge (the platform's whole
  // browser-facing surface runs behind the Cloudflare tunnel — see
  // CLAUDE.md's Rule 0), so it's read here, server-side, same pattern as
  // the locale read above. Missing (local/non-Cloudflare requests) or
  // Cloudflare's own "XX" unknown-country sentinel both fall back to the
  // neutral "global" variant inside resolveLandingVariant() - never IN by
  // default. Handed down via GeoProvider (lib/geo.tsx) rather than as a
  // plain prop because AuthProvider (components/AuthProvider.tsx) hardcodes
  // <GenesisExperience /> for an unauthenticated visitor instead of
  // rendering the {children} this layout passes it - a context provider
  // fed by this server-computed value is what actually reaches it.
  const countryCode = headerStore.get("cf-ipcountry");
  const landingVariant = resolveLandingVariant(countryCode);

  return (
    <html
      lang={locale}
      dir={dir}
      className={`${geist.variable} ${geistMono.variable} ${notoSansDevanagari.variable} ${yatraOne.variable} ${jakarta.variable} ${dmMono.variable}`}
      // The NO_FLASH_SCRIPT/UI_MODE_NO_FLASH_SCRIPT tags below intentionally
      // set data-accent/data-mode/data-ui-mode on this element BEFORE React
      // hydrates (that's the whole point - no flash of the default theme).
      // React's hydration diff has no way to know that's deliberate, so it
      // logs a hydration-mismatch warning on every load even though nothing
      // is actually broken. suppressHydrationWarning tells React to skip
      // the diff on this element only - the standard fix for exactly this
      // pattern (it's what next-themes does for the same reason).
      suppressHydrationWarning
    >
      <head>
        {/* Runtime public config (lib/env.ts): window.__ENV from THIS
         * container's env at request time, so one prebuilt image works on
         * any domain. MUST stay the first element in <head> - it has to run
         * before any client module reads getPublicEnv(). Allowlisted keys
         * only; see web/docs/RUNTIME-CONFIG.md. */}
        <script id="__public-env" nonce={nonce} dangerouslySetInnerHTML={{ __html: publicEnvScript() }} />
        {/* Theme customizer (ThemeProvider.js) - applies the saved accent/
         * mode synchronously, before React hydrates or the first paint
         * happens, so a returning tenant never sees a flash of the default
         * indigo/light theme before their saved choice kicks in.
         *
         * Plain pre-hydration inline script (App Router idiom): it runs
         * during the SSR pass before hydration. Using next/script with the
         * body passed as CHILDREN is what trips React 19 / Next 16's
         * "Encountered a script tag while rendering" warning, so we use a
         * <script> with dangerouslySetInnerHTML instead - same synchronous
         * pre-paint timing, no warning. */}
        <script id="no-flash-theme" nonce={nonce} dangerouslySetInnerHTML={{ __html: NO_FLASH_SCRIPT }} />
        {/* Console typography density (direct feedback, 2026-08-03): the
         * root font-size that shrinks every rem-based Tailwind utility
         * sitewide (globals.css's html[data-density="console"] rule) is
         * meant for the logged-in console (GitHub/Linear/Stripe-dashboard
         * density), not the public marketing landing page - applying it
         * everywhere left the marketing container narrower (936px) than
         * the ~1140-1200px industry-standard content width, and every
         * touch target ~19% smaller than intended.
         *
         * There is no server-readable "is this visitor logged in" signal
         * in this app (Keycloak's session cookie lives on the auth. host,
         * a different origin the app server can't read - see AuthProvider's
         * check-sso comment) - so this can't be decided in the root layout
         * the way locale/theme are. Same pre-hydration-script technique as
         * NO_FLASH_SCRIPT above, keyed off AuthProvider's own "vd_authed"
         * localStorage hint (the same one it already uses to avoid a
         * landing-vs-dashboard flicker on refresh) so a RETURNING
         * authenticated visitor gets console density with no flash; a
         * visitor whose very first load ever resolves authenticated still
         * gets one corrective reflow once AuthProvider's status settles
         * (see its useEffect) - an accepted, bounded trade-off given there
         * is no earlier signal to key off. */}
        <script id="no-flash-density" nonce={nonce} dangerouslySetInnerHTML={{ __html: NO_FLASH_DENSITY_SCRIPT }} />
        {/* Transport warm-up: the first API call and the Keycloak session
         * check each pay DNS+TCP+TLS (~2 RTTs through Cloudflare, measured
         * ~1s to the API host cold) before any bytes move. Preconnect
         * starts those handshakes while the HTML/JS is still downloading.
         * crossOrigin="anonymous" matches the CORS-mode Bearer fetches
         * apiFetch makes - without it the warmed socket isn't reusable for
         * the actual request. Rendered only when the origin is configured,
         * never emitting the localhost dev default. */}
        {getPublicEnv("NEXT_PUBLIC_API_BASE_URL") ? (
          <link rel="preconnect" href={getPublicEnv("NEXT_PUBLIC_API_BASE_URL")} crossOrigin="anonymous" />
        ) : null}
        {getPublicEnv("NEXT_PUBLIC_KEYCLOAK_URL") ? (
          <link rel="preconnect" href={getPublicEnv("NEXT_PUBLIC_KEYCLOAK_URL")} crossOrigin="anonymous" />
        ) : null}
      </head>
      {/* suppressHydrationWarning here too (not just <html>) - browser
       * extensions like Grammarly inject their own data-gr-* attributes
       * directly onto <body> before React hydrates, which otherwise trips a
       * "server/client mismatch" console error for attributes this app never
       * rendered and has no control over. Same documented pattern as the
       * <html> tag above, just covering the one other element an extension
       * is known to touch. */}
      <body className={jakarta.className} suppressHydrationWarning>
        {/* JSON-LD structured data (STRUCTURED_DATA, root-structured-data.ts). JsonLd
         * escapes it (STRUCTURED_DATA carries operator env values). */}
        <JsonLd data={STRUCTURED_DATA} />
        {/* Redesign 2026-07-21: GlobalCursorFX (magnetic button pull + cursor
         * spotlight) and GlobalBackgroundFX (full-screen click-ripple canvas)
         * were removed. Applied to every button/screen app-wide, they read as
         * cheap/template-y flourish and made buttons feel non-standard (things
         * physically moving toward the cursor on hover). The components were
         * deleted in the 2026-09-30 dead-code pass (git history has them). */}
        <I18nProvider
          locale={locale}
          messages={messages}
          enMessages={enMessages}
          complete={catalogComplete}
          version={catalogVersion}
        >
          <GeoProvider country={countryCode} variant={landingVariant}>
            {/* Inside I18nProvider: ToastHost calls useTranslations(), which
             * throws if rendered outside the provider. Kept above ThemeProvider
             * so toasts still render regardless of theme state. */}
            <ToastHost />
            <ThemeProvider>
              {/* Reads/writes ThemeProvider's own accent+mode state, so it has
               * to live inside the provider, not alongside GlobalCursorFX
               * above - see WeatherThemeEngine.js for what it does with them. */}
              <WeatherThemeEngine />
              {/* Platform announcement banner + the /api/public/status poll that
               * also drives AppShell's maintenance screen (platformStatus.ts).
               * Above AuthProvider so public pages show the banner too. */}
              <PlatformStatusBanner />
              {/* Read-only view-as bar (platform-admin impersonation, viewAs.ts).
               * Above AuthProvider so it can lift `#grant=` off /view-as before a
               * sign-in redirect drops the fragment. Renders nothing otherwise. */}
              <ViewAsBanner />
              {/* Simple/Technical UI mode (UiModeProvider.js) removed - direct
               * feedback that Simple mode hid the entire Build/Connect nav
               * (Agents/Workflows/Knowledge Base), leaving no way to create or
               * use agents at all. One console, always full-featured, rather
               * than maintaining two experiences. UiModeProvider.js was deleted in the
               * 2026-09-30 dead-code pass. (The
               * TopNav/MarketingHome files this used to cite as precedent for
               * that posture were deleted in the 2026-07-29 dead-code pass -
               * git history, not the working tree, is the archive.) */}
              {/* SystemHealthProvider is mounted here at the root (inside
               * AuthProvider so it only polls once a session context exists) —
               * NOT inside AppShell. Pages wrap their own content in <AppShell>,
               * so a page component sits above AppShell in the tree; a root-level
               * provider is what lets those page-level useSystemHealth() calls
               * (e.g. the Knowledge page's sidecar gate) and AppShell's own
               * core-down maintenance gate read the same single polled signal. */}
              <AuthProvider authedHint={authedHint}>
                {/* Session-lived shell chrome (tenantSettings/account/avatar/MCP
                 * attention badge/usage quota) — mounted once here instead of
                 * inside AppShell (which remounts every navigation, since it's
                 * wrapped per-page rather than hosted by a shared layout - see
                 * RouteTransition.tsx's module comment). Before this, those
                 * fetches re-ran on every single click; see
                 * AppChromeProvider.tsx's own header comment for the full
                 * perf-audit context (2026-08-01). Inside AuthProvider so it
                 * can gate its fetches on a real session via useAuth(). */}
                <AppChromeProvider>
                  {/* Per-login random accent (one per Keycloak login, stable
                   * across refreshes, new on each login). Mounted here because
                   * AppChromeProvider is the authenticated console subtree; it
                   * self-gates on auth.isAuthenticated() so the pass-through on
                   * public routes leaves logged-out visitors on the landing
                   * per-refresh random. WeatherThemeEngine stays ABOVE AuthProvider
                   * (it must run for logged-out visitors too); its accent now
                   * yields to this layer via ThemeProvider.setWeatherAccent. */}
                  <SessionAccentEngine />
                  <SystemHealthProvider>
                    {/* Polled, localStorage-diffed "the platform-hosted model
                     * changed" notice for FREE-tier tenants (2026-07-26 feedback)
                     * - see PlatformModelChangeNotice.tsx's own header comment
                     * for why this is a light poll-and-diff rather than a
                     * per-turn signal. Renders nothing; only mounted for its
                     * effect, same "no UI of its own" shape as SystemHealthProvider. */}
                    <PlatformModelChangeNotice />
                    {/* First-run product tour (welcome modal → spotlighted
                     * steps → completion), mounted here rather than inside
                     * AppShell for the same reason AppChromeProvider is here:
                     * AppShell remounts on every navigation (it's wrapped
                     * per-page, not hosted by a shared layout), which would
                     * reset tour progress the moment a step's own action
                     * navigated to a new route. TourProvider persists across
                     * navigation; TourOverlay renders the actual UI. */}
                    <TourProvider>
                      <TourOverlay />
                      {/* Non-blocking floating loading icon that holds until each
                       * page's data is actually ready (see RouteTransition) —
                       * shows while a slow fetch is still in flight without
                       * covering the page. Wraps children so pages can opt in
                       * via usePageReady(). */}
                      <RouteTransition>{children}</RouteTransition>
                    </TourProvider>
                  </SystemHealthProvider>
                </AppChromeProvider>
              </AuthProvider>
            </ThemeProvider>
          </GeoProvider>
        </I18nProvider>
      </body>
    </html>
  );
}
