import nodeProcess from "node:process";
import { NextResponse } from "next/server";
import { verifyPlatformAdmin } from "./app/platform-admin/lib/serverAuth";
import {
  SUPPORTED_LOCALES,
  DEFAULT_LOCALE,
  LOCALE_COOKIE,
  splitLocalePrefix,
  isLocalizedRoute,
} from "./app/lib/i18n-config";
import { classifyAiCrawler, classifyAiReferral } from "./app/lib/ai-crawlers";
import { matchesPublicRoute } from "./app/lib/publicRoutes";
import { isOssSite, isCommercialRoute } from "./app/lib/siteMode";
import { isBillingEnabled, isBillingRoute, billingOffRedirect } from "./app/lib/billing-flag";
import { declaredEdition, isPersonalDenied, isPersonalPlatformAdminAllowed, parseEdition } from "./app/lib/edition";
import {
  NONCE_HEADER,
  buildPageCsp,
  buildStaticHtmlCsp,
  createNonce,
  cspHeaderName,
  isEmbedPath,
  staticFilePolicy,
} from "./app/lib/csp";

// Runs once per request, before any page renders. Two jobs.
//
// 1. Locale-prefixed URLs (multilingual SEO, 2026-09-19). "/hi" and
//    "/hi/billing" are real, crawlable addresses for the Hindi versions of
//    "/" and "/billing". There is no app/[locale]/ directory: the prefix is
//    stripped here and the request is REWRITTEN to the existing route with
//    the locale pinned by the URL. See i18n-config.ts for which locales and
//    which routes qualify, and why both lists are short.
// 2. Everything else: decide the locale for this visitor and set it as a
//    cookie. That path never redirects or rewrites, so every unprefixed route
//    and every existing Playwright spec keeps working against the same URLs.
//
// Crawlers are never redirected by language or geography - Googlebot sends no
// Accept-Language and crawls from the US, so a negotiated redirect would hide
// every non-English page from it. Negotiation only ever changes what the
// UNPREFIXED url renders; a prefixed URL always renders its own language.
//
// Order of precedence for the unprefixed path (first match wins):
//   1. An existing NEXT_LOCALE cookie - once a person has explicitly chosen
//      a language via LanguageSwitcher (or a locale-prefixed URL), that
//      always wins on every later request.
//   2. DEFAULT_LOCALE (English).
//
// Automatic language detection is DISABLED (2026-10-05, direct instruction):
// neither the geo hint (cf-ipcountry / x-vercel-ip-country) nor the
// browser's Accept-Language header changes what an unprefixed URL renders.
// Both were guessing on the visitor's behalf - and the non-English renders
// currently fail to hydrate (dead buttons/language switcher), so a detected
// visitor got a broken, non-interactive page they could not switch out of.
// Every language remains one explicit choice away via the language switcher
// or a locale-prefixed URL; nothing decides for them any more.
const SUPPORTED_CODES = new Set(SUPPORTED_LOCALES.map((l) => l.code));

// Vulnerability-scanner probes (security audit 2026-09-18). Hundreds of bots a
// day walk a fixed list of paths that can never exist in this app - WordPress,
// PHP, dotfiles, cloud credential files. Left alone each one renders the full
// not-found page (481 KB measured in production) through the whole React tree.
// Answering here costs a few bytes and no render. This is NOT a security
// boundary - none of these files exist either way - it is cost control, and it
// lives in the app because the edge WAF rule that would do the same thing is
// one more control that disappears on the next hosting move. /.well-known is
// deliberately not matched.
const SCANNER_PATH =
  /(^|\/)\.(env|git|svn|hg|aws|ssh|docker|vscode|idea|DS_Store|htaccess|htpasswd)(\.|\/|$)|\.(php\d?|phtml|asp|aspx|jsp|jspx|cgi|bak|sql|sqlite|old|swp)$|^\/(wp-|wordpress|phpmyadmin|pma|cgi-bin|vendor\/|xmlrpc|adminer|_ignition|telescope|actuator|solr|boaform|HNAP1|owa\/|ecp\/|autodiscover)/i;

// Bare/www marketing hosts -> the host the console actually lives on. Only
// the two real apex domains are listed; localhost, container names and every
// already-subdomained host (app./api./auth./...) never match, so dev, compose
// and e2e are untouched. vegaduta.xyz redirects to .ai at the edge anyway -
// it's here for defence-in-depth on compose staging where no edge exists.
const APEX_TO_APP_HOST = {
  "vegaduta.ai": "app.vegaduta.ai",
  "www.vegaduta.ai": "app.vegaduta.ai",
  "vegaduta.xyz": "app.vegaduta.xyz",
  "www.vegaduta.xyz": "app.vegaduta.xyz",
};

// Query params that mean "this URL is an OIDC callback in flight" - see the
// comment at the redirect site for why these must never be host-redirected.
const OIDC_CALLBACK_PARAMS = ["code", "state", "session_state", "iss", "error", "error_description"];

// A trailing extension means a file under public/ or a metadata route
// (robots.txt, sitemap.xml, silent-check-sso.html, /media/*), not a page.
const FILE_LIKE_PATH = /\.[a-z0-9]{1,12}$/i;

// AI visibility log (GEO pass, 2026-09-19). Before this there was no way to
// answer either "do AI crawlers fetch this site at all?" or "has an assistant
// ever sent us a visitor?" - nothing read the User-Agent and nothing looked at
// the Referer. One JSON line per hit to stdout, which Container Apps ships to
// Log Analytics; see docs/plans/GEO-STRATEGY-2026-09-19.md for the queries.
//
// Deliberately NOT routed through the landing beacon: that pipeline's contract
// is "no visitor data beyond +1" and widening it needs core-3. What is logged
// here is the crawler's own published name or an assistant's hostname bucket,
// plus OUR path - never an IP, never a full User-Agent, never the Referer's
// path or query (which can contain the person's prompt), never a cookie.
// Pages only: a crawler fetching 40 chunks is one visit, not 41.
function logAiVisibility(request) {
  const path = request.nextUrl.pathname;
  if (path.startsWith("/_next/") || /\.[a-z0-9]{2,5}$/i.test(path)) {
    // Static files are noise, with three exceptions that ARE the signal.
    if (path !== "/robots.txt" && path !== "/llms.txt" && path !== "/sitemap.xml") return;
  }
  const bot = classifyAiCrawler(request.headers.get("user-agent"));
  if (bot) {
    console.log(JSON.stringify({ evt: "ai_crawler", bot, path }));
    return;
  }
  const source = classifyAiReferral(request.headers.get("referer"), request.nextUrl.searchParams.get("utm_source"));
  if (source) console.log(JSON.stringify({ evt: "ai_referral", source, path }));
}

// Content-Security-Policy (security review FE-002, 2026-09-30) - ENFORCED,
// built per request from runtime config, not in next.config.js (frozen at
// build time) - see app/lib/csp.ts. Three cases:
//  - a page: a fresh nonce; the policy goes on the response AND on the
//    forwarded request headers (Next.js reads the nonce from the request's
//    CSP header for its own <script> tags; layout.tsx reads x-nonce);
//  - the four static HTML files with a policy of their own
//    (silent-check-sso, sandbox-frame, app-frame, run-app), and any other
//    public *.html (operator-authored, no nonce possible);
//  - other files (images, service-worker scripts, manifests): no CSP, as a
//    policy on them would govern nothing useful or would bind the service
//    worker to a nonce it can never carry.
// CSP_REPORT_ONLY=true switches the header to report-only (operator escape
// hatch; see csp.ts).
// Privileged route handlers the merged platform-admin section added under
// app/platform-admin/api/ (the Academy admin proxy - they forward to a
// privileged upstream using the server-side SCHOLARSHIP_ADMIN_KEY). The
// handlers themselves already call verifyPlatformAdmin; this is the
// defence-in-depth net web-admin's own proxy.ts carried for the same
// reason - a future route added under this prefix that forgets the check
// must fail closed here, not silently proxy. Scoped to exactly that prefix:
// /api/client-log stays deliberately unauthenticated (see that route's own
// comment), which is why the matcher exclusion of /api/* below is fine.
export function isPlatformAdminPrivilegedApiPath(pathname) {
  return pathname === "/platform-admin/api/academy" || pathname.startsWith("/platform-admin/api/academy/");
}

// True when this install is the personal edition, read from the container's REAL environment.
// NOT lib/edition.ts's edition(): that goes through getPublicEnv, and the proxy bundle's `process`
// shim may hold only build-time values (see the PLATFORM_ADMIN_HOST note in route()), so a runtime-only
// NEXT_PUBLIC_EDITION=personal would be invisible to it. `node:process` is the live process. Same
// precedence as declaredEdition(): local auth is personal (an explicit lite|full never overrides it), else an explicit NEXT_PUBLIC_EDITION, else
// NEXT_PUBLIC_DEPLOY_ENV=lite|full names a shared tier; only when the runtime env says nothing at all
// does it fall back to declaredEdition() (the build-time baked values). Everything non-personal
// returns false here, so the 404 block in route() never runs for lite, full or an undeclared install.
export function isPersonalEditionAtRuntime() {
  const env = nodeProcess.env;
  if ((env.NEXT_PUBLIC_AUTH_MODE || "").trim().toLowerCase() === "local") return true;
  const explicit = parseEdition(env.NEXT_PUBLIC_EDITION);
  if (explicit) return explicit === "personal";
  const deploy = parseEdition(env.NEXT_PUBLIC_DEPLOY_ENV);
  if (deploy === "lite" || deploy === "full") return false;
  return declaredEdition() === "personal";
}

export async function proxy(request) {
  const headerName = cspHeaderName();
  const pathname = request.nextUrl.pathname;
  // Defence-in-depth auth for the privileged platform-admin API routes
  // (see isPlatformAdminPrivilegedApiPath above). Runs before everything
  // else - a denied request never reaches routing.
  if (isPlatformAdminPrivilegedApiPath(pathname)) {
    const result = await verifyPlatformAdmin(request);
    if (!result.ok) {
      return NextResponse.json({ error: result.error }, { status: result.status });
    }
  }
  if (FILE_LIKE_PATH.test(pathname)) {
    const response = route(request, null);
    const filePolicy = staticFilePolicy(pathname) || (/\.html?$/i.test(pathname) ? buildStaticHtmlCsp() : null);
    if (filePolicy) response.headers.set(headerName, filePolicy);
    return response;
  }
  const nonce = createNonce();
  const policy = buildPageCsp({
    nonce,
    dev: process.env.NODE_ENV === "development",
    frameable: isEmbedPath(splitLocalePrefix(pathname).pathname),
  });
  const response = route(request, { nonce, policy, headerName });
  response.headers.set(headerName, policy);
  return response;
}

// Also the /api/eval/* route handlers (they read repository files when present).
const DEV_ONLY_ROUTE = /^\/(api\/)?(ui-kit|eval)(\/|$)/;
export function isDevOnlyRoute(pathname) {
  return DEV_ONLY_ROUTE.test(pathname);
}
function devRoutesEnabled() {
  return process.env.NODE_ENV !== "production" || process.env.ENABLE_DEV_ROUTES === "true";
}

// Adds the nonce + policy to headers forwarded to the page render.
function withCsp(headers, csp) {
  if (csp) {
    headers.set(NONCE_HEADER, csp.nonce);
    headers.set(csp.headerName, csp.policy);
  } else {
    // Never let a client supply these.
    headers.delete(NONCE_HEADER);
  }
  return headers;
}

function route(request, csp) {
  if (SCANNER_PATH.test(request.nextUrl.pathname)) {
    return new NextResponse(null, { status: 404, headers: { "cache-control": "public, max-age=3600" } });
  }

  logAiVisibility(request);

  // Developer-only surfaces (security review FE-017): the component gallery
  // (/ui-kit, with its mock API) and the edge-model eval harness (/eval) are
  // not product routes. They 404 in a production server unless an operator
  // opts in with ENABLE_DEV_ROUTES=true (read at runtime).
  if (isDevOnlyRoute(splitLocalePrefix(request.nextUrl.pathname).pathname) && !devRoutesEnabled()) {
    return new NextResponse(null, { status: 404 });
  }

  // Plain-HTTP visitors (security audit 2026-09-18). Cloudflare talks HTTPS to
  // the origin whatever the visitor used, so production answered 200 over
  // http:// with no redirect, and HSTS only protects someone who has already
  // been here over https once. Cloudflare reports the visitor's real scheme in
  // CF-Visitor; redirecting on it makes this hold even when the zone's "Always
  // Use HTTPS" switch is off. Absent header (local dev, compose, e2e) = no-op.
  // The target is the request's OWN URL with the scheme flipped - never the
  // Host header: anyone reaching the origin directly (Compose, Helm, no
  // Cloudflare) could send Host: evil.example plus this header and get a
  // cacheable 308 to evil.example (open redirect).
  if ((request.headers.get("cf-visitor") || "").includes('"scheme":"http"')) {
    const secure = request.nextUrl.clone();
    secure.protocol = "https:";
    return NextResponse.redirect(secure, 308);
  }

  // Console-host canonicalisation (2026-09-28). The same Next app serves both
  // the marketing site (vegaduta.ai apex) and the console (app.vegaduta.ai),
  // and before this block nothing ever moved a visitor from apex to the app
  // host: sign in from the landing and the whole console session ran on
  // vegaduta.ai/*, so every relative rail link (/tools, /agents, /build)
  // resolved on the marketing host - reported as "it's going to
  // https://vegaduta.ai/tools even for a signed-in user". The console's
  // canonical host is app.<domain>; anything that is NOT a public route and
  // is requested on the apex/www host now gets a permanent redirect there.
  //
  // Three deliberate exclusions:
  //  - OIDC callback params (code/state/session_state/iss/error*): keycloak-js
  //    stores the PKCE verifier in the INITIATING origin's localStorage, so
  //    the redirect back from auth.vegaduta.ai must complete on the same
  //    origin that started login or the token exchange fails. Apex is a
  //    registered redirect URI precisely so this works; the NEXT navigation
  //    (page.tsx's / -> /build) then lands on the app host via this rule.
  //  - "/" (the marketing homepage): deliberately NEVER host-redirected.
  //    keycloak-js's default response_mode is "fragment", which puts the
  //    auth code where a proxy can never see it - so "/?no-params" is
  //    indistinguishable from "/#code=..." mid-callback here, and bouncing
  //    that to app.* would strand the callback on an origin with no PKCE
  //    verifier. The cookie hint cannot disambiguate it either: a returning
  //    user re-signing in HAS vd_authed set when their callback lands.
  //    Consequence: the post-login landing on apex still renders for one
  //    beat, then "/" -> "/build" client-side and THIS rule moves the
  //    console hop to the app host.
  //  - File paths (any extension in the last segment): /silent-check-sso.html
  //    is fetched inside a same-origin iframe by self-gating public pages -
  //    redirecting it cross-origin would break the silent check's postMessage
  //    origin test - plus robots.txt/sitemap.xml/llms.txt/manifest/icons.
  // Platform-admin host compat (merged web-admin): the standalone console
  // lived at admin.<domain>/*; its routes now live at /platform-admin/* in
  // this app. Traffic on the admin host (PLATFORM_ADMIN_HOST, set by the
  // chart from global.hosts.admin) is redirected once, so old links and the
  // admin.<domain> host keep working. Static/runtime assets pass through
  // untouched so the app shell can still serve on this host.
  // NOT process.env.<NAME> - the proxy bundle's `process` shim holds only
  // build-time values, so runtime env never reaches it. `node:process` is
  // treated as an external (like node:crypto further down this file) and
  // resolves to the real Node process, whose env is live at request time.
  // (Verified on the deployed image: process.env.X was empty here while the
  // pod env had it; node:process sees the container env.)
  const adminHost = (nodeProcess.env.PLATFORM_ADMIN_HOST || "").toLowerCase();
  // NOTE the Host HEADER, not request.nextUrl.hostname: in the standalone
  // server nextUrl is built from the bind address (0.0.0.0), not the request
  // Host. ingress-nginx passes the real host through intact.
  const requestHost = (request.headers.get("host") || "").split(":")[0].toLowerCase();
  if (
    adminHost &&
    requestHost === adminHost &&
    !request.nextUrl.pathname.startsWith("/platform-admin") &&
    // The public docs page (shared 404/error/shell links point at /docs) is not
    // an admin route; redirecting it gave /platform-admin/docs, a 404 (SE-82).
    request.nextUrl.pathname !== "/docs" &&
    !request.nextUrl.pathname.startsWith("/docs/") &&
    !request.nextUrl.pathname.startsWith("/_next") &&
    !request.nextUrl.pathname.startsWith("/api/") &&
    !FILE_LIKE_PATH.test(request.nextUrl.pathname)
  ) {
    // Build the target from the request's Host header - nextUrl's origin is
    // the standalone bind address, which would leak into the Location.
    const scheme = (request.headers.get("x-forwarded-proto") || "http").split(",")[0].trim() || "http";
    const targetPath = `/platform-admin${request.nextUrl.pathname === "/" ? "" : request.nextUrl.pathname}`;
    return NextResponse.redirect(`${scheme}://${requestHost}${targetPath}${request.nextUrl.search}`, 307);
  }

  const appHost = APEX_TO_APP_HOST[request.nextUrl.hostname.toLowerCase()];
  if (appHost) {
    const barePathname = splitLocalePrefix(request.nextUrl.pathname).pathname;
    const isAuthCallback = OIDC_CALLBACK_PARAMS.some((p) => request.nextUrl.searchParams.has(p));
    // Console routes are never locale-prefixed, so the redirect target drops
    // the prefix the locale block below would have stripped anyway.
    if (
      barePathname !== "/" &&
      !isAuthCallback &&
      !FILE_LIKE_PATH.test(barePathname) &&
      !matchesPublicRoute(barePathname)
    ) {
      const target = request.nextUrl.clone();
      target.hostname = appHost;
      target.port = "";
      target.pathname = barePathname;
      return NextResponse.redirect(target, 308);
    }
  }

  // Billing switch (lib/billing-flag.ts). With NEXT_PUBLIC_BILLING_ENABLED
  // unset - the self-hosted default - there are no plans to sell or manage,
  // so /billing, /billing/*, /admin/billing and their locale variants
  // ("/hi/billing") go to the console instead of rendering. 307, not 308:
  // an operator can switch billing on later without browsers having cached
  // a permanent redirect. Billing ON = this block is skipped entirely.
  // A personal install has no billing whatever NEXT_PUBLIC_BILLING_ENABLED says; the runtime edition is
  // read from the real process env (isPersonalEditionAtRuntime) so a runtime-only
  // NEXT_PUBLIC_EDITION=personal is seen here too.
  if (!isBillingEnabled() || isPersonalEditionAtRuntime()) {
    const billingPath = splitLocalePrefix(request.nextUrl.pathname).pathname;
    if (isBillingRoute(billingPath)) {
      const target = request.nextUrl.clone();
      target.pathname = billingOffRedirect(billingPath);
      target.search = "";
      return NextResponse.redirect(target, 307);
    }
  }

  // Personal edition: the pages a personal install does not have answer a real 404, not just a client
  // gate (components/shell/FeatureGate stays as defence in depth). Placed after the billing block so
  // /billing and /admin/billing keep their redirect (those rows are deny:false in the table). Two sources, both from lib/edition.ts:
  // the EDITION_ROUTES deny rows (console pages, sign-up, /try, /view-as, the A2A discovery files)
  // and /platform-admin paths outside the personal allowlist. lite, full and undeclared installs
  // skip the whole block.
  if (isPersonalEditionAtRuntime()) {
    const personalPath = splitLocalePrefix(request.nextUrl.pathname).pathname;
    if (isPersonalDenied(personalPath) || !isPersonalPlatformAdminAllowed(personalPath)) {
      return new NextResponse(null, { status: 404 });
    }
  }

  // Site-mode switch (lib/siteMode.ts). In the default "oss" mode the
  // sales/pricing/lead-gen pages do not exist; they go to the landing page.
  // 307 so flipping NEXT_PUBLIC_SITE_MODE=classic is not fought by caches.
  if (isOssSite()) {
    const sitePath = splitLocalePrefix(request.nextUrl.pathname).pathname;
    if (isCommercialRoute(sitePath)) {
      const target = request.nextUrl.clone();
      target.pathname = "/";
      target.search = "";
      return NextResponse.redirect(target, 307);
    }
  }

  const existing = request.cookies.get(LOCALE_COOKIE)?.value;
  const existingIsValid = existing && SUPPORTED_CODES.has(existing);

  const { locale: urlLocale, pathname: barePath } = splitLocalePrefix(request.nextUrl.pathname);
  if (urlLocale) {
    const translated = urlLocale !== DEFAULT_LOCALE && isLocalizedRoute(barePath);
    if (!translated) {
      // No such language version: English has exactly one address (the
      // unprefixed one), and a route outside LOCALIZED_ROUTES renders English
      // body copy whatever the prefix says. Send both to the real URL rather
      // than serving a duplicate. 308 for "/en/..." (that mapping is
      // permanent); 307 otherwise, because a route can join LOCALIZED_ROUTES
      // later and a browser-cached permanent redirect would then hide it.
      const target = request.nextUrl.clone();
      target.pathname = barePath;
      const redirect = NextResponse.redirect(target, urlLocale === DEFAULT_LOCALE ? 308 : 307);
      setLocaleCookie(redirect, request, urlLocale);
      return redirect;
    }
    const rewritten = request.nextUrl.clone();
    rewritten.pathname = barePath;
    const rewriteHeaders = withCsp(new Headers(request.headers), csp);
    rewriteHeaders.set("x-locale", urlLocale);
    // x-url-locale is what tells the metadata layer this language came from
    // the URL (so canonical = the prefixed URL) rather than from a cookie on
    // the unprefixed one (canonical = the unprefixed URL).
    rewriteHeaders.set("x-url-locale", urlLocale);
    rewriteHeaders.set("x-pathname", barePath);
    const rewrite = NextResponse.rewrite(rewritten, { request: { headers: rewriteHeaders } });
    // Following a link to a language version is an explicit choice - carry it
    // onto the unprefixed pages this visitor opens next (/try, /docs, ...).
    if (existing !== urlLocale) setLocaleCookie(rewrite, request, urlLocale);
    return rewrite;
  }

  let detected = existing;
  if (!existingIsValid) {
    // No detection at all (2026-10-05): no geo lookup, no Accept-Language
    // negotiation. A first-time visitor gets the operator's chosen default -
    // APP_LOCALE (the personal installer's language pick) when it names a
    // supported locale, English otherwise. Switching languages is always a
    // deliberate act (the switcher, or a "/hi"-style URL above).
    const envLocale = process.env.APP_LOCALE;
    detected = envLocale && SUPPORTED_CODES.has(envLocale) ? envLocale : DEFAULT_LOCALE;
  }

  // Forward the decided locale as a request header too, not just a
  // response cookie. A response cookie only reaches the BROWSER (via
  // Set-Cookie) - it doesn't retroactively appear in this same request's
  // cookies() read inside layout.js's server render. Without this header,
  // a first-time visitor's very first page load would render in English
  // (layout.js sees no cookie yet) even though middleware just correctly
  // detected e.g. Hindi, and the correct locale would only "kick in" one
  // navigation later. The header closes that gap for the current request;
  // the cookie is what makes it stick for every request after this one.
  const requestHeaders = withCsp(new Headers(request.headers), csp);
  requestHeaders.set("x-locale", detected);
  // Never trust these two from the client: on an unprefixed URL there is no
  // URL locale, and the pathname is the one Next is about to route.
  requestHeaders.delete("x-url-locale");
  requestHeaders.set("x-pathname", request.nextUrl.pathname);
  const response = NextResponse.next({ request: { headers: requestHeaders } });

  if (!existingIsValid) {
    // One year, not a session cookie - a returning visitor shouldn't get
    // re-detected (and potentially flip languages) on every new browser
    // session; explicit changes via LanguageSwitcher overwrite this the
    // same way.
    setLocaleCookie(response, request, detected);
  }
  return response;
}

function setLocaleCookie(response, request, locale) {
  response.cookies.set(LOCALE_COOKIE, locale, {
    maxAge: 60 * 60 * 24 * 365,
    path: "/",
    sameSite: "lax",
    // Not unconditional: `next dev` and the e2e stack serve plain http, where
    // a Secure cookie is silently dropped and the locale would never stick.
    secure: request.nextUrl.protocol === "https:" || request.headers.get("x-forwarded-proto") === "https",
  });
}

export const config = {
  // Skip static assets and API routes - no locale decision needed for
  // those, and running this on every /_next/* request would be wasted work.
  matcher: ["/((?!_next/static|_next/image|favicon.ico|api/).*)"],
};
