// Per-conversation local session caches (one per tier). Split out of
// ../tierRouter.ts, which re-exports resetEdgeSession.

import type { Tier0Session } from "../tier0";
import type { Tier1Session } from "../tier1";
import type { Tier2Session } from "../tier2";
import { DEFAULT_SESSION_KEY } from "./shared";

export const tier0Sessions = new Map<string, Tier0Session>();
export const tier1Sessions = new Map<string, Tier1Session>();
export const tier2Sessions = new Map<string, Tier2Session>();

/** Drop the local conversation context for a key (new chat / reset), across
 * every local tier. Safe to call with an unknown key or on the server. */
export function resetEdgeSession(sessionKey?: string | null): void {
  const key = sessionKey || DEFAULT_SESSION_KEY;
  const t0 = tier0Sessions.get(key);
  if (t0) {
    tier0Sessions.delete(key);
    try {
      t0.destroy();
    } catch {
      // Best-effort.
    }
  }
  const t1 = tier1Sessions.get(key);
  if (t1) {
    tier1Sessions.delete(key);
    try {
      t1.destroy();
    } catch {
      // Best-effort.
    }
  }
  const t2 = tier2Sessions.get(key);
  if (t2) {
    tier2Sessions.delete(key);
    try {
      t2.destroy();
    } catch {
      // Best-effort.
    }
  }
}


export function dropTier0Session(key: string): void {
  const session = tier0Sessions.get(key);
  if (!session) return;
  tier0Sessions.delete(key);
  try {
    session.destroy();
  } catch {
    // Best-effort.
  }
}

export function dropTier1Session(key: string): void {
  const session = tier1Sessions.get(key);
  if (!session) return;
  tier1Sessions.delete(key);
  try {
    session.destroy();
  } catch {
    // Best-effort.
  }
}

export function dropTier2Session(key: string): void {
  const session = tier2Sessions.get(key);
  if (!session) return;
  tier2Sessions.delete(key);
  try {
    session.destroy();
  } catch {
    // Best-effort.
  }
}
