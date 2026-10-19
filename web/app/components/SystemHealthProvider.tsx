"use client";

import { createContext, useCallback, useContext, useEffect, useRef, useState } from "react";
import { sharedSystemHealth } from "../lib/sharedPoll";
import { visibleInterval } from "../lib/visibleInterval";

// Shared, polled system-health signal for the authenticated console. Mounted
// once by AppShell (which wraps every console page), so any page rendered as
// its child can read it via useSystemHealth() without its own probe.
//
// It answers two product questions:
//   1. Is CORE reachable at all? If not, the app shows a "We'll be back"
//      maintenance takeover instead of a wall of broken pages (see AppShell +
//      MaintenanceScreen). `coreReachable` is optimistic — it only flips false
//      after FAIL_THRESHOLD consecutive failed probes, so a single blip or a
//      slow first byte never flashes the maintenance screen.
//   2. Is the SIDECAR (Python embeddings/extraction service) up? Knowledge
//      upload + semantic search depend on it, so when it's down those controls
//      are disabled with an explanation rather than failing on submit.
//
// The probe is api.getSystemHealthSilent() — it never raises the global toast
// (this polls on an interval) and never throws.

type SystemHealth = {
  status: any | null; // last successful SystemStatusResponse, or null before the first success
  coreReachable: boolean; // false only after repeated failed probes → maintenance screen
  sidecarStatus: string | null; // "UP" | "DOWN" | "NOT_CONFIGURED" | "UNKNOWN" | null (unknown yet)
  sidecarAvailable: boolean; // optimistic: true unless a probe confirms DOWN/UNKNOWN
  checked: boolean; // has at least one probe completed
  refresh: () => void; // force an immediate re-probe (e.g. a Retry button)
};

const DEFAULT: SystemHealth = {
  status: null,
  coreReachable: true,
  sidecarStatus: null,
  sidecarAvailable: true,
  checked: false,
  refresh: () => {},
};

const SystemHealthContext = createContext<SystemHealth>(DEFAULT);

const POLL_MS = 20000; // re-probe cadence
const FAIL_THRESHOLD = 2; // consecutive failed probes before declaring core down

export function SystemHealthProvider({ children }: { children: React.ReactNode }) {
  const [status, setStatus] = useState<any | null>(null);
  const [coreReachable, setCoreReachable] = useState(true); // optimistic default
  const [checked, setChecked] = useState(false);
  const failuresRef = useRef(0);
  const cancelledRef = useRef(false);

  const probe = useCallback(async (force = false) => {
    const res = await sharedSystemHealth({ force });
    if (cancelledRef.current) return;
    if (res.ok) {
      failuresRef.current = 0;
      setStatus(res.data);
      setCoreReachable(true);
    } else if (res.status === 401 || res.status === 403) {
      // Core answered — it's an auth state, not an outage. Don't trip the
      // maintenance screen; AuthProvider owns the session/redirect flow.
      failuresRef.current = 0;
      setCoreReachable(true);
    } else {
      failuresRef.current += 1;
      if (failuresRef.current >= FAIL_THRESHOLD) setCoreReachable(false);
    }
    setChecked(true);
  }, []);

  useEffect(() => {
    cancelledRef.current = false;
    void probe();
    const stop = visibleInterval((): void => void probe(), POLL_MS);
    return () => {
      cancelledRef.current = true;
      stop();
    };
  }, [probe]);

  const sidecarStatus: string | null = status?.sidecar?.status ?? null;
  // Optimistic: treat the sidecar as available until a probe explicitly says
  // otherwise. NOT_CONFIGURED is a deployment choice (no status probe wired),
  // not an outage, so it does NOT gate the UI — only DOWN/UNKNOWN do.
  const sidecarAvailable = sidecarStatus == null || sidecarStatus === "UP" || sidecarStatus === "NOT_CONFIGURED";

  const value: SystemHealth = {
    status,
    coreReachable,
    sidecarStatus,
    sidecarAvailable,
    checked,
    refresh: () => void probe(true),
  };

  return <SystemHealthContext.Provider value={value}>{children}</SystemHealthContext.Provider>;
}

export function useSystemHealth(): SystemHealth {
  return useContext(SystemHealthContext);
}
