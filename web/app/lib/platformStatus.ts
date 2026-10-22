import { useSyncExternalStore } from "react";
import { API_BASE } from "./api/core";
import { declaredEdition, parseEdition, type Edition } from "./edition";

// Platform-wide maintenance + announcement state, set by platform admins in
// web-admin (/maintenance) and read here from the PUBLIC, unauthenticated
// GET /api/public/status:
//   { maintenance: { enabled, message, endsAt|null },
//     announcement: { enabled, message, level, linkUrl|null, expiresAt|null } | null,
//     edition: { tier, billingEnabled, authMode, features: { <feature-id>: boolean } } }
//
// The edition block is a VERIFICATION, not an input: the console renders from the synchronous
// NEXT_PUBLIC_EDITION seed (lib/edition.ts) and only compares it with the tier core reports. A
// mismatch (a console image built for one edition in front of a core running another) logs a
// warning and raises a banner through the announcement slot.
//
// Two ways maintenance is detected:
//   1. the status poll (on load + every POLL_MS) says maintenance.enabled;
//   2. any API call answers 503 { error: "maintenance" } - api/core.ts's toJson
//      calls signalMaintenance(), so an open tab flips immediately instead of
//      waiting for the next poll.
// Everything fails silent: a status fetch that errors leaves the last known
// state (initially: no banner, no maintenance) and never toasts.
//
// Module-level store + useSyncExternalStore so the banner (root layout) and
// AppShell's maintenance gate read one shared state without a provider.

export const STATUS_POLL_MS = 5 * 60 * 1000;
export const DISMISS_KEY_PREFIX = "vd_announcement_dismissed:";

export type AnnouncementLevel = "info" | "warning" | "critical";

export interface PublicMaintenance {
  enabled: boolean;
  message: string | null;
  endsAt: string | null;
}

export interface PublicAnnouncement {
  enabled: boolean;
  message: string;
  level: AnnouncementLevel;
  linkUrl: string | null;
  expiresAt: string | null;
}

/** What core reports about the edition it runs as (EditionInfo); unknown fields are tolerated. */
export interface PublicEdition {
  tier: Edition;
  billingEnabled: boolean;
  authMode: string;
  features: Record<string, boolean>;
}

export interface PublicStatus {
  maintenance: PublicMaintenance;
  announcement: PublicAnnouncement | null;
  /** Null from a core that predates the edition contract (or sent something unusable). */
  edition: PublicEdition | null;
}

export interface PlatformStatusState {
  status: PublicStatus | null;
  /** Set when an API call answered 503 {error:"maintenance"}; cleared when the poll says maintenance is off. */
  maintenanceSignalled: boolean;
}

// ---------------------------------------------------------------- pure helpers

function normalizeEdition(raw: any): PublicEdition | null {
  if (!raw || typeof raw !== "object") return null;
  const tier = parseEdition(typeof raw.tier === "string" ? raw.tier : null);
  if (!tier) return null;
  const features: Record<string, boolean> = {};
  if (raw.features && typeof raw.features === "object") {
    for (const [k, v] of Object.entries(raw.features)) if (typeof v === "boolean") features[k] = v;
  }
  return {
    tier,
    billingEnabled: raw.billingEnabled === true,
    authMode: typeof raw.authMode === "string" ? raw.authMode : "",
    features,
  };
}

/** Tolerant parse of the public status body; null for anything unusable. */
export function normalizePublicStatus(raw: any): PublicStatus | null {
  if (!raw || typeof raw !== "object") return null;
  const m = raw.maintenance && typeof raw.maintenance === "object" ? raw.maintenance : {};
  const a = raw.announcement && typeof raw.announcement === "object" ? raw.announcement : null;
  const level: AnnouncementLevel = a && ["info", "warning", "critical"].includes(a.level) ? a.level : "info";
  return {
    maintenance: {
      enabled: m.enabled === true,
      message: typeof m.message === "string" && m.message.trim() ? m.message : null,
      endsAt: typeof m.endsAt === "string" && m.endsAt ? m.endsAt : null,
    },
    announcement: a
      ? {
          enabled: a.enabled === true,
          message: typeof a.message === "string" ? a.message : "",
          level,
          linkUrl: typeof a.linkUrl === "string" && /^https?:\/\//i.test(a.linkUrl) ? a.linkUrl : null,
          expiresAt: typeof a.expiresAt === "string" && a.expiresAt ? a.expiresAt : null,
        }
      : null,
    edition: normalizeEdition(raw.edition),
  };
}

/**
 * The console's edition seed disagreeing with the tier core reports, or null when they agree, when
 * core did not report one, or when the console was not told its edition at all (an undeclared build
 * renders "every feature on" and has nothing to contradict).
 */
export function editionMismatch(
  status: Pick<PublicStatus, "edition"> | null | undefined,
  seed: Edition | null = declaredEdition()
): { seed: Edition; server: Edition } | null {
  const server = status?.edition?.tier;
  if (!server || !seed || server === seed) return null;
  return { seed, server };
}

function mismatchMessage(m: { seed: Edition; server: Edition }): string {
  return (
    `This console was built for the ${m.seed} edition but the server runs as ${m.server}. ` +
    "Some pages may be missing or broken; restart the stack with a single VEGADUTA_EDITION value."
  );
}

let warnedMismatch: string | null = null;

/**
 * Warns once per distinct mismatch and, when core sent no announcement of its own, surfaces it through
 * the announcement slot so PlatformStatusBanner shows it. Rendering is never changed by it.
 */
export function verifyEdition(status: PublicStatus, seed: Edition | null = declaredEdition()): PublicStatus {
  const m = editionMismatch(status, seed);
  if (!m) return status;
  const message = mismatchMessage(m);
  if (warnedMismatch !== message) {
    warnedMismatch = message;
    console.warn(`[edition] ${message}`);
  }
  if (status.announcement) return status;
  return { ...status, announcement: { enabled: true, message, level: "warning", linkUrl: null, expiresAt: null } };
}

/** Small stable string hash (djb2, base36) - identifies one announcement text for its dismiss key. */
export function hashMessage(s: string): string {
  let h = 5381;
  for (let i = 0; i < s.length; i++) h = ((h << 5) + h + s.charCodeAt(i)) | 0;
  return (h >>> 0).toString(36);
}

/** A changed message (or level/link) gets a new key, so a dismissed banner reappears when it changes. */
export function dismissKey(a: Pick<PublicAnnouncement, "message" | "level" | "linkUrl">): string {
  return `${DISMISS_KEY_PREFIX}${hashMessage(`${a.level}|${a.message}|${a.linkUrl || ""}`)}`;
}

/** Enabled, has text, not expired. Dismissal is checked separately (it needs storage). */
export function announcementActive(a: PublicAnnouncement | null | undefined, now: number = Date.now()): boolean {
  if (!a || !a.enabled || !a.message.trim()) return false;
  if (!a.expiresAt) return true;
  const t = Date.parse(a.expiresAt);
  return Number.isNaN(t) ? true : t > now;
}

/** True for core's maintenance refusal: HTTP 503 with body { error: "maintenance" }. */
export function isMaintenanceResponse(status: number, body: any): boolean {
  return status === 503 && !!body && typeof body === "object" && body.error === "maintenance";
}

export function maintenanceActive(state: PlatformStatusState): boolean {
  return state.maintenanceSignalled || !!state.status?.maintenance.enabled;
}

export function readDismissed(key: string): boolean {
  try {
    return window.localStorage.getItem(key) === "1";
  } catch {
    return false;
  }
}

export function writeDismissed(key: string): void {
  try {
    window.localStorage.setItem(key, "1");
  } catch {
    // Private mode / blocked storage: the banner just comes back next load.
  }
}

// ---------------------------------------------------------------- store

let state: PlatformStatusState = { status: null, maintenanceSignalled: false };
const listeners = new Set<() => void>();
const SERVER_STATE: PlatformStatusState = { status: null, maintenanceSignalled: false };

function setState(next: PlatformStatusState) {
  state = next;
  listeners.forEach((l) => l());
}

function subscribe(l: () => void) {
  listeners.add(l);
  return () => listeners.delete(l);
}

export function getPlatformStatusState(): PlatformStatusState {
  return state;
}

/** Called by api/core.ts when a response is core's maintenance 503. */
export function signalMaintenance(): void {
  if (state.maintenanceSignalled) return;
  setState({ ...state, maintenanceSignalled: true });
}

/** Test hook. */
export function resetPlatformStatusForTests(): void {
  state = { status: null, maintenanceSignalled: false };
  inflight = null;
  lastAttemptAt = 0;
  lastFailed = false;
  warnedMismatch = null;
}

// One request at a time, and never more often than MIN_REFETCH_MS unless the
// caller forces it (the maintenance screen's Retry). Layout remounts and the
// shell's own refresh calls therefore collapse into the single banner poll.
// After a failure the next automatic attempt waits FAILURE_BACKOFF_MS: a
// transient 429/5xx/CORS rejection (SE-36) must not be hammered, and each
// failed attempt shows up as a console error the browser itself emits.
export const MIN_REFETCH_MS = 30 * 1000;
export const FAILURE_BACKOFF_MS = 4 * 60 * 1000;

let inflight: Promise<void> | null = null;
let lastAttemptAt = 0;
let lastFailed = false;

/**
 * Fetch /api/public/status once. Plain fetch on purpose: the endpoint is public
 * (no token needed), and apiFetch's toast/outage handling must not fire for a
 * background poll. Fails silent - keeps the last known state. Coalesces
 * concurrent callers and throttles automatic calls (see above); pass
 * { force: true } for a user-initiated retry.
 */
export async function refreshPlatformStatus(
  fetchImpl: typeof fetch = fetch,
  opts: { force?: boolean } = {},
): Promise<void> {
  if (inflight !== null) return inflight;
  const wait = lastFailed ? FAILURE_BACKOFF_MS : MIN_REFETCH_MS;
  if (!opts.force && lastAttemptAt && Date.now() - lastAttemptAt < wait) return;
  lastAttemptAt = Date.now();
  inflight = (async () => {
    try {
      const res = await fetchImpl(`${API_BASE}/api/public/status`, { cache: "no-store", credentials: "omit" });
      if (!res.ok) {
        lastFailed = true;
        return;
      }
      const status = normalizePublicStatus(await res.json());
      if (!status) {
        lastFailed = true;
        return;
      }
      lastFailed = false;
      const verified = verifyEdition(status);
      // The poll is authoritative for "maintenance ended": clear a 503 signal
      // once the server says maintenance is off.
      setState({ status: verified, maintenanceSignalled: status.maintenance.enabled ? state.maintenanceSignalled : false });
    } catch {
      // Network error / CORS rejection / bad JSON: no banner change, no toast.
      lastFailed = true;
    } finally {
      inflight = null;
    }
  })();
  return inflight;
}

export function usePlatformStatus(): PlatformStatusState {
  return useSyncExternalStore(subscribe, getPlatformStatusState, () => SERVER_STATE);
}
