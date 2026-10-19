import type { Page } from "@playwright/test";
import { API_URL } from "./prod-env";

/**
 * Bearer-token + SSE helpers for API-level production specs.
 *
 * The console keeps its Keycloak tokens in MEMORY only (web/app/lib/auth.ts
 * just removes the legacy sessionStorage["vd_kc_session"]), so there is
 * nothing to read from storage. The credential is captured instead from the
 * Authorization header of the app's own requests to api.<domain>:
 * trackBearerToken(page) registers the listener (signInToConsole does it
 * before navigating) and getBearerToken(page) returns the newest token seen.
 * It is the same credential the app's apiFetch uses, which unlocks
 * contract-level assertions (response shape, error status codes, SSE
 * framing) that a DOM-only test cannot make.
 *
 * The token is used in-process only (Authorization header on requests this
 * spec itself makes) - it is never printed, stored, or sent anywhere but
 * api.<domain>.
 */
const bearerByPage = new WeakMap<Page, string>();
const trackedPages = new WeakSet<Page>();

/** Start capturing the app's Authorization header on this page. Idempotent; call BEFORE navigating. */
export function trackBearerToken(page: Page): void {
  if (trackedPages.has(page)) return;
  trackedPages.add(page);
  page.on("request", (req) => {
    if (!req.url().startsWith(API_URL)) return;
    const auth = req.headers()["authorization"];
    // Keep the NEWEST token: the app refreshes it every few minutes and an old one 401s "Jwt expired".
    if (auth && /^bearer /i.test(auth)) bearerByPage.set(page, auth.replace(/^bearer /i, ""));
  });
}

/** The newest token captured for this page, or null. Does not trigger any request. */
export function peekBearerToken(page: Page): string | null {
  return bearerByPage.get(page) ?? null;
}

export async function getBearerToken(page: Page): Promise<string> {
  const seen = peekBearerToken(page);
  if (seen) return seen;
  // Not tracked yet (page signed in by other means) or the app has not called the API since: attach the
  // listener, then reload so the app's first /api request carries the header.
  const wasTracked = trackedPages.has(page);
  trackBearerToken(page);
  if (!wasTracked) {
    await page.reload({ waitUntil: "domcontentloaded" }).catch(() => {});
  }
  const deadline = Date.now() + 30_000;
  while (Date.now() < deadline) {
    const token = peekBearerToken(page);
    if (token) return token;
    await page.waitForTimeout(250);
  }
  throw new Error(
    `no Authorization header was seen on any request to ${API_URL} after sign-in - the session is not ` +
      "authenticated or the console made no API call (tokens are memory-only, so there is nothing in storage to read)"
  );
}

export function authHeaders(token: string): Record<string, string> {
  return { Authorization: `Bearer ${token}` };
}

/**
 * Chat SSE stream parsing. `POST /api/agents/{id}/chat` emits `data:`-only
 * frames (Spring's writer; no `event:` field). Each frame is either:
 *   - a raw reply-text chunk (possibly split across several `data:` lines -
 *     consecutive lines within one frame are joined with "\n", matching how
 *     web/app/lib/api/chat.ts parses it), or
 *   - `@@AGENT_STEP_EVENT_4d81ba@@` + one JSON line (a tool/step trace), or
 *   - `@@AGENT_CHAT_STREAM_ERROR_9f2b1c@@` + a user-facing failure sentence.
 * Marker constants mirror AgentStepEventEmitter / AgentController - if the
 * backend rotates them this parser should be updated with them.
 */
export const STEP_EVENT_MARKER = "@@AGENT_STEP_EVENT_4d81ba@@";
export const STREAM_ERROR_MARKER = "@@AGENT_CHAT_STREAM_ERROR_9f2b1c@@";

export interface ParsedChatStream {
  /** Concatenated reply text exactly as a consumer would render it. */
  replyText: string;
  /** Decoded step events (tool calls, reasoning steps) - only present when the request asked for stepEvents. */
  stepEvents: Array<Record<string, unknown>>;
  /** In-band stream error sentences, if any. */
  streamErrors: string[];
  /** Every `data:` payload line, verbatim - for marker-leak assertions. */
  rawDataLines: string[];
}

export function parseChatSse(body: string): ParsedChatStream {
  const replyParts: string[] = [];
  const stepEvents: Array<Record<string, unknown>> = [];
  const streamErrors: string[] = [];
  const rawDataLines: string[] = [];

  // SSE events are separated by blank lines; within one event several
  // "data:" lines join with "\n" (a multi-line token is legal framing).
  for (const eventBlock of body.split(/\r?\n\r?\n/)) {
    const dataLines = eventBlock
      .split(/\r?\n/)
      .filter((l) => l.startsWith("data:"))
      // Strip EXACTLY the 5-char prefix; a space after the colon is payload
      // (documented in chat.ts's own parser - a lone " " token is common).
      .map((l) => l.slice(5));
    if (dataLines.length === 0) continue;
    const payload = dataLines.join("\n");
    rawDataLines.push(payload);

    if (payload.startsWith(STEP_EVENT_MARKER)) {
      try {
        stepEvents.push(JSON.parse(payload.slice(STEP_EVENT_MARKER.length)));
      } catch {
        stepEvents.push({ __unparsed: payload.slice(0, 200) });
      }
    } else if (payload.startsWith(STREAM_ERROR_MARKER)) {
      streamErrors.push(payload.slice(STREAM_ERROR_MARKER.length));
    } else {
      replyParts.push(payload);
    }
  }
  return { replyText: replyParts.join(""), stepEvents, streamErrors, rawDataLines };
}

/**
 * AG-UI SSE parsing. `POST /api/agents/{id}/agui/run` and the
 * `GET .../runs/{runId}/events` rejoin endpoint both emit
 * `id: <seq>` + `data: <json>` frames (see AgUiRunController#toSse).
 */
export interface AgUiEvent {
  seq: number | null;
  json: Record<string, unknown>;
  type: string | null;
}

export function parseAgUiSse(body: string): AgUiEvent[] {
  const events: AgUiEvent[] = [];
  for (const block of body.split(/\r?\n\r?\n/)) {
    let seq: number | null = null;
    const dataLines: string[] = [];
    for (const line of block.split(/\r?\n/)) {
      if (line.startsWith("id:")) {
        const n = Number.parseInt(line.slice(3).trim(), 10);
        seq = Number.isNaN(n) ? null : n;
      } else if (line.startsWith("data:")) {
        dataLines.push(line.slice(5).replace(/^ /, ""));
      }
    }
    if (dataLines.length === 0) continue;
    let json: Record<string, unknown> = {};
    try {
      json = JSON.parse(dataLines.join("\n"));
    } catch {
      json = { __unparsed: dataLines.join("\n").slice(0, 300) };
    }
    events.push({ seq, json, type: typeof json.type === "string" ? json.type : null });
  }
  return events;
}

/**
 * Fetch the signed-in tenant's agent list and pick one for specs that need
 * "any real agent". Returns null when the tenant has none (the spec should
 * then skip with that reason - an empty tenant is a valid state, not a
 * product failure).
 */
export async function pickAnyAgent(
  request: { get: (url: string, opts?: object) => Promise<{ ok(): boolean; status(): number; json(): Promise<unknown> }> },
  token: string
): Promise<{ id: string; name?: string } | null> {
  const res = await request.get(`${API_URL}/api/agents`, { headers: authHeaders(token) });
  if (!res.ok()) {
    throw new Error(`GET /api/agents answered ${res.status()} for a signed-in tenant - session or API broken`);
  }
  const agents = (await res.json()) as Array<{ id: string; name?: string }>;
  return Array.isArray(agents) && agents.length > 0 ? agents[0] : null;
}
