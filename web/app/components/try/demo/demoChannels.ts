// Guided-lab analytics, the shareable agent link and the real-messenger bridge
// (/api/demo/channels). Never rejects; see ../demoClient.ts.

import { API_BASE, readJson } from "./demoHttp";

// --- guided-lab analytics ----------------------------------------------------

/**
 * Fire-and-forget funnel beacon for the guided lab (POST
 * /api/demo/session/event). Same session auth as every other call here, and
 * the same philosophy taken one step further: not only does it never throw,
 * it doesn't even report failure — a lost analytics event is nobody's
 * problem, least of all the visitor's.
 */
export function postDemoEvent(token: string, event: "lab_started" | "lab_completed"): void {
  try {
    void fetch(`${API_BASE}/api/demo/session/event`, {
      method: "POST",
      headers: { "Content-Type": "application/json", "X-Demo-Token": token },
      body: JSON.stringify({ event }),
    }).catch(() => {});
  } catch {
    /* nothing to report — see javadoc */
  }
}

// --- shareable agent link ----------------------------------------------------

// Flat optional-field shape rather than a discriminated union — same
// compiler-settings reasoning as CreateResult (demoSession.ts).
export type ShareResult = {
  ok: boolean;
  /** Absolute URL ready to copy, e.g. https://app.example.com/a/<shareId>. */
  shareUrl?: string;
  /** Always a soft human sentence when !ok. */
  message?: string;
};

/**
 * POST /api/demo/session/share — mints (or re-returns) the public share link
 * for this sandbox's agent. Never rejects: any failure resolves to a sentence
 * the panel renders as content.
 */
export async function createShare(token: string): Promise<ShareResult> {
  const fallback: ShareResult = {
    ok: false,
    message: "We couldn't create a share link just now. Your sandbox is fine — try again in a moment.",
  };
  try {
    const res = await fetch(`${API_BASE}/api/demo/session/share`, {
      method: "POST",
      headers: { "Content-Type": "application/json", "X-Demo-Token": token },
    });
    const body = await readJson(res);
    if (!res.ok || !body || typeof body.shareUrl !== "string" || !body.shareUrl) {
      return fallback;
    }
    return { ok: true, shareUrl: body.shareUrl };
  } catch {
    return fallback;
  }
}

// ---------------------------------------------------------------------------
// Real-messenger bridge (/api/demo/channels)
//
// Two channels with deliberately different honesty levels, and the types below
// keep them apart rather than smoothing over the difference:
//
//   telegram  - a REAL connection. A single-use pairing code binds the
//               visitor's Telegram chat to this sandbox, and what the bot
//               replies is their own agent's output over the real Bot API.
//   whatsapp  - a PREVIEW. The reply is real (it is an ordinary
//               /api/demo/chat turn); the delivery is not. `mode` comes from
//               the server so the UI's labelling can never silently go stale
//               if a genuine platform sender is added later.
//
// Same error philosophy as the rest of this module: nothing here throws, and
// every failure resolves to "channel unavailable", which the page renders by
// simply not showing the card.
// ---------------------------------------------------------------------------

export type ChannelMessage = {
  direction: "IN" | "OUT";
  text: string;
  at: string;
};

export type TelegramStatus = {
  available: boolean;
  botUsername: string | null;
  /** NONE | PENDING | LINKED | REVOKED */
  state: string;
  pairingCode: string | null;
  deepLink: string | null;
  peerLabel: string | null;
  linkedAt: string | null;
  messageCount: number;
  messages: ChannelMessage[];
};

export type WhatsAppStatus = {
  /**
   * "PREVIEW" = the original no-transport preview (unchanged meaning).
   * "LIVE" = a platform WhatsApp Business number is configured and on: the
   * fields below describe a REAL wa.me pairing — see DemoChannelController.
   */
  mode: string;
  used: boolean;
  messageCount: number;
  /** LIVE only. false hides the WhatsApp card entirely. */
  available?: boolean;
  /** LIVE only: NONE | PENDING | LINKED | REVOKED */
  state?: string;
  /** LIVE only: single-use code, null once redeemed. */
  pairingCode?: string | null;
  /** LIVE only: https://wa.me/<number>?text=<code> */
  deepLink?: string | null;
  /** LIVE only: platform number with the middle digits hidden. */
  businessNumberMasked?: string | null;
};

export type ChannelsStatus = {
  telegram: TelegramStatus;
  whatsapp: WhatsAppStatus;
};

const CHANNELS_UNAVAILABLE: ChannelsStatus = {
  telegram: {
    available: false,
    botUsername: null,
    state: "NONE",
    pairingCode: null,
    deepLink: null,
    peerLabel: null,
    linkedAt: null,
    messageCount: 0,
    messages: [],
  },
  whatsapp: {
    mode: "PREVIEW",
    used: false,
    messageCount: 0,
    available: false,
    state: "NONE",
    pairingCode: null,
    deepLink: null,
    businessNumberMasked: null,
  },
};

function normalizeChannels(body: any): ChannelsStatus {
  if (!body || !body.telegram || !body.whatsapp) return CHANNELS_UNAVAILABLE;
  return {
    telegram: {
      ...CHANNELS_UNAVAILABLE.telegram,
      ...body.telegram,
      messages: Array.isArray(body.telegram.messages) ? body.telegram.messages : [],
    },
    whatsapp: { ...CHANNELS_UNAVAILABLE.whatsapp, ...body.whatsapp },
  };
}

async function channelCall(token: string, path: string, method: "GET" | "POST" | "DELETE"): Promise<ChannelsStatus> {
  try {
    const res = await fetch(`${API_BASE}/api/demo/channels${path}`, {
      method,
      headers: { "X-Demo-Token": token },
    });
    if (!res.ok) return CHANNELS_UNAVAILABLE;
    return normalizeChannels(await readJson(res));
  } catch {
    return CHANNELS_UNAVAILABLE;
  }
}

/**
 * Polled while a pairing is outstanding or a chat is live. `since` is the
 * newest message timestamp already rendered, so a tab left open for an hour
 * still transfers only what is new.
 */
export function fetchChannels(token: string, since?: string | null): Promise<ChannelsStatus> {
  const query = since ? `?since=${encodeURIComponent(since)}` : "";
  return channelCall(token, query, "GET");
}

/** Idempotent — reloading must not invalidate the code already on the visitor's phone screen. */
export function pairTelegram(token: string): Promise<ChannelsStatus> {
  return channelCall(token, "/telegram/pair", "POST");
}

export function unlinkTelegram(token: string): Promise<ChannelsStatus> {
  return channelCall(token, "/telegram", "DELETE");
}

/**
 * Real WhatsApp pairing (only meaningful when status.whatsapp.mode === "LIVE"):
 * issues the single-use VD-XXXX code + wa.me deep link. Idempotent, like
 * pairTelegram. Sends nothing and collects no phone number.
 */
export function pairWhatsApp(token: string): Promise<ChannelsStatus> {
  return channelCall(token, "/whatsapp/pair", "POST");
}

export function unlinkWhatsApp(token: string): Promise<ChannelsStatus> {
  return channelCall(token, "/whatsapp", "DELETE");
}

/**
 * Analytics beacon only: it sends no WhatsApp message and collects no phone
 * number. The reply shown inside the WhatsApp frame comes from the ordinary
 * sendMessage() turn (demoChat.ts).
 */
export function recordWhatsAppPreview(token: string): Promise<ChannelsStatus> {
  return channelCall(token, "/whatsapp/preview", "POST");
}
