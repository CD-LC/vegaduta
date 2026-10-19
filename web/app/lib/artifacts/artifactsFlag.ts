// Artifacts pane - web-side feature flag. DEFAULT OFF: with the flag off, the
// chat renders byte-for-byte as before (no parsing, no pane, no chips).
//
// Two switches, either turns the pane on:
//  - NEXT_PUBLIC_ARTIFACTS=1   runtime or build-time via getPublicEnv (like NEXT_PUBLIC_MCP_APPS);
//  - localStorage "vegaduta.artifacts" = "1"   a per-viewer opt-in, for trying
//    it on a build where the flag is off. localStorage can throw or be empty
//    (private window, blocked site data); every access is guarded.
//
// The server side is separate: the model only learns the convention when
// app.chat.directives.artifacts.enabled is on for the agent (core
// ArtifactsDirective). Turning this flag on without that simply shows nothing
// new, because the model never emits `artifact:` fences.

import { getPublicEnv } from "../env";

export const ARTIFACTS_BUILD_FLAG = getPublicEnv("NEXT_PUBLIC_ARTIFACTS") === "1";
export const ARTIFACTS_STORAGE_KEY = "vegaduta.artifacts";

export function readArtifactsOverride(): boolean {
  try {
    return typeof window !== "undefined" && window.localStorage.getItem(ARTIFACTS_STORAGE_KEY) === "1";
  } catch {
    return false;
  }
}

export function writeArtifactsOverride(on: boolean): void {
  try {
    if (typeof window === "undefined") return;
    if (on) window.localStorage.setItem(ARTIFACTS_STORAGE_KEY, "1");
    else window.localStorage.removeItem(ARTIFACTS_STORAGE_KEY);
  } catch {
    /* storage unavailable: the override simply does not persist */
  }
}

export function artifactsEnabledNow(): boolean {
  return ARTIFACTS_BUILD_FLAG || readArtifactsOverride();
}
