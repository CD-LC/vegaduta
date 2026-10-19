import { publicApiBase } from "../../lib/env";
import { fetchWithRetry, fetchWithTimeout } from "../../lib/http/fetchWithRetry";
// Client for the PUBLIC workflow-share surface (/api/share/workflows/{shareId}).
//
// Same separation as /agent/[shareId]/api.ts: a visitor here has no session,
// no auth, nothing but the shareId in the URL, which is the only credential
// these endpoints take. Nothing in this module ever throws - each call
// resolves to a typed result the page renders as honest, labelled content.
//
// Deliberately self-contained: zero dependency on the authenticated
// lib/api.ts client, not even its generic voiceBlobExtension helper (see
// turnFilename below for this module's own, narrower equivalent). This is a
// WORKFLOW, not a chat agent - each interaction is one input (typed or
// spoken) -> one run -> one result, never an appended message thread.

const API_BASE = publicApiBase();

/** The backend's failure vocabulary, plus "unavailable" for everything else
 *  (network down, unknown code, malformed body). Never a raw status code. */
export type ShareErrorCode = "rate_limited" | "expired" | "unavailable";

export type SharedWorkflowInfo = {
  workflowName: string;
  // White-label branding, same convention as agent/[shareId]/api.ts's
  // SharedAgentInfo: the OWNING tenant's own brand, or null for platform
  // (VegaDūta) branding. The backend only sends these while the owner's plan
  // actually includes white-label, so presence alone means "render the
  // tenant's brand, drop VegaDūta's".
  brandDisplayName: string | null;
  brandLogoUrl: string | null;
  // Push-to-talk gate - server-resolved from the workflow's own configured
  // trigger, never guessed true for a workflow that can't take voice input.
  supportsMediaInput: boolean;
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
  info?: SharedWorkflowInfo;
  error?: ShareErrorCode;
};

export type ShareRunResult = {
  ok: boolean;
  resultText?: string;
  status?: string;
  errorMessage?: string | null;
  error?: ShareErrorCode;
};

export type ShareVoiceTurnResult = {
  ok: boolean;
  resultText?: string;
  audioBase64?: string;
  method?: string;
  error?: ShareErrorCode;
};

async function readJson(res: Response): Promise<any> {
  try {
    return await res.json();
  } catch {
    return null;
  }
}

/** 410/404 always means expired/revoked; 429 always means rate-limited;
 *  every other non-2xx (or a malformed body) is the generic "unavailable". */
function normalizeError(status: number): ShareErrorCode {
  if (status === 410 || status === 404) return "expired";
  if (status === 429) return "rate_limited";
  return "unavailable";
}

/** GET /api/share/workflows/{shareId} - who this shared workflow is. */
export async function fetchSharedWorkflow(shareId: string): Promise<ShareLoadResult> {
  try {
    const res = await fetchWithRetry(`${API_BASE}/api/share/workflows/${encodeURIComponent(shareId)}`, {}, 8000, 2);
    if (!res.ok) return { ok: false, error: normalizeError(res.status) };
    const body = await readJson(res);
    if (!body || typeof body.workflowName !== "string") return { ok: false, error: "unavailable" };
    return {
      ok: true,
      info: {
        workflowName: body.workflowName,
        brandDisplayName:
          typeof body.brandDisplayName === "string" && body.brandDisplayName ? body.brandDisplayName : null,
        brandLogoUrl: safeLogoUrl(body.brandLogoUrl),
        supportsMediaInput: body.supportsMediaInput === true,
      },
    };
  } catch (error) {
    console.error("Failed to fetch shared workflow:", error);
    return { ok: false, error: "unavailable" };
  }
}

/** POST /api/share/workflows/{shareId}/run {message} -> {resultText, status,
 *  errorMessage}. Input is trimmed here so every caller gets the same
 *  guarantee. One input, one run, one result - never appended to a thread. */
export async function runSharedWorkflow(shareId: string, message: string): Promise<ShareRunResult> {
  const trimmed = message.trim();
  if (!trimmed) return { ok: false, error: "unavailable" };
  try {
    const res = await fetchWithRetry(
      `${API_BASE}/api/share/workflows/${encodeURIComponent(shareId)}/run`,
      {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ message: trimmed }),
      },
      // A run is NOT idempotent: retrying after a timeout starts a SECOND run
      // that the workflow owner pays for, while the first is still going. So no
      // retry, and a budget just under Cloudflare's ~100s edge limit, because
      // multi-agent workflows legitimately take longer than the old 50s.
      95000,
      0
    );
    if (!res.ok) return { ok: false, error: normalizeError(res.status) };
    const body = await readJson(res);
    if (!body || typeof body.resultText !== "string") return { ok: false, error: "unavailable" };
    return {
      ok: true,
      resultText: body.resultText,
      status: typeof body.status === "string" ? body.status : undefined,
      errorMessage: typeof body.errorMessage === "string" ? body.errorMessage : null,
    };
  } catch (error) {
    console.error("Failed to run shared workflow:", error);
    return { ok: false, error: "unavailable" };
  }
}

/** POST /api/share/workflows/{shareId}/report {reason}. Resolves true when
 *  the report was accepted; a failure still resolves (false) - the page
 *  thanks the reporter either way. */
export async function reportSharedWorkflow(shareId: string, reason: string): Promise<boolean> {
  try {
    const res = await fetchWithTimeout(
      `${API_BASE}/api/share/workflows/${encodeURIComponent(shareId)}/report`,
      {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ reason: reason.trim() }),
      },
      5000
    );
    return res.ok;
  } catch (error) {
    console.error("Failed to report shared workflow:", error);
    return false;
  }
}

// ---------------------------------------------------------------------------
// Push-to-talk voice turn (POST /api/share/workflows/{shareId}/turn) - a
// single BLOCKING JSON response, not SSE. Unlike agent/[shareId]/api.ts's
// sendSharedVoiceTurn (a streamed multi-event reply for a back-and-forth
// chat), a workflow run is one-shot: speak once, run once, hear the result.
// A plain fetch + JSON parse is all this needs - no reader loop.
// ---------------------------------------------------------------------------

/** Picks a filename extension from the blob's own MIME type - "wav" only
 *  when the type explicitly says so, "webm" otherwise (MediaRecorder's most
 *  common real-world output when "audio/wav" isn't supported). Deliberately
 *  narrower than lib/api.ts's voiceBlobExtension - this module has zero
 *  dependency on that file. */
function turnFilename(mimeType: string): string {
  return (mimeType || "").toLowerCase().includes("wav") ? "turn.wav" : "turn.webm";
}

/** POST /api/share/workflows/{shareId}/turn - multipart `audio` field,
 *  returns ONE blocking JSON body: { audioBase64, method, resultText,
 *  runStatus }. `method` ("elevenlabs" | "sarvam") decides the MIME type of
 *  audioBase64: sarvam means audio/wav, anything else means audio/mpeg - a
 *  real bug elsewhere in this codebase came from hardcoding audio/mpeg
 *  regardless of method, so every caller of this function must read `method`
 *  back from the result before building a data: URI, never assume mpeg. */
export async function sendSharedWorkflowVoiceTurn(shareId: string, audioBlob: Blob): Promise<ShareVoiceTurnResult> {
  const formData = new FormData();
  formData.append("audio", audioBlob, turnFilename(audioBlob.type));

  try {
    const res = await fetchWithTimeout(
      `${API_BASE}/api/share/workflows/${encodeURIComponent(shareId)}/turn`,
      { method: "POST", body: formData },
      45000
    );
    if (!res.ok) return { ok: false, error: normalizeError(res.status) };
    const body = await readJson(res);
    if (!body || typeof body.resultText !== "string" || typeof body.audioBase64 !== "string") {
      return { ok: false, error: "unavailable" };
    }
    return {
      ok: true,
      resultText: body.resultText,
      audioBase64: body.audioBase64,
      method: typeof body.method === "string" ? body.method : "elevenlabs",
    };
  } catch (error) {
    console.error("Failed to send shared workflow voice turn:", error);
    return { ok: false, error: "unavailable" };
  }
}
