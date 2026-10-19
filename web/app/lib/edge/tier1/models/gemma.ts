// Pure data: built-in fallback manifest entries for the gemma family (extracted
// verbatim from tier1.ts; no logic here). Registered in ../registry.ts.
import type { ManifestModel } from "../../capabilities";

export const GEMMA_MODEL: ManifestModel =   {
    id: "gemma-2-2b-it-q4f16_1-MLC",
    displayName: "On-device model — efficient",
    detailName:
      "Gemma 2 2B Instruct (q4f16), about 1.9 GB. Downloaded from the public MLC/WebLLM model CDN.",
    sizeBytes: 1_895_300_000,
    minDeviceMemoryGB: 4,
    // 2026-08-10 audit finding #6 (tier1.ts half) — same derivation as the
    // Qwen2.5-1.5B entry's comment above (conservative sibling ratio ≈0.292
    // applied to this model's own sizeBytes, rounded up to the next 256 MiB
    // step): 1_895_300_000 × 0.292 ≈ 554.2 MB → 805_306_368 (768 MiB, i.e.
    // 3 × 256 MiB — 554 MB doesn't fit in 2 × 256 MiB (512 MiB), so it
    // rounds up to the next step).
    minMaxBufferSize: 805_306_368,
    tier: 1,
    url: null,
    sha256: null,
    speedTier: "fast",
    contextWindowSize: 4096,
  };
