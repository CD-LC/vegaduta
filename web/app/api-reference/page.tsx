import type { Metadata } from "next";
import Image from "next/image";
import Link from "next/link";

import { API_AUTH_NOTES, API_BASE_PUBLIC, API_GROUPS, type ApiAuth } from "../lib/api-reference-data";
import { Button } from "../components/ui/button";
import PublicAuthCtas from "../components/PublicAuthCtas";
import { SITE_URL } from "../lib/site-url";
import JsonLd from "../components/JsonLd";
import BrandName from "../components/BrandName";

// ---------------------------------------------------------------------------
// Public /api-reference page (Tier-4 public surface). Fully server-rendered
// from app/lib/api-reference-data.ts - a curated, honest map of the real REST
// surface (the @RequestMapping roots under core/), not a generated OpenAPI
// dump. When a generated spec lands it can replace the data file without
// touching this page.
// ---------------------------------------------------------------------------

const BASE = SITE_URL;
const PATH = "/api-reference";

export const metadata: Metadata = {
  title: "API Reference — VegaDūta",
  description:
    "REST API reference for the VegaDūta agentic-AI platform - authentication, agents, sessions, workflows, channels, MCP tools, billing and operations endpoints.",
  alternates: { canonical: PATH },
  openGraph: {
    title: "API Reference — VegaDūta",
    description: "REST API reference for the VegaDūta agentic-AI platform.",
    url: PATH,
    type: "website",
  },
};

const AUTH_STYLE: Record<ApiAuth, string> = {
  "Bearer JWT": "border-sky-500/40 bg-sky-500/10 text-sky-700 dark:text-sky-300",
  Public: "border-emerald-500/40 bg-emerald-500/10 text-emerald-700 dark:text-emerald-300",
  "Webhook signature": "border-violet-500/40 bg-violet-500/10 text-violet-700 dark:text-violet-300",
  "Device token": "border-amber-500/40 bg-amber-500/10 text-amber-700 dark:text-amber-300",
};

const jsonLd = {
  "@context": "https://schema.org",
  "@graph": [
    {
      "@type": "BreadcrumbList",
      itemListElement: [
        { "@type": "ListItem", position: 1, name: "Home", item: BASE },
        { "@type": "ListItem", position: 2, name: "API Reference", item: `${BASE}${PATH}` },
      ],
    },
    {
      "@type": "TechArticle",
      headline: "VegaDūta REST API reference",
      about: "REST API for the VegaDūta agentic-AI platform",
      url: `${BASE}${PATH}`,
    },
  ],
};

export default function ApiReferencePage() {
  return (
    <div className="vd-pub relative min-h-screen w-full overflow-x-clip bg-background text-foreground antialiased">
      <JsonLd data={jsonLd} />
      <div aria-hidden className="pointer-events-none fixed inset-0 -z-10">
        <div className="absolute -top-40 left-1/2 h-[38rem] w-[38rem] -translate-x-1/2 rounded-full bg-primary/16 blur-[140px]" />
      </div>

      <nav className="mx-auto flex max-w-6xl flex-wrap items-center justify-center gap-y-3 px-5 py-5 sm:justify-between sm:px-8">
        <Link href="/" className="brand-name flex items-center gap-2.5 text-lg font-semibold tracking-tight">
          <Image
            src="/logo-mark-sm.webp"
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
            <Link href="/status">Status</Link>
          </Button>
          <Button asChild variant="ghost" size="sm" className="text-muted-foreground hover:text-foreground">
            <Link href="/changelog">Changelog</Link>
          </Button>
          <PublicAuthCtas>
            <Button asChild variant="outline" size="sm">
              <Link href="/signup">Start free</Link>
            </Button>
          </PublicAuthCtas>
        </div>
      </nav>

      <header className="mx-auto max-w-3xl px-6 pb-10 pt-10 text-center">
        <span className="font-mono text-xs uppercase tracking-[0.18em] text-primary">API reference</span>
        <h1 className="mt-3 text-balance text-3xl font-semibold tracking-tight sm:text-4xl">The VegaDūta REST API</h1>
        <p className="mx-auto mt-4 max-w-xl leading-relaxed text-muted-foreground">
          Everything the console does is an API call - this is the same surface, documented for integrators. Base URL{" "}
          <code className="rounded border border-border/60 bg-muted/40 px-1.5 py-0.5 font-mono text-xs">
            {API_BASE_PUBLIC}
          </code>
        </p>
      </header>

      <main className="mx-auto max-w-5xl px-6 pb-20 sm:px-8">
        {/* ------------------------------------------------------- Auth notes */}
        <section className="rounded-2xl border border-border/60 px-6 py-6">
          <h2 className="text-sm font-semibold">Authentication &amp; conventions</h2>
          <ul className="mt-3 list-disc space-y-2 ps-5 text-sm leading-relaxed text-muted-foreground">
            {API_AUTH_NOTES.map((note) => (
              <li key={note.slice(0, 40)}>{note}</li>
            ))}
          </ul>
          <div className="mt-4 flex flex-wrap gap-2">
            {(Object.keys(AUTH_STYLE) as ApiAuth[]).map((a) => (
              <span
                key={a}
                className={`inline-flex rounded-full border px-2 py-0.5 font-mono text-[10px] uppercase tracking-wider ${AUTH_STYLE[a]}`}
              >
                {a}
              </span>
            ))}
          </div>
        </section>

        {/* ---------------------------------------------------------- Groups */}
        <div className="mt-10 space-y-10">
          {API_GROUPS.map((group) => (
            <section key={group.id} id={group.id}>
              <h2 className="text-lg font-semibold tracking-tight">{group.name}</h2>
              <p className="mt-1.5 max-w-3xl text-sm leading-relaxed text-muted-foreground">{group.blurb}</p>
              <div className="mt-4 overflow-x-auto rounded-2xl border border-border/60">
                <table className="w-full text-start text-sm">
                  <thead>
                    <tr className="border-b border-border/60 text-xs uppercase tracking-wider text-muted-foreground">
                      <th className="px-4 py-3 font-medium">Method</th>
                      <th className="px-4 py-3 font-medium">Path</th>
                      <th className="px-4 py-3 font-medium">Auth</th>
                      <th className="px-4 py-3 font-medium">Description</th>
                    </tr>
                  </thead>
                  <tbody className="divide-y divide-border/60">
                    {group.endpoints.map((e) => (
                      <tr key={`${e.methods}-${e.path}`}>
                        <td className="whitespace-nowrap px-4 py-3 font-mono text-xs text-foreground/90">
                          {e.methods}
                        </td>
                        <td className="whitespace-nowrap px-4 py-3 font-mono text-xs text-primary">{e.path}</td>
                        <td className="whitespace-nowrap px-4 py-3">
                          <span
                            className={`inline-flex rounded-full border px-2 py-0.5 font-mono text-xs uppercase tracking-wider ${AUTH_STYLE[e.auth]}`}
                          >
                            {e.auth}
                          </span>
                        </td>
                        <td className="px-4 py-3 text-muted-foreground">{e.description}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </section>
          ))}
        </div>

        <section className="mt-14 rounded-2xl border border-primary/25 bg-primary/[0.06] px-6 py-8 text-center">
          <h2 className="text-xl font-semibold tracking-tight">Ship something on it</h2>
          <p className="mx-auto mt-2 max-w-lg text-sm leading-relaxed text-muted-foreground">
            The Free plan needs no card - sign up, create an agent, and every endpoint above is live against your
            tenant. Live platform health is on the{" "}
            <Link href="/status" className="text-primary underline-offset-2 hover:underline">
              status page
            </Link>
            .
          </p>
          <div className="mt-5">
            <PublicAuthCtas size="default" variant="default">
              <Button asChild>
                <Link href="/signup">Start free</Link>
              </Button>
            </PublicAuthCtas>
          </div>
        </section>
      </main>
    </div>
  );
}
