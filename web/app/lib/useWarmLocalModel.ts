"use client";

import { useEffect } from "react";
import { localModelsApi } from "./api/localModels";
import { localModelAllowed } from "./personalInstall";
import { warmPlatformModel } from "./warmPlatformModel";

const STORAGE_KEY = "vegaduta.localModel.warmedAt";
/** How long one warm-up is trusted before a surface asks again (core keeps the model for app.local-models.keep-alive). */
const REWARM_AFTER_MS = 5 * 60 * 1000;

/**
 * Asks core to load the local model (POST /api/local-models/warm) when a chat surface opens, so a
 * laptop whose Ollama unloaded the model during a pause has it back before the user finishes
 * typing. Fire-and-forget and silent: a 403 (not an admin), a 404 (no local model on this install)
 * or a dead server all mean "nothing to warm", never an error on screen. Throttled per browser tab
 * so moving between surfaces does not re-issue it.
 */
export function useWarmLocalModel(enabled = true): void {
  useEffect(() => {
    if (!enabled) return;
    if (!localModelAllowed()) {
      // Hosted console: the platform model is the one a chat would wait on.
      warmPlatformModel();
      return;
    }
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
    void localModelsApi.warmLocalModel().catch((): void => undefined);
  }, [enabled]);
}
