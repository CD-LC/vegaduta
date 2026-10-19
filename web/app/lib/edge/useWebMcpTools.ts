"use client";

// The mount-side of webmcp.ts: while an agent surface is open, that agent's
// edge-safe tools are published to navigator.modelContext; when it closes (or
// the person switches agents), they're withdrawn. Kept in its own file so
// webmcp.ts stays framework-free and testable in plain node.
//
// Deliberately silent: every failure mode inside publishWebMcpTools resolves
// to an empty state rather than throwing, so a browser without WebMCP, a
// server without /api/edge/tools, and a user who turned publishing off all
// look the same from here — nothing published, nothing reported, nothing
// broken. The Edge console is where the state is explained (EdgeConsole.tsx).

import { useEffect, useState } from "react";
import {
  getWebMcpPublishState,
  publishWebMcpTools,
  subscribeWebMcpPublishState,
  unpublishWebMcpTools,
  type WebMcpPublishState,
} from "./webmcp";
import { EDGE_PREFS_EVENT } from "./capabilities";

/**
 * Publish `agentId`'s tools for as long as this component is mounted.
 * Re-publishes when the agent changes or the person flips the preference.
 */
export function useWebMcpTools(agentId: string | null | undefined): WebMcpPublishState {
  const [state, setState] = useState<WebMcpPublishState>(getWebMcpPublishState);

  useEffect(() => subscribeWebMcpPublishState(setState), []);

  useEffect(() => {
    if (!agentId) return undefined;
    let cancelled = false;
    const publish = () => {
      void publishWebMcpTools(agentId).catch(() => {
        /* publishWebMcpTools never rejects; belt-and-braces */
      });
    };
    publish();
    // The preference lives in localStorage and is changed from the Edge
    // console, which may be open in this same tab — the shared prefs event is
    // how every other edge surface stays in sync, so this follows it too.
    const onPrefs = () => {
      if (!cancelled) publish();
    };
    if (typeof window !== "undefined") window.addEventListener(EDGE_PREFS_EVENT, onPrefs);
    return () => {
      cancelled = true;
      if (typeof window !== "undefined") window.removeEventListener(EDGE_PREFS_EVENT, onPrefs);
      // Withdraw on unmount: a tool that is no longer on screen must not stay
      // callable by whatever agent is visiting the page.
      unpublishWebMcpTools();
    };
  }, [agentId]);

  return state;
}
