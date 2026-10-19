// Pure data: built-in fallback manifest entries for the phi family (extracted
// verbatim from tier1.ts; no logic here). Registered in ../registry.ts.
import type { ManifestModel } from "../../capabilities";

  // Appended pair (2026-08-06) — keep in sync with the server seed's
  // matching pair in EdgeAgentsConfigController.DEFAULT_MANIFEST.
export const PHI_MODEL: ManifestModel =   {
    id: "Phi-3.5-mini-instruct-q4f16_1-MLC",
    displayName: "On-device model — most capable",
    detailName:
      "Phi 3.5 Mini Instruct (q4f16), about 3.7 GB. Downloaded from the public MLC/WebLLM model CDN.",
    sizeBytes: 3_672_070_000,
    minDeviceMemoryGB: 8,
    minMaxBufferSize: 1_073_741_824,
    tier: 1,
    url: null,
    sha256: null,
    speedTier: "capable",
    contextWindowSize: 4096,
  };
