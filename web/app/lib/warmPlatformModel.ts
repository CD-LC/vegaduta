"use client";

import { apiFetch } from "./api/core";
import { localModelAllowed } from "./personalInstall";

const STORAGE_KEY = "vegaduta.platformModel.warmedAt";
/** How long one warm-up is trusted before the browser asks again (core also throttles, per instance). */
const REWARM_AFTER_MS = 4 * 60 * 1000;

/**
 * Asks core to load the platform model (POST /api/platform-llm/warm) as soon as someone signs in or
 * opens a chat, so a hosted model that scaled to zero is awake before their first message instead
 * of costing that message a minute. Fire-and-forget and silent: an older core (404), an empty pool
 * or a dead server all mean "nothing to warm". Throttled per browser tab. Personal installs skip it:
 * their model is the local one, which useWarmLocalModel already loads.
 */
export function warmPlatformModel(): void {
  if (localModelAllowed()) return;
  let last = 0;
  try {
    last = Number(sessionStorage.getItem(STORAGE_KEY) ?? 0);
  } catch {
    // storage unavailable: just warm
  }
  if (Date.now() - last < REWARM_AFTER_MS) return;
  try {
    sessionStorage.setItem(STORAGE_KEY, String(Date.now()));
  } catch {
    // ignore
  }
  void apiFetch(`/api/platform-llm/warm`, { method: "POST" }).catch((): void => undefined);
}
