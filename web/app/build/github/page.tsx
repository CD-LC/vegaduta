"use client";

// Dedicated "Connect GitHub" screen for the App Builder BYO-repo pivot
// (docs/APP-BUILDER-BYO-REPO-CONTRACT-2026-07-30.md §4). This is a focused,
// linkable/bookmarkable home for the connect/disconnect flow — the actual
// form lives in GithubConnectCard (shared with the compact status affordance
// on ../page.tsx's header) so there is exactly one place that owns the
// connect/disconnect logic, not two.
//
// Follows ../page.tsx's own conventions: AppShell wrapper, usePageReady to
// avoid an error-flash on load, honest "not available on this deployment"
// branch when NEXT_PUBLIC_APP_BUILDER_URL isn't set. Copy is inline (not
// i18n keys) for the same reason as the unavailable-state block in
// ../page.tsx: web/messages/en.json is fragile and may be under concurrent
// edit; a follow-up pass can promote this to real i18n keys.
import { useEffect, useState } from "react";
import Link from "next/link";
import { ArrowLeft, Sparkles } from "lucide-react";
import AppShell from "../../components/AppShell";
import { usePageReady } from "../../components/RouteTransition";
import GithubConnectCard from "../components/GithubConnectCard";
import { Button } from "../../components/ui/button";
import { APP_BUILDER_URL, APP_BUILDER_CONFIGURED } from "../lib";

export default function ConnectGithubPage() {
  const [loaded, setLoaded] = useState(false);
  useEffect(() => setLoaded(true), []);
  usePageReady(loaded);

  if (!APP_BUILDER_CONFIGURED) {
    return (
      <AppShell title="Connect GitHub" eyebrow="App Builder" wide>
        <div className="mx-auto max-w-xl py-16 text-center">
          <div className="mb-4 inline-flex size-12 items-center justify-center rounded-2xl bg-muted text-muted-foreground">
            <Sparkles aria-hidden="true" size={22} />
          </div>
          <h2 className="text-lg font-semibold text-foreground">App Builder is not available on this deployment</h2>
          <p className="mx-auto mt-2 max-w-md text-sm leading-relaxed text-muted-foreground">
            The app-builder service is an optional add-on and is not running here yet. There is nothing to
            connect GitHub to.
          </p>
        </div>
      </AppShell>
    );
  }

  return (
    <AppShell title="Connect GitHub" eyebrow="App Builder" wide>
      <div className="mx-auto max-w-2xl py-2">
        <Button asChild variant="ghost" size="sm" className="mb-4 -ml-2 text-muted-foreground">
          <Link href="/build">
            <ArrowLeft aria-hidden="true" size={14} /> Back to App Builder
          </Link>
        </Button>

        <GithubConnectCard />

        <p className="mt-4 text-xs leading-relaxed text-muted-foreground">
          Nothing here talks to a preview container we run — that infrastructure is being retired. Once a repo
          is connected, go back to App Builder and keep chatting with the assistant; each change opens or
          updates a pull request on {APP_BUILDER_URL ? "your connected repo" : "GitHub"}.
        </p>
      </div>
    </AppShell>
  );
}
