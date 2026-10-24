// The Phone app studio's model router: on-device first, cloud second.
//
//   device  WebLLM (tier 1) when a model is ALREADY downloaded, else Chrome's
//           built-in model (tier 0) only when it is already "available" -
//           never trigger Chrome's multi-GB download from here. Free, private,
//           streams tokens so the person watches the code being written.
//   cloud   POST /api/assist/code-edit (FREE-tier/BYOK aware server-side;
//           the studio's own system prompt, no form-field framing - falls
//           back to /api/assist/draft on a core that predates it). Replies are capped server-side
//           (app.agents.default-max-tokens), so the studio only ever asks it
//           for SEARCH/REPLACE edit blocks, never whole apps.
//
// Honours the person's routing preference: "hosted-only" or a pinned hosted
// override skips the device leg. Never throws - resolves {ok:false}.

import { auth } from "../../lib/auth";
import { API_BASE } from "../../lib/api";
import {
  detectCapabilities,
  edgeEnabled,
  getEdgeRoutingPreference,
  getStoredModelOverride,
} from "../../lib/edge/capabilities";
import { createTier0Session, tier0Availability, Tier0Error } from "../../lib/edge/tier0";
import {
  createTier1Session,
  estimateTier1Tokens,
  getTier1RunModel,
  resolveTier1ContextWindow,
  Tier1Error,
} from "../../lib/edge/tier1";
import { applyViewAsHeader } from "../../lib/viewAs";

export type EngineKind = "device" | "cloud";

export interface EngineResult {
  ok: boolean;
  text: string;
  engine: EngineKind | null;
  modelName: string | null;
  error?: string;
  /** True when this call reached the cloud endpoint (answered or not), so
   * callers can cap how many cloud round-trips one edit costs. */
  triedCloud?: boolean;
}

export interface RunOptions {
  system: string;
  user: string;
  onToken?: (delta: string) => void;
  signal?: AbortSignal;
  /** "auto" = device then cloud; "device"/"cloud" = that leg only. */
  prefer?: "auto" | EngineKind;
  /** Reply size the caller needs; a device model whose context can't hold
   * prompt + reply is skipped rather than truncated mid-app. */
  replyTokens?: number;
}

export interface DeviceEngineInfo {
  tier: 0 | 1;
  /** "WebLLM · WebGPU" or "Chrome built-in AI" - shown next to the model. */
  runtime: string;
  modelName: string;
  contextWindow: number;
}

function deviceAllowed(): boolean {
  if (typeof window === "undefined" || !edgeEnabled()) return false;
  if (getStoredModelOverride() === "hosted") return false;
  if (getEdgeRoutingPreference() === "hosted-only") return false;
  return true;
}

/** Which on-device engine would run right now, or null. Never downloads. */
export async function deviceEngine(): Promise<DeviceEngineInfo | null> {
  try {
    if (!deviceAllowed()) return null;
    const caps = await detectCapabilities();
    const model = await getTier1RunModel(caps);
    if (model) {
      const m = model as { displayName?: string; id: string };
      return { tier: 1, runtime: "WebLLM · WebGPU", modelName: m.displayName || m.id, contextWindow: resolveTier1ContextWindow(model) };
    }
    if ((await tier0Availability()) === "available") {
      return { tier: 0, runtime: "Chrome built-in AI", modelName: "Gemini Nano", contextWindow: 6000 };
    }
    return null;
  } catch {
    return null;
  }
}

/** Where a cloud request would go. Mirrors AssistService.buildChatClient:
 * FREE plan -> the VegaDūta platform pool; otherwise the tenant's own active
 * provider key (BYOK). Read silently - a failure only degrades the label. */
export interface CloudRoute {
  kind: "platform" | "byok" | "signed-out" | "unknown";
  /** Short label, e.g. "VegaDūta platform AI" or "Your OPENAI key". */
  label: string;
  detail: string;
}

async function silentGet(path: string): Promise<unknown> {
  const token = await auth.getToken({ silent: true }).catch((): string | null => null);
  if (!token) throw new Error("signed-out");
  const res = await fetch(`${API_BASE}${path}`, { headers: applyViewAsHeader({ Authorization: `Bearer ${token}` }) });
  if (!res.ok) throw new Error(String(res.status));
  return res.json();
}

function prettyProvider(name: string): string {
  const map: Record<string, string> = {
    OPENAI: "OpenAI", ANTHROPIC: "Anthropic", GEMINI: "Google Gemini", GOOGLE: "Google Gemini",
    MISTRAL: "Mistral", GROQ: "Groq", OPENROUTER: "OpenRouter", DEEPSEEK: "DeepSeek", XAI: "xAI",
  };
  return map[name] || name.charAt(0) + name.slice(1).toLowerCase().replace(/_/g, " ");
}

// The route only changes when the person swaps plan or provider, so every
// successful edit shouldn't pay two extra GETs to relabel it.
const ROUTE_TTL_MS = 5 * 60_000;
let routeCache: { at: number; route: CloudRoute } | null = null;

export async function cloudRoute(opts: { fresh?: boolean } = {}): Promise<CloudRoute> {
  if (!opts.fresh && routeCache && Date.now() - routeCache.at < ROUTE_TTL_MS) return routeCache.route;
  const route = await lookupCloudRoute();
  if (route.kind === "platform" || route.kind === "byok") routeCache = { at: Date.now(), route };
  return route;
}

async function lookupCloudRoute(): Promise<CloudRoute> {
  try {
    const quota = (await silentGet("/api/tenant/usage-quota")) as { plan?: string; usesPlatformPool?: boolean };
    if (quota?.plan === "FREE") {
      return { kind: "platform", label: "VegaDūta platform AI", detail: "Hosted model pool included with your plan. Counts toward your monthly AI tokens." };
    }
    const providers = (await silentGet("/api/providers").catch((): unknown[] => [])) as { providerName?: string; status?: string }[];
    const active = Array.isArray(providers) ? providers.find((p) => p?.status === "ACTIVE" && p.providerName) : null;
    if (active?.providerName) {
      const name = prettyProvider(active.providerName);
      return { kind: "byok", label: `Your ${name} key`, detail: `Runs on your own ${name} API key (BYOK). Billed by ${name}.` };
    }
    if (quota?.usesPlatformPool) {
      return { kind: "platform", label: "VegaDūta platform AI", detail: "Hosted model pool. Counts toward your monthly AI tokens." };
    }
    return { kind: "unknown", label: "no provider connected", detail: "No AI provider is connected. Add one under AI models." };
  } catch (e) {
    if (e instanceof Error && e.message === "signed-out") {
      return { kind: "signed-out", label: "sign in to use it", detail: "Sign in to use the cloud model." };
    }
    return { kind: "unknown", label: "platform or your provider", detail: "Couldn't check which cloud model you're on." };
  }
}

// Startup-failure retry (2026-09-28). "The on-device model is taking too
// long to start" is the WAIT ending, not the load dying: tier1's
// withEngineLoadDeadline leaves the timed-out acquireEngine running in the
// background, and tier0's create-timeout is just the caller's wait ending
// on Chrome's own first-use init. A retry attaches to that still-running
// load (or to a now-warm engineInstance) and usually resolves fast - so a
// start-up-class error is retried here instead of surfaced. Only
// startup/transient classes retry; "unsupported"/"unavailable"/"aborted"
// are final and must reach runModel's fallthrough immediately.
const DEVICE_SESSION_ATTEMPTS = 3;
const DEVICE_TURN_ATTEMPTS = 2; // first try + one retry on empty/stalled replies
const DEVICE_RETRY_DELAY_MS = 1_500;

function abortError(): Error {
  const e = new Error("Aborted");
  e.name = "AbortError";
  return e;
}

function waitForRetry(ms: number, signal?: AbortSignal): Promise<void> {
  return new Promise((resolve, reject) => {
    if (signal?.aborted) {
      reject(abortError());
      return;
    }
    const onAbort = () => {
      clearTimeout(timer);
      reject(abortError());
    };
    const timer = setTimeout(() => {
      signal?.removeEventListener("abort", onAbort);
      resolve();
    }, ms);
    signal?.addEventListener("abort", onAbort);
  });
}

function isRetriableStartError(e: unknown): boolean {
  if (e instanceof Tier1Error) return e.code === "engine-failed" || e.code === "device-lost";
  if (e instanceof Tier0Error) return e.code === "create-timeout" || e.code === "create-failed";
  return false;
}

/** Turn-level failures worth one retry on the same warm engine. Small
 * on-device models stochastically return empty replies or stall mid-stream
 * (the same reason tierRouter retries once on empty); "context-window-
 * exceeded"/"aborted"/"unsupported" are never retryable. */
function isRetriableTurnError(e: unknown): boolean {
  if (e instanceof Tier1Error) {
    return e.code === "prompt-failed" || e.code === "generation-timeout" || e.code === "device-lost";
  }
  if (e instanceof Tier0Error) {
    return e.code === "prompt-failed" || e.code === "generation-timeout" || e.code === "turn-ceiling-exceeded";
  }
  return false;
}

async function openDeviceSession(
  info: DeviceEngineInfo,
  o: RunOptions
): Promise<Awaited<ReturnType<typeof createTier1Session>> | Awaited<ReturnType<typeof createTier0Session>>> {
  if (info.tier === 1) {
    const caps = await detectCapabilities();
    return createTier1Session(caps, { systemPrompt: o.system, disableTools: true });
  }
  return createTier0Session(o.system);
}

async function runDevice(info: DeviceEngineInfo, o: RunOptions): Promise<string> {
  let lastErr: unknown = new Error("The on-device model could not start.");
  for (let attempt = 1; attempt <= DEVICE_SESSION_ATTEMPTS; attempt++) {
    if (o.signal?.aborted) throw abortError();
    try {
      const session = await openDeviceSession(info, o);
      try {
        for (let turn = 1; ; turn++) {
          try {
            const text = await session.prompt(o.user, o.onToken, o.signal);
            if (text.trim() || turn >= DEVICE_TURN_ATTEMPTS) return text;
            // Empty reply - one retry on the same warm engine.
          } catch (e) {
            if (o.signal?.aborted || turn >= DEVICE_TURN_ATTEMPTS || !isRetriableTurnError(e)) throw e;
            await waitForRetry(DEVICE_RETRY_DELAY_MS, o.signal);
          }
        }
      } finally {
        session.destroy();
      }
    } catch (e) {
      if (o.signal?.aborted) throw e;
      lastErr = e;
      if (!isRetriableStartError(e) || attempt >= DEVICE_SESSION_ATTEMPTS) throw e;
      await waitForRetry(DEVICE_RETRY_DELAY_MS, o.signal);
    }
  }
  throw lastErr;
}

let codeEditMissing = false;

// The cloud assist endpoints answer in ONE non-streamed reply, so a slow
// turn (a whole-app write on a small pool model can run minutes) currently
// hangs the UI until the response or the user gives up. Cap the wait so a
// stalled backend still ends the turn with a clear message instead of an
// eternal "waiting".
const CLOUD_TURN_TIMEOUT_MS = 300_000;

async function runCloud(o: RunOptions): Promise<string> {
  const headers: Record<string, string> = { "Content-Type": "application/json" };
  const token = await auth.getToken({ silent: true }).catch((): string | null => null);
  if (token) headers.Authorization = `Bearer ${token}`;
  applyViewAsHeader(headers);
  // Combine the caller's stop signal with the ceiling; whichever fires
  // first aborts the fetch. A timeout abort surfaces as TimeoutError and is
  // translated in runModel's catch.
  const signal = o.signal
    ? AbortSignal.any([o.signal, AbortSignal.timeout(CLOUD_TURN_TIMEOUT_MS)])
    : AbortSignal.timeout(CLOUD_TURN_TIMEOUT_MS);
  let res: Response | null = null;
  if (!codeEditMissing) {
    res = await fetch(`${API_BASE}/api/assist/code-edit`, {
      method: "POST",
      headers,
      signal,
      body: JSON.stringify({ system: o.system, prompt: o.user }),
    });
    // Web can ship before core: an older core has no /code-edit.
    if (res.status === 404 || res.status === 405) {
      codeEditMissing = true;
      res = null;
    }
  }
  if (!res) {
    res = await fetch(`${API_BASE}/api/assist/draft`, {
      method: "POST",
      headers,
      signal,
      body: JSON.stringify({
        fieldLabel: "Code edit for a single-file mobile web app (reply in the exact format requested, no commentary)",
        instruction: `${o.system}\n\n${o.user}`,
      }),
    });
  }
  if (res.status === 401) throw new Error("Sign in to use the cloud model.");
  if (!res.ok) {
    // Core replies { error: "<actionable text>" } (e.g. "No active LLM
    // provider - activate one on the Providers page first."). Show it, so the
    // user can tell a missing key from an outage.
    const err = (await res.json().catch((): null => null)) as { error?: unknown } | null;
    const why = typeof err?.error === "string" && err.error.trim() ? ` ${err.error.trim()}` : "";
    throw new Error(`Cloud model unavailable (HTTP ${res.status}).${why}`);
  }
  const body = (await res.json().catch((): null => null)) as { suggestion?: unknown } | null;
  const text = typeof body?.suggestion === "string" ? body.suggestion : "";
  // No streaming on this endpoint - hand the whole reply to the listener so
  // the live code view still fills in.
  if (text) o.onToken?.(text);
  return text;
}

export async function runModel(o: RunOptions): Promise<EngineResult> {
  const prefer = o.prefer || "auto";
  let deviceError: string | undefined;
  if (prefer !== "cloud") {
    const info = await deviceEngine();
    const need = estimateTier1Tokens(o.system + o.user) + (o.replyTokens || 1200);
    if (info && need <= info.contextWindow) {
      try {
        const text = await runDevice(info, o);
        if (text.trim()) return { ok: true, text, engine: "device", modelName: `${info.runtime}: ${info.modelName}` };
        deviceError = "The on-device model returned nothing.";
      } catch (e) {
        if (o.signal?.aborted) return { ok: false, text: "", engine: null, modelName: null, error: "Stopped." };
        deviceError = e instanceof Error ? e.message : String(e);
      }
    } else if (info) {
      deviceError = "This request is bigger than the on-device model's memory window.";
    } else {
      deviceError = "No on-device model is ready.";
    }
    if (prefer === "device") return { ok: false, text: "", engine: null, modelName: null, error: deviceError };
  }
  try {
    const text = await runCloud(o);
    if (!text.trim()) return { ok: false, text: "", engine: "cloud", modelName: null, error: "The cloud model returned nothing.", triedCloud: true };
    const route = await cloudRoute();
    return { ok: true, text, engine: "cloud", modelName: route.label, triedCloud: true };
  } catch (e) {
    if (o.signal?.aborted) return { ok: false, text: "", engine: null, modelName: null, error: "Stopped." };
    const timedOut = e instanceof Error && e.name === "TimeoutError";
    const msg = timedOut
      ? "The model took too long to answer. Try a smaller change, or check that the model pool on your install is healthy."
      : e instanceof Error ? e.message : String(e);
    return { ok: false, text: "", engine: null, modelName: null, error: deviceError ? `${deviceError} ${msg}` : msg, triedCloud: true };
  }
}

// ---------------------------------------------------------------------------
// Prompts

export const EDIT_SYSTEM = [
  "You edit a single-file mobile web app: one HTML document with inline <style> and <script>.",
  "The app may have SEVERAL screens (sections toggled by location.hash or a show() helper) - preserve them all; edit only what the request asks.",
  "Reply ONLY with edit blocks in exactly this format, nothing else:",
  "<<<<<<< SEARCH",
  "exact existing lines copied from the app",
  "=======",
  "the new lines",
  ">>>>>>> REPLACE",
  "Rules:",
  "- SEARCH must copy existing lines EXACTLY and be unique. Keep blocks small (a few lines).",
  "- Never edit or copy the vd:connect placeholder comment - the studio manages it.",
  "- To add something, SEARCH a nearby existing line and repeat it in REPLACE together with the new code.",
  "- Use several blocks for several changes. Change only what the request needs.",
  "- After the last block, write exactly one line: NOTE: <one plain sentence describing what changed, for a non-technical user - no file names, no jargon>.",
  "- Keep it offline: no external scripts, fonts or frameworks. Plain JavaScript.",
  "- The skeleton script already defines esc(s), load(key, default) and save(key, value) at top level. Use them; NEVER declare const/let/var/function esc, load or save again (that throws a SyntaxError and blanks the app).",
  "- File upload for end users: use <input type=\"file\"> (add accept=\"image/*\" for photos), read it with FileReader, preview images in an <img>, keep the result in localStorage when small, and show a friendly message for oversized or wrong files. Never post files to made-up URLs.",
  "- Use the CSS variables var(--primary) var(--bg) var(--surface) var(--text) var(--muted) var(--radius) for colours. Do not edit the studio:theme block.",
  "- Save user data with localStorage. Keep it touch-friendly (tap targets 44px+).",
  "- Device features are allowed when asked: camera via navigator.mediaDevices.getUserMedia({video:true}), Bluetooth via navigator.bluetooth.requestDevice, vibration via navigator.vibrate, share via navigator.share. Always inside a tap handler and with a graceful fallback if the feature is missing.",
  "- If the source contains a vd:connect placeholder, the owner attached platform services; it lists their call names. Call one with await window.vd.call(name, text) (resolves to a string). Wire real buttons/forms to them when asked, with clear success/failure UI.",
  "- If the app block enables sign-in, these exist: await window.vd.auth.user() -> {name,email}|null, await window.vd.auth.require() -> asks the host to sign the user in (renders a trusted overlay OUTSIDE the app) and resolves to the user, window.vd.auth.signOut(). NEVER draw a password field inside the app - the host owns credentials; you only call vd.auth.* and read name/email from the result.",
  "- If the app block enables AI, await window.vd.ai.chat(prompt) answers on a hosted model - use it for features the owner asked to power with AI.",
].join("\n");

/** `target` is describeTarget()'s short pointer to the tapped element -
 * never its markup, which is already in APP. */
export function editPrompt(html: string, request: string, target?: string | null): string {
  const parts = [`APP:\n${html}`];
  if (target) {
    parts.push(`THE USER TAPPED ONE ELEMENT - the request is about it:\n${target}`);
  }
  parts.push(`REQUEST: ${request}`);
  return parts.join("\n\n");
}

export const CREATE_SYSTEM = [
  "You write small, polished mobile web apps as ONE self-contained HTML file.",
  "Reply with ONLY the HTML document, starting with <!doctype html> and ending with </html>. No markdown fences, no explanation.",
  "Requirements: mobile-first layout; inline <style> and <script>; no external scripts, fonts or images;",
  "keep the studio:theme block from the skeleton and use its CSS variables for every colour;",
  "save user data with localStorage; big tap targets; realistic sample content.",
  "When the request needs more than one screen, build MULTIPLE views inside the one file: a <section> per screen and a tiny router (listen to hashchange, or a show(id) helper on nav taps) with a bottom nav bar - never separate files or links to pages that do not exist.",
  "Out-of-the-box features when asked: file/photo upload via <input type=\"file\"> + FileReader with an image preview; dark mode; search/filter; export as a downloaded file. Device features when asked: camera via navigator.mediaDevices.getUserMedia({video:true}) showing a <video>, Bluetooth via navigator.bluetooth.requestDevice, vibration via navigator.vibrate, share via navigator.share. Always inside a tap handler, always with a friendly fallback if the API is missing.",
  "If the request mentions connecting an agent or workflow, prefer window.vd.call(alias, text) for names the owner wired in, or add a fetch() POST to the endpoint URL they gave, with clear success/failure feedback. Never create password fields - sign-in is host-rendered via await window.vd.auth.require() when the app needs it.",
  "The skeleton script already defines esc(s), load(key, default) and save(key, value) at top level - use them and never declare esc, load or save again.",
  "Stay under ~300 lines - finish the file, never trail off.",
].join("\n");

export function createPrompt(skeleton: string, description: string): string {
  return `SKELETON (fill it in):\n${skeleton}\n\nBUILD THIS APP: ${description}`;
}
