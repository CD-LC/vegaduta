import type { EffectiveSetting, EffectiveSettings } from "../../lib/api/settings";

// The share of live turns the background quality judge scores. The server holds it as a fraction 0..1
// (core JudgeSampleRate, platform setting platform.quality.judge.sample-rate, 5% as shipped) and the settings
// catalog publishes it under the key below. This reads it for display only; changing it is a settings-catalog
// action, so the quality panel never offers a control the backend does not grant.

export const JUDGE_SAMPLE_RATE_KEY = "quality.judge.sampleRate";

export interface JudgeSampling {
  /** 0..100, one decimal at most. */
  percent: number;
  source: EffectiveSetting["source"];
  locked: boolean;
}

/** null when the settings API does not publish the rate (older core, key not in the catalog, unreadable value). */
export function judgeSamplingFrom(effective: EffectiveSettings | null | undefined): JudgeSampling | null {
  const entry = effective?.values?.[JUDGE_SAMPLE_RATE_KEY];
  if (!entry) return null;
  const n = typeof entry.value === "number" ? entry.value : typeof entry.value === "string" ? Number(entry.value) : NaN;
  if (!Number.isFinite(n) || n < 0) return null;
  // The wire value is a fraction; anything above 1 can only be a percentage already.
  const percent = n <= 1 ? n * 100 : n;
  return { percent: Math.min(100, Math.round(percent * 10) / 10), source: entry.source, locked: entry.locked };
}
