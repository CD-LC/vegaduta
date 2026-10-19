"use client";

import { useEffect, useState } from "react";
import { api } from "./api";

// Whether workflow import/export is switched on for this deployment
// (app.workflow.portability.enabled, default off). Probed once per page load and shared by every caller; false until
// the answer is known, so with the feature off the workflows page renders exactly as it did before the feature.
let probe: Promise<boolean> | null = null;

export function usePortabilityEnabled(): boolean {
  const [enabled, setEnabled] = useState(false);
  useEffect(() => {
    let live = true;
    if (probe === null) probe = api.portabilityEnabled();
    void probe.then((value: boolean) => {
      if (live) setEnabled(value);
    });
    return () => {
      live = false;
    };
  }, []);
  return enabled;
}
