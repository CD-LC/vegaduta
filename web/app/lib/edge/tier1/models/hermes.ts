// Pure data: built-in fallback manifest entries for the hermes family (extracted
// verbatim from tier1.ts; no logic here). Registered in ../registry.ts.
import type { ManifestModel } from "../../capabilities";

  // Tool-calling entry (2026-09-09, "WebLLM as the main runtime" push —
  // gap closed: agents/toolBridge.ts already runs a manual JSON-envelope
  // tool protocol (toolProtocolInstructions/parseToolCall below, with a
  // schema-constrained retry — see streamCompletion's TOOL_CALL_ENVELOPE_SCHEMA
  // pass), but every model in this manifest before this entry is a
  // general-purpose instruct model, none fine-tuned for structured tool
  // output. `functionCallingModelIds` — a constant in the installed
  // @mlc-ai/web-llm@0.2.84 package itself (lib/index.js), the library's own
  // list of models it considers reliable for `ChatCompletionRequest.tools`
  // — names exactly five ids; this is the smallest of them. Being the
  // biggest `speedTier: "capable"` entry now (vram 4033 MB > Phi-3.5-mini's
  // 3672 MB), selectBestModel's existing "complex turn → biggest capable
  // wins" rule already prefers it with NO other code change — and
  // estimateTurnComplexity already treats `toolsActive` as complex
  // (capabilities.ts), so a tool-using turn reaches for this model first,
  // automatically, the moment it's cached. Documented tradeoff: the same
  // "biggest wins" rule also prefers it for a long NON-tool turn over the
  // smaller Phi-3.5-mini — accepted as consistent with that rule's existing
  // intent (bigger-is-more-capable), not a new behavior class.
  // sizeBytes = vram_required_MB × 1e6 (4033.28 → 4_033_280_000), the exact
  // convention this manifest already uses — verified by cross-checking
  // Phi-3.5-mini's vram_required_MB (3672.07) against its sizeBytes above,
  // an exact match. minDeviceMemoryGB/minMaxBufferSize kept at the SAME
  // conservative floor as the other two "capable" entries (not scaled up
  // per its slightly higher vram) — consistent with how this manifest
  // already treats "capable" as one coarse tier, not a per-model floor.
  // Known gap, flagged not silently worked around: this model's package
  // record also sets `required_features: ["shader-f16"]` — a WebGPU
  // extension this manifest has no field to gate on yet (only
  // minMaxBufferSize/minDeviceMemoryGB exist). Effectively universal on
  // capable-tier hardware in 2026, but not a HARD guarantee the way the
  // existing floors are; a device lacking it fails the download/load with a
  // Tier1Error the router already falls through on, so this degrades safely
  // rather than silently, but a real `requiredFeatures` gate is the correct
  // fix if this ever proves to matter in practice.
export const HERMES_MODEL: ManifestModel =   {
    id: "Hermes-2-Pro-Mistral-7B-q4f16_1-MLC",
    displayName: "On-device model — tool-savvy",
    detailName:
      "Hermes 2 Pro (Mistral 7B, q4f16), about 4.0 GB. Fine-tuned for reliable tool/function calling. Downloaded from the public MLC/WebLLM model CDN.",
    sizeBytes: 4_033_280_000,
    minDeviceMemoryGB: 8,
    minMaxBufferSize: 1_073_741_824,
    tier: 1,
    url: null,
    sha256: null,
    // Largest of the set — only ever picked when a turn looks complex
    // (which every tool-using turn already does).
    speedTier: "capable",
    contextWindowSize: 4096,
  };
