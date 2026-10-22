import type { MetadataRoute } from "next";
import { INDUSTRY_INDEX } from "./help/workflow-recipes/recipes-index";
import { INTEGRATIONS } from "./integrations/integrations-data";
import { AI_TOPICS } from "./ai/ai-data";
import { PLATFORM_TOPICS } from "./platform/platform-data";
import { TUTORIALS } from "./tutorials/tutorials-data";
import { INDEXABLE_LOCALES, isLocalizedRoute, localizedPath } from "./lib/i18n-config";
import { hreflangMap } from "./lib/seo-i18n";
import { isPersonalEdition } from "./lib/edition";
import { siteUrl } from "./lib/site-url";
import { isBillingEnabled, isBillingRoute } from "./lib/billing-flag";
import { isCommercialRoute, isOssSite } from "./lib/siteMode";

// Request-time (SE-05): origin and site mode come from the pod environment, so
// the sitemap must not be prerendered at `next build`.
export const dynamic = "force-dynamic";

// lastModified on every entry (SEO audit, 2026-09-09): a sitemap with no
// dates gives crawlers nothing to schedule recrawls against, so a landing
// copy change could sit un-recrawled for weeks. Build time is the honest
// signal this app can actually give — every public page here is statically
// rendered from source (no CMS, no per-page edit dates in the data
// modules), so "the last deploy" IS when any of it last changed. This
// route is evaluated once at build (it reads nothing dynamic), so the value
// is stable across requests until the next deploy, exactly as intended.
const BUILT_AT = new Date();

// sitemap.xml (SEO foundations pass, 2026-07-25). Public funnel URLs only —
// the authenticated console surface is excluded here and disallowed in
// robots.ts. Priorities: landing highest, the no-signup /try sandbox and the
// workflow-recipes library (the two strongest organic entry points) next,
// then the rest of the funnel.
//
// /features and /help: briefly removed then re-added the same day
// (2026-07-30) — AuthProvider's ANONYMOUS_RENDER_ROUTES now renders each
// page's real content (not the homepage) for a logged-out visitor/crawler,
// so the duplicate-content concern that justified removing them is fixed;
// see robots.ts's matching comment.
export default function sitemap(): MetadataRoute.Sitemap {
  // A personal install has no public funnel to advertise (robots.ts disallows everything there).
  if (isPersonalEdition()) return [];
  const BASE = siteUrl();
  // Stamped onto every entry below in one place rather than repeated 20+
  // times — see BUILT_AT above for why build time is the right date.
  return withLastModified(BASE, [
    {
      url: `${BASE}/`,
      changeFrequency: "weekly",
      priority: 1.0,
    },
    {
      url: `${BASE}/try`,
      changeFrequency: "monthly",
      priority: 0.9,
    },
    // "Hiring Papers" landing (2026-07-31) — the employment-dossier funnel
    // entry point (/hire), public + standalone like /try.
    {
      url: `${BASE}/hire`,
      changeFrequency: "weekly",
      priority: 0.8,
    },
    // "/enterprise" — B2B lead generation landing for Agentic AI implementation
    // services (2026-09-25). Google Ads funnel entry point, public + standalone.
    {
      url: `${BASE}/enterprise`,
      changeFrequency: "weekly",
      priority: 0.8,
    },
    // "/earn" — the automation-entrepreneur playbook (2026-08-03): learn
    // agentic AI, automate workflows for businesses, price on outcomes.
    {
      url: `${BASE}/earn`,
      changeFrequency: "weekly",
      priority: 0.8,
    },
    {
      url: `${BASE}/help/workflow-recipes`,
      changeFrequency: "monthly",
      priority: 0.8,
    },
    // Linkable free tool + authority pack (SEO Phase 3 assets, 2026-07-25).
    {
      url: `${BASE}/whatsapp-cost-calculator`,
      changeFrequency: "monthly",
      priority: 0.8,
    },
    {
      url: `${BASE}/compare`,
      changeFrequency: "monthly",
      priority: 0.7,
    },
    {
      url: `${BASE}/compare/n8n-alternative-india`,
      changeFrequency: "monthly",
      priority: 0.8,
    },
    {
      url: `${BASE}/compare/dify-alternative-india`,
      changeFrequency: "monthly",
      priority: 0.8,
    },
    {
      url: `${BASE}/compare/botpress-alternative-india`,
      changeFrequency: "monthly",
      priority: 0.8,
    },
    // /glossary (GEO pass, 2026-09-19) — definitional "what is X" page with
    // primary sources; the query shape answer engines cite most.
    {
      url: `${BASE}/glossary`,
      changeFrequency: "monthly",
      priority: 0.8,
    },
    {
      url: `${BASE}/why-vegaduta`,
      changeFrequency: "monthly",
      priority: 0.7,
    },
    {
      url: `${BASE}/features`,
      changeFrequency: "monthly",
      priority: 0.7,
    },
    {
      url: `${BASE}/help`,
      changeFrequency: "monthly",
      priority: 0.6,
    },
    // /explore removed 2026-07-31: it 301s to "/" (explore/page.tsx) — a
    // sitemap must not advertise a permanent redirect. Replaced by the
    // public community template gallery, which is real, indexable content.
    {
      url: `${BASE}/templates`,
      changeFrequency: "daily",
      priority: 0.7,
    },
    // /plugins — the public first-party IDE/browser plugin marketplace
    // (2026-08-09). It has been indexable and canonicalised since it shipped
    // but was never added here, so it was reachable only by crawl from the
    // landing page's closing link row; added 2026-08-17.
    {
      url: `${BASE}/plugins`,
      changeFrequency: "weekly",
      priority: 0.7,
    },
    {
      url: `${BASE}/billing`,
      changeFrequency: "monthly",
      priority: 0.7,
    },
    // /about — founder/registered-entity transparency page (2026-08-09).
    // Same omission as /plugins above: real indexable content with its own
    // canonical + Person JSON-LD, and it is exactly the page an outside
    // compliance or trust review goes looking for.
    {
      url: `${BASE}/about`,
      changeFrequency: "monthly",
      priority: 0.6,
    },
    {
      url: `${BASE}/docs`,
      changeFrequency: "monthly",
      priority: 0.6,
    },
    // Tier-4 transparency pages (2026-09-23): /status refreshes live but its
    // chrome is stable (weekly crawl is plenty); /api-reference and
    // /changelog change with releases.
    {
      url: `${BASE}/status`,
      changeFrequency: "weekly",
      priority: 0.5,
    },
    {
      url: `${BASE}/api-reference`,
      changeFrequency: "weekly",
      priority: 0.6,
    },
    {
      url: `${BASE}/changelog`,
      changeFrequency: "weekly",
      priority: 0.5,
    },
    {
      url: `${BASE}/signup`,
      changeFrequency: "monthly",
      priority: 0.6,
    },
    {
      url: `${BASE}/login`,
      changeFrequency: "monthly",
      priority: 0.5,
    },
    {
      url: `${BASE}/privacy`,
      changeFrequency: "monthly",
      priority: 0.5,
    },
    {
      url: `${BASE}/pricing`,
      changeFrequency: "monthly",
      priority: 0.5,
    },
    {
      url: `${BASE}/refund-policy`,
      changeFrequency: "monthly",
      priority: 0.5,
    },
    {
      url: `${BASE}/shipping-policy`,
      changeFrequency: "monthly",
      priority: 0.5,
    },
    {
      url: `${BASE}/contact`,
      changeFrequency: "monthly",
      priority: 0.5,
    },
    {
      url: `${BASE}/terms`,
      changeFrequency: "monthly",
      priority: 0.5,
    },
    {
      url: `${BASE}/account-deletion`,
      changeFrequency: "yearly",
      priority: 0.3,
    },
    // Programmatic per-industry workflow pages (/help/workflow-recipes/[industry],
    // SEO Phase 1) — generated from the same recipes-index the guide renders,
    // so a new sector pack lands in the sitemap without touching this file.
    ...INDUSTRY_INDEX.map((industry) => ({
      url: `${BASE}/help/workflow-recipes/${industry.id}`,
      changeFrequency: "monthly" as const,
      priority: 0.7,
    })),
    // SEO/AEO documentation hubs (2026-08-14): /integrations, /ai,
    // /platform, /tutorials — statically generated from the same data
    // modules the pages render, so a new entry lands in the sitemap without
    // touching this file (the recipes-index pattern above). The four index
    // pages get 0.7; article pages 0.8 — they are the pages meant to rank
    // for feature/integration searches. /ai/model-context-protocol is
    // deliberately absent (permanent redirect to /ai/mcp; a sitemap must
    // not advertise a redirect — same rule as /explore).
    ...[
      { base: "/integrations", pages: INTEGRATIONS },
      { base: "/ai", pages: AI_TOPICS },
      { base: "/platform", pages: PLATFORM_TOPICS },
      { base: "/tutorials", pages: TUTORIALS },
    ].flatMap(({ base, pages }) => [
      {
        url: `${BASE}${base}`,
        changeFrequency: "weekly" as const,
        priority: 0.7,
      },
      ...pages.map((p) => ({
        url: `${BASE}${base}/${p.slug}`,
        changeFrequency: "monthly" as const,
        priority: 0.8,
      })),
    ]),
  ]);
}

// Language versions (multilingual SEO, 2026-09-19). A translated route
// (LOCALIZED_ROUTES) expands into one <url> per language, and EVERY member of
// that cluster - the English one included - carries the same xhtml:link set
// from hreflangMap(), the function the pages' own <link rel="alternate"> tags
// come from. Same source on both sides is what keeps the annotations
// reciprocal; a route that is not translated passes through untouched, with
// no alternates at all.
function withLanguageVersions(BASE: string, entries: MetadataRoute.Sitemap): MetadataRoute.Sitemap {
  return entries.flatMap((entry) => {
    const pathname = entry.url.slice(BASE.length) || "/";
    const bare = pathname.length > 1 && pathname.endsWith("/") ? pathname.slice(0, -1) : pathname;
    if (!isLocalizedRoute(bare)) return [entry];
    const alternates = { languages: hreflangMap(bare) };
    return [
      { ...entry, alternates },
      ...INDEXABLE_LOCALES.map((code) => ({
        ...entry,
        url: `${BASE}${localizedPath(bare, code)}`,
        alternates,
      })),
    ];
  });
}

function withLastModified(BASE: string, entries: MetadataRoute.Sitemap): MetadataRoute.Sitemap {
  return withLanguageVersions(BASE, withoutRedirectedRoutes(BASE, entries)).map((entry) => ({ lastModified: BUILT_AT, ...entry }));
}

// Billing off (lib/billing-flag.ts): /billing and /features redirect away, and
// a sitemap must not advertise a redirect.
//
// OSS site mode (SE-24): proxy.js 307s the commercial routes (/try, /hire,
// /enterprise, /earn, /compare, ...) to "/", so they are dropped here too.
function withoutRedirectedRoutes(BASE: string, entries: MetadataRoute.Sitemap): MetadataRoute.Sitemap {
  const billingOn = isBillingEnabled();
  const oss = isOssSite();
  return entries.filter((entry) => {
    const path = entry.url.slice(BASE.length) || "/";
    if (!billingOn && isBillingRoute(path)) return false;
    if (oss && isCommercialRoute(path)) return false;
    return true;
  });
}
