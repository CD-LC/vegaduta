"use client";

import { formatBytes as formatSharedBytes } from "../../../lib/format";
import type { ReactNode } from "react";
import { Eye, Mic, Volume2 } from "lucide-react";
import { type PromptApiAvailability } from "../../../lib/edge/capabilities";
import { type AssetStatus } from "../../../lib/edge/downloadManager";
import { EDGE_BRANDING } from "../../../lib/edge/branding";
import { ensureSttModel, isSttModelCached, STT_ASSET_ID } from "../../../lib/edge/audio/whisper";
import { ensureTtsModel, isTtsModelCached, TTS_ASSET_ID } from "../../../lib/edge/audio/kokoro";
import { ensureVisionModel, isVisionModelCached, VISION_ASSET_ID } from "../../../lib/edge/vision";

export type Tier0Status = "checking" | PromptApiAvailability;

export type UtilityKind = "stt" | "tts" | "vision";

/** One row per on-device audio/vision utility — each module's own public
 * contract (whisper.ts/kokoro.ts/vision.ts), never duplicated here. */
export const UTILITY_DEFS: Array<{
  kind: UtilityKind;
  assetId: string;
  displayName: string;
  icon: ReactNode;
  isCached: () => boolean;
  ensure: (onProgress?: (p: { loaded: number; total: number; status: string }) => void) => Promise<void>;
}> = [
  {
    kind: "stt",
    assetId: STT_ASSET_ID,
    displayName: EDGE_BRANDING.sttModelDisplayName,
    icon: <Mic size={13} />,
    isCached: isSttModelCached,
    ensure: ensureSttModel,
  },
  {
    kind: "tts",
    assetId: TTS_ASSET_ID,
    displayName: EDGE_BRANDING.ttsModelDisplayName,
    icon: <Volume2 size={13} />,
    isCached: isTtsModelCached,
    ensure: ensureTtsModel,
  },
  {
    kind: "vision",
    assetId: VISION_ASSET_ID,
    displayName: EDGE_BRANDING.visionModelDisplayName,
    icon: <Eye size={13} />,
    isCached: isVisionModelCached,
    ensure: ensureVisionModel,
  },
];

export const formatBytes = (bytes: number): string => formatSharedBytes(bytes, { base: 1000, minUnit: "KB" });

export function percentOf(asset: AssetStatus): number {
  if (!asset.totalBytes) return 0;
  return Math.min(100, Math.round((asset.receivedBytes / asset.totalBytes) * 100));
}
