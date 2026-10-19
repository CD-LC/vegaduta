// Edge (on-device) leg of "Ask AI to generate the
// tool" (core/agents' CustomCodeTool "ask AI" flow). The maintainer's explicit
// requirement: "all 3 ways edge, platform, byok should support" code
// generation — platform-hosted and BYOK both already funnel through the
// server's POST /api/tools/code/generate (CodeToolGenerationService, which
// itself picks FREE-tier vs BYOK under the hood); this module is what makes
// edge the third leg, running the draft completion on-device instead.
//
// Deliberate sibling of tierRouter.ts, NOT an edit to it (tierRouter.ts and
// tier1.ts are locked by a concurrent session as of this file's authorship —
// see the coordination note in CLAUDE.md/AGENT-COORDINATION). This module
// only ever READS tier0.ts's/tier1.ts's exported session primitives —
// createTier0Session, createTier1Session, tier0Available, getTier1RunModel —
// the exact same primitives tierRouter.ts itself is built on, so on-device
// codegen behaves identically to on-device chat with respect to which tier
// runs and when. It does NOT touch tierRouter's per-conversation session
// maps (tier0Sessions/tier1Sessions keyed by chat sessionKey): a codegen
// draft is one request/response, not a turn in an ongoing conversation, so
// it opens its own short-lived session here and destroys it immediately
// after — never pollutes, never reuses, a chat session.
//
// Validation split (binding for this module): the on-device model only ever
// PROPOSES source code. It is never trusted to certify that code as safe or
// even syntactically valid — edge cannot compile or sandbox anything
// locally. Every on-device draft is re-checked against the server's
// LLM-free POST /api/tools/code/validate before generateToolOnDevice ever
// resolves ok:true; a validate call that itself fails (network, 404 on an
// undeployed endpoint, 5xx) is NOT treated as "the code must be fine" — it
// resolves ok:false so the caller falls through to the full server-side
// generate endpoint, exactly like every other edge typed failure.
//
// Silence contract: like toolBridge.ts, this module talks to the server
// through its own direct fetch (bearer header, client-side timeout) rather
// than api.ts's apiFetch/toJson — that wrapper raises a global error toast
// on every non-2xx response, which would pop a spurious "failed" banner for
// a validate call that's merely unavailable on this deployment, moments
// before the caller's fallback to the full server generate path succeeds.
// Edge failures stay silent; the caller decides what (if anything) to show.

import { auth } from "../auth";
import { API_BASE } from "../api";
import {
  detectCapabilities,
  edgeEnabled,
  getEdgeOptIn,
  getEdgeRoutingPreference,
  getStoredModelOverride,
} from "./capabilities";
import { createTier0Session, tier0Available, Tier0Error } from "./tier0";
import { createTier1Session, getTier1RunModel, Tier1Error } from "./tier1";
import { applyViewAsHeader } from "../viewAs";

// ---------------------------------------------------------------------------
// Types
// ---------------------------------------------------------------------------

export type CodegenLanguage = "JAVA" | "PYTHON";

export interface CodegenValidationError {
  message?: string;
  line?: number;
  [key: string]: unknown;
}

export interface EdgeCodegenSuccess {
  ok: true;
  /** Which local tier produced the draft — informational, for UI copy
   * ("generated on-device (larger model)" etc). */
  tier: 0 | 1;
  sourceCode: string;
  /** Authoritative verdict from POST /api/tools/code/validate — never
   * self-reported by the local model. */
  valid: boolean;
  errors: CodegenValidationError[];
  warnings: string[];
  language: CodegenLanguage;
}

export type EdgeCodegenFailureReason =
  | "edge-disabled" // opted out / no eligible tier ready on this device
  | "no-local-tier" // same bucket as edge-disabled, kept distinct for logs
  | "generation-failed" // the on-device completion itself errored
  | "empty-reply" // model returned nothing usable
  | "validate-unavailable" // couldn't get an authoritative verdict — never
  // treated as "must be fine"
  | "aborted"; // caller's AbortSignal fired

export interface EdgeCodegenFailure {
  ok: false;
  reason: EdgeCodegenFailureReason;
  detail?: string;
}

export type EdgeCodegenResult = EdgeCodegenSuccess | EdgeCodegenFailure;

export interface GenerateOnDeviceOptions {
  requirement: string;
  /** Canned preset label or freeform "write your own" text — same field the
   * server generate endpoint's CodeToolGenerationRequest.template carries. */
  template?: string | null;
  language: CodegenLanguage;
  /** Prompt context only, never persisted here. */
  toolName?: string | null;
  signal?: AbortSignal;
}

// ---------------------------------------------------------------------------
// Prompt construction — mirrors CodeToolGenerationService's system prompt
// (core/agents/.../tools/code/generate/CodeToolGenerationService.java),
// trimmed for a 1-3B-class on-device model: shorter instructions leave more
// of the context budget for the generated program itself.
// ---------------------------------------------------------------------------

function systemPromptFor(language: CodegenLanguage): string {
  if (language === "PYTHON") {
    return (
      "You write a single Python tool for a sandboxed agent platform. " +
      "Output ONLY the code, no prose, no markdown fence. The module must " +
      "define a top-level function run(input) taking a dict and returning " +
      "any JSON-serializable value. No file, network, process, subprocess, " +
      "or os-module access is available - the sandbox blocks it. Keep the " +
      "program self-contained, standard library only."
    );
  }
  return (
    "You write a single Java tool for a sandboxed agent platform. Output " +
    "ONLY the code, no prose, no markdown fence. It must be a public class " +
    "named Tool with a public static Object run(java.util.Map<String, " +
    "Object> input) method returning any JSON-serializable value. No file, " +
    "network, process, or reflection access is available - the sandbox " +
    "blocks it. Keep the program self-contained, JDK standard library only."
  );
}

function userPromptFor(
  requirement: string,
  template: string | null | undefined,
  toolName: string | null | undefined
): string {
  const parts = [`Requirement: ${requirement.trim()}`];
  if (template && template.trim()) parts.push(`Template/shape to follow: ${template.trim()}`);
  if (toolName && toolName.trim()) parts.push(`Intended tool name (context only): ${toolName.trim()}`);
  return parts.join("\n");
}

/** Strip a leading/trailing ``` or ```lang fence - local models reliably add
 * one despite being told not to. Mirrors the server's own fence-strip. */
function stripFence(text: string): string {
  let out = text.trim();
  if (out.startsWith("```")) {
    out = out
      .replace(/^```[a-zA-Z0-9]*\s*/, "")
      .replace(/```\s*$/, "")
      .trim();
  }
  return out;
}

// ---------------------------------------------------------------------------
// Tier eligibility — same gating rules routeTurn applies (opt-in, routing
// preference, hosted override) so codegen never runs on-device against the
// person's stated preference, even though this is a one-shot call outside
// the chat router. Never rejects.
// ---------------------------------------------------------------------------

async function eligibleTier(): Promise<0 | 1 | null> {
  try {
    if (!edgeEnabled() || typeof window === "undefined") return null;
    if (getStoredModelOverride() === "hosted") return null;
    if (getEdgeRoutingPreference() === "hosted-only") return null;
    if (!getEdgeOptIn()) return null;
    const caps = await detectCapabilities();
    // Prefer tier 1 (WebLLM) when a fitting model is already downloaded -
    // same best-fit preference the chat router uses; tier 0 (Chrome
    // built-in) is the fallback, not the default, since it tends to be the
    // smaller/less code-capable model.
    const t1Model = await getTier1RunModel(caps);
    if (t1Model) return 1;
    if (await tier0Available()) return 0;
    return null;
  } catch {
    return null;
  }
}

async function runOneShot(
  tier: 0 | 1,
  systemPrompt: string,
  userPrompt: string,
  signal?: AbortSignal
): Promise<string> {
  if (tier === 1) {
    const caps = await detectCapabilities();
    const session = await createTier1Session(caps, { systemPrompt });
    try {
      return await session.prompt(userPrompt, undefined, signal);
    } finally {
      session.destroy();
    }
  }
  const session = await createTier0Session(systemPrompt);
  try {
    return await session.prompt(userPrompt, undefined, signal);
  } finally {
    session.destroy();
  }
}

// ---------------------------------------------------------------------------
// Server-side validation call — direct fetch, never through api.ts (see the
// silence-contract note at the top of this file).
// ---------------------------------------------------------------------------

interface ValidateVerdict {
  valid: boolean;
  errors: CodegenValidationError[];
  warnings: string[];
}

async function bearerHeaders(): Promise<Record<string, string>> {
  const headers: Record<string, string> = { "Content-Type": "application/json" };
  try {
    const token = await auth.getToken({ silent: true });
    if (token) headers.Authorization = `Bearer ${token}`;
    applyViewAsHeader(headers);
  } catch {
    // Fall through unauthenticated - the server will 401 and this module
    // treats that like any other validate-unavailable outcome.
  }
  return headers;
}

/** POST /api/tools/code/validate — thin, LLM-free sandbox check. Throws on
 * anything short of a clean 2xx JSON verdict; callers must not treat a
 * thrown error as "the code is fine." */
async function validateOnServer(
  language: CodegenLanguage,
  toolName: string | null | undefined,
  sourceCode: string,
  signal?: AbortSignal
): Promise<ValidateVerdict> {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), 20_000);
  const onAbort = () => controller.abort();
  signal?.addEventListener("abort", onAbort);
  try {
    const res = await fetch(`${API_BASE}/api/tools/code/validate`, {
      method: "POST",
      headers: await bearerHeaders(),
      body: JSON.stringify({ language, toolName: toolName || null, sourceCode }),
      signal: controller.signal,
    });
    if (!res.ok) {
      throw new Error(`validate endpoint returned HTTP ${res.status}`);
    }
    const body = await res.json();
    return {
      valid: Boolean(body?.valid),
      errors: Array.isArray(body?.errors) ? body.errors : [],
      warnings: Array.isArray(body?.warnings) ? body.warnings : [],
    };
  } finally {
    clearTimeout(timer);
    signal?.removeEventListener("abort", onAbort);
  }
}

// ---------------------------------------------------------------------------
// Entry point
// ---------------------------------------------------------------------------

/**
 * Attempt on-device generation for the "Ask AI" tool-generation flow.
 * Resolves `{ ok:false, reason:"no-local-tier" }` immediately (no model
 * load, no network call) when edge can't/shouldn't serve this — including
 * when the person hasn't opted in to on-device mode — so the caller can
 * fall straight through to the server generate endpoint
 * (api.generateCustomCodeTool) without ever touching the device.
 *
 * On an on-device completion, the draft is ALWAYS re-checked against the
 * server's authoritative validate endpoint before this resolves ok:true — a
 * `valid:false` verdict still resolves ok:true (same "never lose the draft,
 * never hide the rejection" posture the server generate flow itself uses),
 * so the caller can choose to show the rejected draft with its errors, or
 * fall back to a full server-side regenerate. A validate call that itself
 * fails resolves ok:false — edge-generated code that was never actually
 * checked must never reach the UI as if it had been.
 *
 * Never throws.
 */
export async function generateToolOnDevice(options: GenerateOnDeviceOptions): Promise<EdgeCodegenResult> {
  const { requirement, template, language, toolName, signal } = options;
  try {
    if (!requirement || !requirement.trim()) {
      return { ok: false, reason: "generation-failed", detail: "empty requirement" };
    }
    const tier = await eligibleTier();
    if (tier === null) return { ok: false, reason: "no-local-tier" };

    let raw: string;
    try {
      if (signal?.aborted) return { ok: false, reason: "aborted" };
      raw = await runOneShot(tier, systemPromptFor(language), userPromptFor(requirement, template, toolName), signal);
    } catch (err) {
      const aborted = (err instanceof Tier0Error || err instanceof Tier1Error) && err.code === "aborted";
      if (aborted) return { ok: false, reason: "aborted" };
      const detail = err instanceof Error ? err.message : String(err);
      return { ok: false, reason: "generation-failed", detail };
    }

    const sourceCode = stripFence(raw);
    if (!sourceCode) return { ok: false, reason: "empty-reply" };

    try {
      const verdict = await validateOnServer(language, toolName, sourceCode, signal);
      return {
        ok: true,
        tier,
        sourceCode,
        valid: verdict.valid,
        errors: verdict.errors,
        warnings: verdict.warnings,
        language,
      };
    } catch (err) {
      const detail = err instanceof Error ? err.message : String(err);
      return { ok: false, reason: "validate-unavailable", detail };
    }
  } catch (err) {
    // Belt-and-braces, matching routeTurn's own absolute "never throw to the
    // caller" contract.
    return {
      ok: false,
      reason: "generation-failed",
      detail: err instanceof Error ? err.message : String(err),
    };
  }
}

/** True when edge is in a position to attempt codegen right now (opted in,
 * routing preference allows it, a local tier is ready). Cheap UI hook for
 * showing/hiding an "on-device draft" affordance without actually running a
 * generation. Never rejects. */
export async function edgeCodegenReady(): Promise<boolean> {
  return (await eligibleTier()) !== null;
}
