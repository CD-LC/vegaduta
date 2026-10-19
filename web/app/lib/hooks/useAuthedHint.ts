"use client";

import { useEffect, useState } from "react";
import { hasAuthedHint } from "../authHint";

// The vd_authed localStorage/cookie pair AuthProvider maintains on every
// auth-state change (set on "ready", cleared on logout/unauthenticated/error).
// This is the documented public-chrome convention — help/workflow-recipes'
// PublicWorkflowNav uses the same read — and it is a UI HINT ONLY, never a
// security decision: the console's real gate stays AuthProvider + the API's
// bearer check. A stale "1" just shows "Open console" to someone whose
// session expired; the console re-checks for real on arrival.
//
// Starts false and only flips in an effect, so server render and first paint
// always show the signed-out chrome — no hydration mismatch, and a public
// page never flashes console copy at a crawler or a stranger.
export function useAuthedHint(): boolean {
  const [authed, setAuthed] = useState(false);
  useEffect(() => {
    try {
      setAuthed(hasAuthedHint());
    } catch {
      // storage blocked (private mode, policy) - keep the signed-out chrome
    }
  }, []);
  return authed;
}
