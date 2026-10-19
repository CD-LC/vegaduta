"use client";

import { useEffect, useState } from "react";
import SystemStatusPanel from "../dashboard/SystemStatusPanel";
import { api } from "../../lib/api";

// Self-fetching wrapper around the shared SystemStatusPanel - reads the
// platform-wide health passthrough (GET /api/platform/health, same
// SystemStatusResponse shape SystemStatusController exposes). Used by Overview
// and embedded as a native panel in Diagnostics & Observability (§10). Pass
// `pollMs` to keep it live.
export function SystemStatusSection({ pollMs, hideHeader = false }: { pollMs?: number; hideHeader?: boolean }) {
  const [health, setHealth] = useState<any>(null);
  const [error, setError] = useState<any>(null);

  useEffect(() => {
    let cancelled = false;
    const load = () =>
      api
        .getPlatformHealth()
        .then((h) => {
          if (cancelled) return;
          setHealth(h);
          setError(null);
        })
        .catch((e) => !cancelled && setError(e.message));
    load().catch((e) => console.error("Platform health load failed", e));
    if (!pollMs) return undefined;
    const t = setInterval(() => {
      load().catch((e) => console.error("Platform health poll failed", e));
    }, pollMs);
    return () => {
      cancelled = true;
      clearInterval(t);
    };
  }, [pollMs]);

  return <SystemStatusPanel status={health} error={error} hideHeader={hideHeader} />;
}
