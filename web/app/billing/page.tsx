import type { Metadata } from "next";
import { localizedRouteMetadata } from "../lib/seo-i18n";
import { FREE_MODE } from "../lib/freeMode";
import BillingPageClient from "./BillingPageClient";
import { SITE_URL } from "../lib/site-url";
import JsonLd from "../components/JsonLd";

// ---------------------------------------------------------------------------
// Server shell for the public /billing page (structured-data audit,
// 2026-07-30). The page used to be entirely "use client" (BillingPageClient
// now) with no `metadata` export and no JSON-LD — despite listing three real
// pricing tiers with real feature lists, a textbook Product+Offer target.
// Same split docs/page.tsx and whatsapp-cost-calculator/page.tsx already use:
// a server component supplies metadata + JSON-LD, the interactive chrome
// (i18n, region detection, auth.login onClick) stays client-side underneath.
//
// Tier names/prices/taglines mirror messages/en.json's billing.* keys and
// BillingPageClient's own TIERS array exactly — USD pricing (the
// SoftwareApplication AggregateOffer in layout.tsx already quotes $0-$79 in
// USD too), so this stays consistent sitewide rather than picking INR here.
// ---------------------------------------------------------------------------

const BASE = SITE_URL;
const PATH = "/billing";

const DESCRIPTION =
  "Start free, no card required. Free, Premium ($19/mo) and Unlimited ($79/mo) plans for WhatsApp AI agents — upgrade only when your team outgrows a limit.";

// generateMetadata, not a static export (multilingual SEO, 2026-09-19): this
// route also serves "/<locale>/billing", and each language version needs its own
// title/description, a canonical pointing at ITSELF, and the shared hreflang
// cluster. The copy lives in messages/*.json under seoMeta.billing (en.json is
// the English source; DESCRIPTION above now feeds only the JSON-LD below).
const FREE_TITLE = "VegaDūta — Free during launch, open source, everything unlocked";
const FREE_DESCRIPTION =
  "Every VegaDūta feature is free for everyone during our launch, built by a small team of developers. No card needed — explore, experiment and run. Pricing will be introduced later, with advance notice.";

export async function generateMetadata(): Promise<Metadata> {
  const md = await localizedRouteMetadata("billing", PATH);
  // Free mode (lib/freeMode.ts): the seoMeta.billing copy sells paid tiers
  // that are not on the page, so the title/description get the free-mode
  // override. Canonical/hreflang/alternates pass through untouched.
  if (FREE_MODE) {
    md.title = FREE_TITLE;
    md.description = FREE_DESCRIPTION;
    md.openGraph = { ...md.openGraph, title: FREE_TITLE, description: FREE_DESCRIPTION };
  }
  return md;
}

const jsonLd = {
  "@context": "https://schema.org",
  "@graph": [
    {
      "@type": "BreadcrumbList",
      itemListElement: [
        { "@type": "ListItem", position: 1, name: "Home", item: BASE },
        { "@type": "ListItem", position: 2, name: "Pricing", item: `${BASE}${PATH}` },
      ],
    },
    {
      "@type": "Product",
      name: "VegaDūta",
      description: FREE_MODE ? FREE_DESCRIPTION : DESCRIPTION,
      brand: { "@id": `${BASE}/#organization` },
      // Free mode advertises exactly one $0 offer - emitting the $19/$79
      // tiers while the page says "everything is free" would be structured-
      // data spam and the wrong answer in a search snippet.
      offers: FREE_MODE
        ? [
            {
              "@type": "Offer",
              name: "Free - everything unlocked",
              description: "Every feature, every channel, no card. Built by a small team of developers.",
              price: "0",
              priceCurrency: "USD",
              url: `${BASE}${PATH}`,
            },
          ]
        : [
            {
              "@type": "Offer",
              name: "Free",
              description: "Try the whole platform, no card required.",
              price: "0",
              priceCurrency: "USD",
              url: `${BASE}${PATH}`,
            },
            {
              "@type": "Offer",
              name: "Premium",
              description: "For teams running real automations.",
              price: "19",
              priceCurrency: "USD",
              url: `${BASE}${PATH}`,
            },
            {
              "@type": "Offer",
              name: "Unlimited",
              description: "No caps. White-label ready.",
              price: "79",
              priceCurrency: "USD",
              url: `${BASE}${PATH}`,
            },
          ],
    },
  ],
};

export default function BillingPage() {
  return (
    <>
      <JsonLd data={jsonLd} />
      <BillingPageClient />
    </>
  );
}
