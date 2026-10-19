// Client bridge to the server's edge tool surface.
//
// The propose/execute split (binding): the LOCAL model only ever
// PROPOSES a tool call; the SERVER executes it through the same guarded
// registry paid tenants use (GuardedToolCallback approval gate, step ceiling,
// LoggingToolCallback audit — all unchanged). This module is the only place
// tier-1 code talks to that surface:
//
//   GET  /api/edge/tools?agentId=<uuid>  → {tools: [{name, description,
//                                           inputSchema}]} — the curated
//                                           edge-safe tool subset
//   POST /api/edge/tools/execute         → body {agentId: <uuid>, toolName,
//                                           argumentsJson: <JSON string>}
//                                           reply {ok, resultJson?, error?}
//                                           (no sessionId — the server
//                                           generates its own marker)
//
// Auth: the same Keycloak bearer the rest of web/app/lib rides on —
// auth.getToken({ silent: true }). Deliberately NOT via api.ts's apiFetch:
// that wrapper toasts on every failure, and edge failures must stay silent
// (the tier router's fall-through contract) — also silent:true so a token
// hiccup here can never fire the reauth prompt from a background edge turn.
// API_BASE is imported from api.ts (read-only import) so the two files can
// never disagree about where core lives.
//
// Entitlement: canUseServerTools() below decides — synchronously, without
// prompting — whether this caller may reach the execute endpoint at all, and
// executeEdgeTool refuses locally when it says no. Distinct from the sticky-404
// flag: "you were never entitled to this" is not "the server isn't there".
//
// Server-not-deployed tolerance (binding for this wave): a 404 on either
// endpoint marks the whole tool surface unavailable for the rest of the
// browser session — plain local chat keeps working with tools disabled, and
// nothing beyond a details note is ever surfaced to the user.

import { auth } from "../auth";
import { API_BASE } from "../api";
import { getEdgeLearningOptIn } from "./capabilities";
import { applyViewAsHeader } from "../viewAs";

// ---------------------------------------------------------------------------
// Types
// ---------------------------------------------------------------------------

/** One edge-safe tool definition as published by GET /api/edge/tools. The
 * inputSchema is the server's exact JSON schema, passed to the local model
 * verbatim — never a hand-copied schema. */
export interface EdgeToolDefinition {
  name: string;
  description: string;
  inputSchema: unknown;
}

export type EdgeToolExecuteResult =
  | { ok: true; result: string }
  | {
      ok: false;
      /** "unavailable" = endpoint missing/unauthorized (tools should be
       * disabled for the session); "rejected" = server refused this call
       * (not allowlisted, approval-gated, quota); "failed" = execution or
       * network error. */
      code: "unavailable" | "rejected" | "failed";
      detail: string;
    };

// ---------------------------------------------------------------------------
// Session-sticky availability
// ---------------------------------------------------------------------------

/** Sticky for the browser session: once either endpoint 404s (server not yet
 * deployed), stop asking — tools stay off, chat stays on. */
let toolSurfaceGone = false;

/** Per-agent tool-list cache (one fetch per agent per session; null = the
 * fetch failed / surface unavailable — treated as "no tools"). */
const toolListCache = new Map<string, EdgeToolDefinition[] | null>();

export function edgeToolsUnavailable(): boolean {
  return toolSurfaceGone;
}

/** Test/support hook: forget cached tool state (new sign-in, new session). */
export function resetEdgeToolBridge(): void {
  toolSurfaceGone = false;
  toolListCache.clear();
}

// ---------------------------------------------------------------------------
// The spend guard — who is even allowed to ask the server to execute a tool
//
// Every POST to /api/edge/tools/execute is a SERVER-side execution on a real
// tenant: the guarded registry, the audit log, the bill. An account-less
// on-device visitor (WebGPU/WebLLM, outside the entitlement system entirely)
// has no standing there, and until now the only thing that stopped one was the
// server answering 401 — executeEdgeTool posted unconditionally. That is a
// correct outcome reached the expensive way: one request, up to the 60s
// timedFetch ceiling below, and a log line on an endpoint that should never
// have been asked, for every tool call the local model proposes (tier1.ts
// calls executeEdgeTool once per proposed call, inside its tool-round loop).
// Refusing here is the same answer, one round trip earlier.
//
// SYNCHRONOUS, deliberately, and that is the interesting choice. The obvious
// implementation — `await auth.getToken({ silent: true })` — is what
// bearerHeaders() below does, and it is the wrong shape for a decision made
// inside the dispatch loop: getToken() calls kc.updateToken(30), which hits
// the token endpoint whenever the access token is within 30s of expiry. An
// async guard could therefore perform network of its own while deciding
// whether network is allowed. auth.isAuthenticated() answers from the live
// keycloak-js instance (`kc.authenticated && kc.token`) with no await and no
// refresh, and it cannot be MORE permissive than the bearer this module would
// have attached: bearerHeaders() sets Authorization only when getToken()
// resolves a token, and getToken() can only resolve `kc.token` from that same
// instance.
//
// Posture is unchanged from bearerHeaders(): nothing here toasts, prompts, or
// reaches auth.login(). isAuthenticated() is in fact quieter than getToken() —
// it has no refresh to fail and therefore no promptReauth() path at all.
//
// Fails CLOSED. Anything unexpected (no window, keycloak-js failing to
// construct) answers false: a wrong "no tools" degrades to plain on-device
// chat, which this whole tier is built to do, while a wrong "yes" is a request
// somebody pays for.
// ---------------------------------------------------------------------------

/** Why server tools are refused for this caller, or null when they're allowed.
 * The reason text is what executeEdgeTool reports as its failure detail, so the
 * check and the explanation can never drift apart. */
export type ServerToolBlock =
  /** No agent scope to run against — a signed-in person on a surface with no
   * agent selected sees this, and telling them to sign in would be wrong. */
  | { code: "no-agent"; detail: string }
  /** No account. The ordinary state for the free, on-device case. */
  | { code: "signed-out"; detail: string }
  /** Server-side render, or the auth layer could not answer. */
  | { code: "unknown"; detail: string };

/** Why server tools are refused for this caller, or null when they're allowed.
 * The `detail` is what executeEdgeTool reports as its failure text, so the
 * check and the explanation can never drift apart; the `code` exists because a
 * UI must be able to tell the two refusals apart — "sign in" is the wrong
 * sentence for someone who is already signed in and simply has no agent
 * selected. */
export function serverToolBlockReason(agentId: string | null | undefined): ServerToolBlock | null {
  if (typeof window === "undefined") return { code: "unknown", detail: "no browser session" };
  // A plausible agent identity. agentId is the scope the server resolves the
  // tool allowlist against (see the endpoint contract at the top of this
  // file); an on-device session with none — which is what tier1.ts's
  // `agentId: string | null` carries for the account-less case this guard
  // exists for — has nothing to execute against. Deliberately NOT a UUID test:
  // a second, stricter copy of the server's own validation living in the
  // browser is how a call that would have worked gets broken locally.
  if (typeof agentId !== "string" || !agentId.trim()) {
    return {
      code: "no-agent",
      detail: "no agent selected — server tools run against a specific agent",
    };
  }
  try {
    if (!auth.isAuthenticated()) {
      return {
        code: "signed-out",
        detail: "not signed in — server tools run on an account, on-device chat does not",
      };
    }
  } catch {
    return { code: "unknown", detail: "sign-in state unavailable" };
  }
  return null;
}

/**
 * May this caller reach the server's tool surface at all? True only when there
 * is a plausible agent identity AND a live authenticated session to bear a
 * token. Synchronous — safe to call per proposed tool
 * call inside the tool loop, and it can never trigger a sign-in prompt (see
 * the section comment above for why it reads auth.isAuthenticated() rather
 * than awaiting auth.getToken).
 *
 * False does NOT mean the tool surface is broken — it means this session was
 * never entitled to it. Callers should say so rather than showing a failure.
 */
export function canUseServerTools(agentId: string | null | undefined): boolean {
  return serverToolBlockReason(agentId) === null;
}

// ---------------------------------------------------------------------------
// Fetch helpers
// ---------------------------------------------------------------------------

async function bearerHeaders(): Promise<Record<string, string>> {
  const headers: Record<string, string> = { "Content-Type": "application/json" };
  try {
    // silent:true — a background edge turn must never trigger the reauth
    // prompt (see auth.ts getToken javadoc); no token just means the server
    // answers 401 and tools quietly stay off for this turn.
    const token = await auth.getToken({ silent: true });
    if (token) headers.Authorization = `Bearer ${token}`;
    applyViewAsHeader(headers);
  } catch {
    // Fall through unauthenticated.
  }
  return headers;
}

/** fetch with a client-side ceiling — an edge turn must never hang on a dead
 * endpoint. Rejects like fetch does; callers below catch everything. */
async function timedFetch(url: string, init: RequestInit, timeoutMs: number): Promise<Response> {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);
  try {
    return await fetch(url, { ...init, signal: controller.signal });
  } finally {
    clearTimeout(timer);
  }
}

// ---------------------------------------------------------------------------
// GET /api/edge/tools
// ---------------------------------------------------------------------------

/**
 * The edge-safe tool definitions for an agent. Cached per agent per session.
 * Resolves null when the surface is unavailable (404 → sticky for the
 * session), the caller isn't authorized, or anything at all goes wrong —
 * callers treat null exactly like "no tools": plain chat continues. Never
 * rejects.
 */
export async function fetchEdgeTools(agentId?: string | null): Promise<EdgeToolDefinition[] | null> {
  if (typeof window === "undefined" || typeof fetch === "undefined") return null;
  if (toolSurfaceGone) return null;
  const cacheKey = agentId || "__none__";
  if (toolListCache.has(cacheKey)) return toolListCache.get(cacheKey) ?? null;
  let result: EdgeToolDefinition[] | null = null;
  try {
    const query = agentId ? `?agentId=${encodeURIComponent(agentId)}` : "";
    const res = await timedFetch(`${API_BASE}/api/edge/tools${query}`, { headers: await bearerHeaders() }, 10_000);
    if (res.status === 404) {
      toolSurfaceGone = true;
    } else if (res.ok) {
      const body = await res.json();
      const list = Array.isArray(body) ? body : Array.isArray(body?.tools) ? body.tools : null;
      if (list) {
        result = list
          .filter((t: any) => t && typeof t.name === "string" && t.name)
          .map((t: any) => ({
            name: t.name as string,
            description: typeof t.description === "string" ? t.description : "",
            inputSchema: t.inputSchema ?? null,
          }));
      }
    }
    // 401/403/5xx → result stays null: tools off for this agent this
    // session, chat unaffected.
  } catch {
    result = null;
  }
  toolListCache.set(cacheKey, result);
  return result;
}

// ---------------------------------------------------------------------------
// POST /api/edge/tools/execute
// ---------------------------------------------------------------------------

/**
 * Execute one server-side tool on behalf of the local model. Never rejects —
 * every outcome is a typed EdgeToolExecuteResult the tool loop can reason
 * about (an "unavailable" result disables tools for the rest of the session).
 */
export async function executeEdgeTool(
  toolName: string,
  args: unknown,
  agentId?: string | null,
  _sessionId?: string | null,
  _subjectKey?: string | null
): Promise<EdgeToolExecuteResult> {
  if (typeof window === "undefined" || typeof fetch === "undefined") {
    return { ok: false, code: "unavailable", detail: "no browser fetch" };
  }
  if (toolSurfaceGone) {
    return { ok: false, code: "unavailable", detail: "edge tool surface unavailable" };
  }
  // Refuse rather than attempt (see "The spend guard" above). "unavailable" is
  // the right existing code rather than "rejected": tier1.ts's failure handler
  // reads that code as "this session has no server tools" and stops proposing
  // them for the rest of the session, instead of "that one tool failed, try
  // another" — which is the honest reading of an unentitled caller.
  //
  // Note what is deliberately NOT done here: toolSurfaceGone stays untouched.
  // That flag is sticky for the whole browser session and belongs to the 404
  // case (the server genuinely not being deployed). Setting it on an
  // entitlement refusal would leave tools dead for the rest of the session
  // even after the person signs in — a state nothing short of a reload clears.
  const blocked = serverToolBlockReason(agentId);
  if (blocked) {
    return { ok: false, code: "unavailable", detail: blocked.detail };
  }
  try {
    const res = await timedFetch(
      `${API_BASE}/api/edge/tools/execute`,
      {
        method: "POST",
        headers: await bearerHeaders(),
        // Server contract, and it is EXACTLY these three fields:
        //   EdgeToolExecuteRequest(UUID agentId, String toolName, String argumentsJson)
        // The tool arguments travel as a JSON STRING under argumentsJson.
        //
        // Regression 2026-09-13 (commit 958154fc, fixed 2026-09-17): this was
        // changed to `arguments: args ?? {}` to match a SECOND, duplicate
        // controller that same commit added at /api/tools — but this client
        // posts to /api/edge/tools/execute, which has never had an `arguments`
        // field. The server does
        //     argumentsJson = hasText(request.argumentsJson()) ? ... : "{}"
        // so a missing field is not an error: EVERY tool silently executed
        // with empty arguments and returned HTTP 200. Nothing surfaced.
        // toolBridge.test.ts already asserted this exact body and was red the
        // whole time.
        //
        // sessionId and subjectKey are deliberately NOT sent, and must not be.
        // This endpoint generates its own session marker ("edge:" + UUID) and
        // resolves subjectKey from the *credential* — a client-supplied
        // subjectKey would be another user's long-term-memory identity. The
        // parameters stay on the signature because tier1.ts passes them, but
        // this endpoint derives both server-side by design.
        body: JSON.stringify({
          agentId: agentId || null,
          toolName,
          argumentsJson: JSON.stringify(args ?? {}),
        }),
      },
      60_000
    );
    if (res.status === 404) {
      toolSurfaceGone = true;
      return { ok: false, code: "unavailable", detail: "edge tool surface unavailable" };
    }
    if (res.status === 401 || res.status === 403) {
      return { ok: false, code: "rejected", detail: "not authorized for this tool" };
    }
    if (!res.ok) {
      let detail = `HTTP ${res.status}`;
      try {
        const body = await res.json();
        if (body && typeof body.error === "string") detail = body.error;
      } catch {
        // Non-JSON error body — keep the status detail.
      }
      return { ok: false, code: res.status >= 500 ? "failed" : "rejected", detail };
    }
    const text = await res.text();
    if (!text) return { ok: true, result: "" };
    try {
      const body = JSON.parse(text);
      // Canonical reply shape: {ok, resultJson?, error?}.
      if (body && typeof body === "object" && typeof body.ok === "boolean") {
        if (body.ok === true) {
          const r = body.resultJson;
          return {
            ok: true,
            result: typeof r === "string" ? r : r != null ? JSON.stringify(r) : "",
          };
        }
        return {
          ok: false,
          code: "failed",
          detail: typeof body.error === "string" && body.error ? body.error : "tool execution failed",
        };
      }
      // Unknown-but-JSON body — hand the whole thing to the model as text.
      return { ok: true, result: typeof body === "string" ? body : JSON.stringify(body) };
    } catch {
      return { ok: true, result: text };
    }
  } catch (err) {
    return {
      ok: false,
      code: "failed",
      detail: err instanceof Error ? err.message : String(err),
    };
  }
}

// ---------------------------------------------------------------------------
// Opt-in edge-conversation memory (2026-08-05 — "conversation with edge ai
// should also get saved, agents should learn from it")
// ---------------------------------------------------------------------------

/** Longest fact string composed below — matches the existing remember_fact
 * tool's own informal convention of a short, single-paragraph fact (this is
 * a client-side courtesy trim, not a validated limit; the server's own
 * guardrail/storage layer is the real ceiling). */
const MAX_FACT_CHARS = 600;

function truncate(text: string, max: number): string {
  const trimmed = (text || "").trim();
  return trimmed.length > max ? `${trimmed.slice(0, max - 1)}…` : trimmed;
}

// ---------------------------------------------------------------------------
// Memory-write status (bug fix, 2026-08-06 diagnosis)
//
// Diagnosis found three independent silent-failure sinks between
// remember_fact and the caller: the tool callback itself catches its own
// exceptions and returns a normal `{"error": ...}` result instead of
// throwing (never seen as ok:false here); GuardedToolCallback's approval/
// step-limit "soft stops" do the same; and `toolSurfaceGone` permanently
// disables the whole edge tool surface after one 404 with zero UI signal.
// executeEdgeTool's `ok:true` therefore does NOT guarantee the fact was
// actually saved. Per the audit's recommendation, this keeps normal-turn
// silence (no toast, no interruption) but records a last-outcome snapshot a
// UI can subscribe to and show as a small, persistent indicator — lighting
// up only on a confirmed failure, not on every successful write.
// ---------------------------------------------------------------------------

export interface EdgeMemoryWriteStatus {
  ok: boolean;
  at: number;
  detail: string | null;
}

let lastMemoryWriteStatus: EdgeMemoryWriteStatus | null = null;
const memoryWriteListeners = new Set<(status: EdgeMemoryWriteStatus) => void>();

/** Current snapshot — null until the first on-device memory write attempt. */
export function getEdgeMemoryWriteStatus(): EdgeMemoryWriteStatus | null {
  return lastMemoryWriteStatus;
}

/** Subscribe to memory-write outcome changes. Returns the unsubscribe
 * function. The listener is NOT called with the current value on subscribe —
 * read getEdgeMemoryWriteStatus() for the initial state (same pattern as
 * downloadManager / subscribeEdgeActivity). */
export function subscribeEdgeMemoryWriteStatus(listener: (status: EdgeMemoryWriteStatus) => void): () => void {
  memoryWriteListeners.add(listener);
  return () => {
    memoryWriteListeners.delete(listener);
  };
}

function reportMemoryWriteStatus(ok: boolean, detail: string | null): void {
  lastMemoryWriteStatus = { ok, at: Date.now(), detail };
  for (const listener of memoryWriteListeners) {
    try {
      listener(lastMemoryWriteStatus);
    } catch {
      // A broken listener never breaks the (already-fired) memory write.
    }
  }
}

/** A remember_fact result can be ok:true at the transport level while the
 * tool itself swallowed a failure and returned an `{"error": ...}` JSON
 * envelope as its "successful" result (RememberFactToolCallback's own catch
 * block, and GuardedToolCallback's approval/step-limit soft stops do this).
 * Best-effort sniff for that shape — never throws. */
function resultLooksLikeError(result: string): string | null {
  if (!result) return null;
  try {
    const parsed = JSON.parse(result);
    if (parsed && typeof parsed === "object" && typeof (parsed as any).error === "string" && (parsed as any).error) {
      return (parsed as any).error;
    }
  } catch {
    // Not JSON — treat as a plain successful text result.
  }
  return null;
}

/**
 * Best-effort, fire-and-forget: if (and only if) the person has explicitly
 * turned on "save on-device conversations to this agent's memory"
 * (edge.learningOptIn, off by default — see capabilities.ts's comment on
 * WHY this is a separate consent from edge-first itself), write a short
 * summary of one completed on-device turn into the agent's durable memory
 * through the EXACT SAME path a hosted turn already uses when the agent
 * itself decides to remember something: the remember_fact tool, via the
 * already-shipped POST /api/edge/tools/execute bridge above (full
 * auth/tenant-scoping, guardrail/approval decoration, and audit logging,
 * unchanged — nothing here is a new/parallel write path).
 *
 * Deliberately does not INTERRUPT the conversation on success or failure —
 * this mirrors the tier router's own "never let a background edge concern
 * interrupt the conversation" contract. Never throws. It DOES record the
 * outcome via reportMemoryWriteStatus (see above) so a low-noise UI
 * indicator can reflect a confirmed failure — "silent" no longer means
 * "invisible even when broken."
 */
export async function recordEdgeConversationFact(
  agentId: string | null | undefined,
  userMessage: string,
  assistantReply: string
): Promise<void> {
  try {
    if (!agentId || !getEdgeLearningOptIn()) return;
    const fact = truncate(
      `On-device conversation - user asked: "${truncate(userMessage, 200)}" — agent replied: "${truncate(assistantReply, 350)}"`,
      MAX_FACT_CHARS
    );
    if (!fact) return;
    const result = await executeEdgeTool("remember_fact", { fact }, agentId);
    // `=== false` (not `!result.ok`): with this repo's strict:false
    // tsconfig, only the literal comparison narrows EdgeToolExecuteResult's
    // discriminated union (same gotcha AgentPlayground.tsx's sendViaEdge and
    // sendFramesLocally document for RouteTurnResult).
    if (result.ok === false) {
      reportMemoryWriteStatus(false, result.detail);
      return;
    }
    const swallowedError = resultLooksLikeError(result.result);
    if (swallowedError) {
      reportMemoryWriteStatus(false, swallowedError);
      return;
    }
    reportMemoryWriteStatus(true, null);
  } catch (err) {
    // Best-effort only - never let a memory-write attempt affect the turn
    // the person is actually looking at. Still record the outcome.
    reportMemoryWriteStatus(false, err instanceof Error ? err.message : String(err));
  }
}
