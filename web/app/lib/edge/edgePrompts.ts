// The wording the in-browser (edge) runtime sends to a model or shows a person: the fallback system prompt, the
// reasoning nudge, the on-device guardrail refusals and the codebase Q&A instruction. These used to be string
// literals here, so changing them on the instructions console (/admin/instructions, the server's prompt registry)
// did nothing for turns that never leave the device.
//
// Now the effective wording is read from GET /api/edge/prompts (the registry's `edge.*` keys resolved for the
// signed-in caller: platform, workspace and agent overrides over the shipped text). The bundled defaults in
// edgePrompts.defaults.json are the fallback for everything that cannot reach the server - offline, signed out
// (the anonymous /try surface), a server that predates the endpoint, a timeout - so an edge turn never waits on,
// or fails because of, a prompt lookup. A test keeps the bundled defaults byte-equal to the shipped
// core/prompts/src/main/resources/prompts/en/edge.*.md files.
//
// Safety rules this module keeps:
//   - A key is only ever replaced by a non-blank string from the server. Missing, blank or malformed means the
//     bundled default.
//   - Only a key the server marks `switchable` (the reasoning nudge) can be switched off; a switched-off value for
//     any other key is ignored, so the guardrail refusals and the fallback prompt can never disappear.
//   - The bundle changes WORDING only. Whether a message is blocked is still decided by the patterns in
//     edgeGuardrails.ts, never by anything fetched.

import { API_BASE } from "../api/transport";
import { auth } from "../auth";
import { viewAsHeaders } from "../viewAs";
import DEFAULTS from "./edgePrompts.defaults.json";

export type EdgePromptKey = keyof typeof DEFAULTS;

export const EDGE_PROMPT_KEYS = Object.keys(DEFAULTS) as EdgePromptKey[];

/** How long a fetched bundle is trusted before the next turn asks again. Short, like the agent policy lookup. */
export const EDGE_PROMPTS_TTL_MS = 60_000;
/** After a failed lookup the next turn does not ask again for this long (a down server must not cost every turn). */
export const EDGE_PROMPTS_FAILURE_TTL_MS = 15_000;
/** A lookup that has not answered by now is given up on; the turn goes on with what it has. */
export const EDGE_PROMPTS_TIMEOUT_MS = 3_000;

interface BundleEntry {
  text: string | null;
  enabled: boolean;
  /** The registry lets an admin switch this key off; any other key always has text. */
  switchable: boolean;
}

interface CacheEntry {
  /** Until when this copy is trusted without asking again. */
  expiresAt: number;
  prompts: Map<string, BundleEntry>;
}

const cache = new Map<string, CacheEntry>();
const inflight = new Map<string, Promise<void>>();
/** The bundle the sync accessors read: the one for the agent most recently primed. */
let current: CacheEntry | null = null;

const cacheKey = (agentId: string | null | undefined) => agentId ?? "";

function parseBundle(body: unknown): Map<string, BundleEntry> | null {
  if (!body || typeof body !== "object") return null;
  const prompts = (body as { prompts?: unknown }).prompts;
  if (!prompts || typeof prompts !== "object") return null;
  const out = new Map<string, BundleEntry>();
  for (const [key, raw] of Object.entries(prompts as Record<string, unknown>)) {
    if (!raw || typeof raw !== "object") continue;
    const entry = raw as { text?: unknown; enabled?: unknown; switchable?: unknown };
    out.set(key, {
      text: typeof entry.text === "string" ? entry.text : null,
      enabled: entry.enabled !== false,
      switchable: entry.switchable === true,
    });
  }
  return out;
}

async function fetchBundle(agentId: string | null | undefined): Promise<Map<string, BundleEntry> | null> {
  // Signed out (the anonymous /try surface) has nothing to ask: the endpoint needs a session. No token, no request.
  const token = await auth.getToken({ silent: true });
  if (!token) return null;
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), EDGE_PROMPTS_TIMEOUT_MS);
  try {
    const qs = agentId ? `?agentId=${encodeURIComponent(agentId)}` : "";
    const res = await fetch(`${API_BASE}/api/edge/prompts${qs}`, {
      headers: { Authorization: `Bearer ${token}`, ...viewAsHeaders() },
      signal: controller.signal,
    });
    if (!res.ok) return null;
    return parseBundle(await res.json());
  } finally {
    clearTimeout(timer);
  }
}

/**
 * Makes the effective wording for this agent the one the sync accessors return, fetching it when the cached copy
 * is older than {@link EDGE_PROMPTS_TTL_MS}. Never rejects and never throws: offline, signed out, a failed or slow
 * request and a malformed answer all leave the previous copy (or the bundled defaults) in force.
 */
export async function primeEdgePrompts(agentId?: string | null): Promise<void> {
  try {
    if (typeof window === "undefined" || typeof fetch === "undefined") return;
    const key = cacheKey(agentId);
    const cached = cache.get(key);
    if (cached) current = cached;
    if (cached && Date.now() < cached.expiresAt) return;
    if (typeof navigator !== "undefined" && navigator.onLine === false) return;
    let pending = inflight.get(key);
    if (pending === undefined) {
      pending = (async () => {
        let prompts: Map<string, BundleEntry> | null = null;
        try {
          prompts = await fetchBundle(agentId);
        } catch {
          // Offline, aborted, blocked: the last good copy (or the bundled defaults) stands.
        }
        const entry: CacheEntry = prompts
          ? { expiresAt: Date.now() + EDGE_PROMPTS_TTL_MS, prompts }
          : { expiresAt: Date.now() + EDGE_PROMPTS_FAILURE_TTL_MS, prompts: cached?.prompts ?? new Map() };
        cache.set(key, entry);
        current = entry;
        inflight.delete(key);
      })();
      inflight.set(key, pending);
    }
    await pending;
  } catch {
    // A prompt lookup must never be the reason a turn fails.
  }
}

/** The text in force for `key`, or null when an admin switched a switchable prompt off. */
export function edgePromptOrNull(key: EdgePromptKey): string | null {
  const fetched = current?.prompts.get(key);
  if (fetched) {
    if (!fetched.enabled || fetched.text === null) {
      if (fetched.switchable) return null;
    } else if (fetched.text.trim() !== "") {
      return fetched.text;
    }
  }
  return DEFAULTS[key];
}

/** The text in force for `key`; the bundled default when nothing usable came from the server. */
export function edgePrompt(key: EdgePromptKey): string {
  return edgePromptOrNull(key) ?? DEFAULTS[key];
}

/** The shipped wording, ignoring anything fetched (tests and the offline path). */
export function edgePromptDefault(key: EdgePromptKey): string {
  return DEFAULTS[key];
}

/** Test/console hook: forget every fetched bundle so the bundled defaults are in force again. */
export function resetEdgePrompts(): void {
  cache.clear();
  inflight.clear();
  current = null;
}
