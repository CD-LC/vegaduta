import type { MetadataRoute } from "next";
import { AI_CRAWLERS } from "./lib/ai-crawlers";
import { siteUrl } from "./lib/site-url";

// robots.txt (SEO foundations pass, 2026-07-25). Served by Next's Metadata
// API at /robots.txt on both the apex host and the app. host.
//
// The disallow list is the authenticated console + API surface — every real
// route directory under web/app/ that is not part of the public funnel
// (landing, /try, /explore, /billing, /signup, /login, /privacy, /terms,
// /docs, /help/workflow-recipes). /ui-kit is technically in PUBLIC_ROUTES
// (lib/auth.ts) but it's an internal component gallery, not a page worth
// indexing, so it's disallowed too. /signup/complete and /help/docs are
// gated sub-pages of otherwise-public sections.
//
// /features and /help are intentionally NOT in this disallow list (despite
// both being wrapped in <AppShell>, which normally means "authenticated
// console screen" - see /build, /templates etc. below). AuthProvider's
// ANONYMOUS_RENDER_ROUTES (AuthProvider.tsx) special-cases these two exact
// paths to render their real page content - wrapped in an anonymous
// AuthContext - for a logged-out visitor/crawler instead of swapping in the
// homepage, so the duplicate-content/cloaking risk a prior version of this
// comment warned about is fixed at the source. Do not re-disallow them
// without first checking that route still special-cases in AuthProvider.
//
// /a/ was previously listed here too, but that combination is backwards: the
// per-share layout (a/[shareId]/layout.tsx) already sets a page-level
// `robots: {index:false, follow:false}`, and per Google's documented
// crawling behavior a Disallow'd URL is never fetched at all — so its
// meta-robots tag is never read, and an externally-linked /a/<id> page can
// still surface in search results as a bare "no information available" URL
// instead of being fully excluded. /agent/[shareId] and /w/[shareId] rely on
// noindex alone (no robots.ts entry) for exactly this reason; /a/ now
// matches that pattern instead of being the one inconsistent entry.
//
// AI CRAWLERS (GEO pass, 2026-09-19). Policy: every AI crawler is welcome on
// the public site, under exactly the rules everyone else gets. They are named
// explicitly, even though "*" already admitted them, for two reasons:
//   1. It is a decision, written down. The common failure is a later
//      "block the scrapers" change (here, or a Cloudflare "block AI bots"
//      toggle) removing the site from ChatGPT/Claude/Perplexity answers
//      without anyone deciding to. Whoever does that now has to delete this.
//   2. The three kinds are different, and blocking one is NOT blocking the
//      others — training (GPTBot, ClaudeBot, CCBot, Google-Extended,
//      Applebot-Extended), search index (OAI-SearchBot, Claude-SearchBot,
//      PerplexityBot — these decide whether an answer can CITE us), and
//      user-triggered fetch (ChatGPT-User, Claude-User, Perplexity-User).
//      If training access is ever withdrawn, the index and user agents must
//      stay, or the site disappears from AI search entirely.
// Google-Extended is a robots TOKEN, not a crawler: it governs Gemini training
// only and has no effect on AI Overviews / AI Mode, which use Googlebot and
// ordinary snippet controls.
//
// THE TRAP: a crawler that matches a named group IGNORES the "*" group
// completely. A bare `{ userAgent: "GPTBot", allow: "/" }` would therefore
// open /admin, /agents and the rest of the console to it. The named group
// below repeats the full disallow list for that reason — keep them in sync
// (they share DISALLOW; robots.test.ts asserts it). The token list itself is in
// lib/ai-crawlers.ts, shared with proxy.js so the bots we admit and the bots we
// count are the same list.
// Request-time (SE-05): the Sitemap line must carry the deployment origin from
// the pod environment, not the localhost default frozen at `next build`.
export const dynamic = "force-dynamic";

export default function robots(): MetadataRoute.Robots {
  return {
    rules: [
      { userAgent: "*", allow: "/", disallow: DISALLOW },
      { userAgent: AI_CRAWLERS, allow: "/", disallow: DISALLOW },
    ],
    sitemap: `${siteUrl()}/sitemap.xml`,
  };
}

const DISALLOW = [
  "/api/",
  "/admin",
  "/agents",
  // "/ai" itself (the public SEO/AEO hub) is deliberately ALLOWED -
  // only the segment-distinct authenticated console route below it
  // is blocked. See PUBLIC_ROUTES' matchesPublicRoute in lib/auth.ts:
  // segment-aware matching keeps "/ai" from ever covering this.
  "/ai-models",
  "/build",
  "/clips",
  "/code-intel",
  "/data-usage",
  "/devices",
  "/edge",
  "/files",
  "/jobs",
  "/knowledge",
  "/playground",
  "/providers",
  "/quick-task",
  "/shared-with-me",
  "/studio",
  // "/templates" itself is deliberately ALLOWED (2026-07-31): the
  // gallery became a public, server-rendered marketing surface
  // (web/app/templates/page.tsx). Only the caller-specific
  // sub-route stays blocked.
  "/templates/mine",
  "/tools",
  "/ui-kit",
  "/usage",
  "/workflows",
  "/signup/complete",
  "/help/docs",
  "/help/controls",
];
