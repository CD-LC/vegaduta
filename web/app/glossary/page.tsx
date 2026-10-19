import type { Metadata } from "next";
import Image from "next/image";
import Link from "next/link";
import { ArrowRight } from "lucide-react";
import BrandName from "../components/BrandName";
import { GLOSSARY, GLOSSARY_DATES } from "./glossary-data";
import { SITE_URL } from "../lib/site-url";
import JsonLd from "../components/JsonLd";

// ---------------------------------------------------------------------------
// /glossary — agentic AI terms, defined (GEO pass, 2026-09-19).
//
// Same posture as the four SEO/AEO hubs (components/marketing/HubArticle.tsx):
// a server component with standalone marketing chrome and no AppShell/auth, so
// a crawler — none of the AI crawlers run JavaScript — and a logged-out visitor
// get identical, complete HTML. Content and its rules live in
// glossary-data.ts; this file only renders.
//
// One page rather than a page per term, deliberately: twelve short definitions
// on twelve URLs would be twelve thin pages, which is the scaled-content
// pattern Google's spam policy names. Each term is a fragment (#id) that can be
// linked to, carries its own <h2>, and is its own DefinedTerm node.
//
// JSON-LD: BreadcrumbList + a DefinedTermSet whose terms are the visible
// definitions verbatim. Nothing is in the markup that is not on the page.
// ---------------------------------------------------------------------------

const BASE = SITE_URL;
const PATH = "/glossary";
const TITLE = "Agentic AI Glossary — AI Agents, MCP, RAG, Guardrails Defined | VegaDūta";
const DESCRIPTION =
  "Plain-language definitions of agentic AI terms — AI agent, Model Context Protocol (MCP), RAG, guardrails, human-in-the-loop, A2A, AG-UI, BYOK — each with primary sources.";

export const metadata: Metadata = {
  title: TITLE,
  description: DESCRIPTION,
  alternates: { canonical: PATH },
  openGraph: {
    title: TITLE,
    description: DESCRIPTION,
    url: PATH,
    type: "article",
    images: ["/og-image.png"],
  },
};

function formatDate(iso: string): string {
  return new Date(`${iso}T00:00:00Z`).toLocaleDateString("en-GB", {
    day: "numeric",
    month: "long",
    year: "numeric",
    timeZone: "UTC",
  });
}

const jsonLd = {
  "@context": "https://schema.org",
  "@graph": [
    {
      "@type": "BreadcrumbList",
      itemListElement: [
        { "@type": "ListItem", position: 1, name: "Home", item: BASE },
        { "@type": "ListItem", position: 2, name: "Glossary", item: `${BASE}${PATH}` },
      ],
    },
    {
      "@type": "DefinedTermSet",
      "@id": `${BASE}${PATH}#terms`,
      name: "Agentic AI glossary",
      description: DESCRIPTION,
      url: `${BASE}${PATH}`,
      inLanguage: "en",
      publisher: { "@id": `${BASE}/#organization` },
      isPartOf: { "@id": `${BASE}/#website` },
      datePublished: GLOSSARY_DATES.published,
      dateModified: GLOSSARY_DATES.updated,
      hasDefinedTerm: GLOSSARY.map((t) => ({
        "@type": "DefinedTerm",
        "@id": `${BASE}${PATH}#${t.id}`,
        name: t.term,
        description: t.definition,
        url: `${BASE}${PATH}#${t.id}`,
        inDefinedTermSet: { "@id": `${BASE}${PATH}#terms` },
        ...(t.alsoKnownAs?.length ? { alternateName: t.alsoKnownAs } : {}),
      })),
    },
  ],
};

export default function GlossaryPage() {
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
          <p className="text-xs font-semibold uppercase tracking-[0.14em] text-primary">Glossary</p>
          <h1 className="max-w-3xl text-2xl font-semibold tracking-tight text-foreground sm:text-4xl">
            Agentic AI, in plain terms
          </h1>
          <p className="max-w-3xl text-sm leading-relaxed text-muted-foreground sm:text-base">
            {GLOSSARY.length} terms you meet when you build AI agents, each defined the way the
            field uses it — not the way a vendor would like it used — and each linked to its
            primary source. How <BrandName /> relates to a term is kept in a separate, labelled
            line, so the definition stays a definition.
          </p>
          <p className="text-xs text-muted-foreground">
            By the <BrandName /> team · Last updated{" "}
            <time dateTime={GLOSSARY_DATES.updated}>{formatDate(GLOSSARY_DATES.updated)}</time>
          </p>
        </header>

        {/* In-page index — real anchors, so every term is one hop from the top */}
        <nav aria-label="Terms" className="mt-8 rounded-xl border border-border bg-card p-5 sm:p-6">
          <ul className="flex flex-wrap gap-x-5 gap-y-2">
            {GLOSSARY.map((t) => (
              <li key={t.id}>
                <a
                  href={`#${t.id}`}
                  className="text-sm font-medium text-primary underline-offset-4 hover:underline"
                >
                  {t.term}
                </a>
              </li>
            ))}
          </ul>
        </nav>

        <div className="mt-4 divide-y divide-border">
          {GLOSSARY.map((t) => (
            <section key={t.id} id={t.id} className="scroll-mt-8 py-10">
              <h2 className="max-w-3xl text-lg font-semibold tracking-tight text-foreground sm:text-xl">
                {t.question}
              </h2>
              {t.alsoKnownAs && t.alsoKnownAs.length > 0 ? (
                <p className="mt-1 text-xs text-muted-foreground">
                  Also called: {t.alsoKnownAs.join(", ")}
                </p>
              ) : null}

              {/* The definition: first, alone, and complete without context. */}
              <p className="mt-3 max-w-3xl text-sm leading-relaxed text-foreground sm:text-base">
                {t.definition}
              </p>
              {t.detail ? (
                <p className="mt-3 max-w-3xl text-sm leading-relaxed text-muted-foreground sm:text-base">
                  {t.detail}
                </p>
              ) : null}

              {t.sources.length > 0 ? (
                <div className="mt-4 max-w-3xl">
                  <h3 className="text-[11px] font-semibold uppercase tracking-[0.14em] text-muted-foreground">
                    Sources
                  </h3>
                  <ul className="mt-2 space-y-1.5">
                    {t.sources.map((s) => (
                      <li key={s.href} className="text-sm leading-relaxed">
                        <a
                          href={s.href}
                          target="_blank"
                          rel="noopener noreferrer"
                          className="text-primary underline-offset-4 hover:underline"
                        >
                          {s.label}
                        </a>
                      </li>
                    ))}
                  </ul>
                </div>
              ) : null}

              <div className="mt-5 max-w-3xl rounded-xl border border-border bg-card p-5">
                <h3 className="text-[11px] font-semibold uppercase tracking-[0.14em] text-muted-foreground">
                  On <BrandName />
                </h3>
                <p className="mt-2 text-sm leading-relaxed text-muted-foreground">{t.onVegaDuta}</p>
                <Link
                  href={t.learnMore.href}
                  className="mt-3 inline-flex items-center gap-1.5 text-sm font-medium text-primary underline-offset-4 hover:underline"
                >
                  {t.learnMore.label}
                  <ArrowRight className="size-3.5" aria-hidden="true" />
                </Link>
              </div>
            </section>
          ))}
        </div>

        <section className="mt-6 rounded-xl border border-border bg-card p-6 sm:p-8">
          <h2 className="text-lg font-semibold tracking-tight text-foreground">
            See the terms working together
          </h2>
          <p className="mt-2 max-w-2xl text-sm leading-relaxed text-muted-foreground">
            The sandbox provisions a real tenant — describe an agent in one sentence and test it,
            no account, no card.
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
              href="/ai"
              className="inline-flex items-center gap-2 rounded-md border border-border bg-background px-4 py-2 text-sm font-medium text-foreground transition-colors hover:bg-muted/50"
            >
              AI capabilities
            </Link>
          </div>
        </section>
      </div>
    </main>
  );
}
