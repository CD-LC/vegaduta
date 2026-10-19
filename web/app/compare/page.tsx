import type { Metadata } from "next";
import Image from "next/image";
import Link from "next/link";
import { ArrowRight, Scale } from "lucide-react";
import BrandName from "../components/BrandName";
import { COMPETITORS, PRICE_DISCLAIMER } from "./compare-data";
import { SITE_URL } from "../lib/site-url";
import JsonLd from "../components/JsonLd";

// ---------------------------------------------------------------------------
// SEO authority pack — /compare index (2026-07-25). Small server-rendered hub
// linking the per-competitor comparison pages. Claims and citations live in
// compare-data.ts (sourced from VEGADUTA-DOMINANCE-ROADMAP-2026-07.md).
// ---------------------------------------------------------------------------

export const metadata: Metadata = {
  title: "Compare VegaDūta: n8n, Dify, Botpress (2026)",
  description:
    "Honest side-by-side comparisons of VegaDūta with n8n, Dify and Botpress for governed AI agents on WhatsApp, voice and email, including what each does better.",
  alternates: { canonical: "/compare" },
  openGraph: {
    title: "Compare VegaDūta: n8n, Dify, Botpress (2026)",
    description:
      "Honest side-by-side comparisons of VegaDūta with n8n, Dify and Botpress for governed AI agents on WhatsApp, voice and email.",
    url: "/compare",
    images: ["/og-image.png"],
  },
};

const BLURB: Record<string, string> = {
  "n8n-alternative-india":
    "The workflow-automation heavyweight. Better at generic automation than VegaDūta — but no multi-tenancy, no Indian languages, no channel-native agents.",
  "dify-alternative-india":
    "The open-source LLM app builder. Strong RAG and visual building — but workspace sharing isn't tenant isolation, and there's no WhatsApp or Indic story.",
  "botpress-alternative-india":
    "The conversational-AI specialist, closest in spirit to VegaDūta — priced for Western SMBs, without UPI, Indic voice, or an anonymous sandbox.",
};

// Structured-data audit (2026-07-30): the per-competitor child pages
// (compare/[slug]/page.tsx) already carry BreadcrumbList + FAQPage JSON-LD;
// this hub page had metadata but none. BreadcrumbList (Home > Compare) +
// an ItemList of the 3 comparison pages mirrors the pattern already used on
// help/workflow-recipes/[industry]/page.tsx — same data already in scope,
// no new fetch.
const BASE = SITE_URL;
const jsonLd = {
  "@context": "https://schema.org",
  "@graph": [
    {
      "@type": "BreadcrumbList",
      itemListElement: [
        { "@type": "ListItem", position: 1, name: "Home", item: BASE },
        { "@type": "ListItem", position: 2, name: "Compare", item: `${BASE}/compare` },
      ],
    },
    {
      "@type": "ItemList",
      itemListElement: COMPETITORS.map((c, i) => ({
        "@type": "ListItem",
        position: i + 1,
        name: `VegaDūta vs ${c.name}`,
        url: `${BASE}/compare/${c.slug}`,
      })),
    },
  ],
};

export default function CompareIndexPage() {
  return (
    <main
      lang="en"
      dir="ltr"
      className="vd-pub marketing-root min-h-screen w-full bg-background text-foreground antialiased"
    >
      <JsonLd data={jsonLd} />
      <div aria-hidden className="pointer-events-none fixed inset-0 -z-10 overflow-hidden">
        <div className="absolute -top-40 left-1/2 h-[38rem] w-[38rem] -translate-x-1/2 rounded-full bg-primary/16 blur-[140px]" />
      </div>

      <div className="mx-auto w-full max-w-4xl px-5 py-10 sm:px-8 sm:py-14">
        <Link href="/" className="brand-name flex items-center gap-2.5 text-lg font-semibold tracking-tight" aria-label="VegaDūta home">
          <Image src="/logo-mark.png" alt="" height={32} width={44} className="h-8 w-auto object-contain" style={{ height: 32, width: "auto" }} draggable={false} />
          <span>
            Vega<span className="text-primary">Dūta</span>
          </span>
        </Link>

        <header className="mt-8 space-y-4">
          <p className="flex items-center gap-2 text-xs font-semibold uppercase tracking-[0.14em] text-primary">
            <Scale className="size-4" aria-hidden="true" />
            Comparisons · 2026
          </p>
          <h1 className="max-w-2xl text-2xl font-semibold tracking-tight sm:text-4xl">
            How <BrandName /> compares — honestly
          </h1>
          <p className="max-w-2xl text-sm leading-relaxed text-muted-foreground sm:text-base">
            Each page below is a fair side-by-side: what the other platform genuinely does better,
            what <BrandName /> does differently — agents on WhatsApp, voice and seven more channels,
            approval gates and an audit log around every action, a lab to replay and test each
            agent, schema-per-tenant isolation, UPI billing — and when you should pick each.
            Competitor prices come from each vendor&apos;s own pricing page, and where we did not
            check something the table says so. {PRICE_DISCLAIMER}
          </p>
        </header>

        <ul className="mt-10 space-y-4">
          {COMPETITORS.map((c) => (
            <li key={c.slug}>
              <Link
                href={`/compare/${c.slug}`}
                className="group block rounded-xl border border-border bg-card p-5 transition-colors hover:border-primary/40 sm:p-6"
              >
                <span className="flex items-center justify-between gap-3">
                  <span className="text-base font-semibold tracking-tight text-foreground sm:text-lg">
                    <BrandName /> vs {c.name} for WhatsApp AI agents in India
                  </span>
                  <ArrowRight
                    className="size-4 shrink-0 text-muted-foreground transition-transform group-hover:translate-x-0.5 group-hover:text-primary"
                    aria-hidden="true"
                  />
                </span>
                <span className="mt-2 block max-w-2xl text-sm leading-relaxed text-muted-foreground">
                  {BLURB[c.slug]}
                </span>
              </Link>
            </li>
          ))}
        </ul>

        <section className="mt-10 rounded-xl border border-border bg-muted/20 p-5 sm:p-6">
          <h2 className="text-base font-semibold tracking-tight text-foreground">
            Prefer the architecture story?
          </h2>
          <p className="mt-2 max-w-2xl text-sm leading-relaxed text-muted-foreground">
            Read{" "}
            <Link href="/why-vegaduta" className="text-primary underline-offset-2 hover:underline">
              why we built <BrandName /> on Spring Boot/Java 21, schema-per-tenant Postgres and Keycloak
            </Link>{" "}
            — including what we haven&apos;t built yet. Or skip the reading and{" "}
            <Link href="/try" className="text-primary underline-offset-2 hover:underline">
              open the no-signup sandbox
            </Link>
            . Curious what a WhatsApp AI agent actually costs versus the Business API alone?{" "}
            <Link href="/whatsapp-cost-calculator" className="text-primary underline-offset-2 hover:underline">
              Try the free WhatsApp cost calculator
            </Link>
            .
          </p>
        </section>
      </div>
    </main>
  );
}
