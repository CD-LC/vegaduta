"use client";

// Per-user "Disable apps" switch for MCP Apps. Browser-local on purpose (a viewing preference, not account
// state) and fully fail-soft: localStorage can be absent or throw (private window, blocked site data), in
// which case apps stay enabled for the session and the toggle still works in memory.

import { useCallback, useSyncExternalStore } from "react";

export const APPS_DISABLED_KEY = "vd.mcpApps.disabled";
const EVENT = "vd-mcp-apps-pref";
let memoryOverride: boolean | null = null; // used when storage is unavailable

export function readAppsDisabled(): boolean {
  try {
    return window.localStorage.getItem(APPS_DISABLED_KEY) === "1";
  } catch {
    return memoryOverride === true;
  }
}

export function writeAppsDisabled(disabled: boolean): void {
  memoryOverride = disabled;
  try {
    if (disabled) window.localStorage.setItem(APPS_DISABLED_KEY, "1");
    else window.localStorage.removeItem(APPS_DISABLED_KEY);
  } catch {
    /* storage unavailable: the in-memory value above still applies this session */
  }
  try {
    window.dispatchEvent(new Event(EVENT));
  } catch {
    /* no window (SSR/tests) */
  }
}

function subscribe(onChange: () => void): () => void {
  window.addEventListener(EVENT, onChange);
  window.addEventListener("storage", onChange);
  return () => {
    window.removeEventListener(EVENT, onChange);
    window.removeEventListener("storage", onChange);
  };
}

/** [disabled, setDisabled] - shared by every app card on the page and across tabs. */
export function useAppsDisabled(): [boolean, (disabled: boolean) => void] {
  const disabled = useSyncExternalStore(subscribe, readAppsDisabled, () => false);
  const set = useCallback((v: boolean) => writeAppsDisabled(v), []);
  return [disabled, set];
}
