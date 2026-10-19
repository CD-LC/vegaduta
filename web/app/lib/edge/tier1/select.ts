// Selection / fit logic: model-id serveability, manifest sanitising, size
// formatting and the "is there a smaller fitting model" check (extracted
// verbatim from tier1.ts; pure).

import { modelFits, type EdgeCapabilities, type ManifestModel, type ModelManifest } from "../capabilities";
import { KNOWN_WEBLLM_MODEL_IDS } from "./models/webllmIds";

/** Can the installed WebLLM engine serve this exact model id? */
export function isWebLlmServableModelId(id: string): boolean {
  return KNOWN_WEBLLM_MODEL_IDS.has(id);
}

/** Exact match passes through; the one known drift shape (an id published
 * without the "-MLC" suffix) is repaired; anything else → null (unserveable). */
export function repairWebLlmModelId(id: string): string | null {
  if (KNOWN_WEBLLM_MODEL_IDS.has(id)) return id;
  if (!id.endsWith("-MLC") && KNOWN_WEBLLM_MODEL_IDS.has(`${id}-MLC`)) return `${id}-MLC`;
  return null;
}

/** Drop/repair tier-1 entries the installed engine can't serve (other tiers
 * pass through untouched — their ids aren't WebLLM ids). Applied to every
 * manifest this module consumes, server-fetched or fallback. */
export function sanitizeTier1Manifest(manifest: ModelManifest): ModelManifest {
  const models: ManifestModel[] = [];
  for (const model of manifest.models) {
    if (model.tier !== 1) {
      models.push(model);
      continue;
    }
    const repaired = repairWebLlmModelId(model.id);
    if (repaired === null) continue; // unserveable — never offer it
    models.push(repaired === model.id ? model : { ...model, id: repaired });
  }
  return { ...manifest, models };
}

export function formatSize(bytes: number): string {
  if (bytes >= 1_000_000_000) return `${(bytes / 1_000_000_000).toFixed(1)} GB`;
  if (bytes >= 1_000_000) return `${Math.round(bytes / 1_000_000)} MB`;
  return `${Math.max(1, Math.round(bytes / 1_000))} KB`;
}

export function smallerFittingModelExists(manifest: ModelManifest, caps: EdgeCapabilities, than: ManifestModel): boolean {
  try {
    return manifest.models.some(
      (m) => m.tier === 1 && m.id !== than.id && m.sizeBytes < than.sizeBytes && modelFits(m, caps)
    );
  } catch {
    return false;
  }
}
