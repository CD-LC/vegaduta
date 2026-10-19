// The conversation: one chat turn, the durable transcript, and the transcript email. Never
// rejects; see ../demoClient.ts.

import { API_BASE, readJson, timeoutSignal } from "./demoHttp";

export type DemoChatResult = {
  reply: string;
  sessionId: string;
  // True when `reply` is a canned sample rather than model output. The UI
  // MUST label it - see DemoChatController's javadoc. Presenting a fallback
  // as a real answer would be a fabricated demo, which is precisely what this
  // whole feature exists to replace.
  simulated: boolean;
  messagesUsed: number;
  messageLimit: number;
  daysRemaining: number;
  expired: boolean;
  // True when the turn was refused because the sandbox is past its editable
  // window — the reply is the server's own view-only nudge and the UI should
  // flip the composer into the read-only state. Optional (older backends).
  readOnly?: boolean;
};

/**
 * Sends one turn. NEVER rejects and never returns an error shape - a network
 * failure resolves to a `simulated: true` result, exactly like the server's
 * own fallback, so the UI has one code path and no error branch at all.
 */
export async function sendMessage(
  token: string,
  message: string,
  sessionId: string | null,
  businessName?: string
): Promise<DemoChatResult> {
  const offline: DemoChatResult = {
    reply:
      "(Sample response - we couldn't reach the live model just now.)\n\n" +
      "This agent is set up to reply to customers in their own language, draft messages and product " +
      "descriptions, and translate between Indian languages. Try again in a moment, or create a free " +
      "account for your own workspace.",
    sessionId: sessionId ?? "",
    simulated: true,
    messagesUsed: 0,
    messageLimit: 0,
    daysRemaining: 0,
    expired: false,
  };
  try {
    const res = await fetch(`${API_BASE}/api/demo/chat`, {
      method: "POST",
      headers: { "Content-Type": "application/json", "X-Demo-Token": token },
      body: JSON.stringify({ message, sessionId, businessName }),
    });
    const body = await readJson(res);
    if (!body || typeof body.reply !== "string") return offline;
    return body as DemoChatResult;
  } catch {
    return offline;
  }
}

// --- durable transcript ------------------------------------------------------

export type HistoryMessage = {
  direction: "IN" | "OUT";
  text: string;
  simulated: boolean;
  at: string;
};

/**
 * GET /api/demo/history — the server-side transcript (migration V146). What
 * lets a refreshed tab (or a day-3 read-only visit) re-show the conversation
 * instead of losing it with the React state. Never rejects: any failure (or
 * an older backend without the endpoint) resolves to an empty list and the
 * page simply starts from the welcome message as before.
 */
export async function fetchHistory(token: string): Promise<HistoryMessage[]> {
  try {
    const res = await fetch(`${API_BASE}/api/demo/history`, {
      headers: { "X-Demo-Token": token },
      signal: timeoutSignal(15_000),
    });
    if (!res.ok) return [];
    const body = await readJson(res);
    if (!body || !Array.isArray(body.messages)) return [];
    return body.messages
      .filter((m: any) => m && typeof m.text === "string" && (m.direction === "IN" || m.direction === "OUT"))
      .map((m: any) => ({
        direction: m.direction,
        text: m.text,
        simulated: m.simulated === true,
        at: typeof m.at === "string" ? m.at : "",
      }));
  } catch {
    return [];
  }
}

// --- transcript email --------------------------------------------------------

export type TranscriptEmailResult = {
  sent: boolean;
  // Always a human sentence — the server's own on both success and the
  // 2-per-sandbox limit, or a soft local one when the endpoint is unreachable
  // (or does not exist yet on an older backend). The UI renders this string
  // verbatim; there is deliberately no separate error shape.
  message: string;
};

/**
 * POST /api/demo/transcript-email — mails the visitor their conversation
 * (max 2 sends per sandbox, enforced server-side). Like everything else in
 * this module it never rejects: a 404 from a backend that predates the
 * endpoint, a network blip and a server-side limit all resolve to the same
 * `{sent, message}` shape.
 */
export async function emailTranscript(
  token: string,
  email: string,
  // The web chat's server-side history lives in Redis keyed by the same
  // client-generated sessionId /api/demo/chat echoes back (see core's
  // DemoTranscriptEmailService javadoc). Without it the backend can only
  // include the Telegram/preview mirror, so the email would omit the very
  // conversation the visitor is looking at. Optional: the email capture
  // itself still works without it.
  sessionId?: string | null
): Promise<TranscriptEmailResult> {
  const fallback: TranscriptEmailResult = {
    sent: false,
    message: "We couldn't send the email just now — your conversation is still right here on this page.",
  };
  try {
    const res = await fetch(`${API_BASE}/api/demo/transcript-email`, {
      method: "POST",
      headers: { "Content-Type": "application/json", "X-Demo-Token": token },
      body: JSON.stringify({ email, ...(sessionId ? { sessionId } : {}) }),
    });
    const body = await readJson(res);
    // Trust the shape, not the status: the limit response is still
    // {sent:false, message}, and rendering the server's own sentence beats
    // inventing one from a status code.
    if (!body || typeof body.sent !== "boolean") return fallback;
    return {
      sent: body.sent,
      message:
        typeof body.message === "string" && body.message
          ? body.message
          : body.sent
            ? "Sent — check your inbox."
            : fallback.message,
    };
  } catch {
    return fallback;
  }
}
