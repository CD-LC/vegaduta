// ---------------------------------------------------------------------------
// AGGREGATOR ONLY (2026-07-24). Everything that used to live inline here now
// lives in recipes-core-patterns.tsx, and the shared types + NODE_META live in
// recipes-types.ts.
//
// IMPORTANT: importing this module pulls in EVERY sector pack (~1.9 MB of
// source). Do NOT import it from a page that only needs to render the
// category list — use recipes-index.ts (metadata only) plus
// recipes-registry.ts (lazy per-pack loaders) instead. This file remains for
// anything that genuinely needs the whole catalogue at once.
//
// Re-exports the types so the ~34 pack modules can keep their existing
// `import type { Industry } from "./recipes-data"` line unchanged (type-only,
// erased at build time, so it costs nothing at runtime).
// ---------------------------------------------------------------------------
export type { NodeKind, RecipeStep, AgentRole, RecipeBuild, Recipe, Industry } from "./recipes-types";
export { NODE_META } from "./recipes-types";

import type { Industry } from "./recipes-types";
import { INDUSTRIES_CORE } from "./recipes-core-patterns";
import { INDUSTRIES_AUTO } from "./recipes-automotive-telecom";
import { INDUSTRIES_INFRA } from "./recipes-utilities-construction";
import { INDUSTRIES_CIVIC } from "./recipes-travel-nonprofit";
import { INDUSTRIES_HEALTHPLUS } from "./recipes-pharma-fitness";
import { INDUSTRIES_PROOPS } from "./recipes-law-gaming";
import { INDUSTRIES_STAFFFIN } from "./recipes-staffing-lending";
import { INDUSTRIES_INSTITUTIONS } from "./recipes-hospital-education";
import { INDUSTRIES_TRADE } from "./recipes-shipping-insurance-d2c";
import { INDUSTRIES_AVMEDIA } from "./recipes-aviation-media";
import { INDUSTRIES_FACEVENT } from "./recipes-facilities-events";
import { INDUSTRIES_VETSALON } from "./recipes-vet-salon";
import { INDUSTRIES_ACCTSOLAR } from "./recipes-accounting-solar";
import { INDUSTRIES_MINETRADE } from "./recipes-mining-customs";
import { INDUSTRIES_LUXFMCG } from "./recipes-luxury-fmcg";
import { INDUSTRIES_TOWERWASTE } from "./recipes-towers-waste";
import { INDUSTRIES_WHPRINT } from "./recipes-warehousing-printing";
import { INDUSTRIES_EVDRONE } from "./recipes-ev-drone";
import { INDUSTRIES_DIAGELDER } from "./recipes-diagnostics-eldercare";
import { INDUSTRIES_MFICOOP } from "./recipes-microfinance-coopbank";
import { INDUSTRIES_QCOMMKITCHEN } from "./recipes-qcommerce-cloudkitchen";
import { INDUSTRIES_TEXTILECRAFT } from "./recipes-textile-handicraft";
import { INDUSTRIES_SOCDC } from "./recipes-soc-datacenter";
import { INDUSTRIES_TRANSITTEMPLE } from "./recipes-transit-temple";
import { INDUSTRIES_DAIRYSEA } from "./recipes-dairy-seafood";
import { INDUSTRIES_FREELANCE } from "./recipes-freelancer-smb";
import { INDUSTRIES_EVERYDAY } from "./recipes-everyday-personalhealth";
import { INDUSTRIES_CREATORS } from "./recipes-creators-shortform";
import { INDUSTRIES_LEARNING } from "./recipes-learners-tutors";
import { INDUSTRIES_GROWTH } from "./recipes-growth-sales";
import { INDUSTRIES_WEBDATA } from "./recipes-scraping-dataentry";
import { INDUSTRIES_DEVQA } from "./recipes-qa-devteams";
import { INDUSTRIES_CAREERMONEY } from "./recipes-career-personalfinance";
import { INDUSTRIES_FAMILY } from "./recipes-family-couples";
import { INDUSTRIES_WELLBEING } from "./recipes-wellbeing";

export const INDUSTRIES: Industry[] = [
  ...INDUSTRIES_CORE,

  // ---- Appended sector packs (authored in standalone modules, see imports) ----
  ...INDUSTRIES_AUTO,
  ...INDUSTRIES_INFRA,
  ...INDUSTRIES_CIVIC,
  ...INDUSTRIES_HEALTHPLUS,
  ...INDUSTRIES_PROOPS,
  ...INDUSTRIES_STAFFFIN,
  ...INDUSTRIES_INSTITUTIONS,
  ...INDUSTRIES_TRADE,
  ...INDUSTRIES_AVMEDIA,
  ...INDUSTRIES_FACEVENT,
  ...INDUSTRIES_VETSALON,
  ...INDUSTRIES_ACCTSOLAR,
  ...INDUSTRIES_MINETRADE,
  ...INDUSTRIES_LUXFMCG,
  ...INDUSTRIES_TOWERWASTE,
  ...INDUSTRIES_WHPRINT,
  // --- 2026-07-24: further industries ---
  ...INDUSTRIES_DIAGELDER,
  ...INDUSTRIES_EVDRONE,
  ...INDUSTRIES_QCOMMKITCHEN,
  ...INDUSTRIES_TEXTILECRAFT,
  ...INDUSTRIES_MFICOOP,
  ...INDUSTRIES_DAIRYSEA,
  ...INDUSTRIES_SOCDC,
  ...INDUSTRIES_TRANSITTEMPLE,
  // --- 2026-07-24: PEOPLE, not sectors — individuals, creators, small operators ---
  ...INDUSTRIES_EVERYDAY,
  ...INDUSTRIES_FREELANCE,
  ...INDUSTRIES_CREATORS,
  ...INDUSTRIES_LEARNING,
  ...INDUSTRIES_GROWTH,
  ...INDUSTRIES_WEBDATA,
  ...INDUSTRIES_DEVQA,
  ...INDUSTRIES_CAREERMONEY,
  ...INDUSTRIES_FAMILY,
  ...INDUSTRIES_WELLBEING,
];

