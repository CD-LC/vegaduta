import { publicApiBase } from "../../lib/env";
import { fetchWithRetry, fetchWithTimeout } from "../../lib/http/fetchWithRetry";
// Client for the PUBLIC shared-agent surface (/api/demo/share/{shareId}).
//
// Same philosophy as components/try/demoClient.ts, and deliberately a
// separate module rather than an import from it: this page is visited by
// strangers who received a link — they have no demo token, no sandbox and no
// relationship with /try's localStorage. All they hold is the shareId in the
// URL, which is the only credential these endpoints take.
//
// Every path lives here and nowhere else, and nothing in this module ever
// throws: each call resolves to a typed result the page renders as honest,
// labelled content. The backend speaks in error CODES (viewer_cap,
// rate_limited, budget_exhausted, moderated, expired, invalid) — mapping
// those codes to human sentences is the PAGE's job; this module only
// normalizes them. The one job normalization DOES own is separating
// permanent failures from transient ones, so the page can avoid telling
// someone holding a dead link to "try again" — see normalizeError.

const API_BASE = publicApiBase();

/** The backend's failure vocabulary, plus two codes this client derives from
 *  the STATUS rather than the body: "invalid" (this share is permanently
 *  gone) and "unavailable" for everything else (network down, timeout, 5xx,
 *  unknown code, malformed body). Never a raw status code. */
export type ShareErrorCode =
  "viewer_cap" | "rate_limited" | "budget_exhausted" | "moderated" | "expired" | "invalid" | "unavailable";

// The codes we accept from a RESPONSE BODY. "invalid" is deliberately absent
// — it is status-derived only, see normalizeError for why trusting the body's
// copy of that word would be a bug.
const KNOWN_CODES: ShareErrorCode[] = ["viewer_cap", "rate_limited", "budget_exhausted", "moderated", "expired"];

export type SharedAgentInfo = {
  agentName: string;
  personaLabel: string | null;
  expiresAt: string | null;
};

// Flat optional-field shapes rather than discriminated unions — this
// project's tsconfig runs without strictNullChecks, so an `if (!r.ok)` guard
// would not narrow a union anyway (see demoClient.ts's CreateResult note).
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

/** Status first (it is the only thing that tells permanent from transient),
 *  body second.
 *
 *  410 always means expired. 404/403 always mean the share itself is gone —
 *  no such id, sharing revoked, link deleted — which is PERMANENT: the page
 *  must not invite a retry that can never succeed. This used to collapse
 *  into "unavailable", so a nonsense or revoked share id was greeted with
 *  "we couldn't reach this agent just now — try again in a moment", wording
 *  that describes a network blip. That was the bug; keep the two apart.
 *
 *  "invalid" is derived from the STATUS and never from the body, because the
 *  backend overloads that one word across two endpoints: GET /{shareId}
 *  answers 404 {"error":"invalid"} for "no such share", but POST
 *  /{shareId}/chat answers 422 {"error":"invalid"} for "your message was
 *  empty or too long". Trusting the body's code would turn a rejected
 *  MESSAGE into a dead-link screen and end a perfectly live conversation —
 *  which is why "invalid" is not in KNOWN_CODES. Don't add it there.
 *
 *  Everything else: trust the body's own code when it is one we know, and
 *  fall back to "unavailable" rather than inventing one. */
function normalizeError(status: number, body: any): ShareErrorCode {
  if (status === 410) return "expired";
  // 404 ONLY - deliberately not 403. DemoShareController never emits 403 on
  // this path (it answers 404 for "no such share" and 410 for expired), so
  // in practice a 403 here comes from the edge - a Cloudflare/WAF bot block
  // or a security filter - which is TRANSIENT. Mapping it to "invalid" would
  // tell a visitor the link is permanently dead and swap the composer out
  // for a dead-end screen over what a retry would have fixed. Let it fall
  // through to "unavailable", whose wording is honest about not knowing.
  if (status === 404) return "invalid";
  const code = body && (body.error || body.code);
  if (typeof code === "string" && (KNOWN_CODES as string[]).includes(code)) {
    return code as ShareErrorCode;
  }
  return "unavailable";
}

/** GET /api/demo/share/{shareId} — who this shared agent is. */
export async function fetchSharedAgent(shareId: string): Promise<ShareLoadResult> {
  try {
    const res = await fetchWithRetry(`${API_BASE}/api/demo/share/${encodeURIComponent(shareId)}`, {}, 8000, 2);
    const body = await readJson(res);
    if (!res.ok) return { ok: false, error: normalizeError(res.status, body) };
    if (!body || typeof body.agentName !== "string") return { ok: false, error: "unavailable" };
    return {
      ok: true,
      info: {
        agentName: body.agentName,
        personaLabel: typeof body.personaLabel === "string" ? body.personaLabel : null,
        expiresAt: typeof body.expiresAt === "string" ? body.expiresAt : null,
      },
    };
  } catch (error) {
    console.error("Failed to fetch shared agent:", error);
    return { ok: false, error: "unavailable" };
  }
}

/** POST /api/demo/share/{shareId}/chat {message} → {reply}. Input is trimmed
 *  here so every caller gets the same guarantee. */
export async function sendSharedChat(shareId: string, message: string): Promise<ShareChatResult> {
  const trimmed = message.trim();
  if (!trimmed) return { ok: false, error: "unavailable" };
  try {
    const res = await fetchWithRetry(
      `${API_BASE}/api/demo/share/${encodeURIComponent(shareId)}/chat`,
      {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ message: trimmed }),
      },
      50000,
      1
    );
    const body = await readJson(res);
    if (!res.ok) return { ok: false, error: normalizeError(res.status, body) };
    if (!body || typeof body.reply !== "string") return { ok: false, error: "unavailable" };
    return { ok: true, reply: body.reply };
  } catch (error) {
    console.error("Failed to send shared chat:", error);
    return { ok: false, error: "unavailable" };
  }
}

/** POST /api/demo/share/{shareId}/report {reason}. Resolves true when the
 *  report was accepted; a failure still resolves (false) — the page thanks
 *  the reporter either way rather than asking them to retry a safety report,
 *  but the boolean is honest for any caller that cares. */
export async function reportSharedAgent(shareId: string, reason: string): Promise<boolean> {
  try {
    const res = await fetchWithTimeout(
      `${API_BASE}/api/demo/share/${encodeURIComponent(shareId)}/report`,
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
