// Thin fetch wrapper around the core Spring Boot API. No extra HTTP client
// library - plain fetch is enough for this surface area, consistent with
// the rest of this project's "no dependency until it earns its keep" style.
//
// NEXT_PUBLIC_API_BASE_URL lets this point somewhere other than localhost
// (e.g. a docker-compose service name in Stage 8) without a rebuild-time
// code change - defaults to the same localhost:8080 every curl example in
// the README uses.
//
// Layout: transport.ts (base URL, network failures, authenticated fetch),
// streamMarkers.ts (SSE markers), responses.ts (toJson & friends) and
// browserDownload.ts are re-exported below; this file owns apiFetch and its
// GET dedupe / stale-while-revalidate cache.
import { auth } from "../auth";
import {
  PERSIST_MAX_AGE_MS,
  clearPersisted,
  isPersistablePath,
  readPersisted,
  writePersisted,
} from "./persistentCache";
import { isViewAsActive, subscribeViewAs } from "../viewAs";
import { apiFetchOnce, isReplayableBody, type ApiFetchOptions } from "./transport";

export {
  API_BASE,
  BACKEND_UNREACHABLE_MESSAGE,
  OFFLINE_MESSAGE,
  apiActivity,
  apiFetchOnce,
  describeNetworkFailure,
  doFetch,
  isReplayableBody,
  type ApiError,
  type ApiFetchOptions,
} from "./transport";
export {
  FRAMEWORK_MESSAGE_FAILURE_CLASSES,
  STEP_EVENT_MARKER,
  STREAM_ERROR_MARKER,
  type AgentStepEvent,
} from "./streamMarkers";
export { friendlyStatusFallback, jsonBody, toJson, toJsonQuietOn } from "./responses";
export { triggerBrowserDownload, voiceBlobExtension } from "./browserDownload";

// Direct requirement (2026-08-05): "never log the user out merely because a
// single API call returned 401 - retry once after a token refresh first."
//
// A 401 here is almost never "the session is over". The realistic causes are a
// clock-skewed or a few-seconds-stale access token, a request that was already
// in flight when the token rotated, or core restarting behind the tunnel. The
// demand refresh inside apiFetchOnce uses updateToken(30), which is a no-op
// when the token merely *looks* valid client-side - so the retry below forces a
// real refresh (updateToken(-1)) and replays the request exactly once. Only if
// that forced refresh itself fails is the session genuinely gone, and even then
// nothing here logs anyone out: refreshToken()'s failure path reports it and
// getToken()'s own path raises the "stay signed in?" prompt, which is
// user-answered. Exactly one retry - never a loop.
// Two layers of "don't ask the network twice", the out-of-the-box speed work
// every modern console ships (SWR/React-Query style, kept dependency-free):
//
// 1. IN-FLIGHT DEDUPE - identical CONCURRENT GETs share one request. Page +
//    component pairs routinely fire the same read at the same moment (e.g.
//    /agents' own mount fetch alongside AgentPlayground's provider list).
// 2. FRESH TTL + STALE-WHILE-REVALIDATE - a completed GET is remembered and
//    served for GET_CACHE_TTL_MS with zero network. Past that, for up to
//    GET_CACHE_STALE_MS, the stale copy is STILL served instantly while a
//    deduped background refresh repopulates the entry for the next read -
//    the standard SWR contract (React Query's staleTime+refetch, minus the
//    dependency). This is what kills the "every page loads" feel: a revisit
//    renders last-known data immediately instead of a spinner, and the data
//    self-heals behind the scenes. Own-writes stay exact - every mutation
//    through this same apiFetch flushes the whole map (non-GET branch), so
//    a page that writes then re-reads always sees its own write. The one
//    honest bound: data another user/tab changes can be up to ~STALE_MS old
//    on screen, converging on each navigation.
//
// Safety posture: only PLAIN GETs participate - any caller-specific option
// (timeout, signal, headers, body) or non-GET method bypasses sharing
// entirely, so nothing with side effects or per-caller semantics can ever
// be conflated. Error responses are shared in-flight (all waiters get the
// same failure, same as today) but never cached - a recovered backend shows
// up on the very next call. A terminal 401 flushes the cache too: the
// session that populated it is over, and no later identity may inherit it.
const GET_CACHE_TTL_MS = 15_000;
// Capability / config reads that only change when an operator redeploys or flips a platform setting. They are
// re-asked on every page, so they stay fresh longer than a list read (a write still flushes them, as for any GET).
const STATIC_GET_TTL_MS = 120_000;
const STATIC_GET_PATH =
  /^\/api\/(coding-tasks\/capabilities|scheduled-tasks\/capabilities|data-tables\/config|session\/config|providers\/modalities|browser\/takeover\/capabilities)(\?|$)/;
const freshTtlMs = (path: string): number => (STATIC_GET_PATH.test(path) ? STATIC_GET_TTL_MS : GET_CACHE_TTL_MS);
const GET_CACHE_STALE_MS = 300_000;
const GET_CACHE_MAX_ENTRIES = 200;

// A GET response buffered into a reusable form - a Response body is
// single-use, so sharing/caching requires keeping the bytes and handing
// each consumer a fresh Response. `res` survives for the (test-stub) case
// where the response has no text() to buffer; those pass through unshared.
type SharedGet = { res?: Response; ok: boolean; status?: number; headers?: Headers; body?: string };

const inflightGets = new Map<string, Promise<SharedGet>>();
// `maxStaleMs` overrides GET_CACHE_STALE_MS for entries seeded from the L2
// (IndexedDB) copy, which are deliberately older than one page life.
const getCache = new Map<string, { data: SharedGet; fetchedAt: number; maxStaleMs?: number }>();

// Set by hydrateApiCache(); apiFetch waits on it (bounded) before its first
// L1 lookup so a read issued during boot sees the persisted rows.
let hydration: Promise<void> | null = null;
// Bumped on every flush; a hydration that started before one must not seed.
let cacheEpoch = 0;
const HYDRATION_WAIT_MS = 250;

/**
 * Flush the whole GET cache. Called by tests between cases (a module-level
 * cache survives across a suite's fetch stubs otherwise) and legitimately by
 * sign-out/user-switch flows - a different identity must never inherit a
 * previous session's cached reads.
 */
// Returns the IndexedDB wipe so logout can wait for it before navigating away
// (a page unload mid-delete would leave the previous identity's reads on disk).
export function clearApiGetCache(): Promise<void> {
  getCache.clear();
  cacheEpoch++;
  hydration = null;
  return clearPersisted().catch(() => {});
}

/**
 * L2 -> L1 seed, called once per session at boot (AppChromeProvider). Loads
 * the signed-in user's persisted list reads into the in-memory map so the
 * first navigation after a reload paints from cache and revalidates behind it.
 * Never throws and never blocks longer than HYDRATION_WAIT_MS per read.
 */
export function hydrateApiCache(): Promise<void> {
  const scope = auth.getUserId?.();
  // A view-as tab must never seed the admin's own persisted reads (nor the
  // reverse - see persistGet).
  if (isViewAsActive()) return Promise.resolve();
  if (!scope || typeof window === "undefined") return Promise.resolve();
  const epoch = cacheEpoch;
  const pending: Promise<void> = readPersisted(scope)
    .then((rows) => {
      if (epoch !== cacheEpoch) return;
      for (const r of rows) {
        // Never clobber something a live request already cached this session.
        if (getCache.has(r.path)) continue;
        getCache.set(r.path, {
          data: {
            ok: true,
            status: r.status,
            headers: new Headers(r.contentType ? { "content-type": r.contentType } : {}),
            body: r.body,
          },
          fetchedAt: r.fetchedAt,
          maxStaleMs: PERSIST_MAX_AGE_MS,
        });
      }
    })
    .catch(() => {});
  hydration = pending;
  // Once the seed has landed there is nothing left to wait for: clear the
  // handle so later reads skip the bounded wait entirely (unless a flush or a
  // newer hydration already replaced it).
  void pending.then(() => {
    if (hydration === pending) hydration = null;
  });
  return pending;
}

function persistGet(path: string, s: SharedGet): void {
  if (!isPersistablePath(path) || s.body == null) return;
  // View-as reads are another tenant's data under the ADMIN's user id scope:
  // never written to disk.
  if (isViewAsActive()) return;
  const scope = auth.getUserId?.();
  if (!scope) return;
  void writePersisted(scope, {
    path,
    fetchedAt: Date.now(),
    status: s.status ?? 200,
    contentType: s.headers?.get("content-type") ?? null,
    body: s.body,
  });
}

/**
 * Session boot warm-up: fire the handful of GETs nearly every console route
 * needs the moment auth resolves, so the FIRST navigation renders from the
 * SWR cache instead of paying a cold round trip per read. Fire-and-forget
 * through apiFetch itself - responses land in the same dedupe+cache a real
 * page call uses (a page mounting mid-prefetch shares the in-flight request
 * rather than doubling it). Errors are swallowed by design: prefetch is
 * speculative, the real call site surfaces its own errors when it runs.
 */
// Entering or leaving view-as switches whose data every GET returns, so the
// in-memory cache (and the on-disk copy) from the other identity is dropped.
if (typeof window !== "undefined") {
  subscribeViewAs(() => {
    void clearApiGetCache();
  });
}

export function prefetchApiGets(paths: string[]): void {
  for (const path of paths) {
    apiFetch(path).then(
      () => {},
      () => {}
    );
  }
}

// POSTs that only READ (the body is too large for a query string, or it is a dry-run preview): they change no
// server state, so they must not flush the GET cache or drop the reads on the wire - model-pricing runs on every
// Agents page load and used to empty the cache under providers/availability and friends each time.
function isReadOnlyPost(path: string): boolean {
  return /^\/api\/(model-pricing|workflows\/import\/preview)(\?|$)/.test(path);
}

/**
 * Share one in-flight call between concurrent callers of the same `key` (the entry is dropped when it settles,
 * so this never serves a stale result). For reads that bypass apiFetch's own GET sharing, e.g. a raw fetch or a
 * read-only POST.
 */
const sharedCalls = new Map<string, Promise<unknown>>();
export function shareInflight<T>(key: string, call: () => Promise<T>): Promise<T> {
  const existing = sharedCalls.get(key);
  if (existing !== undefined) return existing as Promise<T>;
  const p = call();
  sharedCalls.set(key, p);
  const clear = () => {
    if (sharedCalls.get(key) === p) sharedCalls.delete(key);
  };
  p.then(clear, clear);
  return p;
}

function isChattyWrite(path: string): boolean {
  return /^\/api\/(telemetry|voice)(\/|\?|$)|^\/api\/agents\/[^/?]+\/(chat|voice)(\/|\?|$)/.test(path);
}

function isDedupableGet(options: ApiFetchOptions): boolean {
  const method = (options?.method || "GET").toUpperCase();
  return (
    method === "GET" &&
    options?.timeoutMs == null &&
    options?.signal == null &&
    options?.headers == null &&
    options?.body == null &&
    // skipGetCache: explicit opt-out for inherently volatile reads that a
    // caller polls for live progress (e.g. a workflow run's growing trace).
    // Without it the 15s fresh TTL replays the same snapshot to every poll
    // tick, and the SWR window keeps the UI one refresh behind forever -
    // the exact "steps never update until I reload the page" freeze.
    options?.skipGetCache !== true
  );
}

function responseFromShared(s: SharedGet): Response {
  // Two concurrent GETs of one binary path share the in-flight Response: give
  // each its own clone so neither reads an already-consumed body.
  if (s.res) return typeof s.res.clone === "function" && !s.res.bodyUsed ? s.res.clone() : s.res;
  // Null-body statuses (204 etc.) reject a non-null body in the Response
  // constructor - an empty buffered body is passed as null.
  return new Response(s.body === "" ? null : s.body, { status: s.status, headers: s.headers });
}

// Only a text body can be held as a string. A binary download (PDF, DOCX,
// PPTX, images, audio, video) read through res.text() is UTF-8-decoded:
// every invalid byte becomes U+FFFD, and the file the user saves is the
// right size but "corrupted" - every generated document since the cache
// landed (2026-09-25). No content-type (204/empty) counts as text.
const TEXT_CONTENT_TYPE =
  /^\s*(text\/|application\/(json|xml|javascript|x-ndjson|problem\+json|[\w.+-]*\+(json|xml))\b)/i;

export function isTextResponse(res: { headers?: { get?: (name: string) => string | null } }): boolean {
  const type = res?.headers?.get?.("content-type");
  return !type || TEXT_CONTENT_TYPE.test(type);
}

async function shareableGet(path: string, options: ApiFetchOptions): Promise<SharedGet> {
  const res = await performApiFetch(path, options);
  if (typeof res?.text !== "function" || !isTextResponse(res)) {
    // Passed through untouched and never cached (refreshGet stores only `body`).
    return { res, ok: !!res?.ok };
  }
  return { status: res.status, headers: res.headers, body: await res.text(), ok: res.ok };
}

// A write invalidates every cached read AND every read already on the wire:
// a GET sent before the write lands can answer with the old state, and sharing
// it with (or caching it for) a later reader would hide the write until reload.
function flushForWrite(): void {
  getCache.clear();
  inflightGets.clear();
  cacheEpoch++;
  void clearPersisted();
}

export async function apiFetch(path: string, options: ApiFetchOptions = {}): Promise<Response> {
  const method = (options?.method || "GET").toUpperCase();
  if (method !== "GET") {
    if (method === "POST" && isReadOnlyPost(path)) return performApiFetch(path, options);
    // Any write can change what every GET returns, and the cache has no way
    // to know which paths a mutation touched - the whole (small, ~15s) map
    // is dropped. This is what keeps the cache honest: a page that writes
    // then re-reads always sees its own write.
    if (isChattyWrite(path)) {
      // Sending a message / a telemetry beacon cannot change an agent, workflow
      // or config LIST, but used to empty the whole cache and make the next
      // navigation cold. Drop only the reads that could have moved.
      for (const k of [...getCache.keys()]) if (!isPersistablePath(k)) getCache.delete(k);
      return performApiFetch(path, options);
    }
    flushForWrite();
    try {
      return await performApiFetch(path, options);
    } finally {
      // A GET issued while the write was in flight may have read the
      // pre-write state; whatever it left behind is dropped again now that
      // the server has applied the write.
      flushForWrite();
    }
  }
  if (isDedupableGet(options)) {
    // Only while a boot-time hydration is still in flight (it is cleared once
    // settled): wait for it, bounded, and drop the timer either way so a
    // fast hydration does not leave a 250 ms timeout behind every read.
    const pending = hydration;
    if (pending !== null) {
      let timer: ReturnType<typeof setTimeout> | undefined;
      await Promise.race([
        pending,
        new Promise<void>((r) => {
          timer = setTimeout(r, HYDRATION_WAIT_MS);
        }),
      ]);
      clearTimeout(timer);
    }
    const cached = getCache.get(path);
    if (cached) {
      const age = Date.now() - cached.fetchedAt;
      if (age <= freshTtlMs(path)) {
        return responseFromShared(cached.data);
      }
      if (age <= (cached.maxStaleMs ?? GET_CACHE_STALE_MS)) {
        // Stale-while-revalidate: the caller renders last-known data with
        // zero wait; the deduped refresh lands in the cache for the next
        // read. Explicit settle handler - nobody awaits this refresh, so a
        // rejection must be swallowed here, not surface as unhandled.
        refreshGet(path, options).then(
          () => {},
          () => {}
        );
        return responseFromShared(cached.data);
      }
      getCache.delete(path);
    }
    return refreshGet(path, options).then(responseFromShared);
  }
  return performApiFetch(path, options);
}

// One network fetch behind the in-flight dedupe: concurrent callers share
// the same promise (each still gets its own Response via responseFromShared
// at the call site), a success lands in the cache, a 401 flushes it.
function refreshGet(path: string, options: ApiFetchOptions): Promise<SharedGet> {
  const inflight = inflightGets.get(path);
  if (inflight != null) {
    return inflight;
  }
  const startEpoch = cacheEpoch;
  const promise = shareableGet(path, options);
  inflightGets.set(path, promise);
  // .then(clear, clear) rather than .finally: a discarded finally() chain
  // would surface an unhandled rejection on top of the caller's own.
  // Only remove our own entry: a write may have cleared the map and a newer
  // GET registered under the same path since.
  const clear = () => {
    if (inflightGets.get(path) === promise) inflightGets.delete(path);
  };
  promise.then(clear, clear);
  promise.then(
    (s) => {
      // A flush (write, sign-out) since this GET started means its answer may
      // predate that change: hand it to its own callers but do not keep it.
      if (s.ok && s.body != null && startEpoch === cacheEpoch) {
        if (getCache.size >= GET_CACHE_MAX_ENTRIES) {
          getCache.delete(getCache.keys().next().value);
        }
        getCache.set(path, { data: s, fetchedAt: Date.now() });
        persistGet(path, s);
      }
      if (s.status === 401) {
        getCache.clear();
        cacheEpoch++;
        void clearPersisted();
      }
      // Explicit rejection handler on this discarded branch - a failed
      // shareableGet must not surface as a second, unhandled rejection on
      // top of the one the real caller already gets.
    },
    () => {}
  );
  return promise;
}

async function performApiFetch(path: string, options: ApiFetchOptions = {}): Promise<Response> {
  const res = await apiFetchOnce(path, options);
  if (res.status !== 401) return res;
  if (!isReplayableBody(options?.body)) return res;
  const refreshed = await auth.refreshToken();
  if (!refreshed) return res;
  return apiFetchOnce(path, options);
}
