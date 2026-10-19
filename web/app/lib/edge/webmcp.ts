// Publish the agent's edge-safe tools to the BROWSER, so
// any agent the person is already running (Chrome's own, an extension, a
// local WebLLM loop) can drive this app through typed calls instead of
// scraping the DOM.
//
// This is the WebMCP surface — `navigator.modelContext`, shipped in Chrome
// 146 behind a flag and widened by the Chrome 149 origin trial, currently a
// W3C Community Group incubation and a Chrome-only implementation. It is
// therefore PROGRESSIVE ENHANCEMENT in the strictest sense: every function
// here answers "not supported" on every other browser and the app behaves
// exactly as it did before.
//
// WHAT THIS DOES NOT CHANGE — the trust boundary is unmoved. A registered
// tool's execute() calls the same POST /api/edge/tools/execute the local
// model's propose/execute loop already uses (toolBridge.ts), so the server's
// guarded registry — allowlist, GuardedToolCallback approval gate, step
// ceiling, LoggingToolCallback audit — governs every invocation exactly as
// before. The browser's own per-site consent prompt is an ADDITIONAL gate in
// front of that, never a replacement for it. Nothing here can invoke a tool
// the server wouldn't already run for this user.
//
// Tool list provenance: GET /api/edge/tools already returns the curated
// edge-safe SUBSET for an agent (not the full tenant registry), so this
// publishes a list the platform has already decided is safe for on-device
// proposal. If that endpoint is unavailable (404/401/pre-Wave-2 server),
// nothing is published and nothing is reported as broken.
//
// NAMESPACING: tools are registered as `vegaduta_<tool name>`. A page is a
// single shared namespace — another script (or another instance of this
// module) registering the same bare name would throw InvalidStateError and,
// worse, a bare `search` or `send_email` gives a visiting agent no way to
// tell whose tool it is. The raw server-side name travels in the description
// and is what actually reaches executeEdgeTool.
//
// readOnlyHint is left at its default (false = "may modify") for EVERY tool.
// The server's schema doesn't carry read/write intent today, and guessing it
// from a name would hand a mutating tool the annotation that tells a browser
// it can be called without asking. An honest "unknown" costs one extra
// consent prompt; a wrong "read-only" costs the user a silent side effect.

import { fetchEdgeTools, executeEdgeTool, type EdgeToolDefinition } from "./toolBridge";
import { getWebMcpEnabled } from "./capabilities";

// ---------------------------------------------------------------------------
// Feature detection
// ---------------------------------------------------------------------------

/** The subset of the WebMCP tool descriptor this module produces. Declared
 * locally (not imported from a @types package) because the API is still in
 * incubation and no stable typings ship for it — same posture tier0.ts takes
 * toward the Prompt API's `LanguageModel` global. */
interface WebMcpToolDescriptor {
  name: string;
  description: string;
  inputSchema?: unknown;
  execute: (input: unknown) => Promise<unknown>;
  annotations?: { readOnlyHint?: boolean };
  /**
   * The current IDL's replacement for `unregisterTool`: aborting this signal
   * withdraws the tool. Optional because a browser on the older shape ignores
   * an unknown descriptor key harmlessly, whereas omitting it on a current
   * browser leaves the tool unwithdrawable - so it is always sent when the
   * environment has AbortController.
   */
  signal?: AbortSignal;
}

interface WebMcpApi {
  /** Per the current IDL this returns Promise<undefined> and accepts an
   * AbortSignal on the descriptor. Typed as `unknown` return because the
   * flagged Chrome build and the older explainer shape both returned void —
   * the call site awaits it either way, which is correct for both. */
  registerTool?: (tool: WebMcpToolDescriptor) => unknown;
  /** REMOVED from the spec in favour of AbortSignal, but kept as a fallback:
   * a browser that still ships it is still entitled to be driven by it. */
  unregisterTool?: (name: string) => void;
  /** Older/alternate shape in the explainer: replace the whole tool set in
   * one call. Used only when registerTool is absent. */
  provideContext?: (context: { tools: WebMcpToolDescriptor[] }) => void;
}

/**
 * Which global answered. `document` is the CANONICAL surface — upstream moved
 * the API off `Navigator` onto `Document`, and this module was written against
 * the old location, which is why it has never once executed against a real
 * browser implementation (there is also no origin-trial token registered for
 * any VegaDuta origin, so `document.modelContext` is undefined for every real
 * visitor today regardless). Navigator is retained as a fallback rather than
 * replaced: a browser still shipping the old shape is still worth driving, and
 * removing it would be trading one guess about the surface for another.
 */
export type WebMcpApiKind = "documentModelContext" | "modelContext" | "modelContextTesting";

export interface WebMcpSupport {
  supported: boolean;
  /** Which global answered — `modelContextTesting` is Chrome's flagged
   * development surface, reported distinctly so the console can say "this is
   * a test build" rather than implying production support. */
  api: WebMcpApiKind | null;
  /** Populated when unsupported: a short, honest reason for the UI. */
  reason?: "no-browser" | "not-implemented" | "insecure-context";
}

function usable(candidate: unknown): candidate is WebMcpApi {
  if (!candidate || typeof candidate !== "object") return false;
  const api = candidate as WebMcpApi;
  return typeof api.registerTool === "function" || typeof api.provideContext === "function";
}

/**
 * Probe order is canonical-first, and the order is the whole point:
 *   1. document.modelContext        — where the spec actually put it
 *   2. navigator.modelContext       — where this file used to look, and only there
 *   3. navigator.modelContextTesting — Chrome's flagged development surface
 * A browser implementing (1) would have been reported as "not-implemented"
 * by the previous version of this function.
 */
function readApi(): { api: WebMcpApi; kind: WebMcpApiKind } | null {
  if (typeof document !== "undefined") {
    const doc = document as unknown as Record<string, unknown>;
    if (usable(doc.modelContext)) {
      return { api: doc.modelContext as WebMcpApi, kind: "documentModelContext" };
    }
  }
  if (typeof navigator === "undefined") return null;
  const nav = navigator as unknown as Record<string, unknown>;
  if (usable(nav.modelContext)) {
    return { api: nav.modelContext as WebMcpApi, kind: "modelContext" };
  }
  if (usable(nav.modelContextTesting)) {
    return { api: nav.modelContextTesting as WebMcpApi, kind: "modelContextTesting" };
  }
  return null;
}

/** Is WebMCP usable on this page right now? Synchronous, no side effects —
 * safe to call during render. */
export function detectWebMcp(): WebMcpSupport {
  if (typeof window === "undefined" || typeof navigator === "undefined") {
    return { supported: false, api: null, reason: "no-browser" };
  }
  // The spec requires a secure context (and top-level browsing contexts
  // only). isSecureContext covers https:// and localhost alike, so this
  // never fires on a normal deploy or a dev server.
  if (typeof window.isSecureContext === "boolean" && !window.isSecureContext) {
    return { supported: false, api: null, reason: "insecure-context" };
  }
  const found = readApi();
  if (!found) return { supported: false, api: null, reason: "not-implemented" };
  return { supported: true, api: found.kind };
}

// ---------------------------------------------------------------------------
// Naming
// ---------------------------------------------------------------------------

export const WEBMCP_TOOL_PREFIX = "vegaduta_";

/** `vegaduta_<sanitized>` — lower-cased, non-[a-z0-9_] runs collapsed to a
 * single underscore, trimmed, and length-capped. Two server tools whose
 * names only differ outside that character set would collide; the collision
 * is detected at registration time and the second one is skipped rather than
 * silently shadowing the first. */
export function webMcpToolName(rawName: string): string {
  const slug = rawName
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, "_")
    .replace(/^_+|_+$/g, "")
    .slice(0, 48);
  return WEBMCP_TOOL_PREFIX + (slug || "tool");
}

// ---------------------------------------------------------------------------
// Publish / unpublish
// ---------------------------------------------------------------------------

export interface WebMcpPublishState {
  /** Names as registered with the browser (prefixed). */
  toolNames: string[];
  agentId: string | null;
  api: WebMcpApiKind | null;
  /** Tools the server returned but that could not be registered (duplicate
   * name, browser rejection). Surfaced, never swallowed. */
  skipped: number;
}

const EMPTY_STATE: WebMcpPublishState = { toolNames: [], agentId: null, api: null, skipped: 0 };

let current: WebMcpPublishState = EMPTY_STATE;
const listeners = new Set<(state: WebMcpPublishState) => void>();

function emit(next: WebMcpPublishState): void {
  current = next;
  for (const listener of listeners) {
    // A broken listener must never break tool publishing — same rule
    // toolBridge.ts's memory-status listeners follow.
    try {
      listener(next);
    } catch {
      /* ignore */
    }
  }
}

/** Current publish state (empty when nothing is published). */
export function getWebMcpPublishState(): WebMcpPublishState {
  return current;
}

export function subscribeWebMcpPublishState(
  listener: (state: WebMcpPublishState) => void
): () => void {
  listeners.add(listener);
  listener(current);
  return () => {
    listeners.delete(listener);
  };
}

/**
 * Owns the lifetime of every tool this module registers.
 *
 * THE WITHDRAWAL BUG (fixed 2026-09-17): the spec REMOVED `unregisterTool` in
 * favour of passing an `AbortSignal` on the descriptor and aborting it. This
 * module only ever branched on `typeof api.unregisterTool === "function"`, so
 * against a current browser the else-branch fell through to `provideContext`
 * (also absent) and the withdrawal silently did NOTHING — tools stayed
 * published to the agent after the component unmounted, the user toggled the
 * feature off, or the agent changed. That is a leak of tool surface past the
 * point the user revoked it, which is the one failure mode a tool-publishing
 * API must not have.
 *
 * Replaced per registration set, not reused: an AbortController cannot be
 * un-aborted, so publishing a new set needs a fresh one.
 */
let activeRegistration: AbortController | null = null;

/** Remove every tool this module registered. Idempotent; never throws. */
export function unpublishWebMcpTools(): void {
  // Canonical path first: abort the signal handed to registerTool. Done before
  // the legacy branches and unconditionally, because a browser may accept the
  // signal AND still expose unregisterTool, and aborting is the cheap,
  // idempotent operation of the two.
  if (activeRegistration) {
    try {
      activeRegistration.abort();
    } catch {
      // An already-aborted controller throws nothing, but a hostile polyfill
      // might; withdrawal must never throw into a React cleanup.
    }
    activeRegistration = null;
  }
  const found = readApi();
  if (found && typeof found.api.unregisterTool === "function") {
    for (const name of current.toolNames) {
      try {
        found.api.unregisterTool(name);
      } catch {
        // Already gone (page navigated, agent cleared the set) — nothing to
        // repair, and a failed unregister must never throw into a React
        // cleanup function.
      }
    }
  } else if (found && typeof found.api.provideContext === "function" && current.toolNames.length) {
    // provideContext replaces the whole set: an empty list is the unpublish.
    try {
      found.api.provideContext({ tools: [] });
    } catch {
      /* ignore */
    }
  }
  if (current !== EMPTY_STATE) emit(EMPTY_STATE);
}

function buildDescriptor(tool: EdgeToolDefinition, agentId: string | null): WebMcpToolDescriptor {
  const registeredName = webMcpToolName(tool.name);
  const description =
    (tool.description || `The VegaDūta tool "${tool.name}".`) +
    ` (VegaDūta tool "${tool.name}" — runs server-side under this account's` +
    ` existing approval and audit rules.)`;
  return {
    name: registeredName,
    description,
    ...(tool.inputSchema ? { inputSchema: tool.inputSchema } : {}),
    annotations: { readOnlyHint: false },
    async execute(input: unknown) {
      const result = await executeEdgeTool(tool.name, input ?? {}, agentId);
      // Two shapes in one object on purpose: agents built against MCP expect
      // `content[]`, while the WebMCP explainer's own examples return a
      // plain structured object. Both are cheap, and returning only one
      // would silently read as "no result" to half the callers.
      // `=== true`, not a bare truthiness check: this project compiles with
      // strictNullChecks off, where only the explicit-literal comparison
      // narrows the union (the same idiom tier1.ts's tool loop uses).
      if (result.ok === true) {
        return { ok: true, result: result.result, content: [{ type: "text", text: result.result }] };
      }
      const text = `Tool "${tool.name}" did not run: ${result.detail} (${result.code}).`;
      return { ok: false, error: text, code: result.code, content: [{ type: "text", text }] };
    },
  };
}

export interface PublishOptions {
  /** Skip the user-preference check — used by the Edge console's own "try
   * it" affordance, never by automatic mounting. */
  force?: boolean;
}

/**
 * Publish this agent's edge-safe tools to the browser's model context.
 * Resolves the resulting state (empty when nothing was published: no WebMCP,
 * preference off, no tool surface, or an empty tool list). Never rejects.
 *
 * Republishing for a different agent replaces the previous set — a page only
 * ever exposes ONE agent's tools, the one the person is looking at.
 */
export async function publishWebMcpTools(
  agentId: string | null,
  options: PublishOptions = {}
): Promise<WebMcpPublishState> {
  const support = detectWebMcp();
  if (!support.supported) return EMPTY_STATE;
  if (!options.force && !getWebMcpEnabled()) {
    // Turning the preference OFF while tools are published has to withdraw
    // them, not just decline to add more — the mount hook re-runs this on
    // every preference change precisely so that flip takes effect at once.
    unpublishWebMcpTools();
    return EMPTY_STATE;
  }

  const found = readApi();
  if (!found) return EMPTY_STATE;

  const tools = await fetchEdgeTools(agentId);
  if (!tools || tools.length === 0) {
    unpublishWebMcpTools();
    return EMPTY_STATE;
  }

  // Replace rather than append: stale tools from a previously-viewed agent
  // must never stay callable.
  unpublishWebMcpTools();

  const descriptors: WebMcpToolDescriptor[] = [];
  const seen = new Set<string>();
  let skipped = 0;
  for (const tool of tools) {
    const descriptor = buildDescriptor(tool, agentId);
    if (seen.has(descriptor.name)) {
      skipped += 1; // name collision after sanitizing — first one wins
      continue;
    }
    seen.add(descriptor.name);
    descriptors.push(descriptor);
  }

  // A fresh controller per published set — see activeRegistration's comment for
  // why it cannot be reused. Any previous set is withdrawn first so two
  // controllers can never both believe they own the surface.
  if (activeRegistration) {
    try {
      activeRegistration.abort();
    } catch {
      /* ignore */
    }
  }
  const controller = typeof AbortController === "function" ? new AbortController() : null;
  activeRegistration = controller;

  const registered: string[] = [];
  if (typeof found.api.registerTool === "function") {
    for (const descriptor of descriptors) {
      try {
        // registerTool returns Promise<undefined> in the current IDL and void
        // in the older shape. It is deliberately NOT awaited here: this
        // function is synchronous by contract (called from render/effect
        // paths), and awaiting would reorder registration against the
        // unpublish that a fast unmount fires. A rejected promise is swallowed
        // for the same reason a throw is - one bad tool must not cost the set.
        const result = found.api.registerTool(
          controller ? { ...descriptor, signal: controller.signal } : descriptor
        );
        if (result && typeof (result as Promise<unknown>).catch === "function") {
          (result as Promise<unknown>).catch(() => {
            /* browser refused this one tool; the rest of the set stands */
          });
        }
        registered.push(descriptor.name);
      } catch {
        // InvalidStateError (duplicate/invalid schema) or a browser refusal:
        // one bad tool must not cost the rest of the set.
        skipped += 1;
      }
    }
  } else if (typeof found.api.provideContext === "function") {
    try {
      found.api.provideContext({ tools: descriptors });
      registered.push(...descriptors.map((d) => d.name));
    } catch {
      skipped += descriptors.length;
    }
  }

  const next: WebMcpPublishState = {
    toolNames: registered,
    agentId,
    api: found.kind,
    skipped,
  };
  emit(registered.length ? next : { ...EMPTY_STATE, skipped });
  return current;
}

/** Test seam: forget what this module thinks is published WITHOUT calling the
 * browser. Subscribers are kept (dropping them would silently detach live
 * components); they're notified of the reset like any other change. */
export function resetWebMcpState(): void {
  emit(EMPTY_STATE);
}
