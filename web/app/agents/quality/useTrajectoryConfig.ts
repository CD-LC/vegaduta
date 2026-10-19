"use client";

import { useEffect, useState } from "react";
import { api } from "../../lib/api";
import { normalizeTrajectoryConfig, type TrajectoryConfig } from "./trajectoryEval";

// One config fetch per agent per page lifetime, shared by every case row and run panel that asks: the
// trajectory surface appears on each case of each dataset, and N identical GETs for "is this feature on?" would
// be pure noise. A failed read (older core, Premium gate, transient error) resolves null and is not cached, so
// the next mount tries again.
const cache = new Map<string, Promise<TrajectoryConfig | null>>();

function loadConfig(agentId: string): Promise<TrajectoryConfig | null> {
  const hit = cache.get(agentId);
  if (hit !== undefined) return hit;
  const pending: Promise<TrajectoryConfig | null> = Promise.resolve(api.getTrajectoryEvalConfig(agentId))
    .then((raw: unknown) => normalizeTrajectoryConfig(raw))
    .catch((): TrajectoryConfig | null => {
      cache.delete(agentId);
      return null;
    });
  cache.set(agentId, pending);
  return pending;
}

/**
 * The trajectory-eval config for an agent: `undefined` while loading, `null` when the surface should not be
 * shown at all (feature off, older core, plan gate, failure), otherwise the config. Callers render nothing for
 * anything but an enabled config - an absent optional surface must not look like an error.
 */
export function useTrajectoryConfig(agentId: string | null | undefined): TrajectoryConfig | null | undefined {
  const [state, setState] = useState<{ agentId: string; config: TrajectoryConfig | null } | null>(null);

  useEffect(() => {
    if (!agentId) return undefined;
    let cancelled = false;
    const requestedFor: string = agentId;
    void loadConfig(requestedFor).then((config) => {
      if (!cancelled) setState({ agentId: requestedFor, config });
    });
    return () => {
      cancelled = true;
    };
  }, [agentId]);

  if (!agentId) return null;
  if (!state || state.agentId !== agentId) return undefined;
  return state.config && state.config.enabled ? state.config : null;
}
