// The Edge console's "Installed" rows (section 3), derived from what this
// browser holds: the built-in model, cached text models, the optional
// vision / speech models and any downloaded motion engines. Pure apart from
// the localStorage-backed install checks; EdgeConsole supplies the delete flow.

import type { InstalledRow } from "./EdgeInstalledList";
import type { ManifestModel } from "../../lib/edge/capabilities";
import { downloadManager } from "../../lib/edge/downloadManager";
import { isTier1ModelCached } from "../../lib/edge/tier1";
import { TIER0_ASSET_ID } from "../../lib/edge/tier0";
import { EDGE_BRANDING } from "../../lib/edge/branding";
import {
  deleteSttModel,
  deleteTier1Model,
  deleteTtsModel,
  deleteVisionModel,
  STT_ASSET_ID,
  STT_MODEL_SIZE_BYTES,
  TTS_ASSET_ID,
  TTS_MODEL_SIZE_BYTES,
  VISION_ASSET_ID,
  VISION_MODEL_SIZE_BYTES,
  type EdgeMode,
} from "./edgeConsoleBridge";

export function buildInstalledRows(input: {
  tier0Installed: boolean;
  visionInstalled: boolean;
  sttInstalled: boolean;
  ttsInstalled: boolean;
  textModels: ManifestModel[];
  motionEngines: Array<{ id: string; displayName: string }>;
  motionBytesById: Record<string, number>;
  override: string | null | undefined;
  mode: EdgeMode;
  autoPickId: string | null;
  confirmAndDelete: (id: string, name: string, run: () => Promise<void>) => void;
}): InstalledRow[] {
  const { textModels, motionEngines, motionBytesById, override, mode, autoPickId, confirmAndDelete } = input;
  const installedRows: InstalledRow[] = [];
  if (input.tier0Installed) {
    installedRows.push({
      id: TIER0_ASSET_ID,
      name: EDGE_BRANDING.tier0ModelDisplayName,
      sizeBytes: null,
      deletable: false,
      badge: "browser",
    });
  }
  for (const model of textModels) {
    if (!isTier1ModelCached(model.id)) continue;
    installedRows.push({
      id: model.id,
      name: model.displayName,
      sizeBytes: model.sizeBytes,
      deletable: true,
      onDelete: () => void confirmAndDelete(model.id, model.displayName, () => deleteTier1Model(model.id)),
      badge: override === model.id ? "default" : mode === "edge-first" && autoPickId === model.id ? "auto" : null,
    });
  }
  if (input.visionInstalled) {
    installedRows.push({
      id: VISION_ASSET_ID,
      name: EDGE_BRANDING.visionModelDisplayName,
      sizeBytes: VISION_MODEL_SIZE_BYTES,
      deletable: true,
      onDelete: () => void confirmAndDelete(VISION_ASSET_ID, EDGE_BRANDING.visionModelDisplayName, deleteVisionModel),
      badge: null,
    });
  }
  if (input.sttInstalled) {
    installedRows.push({
      id: STT_ASSET_ID,
      name: EDGE_BRANDING.sttModelDisplayName,
      sizeBytes: STT_MODEL_SIZE_BYTES,
      deletable: true,
      onDelete: () => void confirmAndDelete(STT_ASSET_ID, EDGE_BRANDING.sttModelDisplayName, deleteSttModel),
      badge: null,
    });
  }
  if (input.ttsInstalled) {
    installedRows.push({
      id: TTS_ASSET_ID,
      name: EDGE_BRANDING.ttsModelDisplayName,
      sizeBytes: TTS_MODEL_SIZE_BYTES,
      deletable: true,
      onDelete: () => void confirmAndDelete(TTS_ASSET_ID, EDGE_BRANDING.ttsModelDisplayName, deleteTtsModel),
      badge: null,
    });
  }
  for (const engine of motionEngines) {
    const bytes = motionBytesById[engine.id] || 0;
    if (bytes <= 0) continue;
    installedRows.push({
      id: engine.id,
      name: engine.displayName,
      sizeBytes: bytes,
      deletable: true,
      onDelete: () =>
        void confirmAndDelete(engine.id, engine.displayName, () => downloadManager.deleteAsset(engine.id)),
      badge: null,
    });
  }
  return installedRows;
}
