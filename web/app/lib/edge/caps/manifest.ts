// The edge model manifest wire contract + tolerant normalisation. Split out of
// ../capabilities.ts, which re-exports every name.


/** One acquirable on-device asset as published by GET /api/edge/manifest.
 * THIS TYPE IS THE CANONICAL WIRE CONTRACT — the server's EdgeModelEntry
 * (core/tenancy EdgeAgentsConfigController) matches it field-for-field:
 * `version` is a STRING, `tier` is a NUMBER (0 | 1 | 2), the memory floor is
 * spelled `minDeviceMemoryGB` (capital GB), and `url`/`sha256` are nullable.
 * Near-miss payloads from older/drifted servers go through
 * normalizeManifest() below — never consume a raw manifest body directly. */
export interface ManifestModel {
  id: string;
  /** Brand-first consumer-facing name — never a raw model
   * name; that lives in detailName. */
  displayName: string;
  /** Details-panel-only raw model identity + provenance (honest, secondary). */
  detailName?: string | null;
  sizeBytes: number;
  /** Minimum navigator.deviceMemory (GB) to auto-select this model. 0 = no floor. */
  minDeviceMemoryGB: number;
  /** Minimum WebGPU adapter maxBufferSize. 0 = no floor (tier-2 WASM models). */
  minMaxBufferSize: number;
  /** Which tier this asset serves: 0 = browser built-in (Prompt API),
   * 1 = WebLLM/WebGPU, 2 = WASM/other. */
  tier: 0 | 1 | 2;
  /** Weight download URL. null is a legitimate, permanent state: no hosted
   * weights (the engine resolves by id against its default CDN) or nothing
   * published yet. */
  url?: string | null;
  /** Integrity hash for url-hosted weights; null when unknown/not applicable. */
  sha256?: string | null;
  /** Latency-tier tag consumed by selectBestModel's complexity-aware
   * selection: "fast" = small/quick, the default pick for ordinary
   * conversational turns; "capable" = bigger/slower, picked only when the
   * turn looks complex (see estimateTurnComplexity below). Optional and
   * tolerant on purpose — older/undrifted manifest entries that predate this
   * field simply have it undefined, and selectBestModel falls back to its
   * pre-existing biggest-fitting-wins behavior whenever NO fitting model in
   * a manifest carries this tag (see that function's own comment). Shared
   * field name/values with tier1.ts's manifest entries — do not rename. */
  speedTier?: "fast" | "capable";
  /** D-9 (EDGE-CONTEXT-BUDGET-DESIGN.md): the model's context window in
   * tokens, sourced from the installed @mlc-ai/web-llm package's model
   * records server-side (never guessed) and admin-overridable via the stored
   * manifest. null/undefined = unknown — tier1.ts then falls back to the
   * installed web-llm record at engine init, else a conservative 4096.
   * A raised admin value is passed into the WebLLM engine load config
   * (context_window_size override) and re-gated by modelFits (bigger window
   * ⇒ bigger KV cache ⇒ bigger device-memory floor). */
  contextWindowSize?: number | null;
}

export interface ModelManifest {
  version: string;
  models: ManifestModel[];
}

// --- Tolerant manifest normalization ---------------------------------------

function coerceString(v: unknown): string | null {
  return typeof v === "string" && v ? v : null;
}

function coerceNumber(v: unknown): number | null {
  if (typeof v === "number" && Number.isFinite(v)) return v;
  if (typeof v === "string" && v.trim() !== "") {
    const n = Number(v);
    if (Number.isFinite(n)) return n;
  }
  return null;
}

/** "fast"/"capable" pass through; anything else (missing field, drifted
 * casing/value from an older server) → undefined, never guessed. */
function coerceSpeedTier(v: unknown): "fast" | "capable" | undefined {
  return v === "fast" || v === "capable" ? v : undefined;
}

/** D-9: a context window must be a positive whole token count; anything else
 * (absent, 0, negative, non-numeric drift) → null, never guessed — the
 * client-side mirror of the server's normalizeContextWindows. */
function coerceContextWindowSize(v: unknown): number | null {
  const n = coerceNumber(v);
  if (n === null) return null;
  const whole = Math.trunc(n);
  return whole > 0 ? whole : null;
}

/** "tier1" / "TIER_2" / "1" / 1 → 1|2; anything unrecognized → null. */
function coerceTier(v: unknown): 0 | 1 | 2 | null {
  if (v === 0 || v === 1 || v === 2) return v;
  if (typeof v === "number" && Number.isFinite(v)) {
    const n = Math.trunc(v);
    return n === 0 || n === 1 || n === 2 ? (n as 0 | 1 | 2) : null;
  }
  if (typeof v === "string") {
    const m = /^\s*(?:tier[\s_-]*)?([0-2])\s*$/i.exec(v);
    if (m) return Number(m[1]) as 0 | 1 | 2;
  }
  return null;
}

/**
 * Coerce a raw /api/edge/manifest payload into the canonical ModelManifest,
 * or null when it isn't manifest-shaped at all. Tolerated near-misses (schema
 * drift must DEGRADE, never silently empty the model list — the bug this
 * guards against was a server sending tier:"tier1" strings, which made every
 * `m.tier === 1` filter come up empty):
 *   - tier as a string ("tier1", "1") or float → number 0|1|2;
 *   - minDeviceMemoryGb / min_device_memory_gb casing → minDeviceMemoryGB;
 *   - numeric version → string;
 *   - models list under `models`, `assets` or `entries`;
 *   - snake_case fallbacks for the remaining fields; missing numerics → 0.
 * Entries without a usable id or tier are dropped (never guessed).
 */
export function normalizeManifest(raw: unknown): ModelManifest | null {
  if (!raw || typeof raw !== "object" || Array.isArray(raw)) return null;
  const obj = raw as Record<string, unknown>;
  const listRaw = Array.isArray(obj.models)
    ? obj.models
    : Array.isArray(obj.assets)
      ? obj.assets
      : Array.isArray(obj.entries)
        ? obj.entries
        : null;
  if (!listRaw) return null;
  const models: ManifestModel[] = [];
  for (const entry of listRaw) {
    if (!entry || typeof entry !== "object" || Array.isArray(entry)) continue;
    const e = entry as Record<string, unknown>;
    const id = coerceString(e.id) ?? coerceString(e.modelId) ?? coerceString(e.model_id);
    if (!id) continue;
    const tier = coerceTier(e.tier);
    if (tier === null) continue;
    models.push({
      id,
      displayName: coerceString(e.displayName) ?? coerceString(e.display_name) ?? id,
      detailName: coerceString(e.detailName) ?? coerceString(e.detail_name),
      sizeBytes: coerceNumber(e.sizeBytes ?? e.size_bytes) ?? 0,
      minDeviceMemoryGB:
        coerceNumber(e.minDeviceMemoryGB ?? (e as any).minDeviceMemoryGb ?? e.min_device_memory_gb) ?? 0,
      minMaxBufferSize: coerceNumber(e.minMaxBufferSize ?? e.min_max_buffer_size) ?? 0,
      tier,
      url: coerceString(e.url),
      sha256: coerceString(e.sha256),
      speedTier: coerceSpeedTier(e.speedTier ?? (e as any).speed_tier),
      contextWindowSize: coerceContextWindowSize(e.contextWindowSize ?? (e as any).context_window_size),
    });
  }
  const versionRaw = obj.version;
  const version =
    typeof versionRaw === "string" && versionRaw
      ? versionRaw
      : typeof versionRaw === "number" && Number.isFinite(versionRaw)
        ? String(versionRaw)
        : "unknown";
  return { version, models };
}
