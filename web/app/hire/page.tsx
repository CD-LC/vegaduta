import { headers } from "next/headers";
import HireLanding from "../components/hire-landing/HireLanding";
import { resolveLandingVariant } from "../lib/geo-config";

// ---------------------------------------------------------------------------
// /hire — the "Hiring Papers" public landing (2026-07-31 redesign, approved v3
// mockup). SERVER component: exports the route metadata; all content and
// interactivity live in the HireLanding client tree
// (components/hire-landing/). Public route — has a PUBLIC_ROUTES entry in
// lib/auth.ts and a sitemap.ts entry; renders standalone chrome (no AppShell,
// no useAuth — the /docs pattern), because public routes mount with NO
// AuthContext and an AppShell here would be an SSR 500.
//
// Canonical resolves against layout.tsx's metadataBase (SITE_URL)
// → <SITE_URL>/hire. Copy note: page metadata stays inline English
// like every other route's metadata (metadata is server-side and outside the
// I18nProvider) — geo-gated below for currency only, not translated; the
// page body itself is fully keyed under the "hire" namespace in
// web/messages/en.json, including its own India/global copy split.
// ---------------------------------------------------------------------------

const TITLE = "Hire AI staff — VegaDūta";
// 152 chars — inside the ~155 SERP budget. The currency mention is the one
// India-specific fact in this copy, so — same CF-IPCountry read as
// layout.tsx's own generateMetadata(), same reasoning — it can't stay a
// static export: a US/EU visitor sharing this link must not see "₹0" in the
// unfurled preview.
function description(isIndia: boolean) {
  const price = isIndia ? "₹0" : "$0";
  return `Read the employment dossier of your first AI hire — CV, work trial, salary worksheet and contract. Interview them first; hiring starts at ${price}, no card.`;
}

export async function generateMetadata() {
  const headerStore = await headers();
  const isIndia = resolveLandingVariant(headerStore.get("cf-ipcountry")) === "india";
  const desc = description(isIndia);
  return {
    title: TITLE,
    description: desc,
    alternates: { canonical: "/hire" },
    openGraph: {
      title: TITLE,
      description: desc,
      url: "/hire",
      type: "website",
      images: ["/og-image.png"],
    },
  };
}

export default function HirePage() {
  return <HireLanding />;
}
