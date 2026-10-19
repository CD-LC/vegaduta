// A quiet call to POST /api/assist/draft for the agent checklist (2026-09-19).
//
// Why this does not go through api.askAiDraft: that wrapper parses with
// api.ts's toJson, which raises a global red error toast (and a client-error
// beacon) for every non-401 failure, and its fetch layer raises a second
// "service unavailable" toast for a network failure or a timeout abort. For
// the checklist those failures are EXPECTED, and today common:
//
//   AssistService sends only plan == FREE tenants to the hosted model pool.
//   Everyone else needs an active provider key, so a workspace on the
//   complimentary signup grant (no key yet), or a paid workspace that uses the
//   platform model with no key of its own, gets HTTP 400 "No active LLM
//   provider". Quota (429) and an empty pool (503) are the other usual ones.
//
// The checklist answers all of them the same way - it builds a basic draft
// locally and says so inline - so a popup telling the person to go and add a
// provider is wrong twice over. api.ts exposes no quiet variant of this call
// (toJsonQuietOn is module-private there, and would not cover the network
// toast anyway), so this makes the request directly with the same bearer
// token, the same precedent as lib/edge/codegen.ts's silent validate call.
//
// Contract: NEVER throws and never toasts. Returns the suggestion text, or
// null for any failure (HTTP error, network, timeout, abort, malformed body).
// The request is really cancelled at the deadline - not just ignored - so a
// slow reply cannot land minutes later.

import { API_BASE } from "../../../lib/api";
import { auth } from "../../../lib/auth";
import { applyViewAsHeader } from "../../../lib/viewAs";

export interface QuietDraftPayload {
  fieldLabel: string;
  instruction: string;
}

export async function requestDraftQuietly(
  payload: QuietDraftPayload,
  opts: { timeoutMs: number; signal?: AbortSignal }
): Promise<string | null> {
  const controller = new AbortController();
  const onOuterAbort = () => controller.abort();
  let timer: ReturnType<typeof setTimeout> | undefined;
  try {
    if (opts.signal) {
      if (opts.signal.aborted) return null;
      opts.signal.addEventListener("abort", onOuterAbort);
    }
    // The deadline covers the token refresh too, not only the fetch.
    timer = setTimeout(() => controller.abort(), opts.timeoutMs);

    const headers: Record<string, string> = { "Content-Type": "application/json" };
    // silent: a failed token refresh here must not raise the "stay signed
    // in?" prompt from a helper call; the request goes out without a token,
    // the server answers 401, and the caller falls back like any other miss.
    const token = await Promise.race<string | null>([
      auth.getToken({ silent: true }).catch((): string | null => null),
      new Promise<null>((resolve) => controller.signal.addEventListener("abort", () => resolve(null), { once: true })),
    ]);
    if (controller.signal.aborted) return null;
    if (token) headers.Authorization = `Bearer ${token}`;
    applyViewAsHeader(headers);

    const res = await fetch(`${API_BASE}/api/assist/draft`, {
      method: "POST",
      headers,
      body: JSON.stringify(payload),
      signal: controller.signal,
    });
    if (!res.ok) return null;
    const body: unknown = await res.json();
    const suggestion = body && typeof body === "object" ? (body as { suggestion?: unknown }).suggestion : null;
    return typeof suggestion === "string" && suggestion.trim() ? suggestion : null;
  } catch {
    return null;
  } finally {
    if (timer) clearTimeout(timer);
    opts.signal?.removeEventListener("abort", onOuterAbort);
  }
}
