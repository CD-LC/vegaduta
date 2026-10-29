"use client";

import type { ReactNode } from "react";
import Link from "next/link";
import { ArrowRight } from "lucide-react";
import { Button } from "./ui/button";
import { useAuthedHint } from "../lib/hooks/useAuthedHint";
import { useTranslations } from "../lib/i18n";

// Auth-aware CTA cluster for PUBLIC pages (the ones outside AuthProvider's
// Keycloak lifecycle — /docs, /billing, /status, /changelog, /api-reference,
// /whatsapp-cost-calculator, /hire, /earn ...). Before this component those
// navs hard-rendered "Sign in" + "Start free" to everyone, so a signed-in
// tenant was told to sign in to the session they already had, and the
// console-bound links they were offered resolved on the marketing host.
//
// Usage: wrap the page's signed-out auth buttons as children. A visitor with
// the vd_authed session hint gets a single "Open console" link to "/" instead
// — which proxy.js's apex->app redirect then puts on the console host (or
// page.tsx's / -> /build handoff does on a host that doesn't redirect).
// Children render during SSR either way, so crawlers always see the
// acquisition CTAs and there is no layout shift against the server HTML.
export default function PublicAuthCtas({
  children,
  size = "sm",
  variant = "outline",
}: {
  /** The page's signed-out CTAs (Sign in / Start free / Get started ...). */
  children: ReactNode;
  /** Size/variant of the authed-state "Open console" button. */
  size?: "sm" | "default" | "lg";
  variant?: "default" | "outline" | "ghost";
}) {
    const tHC = useTranslations("hcComponentsPublicAuthCtas");
  const authed = useAuthedHint();
  if (authed) {
    return (
      <Button asChild size={size} variant={variant}>
        <Link href="/">
          {tHC("hcComponentsPublicAuthCtas.s1")} <ArrowRight className="size-4" aria-hidden="true" />
        </Link>
      </Button>
    );
  }
  return <>{children}</>;
}
