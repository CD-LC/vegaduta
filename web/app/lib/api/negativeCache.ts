// Session-scoped memory of "the server already told us this is not there"
// (prod signed-in pass 2026-10-05, bug 12).
//
// A browser logs "Failed to load resource: 404" for every 404 response no
// matter how quietly the code handles it, so the only way to keep an EXPECTED
// 404 out of the console is not to ask again. Two shapes of expected 404:
//   - a feature flag that is off on this deployment (browser takeover): the
//     answer does not change while the tab is open - remembered for the whole
//     browser session (no TTL);
//   - "nothing recorded for this agent yet" (the hourly health sweep): the
//     answer changes slowly - remembered for a TTL.
// Backed by sessionStorage so a reload does not re-probe, with a module Map in
// front so it still works where storage is blocked.

const PREFIX = "vd.missing.";
const memory = new Map<string, number>(); // key -> expiry epoch ms (Infinity = whole session)

function readStored(key: string): number | null {
  try {
    const raw = globalThis.sessionStorage?.getItem(PREFIX + key);
    if (raw == null) return null;
    if (raw === "session") return Infinity;
    const n = Number(raw);
    return Number.isFinite(n) ? n : null;
  } catch {
    return null;
  }
}

/** Remember that `key` is missing. `ttlMs` omitted = for the whole browser session. */
export function rememberMissing(key: string, ttlMs?: number): void {
  const expiry = ttlMs == null ? Infinity : Date.now() + ttlMs;
  memory.set(key, expiry);
  try {
    globalThis.sessionStorage?.setItem(PREFIX + key, expiry === Infinity ? "session" : String(expiry));
  } catch {
    // Best-effort - the Map above still covers this page.
  }
}

/** True while a remembered "missing" for `key` is still current. */
export function isKnownMissing(key: string): boolean {
  let expiry = memory.get(key);
  if (expiry === undefined) {
    const stored = readStored(key);
    if (stored === null) return false;
    expiry = stored;
    memory.set(key, expiry);
  }
  if (Date.now() < expiry) return true;
  forgetMissing(key);
  return false;
}

export function forgetMissing(key: string): void {
  memory.delete(key);
  try {
    globalThis.sessionStorage?.removeItem(PREFIX + key);
  } catch {
    // Best-effort.
  }
}

/** Test seam: drop everything remembered. */
export function resetMissingCacheForTests(): void {
  memory.clear();
  try {
    const store = globalThis.sessionStorage;
    if (!store) return;
    for (let i = store.length - 1; i >= 0; i--) {
      const k = store.key(i);
      if (k && k.startsWith(PREFIX)) store.removeItem(k);
    }
  } catch {
    // Best-effort.
  }
}
