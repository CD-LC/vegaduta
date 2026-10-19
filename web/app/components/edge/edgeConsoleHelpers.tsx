"use client";

import { formatBytes } from "../../lib/format";
import {
  
  
  
  
  
  
  
  
  
  
  
  
  
  type EdgeCapabilities,
  
  
  
  
} from "../../lib/edge/capabilities";
import {  type AssetStatus } from "../../lib/edge/downloadManager";

export type DeviceVerdict = "great" | "good" | "basic" | "none";

export function deviceVerdict(caps: EdgeCapabilities): DeviceVerdict {
  if (caps.webgpu.available && (caps.deviceMemoryGB == null || caps.deviceMemoryGB >= 8)) return "great";
  if (caps.webgpu.available) return "good";
  const a = caps.promptApi.availability;
  if (a === "available" || a === "downloadable" || a === "downloading") return "basic";
  return "none";
}

export function percentOf(asset: AssetStatus): number {
  if (!asset.totalBytes) return 0;
  return Math.min(100, Math.round((asset.receivedBytes / asset.totalBytes) * 100));
}

/** Chrome's built-in model reports a 0..1 fraction with totalBytes 1 — byte
 * counts only render when totalBytes is a real size (same rule as the chip). */
export function hasRealByteCounts(asset: AssetStatus): boolean {
  return asset.totalBytes > 1024;
}

export function formatLastUsed(epochMs: number | null, neverLabel: string): string {
  if (!epochMs) return neverLabel;
  try {
    return new Date(epochMs).toLocaleDateString();
  } catch {
    return neverLabel;
  }
}

/** One in-flight download's progress row: bar + pct + MB counts. */
export function ProgressRow({ asset }: { asset: AssetStatus }) {
  const pct = percentOf(asset);
  return (
    <div className="mt-2">
      <div className="h-1.5 w-full overflow-hidden rounded-full bg-secondary">
        <div className="h-full rounded-full bg-primary transition-[width] duration-300" style={{ width: `${pct}%` }} />
      </div>
      <div className="mt-1 flex items-center justify-between text-[11px] text-muted-foreground">
        <span>{pct}%</span>
        {hasRealByteCounts(asset) && (
          <span>
            {formatBytes(asset.receivedBytes)} / {formatBytes(asset.totalBytes)}
          </span>
        )}
      </div>
    </div>
  );
}

// ---------------------------------------------------------------------------
// The console
// ---------------------------------------------------------------------------
