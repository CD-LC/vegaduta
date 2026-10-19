"use client";

import DemoLanding from "../demo-landing/DemoLanding";
import { useGeo } from "../../lib/geo";
import { isOssSite } from "../../lib/siteMode";
import OssLanding from "../oss-landing/OssLanding";

// GenesisExperience is what AuthProvider renders for an unauthenticated
// visitor (see AuthProvider.js). Direct feedback: "replace that main page
// with this one" - the single-hero AXON Genesis Landing rebuild
// (GenesisAxonLanding.js/genesisAxon.css, design_files/Genesis Landing.dc.html)
// was retired here in favor of AgenticAiLanding.js (design_files/Agentic AI
// Landing.dc.html), the audience-segmented For You/Business/Enterprise/
// Education page originally built at the separate /explore route (task
// #477).
//
// CORRECTED 2026-07-27 nav audit: AgenticAiLanding's stint at the logged-out
// root was itself superseded by the "say it, watch it build" Magic Studio
// work (see docs/memory: magic-studio-demo-workflows.md) - this component now
// renders DemoLanding (demo-landing/DemoLanding.tsx), which is the actual
// live "/" experience; AgenticAiLanding only remains reachable at /explore
// (see explore/page.js). A prior version of this comment claimed
// AgenticAiLanding was "now promoted to the logged-out root itself" - that
// is no longer true and should not be relied on.
//
// GenesisAxonLanding.js and genesisAxon.css are unused (superseded by
// AgenticAiLanding, itself since superseded by DemoLanding at root - neither
// swap deleted the loser). Per explicit instruction at the time ("delete old
// one"), GenesisAxonLanding.js was meant to be removed rather than kept
// in place like every other superseded file in this project (MarketingHome.js,
// the Act1-7 components, etc.), but it is still present in the tree as of
// this audit - re-verify before assuming it's gone.
// Geo-targeting (2026-07): `variant` comes from useGeo() (lib/geo.tsx),
// itself fed by RootLayout's server-side read of Cloudflare's CF-IPCountry
// header (see layout.tsx) - GenesisExperience stays a plain client
// component (no headers() call here, that would break the build - see
// lib/geo.tsx's own comment for why context, not a direct import, is what
// carries that value across AuthProvider's hardcoded render of this
// component). "india" renders the existing India-focused experience
// unchanged; anything else ("global", or the "global" default if no
// GeoProvider is present) renders the neutral variant.
export default function GenesisExperience() {
  const { variant } = useGeo();
  // Site-mode switch (lib/siteMode.ts): "oss" (default) renders the technical
  // open-source landing; "classic" renders the original DemoLanding below.
  if (isOssSite()) return <OssLanding />;
  return <DemoLanding variant={variant} />;
}
