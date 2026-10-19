import Image from "next/image";
import Link from "next/link";
import type { ReactNode } from "react";
import { ArrowRight, BookOpen, Brain, ChevronRight, GraduationCap, Home, LayoutTemplate, Layers, Lightbulb, Plug, type LucideIcon } from "lucide-react";
import BrandName from "../BrandName";
import { HUB_DATES, type HubPage } from "../../lib/seo-hub";
import { SITE_URL } from "../../lib/site-url";
import { filterBillingItems } from "../../lib/billing-flag";
import JsonLd from "../JsonLd";

// "2026-09-17" -> "17 September 2026". Fixed locale + UTC so the server render
// and any later client render can never disagree about the day.
function formatDate(iso: string): string {
  return new Date(`${iso}T00:00:00Z`).toLocaleDateString("en-GB", {
    day: "numeric",
    month: "long",
    year: "numeric",
    timeZone: "UTC",
  });
}

// ---------------------------------------------------------------------------
// SEO/AEO hub — shared article renderer (2026-08-14).
//
// Renders one HubPage (lib/seo-hub.ts) from any of the four public hubs
// (/integrations, /ai, /platform, /tutorials). Same posture as
// compare/[slug]/page.tsx: fully server-rendered, standalone dark marketing
// chrome, no AppShell/auth — a crawler and a logged-out visitor get
// identical, complete HTML.
//
// JSON-LD: BreadcrumbList + TechArticle + FAQPage (+ HowTo when the page has
// steps), emitted as one @graph. Publisher references the sitewide
// Organization node declared in app/layout.tsx via its stable @id — never
// redeclare Organization here (structured-data audit rule, 2026-07-30).
// FAQs and steps are always ALSO rendered as visible content: markup-only
// FAQ/HowTo blocks are treated as spam by Google.
// ---------------------------------------------------------------------------

const BASE = SITE_URL;

function SectionLabel({ children }: { children: ReactNode }) {
  return (
    <h2 className="text-[11px] font-semibold uppercase tracking-[0.14em] text-muted-foreground">
      {children}
    </h2>
  );
}

// The four hub indexes, cross-linked from every article's footer so link
// equity flows between sections without touching the landing page's nav.
const HUB_INDEXES: Array<{ href: string; label: string; icon: LucideIcon }> = [
  { href: "/integrations", label: "Integrations", icon: Plug },
  { href: "/ai", label: "AI capabilities", icon: Brain },
  { href: "/platform", label: "Platform", icon: Layers },
  { href: "/tutorials", label: "Tutorials", icon: GraduationCap },
];

export default function HubArticle({
  page,
  basePath,
  baseLabel,
}: {
  page: HubPage;
  /** e.g. "/integrations" */
  basePath: string;
  /** e.g. "Integrations" */
  baseLabel: string;
}) {
  const pageUrl = `${BASE}${basePath}/${page.slug}`;
  const hubDates = HUB_DATES[basePath];
  const published = hubDates?.published;
  const updated = page.updated ?? hubDates?.updated;

  const graph: Record<string, unknown>[] = [
    {
      "@type": "BreadcrumbList",
      itemListElement: [
        { "@type": "ListItem", position: 1, name: baseLabel, item: `${BASE}${basePath}` },
        { "@type": "ListItem", position: 2, name: page.name, item: pageUrl },
      ],
    },
    {
      "@type": "TechArticle",
      headline: page.h1,
      description: page.metaDescription,
      url: pageUrl,
      mainEntityOfPage: pageUrl,
      inLanguage: "en",
      publisher: { "@id": `${BASE}/#organization` },
      // GEO pass (2026-09-19): an article with no date and no author is one
      // an answer engine cannot place in time or attribute. The author is the
      // Organization, which is the truth — these pages are written by the
      // team against the codebase, not by a named columnist — and the dates
      // are git's (see HUB_DATES). `about` ties every article to the one
      // SoftwareApplication node so they read as facets of a single product.
      author: { "@id": `${BASE}/#organization` },
      isPartOf: { "@id": `${BASE}/#website` },
      about: { "@id": `${BASE}/#software` },
      ...(published ? { datePublished: published } : {}),
      ...(updated ? { dateModified: updated } : {}),
      ...(page.keyTakeaways?.length ? { abstract: page.keyTakeaways.join(" ") } : {}),
      ...(page.citations?.length
        ? {
            citation: page.citations.map((c) => ({
              "@type": "CreativeWork",
              name: c.label,
              url: c.href,
            })),
          }
        : {}),
    },
    {
      "@type": "FAQPage",
      mainEntity: page.faqs.map((f) => ({
        "@type": "Question",
        name: f.q,
        acceptedAnswer: { "@type": "Answer", text: f.a },
      })),
    },
  ];

  if (page.steps && page.steps.length > 0) {
    graph.push({
      "@type": "HowTo",
      name: page.h1,
      description: page.metaDescription,
      step: page.steps.map((s, i) => ({
        "@type": "HowToStep",
        position: i + 1,
        name: s.name,
        text: s.text,
      })),
    });
  }

  const jsonLd = { "@context": "https://schema.org", "@graph": graph };

  return (
    // lang/dir pinned (2026-09-19): this body is English whatever NEXT_LOCALE says,
    // and <html dir> follows the cookie - an Arabic or Urdu reader got the article
    // mirrored right-to-left. Same attributes on HubIndex, /glossary and /compare.
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
        {/* Brand + breadcrumb */}
        {/* Brand: the winged emblem beside the two-colour wordmark, as on every
            other public chrome (brand rule - never the wordmark alone). */}
        <Link href="/" className="brand-name flex items-center gap-2.5 text-lg font-semibold tracking-tight" aria-label="VegaDūta home">
          <Image src="/logo-mark.png" alt="" height={32} width={44} className="h-8 w-auto object-contain" style={{ height: 32, width: "auto" }} draggable={false} />
          <span>
            Vega<span className="text-primary">Dūta</span>
          </span>
        </Link>

        <nav aria-label="Breadcrumb" className="mt-6">
          <ol className="flex flex-wrap items-center gap-1 text-xs text-muted-foreground">
            <li>
              <Link href="/" className="inline-flex items-center gap-1 hover:text-foreground hover:underline">
                <Home className="size-3.5" aria-hidden="true" />
                Home
              </Link>
            </li>
            <li aria-hidden="true">
              <ChevronRight className="size-3.5" />
            </li>
            <li>
              <Link href={basePath} className="hover:text-foreground hover:underline">
                {baseLabel}
              </Link>
            </li>
            <li aria-hidden="true">
              <ChevronRight className="size-3.5" />
            </li>
            <li aria-current="page" className="text-foreground">
              {page.name}
            </li>
          </ol>
        </nav>

        {/* Header */}
        <header className="mt-6 space-y-4">
          <p className="text-xs font-semibold uppercase tracking-[0.14em] text-primary">
            {page.kicker}
          </p>
          <h1 className="max-w-3xl text-2xl font-semibold tracking-tight text-foreground sm:text-4xl">
            {page.h1}
          </h1>
          {page.intro.map((p) => (
            <p
              key={p.slice(0, 40)}
              className="max-w-3xl text-sm leading-relaxed text-muted-foreground sm:text-base"
            >
              {p}
            </p>
          ))}
          {/* Byline — visible, because a date that exists only in JSON-LD is
              markup claiming something the page does not show. */}
          {updated ? (
            <p className="text-xs text-muted-foreground">
              By the <BrandName /> team · Last updated{" "}
              <time dateTime={updated}>{formatDate(updated)}</time>
            </p>
          ) : null}
        </header>

        {/* In short — standalone sentences an answer engine can quote whole */}
        {page.keyTakeaways && page.keyTakeaways.length > 0 ? (
          <section className="mt-8 max-w-3xl rounded-xl border border-border bg-card p-5 sm:p-6">
            <SectionLabel>In short</SectionLabel>
            <ul className="mt-3 space-y-2">
              {page.keyTakeaways.map((k) => (
                <li key={k} className="flex gap-2.5 text-sm leading-relaxed text-foreground">
                  <span aria-hidden="true" className="mt-[7px] size-1.5 shrink-0 rounded-full bg-primary" />
                  <span>{k}</span>
                </li>
              ))}
            </ul>
          </section>
        ) : null}

        {/* Steps (tutorials) — visible content backing the HowTo JSON-LD */}
        {page.steps && page.steps.length > 0 ? (
          <section className="mt-10">
            <SectionLabel>Step by step</SectionLabel>
            <ol className="mt-4 space-y-5">
              {page.steps.map((s, i) => (
                <li key={s.name} className="flex max-w-3xl gap-4">
                  <span
                    aria-hidden="true"
                    className="mt-0.5 flex size-7 shrink-0 items-center justify-center rounded-full border border-border bg-card text-xs font-semibold text-primary"
                  >
                    {i + 1}
                  </span>
                  <div>
                    <h3 className="text-base font-semibold tracking-tight text-foreground">
                      {s.name}
                    </h3>
                    <p className="mt-1 text-sm leading-relaxed text-muted-foreground">{s.text}</p>
                  </div>
                </li>
              ))}
            </ol>
          </section>
        ) : null}

        {/* Body sections */}
        {page.sections.map((section) => (
          <section key={section.heading} className="mt-10">
            <h2 className="max-w-3xl text-lg font-semibold tracking-tight text-foreground sm:text-xl">
              {section.heading}
            </h2>
            <p className="mt-2 max-w-3xl text-sm leading-relaxed text-muted-foreground sm:text-base">
              {section.body}
            </p>
            {section.bullets && section.bullets.length > 0 ? (
              <ul className="mt-3 max-w-3xl space-y-2">
                {section.bullets.map((b) => (
                  <li
                    key={b}
                    className="flex gap-2.5 text-sm leading-relaxed text-muted-foreground"
                  >
                    <span
                      aria-hidden="true"
                      className="mt-[7px] size-1.5 shrink-0 rounded-full bg-primary"
                    />
                    <span>{b}</span>
                  </li>
                ))}
              </ul>
            ) : null}
          </section>
        ))}

        {/* FAQs — visible content backing the FAQPage JSON-LD */}
        <section className="mt-12">
          <SectionLabel>Frequently asked questions</SectionLabel>
          <div className="mt-4 space-y-6">
            {page.faqs.map((f) => (
              <div key={f.q} className="max-w-3xl">
                <h3 className="text-base font-semibold tracking-tight text-foreground">{f.q}</h3>
                <p className="mt-1.5 text-sm leading-relaxed text-muted-foreground">{f.a}</p>
              </div>
            ))}
          </div>
        </section>

        {/* Sources — visible content backing `citation` in the JSON-LD */}
        {page.citations && page.citations.length > 0 ? (
          <section className="mt-12">
            <SectionLabel>Sources</SectionLabel>
            <ul className="mt-3 max-w-3xl space-y-2">
              {page.citations.map((c) => (
                <li key={c.href} className="text-sm leading-relaxed text-muted-foreground">
                  <a
                    href={c.href}
                    target="_blank"
                    rel="noopener noreferrer"
                    className="font-medium text-primary underline-offset-4 hover:underline"
                  >
                    {c.label}
                  </a>
                </li>
              ))}
            </ul>
          </section>
        ) : null}

        {/* CTA */}
        <section className="mt-12 rounded-xl border border-border bg-card p-6 sm:p-8">
          <h2 className="text-lg font-semibold tracking-tight text-foreground">
            See it working in two minutes
          </h2>
          <p className="mt-2 max-w-2xl text-sm leading-relaxed text-muted-foreground">
            The sandbox provisions a real tenant — describe an agent in one sentence and test it,
            no account, no card. Or browse ~90 industry workflow recipes to see what teams build.
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
              <LayoutTemplate className="size-4" aria-hidden="true" />
              Browse workflow recipes
            </Link>
          </div>
        </section>

        {/* Related pages */}
        {filterBillingItems(page.related).length > 0 ? (
          <section className="mt-12">
            <SectionLabel>Related</SectionLabel>
            <ul className="mt-3 space-y-2">
              {filterBillingItems(page.related).map((r) => (
                <li key={r.href}>
                  <Link
                    href={r.href}
                    className="text-sm font-medium text-primary underline-offset-4 hover:underline"
                  >
                    {r.label}
                  </Link>
                </li>
              ))}
            </ul>
          </section>
        ) : null}

        {/* Cross-hub footer nav */}
        <section className="mt-12 border-t border-border pt-8">
          <SectionLabel>
            Explore <BrandName />
          </SectionLabel>
          <ul className="mt-3 flex flex-wrap gap-x-6 gap-y-2">
            {HUB_INDEXES.map((h) => (
              <li key={h.href}>
                <Link
                  href={h.href}
                  className="inline-flex items-center gap-1.5 text-sm font-medium text-muted-foreground underline-offset-4 hover:text-foreground hover:underline"
                >
                  <h.icon className="size-4" aria-hidden="true" />
                  {h.label}
                </Link>
              </li>
            ))}
            <li>
              <Link
                href="/why-vegaduta"
                className="inline-flex items-center gap-1.5 text-sm font-medium text-muted-foreground underline-offset-4 hover:text-foreground hover:underline"
              >
                <Lightbulb className="size-4" aria-hidden="true" />
                Why VegaDūta
              </Link>
            </li>
            <li>
              <Link
                href="/docs"
                className="inline-flex items-center gap-1.5 text-sm font-medium text-muted-foreground underline-offset-4 hover:text-foreground hover:underline"
              >
                <BookOpen className="size-4" aria-hidden="true" />
                Docs
              </Link>
            </li>
          </ul>
        </section>
      </div>
    </main>
  );
}
