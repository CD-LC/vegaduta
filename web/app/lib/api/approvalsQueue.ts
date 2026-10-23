import { settingsApi } from "./settings";

// Reads for the approvals queue that have no home in governanceApi. Everything here is quiet (no global error
// toast): the queue degrades to "no countdown" rather than alarming when a read fails. The TTL comes from the
// settings catalog's effective values through the shared settings client, not a call of its own.

/** Catalog key whose value is how long a request may stay PENDING (core: app.approvals.expiry.ttl). */
export const APPROVAL_EXPIRY_KEY = "approvals.expiry.ttl";

let cachedTtl: Promise<number | null> | null = null;

/**
 * How many seconds a pending approval may wait before core closes it, read from the settings catalog's effective
 * values (GET /api/settings/effective). `0` means requests never expire; `null` means unknown (an older core
 * without the catalog, a failed read, or an unreadable value) and the UI then shows no countdown.
 */
export function parseTtlSeconds(value: unknown): number | null {
  if (typeof value === "number") return Number.isFinite(value) && value >= 0 ? value : null;
  if (typeof value !== "string") return null;
  const text = value.trim();
  if (!text) return null;
  if (/^\d+(\.\d+)?$/.test(text)) return Number(text);
  const iso = /^P(?:(\d+)D)?(?:T(?:(\d+)H)?(?:(\d+)M)?(?:(\d+(?:\.\d+)?)S)?)?$/i.exec(text);
  if (iso && text.length > 1) {
    const [, d, h, m, s] = iso;
    return Number(d ?? 0) * 86_400 + Number(h ?? 0) * 3_600 + Number(m ?? 0) * 60 + Number(s ?? 0);
  }
  const short = /^(\d+(?:\.\d+)?)\s*(s|sec|m|min|h|hr|d)$/i.exec(text);
  if (short) {
    const n = Number(short[1]);
    const unit = short[2].toLowerCase();
    if (unit.startsWith("s")) return n;
    if (unit.startsWith("m")) return n * 60;
    if (unit.startsWith("h")) return n * 3_600;
    return n * 86_400;
  }
  return null;
}

export const approvalsQueueApi = {
  getExpiryTtlSeconds: (): Promise<number | null> => {
    if (cachedTtl === null) {
      cachedTtl = settingsApi
        .getEffective()
        .then((body) => parseTtlSeconds(body.values[APPROVAL_EXPIRY_KEY]?.value))
        .catch((): null => null);
      // An unknown answer is not cached: the next mount asks again.
      void cachedTtl.then((ttl) => {
        if (ttl == null) cachedTtl = null;
      });
    }
    return cachedTtl;
  },
  /** Test hook / after a settings change: forget the cached TTL. */
  resetExpiryTtlCache: (): void => {
    cachedTtl = null;
  },
};
