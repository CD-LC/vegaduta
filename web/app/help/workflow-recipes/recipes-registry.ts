import type { Industry } from "./recipes-types";

// ---------------------------------------------------------------------------
// LAZY PACK REGISTRY. Each entry is a dynamic import, so a sector pack's
// recipe bodies only reach the browser when someone actually opens that
// category. Nothing here is evaluated at module load.
//
// Adding a pack: create recipes-<name>.tsx exporting INDUSTRIES_<KEY>, add a
// line below, and regenerate recipes-index.ts.
// ---------------------------------------------------------------------------

export const PACK_LOADERS: Record<string, () => Promise<Industry[]>> = {
  "recipes-accounting-solar": () => import("./recipes-accounting-solar").then((m) => m.INDUSTRIES_ACCTSOLAR),
  "recipes-automotive-telecom": () => import("./recipes-automotive-telecom").then((m) => m.INDUSTRIES_AUTO),
  "recipes-aviation-media": () => import("./recipes-aviation-media").then((m) => m.INDUSTRIES_AVMEDIA),
  "recipes-career-personalfinance": () => import("./recipes-career-personalfinance").then((m) => m.INDUSTRIES_CAREERMONEY),
  "recipes-core-patterns": () => import("./recipes-core-patterns").then((m) => m.INDUSTRIES_CORE),
  "recipes-creators-shortform": () => import("./recipes-creators-shortform").then((m) => m.INDUSTRIES_CREATORS),
  "recipes-dairy-seafood": () => import("./recipes-dairy-seafood").then((m) => m.INDUSTRIES_DAIRYSEA),
  "recipes-diagnostics-eldercare": () => import("./recipes-diagnostics-eldercare").then((m) => m.INDUSTRIES_DIAGELDER),
  "recipes-ev-drone": () => import("./recipes-ev-drone").then((m) => m.INDUSTRIES_EVDRONE),
  "recipes-everyday-personalhealth": () => import("./recipes-everyday-personalhealth").then((m) => m.INDUSTRIES_EVERYDAY),
  "recipes-facilities-events": () => import("./recipes-facilities-events").then((m) => m.INDUSTRIES_FACEVENT),
  "recipes-family-couples": () => import("./recipes-family-couples").then((m) => m.INDUSTRIES_FAMILY),
  "recipes-freelancer-smb": () => import("./recipes-freelancer-smb").then((m) => m.INDUSTRIES_FREELANCE),
  "recipes-growth-sales": () => import("./recipes-growth-sales").then((m) => m.INDUSTRIES_GROWTH),
  "recipes-hospital-education": () => import("./recipes-hospital-education").then((m) => m.INDUSTRIES_INSTITUTIONS),
  "recipes-law-gaming": () => import("./recipes-law-gaming").then((m) => m.INDUSTRIES_PROOPS),
  "recipes-learners-tutors": () => import("./recipes-learners-tutors").then((m) => m.INDUSTRIES_LEARNING),
  "recipes-luxury-fmcg": () => import("./recipes-luxury-fmcg").then((m) => m.INDUSTRIES_LUXFMCG),
  "recipes-microfinance-coopbank": () => import("./recipes-microfinance-coopbank").then((m) => m.INDUSTRIES_MFICOOP),
  "recipes-mining-customs": () => import("./recipes-mining-customs").then((m) => m.INDUSTRIES_MINETRADE),
  "recipes-pharma-fitness": () => import("./recipes-pharma-fitness").then((m) => m.INDUSTRIES_HEALTHPLUS),
  "recipes-qa-devteams": () => import("./recipes-qa-devteams").then((m) => m.INDUSTRIES_DEVQA),
  "recipes-qcommerce-cloudkitchen": () => import("./recipes-qcommerce-cloudkitchen").then((m) => m.INDUSTRIES_QCOMMKITCHEN),
  "recipes-scraping-dataentry": () => import("./recipes-scraping-dataentry").then((m) => m.INDUSTRIES_WEBDATA),
  "recipes-shipping-insurance-d2c": () => import("./recipes-shipping-insurance-d2c").then((m) => m.INDUSTRIES_TRADE),
  "recipes-soc-datacenter": () => import("./recipes-soc-datacenter").then((m) => m.INDUSTRIES_SOCDC),
  "recipes-staffing-lending": () => import("./recipes-staffing-lending").then((m) => m.INDUSTRIES_STAFFFIN),
  "recipes-textile-handicraft": () => import("./recipes-textile-handicraft").then((m) => m.INDUSTRIES_TEXTILECRAFT),
  "recipes-towers-waste": () => import("./recipes-towers-waste").then((m) => m.INDUSTRIES_TOWERWASTE),
  "recipes-transit-temple": () => import("./recipes-transit-temple").then((m) => m.INDUSTRIES_TRANSITTEMPLE),
  "recipes-travel-nonprofit": () => import("./recipes-travel-nonprofit").then((m) => m.INDUSTRIES_CIVIC),
  "recipes-utilities-construction": () => import("./recipes-utilities-construction").then((m) => m.INDUSTRIES_INFRA),
  "recipes-vet-salon": () => import("./recipes-vet-salon").then((m) => m.INDUSTRIES_VETSALON),
  "recipes-warehousing-printing": () => import("./recipes-warehousing-printing").then((m) => m.INDUSTRIES_WHPRINT),
  "recipes-wellbeing": () => import("./recipes-wellbeing").then((m) => m.INDUSTRIES_WELLBEING),
};

// Small in-memory cache so re-opening a category doesn't refetch the chunk.
const cache = new Map<string, Industry[]>();

export async function loadPack(pack: string): Promise<Industry[]> {
  const hit = cache.get(pack);
  if (hit) return hit;
  const loader = PACK_LOADERS[pack];
  if (!loader) return [];
  const packed = await loader();
  cache.set(pack, packed);
  return packed;
}

/** Load just one category's full recipes (by industry id) from its pack. */
export async function loadIndustry(pack: string, industryId: string): Promise<Industry | null> {
  const all = await loadPack(pack);
  return all.find((i) => i.id === industryId) ?? null;
}
