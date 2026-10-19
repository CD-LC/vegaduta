"use client";

import Image from "next/image";
import Link from "next/link";
import { ArrowRight } from "lucide-react";
import { Button } from "../../components/ui/button";
import { useAuthedHint } from "../../lib/hooks/useAuthedHint";
import BrandName from "../../components/BrandName";

// Shared public-surface nav for /help/workflow-recipes and its per-industry
// pages (/help/workflow-recipes/[industry]) - both are PUBLIC_ROUTES (see
// lib/auth.ts), rendered without AuthContext, so no AppShell/useAuth here.
// Extracted so both routes stay in sync instead of drifting (see
// FREE-TIER/nav-audit notes: the two pages previously duplicated this inline
// and the [industry] page never got it at all, wrapping in AppShell instead -
// which throws on useAuth() for a logged-out visitor since public routes
// render with no AuthContext.Provider).
//
// Auth-aware via useAuthedHint (the vd_authed localStorage hint AuthProvider
// sets on sign-in and clears on logout - a UI hint only, never a security
// decision): a signed-in visitor gets a highlighted "Back to console" and no
// "Start free"; a logged-out visitor gets the acquisition pair.
export default function PublicWorkflowNav() {
  const authedHint = useAuthedHint();

  return (
    <nav className="mx-auto flex max-w-7xl flex-wrap items-center justify-center gap-y-3 px-5 py-5 sm:justify-between sm:px-8">
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
        <span><BrandName /></span>
      </Link>
      <div className="flex flex-wrap items-center justify-center gap-2 sm:gap-3">
        <Button asChild variant="ghost" size="sm" className="text-muted-foreground hover:text-foreground">
          <Link href="/docs">Docs</Link>
        </Button>
        {authedHint ? (
          <Button asChild size="sm">
            <Link href="/help">
              Back to console
              <ArrowRight className="size-4" aria-hidden="true" />
            </Link>
          </Button>
        ) : (
          <>
            <Button asChild variant="ghost" size="sm" className="text-muted-foreground hover:text-foreground">
              <Link href="/help">Open the console</Link>
            </Button>
            <Button asChild variant="outline" size="sm">
              <Link href="/signup">Start free</Link>
            </Button>
          </>
        )}
      </div>
    </nav>
  );
}
