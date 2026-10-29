import type { Metadata } from "next";
import Image from "next/image";
import Link from "next/link";
import { notFound } from "next/navigation";
import type { ReactNode } from "react";
import { ArrowRight, Check, ChevronRight, Scale } from "lucide-react";
import BrandName from "../../components/BrandName";
import {
  COMPARE_DATES,
  COMPETITORS,
  PRICE_DISCLAIMER,
  VEGA_BEHIND,
  findCompetitor,
  type Citation,
} from "../compare-data";
import { SITE_URL } from "../../lib/site-url";
import { isBillingEnabled } from "../../lib/billing-flag";
import JsonLd from "../../components/JsonLd";

// ---------------------------------------------------------------------------
// SEO authority pack — comparison pages (2026-07-25).
//
// Fully server-rendered, statically generated at build time (same posture as
// /help/workflow-recipes/[industry]). One page per competitor slug in
// compare-data.ts; anything else 404s. All competitor claims and citations
// live in compare-data.ts, sourced from VEGADUTA-DOMINANCE-ROADMAP-2026-07.md
// §2/§3 — see that module's header for the positioning rules this surface
// must respect.
//
// Public marketing chrome: standalone dark ShadCN surface (the /privacy //
// /docs public-page posture), no AppShell/auth — a crawler and a logged-out
// visitor get identical, complete HTML.
// ---------------------------------------------------------------------------

const BASE = SITE_URL;

export const dynamicParams = false;

export function generateStaticParams(): { slug: string }[] {
  return COMPETITORS.map((c) => ({ slug: c.slug }));
}

type Params = { params: Promise<{ slug: string }> };

export async function generateMetadata({ params }: Params): Promise<Metadata> {
  const { slug } = await params;
  const c = findCompetitor(slug);
  if (!c) return {};
  const path = `/compare/${c.slug}`;
  return {
    title: c.metaTitle,
    description: c.metaDescription,
    alternates: { canonical: path },
    openGraph: {
      title: c.metaTitle,
      description: c.metaDescription,
      url: path,
      type: "article",
      images: ["/og-image.png"],
    },
  };
}

// ------------------------------------------------------------- presentation

function SectionLabel({ children }: { children: React.ReactNode }) {
  return <h2 className="text-[11px] font-semibold uppercase tracking-[0.14em] text-muted-foreground">{children}</h2>;
}

function CiteLinks({ cites }: { cites?: Citation[] }) {
  if (!cites || cites.length === 0) return null;
  return (
    <span className="mt-1 block text-xs text-muted-foreground">
      {"Source: "}
      {cites.map((s, i) => (
        <span key={s.href}>
          {i > 0 ? " · " : null}
          <a
            href={s.href}
            target="_blank"
            rel="noreferrer nofollow"
            className="underline decoration-border underline-offset-2 hover:text-foreground"
          >
            {s.label}
          </a>
        </span>
      ))}
    </span>
  );
}

function PickList({ heading, items }: { heading: ReactNode; items: string[] }) {
  return (
    <div className="rounded-xl border border-border bg-card p-5 sm:p-6">
      <h3 className="text-base font-semibold tracking-tight text-foreground">{heading}</h3>
      <ul className="mt-3 space-y-2.5">
        {items.map((item) => (
          <li key={item} className="flex gap-2.5 text-sm leading-relaxed text-muted-foreground">
            <Check className="mt-0.5 size-4 shrink-0 text-primary" aria-hidden="true" />
            <span>{item}</span>
          </li>
        ))}
      </ul>
    </div>
  );
}

// ------------------------------------------------------------------- page

export default async function ComparePage({ params }: Params) {
  const { slug } = await params;
  const c = findCompetitor(slug);
  if (!c) notFound();

  const pageUrl = `${BASE}/compare/${c.slug}`;
  const others = COMPETITORS.filter((o) => o.slug !== c.slug);

  const jsonLd = {
    "@context": "https://schema.org",
    "@graph": [
      {
        "@type": "BreadcrumbList",
        itemListElement: [
          { "@type": "ListItem", position: 1, name: "Compare", item: `${BASE}/compare` },
          { "@type": "ListItem", position: 2, name: `VegaDūta vs ${c.name}`, item: pageUrl },
        ],
      },
      // Article node (GEO pass, 2026-09-19). These are the most citable pages
      // on the site - a real table, per-row sources, the competitor's strengths
      // stated first - and they told a machine none of it: no date, no author,
      // no sources, only a breadcrumb and an FAQ. `citation` is the visible
      // Sources list below, verbatim; the dates are git's (COMPARE_DATES) and
      // the updated date is shown on the page next to that list.
      {
        "@type": "Article",
        headline: c.h1,
        description: c.metaDescription,
        url: pageUrl,
        mainEntityOfPage: pageUrl,
        inLanguage: "en",
        author: { "@id": `${BASE}/#organization` },
        publisher: { "@id": `${BASE}/#organization` },
        isPartOf: { "@id": `${BASE}/#website` },
        about: [{ "@id": `${BASE}/#software` }, { "@type": "SoftwareApplication", name: c.name }],
        datePublished: COMPARE_DATES.published,
        dateModified: COMPARE_DATES.updated,
        citation: c.citations.map((s) => ({ "@type": "CreativeWork", name: s.label, url: s.href })),
      },
      {
        "@type": "FAQPage",
        mainEntity: c.faqs.map((f) => ({
          "@type": "Question",
          name: f.q,
          acceptedAnswer: { "@type": "Answer", text: f.a },
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
        {/* Brand + breadcrumb */}
        <Link
          href="/"
          className="brand-name flex items-center gap-2.5 text-lg font-semibold tracking-tight"
          aria-label="VegaDūta home"
        >
          <Image
            src="/logo-mark.png"
            alt=""
            height={32}
            width={44}
            className="h-8 w-auto object-contain"
            style={{ height: 32, width: "auto" }}
            draggable={false}
          />
          <span>
            Vega<span className="text-primary">Dūta</span>
          </span>
        </Link>

        <nav aria-label="Breadcrumb" className="mt-6">
          <ol className="flex flex-wrap items-center gap-1 text-xs text-muted-foreground">
            <li>
              <Link href="/compare" className="hover:text-foreground hover:underline">
                Compare
              </Link>
            </li>
            <li aria-hidden="true">
              <ChevronRight className="size-3.5" />
            </li>
            <li aria-current="page" className="text-foreground">
              <BrandName /> vs {c.name}
            </li>
          </ol>
        </nav>

        {/* Header */}
        <header className="mt-6 space-y-4">
          <p className="flex items-center gap-2 text-xs font-semibold uppercase tracking-[0.14em] text-primary">
            <Scale className="size-4" aria-hidden="true" />
            Honest comparison · 2026
          </p>
          <h1 className="max-w-3xl text-2xl font-semibold tracking-tight text-foreground sm:text-4xl">{c.h1}</h1>
          {c.intro.map((p) => (
            <p key={p.slice(0, 40)} className="max-w-3xl text-sm leading-relaxed text-muted-foreground sm:text-base">
              {p}
            </p>
          ))}
        </header>

        {/* Comparison table */}
        <section className="mt-10">
          <SectionLabel>Side by side</SectionLabel>
          <div className="mt-3 overflow-x-auto rounded-xl border border-border">
            <table className="w-full min-w-[640px] border-collapse text-start text-sm">
              <thead>
                <tr className="border-b border-border bg-muted/40">
                  <th scope="col" className="w-[22%] px-4 py-3 font-semibold text-foreground">
                    Capability
                  </th>
                  <th scope="col" className="w-[39%] px-4 py-3 font-semibold text-foreground">
                    <BrandName />
                  </th>
                  <th scope="col" className="w-[39%] px-4 py-3 font-semibold text-foreground">
                    {c.name}
                  </th>
                </tr>
              </thead>
              <tbody>
                {c.rows.map((row) => (
                  <tr key={row.label} className="border-b border-border last:border-b-0 align-top">
                    <th scope="row" className="px-4 py-3.5 font-medium text-foreground">
                      {row.label}
                    </th>
                    <td className="px-4 py-3.5 leading-relaxed text-muted-foreground">
                      {row.label.startsWith("Pricing") && isBillingEnabled() ? (
                        <>
                          Free tier plus India-priced paid plans, payable by UPI — see{" "}
                          <Link href="/billing" className="text-primary underline-offset-2 hover:underline">
                            current pricing
                          </Link>
                          .
                        </>
                      ) : (
                        row.vega
                      )}
                    </td>
                    <td className="px-4 py-3.5 leading-relaxed text-muted-foreground">
                      {row.them}
                      <CiteLinks cites={row.cites} />
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <p className="mt-2 text-xs text-muted-foreground">{PRICE_DISCLAIMER}</p>
        </section>

        {/* What they do better — honesty first */}
        <section className="mt-12">
          <SectionLabel>What {c.name} does better</SectionLabel>
          <p className="mt-3 max-w-3xl text-sm leading-relaxed text-muted-foreground">
            A comparison you can trust has to start with the other side&apos;s strengths. These are real, and if they
            are what your project needs most, {c.name} is the better pick:
          </p>
          <ul className="mt-4 max-w-3xl space-y-2.5">
            {c.theyDoBetter.map((item) => (
              <li key={item} className="flex gap-2.5 text-sm leading-relaxed text-foreground">
                <span aria-hidden="true" className="mt-[7px] size-1.5 shrink-0 rounded-full bg-primary" />
                <span>{item}</span>
              </li>
            ))}
          </ul>
        </section>

        {/* When to pick which */}
        <section className="mt-12 grid gap-4 sm:grid-cols-2">
          <PickList heading={`Pick ${c.name} when…`} items={c.whenToPickThem} />
          <PickList
            heading={
              <>
                Pick <BrandName /> when…
              </>
            }
            items={c.whenToPickVega}
          />
        </section>

        {/* Honest current-state */}
        <section className="mt-12 rounded-xl border border-border bg-muted/20 p-5 sm:p-6">
          <h2 className="text-base font-semibold tracking-tight text-foreground">
            Where <BrandName /> is honestly behind
          </h2>
          <ul className="mt-3 max-w-3xl space-y-2 text-sm leading-relaxed text-muted-foreground">
            {VEGA_BEHIND.map((item) => (
              <li key={item}>{item}</li>
            ))}
          </ul>
          <p className="mt-3 max-w-3xl text-sm leading-relaxed text-muted-foreground">
            For the full story on how the platform is built — and what it deliberately hasn&apos;t built yet — read{" "}
            <Link href="/why-vegaduta" className="text-primary underline-offset-2 hover:underline">
              why we built <BrandName /> on Java, Postgres and Keycloak
            </Link>
            .
          </p>
        </section>

        {/* FAQs (visible content backing the FAQPage JSON-LD) */}
        <section className="mt-12">
          <SectionLabel>Frequently asked questions</SectionLabel>
          <div className="mt-4 space-y-6">
            {c.faqs.map((f) => (
              <div key={f.q} className="max-w-3xl">
                <h3 className="text-base font-semibold tracking-tight text-foreground">{f.q}</h3>
                <p className="mt-1.5 text-sm leading-relaxed text-muted-foreground">{f.a}</p>
              </div>
            ))}
          </div>
        </section>

        {/* CTAs */}
        <section className="mt-12 rounded-xl border border-border bg-card p-6 sm:p-8">
          <h2 className="text-lg font-semibold tracking-tight text-foreground">
            The fastest way to compare is to try it
          </h2>
          <p className="mt-2 max-w-2xl text-sm leading-relaxed text-muted-foreground">
            The sandbox provisions a real tenant — build a WhatsApp-ready agent in your language, no account, no card.
            Or browse ~90 industry recipes to see what teams actually build.
          </p>
          <div className="mt-5 flex flex-wrap items-center gap-3">
            <Link
              href="/try"
              className="inline-flex items-center gap-2 rounded-md bg-primary px-4 py-2 text-sm font-medium text-primary-foreground transition-colors hover:bg-primary/90"
            >
              Try the sandbox — no signup
              <ArrowRight className="size-4" aria-hidden="true" />
            </Link>
            <Link
              href="/help/workflow-recipes"
              className="inline-flex items-center gap-2 rounded-md border border-border bg-background px-4 py-2 text-sm font-medium text-foreground transition-colors hover:bg-muted/50"
            >
              Browse workflow recipes
            </Link>
          </div>
        </section>

        {/* Sources */}
        <section className="mt-12">
          <SectionLabel>Sources</SectionLabel>
          <p className="mt-3 max-w-3xl text-sm leading-relaxed text-muted-foreground">
            Competitor claims on this page come from the cited vendor pages and third-party research below. Where we did
            not check something, the table says so. {PRICE_DISCLAIMER} Page last updated{" "}
            <time dateTime={COMPARE_DATES.updated}>{COMPARE_DATES.updatedLabel}</time>.
          </p>
          <ul className="mt-3 space-y-1.5 text-sm">
            {c.citations.map((s) => (
              <li key={s.href}>
                <a
                  href={s.href}
                  target="_blank"
                  rel="noreferrer nofollow"
                  className="text-primary underline-offset-2 hover:underline"
                >
                  {s.label}
                </a>
              </li>
            ))}
          </ul>
        </section>

        {/* Other comparisons */}
        <section className="mt-12 border-t border-border pt-8">
          <SectionLabel>More comparisons</SectionLabel>
          <ul className="mt-3 space-y-2">
            {others.map((o) => (
              <li key={o.slug}>
                <Link
                  href={`/compare/${o.slug}`}
                  className="text-sm font-medium text-primary underline-offset-4 hover:underline"
                >
                  <BrandName /> vs {o.name} for WhatsApp AI agents in India
                </Link>
              </li>
            ))}
            <li>
              <Link
                href="/why-vegaduta"
                className="text-sm font-medium text-primary underline-offset-4 hover:underline"
              >
                Why <BrandName /> runs on Java 21, Postgres and Keycloak
              </Link>
            </li>
          </ul>
        </section>
      </div>
    </main>
  );
}
