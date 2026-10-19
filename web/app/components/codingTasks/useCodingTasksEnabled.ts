"use client";

import { useEffect, useState } from "react";
import { api } from "../../lib/api";
import { useSignedIn } from "../useSignedIn";

// Whether Coding tasks is usable on this deployment (GET /api/coding-tasks/capabilities: the flag is on). The
// sidebar uses it to show the "Coding tasks" item only when the page behind it works, so a deployment with the flag
// off (the default) gets no dead link. AppShell remounts on every navigation, so the answer is cached for the page's
// lifetime in a module-level promise: one request, however many shells mount.

let cached: Promise<boolean> | null = null;

function loadEnabled(): Promise<boolean> {
  if (cached === null) {
    // Promise.resolve().then: a throw from a partially mocked api becomes a rejection, not a render crash.
    cached = Promise.resolve()
      .then(() => api.getCodingTasksConfig())
      .then((c) => !!c?.enabled)
      .catch(() => {
        // Failed or a core that predates the feature: hide the item (and do not re-ask on every navigation).
        return false;
      });
  }
  return cached;
}

/** True once the server reports the Coding tasks flag on; false while unknown, off or unreachable. */
export function useCodingTasksEnabled(): boolean {
  const [enabled, setEnabled] = useState(false);
  // Only once a session exists. AppShell also renders on /features and /help for a logged-out visitor and while
  // Keycloak is still resolving; asking then sent the request with no token, got a 401, and cached "off" for the
  // page's lifetime - so the nav item vanished for a signed-in user on those pages.
  const signedIn = useSignedIn();
  useEffect(() => {
    if (!signedIn) return undefined;
    let live = true;
    void loadEnabled().then((v) => {
      if (live) setEnabled(v);
    });
    return () => {
      live = false;
    };
  }, [signedIn]);
  return enabled;
}
