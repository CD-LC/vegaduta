"use client";

import { useEffect, useState } from "react";
import { researchApi } from "../../lib/api/research";
import { useSignedIn } from "../useSignedIn";

// Whether the Research page can start runs on this deployment (GET /api/research/status: the run
// endpoints are on). The sidebar shows the "Research" item only then, so a personal install started
// without web search (or any install with app.research.runs.enabled=false, the default) gets no dead
// link to a page that only says "not available". Same shape as useCodingTasksEnabled: one request per
// page lifetime, cached in a module-level promise because AppShell remounts on every navigation.

let cached: Promise<boolean> | null = null;

function loadEnabled(): Promise<boolean> {
  if (cached === null) {
    cached = Promise.resolve()
      .then(() => researchApi.getResearchStatus())
      .then((status) => !!status?.runsEnabled)
      .catch(() => false);
  }
  return cached;
}

/** True once the server reports research runs on; false while unknown, off or unreachable. */
export function useResearchEnabled(): boolean {
  const [enabled, setEnabled] = useState(false);
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
