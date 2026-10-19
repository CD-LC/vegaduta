// The network layer of the third-party lookup tools (split out of
// localToolsWeb.ts, which re-exports the public parts): budgets and the host
// allowlist, the failure vocabulary, the deadline, the single requestJson
// chokepoint every request goes through, and the shared argument/text helpers.

// ---------------------------------------------------------------------------
// Budgets
// ---------------------------------------------------------------------------

/**
 * Wall-clock ceiling for ONE tool call, every request it makes included.
 *
 * Derived from the measurements in the header: the slowest single hop measured
 * was 1.16 s and the slowest sequence (geocode → forecast) 2.1 s, on a machine
 * with a working connection. A ceiling has to leave room for the case this
 * number exists for — a phone on a bad train connection — without leaving the
 * model waiting on something that is never going to answer. 8 s is ~3.8x the
 * measured worst sequence.
 */
export const NETWORK_TOOL_BUDGET_MS = 8_000;

/**
 * Ceiling on any single request. A tool that makes two requests gets at most
 * this each AND at most NETWORK_TOOL_BUDGET_MS overall, whichever binds first,
 * so one stalled hop cannot eat the whole budget and starve the next.
 */
export const PER_REQUEST_TIMEOUT_MS = 4_000;

/**
 * Ceiling on a response body, enforced two ways (see readCappedBody): refused
 * from `content-length` before a byte is read when the server declares one, and
 * enforced again while streaming for servers that do not — api.open-meteo.com
 * answers `Transfer-Encoding: chunked` with no content-length (measured
 * 2026-09-12), so the streaming half is not hypothetical.
 *
 * 64_000 is ~26x the largest body any of these endpoints actually returned in
 * testing (the Wikipedia summary for "Ada Lovelace", content-length 2469).
 */
export const MAX_RESPONSE_BYTES = 64_000;

/** Longest query string these tools will send to a third party. Short on
 * purpose: a lookup term is a few words, and a cap is the crudest but most
 * reliable guard against a model pasting a conversation into a URL. */
const MAX_QUERY_CHARS = 200;

/** Longest prose block copied out of a response into the result. The runner
 * caps the whole result at MAX_RESULT_CHARS (2000, read 2026-09-12), so leaving
 * headroom here is what keeps the title, the URL and the disclosure line from
 * being the part that gets truncated away. */
const MAX_EXTRACT_CHARS = 700;

/**
 * Every host this module is permitted to contact, in one place.
 *
 * This is the machine-readable form of the `network` field, and the test
 * asserts that requestJson refuses any URL whose host is not in it — so a later
 * edit that adds an endpoint has to add it here too, where the CSP note and the
 * verification record live. It is an exact-match list: no wildcards, no suffix
 * matching, because "*.open-meteo.com" would quietly permit hosts nobody
 * checked.
 */
export const ALLOWED_HOSTS: readonly string[] = [
  "en.wikipedia.org",
  "geocoding-api.open-meteo.com",
  "api.open-meteo.com",
  "api.frankfurter.dev",
];

// ---------------------------------------------------------------------------
// Failure vocabulary
//
// runLocalTool maps a thrown LocalToolRefusal to code "rejected" and anything
// else to code "failed". LocalToolRefusal is not exported from localTools.ts,
// so every failure raised here arrives as "failed". That is honest but blunt —
// "arguments" and "offline" deserve different advice — so the DISTINCTION is
// carried in two places that do survive: `kind` (for this module's own tests
// and any future caller) and the message itself, which always ends with a
// sentence saying whether trying again could help. needsOtherFiles asks for
// LocalToolRefusal to be exported so the argument cases can become "rejected".
// ---------------------------------------------------------------------------

export type WebLookupFailureKind =
  /** The model's arguments were wrong. Retrying the same call will not help. */
  | "arguments"
  /** The request never reached the host: offline, DNS, CSP, an extension. */
  | "offline"
  /** The host answered, with an error status. */
  | "http"
  /** The host answered 404 for a thing that does not exist. */
  | "not_found"
  /** The host asked us to slow down (429). */
  | "rate_limited"
  /** Our own deadline fired first. */
  | "timeout"
  /** The body was not the shape this tool knows how to read. */
  | "malformed"
  /** The body was larger than MAX_RESPONSE_BYTES. */
  | "too_large";

export class WebLookupError extends Error {
  readonly kind: WebLookupFailureKind;
  constructor(kind: WebLookupFailureKind, message: string) {
    super(message);
    this.name = "WebLookupError";
    this.kind = kind;
  }
}

export function fail(kind: WebLookupFailureKind, message: string): never {
  throw new WebLookupError(kind, message);
}

// ---------------------------------------------------------------------------
// Deadline
// ---------------------------------------------------------------------------

export interface Deadline {
  /** Milliseconds left in the whole tool call; never negative. */
  remaining(): number;
}

export function startDeadline(budgetMs: number): Deadline {
  const startedAt = Date.now();
  return {
    remaining: (): number => {
      const left = budgetMs - (Date.now() - startedAt);
      return left > 0 ? left : 0;
    },
  };
}

// ---------------------------------------------------------------------------
// The single network chokepoint
//
// EVERY request this module makes goes through requestJson. That is not a
// tidiness preference: it is what makes "no credentials", "allowlisted host
// only", "no redirect off that host", "bounded body" and "bounded time" one
// fact each instead of three copies each, and it is what the test can point at
// to prove no tool has its own private fetch.
// ---------------------------------------------------------------------------

interface JsonRequest {
  /** Absolute https URL. Its host must be in ALLOWED_HOSTS. */
  url: string;
  deadline: Deadline;
  /** Human name of the third party, for failure sentences the model relays. */
  who: string;
  /** What a 404 means for this particular call, in a relayable sentence. */
  notFoundMessage?: string;
}

/** True when `value` is a plain JSON object (not null, not an array). */
export function isObject(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

/**
 * Read a response body without letting it grow unbounded.
 *
 * Two enforcement points, because neither alone covers the traffic these tools
 * actually see: `content-length` is the cheap one and rejects before a byte is
 * read, but api.open-meteo.com sends chunked responses with no such header
 * (measured 2026-09-12), so the stream is read with a running total and
 * cancelled the moment it crosses the cap. The `res.text()` branch is the
 * fallback for a Response implementation with no readable `body` — some test
 * doubles, and older browsers — and there the cap can only be applied after the
 * text resolves, which bounds what enters the model's context but not the bytes
 * over the wire. The overall deadline is what bounds those.
 */
async function readCappedBody(res: Response, who: string): Promise<string> {
  const headers: unknown = (res as unknown as { headers?: unknown }).headers;
  if (isObject(headers) && typeof (headers as { get?: unknown }).get === "function") {
    const declared = (headers as { get: (name: string) => string | null }).get("content-length");
    if (typeof declared === "string" && /^\d+$/.test(declared) && Number(declared) > MAX_RESPONSE_BYTES) {
      fail(
        "too_large",
        `${who} announced a ${declared}-byte response, over this tool's ${MAX_RESPONSE_BYTES}-byte limit, so nothing was downloaded.`
      );
    }
  }

  const body: unknown = (res as unknown as { body?: unknown }).body;
  const reader =
    isObject(body) && typeof (body as { getReader?: unknown }).getReader === "function"
      ? (body as { getReader: () => ReadableStreamDefaultReader<Uint8Array> }).getReader()
      : null;

  if (reader === null) {
    const text = await res.text();
    if (typeof text !== "string") {
      fail("malformed", `${who} returned something this tool could not read as text.`);
    }
    if (text.length > MAX_RESPONSE_BYTES) {
      fail("too_large", `${who} returned more than ${MAX_RESPONSE_BYTES} characters, so the reply was discarded.`);
    }
    return text;
  }

  const chunks: Uint8Array[] = [];
  let total = 0;
  for (;;) {
    const step = await reader.read();
    if (step.done === true) break;
    const value = step.value;
    if (!value) continue;
    total += value.length;
    if (total > MAX_RESPONSE_BYTES) {
      // cancel() stops the transfer rather than letting the rest arrive and be
      // thrown away. Its own rejection is not interesting — we are already
      // failing — so it is swallowed deliberately.
      try {
        await reader.cancel();
      } catch {
        /* the stream was already gone */
      }
      fail("too_large", `${who} returned more than ${MAX_RESPONSE_BYTES} bytes, so the reply was discarded.`);
    }
    chunks.push(value);
  }
  const joined = new Uint8Array(total);
  let at = 0;
  for (const chunk of chunks) {
    joined.set(chunk, at);
    at += chunk.length;
  }
  return new TextDecoder("utf-8").decode(joined);
}

/**
 * The only place this module calls fetch.
 *
 * The request options, and what each one is actually for:
 *
 *   credentials: "omit"  — THIS is the option that enforces "no credentials".
 *     It is what stops the browser attaching cookies for the target host (and
 *     any HTTP-auth or TLS client cert) to the request, and stops it storing a
 *     Set-Cookie from the reply. Without it the default for a cross-origin
 *     fetch is "same-origin", which also sends nothing — but that is a default
 *     someone can change by accident, and this is a promise in a description.
 *   mode: "cors"         — states the requirement rather than discovering it:
 *     a host that drops its CORS headers fails loudly here instead of silently
 *     yielding an opaque, unreadable response.
 *   redirect: "error"    — a redirect would carry the person's query to a host
 *     nobody in this file checked, and (while the CSP is report-only — see
 *     next.config.js) nothing else would stop it. All four endpoints were
 *     verified on 2026-09-12 to answer 200 directly with no 3xx on the paths
 *     these tools use, so this costs nothing and closes that hole now rather
 *     than on CSP-promotion day.
 *   referrerPolicy: "no-referrer" — the page URL is not the third party's
 *     business. This is stricter than the browser default, which would send the
 *     origin.
 *   cache: "no-store"    — a lookup answer is not worth leaving in the HTTP
 *     cache, where it outlives the conversation that asked for it.
 *   headers: none        — nothing is added. No Authorization, no session id,
 *     no client identifier of any kind. The one thing that does ride along is
 *     the `Origin` header, which the browser adds to every cross-origin request
 *     and no page can remove; it is the origin only, not the URL, and it is
 *     also what makes the CORS check work at all.
 *
 * The query itself is the only thing this module transmits.
 */
export async function requestJson(request: JsonRequest): Promise<unknown> {
  const { url, deadline, who } = request;

  let host: string;
  try {
    const parsed = new URL(url);
    if (parsed.protocol !== "https:") {
      fail("arguments", `This tool only makes https requests (got ${parsed.protocol}).`);
    }
    host = parsed.hostname;
  } catch (err) {
    if (err instanceof WebLookupError) throw err;
    fail("arguments", `"${url}" is not a URL this tool can request.`);
  }
  if (!ALLOWED_HOSTS.includes(host)) {
    // Unreachable from the shipped tools — every URL below is built from a
    // literal host — and kept as the structural guarantee that a later edit
    // cannot add a fourth destination without appearing in ALLOWED_HOSTS.
    fail("arguments", `${host} is not one of the hosts this tool is allowed to contact.`);
  }

  if (typeof fetch !== "function") {
    fail("offline", "This browser does not give the page a way to make network requests, so the lookup could not run.");
  }

  const left = deadline.remaining();
  if (left <= 0) {
    fail("timeout", `The lookup ran out of its ${NETWORK_TOOL_BUDGET_MS / 1000}-second budget before reaching ${who}.`);
  }
  const waitMs = left < PER_REQUEST_TIMEOUT_MS ? left : PER_REQUEST_TIMEOUT_MS;

  // AbortController rather than AbortSignal.timeout: the latter is missing from
  // this repo's DOM typings, and an explicit controller is also what lets the
  // timer be cleared on the fast path instead of holding the event loop open.
  const controller = new AbortController();
  let timedOut = false;
  const timer = setTimeout(() => {
    timedOut = true;
    controller.abort();
  }, waitMs);

  // The timer is NOT cleared when fetch() resolves. fetch resolves at the
  // RESPONSE HEADERS; the body is still arriving, and a body read is exactly
  // where a slow or stalled connection hurts. Clearing it there would have left
  // the read phase unbounded — so the timer lives until the body is in hand,
  // and `controller.abort()` aborts the in-flight body too.
  let res: Response;
  try {
    res = await fetch(url, {
      method: "GET",
      credentials: "omit",
      mode: "cors",
      redirect: "error",
      referrerPolicy: "no-referrer",
      cache: "no-store",
      signal: controller.signal,
    });
  } catch (err) {
    if (timedOut) {
      fail(
        "timeout",
        `${who} did not answer within ${Math.round(waitMs / 100) / 10} seconds. Trying again later may work.`
      );
    }
    // A cross-origin fetch failure is deliberately indistinguishable in the
    // browser — offline, DNS, a blocked redirect, a CSP refusal and an
    // ad-blocker all surface as the same TypeError, and the page is not told
    // which. Saying so is more useful than guessing one of them.
    fail(
      "offline",
      `Could not reach ${who}. That usually means this device is offline or something on the network is blocking it; the query was not answered.`
    );
  }

  const status = typeof res.status === "number" ? res.status : 0;
  if (status === 404) {
    fail("not_found", request.notFoundMessage || `${who} has nothing matching that.`);
  }
  if (status === 429) {
    fail(
      "rate_limited",
      `${who} is asking for fewer requests right now (429). Waiting a minute and trying again usually clears it.`
    );
  }
  if (status < 200 || status > 299) {
    fail(
      "http",
      `${who} answered with an error (HTTP ${status}), so the lookup has no result. This is on their side, not this device's.`
    );
  }

  // Everything from here to the parsed body runs under the same deadline, and
  // every failure in it is typed. A connection dropped mid-body is the ordinary
  // mobile case, not an exceptional one: un-wrapped, reader.read()/res.text()
  // would throw a raw TypeError straight past this module's typed-failure
  // contract and surface as a defect on our side rather than a network fact.
  let text: string;
  try {
    text = await readCappedBody(res, who);
  } catch (err) {
    if (timedOut) {
      fail(
        "timeout",
        `${who} started answering but the response did not finish within ${Math.round(waitMs / 100) / 10} seconds.`
      );
    }
    // readCappedBody raises its own typed failure for an over-cap body; that
    // is a decision, not a transport error, so it passes through unchanged.
    if (err instanceof WebLookupError) throw err;
    fail("offline", `The connection to ${who} dropped while its answer was still arriving, so there is no result.`);
  } finally {
    clearTimeout(timer);
  }

  try {
    return JSON.parse(text);
  } catch {
    fail(
      "malformed",
      `${who} answered with something that is not the data this tool expects, so there is no result to report.`
    );
  }
}

// ---------------------------------------------------------------------------
// Shared argument handling
// ---------------------------------------------------------------------------

export function requireQuery(args: Record<string, unknown>, key: string): string {
  const raw = args[key];
  if (typeof raw !== "string") {
    fail("arguments", `"${key}" must be a string (got ${raw === null ? "null" : typeof raw}).`);
  }
  const trimmed = raw.trim();
  if (!trimmed) {
    fail("arguments", `"${key}" is empty; give the words to look up.`);
  }
  if (trimmed.length > MAX_QUERY_CHARS) {
    fail(
      "arguments",
      `"${key}" is ${trimmed.length} characters; this tool sends at most ${MAX_QUERY_CHARS} to a third party.`
    );
  }
  return trimmed;
}

export function trimProse(text: string): string {
  if (text.length <= MAX_EXTRACT_CHARS) return text;
  return `${text.slice(0, MAX_EXTRACT_CHARS)}… [summary shortened here, not by the source]`;
}

export function asNumber(value: unknown): number | null {
  return typeof value === "number" && Number.isFinite(value) ? value : null;
}

export function asString(value: unknown): string | null {
  return typeof value === "string" && value ? value : null;
}

/** One line, appended to every successful result. The description tells the
 * model before the call; this tells the person after it, in the text the model
 * is summarising, so the disclosure survives a model that paraphrases. */
export function sourceLine(who: string, host: string): string {
  return `[looked up live from ${who} (${host}); your query was sent there]`;
}
