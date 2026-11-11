// Edge capability types and the one-per-session detection pass (split out of
// ../capabilities.ts, which re-exports it). Feature probes only: WebGPU adapter
// limits, the Chrome Prompt API, device memory and connection hints.

/** sessionStorage — one detection result per browser session. */
const CAPS_SESSION_KEY = "edge.capabilities.v1";

// ---------------------------------------------------------------------------
// Capability types
// ---------------------------------------------------------------------------

/** Current Chrome Prompt API availability states (LanguageModel.availability()).
 * "unknown" means the API surface exists but answered something unexpected. */
export type PromptApiAvailability = "unavailable" | "downloadable" | "downloading" | "available" | "unknown";

export type EdgeTier = 0 | 1 | 2 | 3;

export interface EdgeCapabilities {
  version: 1;
  detectedAt: number;
  webgpu: {
    available: boolean;
    /** adapter.limits.maxBufferSize — the number the model manifest's
     * minMaxBufferSize requirement is compared against. */
    maxBufferSize: number | null;
    maxStorageBufferBindingSize: number | null;
    /** adapter.features.has("shader-f16") — fp16 kernels (SD-Turbo's weights
     * are fp16-only; MusicGen falls back to fp32) only run where this is true.
     * absent when unprobed (a caps payload written before the field existed) —
     * treated as no. */
    shaderF16?: boolean | null;
  };
  promptApi: {
    /** "languageModel" = current Chrome global; "windowAi" = the older
     * window.ai.languageModel shape (handled defensively); "none" = absent. */
    shape: "languageModel" | "windowAi" | "none";
    availability: PromptApiAvailability;
  };
  /** navigator.deviceMemory (Chrome-only; null on Firefox/Safari). */
  deviceMemoryGB: number | null;
  hardwareConcurrency: number | null;
  connection: {
    /** Data-saver on → the download manager's opt-in copy must warn (E-0b rule 2). */
    saveData: boolean;
    effectiveType: string | null;
  };
}

export function normalizeAvailability(v: unknown): PromptApiAvailability {
  return v === "available" || v === "downloadable" || v === "downloading" || v === "unavailable" ? v : "unknown";
}

/** Older window.ai.languageModel.capabilities() → { available: "readily" |
 * "after-download" | "no" }. Mapped onto the current vocabulary. */
function normalizeLegacyAvailability(v: unknown): PromptApiAvailability {
  if (v === "readily") return "available";
  if (v === "after-download") return "downloadable";
  if (v === "no") return "unavailable";
  return "unknown";
}

// ---------------------------------------------------------------------------
// Detection — one pass per session
// ---------------------------------------------------------------------------

function emptyCapabilities(): EdgeCapabilities {
  return {
    version: 1,
    detectedAt: 0,
    webgpu: { available: false, maxBufferSize: null, maxStorageBufferBindingSize: null, shaderF16: null },
    promptApi: { shape: "none", availability: "unavailable" },
    deviceMemoryGB: null,
    hardwareConcurrency: null,
    connection: { saveData: false, effectiveType: null },
  };
}

function readSessionCaps(): EdgeCapabilities | null {
  try {
    if (typeof sessionStorage === "undefined") return null;
    const raw = sessionStorage.getItem(CAPS_SESSION_KEY);
    if (!raw) return null;
    const parsed = JSON.parse(raw) as EdgeCapabilities;
    return parsed && parsed.version === 1 ? parsed : null;
  } catch {
    return null;
  }
}

function writeSessionCaps(caps: EdgeCapabilities): void {
  try {
    if (typeof sessionStorage === "undefined") return;
    sessionStorage.setItem(CAPS_SESSION_KEY, JSON.stringify(caps));
  } catch {
    // Best-effort — a full/blocked sessionStorage just means we re-probe next call.
  }
}

let inFlightDetection: Promise<EdgeCapabilities> | null = null;

/**
 * The one detection pass. Cheap to call from anywhere — the first call in a
 * session does the real probing (requestAdapter + availability()), every later
 * call answers from sessionStorage (or joins the in-flight promise).
 * Never rejects: an environment with nothing available resolves to an
 * all-false result, which resolveTier maps to tier 3 (hosted).
 */
export function detectCapabilities(force = false): Promise<EdgeCapabilities> {
  if (!force) {
    const cached = readSessionCaps();
    if (cached) return Promise.resolve(cached);
    if (inFlightDetection !== null) return inFlightDetection;
  }
  inFlightDetection = runDetection().finally(() => {
    inFlightDetection = null;
  });
  return inFlightDetection;
}

async function runDetection(): Promise<EdgeCapabilities> {
  const caps = emptyCapabilities();
  if (typeof navigator === "undefined") return caps; // SSR — never probed server-side
  const nav = navigator as any;

  // WebGPU probe — adapter limits are what the model manifest's per-model
  // minimum-capability requirements compare against (auto mode).
  try {
    if (nav.gpu?.requestAdapter) {
      const adapter = await nav.gpu.requestAdapter();
      if (adapter) {
        const limits = adapter.limits || {};
        caps.webgpu = {
          available: true,
          maxBufferSize: Number(limits.maxBufferSize) || null,
          maxStorageBufferBindingSize: Number(limits.maxStorageBufferBindingSize) || null,
          shaderF16: adapter.features?.has?.("shader-f16") === true ? true : false,
        };
      }
    }
  } catch {
    // No WebGPU — tiers needing it simply don't resolve.
  }

  // Chrome Prompt API — current global first, older window.ai shape second.
  try {
    const LanguageModel = (globalThis as any).LanguageModel;
    if (LanguageModel && typeof LanguageModel.availability === "function") {
      caps.promptApi = {
        shape: "languageModel",
        availability: normalizeAvailability(await LanguageModel.availability()),
      };
    } else {
      const legacy = (globalThis as any).ai?.languageModel;
      if (legacy && typeof legacy.capabilities === "function") {
        const c = await legacy.capabilities();
        caps.promptApi = { shape: "windowAi", availability: normalizeLegacyAvailability(c?.available) };
      }
    }
  } catch {
    // API surface present but broken — treat as absent.
  }

  caps.deviceMemoryGB = typeof nav.deviceMemory === "number" ? nav.deviceMemory : null;
  caps.hardwareConcurrency = typeof nav.hardwareConcurrency === "number" ? nav.hardwareConcurrency : null;
  caps.connection = {
    saveData: !!nav.connection?.saveData,
    effectiveType: typeof nav.connection?.effectiveType === "string" ? nav.connection.effectiveType : null,
  };
  caps.detectedAt = Date.now();
  writeSessionCaps(caps);
  return caps;
}
