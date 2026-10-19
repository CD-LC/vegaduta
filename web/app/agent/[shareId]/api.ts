// Client for the PUBLIC agent-share surface (/api/share/agents/{shareId}).
//
// Same separation as /a/[shareId]/api.ts (the demo-sandbox share client):
// a visitor here has no session, no auth, nothing but the shareId in the
// URL, which is the only credential these endpoints take. Nothing in this
// module ever throws - each call resolves to a typed result the page
// renders as honest, labelled content.

import { voiceBlobExtension } from "../../lib/api";
import { publicApiBase } from "../../lib/env";
import { fetchWithRetry, fetchWithTimeout } from "../../lib/http/fetchWithRetry";
import { adoptShareSession, clearShareSession, getShareSession, shareSessionHeaders } from "./shareSession";

const API_BASE = publicApiBase();

/** The backend's failure vocabulary, plus "unavailable" for everything else (network down, unknown code, malformed body). Never a raw status code. */
export type ShareErrorCode = "rate_limited" | "expired" | "quota_exhausted" | "unavailable";

export type SharedAgentInfo = {
  agentName: string;
  // White-label branding (audit item C2): the OWNING tenant's own brand, or
  // null for platform (VegaDūta) branding. The backend only sends these while
  // the owner's plan actually includes white-label (the read is
  // entitlement-gated in core), so the page can trust presence = "render the
  // tenant's brand, drop VegaDūta's".
  brandDisplayName: string | null;
  brandLogoUrl: string | null;
  // Mic/camera call UI gates (AgentShareController#meta) - server-resolved
  // from the agent's own configured provider/model, same lookup
  // AgentPlayground.tsx does client-side for the authenticated console.
  // false/false for a model the backend can't resolve (never guessed true).
  supportsAudioInput: boolean;
  supportsImageInput: boolean;
};

/** Defense-in-depth for a URL we render as an <img src> on a public page:
 *  only accept well-formed absolute https URLs, even though the backend
 *  already validated this at write time. */
function safeLogoUrl(value: unknown): string | null {
  if (typeof value !== "string" || !value) return null;
  try {
    return new URL(value).protocol === "https:" ? value : null;
  } catch {
    return null;
  }
}

export type ShareLoadResult = {
  ok: boolean;
  info?: SharedAgentInfo;
  error?: ShareErrorCode;
};

export type ShareChatResult = {
  ok: boolean;
  reply?: string;
  error?: ShareErrorCode;
};

async function readJson(res: Response): Promise<any> {
  try {
    return await res.json();
  } catch {
    return null;
  }
}

/** 410 always means expired/revoked; 429 always means rate-limited; 402 means quota exhausted; every other non-2xx (or a malformed body) is the generic "unavailable". */
function normalizeError(status: number): ShareErrorCode {
  if (status === 410 || status === 404) return "expired";
  if (status === 429) return "rate_limited";
  if (status === 402) return "quota_exhausted";
  return "unavailable";
}

/** GET /api/share/agents/{shareId} - who this shared agent is. */
export async function fetchSharedAgent(shareId: string): Promise<ShareLoadResult> {
  try {
    const res = await fetchWithRetry(`${API_BASE}/api/share/agents/${encodeURIComponent(shareId)}`, {}, 8000, 2);
    if (!res.ok) return { ok: false, error: normalizeError(res.status) };
    const body = await readJson(res);
    if (!body || typeof body.agentName !== "string") return { ok: false, error: "unavailable" };
    return {
      ok: true,
      info: {
        agentName: body.agentName,
        brandDisplayName:
          typeof body.brandDisplayName === "string" && body.brandDisplayName ? body.brandDisplayName : null,
        brandLogoUrl: safeLogoUrl(body.brandLogoUrl),
        supportsAudioInput: body.supportsAudioInput === true,
        supportsImageInput: body.supportsImageInput === true,
      },
    };
  } catch (error) {
    console.error("Failed to fetch shared agent:", error);
    return { ok: false, error: "unavailable" };
  }
}

/** POST /api/share/agents/{shareId}/chat {message, sessionToken} -> {reply, sessionToken}. Input is trimmed here so every caller gets the same guarantee.
 *  The visitor token core issues (SEC-005) is persisted per share and sent on every later turn; a 401/409 means it was rejected, so it is dropped and the turn retried once with a fresh visitor. */
export async function sendSharedChat(shareId: string, message: string): Promise<ShareChatResult> {
  const trimmed = message.trim();
  if (!trimmed) return { ok: false, error: "unavailable" };
  try {
    for (let attempt = 0; attempt < 2; attempt++) {
      const token = getShareSession(shareId);
      const res = await fetchWithRetry(
        `${API_BASE}/api/share/agents/${encodeURIComponent(shareId)}/chat`,
        {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify(token ? { message: trimmed, sessionToken: token } : { message: trimmed }),
        },
        50000,
        1
      );
      if ((res.status === 401 || res.status === 409) && token && attempt === 0) {
        clearShareSession(shareId);
        continue;
      }
      if (!res.ok) return { ok: false, error: normalizeError(res.status) };
      const body = await readJson(res);
      if (!body || typeof body.reply !== "string") return { ok: false, error: "unavailable" };
      adoptShareSession(shareId, res, body.sessionToken);
      return { ok: true, reply: body.reply };
    }
    return { ok: false, error: "unavailable" };
  } catch (error) {
    console.error("Failed to send shared chat:", error);
    return { ok: false, error: "unavailable" };
  }
}

/** POST /api/share/agents/{shareId}/report {reason}. Resolves true when the report was accepted; a failure still resolves (false) - the page thanks the reporter either way. */
export async function reportSharedAgent(shareId: string, reason: string): Promise<boolean> {
  try {
    const res = await fetchWithTimeout(
      `${API_BASE}/api/share/agents/${encodeURIComponent(shareId)}/report`,
      {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ reason: reason.trim() }),
      },
      5000
    );
    return res.ok;
  } catch (error) {
    console.error("Failed to report shared agent:", error);
    return false;
  }
}

// ---------------------------------------------------------------------------
// Voice/video call surface (AgentShareVoiceController, core/voice) - the
// anonymous counterpart of api.ts's getVoiceToken/sendVoiceTurnStream. Same
// "no session, shareId is the only credential" posture as every function
// above: no Authorization header anywhere below.
// ---------------------------------------------------------------------------

export type ShareVoiceToken = { token: string; url: string; roomName: string; identity: string };

export type ShareVoiceTokenResult = { ok: boolean; voice?: ShareVoiceToken; error?: ShareErrorCode };

/** POST /api/share/agents/{shareId}/voice/token - mints a share-scoped LiveKit
 * token for the live "Call" button. `mode` mirrors api.ts's getVoiceToken:
 * omitted (or "voice") is a plain voice-only room; "sight" additionally
 * requests the camera-narrating room (see AgentShareVoiceController#token's
 * javadoc for the mode vocabulary). */
export async function getShareVoiceToken(shareId: string, mode?: "voice" | "sight"): Promise<ShareVoiceTokenResult> {
  try {
    const res = await fetchWithTimeout(
      `${API_BASE}/api/share/agents/${encodeURIComponent(shareId)}/voice/token`,
      {
        method: "POST",
        headers: { "Content-Type": "application/json", ...shareSessionHeaders(shareId) },
        body: JSON.stringify(mode ? { mode } : {}),
      },
      10000
    );
    if (!res.ok) return { ok: false, error: normalizeError(res.status) };
    adoptShareSession(shareId, res);
    const body = await readJson(res);
    if (!body || typeof body.token !== "string" || typeof body.url !== "string") {
      return { ok: false, error: "unavailable" };
    }
    return {
      ok: true,
      voice: { token: body.token, url: body.url, roomName: body.roomName, identity: body.identity },
    };
  } catch (error) {
    console.error("Failed to get share voice token:", error);
    return { ok: false, error: "unavailable" };
  }
}

/** Push-to-talk turn callbacks - same shape as api.ts's sendVoiceTurnStream
 * (the backend endpoint literally returns the same
 * VoiceCallController#streamVoiceTurn SSE stream), minus the authenticated
 * client's idle/absolute-timeout retry wrapper - this surface is a smaller,
 * single-attempt read, matching this whole module's "one dedicated, simpler
 * client" posture rather than pulling in requestLifecycle.ts's policy. */
export type ShareVoiceTurnCallbacks = {
  onTranscript?: (payload: { transcript: string; method: string }) => void;
  onReplyDelta?: (text: string) => void;
  onAudioChunk?: (payload: { seq: number; textFragment: string; audioBase64: string; method: string }) => void;
  onDone?: (payload: {
    sessionId: string;
    transcript: string;
    replyText: string;
    speechText: string;
    audioBase64: string;
  }) => void;
  onError?: (message: string) => void;
};

/** POST /api/share/agents/{shareId}/voice/turn - the anonymous push-to-talk
 * turn (AgentShareVoiceController#turn). Named SSE events (event:/data:
 * framing), parsed identically to api.ts's sendVoiceTurnStream: consecutive
 * `data:` lines within one event join with "\n", never treated as separate
 * events (a reply-delta token can legitimately contain an embedded
 * newline). */
export async function sendSharedVoiceTurn(
  shareId: string,
  audioBlob: Blob,
  sessionId: string | null | undefined,
  callbacks: ShareVoiceTurnCallbacks
): Promise<void> {
  const formData = new FormData();
  const ext = `turn.${voiceBlobExtension(audioBlob.type)}`;
  formData.append("audio", audioBlob, ext);
  const visitorToken = sessionId || getShareSession(shareId);
  if (visitorToken) formData.append("sessionId", visitorToken);

  let res: Response;
  try {
    res = await fetchWithTimeout(
      `${API_BASE}/api/share/agents/${encodeURIComponent(shareId)}/voice/turn`,
      { method: "POST", headers: shareSessionHeaders(shareId), body: formData },
      45000
    );
  } catch (error) {
    console.error("Failed to send shared voice turn:", error);
    callbacks.onError?.("Couldn't reach the agent - please try again.");
    return;
  }
  if (!res.ok || !res.body) {
    const body = await readJson(res);
    callbacks.onError?.(
      (body && typeof body.error === "string" && body.error) || "That turn failed - please try again."
    );
    return;
  }

  const reader = res.body.getReader();
  const decoder = new TextDecoder();
  let buffer = "";
  let eventType = "message";
  let dataLines: string[] = [];

  const dispatch = () => {
    if (dataLines.length === 0) return;
    const data = dataLines.join("\n");
    dataLines = [];
    const type = eventType;
    eventType = "message";
    try {
      switch (type) {
        case "transcript":
          callbacks.onTranscript?.(JSON.parse(data));
          break;
        case "reply-delta":
          callbacks.onReplyDelta?.(data);
          break;
        case "audio-chunk":
          callbacks.onAudioChunk?.(JSON.parse(data));
          break;
        case "done":
          {
            const done = JSON.parse(data);
            // The done event echoes the signed visitor token as sessionId (SEC-005).
            if (done && typeof done.sessionId === "string") adoptShareSession(shareId, null, done.sessionId);
            callbacks.onDone?.(done);
          }
          break;
        case "error":
          callbacks.onError?.(data);
          break;
        default:
          break;
      }
    } catch (parseError) {
      console.error(`Failed to parse ${type} event:`, parseError);
    }
  };

  try {
    for (;;) {
      const { done, value } = await reader.read();
      if (done) break;
      buffer += decoder.decode(value, { stream: true });
      const lines = buffer.split("\n");
      buffer = lines.pop() ?? "";
      for (const rawLine of lines) {
        const line = rawLine.replace(/\r$/, "");
        if (line === "") {
          dispatch();
        } else if (line.startsWith("event:")) {
          eventType = line.slice(6).trim();
        } else if (line.startsWith("data:")) {
          dataLines.push(line.slice(5));
        }
      }
    }
    dispatch();
  } catch (error) {
    console.error("Shared voice turn stream failed:", error);
    callbacks.onError?.("The voice reply stream failed.");
  }
}
