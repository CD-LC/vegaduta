// ---------------------------------------------------------------------------
// Public-route table + matcher (extracted from lib/auth.ts, 2026-09-28).
//
// This module is deliberately DEPENDENCY-FREE apart from i18n-config: it is
// imported by proxy.js (the Next proxy/middleware layer), where pulling in
// auth.ts would bundle all of keycloak-js into every proxied request. auth.ts
// re-exports both names, so existing imports from "./lib/auth" /
// "../lib/auth" (layout.tsx, AuthProvider.tsx, tests) keep working.
// ---------------------------------------------------------------------------

import { splitLocalePrefix } from "./i18n-config";

// Routes with no Keycloak session to protect - the canonical list, kept
// shared (not duplicated per consumer) so AuthProvider, layout.tsx's
// metadata canonicalisation and proxy.js's apex->app host redirect all agree
// on what "public" means. AuthProvider.tsx imports matchesPublicRoute()
// rather than keeping its own matching logic - see that file's isPublicRoute
// and the per-route history here of why each entry is public.
// "/try" (the no-signup demo sandbox) belongs here for the same reason
// "/signup" does, and more strictly: its entire premise is a visitor who has
// no Keycloak session and is not going to get one. It authenticates itself
// with an opaque demo token held in localStorage, against core's
// permitAll()'d /api/demo/** surface - so a Keycloak round-trip on this
// route is pure latency on the one page that most needs to feel instant.
// "/a/" (public shared-agent viewer, /a/<shareId>) — same premise as "/try":
// the visitor is a stranger with a link, never a Keycloak session. The
// trailing slash predates the segment-aware matcher below (under the old
// bare-startsWith rule a bare "/a" would have made /admin, /agents and /api
// public too); kept because /a itself is not a page, only /a/<id> is.
// "/workflow/" (public shared-WORKFLOW runner, /workflow/<shareId>) was MISSING
// from this list until 2026-08-16, even though app/workflow/[shareId]/page.tsx
// has always been a real, standalone public page — the workflow twin of the
// "/agent/" entry two slots earlier. Without the prefix, AuthProvider treated
// every shared-workflow link as a gated console route, so a logged-out stranger
// opening one got GenesisExperience (the marketing landing hero) instead of the
// workflow they were sent: the share link silently resolved to the homepage.
// Verified safe to make public the same way "/a/" and "/agent/" are — that page
// renders standalone (it never imports AppShell) and its data client
// (app/workflow/[shareId]/api.ts) is deliberately self-contained, hitting the
// permitAll /api/share/workflows/** surface with the shareId as its only
// credential, never lib/api.ts's bearer-token client. Trailing slash for the
// same reason "/a/" and "/agent/" carry one: /workflow itself is not a page,
// only /workflow/<shareId> is.
// "/code" (the browser-local code assistant, 2026-09-12) is the strictest case
// on this list, stricter even than "/try": that page's premise is a visitor who
// has no Keycloak session, while THIS page's premise is that no server is
// involved at all. It opens a folder from the person's own disk through the
// File System Access API, indexes it in the tab and answers from a model
// already on the machine — app/lib/localcode/ contains no fetch() anywhere, so
// there is no token to carry, no tenant to resolve and no quota to spend.
// The only thing that ever gated it was CHROME, not data: app/code/page.tsx
// wrapped itself in AppShell, which calls useAuth() unconditionally, and
// AuthProvider gives a route in this array no AuthContext at all — so the
// entry alone would have thrown on first render. Both halves land together:
// that page now renders standalone and self-gates its own nav with auth.init()
// (the TemplatesPublicChrome pattern), so a signed-in visitor still gets "Back
// to console" and a signed-out one gets a working page rather than a broken
// nav. Verified safe the same way "/a/", "/agent/" and "/workflow/" were.
// NO trailing slash, and that matters more here than for any other entry: this
// app also has an AUTHENTICATED "/code-intel" route. Under the segment-aware
// rule in matchesPublicRoute() below, "/code" matches /code and /code/<sub>
// and leaves /code-intel private; under the pre-2026-08-14 bare-startsWith
// rule it would have swept /code-intel public — the same hazard "/ai" vs
// "/ai-models" is on this list to document.
// "/forms/" (hosted workflow form, /forms/<token>, 2026-10) - the public page of a workflow Trigger node whose
// triggerType is "form". Same premise as "/workflow/": a stranger with a link, no Keycloak session. It renders
// standalone (no AppShell) and its client (app/forms/[token]/api.ts) is self-contained, hitting the permitAll
// GET/POST /api/public/workflow-forms/{token} with the opaque per-node token as its only credential, never the
// bearer-token lib/api.ts client. Trailing slash: /forms itself is not a page, only /forms/<token> is.
export const PUBLIC_ROUTES = [
  "/signup",
  "/explore",
  "/billing",
  "/login",
  "/ui-kit",
  "/terms",
  "/privacy",
  "/pricing",
  "/refund-policy",
  "/shipping-policy",
  "/contact",
  "/account-deletion",
  "/try",
  "/docs",
  "/a/",
  "/w/",
  "/agent/",
  "/workflow/",
  "/forms/",
  "/help/workflow-recipes",
  "/help/workflow-expressions",
  "/whatsapp-cost-calculator",
  "/compare",
  "/why-vegaduta",
  "/templates",
  "/plugins",
  "/directory",
  "/hire",
  "/enterprise",
  "/earn",
  "/about",
  "/integrations",
  "/ai",
  "/platform",
  "/tutorials",
  "/code",
  "/glossary",
  "/status",
  "/api-reference",
  "/changelog",
  // "/downloads" (the Setup app's per-OS installers + release artifacts) - same
  // premise as "/plugins" and "/templates": a public page over data that needs
  // no session (the GitHub release's own asset list), rendered standalone so a
  // logged-out visitor gets it instead of the landing hero.
  "/downloads",
  // The browser device node (core/devices gateway): pairs with a code and
  // holds a device token, never a user session - it must not bounce into
  // the sign-in flow.
  "/device",
];
// "/glossary" (GEO pass, 2026-09-19) is the definitional page — a server
// component in the same standalone chrome as the four hubs below, no auth, no
// data. Without this entry AuthProvider renders the landing over it.
// "/integrations", "/ai", "/platform", "/tutorials" (SEO/AEO hub, 2026-08-14)
// are the four public documentation hubs — fully server-rendered marketing
// pages in the /compare posture. "/ai" is why matching had to become
// segment-aware (matchesPublicRoute below): under the old bare startsWith
// rule it would have swept the authenticated "/ai-models" console route into
// the public branch too.

// "/status", "/api-reference", "/changelog" (Tier-4 public surface,
// 2026-09-23) are the platform-transparency pages - /status polls the
// permitAll GET /api/public/status itself, the other two are fully
// server-rendered; none needs a session.
//
// "/directory" (agent directory, 2026-10-02) is the public browse page for
// GET /api/directory/agents - permitAll in core's SecurityConfig by design:
// discovery precedes authentication, and every field served is a signed
// snapshot the owning tenant explicitly published (see
// AgentDirectoryController's javadoc). Same segment-aware posture as
// "/templates" - covers /directory and any future /directory/<id> detail.

// The one shared matching rule for PUBLIC_ROUTES, used by isPublicPathname()
// in lib/auth.ts, AuthProvider.tsx's isPublicRoute and proxy.js's host
// canonicalisation. Segment-aware (2026-08-14): an entry with a trailing
// slash is a pure prefix ("/a/" matches every /a/<shareId> but not /admin);
// an entry without one matches itself exactly plus its own sub-paths
// ("/compare" matches /compare and /compare/x, but "/ai" no longer matches
// /ai-models). Strictly narrower than the old bare startsWith — every
// previously-public path either matched exactly or under its own "/"-joined
// prefix, so nothing that used to be public went private.
export function matchesPublicRoute(rawPathname: string | null | undefined): boolean {
  if (!rawPathname) return false;
  // "/hi/billing" is "/billing" in Hindi (multilingual SEO, 2026-09-19) - a
  // locale prefix never changes whether a route is public. Idempotent, so
  // callers that already stripped it (AuthProvider) are unaffected.
  const pathname = splitLocalePrefix(rawPathname).pathname;
  return PUBLIC_ROUTES.some((route) =>
    route.endsWith("/") ? pathname.startsWith(route) : pathname === route || pathname.startsWith(route + "/")
  );
}
// "/plugins" (first-party IDE/browser plugin marketplace, 2026-08-09) is the
// same kind of surface as "/templates" directly before it: a public,
// crawlable gallery over a permitAll API (GET /api/plugins/catalog) rendered
// in the templates pages' standalone chrome, which self-gates auth for its
// signed-in nav state exactly like TemplatesPublicChrome already does.
// NOTE: matching is segment-aware (matchesPublicRoute above), so "/templates"
// also covers "/templates/mine" and "/templates/[id]" — that is intentional. The
// public gallery must be crawlable with no auth; "/templates/mine" is a real
// authenticated view but deliberately renders standalone (no AppShell) and
// self-gates auth internally (calls auth.init() and prompts sign-in itself),
// same pattern as this file's other self-gating public-ish routes. Do not
// "fix" this by moving /templates out of PUBLIC_ROUTES without also giving
// mine/[id] their own AppShell wrapping — see components/templates/MySubmissions.tsx.
