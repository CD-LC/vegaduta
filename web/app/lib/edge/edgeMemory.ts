// Edge memory recall — closes finding E-6 ("edge agent has amnesia"), the last
// open gap of the seven in docs/rewriting-story/04-design/EDGE-AI-STRATEGY.md.
//
// THE GAP. A hosted turn gets recallMemoryFactsBlock injected into its system
// prompt (AgentChatService); an edge turn got only the raw prompt. Tools
// reached full parity on 2026-08-06 — context never did. The user-visible
// effect is not "my agent has no memory", it is "the local model is dumber",
// because the same agent answering the same question genuinely knew less
// on-device. That misattribution is the real cost: it makes edge look like a
// quality downgrade when it is an information downgrade.
//
//   GET /api/edge/memory?agentId=<uuid>  → {facts: string[]}
//
// ONCE PER SESSION, not per turn. The server caps at 24 facts and the fetch is
// query-independent by design: a per-turn round trip to decide what a LOCAL
// model should know would spend the latency that answering locally exists to
// save, and would put a network dependency in front of every offline turn. The
// endpoint accepts an optional `q` for relevance-ranked recall; this client
// deliberately does not use it for that reason.
//
// Same posture as toolBridge.ts throughout: never throws, never toasts, never
// prompts for re-auth, and any failure degrades to "no facts" — which is
// exactly today's behaviour, so a broken memory fetch can never be worse than
// not having shipped this.

import { auth } from "../auth";
import { API_BASE } from "../api";
import { applyViewAsHeader } from "../viewAs";

/** Matches the server's EDGE_MEMORY_MAX_FACTS. Enforced again here because the
 * prompt budget is a CLIENT concern: a tier-1 model runs a 4k–8k window and
 * these tokens compete with the conversation itself. */
const MAX_FACTS = 24;

/** Per-agent, per-session cache. null = fetched and there is nothing (or the
 * fetch failed) — cached either way so a cold endpoint is asked exactly once. */
const cache = new Map<string, string[] | null>();

/** Sticky for the session: a 404 means this deployment has no memory endpoint
 * (older core), so stop asking. Same contract as toolBridge's toolSurfaceGone. */
let memorySurfaceGone = false;

/** Test/support hook — new sign-in or agent switch should forget cached facts. */
export function resetEdgeMemory(): void {
  memorySurfaceGone = false;
  cache.clear();
}

async function bearerHeaders(): Promise<Record<string, string>> {
  const headers: Record<string, string> = {};
  try {
    // silent:true — a background edge turn must never trigger the reauth prompt.
    const token = await auth.getToken({ silent: true });
    if (token) headers.Authorization = `Bearer ${token}`;
    applyViewAsHeader(headers);
  } catch {
    // Unauthenticated: the server answers 401 and we cache "no facts".
  }
  return headers;
}

/**
 * The agent's remembered facts for this caller, or null when there are none /
 * the surface is unavailable. Cached per agent per session. Never rejects.
 *
 * Returns null rather than [] for "nothing" so callers can't accidentally
 * render an empty "Known facts:" header.
 */
export async function fetchEdgeMemory(agentId?: string | null): Promise<string[] | null> {
  if (typeof window === "undefined" || typeof fetch === "undefined") return null;
  if (memorySurfaceGone) return null;
  if (typeof agentId !== "string" || !agentId.trim()) return null;
  // No account, no server-side memory. Deliberately NOT a reason to look
  // broken: an account-less on-device visitor never had facts to recall.
  try {
    if (!auth.isAuthenticated()) return null;
  } catch {
    return null;
  }

  if (cache.has(agentId)) return cache.get(agentId) ?? null;

  let facts: string[] | null = null;
  try {
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), 10_000);
    let res: Response;
    try {
      res = await fetch(`${API_BASE}/api/edge/memory?agentId=${encodeURIComponent(agentId)}`, {
        headers: await bearerHeaders(),
        signal: controller.signal,
      });
    } finally {
      clearTimeout(timer);
    }
    if (res.status === 404) {
      memorySurfaceGone = true;
    } else if (res.ok) {
      const body = await res.json();
      const list = Array.isArray(body?.facts) ? body.facts : null;
      if (list) {
        const cleaned = list
          .filter((f: unknown): f is string => typeof f === "string" && f.trim().length > 0)
          .map((f: string) => f.trim())
          .slice(0, MAX_FACTS);
        facts = cleaned.length > 0 ? cleaned : null;
      }
    }
    // 401/403/5xx → null: no facts this session, the turn is unaffected.
  } catch {
    facts = null;
  }
  cache.set(agentId, facts);
  return facts;
}

/**
 * The system-prompt block, or "" when there is nothing to add.
 *
 * Wording is deliberately close to the hosted recallMemoryFactsBlock so the
 * SAME agent does not behave differently by tier — the whole point of E-6 is
 * parity, and a differently-worded instruction is itself a behaviour delta.
 * "use if relevant" matters: a session-scoped blind fetch will include facts
 * irrelevant to this turn, and a small model told to use them all will
 * cheerfully drag them in.
 */
export function memoryFactsBlock(facts: string[] | null): string {
  if (!facts || facts.length === 0) return "";
  const list = facts.map((f) => `- ${f}`).join("\n");
  return "\n\nKnown facts about this user from previous conversations " + "(use if relevant, ignore if not):\n" + list;
}
