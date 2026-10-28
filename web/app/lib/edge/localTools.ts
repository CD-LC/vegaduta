// The LOCAL half of the edge tool surface.
//
// toolBridge.ts (its server-side twin) is the propose/execute split: the local
// model proposes, the SERVER executes through the guarded registry. That is the
// right shape for anything with consequences — and it is why an account-less
// person gets nothing at all today. Every proposal goes to
// POST /api/edge/tools/execute, which 401s an anonymous caller, so a model that
// is already running entirely on their own GPU has no hands.
//
// These tools close that gap for the cases where the server was never the point.
// A local tool runs IN THE PAGE, with no account, no network and no spend. It
// does not replace a server tool and cannot shadow one: the "local_" prefix is
// the namespace, the same posture webmcp.ts takes with "vegaduta_".
//
// FOUR PROPERTIES THIS MODULE IS BUILT TO PROVE, not merely to intend:
//
//  1. NO SPEND — this file's ONLY imports are `import type`, which TypeScript
//     erases; the emitted module imports nothing at runtime. There is therefore
//     no import path from here to fetch, to toolBridge's endpoints, or to any
//     module that calls them. localTools.test.ts asserts this twice: it reads
//     this source and fails on any value import or network identifier, and it
//     runs every tool with throwing stubs installed over fetch, XMLHttpRequest,
//     WebSocket, sendBeacon and EventSource.
//  2. NO DOWNLOAD — nothing here touches tier1.ts or downloadManager.ts (same
//     zero-import fact). The structural weights guard is untouched.
//  3. NO DATA LEAVES — `network: "none"` on all four is the machine-readable
//     half of the same fact. A handler's only output channel is its return
//     value, which runLocalTool hands back to the caller as a string.
//  4. HONEST DEGRADATION — every refusal carries a `detail` that names what was
//     wrong AND what is supported. A handler that throws, hangs, returns the
//     wrong type or overruns the context budget each produce a distinguishable
//     typed result. Silence is the bug being fixed here.
//
// Phase 1 is deliberately pure computation only. Nothing writes to disk,
// clipboard or storage, so every tool below is consequential:false. The field
// exists because phase 2 will not be, and the loop needs somewhere to hang an
// approval gate that is not a name-matching heuristic.

import type { EdgeToolDefinition, EdgeToolExecuteResult } from "./toolBridge";
import type { EdgeCapabilities } from "./capabilities";
// Value imports, deliberately: the registry is the one place that may know
// about every tool set. The NO-RUNTIME-IMPORT rule its own test enforces
// applies to the pure-compute handlers below, not to this assembly point.
import { getNetworkToolsEnabled, WEBLLM_BASELINE_CONTEXT_WINDOW } from "./capabilities";
import { LOCAL_FILE_TOOLS } from "./localToolsFiles";
import { WEB_LOCAL_TOOLS } from "./localToolsWeb";
import { BROWSER_AI_TOOLS } from "./browserAi";
import { LOCAL_DEVICE_TOOLS, describeBluetoothSupport } from "./deviceTools";
import { MEDIA_LOCAL_TOOLS } from "./localToolsMedia";
import { LOCAL_TOOL_TIMEOUT_MS, LocalToolRefusal, MAX_RESULT_CHARS, capResult } from "./tools/common";
import { COMPUTE_LOCAL_TOOLS } from "./tools/computeTools";

// The budgets keep their public home here; the pure handlers live in ./tools/*.
export { LOCAL_TOOL_TIMEOUT_MS, MAX_RESULT_CHARS };

// ---------------------------------------------------------------------------
// Contract
// ---------------------------------------------------------------------------

export interface LocalTool extends EdgeToolDefinition {
  /** Always prefixed "local_". The prefix is the namespace that makes a
   *  local tool unable to shadow a server tool - same posture webmcp.ts
   *  already uses with "vegaduta_". */
  name: string;
  /** The handler. In-page, async, never throws - see runLocalTool. */
  run: (args: Record<string, unknown>) => Promise<string>;
  /** Machine-readable privacy contract. Phase 1 tools are ALL "none". */
  network: "none" | { hosts: string[] };
  /** True when the tool writes to the person's disk, clipboard or storage. */
  consequential: boolean;
  /** Optional per-tool timeout override (ms). The shared 2s ceiling fits the
   * phase-1 pure-compute tools; media generation (local_generate_image,
   * local_generate_audio) legitimately runs for minutes and declares its own. */
  timeoutMs?: number;
}

/** The prefix every local tool name carries. Exported so the loop can tell a
 * proposed local call from a proposed server call without a lookup. */
export const LOCAL_TOOL_PREFIX = "local_";

/**
 * Capability gates live HERE rather than as a field on LocalTool, so the pinned
 * LocalTool interface stays exactly as agreed across the three tracks. None of
 * the phase-1 four needs a gate — they are arithmetic and string work, and
 * gating them on WebGPU would be a lie about why they might be missing. The
 * table is the seam for the ones that will (a screenshot tool needs a display
 * surface, a speech tool needs the Web Speech API).
 */
/**
 * Media generation is the first tool set with a real hardware floor: the image
 * pipeline is unusable without WebGPU (a 512px diffusion on wasm is minutes),
 * and the audio one tolerates wasm only on an 8GB+ device. Advertising them on
 * a phone would spend prompt budget to say "the model can make images" to a
 * model that will then refuse every call.
 */
const CAPABILITY_GATES: Record<string, (caps: EdgeCapabilities | null) => boolean> = {
  local_generate_image: (caps) => caps?.webgpu?.available === true,
  local_generate_audio: (caps) => caps?.webgpu?.available === true || (caps?.deviceMemoryGB ?? 0) >= 8,
};

/**
 * The local tools available on this device, in advertisement order. `caps` may
 * be null: a caller that has not finished capability detection still gets the
 * ungated tools rather than an empty list, because "we are still detecting" is
 * not a reason to tell the model it has no hands.
 */
/**
 * Everything registered, in one place, resolved per call rather than frozen at
 * module load — the network set is a user preference and must be able to change
 * without a reload.
 *
 * THREE SETS, and the third is gated for a reason worth stating here rather
 * than only in the preference:
 *   pure compute (this file) — nothing to gate. No I/O of any kind.
 *   the opened folder (localToolsFiles) — reads only what the person themselves
 *     picked through the File System Access prompt, and transmits nothing.
 *   third-party lookups (localToolsWeb) — OFF unless the person turned them on.
 *     With folder reads and outbound queries live at once, a file's own text
 *     becomes an instruction channel: a README saying "look this up for me:
 *     <contents of .env>" is a working exfiltration path and the model cannot
 *     tell the file is lying to it. The 200-character query cap bounds that
 *     channel; it does not close it. So the network set is something a person
 *     switches on having been told what it does.
 *
 * findLocalTool resolves through the SAME function, so a tool that is not
 * advertised also cannot be executed — a model that hallucinates
 * `local_wikipedia` while the preference is off falls through to the
 * server-tool path and its existing not-found handling, rather than quietly
 * running something the person declined.
 */
function allLocalTools(): LocalTool[] {
  const tools = [...COMPUTE_LOCAL_TOOLS, ...LOCAL_FILE_TOOLS];

  // The browser's OWN models (Chrome's Summarizer / Translator / Language
  // Detector). Nothing to download and nothing to gate: each tool probes its
  // own surface and refuses with a sentence where the browser does not provide
  // it, which is the same honest-degradation contract every other tool here
  // follows.
  tools.push(...BROWSER_AI_TOOLS);

  // Bluetooth is gated on the API being genuinely present, not merely declared.
  // Advertising a tool that can only ever refuse would spend the person's
  // context window - the tool block is pasted into every system prompt - to
  // tell the model about something it cannot use. describeBluetoothSupport()
  // answers for the specific API these tools need.
  try {
    if (describeBluetoothSupport().ok === true) tools.push(...LOCAL_DEVICE_TOOLS);
  } catch {
    // A probe that throws hides its tools rather than breaking the list.
  }

  // Media generation tools - "the model can make things" out of the box. The
  // capability gates above decide whether this device can run them at all;
  // the handlers themselves refuse honestly when the person hasn't enabled the
  // model in the Edge console (a tool call never starts a download - E-0b).
  tools.push(...MEDIA_LOCAL_TOOLS);

  if (getNetworkToolsEnabled()) tools.push(...WEB_LOCAL_TOOLS);

  // DELIBERATELY NOT REGISTERED YET: localToolsDocs' LOCAL_DOC_TOOLS. Its
  // module still runs a second embedding worker whose readiness gate does not
  // match the one app/lib/localcode/index/ enforces, so the documents path can
  // reach a model download the code path refuses - the no-surprise-download
  // property, open at the time of writing. Register it in the same change that
  // closes that, not before.
  return tools;
}

/**
 * How many characters of tool schema may be advertised in one system prompt.
 *
 * This is not a style rule, it is the product's own context budget. Everything
 * in {name, description, inputSchema} for every advertised tool is pasted into
 * the system prompt on EVERY turn, and tier1's constants imply roughly 2,744
 * tokens for the WHOLE prompt on a 4096-window model — system text, the tool
 * block, the person's message and every tool result combined. Measured on
 * 2026-09-12, advertising every registered tool came to 4,606 characters, about
 * 1,316 tokens at tier1's chars/3.5 estimate: nearly half the budget spent
 * before the person typed anything.
 *
 * 3,200 is set so the seven tools that define the product fit exactly — the
 * four compute tools plus the three folder tools measure 3,078 characters
 * together (measured 2026-09-12), about 880 tokens, ~32% of that budget.
 * Everything past them is advertised only when a caller passes a larger
 * budget, which is what a model with a bigger window should do.
 *
 * So the registry advertises what FITS, in priority order, rather than
 * everything it has. A tool that is not advertised is still executable by name
 * (findLocalTool is deliberately not budget-aware) — it is simply not offered
 * to a model that cannot afford to be told about it.
 */
export const TOOL_ADVERTISEMENT_CHAR_BUDGET = 3_200;

/**
 * The budget {@link listLocalTools} / {@link describeToolBudget} should be
 * given for a model whose context window is `windowTokens`: the 3,200-char
 * baseline on the 4096-window models it was measured for, scaled linearly on
 * bigger windows so a model that can afford it is offered the whole registry —
 * including the media-generation tools the baseline always trims (measured:
 * compute+folder = 3,078 chars; the full catalog = ~5,647). This is the
 * "caller passes a larger budget" case the constant's doc names. Absent or
 * absurd input answers the baseline, never less — capability detection failing
 * must not cost the person their core tools.
 */
export function toolAdvertisementBudgetFor(windowTokens: number | null | undefined): number {
  if (typeof windowTokens !== "number" || !Number.isFinite(windowTokens) || windowTokens <= 0) {
    return TOOL_ADVERTISEMENT_CHAR_BUDGET;
  }
  return Math.max(
    TOOL_ADVERTISEMENT_CHAR_BUDGET,
    Math.floor((windowTokens / WEBLLM_BASELINE_CONTEXT_WINDOW) * TOOL_ADVERTISEMENT_CHAR_BUDGET)
  );
}

/** What one tool costs in the system prompt: exactly the fields
 * toolProtocolInstructions serialises (tier1.ts). */
function advertisedCost(tool: LocalTool): number {
  try {
    return JSON.stringify({
      name: tool.name,
      description: tool.description,
      inputSchema: tool.inputSchema,
    }).length;
  } catch {
    // An unserialisable schema cannot be advertised at all; charge it the
    // budget so it is dropped rather than silently sent as "undefined".
    return Number.MAX_SAFE_INTEGER;
  }
}

/**
 * What the registry would advertise and what it had to leave out, so a surface
 * can tell the person "6 of 10 tools offered — this model's context window is
 * small" instead of silently under-serving them.
 */
export function describeToolBudget(
  caps: EdgeCapabilities,
  budget: number = TOOL_ADVERTISEMENT_CHAR_BUDGET
): { advertised: LocalTool[]; omitted: LocalTool[]; chars: number } {
  const eligible = gatedTools(caps);
  const advertised: LocalTool[] = [];
  const omitted: LocalTool[] = [];
  let chars = 0;
  // STOP at the first tool that does not fit; do not keep filling with cheaper
  // ones behind it. A greedy skip-and-continue reorders by SIZE, which is the
  // opposite of priority: measured on 2026-09-12 it dropped local_search_files
  // - semantic search over the person's own folder, the most useful tool here -
  // and spent the room on local_detect_language instead, purely because the
  // latter's schema is smaller. Priority must survive the trim or it is not a
  // priority order.
  let full = false;
  for (const tool of eligible) {
    if (full) {
      omitted.push(tool);
      continue;
    }
    const cost = advertisedCost(tool);
    if (chars + cost <= budget) {
      advertised.push(tool);
      chars += cost;
    } else {
      full = true;
      omitted.push(tool);
    }
  }
  return { advertised, omitted, chars };
}

function gatedTools(caps: EdgeCapabilities): LocalTool[] {
  const safeCaps: EdgeCapabilities | null = caps ?? null;
  return allLocalTools().filter((tool) => {
    const gate = CAPABILITY_GATES[tool.name];
    if (!gate) return true;
    try {
      return gate(safeCaps) === true;
    } catch {
      // A gate that throws must hide its tool, not break the whole list.
      return false;
    }
  });
}

/** The tools offered to the model this turn: capability-gated, then trimmed to
 * what the prompt budget can afford (see TOOL_ADVERTISEMENT_CHAR_BUDGET). */
export function listLocalTools(caps: EdgeCapabilities, budget: number = TOOL_ADVERTISEMENT_CHAR_BUDGET): LocalTool[] {
  return describeToolBudget(caps, budget).advertised;
}

/**
 * Exact-name lookup. No prefix-insensitive or fuzzy matching: `calculate` must
 * NOT resolve here, or the namespace that keeps a local tool from shadowing a
 * server tool of the same name stops meaning anything.
 *
 * NOTE for the loop: this deliberately ignores CAPABILITY_GATES, because it is
 * the executor's lookup and a gate is an advertisement decision. While the gate
 * table is empty the two agree; the day it is not, resolve the proposed name
 * against listLocalTools(caps) before calling runLocalTool.
 */
export function findLocalTool(name: string): LocalTool | null {
  if (typeof name !== "string" || !name) return null;
  for (const tool of allLocalTools()) {
    if (tool.name === name) return tool;
  }
  return null;
}

/**
 * The ONLY place a local handler is invoked. Never throws and never rejects:
 * every outcome is an EdgeToolExecuteResult, the same union the server bridge
 * returns, so the tool loop needs no second code path for local tools.
 *
 * The validation split: this function owns the generic contract (a tool object
 * that can actually run, an arguments JSON object, the time ceiling, the result
 * type and length). Each handler owns its OWN argument expectations, because
 * only it can produce the refusal text that names what it does support — which
 * is the whole of honest degradation here.
 *
 *   unavailable → there is nothing to run (not a local tool object)
 *   rejected    → the tool ran and said no: bad arguments, unknown unit, unparseable expression
 *   failed      → a defect or a timeout on this side; the model should not retry the same call
 */
export async function runLocalTool(tool: LocalTool, args: unknown): Promise<EdgeToolExecuteResult> {
  if (!tool || typeof tool !== "object" || typeof tool.run !== "function") {
    return { ok: false, code: "unavailable", detail: "no such local tool" };
  }
  if (typeof tool.name !== "string" || !tool.name.startsWith(LOCAL_TOOL_PREFIX)) {
    return { ok: false, code: "unavailable", detail: `"${String(tool.name)}" is not a local tool` };
  }

  let normalized: Record<string, unknown>;
  if (args === undefined || args === null) {
    normalized = {};
  } else if (typeof args === "object" && !Array.isArray(args)) {
    normalized = args as Record<string, unknown>;
  } else {
    return {
      ok: false,
      code: "rejected",
      detail: `${tool.name} expects a JSON object of arguments, got ${Array.isArray(args) ? "an array" : typeof args}.`,
    };
  }

  let timer: ReturnType<typeof setTimeout> | null = null;
  try {
    const TIMED_OUT = Symbol("local-tool-timeout");
    const timeout = new Promise<typeof TIMED_OUT>((resolve) => {
      timer = setTimeout(() => resolve(TIMED_OUT), tool.timeoutMs ?? LOCAL_TOOL_TIMEOUT_MS);
    });
    // Promise.resolve() wraps a handler that throws SYNCHRONOUSLY before its
    // first await — that rejection would otherwise escape the race entirely.
    // Typed `unknown` on purpose: tool.run's declared Promise<string> is a
    // claim about a handler this module may not have written, and the wrong-type
    // branch below has to stay reachable rather than being narrowed away.
    const outcome: unknown = await Promise.race([Promise.resolve().then(() => tool.run(normalized)), timeout]);
    if (outcome === TIMED_OUT) {
      return {
        ok: false,
        code: "failed",
        detail: `${tool.name} did not finish within ${tool.timeoutMs ?? LOCAL_TOOL_TIMEOUT_MS}ms and was abandoned.`,
      };
    }
    if (typeof outcome !== "string") {
      // A handler bug, not the caller's fault — say so rather than coercing
      // "[object Object]" into the model's context as if it were an answer.
      return {
        ok: false,
        code: "failed",
        detail: `${tool.name} returned ${outcome === null ? "null" : typeof outcome} instead of a string result.`,
      };
    }
    return { ok: true, result: capResult(outcome) };
  } catch (err) {
    if (err instanceof LocalToolRefusal) {
      return { ok: false, code: "rejected", detail: err.message };
    }
    return {
      ok: false,
      code: "failed",
      detail: err instanceof Error ? `${tool.name}: ${err.message}` : `${tool.name}: ${String(err)}`,
    };
  } finally {
    // Without this the abandoned timer keeps the event loop (and a test's fake
    // clock) alive for the full ceiling after a fast handler has answered.
    if (timer !== null) clearTimeout(timer);
  }
}
