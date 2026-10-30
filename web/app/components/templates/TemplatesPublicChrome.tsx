"use client";

import { useEffect, useState } from "react";
import type { ReactNode } from "react";
import Link from "next/link";
import { ArrowLeft, ArrowRight, Fingerprint, Home, LayoutTemplate, LogIn, Puzzle, Wallet } from "lucide-react";
import { auth } from "../../lib/auth";
import { Button } from "../ui/button";
import { isBillingEnabled } from "../../lib/billing-flag";
import { isPersonalDenied, isPersonalEdition } from "../../lib/edition";
import { useTranslations } from "../../lib/i18n";

// Standalone dark chrome for the PUBLIC template pages (/templates and
// /templates/[id]) - and, since 2026-08-09, the /plugins marketplace page,
// which is the same kind of public gallery surface and reuses this chrome
// (passing its own `footer`) rather than forking it. These render for
// logged-out visitors, so - exactly like /docs and /compare - they must NOT
// use AppShell or useAuth (which throw on a public route with no
// AuthContext). Self-contained nav + footer instead.
//
// BUT this chrome is also what a signed-in tenant member sees after clicking
// the "Community Templates" console nav link (added 2026-07-30) - /templates
// being a public-route PREFIX means AppShell never wraps it, authed or not.
// Without checking the session here, an already-logged-in user would land on
// a page whose top bar says "Sign in / Start free", which reads as broken
// ("did I get logged out?") rather than as the same public page a stranger
// sees. Same self-gating auth.init() pattern MySubmissions.tsx already uses
// for the identical reason on this same route.
//
// Icon audit (2026-09-24): every nav control carries a lucide glyph that
// matches its destination, using the same vocabulary as the console rail
// (Puzzle = plugins, LayoutTemplate = templates) so the two chromes agree.
/** True for a page the proxy answers 404 on a personal install (lib/edition.ts EDITION_ROUTES), so no link should lead to it. */
const notServed = (path: string): boolean => isPersonalEdition() && isPersonalDenied(path);

export default function TemplatesPublicChrome({
  children,
  footer,
}: {
  children: ReactNode;
  // Optional page-specific footer content; defaults to the templates blurb so
  // the existing /templates call sites need no change.
  footer?: ReactNode;
}) {
    const tHC = useTranslations("hcComponentsTemplatesTemplatesPublicChrome");
  const [authed, setAuthed] = useState(false);
  useEffect(() => {
    let cancelled = false;
    void auth.init().then((kc) => {
      if (!cancelled && kc) setAuthed(true);
    });
    return () => {
      cancelled = true;
    };
  }, []);

  return (
    <div className="vd-pub marketing-root relative min-h-screen w-full overflow-x-clip bg-background text-foreground antialiased">
      <div aria-hidden className="pointer-events-none fixed inset-0 -z-10">
        <div className="absolute -top-40 left-1/2 h-[38rem] w-[38rem] -translate-x-1/2 rounded-full bg-primary/16 blur-[140px]" />
      </div>

      <nav
        className="mx-auto flex max-w-6xl flex-wrap items-center justify-center gap-y-3 px-5 py-5 sm:justify-between sm:px-8"
        aria-label="Site"
      >
        <Link
          href="/"
          className="brand-name flex items-center gap-2.5 text-lg font-semibold tracking-tight"
          aria-label={tHC("s5")}
        >
          <img src="/logo-mark.png" alt="" className="h-8 w-auto object-contain" draggable={false} />
          <span>
            Vega<span className="text-primary">Dūta</span>
          </span>
        </Link>
        <div className="flex flex-wrap items-center justify-center gap-2 sm:gap-3">
          <Button asChild variant="ghost" size="sm" className="text-muted-foreground hover:text-foreground">
            <Link href="/">
              <Home className="size-4" aria-hidden="true" /> Home
            </Link>
          </Button>
          {/* Cross-links the two public marketplace surfaces (templates <->
              plugins) - always visible, authed or not. */}
          <Button asChild variant="ghost" size="sm" className="text-muted-foreground hover:text-foreground">
            <Link href="/plugins">
              <Puzzle className="size-4" aria-hidden="true" /> Plugins
            </Link>
          </Button>
          {/* /directory, /templates/mine and /signup are not served on a personal install (the proxy 404s
              them; lib/edition.ts EDITION_ROUTES), so the chrome does not link to them. */}
          {!notServed("/directory") && (
            <Button asChild variant="ghost" size="sm" className="text-muted-foreground hover:text-foreground">
              <Link href="/directory">
                <Fingerprint className="size-4" aria-hidden="true" /> Directory
              </Link>
            </Button>
          )}
          {authed ? (
            <>
              {!notServed("/templates/mine") && (
                <Button asChild variant="ghost" size="sm" className="text-muted-foreground hover:text-foreground">
                  <Link href="/templates/mine">
                    <LayoutTemplate className="size-4" aria-hidden="true" /> {tHC("s4")}
                  </Link>
                </Button>
              )}
              <Button asChild variant="outline" size="sm">
                <Link href="/">
                  <ArrowLeft className="size-4" aria-hidden="true" /> {tHC("s3")}
                </Link>
              </Button>
            </>
          ) : (
            <>
              {isBillingEnabled() && (
                <Button asChild variant="ghost" size="sm" className="text-muted-foreground hover:text-foreground">
                  <Link href="/billing">
                    <Wallet className="size-4" aria-hidden="true" /> Pricing
                  </Link>
                </Button>
              )}
              <Button
                variant="ghost"
                size="sm"
                className="text-muted-foreground hover:text-foreground"
                onClick={() =>
                  auth.login({
                    redirectUri: `${window.location.origin}${notServed("/templates/mine") ? "/templates" : "/templates/mine"}`,
                  })
                }
                data-testid="login-landing-button"
              >
                <LogIn className="size-4" aria-hidden="true" /> Sign in
              </Button>
              {!notServed("/signup") && (
                <Button asChild variant="outline" size="sm">
                  <Link href="/signup">
                    {tHC("s2")} <ArrowRight className="size-4" aria-hidden="true" />
                  </Link>
                </Button>
              )}
            </>
          )}
        </div>
      </nav>

      <main className="mx-auto w-full max-w-6xl px-5 pb-20 sm:px-8">{children}</main>

      <footer className="mx-auto w-full max-w-6xl px-5 py-10 text-xs text-muted-foreground sm:px-8">
        {footer ?? (
          <p>
            {tHC("s1")}
          </p>
        )}
      </footer>
    </div>
  );
}
