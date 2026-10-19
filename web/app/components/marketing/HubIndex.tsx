import Image from "next/image";
import Link from "next/link";
import { ArrowRight } from "lucide-react";
import type { HubPage } from "../../lib/seo-hub";
import { SITE_URL } from "../../lib/site-url";
import JsonLd from "../JsonLd";

// ---------------------------------------------------------------------------
// SEO/AEO hub — shared index-page renderer (2026-08-14).
//
// Renders the landing page of one hub (/integrations, /ai, /platform,
// /tutorials): a card per HubPage in that hub's data module. Same standalone
// server-rendered marketing posture as HubArticle.tsx (see its header).
//
// JSON-LD: CollectionPage + ItemList so answer engines can enumerate the
// hub's children; publisher references the sitewide Organization node from
// app/layout.tsx by @id.
// ---------------------------------------------------------------------------

const BASE = SITE_URL;

export default function HubIndex({
  pages,
  basePath,
  label,
  h1,
  intro,
}: {
  pages: HubPage[];
  /** e.g. "/integrations" */
  basePath: string;
  /** e.g. "Integrations" — kicker + breadcrumb name */
  label: string;
  h1: string;
  /** 1–2 short paragraphs under the h1. */
  intro: string[];
}) {
  const pageUrl = `${BASE}${basePath}`;

  const jsonLd = {
    "@context": "https://schema.org",
    "@graph": [
      {
        "@type": "CollectionPage",
        name: h1,
        url: pageUrl,
        inLanguage: "en",
        publisher: { "@id": `${BASE}/#organization` },
      },
      {
        "@type": "ItemList",
        itemListElement: pages.map((p, i) => ({
          "@type": "ListItem",
          position: i + 1,
          name: p.name,
          url: `${pageUrl}/${p.slug}`,
        })),
      },
    ],
  };

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

      <div className="mx-auto w-full max-w-5xl px-5 py-10 sm:px-8 sm:py-14">
        {/* Brand: the winged emblem beside the two-colour wordmark, as on every
            other public chrome (brand rule - never the wordmark alone). */}
        <Link href="/" className="brand-name flex items-center gap-2.5 text-lg font-semibold tracking-tight" aria-label="VegaDūta home">
          <Image src="/logo-mark.png" alt="" height={32} width={44} className="h-8 w-auto object-contain" style={{ height: 32, width: "auto" }} draggable={false} />
          <span>
            Vega<span className="text-primary">Dūta</span>
          </span>
        </Link>

        <header className="mt-8 space-y-4">
          <p className="text-xs font-semibold uppercase tracking-[0.14em] text-primary">{label}</p>
          <h1 className="max-w-3xl text-2xl font-semibold tracking-tight text-foreground sm:text-4xl">
            {h1}
          </h1>
          {intro.map((p) => (
            <p
              key={p.slice(0, 40)}
              className="max-w-3xl text-sm leading-relaxed text-muted-foreground sm:text-base"
            >
              {p}
            </p>
          ))}
        </header>

        <section className="mt-10 grid gap-4 sm:grid-cols-2">
          {pages.map((p) => (
            <Link
              key={p.slug}
              href={`${basePath}/${p.slug}`}
              className="group rounded-xl border border-border bg-card p-5 transition-colors hover:border-primary/50 sm:p-6"
            >
              <h2 className="flex items-center justify-between gap-2 text-base font-semibold tracking-tight text-foreground">
                {p.name}
                <ArrowRight
                  className="size-4 shrink-0 text-muted-foreground transition-transform group-hover:translate-x-0.5 group-hover:text-primary"
                  aria-hidden="true"
                />
              </h2>
              <p className="mt-2 text-sm leading-relaxed text-muted-foreground">{p.cardBlurb}</p>
            </Link>
          ))}
        </section>

        <section className="mt-12 rounded-xl border border-border bg-card p-6 sm:p-8">
          <h2 className="text-lg font-semibold tracking-tight text-foreground">
            Try it before reading further
          </h2>
          <p className="mt-2 max-w-2xl text-sm leading-relaxed text-muted-foreground">
            The sandbox provisions a real tenant — describe an agent in one sentence and test it,
            no account, no card.
          </p>
          <div className="mt-5">
            <Link
              href="/try"
              className="inline-flex items-center gap-2 rounded-md bg-primary px-4 py-2 text-sm font-medium text-primary-foreground transition-colors hover:bg-primary/90"
            >
              Try the sandbox — no signup
              <ArrowRight className="size-4" aria-hidden="true" />
            </Link>
          </div>
        </section>
      </div>
    </main>
  );
}
