// Tier 1: WebLLM (WebGPU) runtime wrapper + tool-calling loop.
//
// Same contract as tier0.ts (read that file first): streaming with abort,
// typed errors (a caller NEVER sees a raw throw — everything is a Tier1Error
// the tier router maps to its silent fall-through), session reuse per
// conversation key.
//
// Bundle rule (binding): @mlc-ai/web-llm is loaded ONLY via dynamic import()
// inside the tier-1 path — the static imports below from that package are
// type-only and fully erased at compile time, so the main bundle never
// carries the library. Nothing in this file runs at module load.
//
// Download honesty (E-0b): WebLLM pulls its weights from the upstream MLC
// model CDN (mlc.ai / Hugging Face mirrors) — tier1DownloadDetails() states
// that plus the exact size BEFORE any download, and startTier1Download() is
// only ever called from an explicit user gesture in the chip/banner/picker
// flow. Nothing in the routing path ever auto-downloads: tier1Ready() only
// answers true for weights that are ALREADY cached and verified on this
// device (E-0b rule 6 — never resolve a tier on the promise of a download).
//
// Worker note (deviation, tracked): E-0b rule 1 wants inference in a Web
// Worker (WebLLM's WebWorkerMLCEngine). This wave runs the engine on the
// main thread via CreateMLCEngine — the worker entry file is outside this
// wave's owned file set. The heavy lifting (GPU dispatch) is already async
// on the device timeline; the worker migration is a follow-up that changes
// only acquireEngine() below.

// Facade. The implementation lives in ./tier1/*:
//   errors, log, online          typed errors, console logging, online probe
//   manifest, registry, select   model catalogue (data in tier1/models/*)
//   weightStore, readiness       where weights live; which model can run now
//   installStamps                install stamps + load-failure memory
//   engineState, engineAcquire   engine singleton lifecycle; SW / in-page load
//   download, installed          opt-in download; list / delete / marketplace
//   generation, turn, session    streaming, tool loop, session + prewarm
// Every symbol that used to be defined here is still importable from "./tier1"
// (tests and callers mock and import this path), and nothing in this file runs
// at module load.

export { isWebLlmServableModelId } from "./tier1/select";
export {
  TIER1_BUDGET_SAFETY_MARGIN,
  TIER1_OUTPUT_RESERVE_TOKENS,
  WEBLLM_FALLBACK_CONTEXT_WINDOW,
  assembleTier1Prompt,
  assembleTier1PromptWithToolRelief,
  computeTier1PromptBudget,
  estimateTier1Tokens,
  type Tier1PromptAssembly,
} from "./tier1/promptBudget";
export { parseToolCall, type ParsedToolCall } from "./tier1/toolProtocol";
export { Tier1Error, type Tier1ErrorCode } from "./tier1/errors";
export { fetchTier1Manifest } from "./tier1/manifest";
export {
  getTier1ModelWeightStore,
  getTier1SharedWeightsPreference,
  getTier1WeightBackendState,
  isTier1ModelCached,
  previewTier1SharedWeightsChange,
  setTier1SharedWeightsPreference,
  type Tier1CacheBackend,
  type Tier1SharedWeightsChangeImpact,
  type Tier1WeightBackendReason,
  type Tier1WeightBackendState,
} from "./tier1/weightStore";
export { getTier1RunModel, tier1Ready } from "./tier1/readiness";
export { TIER1_RUNTIME_STAMP, getTier1CacheHealth } from "./tier1/installStamps";
export { isTier1EngineWarm } from "./tier1/engineState";
export { WEBLLM_VENDOR_URL, resolveTier1ContextWindow } from "./tier1/engineAcquire";
export {
  preflightTier1Download,
  redownloadTier1Model,
  startTier1Download,
  tier1DownloadDetails,
  type Tier1DownloadResult,
} from "./tier1/download";
export {
  deleteTier1Model,
  getTier1ModelInstallState,
  listInstalledTier1Models,
  listTier1Marketplace,
  type InstalledTier1Model,
  type Tier1InstallState,
  type Tier1MarketplaceEntry,
} from "./tier1/installed";
export {
  TIER1_STRUCTURED_TIMEOUT_MS,
  tier1StructuredAvailable,
  tier1StructuredJson,
  type Tier1StructuredOptions,
} from "./tier1/generation";
export type { CreateTier1SessionOptions, Tier1Session, Tier1ToolCallEvent } from "./tier1/sessionTypes";
export { withReasoningNudge } from "./tier1/turn";
export { createTier1Session, prewarmTier1Engine } from "./tier1/session";
