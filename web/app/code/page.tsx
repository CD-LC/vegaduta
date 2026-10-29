"use client";

import { useEffect, useState } from "react";
import Link from "next/link";
import { ArrowLeft } from "lucide-react";
import PageHeader from "../components/ax/PageHeader";
import LocalCodePanel from "../components/localcode/LocalCodePanel";
import { usePageReady } from "../components/RouteTransition";
import { Button } from "../components/ui/button";
import { auth } from "../lib/auth";
import { useTranslations } from "../lib/i18n";

/**
 * /code — the browser-local code assistant: open a folder from this device,
 * index it in the tab, ask questions about it and apply suggested edits, with
 * nothing ever leaving the browser. All behaviour lives in
 * components/localcode/LocalCodePanel.tsx; this file is only the route chrome.
 *
 * PUBLIC since 2026-09-12, and the chrome is why it could not be before. The
 * page never needed a session — no API call, no bearer token, no tenant; its
 * whole data layer is app/lib/localcode/, which contains no fetch() — but it
 * used to wrap itself in AppShell, and AppShell calls useAuth() unconditionally
 * while AuthProvider hands a route in PUBLIC_ROUTES no AuthContext at all. So a
 * "/code" entry in PUBLIC_ROUTES on its own would have thrown on first render.
 * Both halves land together: the entry is in web/app/lib/auth.ts (see the
 * history above that array) and AppShell is replaced below by standalone chrome
 * that self-gates its own nav — the shape app/workflow/[shareId] uses for its
 * header/footer, and the auth.init() session check components/templates/
 * TemplatesPublicChrome.tsx uses so a SIGNED-IN visitor is not shown "Sign in".
 *
 * Why self-gating and not just a logged-out bar: /code is linked from inside
 * the console as well as reachable cold. A signed-in person who followed a
 * console link must still get a way back (the "Back to console" button below),
 * and must not be told to sign in to a session they already have — that reads
 * as "I have been logged out", which is the same misreading TemplatesPublicChrome
 * documents on /templates. `authed` starts false and only ever goes true, so the
 * signed-out bar is what renders while auth.init() is in flight; that is the
 * correct default for a page whose whole premise is that no account is needed.
 *
 * usePageReady(true) unconditionally, for app/workflow/[shareId]'s reason: with
 * no AppShell registered, RouteTransition covers this route with its full
 * viewport layer, and everything this page can be waiting for (browser support,
 * a remembered folder, an on-device model) is designed content inside
 * LocalCodePanel, not a flash worth hiding.
 *
 * The nav-rail entry is owned by the AppShell agent, so this page is still
 * reachable by URL only — unchanged by this commit.
 */
export default function LocalCodePage() {
  usePageReady(true);
  const t = useTranslations("localCode");
  const [authed, setAuthed] = useState(false);

  useEffect(() => {
    let cancelled = false;
    // auth.init() is memoized inside lib/auth, and AuthProvider deliberately
    // skips it on a public route — so this is the only call on /code, and it
    // resolves to null (never throws, never redirects) for a visitor with no
    // session. A failure is not handled beyond this: the logged-out bar is
    // already the correct fallback.
    auth
      .init()
      .then((kc) => {
        if (!cancelled && kc) setAuthed(true);
      })
      .catch(() => {
        // check-sso can fail for reasons unrelated to whether there is a
        // session (a blocked third-party iframe, a slow realm). Staying on the
        // signed-out bar is right, and this page needs nothing from a token.
      });
    return () => {
      cancelled = true;
    };
  }, []);

  return (
    <main className="vd-pub relative flex min-h-screen w-full flex-col overflow-x-clip bg-background text-foreground antialiased">
      <header className="sticky top-0 z-40 border-b border-border/60 bg-background/80 backdrop-blur-xl">
        <div className="mx-auto flex h-16 w-full max-w-6xl items-center gap-3 px-4 sm:px-6">
          <Link href="/" className="flex items-center gap-2" aria-label="VegaDūta home">
            <img src="/logo-mark.png" alt="" width={38} height={28} className="h-7 w-auto shrink-0 object-contain" />
            <span className="brand-name hidden text-lg font-semibold tracking-tight sm:inline">
              Vega<span className="text-primary">Dūta</span>
            </span>
          </Link>

          <div className="ms-auto flex items-center gap-2">
            {authed ? (
              <Button asChild size="sm" variant="outline">
                <Link href="/">
                  <ArrowLeft className="size-3.5" />
                  <span className="hidden sm:inline">Back to console</span>
                  <span className="sm:hidden">Console</span>
                </Link>
              </Button>
            ) : (
              <>
                <Button
                  variant="ghost"
                  size="sm"
                  className="text-muted-foreground hover:text-foreground"
                  onClick={() => auth.login({ redirectUri: `${window.location.origin}/code` })}
                  data-testid="login-landing-button"
                >
                  Sign in
                </Button>
                <Button asChild size="sm" variant="outline">
                  <Link href="/signup">Start free</Link>
                </Button>
              </>
            )}
          </div>
        </div>
      </header>

      <div className="mx-auto w-full max-w-6xl flex-1 px-4 py-8 sm:px-6">
        <PageHeader title={t("title")} subtitle={t("subtitle")} eyebrow={t("eyebrow")} />
        <LocalCodePanel />
      </div>
    </main>
  );
}
