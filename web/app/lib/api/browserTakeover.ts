import { apiFetch, toJsonQuietOn, jsonBody } from "./core";

// Browser human takeover (core tools/browseruse BrowserTakeoverController): the person watches the SAME headless
// page the agent is driving and drives it themselves (a captcha, a 2FA prompt, a login), while every agent browser
// tool is refused. The whole surface answers 404 until core's app.browser.takeover.enabled (and browser v2) is on,
// which the capability probe below turns into "render nothing". The takeover token travels in a header, never in a
// URL; what the person types is relayed to the page and is never returned by any of these calls.

export type BrowserTakeoverState =
  | "AGENT_CONTROL"
  | "PAUSED_FOR_USER"
  | "USER_CONTROL"
  | "RESUMING"
  | "EXPIRED"
  | "CANCELLED";

export type BrowserTakeoverView = {
  takeoverId: string;
  state: BrowserTakeoverState;
  /** "agent" when the agent asked (browser_request_user), "user" when the person paused it. */
  requestedBy: "agent" | "user" | null;
  kind: "captcha" | "two_factor" | "login" | "other" | null;
  /** One short sentence written by the agent (shown as text, never markup). */
  reason: string | null;
  /** The site's host only - never a path or query. */
  host: string | null;
  idleRemainingSeconds: number;
  maxRemainingSeconds: number;
  waitRemainingSeconds: number;
};

export type BrowserTakeoverBegin = {
  view: BrowserTakeoverView;
  token: string;
  tokenExpiresInSeconds: number;
  fps: number;
  maxDimension: number;
  maxTypeChars: number;
  inputPerSecond: number;
};

export type BrowserTakeoverInput =
  | { type: "click"; x: number; y: number; frameWidth: number; frameHeight: number }
  | { type: "key"; key: string }
  | { type: "type"; text: string }
  | { type: "scroll"; dx: number; dy: number; x: number; y: number };

export type BrowserTakeoverCapabilities = { enabled: boolean; fps: number; idleTimeoutSeconds: number };

export const TAKEOVER_TOKEN_HEADER = "X-Takeover-Token";

// A switched-off feature answers 404 and a stale/foreign takeover 404/409: raised quietly (no global toast).
const QUIET = [401, 403, 404, 409, 429, 500, 502, 503];
const BASE = "/api/browser/takeover";

export const browserTakeoverApi = {
  getBrowserTakeoverCapabilities: (): Promise<BrowserTakeoverCapabilities> =>
    apiFetch(`${BASE}/capabilities`).then(toJsonQuietOn(QUIET)),

  /** The takeover waiting on or held by me for this agent + chat session; `takeover` is null when there is none. */
  getBrowserTakeoverStatus: (agentId: string, sessionId: string): Promise<{ takeover: BrowserTakeoverView | null }> =>
    apiFetch(`${BASE}/status?agentId=${encodeURIComponent(agentId)}&sessionId=${encodeURIComponent(sessionId)}`, {
      skipGetCache: true,
    }).then(toJsonQuietOn(QUIET)),

  /** "Take over": pause my own session so I can step in. */
  pauseBrowserSession: (agentId: string, sessionId: string, reason?: string): Promise<BrowserTakeoverView> =>
    apiFetch(`${BASE}/pause`, { method: "POST", ...jsonBody({ agentId, sessionId, kind: "other", reason }) }).then(
      toJsonQuietOn(QUIET),
    ),

  beginBrowserTakeover: (takeoverId: string): Promise<BrowserTakeoverBegin> =>
    apiFetch(`${BASE}/${encodeURIComponent(takeoverId)}/begin`, { method: "POST" }).then(toJsonQuietOn(QUIET)),

  refreshBrowserTakeoverToken: (takeoverId: string): Promise<{ token: string; tokenExpiresInSeconds: number }> =>
    apiFetch(`${BASE}/${encodeURIComponent(takeoverId)}/token`, { method: "POST" }).then(toJsonQuietOn(QUIET)),

  handBackBrowserTakeover: (takeoverId: string): Promise<BrowserTakeoverView> =>
    apiFetch(`${BASE}/${encodeURIComponent(takeoverId)}/hand-back`, { method: "POST" }).then(toJsonQuietOn(QUIET)),

  cancelBrowserTakeover: (takeoverId: string): Promise<BrowserTakeoverView> =>
    apiFetch(`${BASE}/${encodeURIComponent(takeoverId)}/cancel`, { method: "POST" }).then(toJsonQuietOn(QUIET)),

  /** Relays one input event. Resolves on 204; rejects with `.status` (403 = renew the token, 429 = slow down). */
  sendBrowserTakeoverInput: async (takeoverId: string, token: string, event: BrowserTakeoverInput): Promise<void> => {
    const res = await apiFetch(`${BASE}/${encodeURIComponent(takeoverId)}/input`, {
      method: "POST",
      headers: { "Content-Type": "application/json", [TAKEOVER_TOKEN_HEADER]: token },
      body: JSON.stringify(event),
    });
    if (!res.ok) {
      const err: Error & { status?: number } = new Error(`takeover input failed (${res.status})`);
      err.status = res.status;
      throw err;
    }
  },

  /** The live view: a text/event-stream of `frame` events and a final `end`. Read it with SseParser. */
  openBrowserTakeoverStream: (takeoverId: string, token: string, signal: AbortSignal): Promise<Response> =>
    apiFetch(`${BASE}/${encodeURIComponent(takeoverId)}/stream`, {
      method: "GET",
      headers: { Accept: "text/event-stream", [TAKEOVER_TOKEN_HEADER]: token },
      cache: "no-store",
      signal,
    }),
};
