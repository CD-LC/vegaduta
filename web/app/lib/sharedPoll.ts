// One request per key at a time, and a short-lived shared result, for the
// shell's background reads (SE-37): /api/system/status was fetched by the root
// SystemHealthProvider, the session warm-up and the dashboard on the same page
// load, and /home asked for the pending approvals more than once.
//
// This sits above the api layer (web/app/lib/api/* has its own owner and its
// own request dedupe) and is deliberately small: callers that need fresh data
// (a Retry button, a post-write refresh) pass { force: true }.

import { api } from "./api";

interface Entry<T> {
  at: number;
  value?: T;
  promise?: Promise<T>;
}

const entries = new Map<string, Entry<unknown>>();

/**
 * Run `fn` unless the same key is already in flight (callers share its promise)
 * or finished less than `ttlMs` ago (callers get the same value). A rejection
 * is shared with callers that joined while it was in flight but is never cached.
 */
export function sharedFetch<T>(key: string, fn: () => Promise<T>, ttlMs: number, opts: { force?: boolean } = {}): Promise<T> {
  const hit = entries.get(key) as Entry<T> | undefined;
  if (hit?.promise !== undefined) return hit.promise;
  if (!opts.force && hit && "value" in hit && Date.now() - hit.at < ttlMs) return Promise.resolve(hit.value as T);
  const promise = fn().then(
    (value) => {
      entries.set(key, { at: Date.now(), value });
      return value;
    },
    (err: unknown) => {
      entries.delete(key);
      throw err;
    },
  );
  entries.set(key, { at: hit?.at ?? 0, value: hit?.value, promise });
  return promise;
}

/** Test hook. */
export function resetSharedPollsForTests(): void {
  entries.clear();
}

export const SYSTEM_STATUS_TTL_MS = 10_000;
export const APPROVALS_TTL_MS = 10_000;

/** The silent /api/system/status probe, shared by SystemHealthProvider and the dashboard. */
export function sharedSystemHealth(opts: { force?: boolean } = {}) {
  return sharedFetch("system-health", () => api.getSystemHealthSilent(), SYSTEM_STATUS_TTL_MS, opts);
}

/** The full status body for readers that want the data and treat a failed probe as an error. */
export async function sharedSystemStatus(opts: { force?: boolean } = {}) {
  const res = await sharedSystemHealth(opts);
  if (!res.ok || !res.data) throw new Error(`system status unavailable (${res.status ?? "no response"})`);
  return res.data;
}

/** Pending approvals (list + real total), requested once per window however many surfaces ask. */
export function sharedPendingApprovals(opts: { force?: boolean } = {}) {
  return sharedFetch("approvals-pending", () => api.listApprovals("PENDING"), APPROVALS_TTL_MS, opts);
}

export function sharedPendingApprovalCount(opts: { force?: boolean } = {}) {
  return sharedFetch("approvals-pending-count", () => api.countApprovals("PENDING"), APPROVALS_TTL_MS, opts);
}
