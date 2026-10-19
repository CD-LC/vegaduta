// Edge preference storage (split out of ../capabilities.ts, which re-exports it):
// the localStorage keys and their typed getters/setters, plus the prefs-changed
// window event that keeps the chip, playground and router in sync.

// ---------------------------------------------------------------------------
// Storage keys (single source of truth — the chip, the playground and the
// router all read the same keys).
// ---------------------------------------------------------------------------

/** localStorage — user's model pick: "auto" | manifest model id | "hosted".
 * A stored value here ALWAYS wins over auto-scoring (binding rule from the
 * plan's "Best-fit model selection" block). */
export const MODEL_OVERRIDE_KEY = "edge.modelOverride";
/** localStorage — "1"/"0" once the person explicitly enabled/disabled
 * on-device replies. UNSET means "no explicit choice yet" — getEdgeOptIn()
 * then derives the default from the routing preference (edge-first → on). */
export const EDGE_OPT_IN_KEY = "edge.optIn";
/** localStorage — the edge-vs-hosted routing preference, three states:
 *   "edge-first"  (DEFAULT on capable devices): plain text turns route
 *                 on-device first, hosted fall-through unchanged;
 *   "hosted-only": never route locally (the persistent user override);
 *   "ask"        : nothing routes locally until the person explicitly
 *                 enables it per the existing opt-in flow (manual mode).
 * A stored value always wins; unset derives from the legacy keys (see
 * getEdgeRoutingPreference) so pre-existing user choices keep their effect. */
export const EDGE_ROUTING_PREF_KEY = "edge.routingPref";
/** localStorage — "1" once the playground opt-in banner was dismissed. */
export const EDGE_BANNER_DISMISSED_KEY = "edge.bannerDismissed";
/** localStorage — per-browser preference for what CallPanel's automatic
 * fallback points do when on-device declines a turn (STT model not cached,
 * local STT declines to handle a clip, or routeTurn's on-device reasoning
 * leg fails). Three states, same shape as EdgeRoutingPreference below:
 *   "auto"  (DEFAULT, unset reads as this): today's existing behavior
 *           unchanged — fall back to the server automatically, surfaced
 *           after the fact via the fallback notice;
 *   "ask"   : pause and let the person confirm before the turn's audio/text
 *             leaves the device for that specific fallback;
 *   "never" : refuse the turn instead of ever falling back automatically.
 * Deliberately does NOT affect the server-policy-driven EDGE_ONLY refusal
 * path (agentPolicy.ts) — that one already stops the turn unconditionally
 * and outranks any client-side preference; this setting only governs the
 * points where CallPanel decides to fall back on its own. */
export const EDGE_FALLBACK_POLICY_KEY = "edge.fallbackPolicy";
/** localStorage — how hard the device scrubs a turn's text BEFORE it falls
 * through to the hosted pool (redactor.ts). Four states, each a superset of
 * the one before it:
 *   "off"     : pre-existing behavior exactly — text is scrubbed only for
 *               agents whose tenant enabled guardrails (EDGE-A);
 *   "secrets" (DEFAULT, unset reads as this): card numbers and government
 *               ids are masked on every hosted send. Chosen as the default
 *               because a hosted model
 *               essentially never needs those verbatim to do the task, so
 *               the answer quality cost is ~zero and the leak it prevents is
 *               the expensive kind;
 *   "contact" : also masks email addresses and phone numbers. NOT the
 *               default — "draft a reply to jane@acme.com" is a legitimate
 *               task that this setting degrades;
 *   "model"   : everything above, plus a warm on-device model pass that
 *               finds the person/company/address spans regexes can't (names
 *               have no pattern). Never downloads or warms a model: with no
 *               engine already in memory this behaves exactly like
 *               "contact". */
export const EDGE_REDACTION_KEY = "edge.redaction";
/** localStorage — "0" to stop publishing this agent's edge-safe tools to the
 * browser's WebMCP model context (webmcp.ts). Unset/anything else reads as
 * ON: the published list is the server's already-curated edge-safe subset,
 * every call still goes through the server's approval gate and audit, and
 * the browser adds its own per-site consent prompt on top. */
export const EDGE_WEBMCP_KEY = "edge.webmcp";
/** localStorage — "1" to let the on-device agent use tools that send a query to
 * a named third party (localToolsWeb.ts: Wikipedia, weather, FX rates).
 * DEFAULT OFF, and the default is the security decision, not timidity.
 *
 * Once the same model can both READ THE OPENED FOLDER (localToolsFiles.ts) and
 * SEND TEXT TO A THIRD PARTY, a file's own contents become an instruction
 * channel: a README that says "look up the following on Wikipedia: <the
 * contents of .env>" is a working exfiltration path, and the model has no way
 * to know the file is lying to it. The query cap (200 chars) bounds the
 * bandwidth but does not close the channel.
 *
 * So network tools are something a person turns on, having been told what they
 * do, rather than something they discover afterwards. Off, every local tool is
 * pure computation or a read of a folder the person themselves opened, and
 * nothing they touch can leave the device. */
export const EDGE_NETWORK_TOOLS_KEY = "edge.networkTools";
/** window event fired whenever any edge preference changes, so sibling
 * components (chip / playground banner) stay in sync without a store. */
export const EDGE_PREFS_EVENT = "edge:prefs-changed";

export type ModelOverride = "auto" | "hosted" | string; // string = manifest model id

// ---------------------------------------------------------------------------
// Preference helpers (localStorage + a window event so components stay in sync
// without a global state library)
// ---------------------------------------------------------------------------

function safeLocalGet(key: string): string | null {
  try {
    if (typeof localStorage === "undefined") return null;
    return localStorage.getItem(key);
  } catch {
    return null;
  }
}

function safeLocalSet(key: string, value: string): void {
  try {
    if (typeof localStorage === "undefined") return;
    localStorage.setItem(key, value);
  } catch {
    // Best-effort.
  }
}

function dispatchPrefsEvent(): void {
  try {
    if (typeof window !== "undefined") window.dispatchEvent(new Event(EDGE_PREFS_EVENT));
  } catch {
    // Best-effort.
  }
}

export function getStoredModelOverride(): ModelOverride {
  return safeLocalGet(MODEL_OVERRIDE_KEY) || "auto";
}

export function setModelOverride(value: ModelOverride): void {
  safeLocalSet(MODEL_OVERRIDE_KEY, value);
  dispatchPrefsEvent();
}

export type EdgeRoutingPreference = "edge-first" | "hosted-only" | "ask";

/**
 * The routing preference, defaulting to "edge-first" (edge is the default
 * first priority on capable devices — capability itself is checked by the
 * router, not here; this module stays synchronous and probe-free).
 * A stored value always wins. Unset derives from the legacy keys so choices
 * made before this preference existed keep their exact effect:
 *   - model override "hosted"  → "hosted-only" (that's what the picker meant);
 *   - opt-in explicitly "0"    → "ask" (the person turned on-device off);
 *   - anything else            → "edge-first".
 */
export function getEdgeRoutingPreference(): EdgeRoutingPreference {
  const stored = safeLocalGet(EDGE_ROUTING_PREF_KEY);
  if (stored === "edge-first" || stored === "hosted-only" || stored === "ask") return stored;
  if (safeLocalGet(MODEL_OVERRIDE_KEY) === "hosted") return "hosted-only";
  if (safeLocalGet(EDGE_OPT_IN_KEY) === "0") return "ask";
  return "edge-first";
}

export function setEdgeRoutingPreference(value: EdgeRoutingPreference): void {
  safeLocalSet(EDGE_ROUTING_PREF_KEY, value);
  dispatchPrefsEvent();
}

export function getEdgeOptIn(): boolean {
  const stored = safeLocalGet(EDGE_OPT_IN_KEY);
  // Explicit choices ("1"/"0") always win and persist. With no explicit
  // choice, edge-first mode answers true — on-device is the default, and the
  // router still requires real capability + a ready model before routing.
  if (stored === "1") return true;
  if (stored === "0") return false;
  return getEdgeRoutingPreference() === "edge-first";
}

export function setEdgeOptIn(on: boolean): void {
  safeLocalSet(EDGE_OPT_IN_KEY, on ? "1" : "0");
  dispatchPrefsEvent();
}

export function getEdgeBannerDismissed(): boolean {
  return safeLocalGet(EDGE_BANNER_DISMISSED_KEY) === "1";
}

export function setEdgeBannerDismissed(): void {
  safeLocalSet(EDGE_BANNER_DISMISSED_KEY, "1");
  dispatchPrefsEvent();
}

export type EdgeFallbackPolicy = "auto" | "ask" | "never";

/**
 * The automatic-fallback preference (see EDGE_FALLBACK_POLICY_KEY above).
 * Unset/unrecognized reads as "auto" — identical to the behavior every
 * caller already had before this preference existed, per the same
 * stored-value-always-wins/default-preserves-existing-behavior contract as
 * getEdgeRoutingPreference above.
 */
export function getEdgeFallbackPolicy(): EdgeFallbackPolicy {
  const stored = safeLocalGet(EDGE_FALLBACK_POLICY_KEY);
  return stored === "ask" || stored === "never" ? stored : "auto";
}

export function setEdgeFallbackPolicy(value: EdgeFallbackPolicy): void {
  safeLocalSet(EDGE_FALLBACK_POLICY_KEY, value);
  dispatchPrefsEvent();
}

/** Escalating scrub levels — see EDGE_REDACTION_KEY above for what each one
 * masks and why "secrets" is the default rather than "off" or "contact". */
export type EdgeRedactionMode = "off" | "secrets" | "contact" | "model";

export function getEdgeRedactionMode(): EdgeRedactionMode {
  const stored = safeLocalGet(EDGE_REDACTION_KEY);
  if (stored === "off" || stored === "secrets" || stored === "contact" || stored === "model") {
    return stored;
  }
  return "secrets";
}

export function setEdgeRedactionMode(value: EdgeRedactionMode): void {
  safeLocalSet(EDGE_REDACTION_KEY, value);
  dispatchPrefsEvent();
}

/** Whether this browser publishes the agent's edge-safe tools to
 * navigator.modelContext (webmcp.ts). Default ON — see EDGE_WEBMCP_KEY. */
export function getWebMcpEnabled(): boolean {
  return safeLocalGet(EDGE_WEBMCP_KEY) !== "0";
}

export function setWebMcpEnabled(on: boolean): void {
  safeLocalSet(EDGE_WEBMCP_KEY, on ? "1" : "0");
  dispatchPrefsEvent();
}

/** Whether the on-device agent may use the third-party lookup tools. Default
 * OFF — see EDGE_NETWORK_TOOLS_KEY for why that default is load-bearing. */
export function getNetworkToolsEnabled(): boolean {
  return safeLocalGet(EDGE_NETWORK_TOOLS_KEY) === "1";
}

export function setNetworkToolsEnabled(on: boolean): void {
  safeLocalSet(EDGE_NETWORK_TOOLS_KEY, on ? "1" : "0");
  dispatchPrefsEvent();
}

/** How long a surface's own inline "ask before falling back" confirm/cancel
 * prompt waits for an answer before treating silence as Cancel — shared so
 * CallPanel/AgentPlayground/ChatPanel all give a person the same amount of
 * time to actually read and decide. */
export const EDGE_FALLBACK_ASK_TIMEOUT_MS = 20_000;

/**
 * The single decision point every chat/voice surface reaches once its
 * on-device attempt has declined or failed and the only question left is
 * whether to fall through to the platform automatically — the shared gate
 * behind CallPanel's voice pipeline (trySendTurnLocally's
 * resolveAutomaticFallback), AgentPlayground's sendViaEdge and ChatPanel's
 * inline routeTurn attempt, so "never fall back" (or "ask me first") means
 * the same thing everywhere a person can set it, not just on the voice
 * surface it originally shipped on.
 *
 *   "auto"  (default) — proceeds unchanged, today's behavior everywhere.
 *   "never" — always refuses; the caller's own refusal copy/UI takes over.
 *   "ask"   — awaits the caller-supplied `ask` prompt (each surface owns its
 *             own inline confirm/cancel UI — this module stays UI-free). A
 *             surface with no `ask` wired refuses rather than silently
 *             proceeding without asking, which would violate the person's
 *             "ask me first" choice — refusing is the safer of the two
 *             failure modes.
 */
export async function resolveAutomaticEdgeFallback(ask?: () => Promise<boolean>): Promise<boolean> {
  const policy = getEdgeFallbackPolicy();
  if (policy === "auto") return true;
  if (policy === "never") return false;
  if (!ask) return false;
  return ask();
}

/** localStorage — explicit, per-device opt-IN (unset/"0" = off) for letting
 * a completed on-device turn also become part of this agent's DURABLE memory
 * (core/agents' existing remember_fact tool, called via the already-shipped
 * POST /api/edge/tools/execute bridge — see toolBridge.ts). Deliberately NOT
 * derived from edgeOptIn/getEdgeRoutingPreference like the other prefs above:
 * "I want replies to run on my device" and "I want this device's
 * conversations to also reach our servers for the agent to remember" are two
 * separate consents, and the whole pitch of on-device mode is "stays on your
 * device" - defaulting this ON the moment someone enables edge-first would
 * quietly break that promise. OFF until the person explicitly turns it on. */
const EDGE_LEARNING_OPT_IN_KEY = "edge.learningOptIn";

export function getEdgeLearningOptIn(): boolean {
  return safeLocalGet(EDGE_LEARNING_OPT_IN_KEY) === "1";
}

export function setEdgeLearningOptIn(on: boolean): void {
  safeLocalSet(EDGE_LEARNING_OPT_IN_KEY, on ? "1" : "0");
  dispatchPrefsEvent();
}
