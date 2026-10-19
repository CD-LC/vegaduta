// Pure data: built-in fallback manifest entries for the qwen family (extracted
// verbatim from tier1.ts; no logic here). Registered in ../registry.ts.
import type { ManifestModel } from "../../capabilities";

export const QWEN_MODEL: ManifestModel =   {
    id: "Qwen2.5-1.5B-Instruct-q4f16_1-MLC",
    displayName: "On-device model — balanced",
    detailName:
      "Qwen2.5 1.5B Instruct (q4f16), about 1.6 GB. Downloaded from the public MLC/WebLLM model CDN.",
    sizeBytes: 1_629_750_000,
    minDeviceMemoryGB: 4,
    // 2026-08-10 audit finding #6 (tier1.ts half): was 0 — no WebGPU-side
    // floor at all, while minDeviceMemoryGB sits at/under the Device Memory
    // API's own 8GB reporting ceiling on Chrome, so that signal alone can't
    // tell a borderline device from a workstation (capabilities.ts's half
    // of this fix handles the null-reporting case; this is the manifest
    // floor). Derived, not invented: this manifest's two capable-tier
    // entries below (Llama-3.2-3B, Phi-3.5-mini) are the only siblings that
    // already set a real floor, at ratios of
    // 1_073_741_824/2_263_690_000 ≈ 0.474 and 1_073_741_824/3_672_070_000 ≈
    // 0.292 of their own sizeBytes respectively. Applying the SMALLER
    // (more conservative — a floor should never overestimate what a small
    // model needs) of those two ratios to this model's own sizeBytes gives
    // 1_629_750_000 × 0.292 ≈ 476.6 MB, rounded UP to the next 256 MiB step
    // (the same step size the two existing 1 GiB floors already sit on: 1
    // GiB = 4 × 256 MiB) = 536_870_912 (512 MiB).
    minMaxBufferSize: 536_870_912,
    tier: 1,
    url: null,
    sha256: null,
    speedTier: "fast",
    contextWindowSize: 4096,
  };
