import { NextResponse } from "next/server";
import { isPersonalEdition } from "../../lib/edition";
import { publicApiBase } from "../../lib/env";

// A2A discovery (2026-09-09). Peers resolve an Agent Card at the PUBLIC
// domain's /.well-known/agent-card.json (canonical name since A2A 0.3;
// ../agent.json is the legacy alias, same body). The card itself is built by
// core (A2aAgentCardController - it knows the agent list, the JSON-RPC
// endpoint URL and the auth schemes actually enforced), so the two route.ts
// files next to this are thin proxies over this one function: the public
// domain fronts the Next.js app, not core, and a well-known URL has to be
// served from the origin a peer already knows.
//
// Lives in its own module (not exported from route.ts) because Next.js
// type-checks a route file's exports against the fixed HTTP-verb set and
// rejects any other named export.
//
// Why a proxy rather than a redirect: A2A clients follow the spec literally -
// a 3xx from the well-known path is not guaranteed to be chased, and the
// card's `url` fields are absolute anyway so nothing depends on which origin
// served the JSON.
//
// API_BASE_URL (server-only, the in-cluster address) is preferred, then
// CORE_INTERNAL_URL (the compose-internal line - a localhost fallback inside
// the web container would dial the web container itself), then the
// browser-facing NEXT_PUBLIC_API_BASE_URL, then the local-dev default api.ts
// uses. Server-side callers never leave the network when the two differ.
function upstreamBase(): string {
  return process.env.API_BASE_URL || process.env.CORE_INTERNAL_URL || publicApiBase();
}

/** Five minutes - the card only changes when an agent is exposed/hidden or
 * core is redeployed, and peers re-fetch it per task, so a short public
 * cache keeps a chatty peer from hammering core without serving a stale
 * card for long. */
export const AGENT_CARD_CACHE_CONTROL = "public, max-age=300";

/** Bound the upstream wait: a peer probing discovery should get a quick,
 * honest 503 from a dead core, not hang on our side of the proxy. */
const UPSTREAM_TIMEOUT_MS = 5000;

export async function fetchAgentCard(): Promise<Response> {
  // A personal install has no peers to advertise to: core does not run the A2A directory there, and the
  // proxy already answers 404 for these paths. This is the same answer from the route itself, for a
  // request that reaches it without passing the proxy. No upstream call is made.
  if (isPersonalEdition()) {
    return new NextResponse(null, { status: 404, headers: { "Cache-Control": "no-store" } });
  }
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), UPSTREAM_TIMEOUT_MS);
  try {
    const upstream = await fetch(`${upstreamBase()}/.well-known/agent-card.json`, {
      headers: { accept: "application/json" },
      signal: controller.signal,
      // Next's fetch cache is bypassed on purpose: the HTTP Cache-Control
      // header below is the one caching layer (CDN/browser side), so a
      // just-exposed agent shows up within the same 5 minutes everywhere.
      cache: "no-store",
    });
    if (!upstream.ok) {
      return unavailable(`upstream responded ${upstream.status}`);
    }
    const body = await upstream.text();
    // Re-parse rather than pass through: a non-JSON body (an HTML error page
    // from a proxy in front of core, say) must not be served as a card.
    let card: unknown;
    try {
      card = JSON.parse(body);
    } catch {
      return unavailable("upstream returned a non-JSON body");
    }
    return NextResponse.json(card, {
      status: 200,
      headers: { "Cache-Control": AGENT_CARD_CACHE_CONTROL },
    });
  } catch (err) {
    const reason = (err as Error)?.name === "AbortError" ? "upstream timed out" : "upstream unreachable";
    return unavailable(reason);
  } finally {
    clearTimeout(timer);
  }
}

/** 503 with a JSON body (never HTML) so an A2A client's discovery step sees
 * a machine-readable "try again later", and no-store so a CDN never pins
 * the failure for the five minutes a good card would get. */
function unavailable(reason: string): NextResponse {
  return NextResponse.json(
    { error: "agent_card_unavailable", message: "The agent card is temporarily unavailable.", reason },
    { status: 503, headers: { "Cache-Control": "no-store", "Retry-After": "30" } }
  );
}
