// Sitewide JSON-LD for the root layout (layout.tsx).

import { DEFAULT_LOCALE, INDEXABLE_LOCALES } from "./lib/i18n-config";
import { SITE_URL } from "./lib/seo-i18n";
import { CIN_NUMBER, LEGAL_ENTITY } from "./lib/legalEntity";
import { ENTITY_FACTS, FEATURE_LIST } from "./lib/platform-facts";

// Sitewide structured data (SEO foundations pass, 2026-07-25). One JSON-LD
// @graph served from the root layout so every public page carries the
// Organization + WebSite + SoftwareApplication entities. Server-rendered
// (this layout is a server component), stringified from a typed const — no
// runtime cost. Offers mirror the real public pricing tiers ($0 free / $79
// top tier).
//
// The Organization node carries a stable @id (structured-data audit,
// 2026-07-30) so every other page that needs to reference "the company" —
// e.g. why-vegaduta's WebPage node — links back to THIS node instead of
// re-declaring a second, conflicting Organization on top of it. Any page
// that needs the entity should reference `${BASE}/#organization` via
// `about`/`publisher`, never redeclare `@type: "Organization"`.
const BASE = SITE_URL;
// inLanguage (2026-09-19): the WebSite node lists every language the site is
// published in; a per-request WebPage node is deliberately NOT added here -
// pages that need one declare their own and reference #website.
const SITE_LANGUAGES = [DEFAULT_LOCALE, ...INDEXABLE_LOCALES];
export const STRUCTURED_DATA = {
  "@context": "https://schema.org",
  "@graph": [
    {
      "@type": "Organization",
      "@id": `${BASE}/#organization`,
      name: "VegaDūta",
      url: BASE,
      logo: `${BASE}/logo-mark.png`,
      // Entity facts (GEO pass, 2026-09-19). An AI assistant answering "who
      // makes VegaDūta?" resolves the brand to an entity first, and until now
      // the only machine-readable facts here were a name and a logo - the
      // legal name and CIN sat on /about as prose a parser cannot use. Nothing
      // below is new information: legalName/identifier are lib/legalEntity.ts
      // (both deployment-configured), and the founding year + region are read
      // off the CIN (see ENTITY_FACTS). No street address, phone or
      // headcount: none is verified, and a wrong identity claim is worse than
      // a short one. No `founder` node either: the site presents the company
      // and the product, not a person.
      legalName: LEGAL_ENTITY,
      description:
        "VegaDūta is an agentic AI platform for building, deploying and governing AI agents and workflows across WhatsApp, Telegram, email, voice and the web.",
      ...(CIN_NUMBER
        ? {
            identifier: {
              "@type": "PropertyValue",
              propertyID: "CIN",
              name: "Corporate Identification Number (India, Ministry of Corporate Affairs)",
              value: CIN_NUMBER,
            },
          }
        : {}),
      foundingDate: ENTITY_FACTS.foundingYear,
      address: {
        "@type": "PostalAddress",
        addressRegion: ENTITY_FACTS.addressRegion,
        addressCountry: ENTITY_FACTS.addressCountry,
      },
      contactPoint: {
        "@type": "ContactPoint",
        contactType: "customer support",
        email: ENTITY_FACTS.contactEmail,
      },
      // No `sameAs` (SEO audit, 2026-09-09): it shipped as an empty array,
      // which is a schema.org no-op at best and a validator warning at
      // worst. The company has no public organisation profiles to list yet
      // — add real URLs when they exist rather than a placeholder.
    },
    {
      // WebSite entity (structured-data audit, 2026-07-30): the homepage
      // ("/") is DemoLanding, rendered client-side for a logged-out visitor
      // by AuthProvider (app/page.tsx itself is the authenticated console
      // dashboard, a "use client" route that never reaches an anonymous
      // crawler) — so it carries no JSON-LD of its own. This sitewide node
      // is the one place a WebSite entity can be declared server-side for
      // every route including "/". No potentialAction/SearchAction: the
      // platform has no sitewide search endpoint to point one at, and a
      // SearchAction that doesn't resolve to a real query page is worse
      // than no sitelinks searchbox at all.
      "@type": "WebSite",
      "@id": `${BASE}/#website`,
      name: "VegaDūta",
      url: BASE,
      publisher: { "@id": `${BASE}/#organization` },
      inLanguage: SITE_LANGUAGES,
    },
    {
      "@type": "SoftwareApplication",
      // @id + featureList (GEO pass, 2026-09-19). The @id lets /billing's
      // Product and any future page point at this node instead of describing
      // the product a second, slightly different way. featureList is the
      // field an assistant lifts capability statements from, so it is built
      // from lib/platform-facts.ts - the counted numbers - and nothing else.
      // No aggregateRating: there are no independently collected reviews,
      // and a self-awarded rating is a spam-policy violation, not a signal.
      "@id": `${BASE}/#software`,
      name: "VegaDūta",
      url: BASE,
      applicationCategory: "BusinessApplication",
      applicationSubCategory: "AI agent platform",
      operatingSystem: "Web",
      publisher: { "@id": `${BASE}/#organization` },
      inLanguage: SITE_LANGUAGES,
      featureList: FEATURE_LIST,
      description:
        "AI staff for your business — agents that answer customers on WhatsApp, phone calls, email and the web, with a console in 22 Indian languages and human approval on consequential actions.",
      offers: {
        "@type": "AggregateOffer",
        lowPrice: 0,
        highPrice: 79,
        priceCurrency: "USD",
      },
    },
  ],
} as const;
