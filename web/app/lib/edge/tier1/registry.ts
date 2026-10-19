// Builds the built-in fallback model catalogue from the per-family data
// modules. The families are lazy-imported so a session that gets a server
// manifest never loads them.

import type { ManifestModel, ModelManifest } from "../capabilities";

/**
 * KEEP IN SYNC WITH THE SERVER SEED. This constant mirrors the seeded default
 * the core edge manifest endpoint (GET /api/edge/manifest) publishes, and is
 * used ONLY when that endpoint 404s (server wave not deployed yet) or the
 * network fails — feature-detected, per the Wave-2 brief. If the server seed
 * changes model ids/sizes, change this too, or fallback-mode devices will
 * offer stale models. Every id must be a valid @mlc-ai/web-llm@0.2.84
 * prebuiltAppConfig model_id (verified against the installed package).
 */
export const BUILTIN_FALLBACK_VERSION = "builtin-fallback-1";

/** Original catalogue order (it is significant to selection tie-breaks). */
export async function loadBuiltinFallbackManifest(): Promise<ModelManifest> {
  const [llama, qwen, phi, hermes, gemma] = await Promise.all([
    import("./models/llama"),
    import("./models/qwen"),
    import("./models/phi"),
    import("./models/hermes"),
    import("./models/gemma"),
  ]);
  const models: ManifestModel[] = [
    llama.LLAMA_3B_MODEL,
    qwen.QWEN_MODEL,
    llama.LLAMA_1B_MODEL,
    phi.PHI_MODEL,
    hermes.HERMES_MODEL,
    gemma.GEMMA_MODEL,
  ];
  return { version: BUILTIN_FALLBACK_VERSION, models };
}
