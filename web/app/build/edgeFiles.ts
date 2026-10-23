// Edge (on-device) leg of App Builder generation — sibling of
// ../lib/edge/codegen.ts, which does the same thing for CustomCodeTool
// drafts. Here the artifact is a file SET ({files:[{path,content}]}), the
// exact wire shape app-builder's POST /projects/{id}/generate/apply
// accepts, and exactly the "posted back as already-generated files" edge
// path AppBuilderCompletionService's javadoc prescribes.
//
// Why this exists: the model runs on the user's own hardware (WebLLM /
// WebGPU, or Chrome's built-in model), so generation costs the platform
// and the user nothing. When no local tier is eligible this resolves
// {ok:false, reason:"no-local-tier"} and the caller falls through to the
// hosted /generate route — same opt-in/routing-preference gating as
// codegen.ts, so a user who picked "hosted only" is never surprised by a
// local model doing the work.
//
// The on-device model only ever PROPOSES files. app-builder still applies
// them in a code-intel scratch workspace and runs the project's check
// command before committing — the server-side verify is unchanged, this
// file just moves where the completion happens.
//
// Never throws.

import {
  detectCapabilities,
  edgeEnabled,
  getEdgeOptIn,
  getEdgeRoutingPreference,
  getStoredModelOverride,
} from "../lib/edge/capabilities";
import { createTier0Session, tier0Available, Tier0Error } from "../lib/edge/tier0";
import { createTier1Session, getTier1RunModel, Tier1Error } from "../lib/edge/tier1";
import type { AppBuilderEditContext, AppBuilderFileWrite } from "./lib";

export interface EdgeFilesSuccess {
  ok: true;
  tier: 0 | 1;
  files: AppBuilderFileWrite[];
  /** Which on-device model produced the files (tier 1's manifest id /
   * display name). Null for tier 0 - the browser's built-in model exposes
   * no model id. Lets the UI name the engine that generated this change. */
  modelName?: string | null;
  /** The model's own one-sentence explanation of the change — forwarded to
   * POST /generate/apply's `summary` so the chat entry reads the same as a
   * hosted turn's. Null when the model produced no summary (small models
   * sometimes skip it). */
  summary?: string | null;
}

export type EdgeFilesFailureReason =
  | "no-local-tier"
  | "generation-failed"
  | "empty-reply"
  | "bad-shape" // model replied but not parseable as {files:[...]}
  | "unsafe-writes" // only rewrote existing files it was never shown in full
  | "aborted";

export interface EdgeFilesFailure {
  ok: false;
  reason: EdgeFilesFailureReason;
  detail?: string;
}

export type EdgeFilesResult = EdgeFilesSuccess | EdgeFilesFailure;

export interface GenerateFilesOptions {
  instruction: string;
  /** Template manifest name (e.g. "Node + React") — goes into the prompt
   * as the stack hint, mirroring the server prompt. */
  stack?: string | null;
  /** Design context: wireframe spec, extracted palette, SVG markup, asset
   * names — composed by designInputs.ts. Text-only, so it works on every
   * on-device model (no vision needed). */
  context?: string | null;
  /** A failed check command's output, for the corrective retry. */
  priorFailure?: string | null;
  /** The project's existing files for this edit (POST /generate/context).
   * Without it the model would write full files it has never seen. */
  existing?: AppBuilderEditContext | null;
  signal?: AbortSignal;
}

// Mirrors AppBuilderCompletionService.SYSTEM_PROMPT, trimmed for a
// 1-3B-class model: shorter rules leave more context budget for the code.
const SYSTEM_PROMPT =
  "You are an autonomous software engineer editing a small app for a " +
  "non-technical user. Reply with ONLY a single JSON object, no markdown " +
  "fences, no prose:\n" +
  '{"summary": "one plain sentence for a non-technical user describing what ' +
  'changed (no file names, no jargon)", "files": [{"path": "relative/path", ' +
  '"content": "full new file content"}]}\n' +
  "Write the FULL content of every file you create or change — never " +
  "partial diffs. Keep changes minimal. Never touch lockfiles, CI config, " +
  "or secrets. Reference uploaded assets by their path under assets/. " +
  "Only write an EXISTING file if it is shown in full below; otherwise " +
  "put the change in a new file.";

// Room for existing files in the prompt, by tier: Chrome's built-in model
// has a ~6k-token window, WebLLM models 4k-32k.
const FILES_BUDGET: Record<0 | 1, number> = { 0: 6_000, 1: 16_000 };

interface PromptFiles {
  text: string;
  /** Existing paths whose FULL content is in the prompt - the only existing
   * files the reply may overwrite. */
  writable: Set<string>;
}

export function filesForPrompt(existing: AppBuilderEditContext | null | undefined, budget: number): PromptFiles {
  const writable = new Set<string>();
  if (!existing) return { text: "", writable };
  const parts: string[] = [];
  let used = 0;
  const readOnly: string[] = [];
  for (const f of existing.files || []) {
    if (f.full && used + f.content.length <= budget) {
      parts.push(`# ${f.path}\n${f.content}`);
      used += f.content.length;
      writable.add(f.path);
    } else {
      readOnly.push(f.path);
    }
  }
  const list = (existing.project_files || []).slice(0, 120);
  const out: string[] = [];
  if (list.length) out.push(`PROJECT FILES:\n${list.join("\n")}`);
  if (parts.length) out.push(`EXISTING FILES (shown in full):\n${parts.join("\n\n")}`);
  if (readOnly.length) out.push(`TOO LARGE TO SHOW - do not write these: ${readOnly.join(", ")}`);
  return { text: out.join("\n\n"), writable };
}

/** Drop writes to existing files the model was not shown in full - a
 * full-file write of those would delete everything it could not see. */
export function safeWrites(
  files: AppBuilderFileWrite[],
  existing: AppBuilderEditContext | null | undefined,
  writable: Set<string>
): AppBuilderFileWrite[] {
  const norm = (p: string) => normalizeWritePath(p).toLowerCase();
  const exists = new Set([...(existing?.project_files || []), ...(existing?.files || []).map((f) => f.path)].map(norm));
  const canWrite = new Set([...writable].map(norm));
  return files.filter((f) => !exists.has(norm(f.path)) || canWrite.has(norm(f.path)));
}

/** `./src\App.tsx` and `/src/App.tsx` are the same file as `src/App.tsx`. */
export function normalizeWritePath(p: string): string {
  return p.replace(/\\/g, "/").replace(/^(\.?\/)+/, "").trim();
}

/** Paths a model must never write, whatever the prompt said: traversal,
 * CI config, secrets and lockfiles. */
export function isForbiddenWritePath(p: string): boolean {
  const n = normalizeWritePath(p).toLowerCase();
  if (!n || n.split("/").some((seg) => seg === ".." || seg === "")) return true;
  return (
    n.startsWith(".github/") ||
    /(^|\/)\.env(\.|$)/.test(n) ||
    /(^|\/)(package-lock\.json|yarn\.lock|pnpm-lock\.yaml|bun\.lockb?)$/.test(n)
  );
}

function userPromptFor(o: GenerateFilesOptions, filesText: string): string {
  const parts = [
    `STACK: ${o.stack?.trim() || "web app"}`,
    `INSTRUCTION:\n${o.instruction.trim()}`,
  ];
  if (o.context?.trim()) {
    parts.push(
      `DESIGN CONTEXT (honor it when writing UI code):\n${o.context.trim().slice(0, 8000)}`
    );
  }
  if (o.priorFailure?.trim()) {
    parts.push(
      "The previous attempt's build/test FAILED with this output — fix the " +
        `files so it succeeds:\n${o.priorFailure.trim().slice(0, 4000)}`
    );
  }
  if (filesText) parts.push(filesText);
  return parts.join("\n\n");
}

/** Same eligibility gating as codegen.ts/toolBridge: opt-in, routing
 * preference, hosted override. Never rejects. Returns the tier AND the
 * resolved model's display name when it's tier 1 (the model picker has
 * already run by this point - same ManifestModel getTier1RunModel chose),
 * so the caller can say exactly which on-device engine generated. */
async function eligibleTier(): Promise<{ tier: 0 | 1; modelName: string | null } | null> {
  try {
    if (!edgeEnabled() || typeof window === "undefined") return null;
    if (getStoredModelOverride() === "hosted") return null;
    if (getEdgeRoutingPreference() === "hosted-only") return null;
    if (!getEdgeOptIn()) return null;
    const caps = await detectCapabilities();
    const t1Model = await getTier1RunModel(caps);
    if (t1Model) {
      const model = t1Model as { displayName?: string; name?: string; id: string };
      return { tier: 1, modelName: model.displayName ?? model.name ?? model.id };
    }
    if (await tier0Available()) return { tier: 0, modelName: null };
    return null;
  } catch {
    return null;
  }
}

async function runOneShot(
  tier: 0 | 1,
  userPrompt: string,
  signal?: AbortSignal
): Promise<string> {
  if (tier === 1) {
    const caps = await detectCapabilities();
    // disableTools: file generation needs the RAW model text — the tool
    // protocol block and response_format would only eat context here.
    const session = await createTier1Session(caps, {
      systemPrompt: SYSTEM_PROMPT,
      disableTools: true,
    });
    try {
      return await session.prompt(userPrompt, undefined, signal);
    } finally {
      session.destroy();
    }
  }
  const session = await createTier0Session(SYSTEM_PROMPT);
  try {
    return await session.prompt(userPrompt, undefined, signal);
  } finally {
    session.destroy();
  }
}

/** Parse the model's reply into a file set — tolerant of a ```json fence
 * and of leading/trailing prose around the JSON object (small models add
 * both despite being told not to). */
function parseReply(raw: string): { files: AppBuilderFileWrite[]; summary: string | null } {
  let text = raw.trim();
  if (text.startsWith("```")) {
    text = text
      .replace(/^```[a-zA-Z0-9]*\s*/, "")
      .replace(/```\s*$/, "")
      .trim();
  }
  // If prose surrounds the JSON, take the outermost { ... }.
  const start = text.indexOf("{");
  const end = text.lastIndexOf("}");
  if (start >= 0 && end > start) text = text.slice(start, end + 1);
  const parsed = JSON.parse(text);
  const files = Array.isArray(parsed?.files) ? parsed.files : [];
  const summary =
    typeof parsed?.summary === "string" && parsed.summary.trim()
      ? parsed.summary.trim()
      : null;
  return {
    files: files
      .filter(
        (f: unknown): f is AppBuilderFileWrite =>
          !!f &&
          typeof (f as AppBuilderFileWrite).path === "string" &&
          typeof (f as AppBuilderFileWrite).content === "string"
      )
      .filter((f: AppBuilderFileWrite) => !isForbiddenWritePath(f.path))
      .map((f: AppBuilderFileWrite) => ({ ...f, path: normalizeWritePath(f.path) })),
    summary,
  };
}

/**
 * Generate a file set on-device. Resolves {ok:false} immediately when no
 * local tier is eligible so the caller can fall through to the hosted
 * /generate route — a local model is never loaded just to discover it
 * shouldn't run.
 */
export async function generateFilesOnDevice(
  options: GenerateFilesOptions
): Promise<EdgeFilesResult> {
  try {
    if (!options.instruction?.trim()) {
      return { ok: false, reason: "generation-failed", detail: "empty instruction" };
    }
    const eligible = await eligibleTier();
    if (eligible === null) return { ok: false, reason: "no-local-tier" };

    const shown = filesForPrompt(options.existing, FILES_BUDGET[eligible.tier]);
    let raw: string;
    try {
      if (options.signal?.aborted) return { ok: false, reason: "aborted" };
      raw = await runOneShot(eligible.tier, userPromptFor(options, shown.text), options.signal);
    } catch (err) {
      const aborted =
        (err instanceof Tier0Error || err instanceof Tier1Error) &&
        err.code === "aborted";
      if (aborted) return { ok: false, reason: "aborted" };
      return {
        ok: false,
        reason: "generation-failed",
        detail: err instanceof Error ? err.message : String(err),
      };
    }

    if (!raw.trim()) return { ok: false, reason: "empty-reply" };
    try {
      const parsed = parseReply(raw);
      if (parsed.files.length === 0) return { ok: false, reason: "bad-shape" };
      const files = safeWrites(parsed.files, options.existing, shown.writable);
      if (files.length === 0) return { ok: false, reason: "unsafe-writes" };
      return { ok: true, tier: eligible.tier, files, modelName: eligible.modelName, summary: parsed.summary };
    } catch {
      return { ok: false, reason: "bad-shape" };
    }
  } catch (err) {
    return {
      ok: false,
      reason: "generation-failed",
      detail: err instanceof Error ? err.message : String(err),
    };
  }
}

/** Cheap UI hook: is on-device file generation eligible right now? */
export async function edgeFilesReady(): Promise<boolean> {
  return (await eligibleTier()) !== null;
}
