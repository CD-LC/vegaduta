import EarnLanding from "../components/earn-landing/EarnLanding";

// ---------------------------------------------------------------------------
// /earn — the automation-entrepreneur playbook (2026-08-03). SERVER
// component: exports route metadata; all content/interactivity live in the
// EarnLanding client tree (components/earn-landing/). Public route — has a
// PUBLIC_ROUTES entry in lib/auth.ts and a sitemap.ts entry; renders
// standalone chrome (no AppShell, no useAuth — the /docs//hire pattern),
// because public routes mount with NO AuthContext.
//
// Canonical resolves against layout.tsx's metadataBase (SITE_URL)
// → <SITE_URL>/earn. Metadata stays inline English like every other
// route's metadata (server-side, outside the I18nProvider); the page body is
// fully keyed under the "landing" namespace (earnPage_*) in messages/en.json.
// ---------------------------------------------------------------------------

const TITLE = "Earn with agentic AI — VegaDūta";
// ~150 chars — inside the ~155 SERP budget.
const DESCRIPTION =
  "The honest playbook for automation entrepreneurs: learn agentic AI free, automate workflows for real businesses, and price on outcomes — not hours.";

export const metadata = {
  title: TITLE,
  description: DESCRIPTION,
  alternates: { canonical: "/earn" },
  openGraph: {
    title: TITLE,
    description: DESCRIPTION,
    url: "/earn",
  },
};

export default function EarnPage() {
  return <EarnLanding />;
}
