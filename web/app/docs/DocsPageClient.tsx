"use client";

import Image from "next/image";
import Link from "next/link";
import { Activity, ArrowRight, BookOpen, Code, GraduationCap, Home, LogIn, Wallet } from "lucide-react";

import { auth } from "../lib/auth";
import { academyUrl, hasAcademy } from "../lib/academyUrl";
import PublicAuthCtas from "../components/PublicAuthCtas";
import { Button } from "../components/ui/button";
import DocsView from "../help/DocsView";
import { DOCS_TITLE, DOCS_SUBTITLE } from "../help/docs-content";
import { isBillingEnabled } from "../lib/billing-flag";
import { docsSiteUrl } from "../lib/siteLinks";
import BrandName from "../components/BrandName";

// Public help docs — the landing-audience twin of the in-console /help/docs
// page. Deliberately a PUBLIC route (see PUBLIC_ROUTES in lib/auth.ts): the
// audience is a visitor from the landing's "Docs" link before they have any
// Keycloak session, so no useAuth/AppShell — same posture and chrome as the
// public /billing page (dark ShadCN marketing surface, one violet accent).
// Content comes from the shared help/docs-content.tsx module; in-console
// deep links are suppressed here (showAppLinks=false) so a logged-out reader
// never lands on an auth-gated route.
//
// Split out of page.tsx (structured-data audit, 2026-07-30): this is the
// client island (nav interactivity, auth.login onClick) — page.tsx is now a
// server component wrapping this, carrying `metadata` + FAQPage JSON-LD,
// which a "use client" file cannot export.
//
// Icon audit (2026-09-24): every nav control and CTA carries a lucide glyph
// that matches its destination, same vocabulary as TemplatesPublicChrome.
export default function DocsPageClient() {
  return (
    <div className="vd-pub relative min-h-screen w-full overflow-x-clip bg-background text-foreground antialiased">
      <div aria-hidden className="pointer-events-none fixed inset-0 -z-10">
        <div className="absolute -top-40 left-1/2 h-[38rem] w-[38rem] -translate-x-1/2 rounded-full bg-primary/16 blur-[140px]" />
      </div>

      {/* --------------------------------------------------------------- Nav */}
      <nav className="mx-auto flex max-w-6xl flex-wrap items-center justify-center gap-y-3 px-5 py-5 sm:justify-between sm:px-8" aria-label="Site">
        <Link href="/" className="brand-name flex items-center gap-2.5 text-lg font-semibold tracking-tight" aria-label="VegaDūta home">
          {/* The human-in-helmet winged emblem, in its own colors, always
              next to the brand name (direct instruction, 2026-07-25). */}
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
          <span><BrandName /></span>
        </Link>
        <div className="flex flex-wrap items-center justify-center gap-2 sm:gap-3">
          <Button asChild variant="ghost" size="sm" className="text-muted-foreground hover:text-foreground">
            {/* NAV-AUDIT FIX (2026-07-27): was /explore - but /docs is linked
                from BOTH / (DemoLanding) and /explore (AgenticAiLanding), so
                "Home" always dropping a visitor on /explore ignored where
                they actually started. / is the real root landing. */}
            <Link href="/">
              <Home className="size-4" aria-hidden="true" /> Home
            </Link>
          </Button>
          {isBillingEnabled() && (
            <Button asChild variant="ghost" size="sm" className="text-muted-foreground hover:text-foreground">
              <Link href="/billing">
                <Wallet className="size-4" aria-hidden="true" /> Pricing
              </Link>
            </Button>
          )}
          <Button asChild variant="ghost" size="sm" className="text-muted-foreground hover:text-foreground">
            <Link href="/api-reference">
              <Code className="size-4" aria-hidden="true" /> API
            </Link>
          </Button>
          {/* The published documentation site (docs/ via mkdocs), when this deployment has one
              (NEXT_PUBLIC_DOCS_URL). These in-app help topics stay: they are written for the
              console's users; the site covers installing, configuring and extending. */}
          {docsSiteUrl() && (
            <Button asChild variant="ghost" size="sm" className="text-muted-foreground hover:text-foreground">
              <a href={docsSiteUrl() ?? undefined}>
                <BookOpen className="size-4" aria-hidden="true" /> Full docs
              </a>
            </Button>
          )}
          <Button asChild variant="ghost" size="sm" className="text-muted-foreground hover:text-foreground">
            <Link href="/plugins">
              <Code className="size-4" aria-hidden="true" /> Plugins
            </Link>
          </Button>
          <Button asChild variant="ghost" size="sm" className="text-muted-foreground hover:text-foreground">
            <Link href="/status">
              <Activity className="size-4" aria-hidden="true" /> Status
            </Link>
          </Button>
          {/* PublicAuthCtas swaps this pair for "Open console" when the
              vd_authed session hint is set - a signed-in tenant is never told
              to sign in to a session they already have. */}
          <PublicAuthCtas>
            <Button
              variant="ghost"
              size="sm"
              className="text-muted-foreground hover:text-foreground"
              onClick={() => auth.login({ redirectUri: `${window.location.origin}/` })}
              data-testid="login-landing-button"
            >
              <LogIn className="size-4" aria-hidden="true" /> Sign in
            </Button>
            <Button asChild variant="outline" size="sm">
              <Link href="/signup">
                Start free <ArrowRight className="size-4" aria-hidden="true" />
              </Link>
            </Button>
          </PublicAuthCtas>
        </div>
      </nav>

      {/* ------------------------------------------------------------- Header */}
      <header className="mx-auto max-w-3xl px-6 pb-12 pt-10 text-center">
        <span className="inline-flex items-center gap-2 font-mono text-xs uppercase tracking-[0.18em] text-primary">
          <BookOpen className="size-4" aria-hidden="true" /> Help &amp; Docs
        </span>
        <h1 className="mt-3 text-balance text-3xl font-semibold tracking-tight sm:text-4xl">{DOCS_TITLE}</h1>
        <p className="mx-auto mt-4 max-w-xl leading-relaxed text-muted-foreground">{DOCS_SUBTITLE}</p>
      </header>

      {/* --------------------------------------------------------------- Docs */}
      <main className="mx-auto max-w-6xl px-6 pb-16 sm:px-8">
        <DocsView />
      </main>

      {/* ----------------------------------------------------------- Footer CTA */}
      <section className="mx-auto max-w-3xl px-6 pb-20 text-center">
        <div className="rounded-2xl border border-primary/25 bg-primary/[0.06] px-6 py-10">
          <h2 className="text-balance text-2xl font-semibold tracking-tight">Best way to learn it? Build one.</h2>
          <p className="mx-auto mt-3 max-w-xl text-sm leading-relaxed text-muted-foreground">
            Everything above is hands-on within minutes of signing up — the Free plan needs no card. Prefer a guided
            path first? The Academy teaches agentic AI free, step by step.
          </p>
          <div className="mt-6 flex flex-wrap items-center justify-center gap-3">
            <PublicAuthCtas size="default" variant="default">
              <Button asChild>
                <Link href="/signup">
                  Start free <ArrowRight className="size-4" aria-hidden="true" />
                </Link>
              </Button>
            </PublicAuthCtas>
            {hasAcademy() && (
              <Button asChild variant="outline">
                <a href={academyUrl()} target="_blank" rel="noopener noreferrer">
                  <GraduationCap className="size-4" aria-hidden="true" /> Academy · learn free
                </a>
              </Button>
            )}
          </div>
        </div>
      </section>
    </div>
  );
}
