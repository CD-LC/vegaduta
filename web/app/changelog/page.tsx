import type { Metadata } from "next";
import Image from "next/image";
import Link from "next/link";

import { CHANGELOG_ENTRIES, type ChangelogKind } from "../lib/changelog-data";
import { Button } from "../components/ui/button";
import PublicAuthCtas from "../components/PublicAuthCtas";
import { SITE_URL } from "../lib/site-url";
import JsonLd from "../components/JsonLd";
import BrandName from "../components/BrandName";

// ---------------------------------------------------------------------------
// Public /changelog page (Tier-4 public surface). Fully server-rendered from
// app/lib/changelog-data.ts, which is mirrored by the repository-root
// CHANGELOG.md - keep the two in step (see that file's header comment).
// ---------------------------------------------------------------------------

const BASE = SITE_URL;
const PATH = "/changelog";

export const metadata: Metadata = {
  title: "Changelog — VegaDūta",
  description:
    "What changed on the VegaDūta platform - new capabilities, security hardening, fixes and notable operational changes, newest first.",
  alternates: { canonical: PATH },
  openGraph: {
    title: "Changelog — VegaDūta",
    description: "What changed on the VegaDūta platform, newest first.",
    url: PATH,
    type: "website",
  },
};

const KIND_STYLE: Record<ChangelogKind, { label: string; className: string }> = {
  added: {
    label: "Added",
    className: "border-emerald-500/40 bg-emerald-500/10 text-emerald-700 dark:text-emerald-300",
  },
  changed: { label: "Changed", className: "border-sky-500/40 bg-sky-500/10 text-sky-700 dark:text-sky-300" },
  fixed: { label: "Fixed", className: "border-amber-500/40 bg-amber-500/10 text-amber-700 dark:text-amber-300" },
  security: {
    label: "Security",
    className: "border-violet-500/40 bg-violet-500/10 text-violet-700 dark:text-violet-300",
  },
};

const jsonLd = {
  "@context": "https://schema.org",
  "@type": "BreadcrumbList",
  itemListElement: [
    { "@type": "ListItem", position: 1, name: "Home", item: BASE },
    { "@type": "ListItem", position: 2, name: "Changelog", item: `${BASE}${PATH}` },
  ],
};

export default function ChangelogPage() {
  return (
    <div className="vd-pub relative min-h-screen w-full overflow-x-clip bg-background text-foreground antialiased">
      <JsonLd data={jsonLd} />
      <div aria-hidden className="pointer-events-none fixed inset-0 -z-10">
        <div className="absolute -top-40 left-1/2 h-[38rem] w-[38rem] -translate-x-1/2 rounded-full bg-primary/16 blur-[140px]" />
      </div>

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
            <Link href="/">Home</Link>
          </Button>
          <Button asChild variant="ghost" size="sm" className="text-muted-foreground hover:text-foreground">
            <Link href="/docs">Docs</Link>
          </Button>
          <Button asChild variant="ghost" size="sm" className="text-muted-foreground hover:text-foreground">
            <Link href="/api-reference">API</Link>
          </Button>
          <Button asChild variant="ghost" size="sm" className="text-muted-foreground hover:text-foreground">
            <Link href="/status">Status</Link>
          </Button>
          <PublicAuthCtas>
            <Button asChild variant="outline" size="sm">
              <Link href="/signup">Start free</Link>
            </Button>
          </PublicAuthCtas>
        </div>
      </nav>

      <header className="mx-auto max-w-3xl px-6 pb-12 pt-10 text-center">
        <span className="font-mono text-xs uppercase tracking-[0.18em] text-primary">Changelog</span>
        <h1 className="mt-3 text-balance text-3xl font-semibold tracking-tight sm:text-4xl">
          What changed on VegaDūta
        </h1>
        <p className="mx-auto mt-4 max-w-xl leading-relaxed text-muted-foreground">
          New capabilities, security hardening, fixes and notable operational changes - newest first. The same list
          ships in the repository as <code className="font-mono text-xs">CHANGELOG.md</code>.
        </p>
      </header>

      <main className="mx-auto max-w-3xl px-6 pb-20 sm:px-8">
        <ol className="relative space-y-10 border-s border-border/60 ps-6">
          {CHANGELOG_ENTRIES.map((entry) => (
            <li key={`${entry.released}-${entry.title}`} className="relative">
              <span
                aria-hidden
                className="absolute -start-[31px] top-1.5 size-3 rounded-full border-2 border-background bg-primary"
              />
              <p className="font-mono text-xs uppercase tracking-[0.18em] text-muted-foreground">{entry.released}</p>
              <h2 className="mt-1.5 text-xl font-semibold tracking-tight">{entry.title}</h2>
              <ul className="mt-4 space-y-3">
                {entry.items.map((item) => (
                  <li key={item.text.slice(0, 48)} className="flex items-start gap-3">
                    <span
                      className={`mt-0.5 inline-flex shrink-0 rounded-full border px-2 py-0.5 font-mono text-xs uppercase tracking-wider ${KIND_STYLE[item.kind].className}`}
                    >
                      {KIND_STYLE[item.kind].label}
                    </span>
                    <p className="text-sm leading-relaxed text-muted-foreground">{item.text}</p>
                  </li>
                ))}
              </ul>
            </li>
          ))}
        </ol>

        <section className="mt-14 rounded-2xl border border-primary/25 bg-primary/[0.06] px-6 py-8 text-center">
          <h2 className="text-xl font-semibold tracking-tight">Want the details?</h2>
          <p className="mx-auto mt-2 max-w-lg text-sm leading-relaxed text-muted-foreground">
            The API surface these entries touch is documented on the{" "}
            <Link href="/api-reference" className="text-primary underline-offset-2 hover:underline">
              API reference
            </Link>
            , and live platform health is on the{" "}
            <Link href="/status" className="text-primary underline-offset-2 hover:underline">
              status page
            </Link>
            .
          </p>
        </section>
      </main>
    </div>
  );
}
