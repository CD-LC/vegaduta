// Pure data: built-in fallback manifest entries for the llama family (extracted
// verbatim from tier1.ts; no logic here). Registered in ../registry.ts.
import type { ManifestModel } from "../../capabilities";

export const LLAMA_3B_MODEL: ManifestModel =   {
    id: "Llama-3.2-3B-Instruct-q4f16_1-MLC",
    // Brand-first display name; the raw model identity
    // lives ONLY in detailName (mirrors the server seed's entries).
    displayName: "On-device model — larger",
    detailName:
      "Llama 3.2 3B Instruct (q4f16), about 2.3 GB. Downloaded from the public MLC/WebLLM model CDN.",
    sizeBytes: 2_263_690_000,
    minDeviceMemoryGB: 8,
    minMaxBufferSize: 1_073_741_824,
    tier: 1,
    url: null,
    sha256: null,
    // speedTier tagging (2026-08-06 latency audit fix #3 — contract shared
    // with capabilities.ts's ManifestModel/selectBestModel; do not rename
    // the field or its two values). 2nd-largest of this 5-model fallback
    // set, alongside Phi-3.5-mini as the "escalate for a complex turn" pair.
    speedTier: "capable",
    // D-9: overrides.context_window_size read verbatim from the installed
    // @mlc-ai/web-llm@0.2.84 package's prebuilt model record for this exact
    // id (2026-08-07) — same never-guess rule as sizeBytes. Ditto for every
    // contextWindowSize below.
    contextWindowSize: 4096,
  };

export const LLAMA_1B_MODEL: ManifestModel =   {
    id: "Llama-3.2-1B-Instruct-q4f16_1-MLC",
    displayName: "On-device model — compact",
    detailName:
      "Llama 3.2 1B Instruct (q4f16), about 0.9 GB. Downloaded from the public MLC/WebLLM model CDN.",
    sizeBytes: 879_040_000,
    minDeviceMemoryGB: 4,
    // 2026-08-10 audit finding #6 (tier1.ts half) — same derivation as the
    // Qwen2.5-1.5B entry's comment above (conservative sibling ratio ≈0.292
    // applied to this model's own sizeBytes, rounded up to the next 256 MiB
    // step): 879_040_000 × 0.292 ≈ 256.7 MB → 268_435_456 (256 MiB).
    minMaxBufferSize: 268_435_456,
    tier: 1,
    url: null,
    sha256: null,
    // Smallest of the set — the default pick for an ordinary/"simple" turn.
    speedTier: "fast",
    contextWindowSize: 4096,
  };
