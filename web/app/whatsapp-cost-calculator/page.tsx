import type { Metadata } from "next";
import Image from "next/image";
import Link from "next/link";
import { ArrowLeft, ArrowRight, IndianRupee } from "lucide-react";
import BrandName from "../components/BrandName";
import { Button } from "../components/ui/button";
import PublicAuthCtas from "../components/PublicAuthCtas";
import WhatsAppCostCalculator from "./calculator";
import { META_PRICING_DOCS_URL, META_RATE_CARD_URL, PRICING_CHECKED_ON } from "./pricing-facts";
import { SITE_URL } from "../lib/site-url";
import { isBillingEnabled } from "../lib/billing-flag";
import JsonLd from "../components/JsonLd";

// ---------------------------------------------------------------------------
// /whatsapp-cost-calculator — linkable free tool (SEO/growth), from
// VEGADUTA-DOMINANCE-ROADMAP-2026-07.md §3 #1 "double down (a)": publish a
// WhatsApp agent cost calculator showing per-resolution cost vs per-broadcast
// cost. SERVER component: all prose, sources, disclaimers and JSON-LD render
// as static HTML; the only client island is ./calculator.tsx.
//
// Public route — needs a PUBLIC_ROUTES entry in lib/auth.ts and a sitemap.ts
// entry (coordinator adds both; this page deliberately touches neither file).
//
// No rate is quoted on this page. The pricing MODEL below was checked against Meta's own
// documentation (pricing-facts.ts holds the date); Meta's page text publishes no numbers, so the
// calculator starts empty and says "check Meta's current rates". Keep it that way until someone
// reads the rate card and flips RATES_VERIFIED.
//
// Chrome matches the public marketing surface (/docs, /billing): forced-dark
// ShadCN token canvas, one violet accent, same nav pattern. "Sign in" here is
// a plain Link to /login (this is a server component — no auth.login onClick).
// ---------------------------------------------------------------------------

const BASE = SITE_URL;
const PATH = "/whatsapp-cost-calculator";

const TITLE = "WhatsApp Business API Cost Calculator | VegaDūta";
// 146 chars — under the 155 budget.
const DESCRIPTION =
  "Estimate your monthly Meta WhatsApp API bill from Meta's per-message rates and compare broadcasts with an agent that resolves in fewer messages.";

export const metadata: Metadata = {
  title: TITLE,
  description: DESCRIPTION,
  alternates: { canonical: PATH },
  openGraph: {
    title: TITLE,
    description: DESCRIPTION,
    url: PATH,
    type: "website",
    images: ["/og-image.png"],
  },
};

const jsonLd = {
  "@context": "https://schema.org",
  "@type": "WebApplication",
  name: "WhatsApp Business API Cost Calculator",
  url: `${BASE}${PATH}`,
  description: DESCRIPTION,
  applicationCategory: "BusinessApplication",
  operatingSystem: "Any",
  browserRequirements: "Requires JavaScript",
  offers: { "@type": "Offer", price: "0", priceCurrency: "INR" },
  provider: { "@type": "Organization", name: "VegaDūta", url: BASE },
};

function SectionHeading({ children }: { children: React.ReactNode }) {
  return <h2 className="text-xl font-semibold tracking-tight text-foreground sm:text-2xl">{children}</h2>;
}

export default function WhatsAppCostCalculatorPage() {
  return (
    <div className="vd-pub marketing-root relative min-h-screen w-full overflow-x-clip bg-background text-foreground antialiased">
      <JsonLd data={jsonLd} />

      <div aria-hidden className="pointer-events-none fixed inset-0 -z-10">
        <div className="absolute -top-40 left-1/2 h-[38rem] w-[38rem] -translate-x-1/2 rounded-full bg-primary/16 blur-[140px]" />
      </div>

      {/* --------------------------------------------------------------- Nav */}
      <nav className="mx-auto flex max-w-6xl flex-wrap items-center justify-center gap-y-3 px-5 py-5 sm:justify-between sm:px-8">
        <Link href="/" className="brand-name flex items-center gap-2.5 text-lg font-semibold tracking-tight">
          <Image
            src="/logo-mark.png"
            alt=""
            height={32}
            width={44}
            className="h-8 w-auto object-contain"
            style={{ height: 32, width: "auto" }}
            draggable={false}
            priority
          />
          <span>
            <BrandName />
          </span>
        </Link>
        <div className="flex flex-wrap items-center justify-center gap-2 sm:gap-3">
          <Button asChild variant="ghost" size="sm" className="text-muted-foreground hover:text-foreground">
            {/* NAV-AUDIT FIX (2026-07-27): was /explore - same fix as
                web/app/docs/page.tsx, see that file's comment. The brand
                logo above already points to "/"; this button disagreeing
                with it was the same missed bug. */}
            <Link href="/">
              <ArrowLeft className="size-3.5 shrink-0" aria-hidden="true" /> Home
            </Link>
          </Button>
          {isBillingEnabled() && (
            <Button asChild variant="ghost" size="sm" className="text-muted-foreground hover:text-foreground">
              <Link href="/billing">Pricing</Link>
            </Button>
          )}
          <PublicAuthCtas>
            <Button asChild variant="ghost" size="sm" className="text-muted-foreground hover:text-foreground">
              <Link href="/login">Sign in</Link>
            </Button>
            <Button asChild variant="outline" size="sm">
              <Link href="/signup">Start free</Link>
            </Button>
          </PublicAuthCtas>
        </div>
      </nav>

      <main className="mx-auto w-full max-w-5xl px-5 pb-20 sm:px-8">
        {/* ----------------------------------------------------------- Header */}
        <header className="mx-auto max-w-3xl pb-10 pt-10 text-center">
          <p className="inline-flex items-center gap-1.5 rounded-full border border-border bg-muted/40 px-3 py-1 text-xs font-semibold uppercase tracking-[0.14em] text-primary">
            <IndianRupee className="size-3.5" aria-hidden="true" /> Free tool — no signup
          </p>
          <h1 className="mt-4 text-3xl font-semibold tracking-tight sm:text-4xl">
            WhatsApp Business API cost calculator
          </h1>
          <p className="mt-4 text-sm leading-relaxed text-muted-foreground sm:text-base">
            Meta now bills WhatsApp Business API messages one at a time. Put in your own conversation volumes and see
            what a broadcast-heavy workflow costs versus an agent that resolves the same conversations in fewer messages
            — including the number that actually matters: cost per <em>resolved</em> conversation.
          </p>
        </header>

        {/* ------------------------------------------------------- Calculator */}
        <WhatsAppCostCalculator />

        {/* ------------------------------------------------------------ Prose */}
        <article className="mx-auto mt-16 max-w-3xl space-y-12">
          <section className="space-y-4">
            <SectionHeading>How Meta bills WhatsApp Business messages</SectionHeading>
            <p className="text-sm leading-relaxed text-muted-foreground sm:text-base" data-testid="pricing-checked">
              Checked against Meta&apos;s documentation on{" "}
              <strong className="font-medium text-foreground">{PRICING_CHECKED_ON}</strong>. Since 1 July 2025 Meta
              charges{" "}
              <strong className="font-medium text-foreground">per delivered template message</strong>, by category:
              marketing, utility or authentication. The rate depends on the category, your monthly volume tier and
              the recipient&apos;s country calling code. Free-form replies inside an open 24-hour customer-service
              window are free, and so are utility templates sent inside that window.
            </p>
            <p className="text-sm leading-relaxed text-muted-foreground sm:text-base">
              <strong className="font-medium text-foreground">Check Meta&apos;s current rates.</strong> Meta&apos;s
              documentation text does not publish the rates themselves: they sit in an interactive rate card by
              market and currency, and Meta may change them on 1 January, 1 April, 1 July or 1 October. This page
              therefore shows no rate. Open{" "}
              <a
                href={META_RATE_CARD_URL}
                target="_blank"
                rel="noopener noreferrer"
                className="font-medium text-primary underline-offset-4 hover:underline"
              >
                the rate card
              </a>
              , pick your market, and type the marketing and utility rates into the calculator.
            </p>
            <p className="text-sm leading-relaxed text-muted-foreground sm:text-base">
              The structural point holds regardless of the exact rate: under per-message pricing,{" "}
              <strong className="font-medium text-foreground">every extra template message is a line item</strong>,
              which penalizes broadcast-heavy workflows and rewards agents that resolve a conversation in fewer
              messages.
            </p>
          </section>

          <section className="space-y-4">
            <SectionHeading>The broadcast-vs-agent math, honestly</SectionHeading>
            <p className="text-sm leading-relaxed text-muted-foreground sm:text-base">
              The calculator&apos;s model is deliberately simple and fully visible under the results: it multiplies your
              monthly conversations by the business-sent messages per conversation on each side, priced at a blended
              rate from your marketing/utility mix. Side A is your workflow today; side B is the same conversations
              handled by an agent — with a messages-per-conversation number that{" "}
              <strong className="font-medium text-foreground">you choose</strong>. The default is lower on the agent
              side because that is the entire premise being tested, but the tool makes no claim about what an agent will
              achieve for your business:{" "}
              <strong className="font-medium text-foreground">it only computes what your own inputs imply</strong>. If
              you set both sides equal, it will happily show you a saving of zero.
            </p>
            <p className="text-sm leading-relaxed text-muted-foreground sm:text-base">
              Real Meta billing has nuances this model skips on purpose: free-form replies inside an open
              customer-service window are not billed, utility templates inside that window are free, and volume tiers
              lower utility and authentication rates. Because the model treats every business-sent message as billable
              at your blended rate, it is a <strong className="font-medium text-foreground">rough upper-bound estimate</strong>{" "}
              on both sides of the comparison, not an invoice forecast.
            </p>
            <p className="text-sm leading-relaxed text-muted-foreground sm:text-base">
              The most useful output is usually not the monthly total but the{" "}
              <strong className="font-medium text-foreground">cost per resolved conversation</strong>. Per-message
              pricing turns message discipline into a unit economic: a support flow that resolves in four precise
              messages and one that meanders through twelve are no longer the same product at the same price.
            </p>
          </section>

          <section className="space-y-4">
            <SectionHeading>Sources and disclaimer</SectionHeading>
            <ul className="list-disc space-y-2 pl-5 text-sm leading-relaxed text-muted-foreground">
              <li>
                <a
                  href={META_PRICING_DOCS_URL}
                  target="_blank"
                  rel="noopener noreferrer"
                  className="font-medium text-primary underline-offset-4 hover:underline"
                >
                  Meta — WhatsApp Business Platform pricing (documentation)
                </a>{" "}
                (pricing model and categories; fetched {PRICING_CHECKED_ON}).
              </li>
              <li>
                <a
                  href={META_RATE_CARD_URL}
                  target="_blank"
                  rel="noopener noreferrer"
                  className="font-medium text-primary underline-offset-4 hover:underline"
                >
                  Meta — rate card by market and currency
                </a>{" "}
                (the only place the per-message rates are published).
              </li>
            </ul>
            <p className="rounded-lg border border-border bg-muted/30 p-4 text-sm leading-relaxed text-muted-foreground">
              <strong className="font-medium text-foreground">Disclaimer:</strong> this calculator produces estimates
              from the numbers you type in. Actual billing depends on message category, customer-service windows and
              volume tiers, and Meta revises pricing on a quarterly calendar —{" "}
              <strong className="font-medium text-foreground">
                verify current rates on Meta&apos;s official pricing pages
              </strong>{" "}
              before making budget decisions.
            </p>
          </section>

          {/* -------------------------------------------------------- End CTA */}
          <section className="rounded-xl border border-border bg-card p-6 text-center sm:p-8">
            <h2 className="text-lg font-semibold tracking-tight text-foreground">
              See an agent answer for your business — free, no signup
            </h2>
            <p className="mx-auto mt-2 max-w-xl text-sm leading-relaxed text-muted-foreground">
              The numbers above are only as good as the messages-per-conversation you believe. The sandbox lets you test
              that belief against a live agent in about a minute.
            </p>
            <div className="mt-5 flex flex-wrap items-center justify-center gap-3">
              <Button asChild size="lg">
                <Link href="/try">
                  Open the sandbox <ArrowRight className="size-4" aria-hidden="true" />
                </Link>
              </Button>
              {isBillingEnabled() && (
                <Button asChild variant="outline" size="lg">
                  <Link href="/billing">See pricing</Link>
                </Button>
              )}
            </div>
          </section>
        </article>
      </main>

      {/* ------------------------------------------------------------ Footer */}
      <footer className="border-t border-border/60">
        <div className="mx-auto flex max-w-6xl flex-wrap items-center justify-between gap-3 px-5 py-6 text-xs text-muted-foreground sm:px-8">
          <span>
            © {new Date().getFullYear()} <BrandName />
          </span>
          <div className="flex flex-wrap items-center gap-4">
            <Link href="/terms" className="hover:text-foreground">
              Terms
            </Link>
            <Link href="/privacy" className="hover:text-foreground">
              Privacy
            </Link>
            {isBillingEnabled() && (
              <Link href="/billing" className="hover:text-foreground">
                Pricing
              </Link>
            )}
            <Link href="/try" className="hover:text-foreground">
              Try free
            </Link>
          </div>
        </div>
      </footer>
    </div>
  );
}
