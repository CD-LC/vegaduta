import type { Industry } from "./recipes-types";
import { INDUSTRIES_CORE_A } from "./recipes-core-patterns-a";
import { INDUSTRIES_CORE_B } from "./recipes-core-patterns-b";
import { INDUSTRIES_CORE_C } from "./recipes-core-patterns-c";

// ---------------------------------------------------------------------------
// The original 19 categories, moved OUT of recipes-data.tsx (2026-07-24) so
// that file becomes a thin aggregator and every category can be lazy-loaded.
// Split into three data modules (file-size ratchet); order is unchanged.
// ---------------------------------------------------------------------------

export const INDUSTRIES_CORE: Industry[] = [...INDUSTRIES_CORE_A, ...INDUSTRIES_CORE_B, ...INDUSTRIES_CORE_C];
