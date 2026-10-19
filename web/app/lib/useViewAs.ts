"use client";

import { useSyncExternalStore } from "react";
import { getViewAsState, subscribeViewAs, type ViewAsState } from "./viewAs";

/**
 * The active read-only view-as session in this tab, or null. Server render and
 * hydration see null; the client re-renders with the stored session right
 * after. Use `readOnly` to disable obvious primary write buttons - core refuses
 * the write regardless, this only saves a round trip and a toast.
 */
export function useViewAs(): { active: boolean; readOnly: boolean; state: ViewAsState | null } {
  const state = useSyncExternalStore(subscribeViewAs, getViewAsState, (): ViewAsState | null => null);
  return { active: state != null, readOnly: state != null, state };
}
