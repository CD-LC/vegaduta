// Per-request metadata for the root layout (layout.tsx#generateMetadata).

import { headers } from "next/headers";
import { DEFAULT_LOCALE, INDEXABLE_LOCALES, localizedPath } from "./lib/i18n-config";
import { SITE_URL, buildAlternates, getSeoRequestContext, localizedSeoText, ogLocale } from "./lib/seo-i18n";

// Brand assets (design_handoff_axon/brand/) - favicon.svg in that bundle is
// an empty placeholder (an <image> tag with no href, verified by opening
// it), so icons are declared from the real PNG renders only; everything
// else here mirrors brand/head-snippet.html 1:1 (manifest, theme-color,
// mstile, og:image, twitter:card) via Next.js's Metadata API instead of a
// hand-written <head> block, since the App Router resolves/dedupes/injects
// these the idiomatic way.
// Geo-targeted metadata (2026-07-31): the Hindi brand tagline ("देश का अपना AI
// एजेंट") in the title is India positioning and must only be served to
// visitors in India — everyone else gets an English-only equivalent. Same
// CF-IPCountry read as the GeoProvider wiring in RootLayout below (missing
// header / "XX" both resolve to "global", never India by default). This
// swaps the static `export const metadata` for generateMetadata(); the
// layout was already fully dynamic (it reads cookies()/headers() on every
// request), so per-request metadata adds no new rendering cost. `viewport`
// stays a separate const export — the App Router convention only forbids
// exporting BOTH `metadata` and `generateMetadata`; viewport is its own
// export either way.
// Site-mode switch (lib/siteMode.ts): in "oss" mode the tab title, snippet and
// link unfurl describe the open-source project, not the hosted product.
export const OSS_TITLE = "VegaDūta — Open-source platform for AI agents";
export const OSS_DESCRIPTION =
  "VegaDūta is an open-source, self-hostable platform for AI agents: MCP, A2A and AG-UI, workflows with human approval, retrieval over your own data. Apache-2.0.";
export async function buildMetadata() {
  const headerStore = await headers();
  // Multilingual SEO (2026-09-19). On a locale-prefixed URL ("/hi") the
  // title/description come from that locale's seoMeta.home and the geo variant
  // below is ignored - a Hindi page is Hindi wherever it is opened. On an
  // unprefixed URL urlLocale is null and the text is exactly what it was.
  const { urlLocale } = await getSeoRequestContext();
  const pathname = headerStore.get("x-pathname") || "/";
  const homeText = await localizedSeoText("home", urlLocale);
  const pageLocale = urlLocale || DEFAULT_LOCALE;
  return {
    metadataBase: new URL(SITE_URL),
    // 2026-07-29 category redesign: title/description carry the category claim
    // ("AI staff", hired not installed) instead of naming the generic category —
    // this line is what search results, link unfurls and browser tabs show.
    //
    // Description trimmed to ~155 chars (content-onpage SEO audit, 2026-07-30):
    // the prior ~205-char version routinely truncated mid-sentence in Google's
    // SERP snippet. /docs used to inherit this exact tag (see docs/page.tsx,
    // which now has its own metadata) — this is the one place the root default
    // still reaches unauthenticated console routes with no page-level override.
    title: homeText.title,
    // Leads with the FREE path and names the free channels. History, because
    // this line has been wrong in both directions: on 2026-09-09 it was
    // rewritten to say "WhatsApp and calls on paid plans", which was true
    // then; migration V225 made WhatsApp free on every plan three days later
    // (landing.faq_a3, ChannelWall.tsx) and this sentence was never updated -
    // so the SERP snippet, and on 2026-09-19 twenty translations of it, told
    // searchers the opposite of the pricing band. Corrected 2026-09-19. The
    // copy lives in messages/*.json seoMeta.home; the free-channel list it
    // must agree with is PLATFORM_FACTS.freeChannels.
    description: homeText.description,
    // Self-referencing canonical against metadataBase (2026-07-31 SEO pass):
    // the app serves on both the apex and the app. host, and "/" was the
    // one public route with NO canonical at all — so the two hosts competed
    // for the homepage. "./" resolves per-route, so every page that doesn't
    // declare its own canonical (only "/" today, since app/page.tsx is a
    // client component and can't export metadata) canonicalises to itself on
    // SITE_URL. Routes with explicit alternates still override.
    //
    // Multilingual SEO (2026-09-19): "/" is a translated route, so it also
    // carries the hreflang cluster, and "/hi" must canonicalise to "/hi" -
    // "./" cannot express that, because proxy.js REWRITES "/hi" to "/" and
    // Next resolves "./" against the rewritten path. x-pathname is the
    // unprefixed route proxy.js routed to. Every other route keeps "./"
    // (this block is inherited only by pages with no alternates of their own).
    alternates: pathname === "/" ? buildAlternates("/", urlLocale) : { canonical: "./" },
    // Installability (2026-09-12). Repointed from the older /site.webmanifest
    // to /manifest.webmanifest, which adds the members Chrome needs to treat
    // this as an installable app rather than a bookmarkable page: id, scope,
    // launch_handler, categories, lang/dir, prefer_related_applications, and
    // maskable icons. Its theme/background colours are read from globals.css's
    // --surface-page rather than the hand-picked hex the old file carried.
    // web-admin/app/layout.tsx still points at its OWN /site.webmanifest and is
    // unaffected — that is a separate app with a separate public/ directory.
    manifest: "/manifest.webmanifest",
    // iOS "Add to Home Screen" reads these, not the manifest, on the versions
    // still in the field — without `capable` the saved icon reopens inside
    // Safari chrome instead of its own window. statusBarStyle "black" (opaque)
    // rather than "black-translucent": translucent puts page content under the
    // status bar, which needs safe-area padding this layout does not apply.
    appleWebApp: { capable: true, title: "VegaDūta", statusBarStyle: "black" as const },
    icons: {
      icon: [
        { url: "/favicon-32.png", sizes: "32x32", type: "image/png" },
        { url: "/favicon-16.png", sizes: "16x16", type: "image/png" },
        { url: "/favicon-48.png", sizes: "48x48", type: "image/png" },
        { url: "/favicon-64.png", sizes: "64x64", type: "image/png" },
      ],
      apple: [{ url: "/apple-touch-icon.png", sizes: "180x180" }],
    },
    other: {
      "msapplication-TileImage": "/mstile-150.png",
    },
    openGraph: {
      title: "VegaDūta — AI Staff for Your Business",
      description:
        "Staff, not software. Hired in one sentence — on WhatsApp, calls and the web, with a console in 30 languages.",
      images: ["/og-image.png"],
      ...(pathname === "/"
        ? {
            title: urlLocale ? homeText.title : "VegaDūta — AI Staff for Your Business",
            ...(urlLocale ? { description: homeText.description } : {}),
            url: localizedPath("/", pageLocale),
            locale: ogLocale(pageLocale),
            alternateLocale: [DEFAULT_LOCALE, ...INDEXABLE_LOCALES].filter((c) => c !== pageLocale).map(ogLocale),
          }
        : {}),
    },
    twitter: {
      card: "summary_large_image",
      images: ["/og-image.png"],
    },
  };
}
