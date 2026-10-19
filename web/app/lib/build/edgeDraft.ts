// On-device (WebLLM, free, open-source weights) helpers for /build.
//
// Three jobs, all running in the person's own browser - the goal text never
// leaves the device:
//   1. draft   goal -> Blueprint (schema-constrained JSON, closed lists only)
//   2. refine  "make it friendlier and ask before sending" -> a patched Blueprint
//   3. dryRun  chat with the DRAFT agent (its real instructions) before it exists
//
// Rules this file keeps (from lib/edge/tier1.ts's E-0b contract):
//   - It NEVER downloads a model. status() only reports; setup lives on /edge,
//     behind that page's explicit size-and-source consent.
//   - Every function degrades to null/"unavailable" rather than throwing, so the
//     builder always has the deterministic recipe path underneath.
//   - Schemas are small and enum-heavy: constrained decoding guarantees valid
//     JSON, not good judgement, and a 1-4B model chooses well only from a short
//     closed list. The recipe catalogue supplies the rest.

import { detectCapabilities, edgeEnabled } from "../edge/capabilities";
import {
  createTier1Session,
  getTier1RunModel,
  prewarmTier1Engine,
  tier1StructuredAvailable,
  tier1StructuredJson,
} from "../edge/tier1";
import { RECIPES, pickRecipe } from "../capabilities";
import {
  blueprintFromRecipe,
  blueprintFromRecipeId,
  setSource,
  withAgent,
  withWorkflow,
  type AutonomyTier,
  type Blueprint,
  type FlowKind,
  type FlowStep,
  type Tone,
} from "./blueprint";

export type EdgeStatus =
  | { state: "disabled"; detail: string }
  | { state: "unsupported"; detail: string }
  | { state: "needs-setup"; detail: string }
  | { state: "warming"; detail: string; modelName: string }
  | { state: "ready"; detail: string; modelName: string };

/** Read-only probe. Starts a warm-up of an ALREADY-downloaded model, never a download. */
export async function edgeStatus(kickWarmup: boolean): Promise<EdgeStatus> {
  if (typeof window === "undefined" || !edgeEnabled()) {
    return { state: "disabled", detail: "On-device AI is not switched on for this deployment. Drafting uses the built-in recipes instead." };
  }
  try {
    const caps = await detectCapabilities();
    if (!caps.webgpu.available) {
      return { state: "unsupported", detail: "This browser has no WebGPU, which on-device AI needs. Try a recent Chrome or Edge on a laptop. Drafting uses the built-in recipes instead." };
    }
    const model = await getTier1RunModel(caps);
    if (!model) {
      return { state: "needs-setup", detail: "Not set up on this device yet. Set it up once (free, runs in your browser) and drafts, edits and test chats become private and instant." };
    }
    const name = (model as { displayName?: string; name?: string; id: string }).displayName ?? (model as { name?: string }).name ?? model.id;
    if (tier1StructuredAvailable()) return { state: "ready", detail: "Ready. Everything below runs on this device.", modelName: name };
    if (kickWarmup) prewarmTier1Engine(caps, "build-studio");
    return { state: "warming", detail: "Loading the on-device model into memory. This takes a few seconds.", modelName: name };
  } catch {
    return { state: "unsupported", detail: "On-device AI could not be checked. Drafting uses the built-in recipes instead." };
  }
}

// ---------------------------------------------------------------------------
// 1. Draft
// ---------------------------------------------------------------------------

const RECIPE_IDS = [...RECIPES.map((r) => r.id), "general"];
const TONE_VALUES: Tone[] = ["friendly", "professional", "concise"];
const AUTONOMY_VALUES: AutonomyTier[] = ["SUPERVISED", "SEMI_AUTONOMOUS", "FULLY_AUTONOMOUS"];
const FLOW_KINDS: FlowKind[] = ["trigger", "agent", "branch", "approval", "action", "output"];
const CREATIVITY = { steady: 0.2, balanced: 0.6, creative: 0.9 } as const;

const DRAFT_SCHEMA = {
  type: "object",
  properties: {
    recipe: { type: "string", enum: RECIPE_IDS },
    name: { type: "string", maxLength: 40 },
    tone: { type: "string", enum: TONE_VALUES },
    creativity: { type: "string", enum: ["steady", "balanced", "creative"] },
    autonomy: { type: "string", enum: AUTONOMY_VALUES },
    usesDocuments: { type: "boolean" },
    needsWorkflow: { type: "boolean" },
    extraRules: { type: "string", maxLength: 300 },
    steps: {
      type: "array",
      maxItems: 7,
      items: {
        type: "object",
        properties: {
          kind: { type: "string", enum: FLOW_KINDS },
          label: { type: "string", maxLength: 60 },
        },
        required: ["kind", "label"],
      },
    },
  },
  required: ["recipe", "name", "tone", "creativity", "autonomy", "usesDocuments", "needsWorkflow"],
};

const DRAFT_SYSTEM = [
  "You design AI agents for small businesses. Given what the owner wants, fill in the JSON.",
  `recipe: ${RECIPES.map((r) => `${r.id} (${r.title})`).join("; ")}; general (anything else).`,
  "name: a short job title such as 'Support Agent'. autonomy: use SUPERVISED if the agent sends anything to customers or moves money, else SEMI_AUTONOMOUS.",
  "usesDocuments: true if answers must come from the owner's own files. needsWorkflow: true only for multi-step processes with approvals or branches.",
  "extraRules: one or two sentences of specific rules from the request (never invent facts). steps: only when needsWorkflow is true; start with a trigger, end with a result.",
].join("\n");

const isRecord = (v: unknown): v is Record<string, unknown> => typeof v === "object" && v !== null && !Array.isArray(v);
const str = (v: unknown, max: number) => (typeof v === "string" ? v.trim().slice(0, max) : "");

/** Turn a model reply into a Blueprint, trusting nothing: every value is checked against the closed lists. */
export function blueprintFromDraft(goal: string, raw: unknown): Blueprint | null {
  if (!isRecord(raw)) return null;
  const recipeId = RECIPE_IDS.includes(String(raw.recipe)) ? String(raw.recipe) : "general";
  const base = blueprintFromRecipeId(recipeId, goal) ?? blueprintFromRecipe(pickRecipe(""), goal);
  let bp: Blueprint = { ...base, goal: goal.trim() };
  bp = setSource({ ...bp, agent: { ...bp.agent, baseInstructions: bp.agent.baseInstructions } }, "agent.instructions", "goal");

  const name = str(raw.name, 40);
  if (name) bp = withAgent(bp, { name }, "agent.name", "device-ai");
  if (TONE_VALUES.includes(raw.tone as Tone)) bp = withAgent(bp, { tone: raw.tone as Tone }, "agent.tone", "device-ai");
  if (raw.creativity === "steady" || raw.creativity === "balanced" || raw.creativity === "creative") {
    bp = withAgent(bp, { temperature: CREATIVITY[raw.creativity] }, "agent.temperature", "device-ai");
  }
  if (AUTONOMY_VALUES.includes(raw.autonomy as AutonomyTier)) bp = withAgent(bp, { autonomyTier: raw.autonomy as AutonomyTier }, "agent.autonomyTier", "device-ai");
  if (typeof raw.usesDocuments === "boolean") bp = withAgent(bp, { knowledgeBase: raw.usesDocuments }, "agent.knowledgeBase", "device-ai");

  const extra = str(raw.extraRules, 300);
  if (extra) {
    bp = withAgent(bp, { baseInstructions: `${bp.agent.baseInstructions}\n\nRules from the owner: ${extra}` }, "agent.instructions", "device-ai");
  }

  if (raw.needsWorkflow === true) {
    bp = withWorkflow(bp, true);
    const steps = Array.isArray(raw.steps) ? raw.steps : [];
    const clean: FlowStep[] = [];
    steps.slice(0, 7).forEach((s, i) => {
      if (!isRecord(s) || !FLOW_KINDS.includes(s.kind as FlowKind)) return;
      const label = str(s.label, 60);
      if (label) clean.push({ id: `d${i}`, kind: s.kind as FlowKind, label, detail: "Drafted on your device from your goal - edit freely." });
    });
    // A usable flow starts with a trigger and holds at least one agent step; otherwise keep the recipe's own.
    if (clean.length >= 3 && clean[0].kind === "trigger" && clean.some((s) => s.kind === "agent") && bp.workflow) {
      bp = setSource({ ...bp, workflow: { ...bp.workflow, steps: clean } }, "workflow.steps", "device-ai");
    }
  } else if (raw.needsWorkflow === false) {
    bp = withWorkflow(bp, false);
  }
  return bp;
}

export async function draftWithEdge(goal: string, signal?: AbortSignal): Promise<Blueprint | null> {
  if (!tier1StructuredAvailable()) return null;
  const raw = await tier1StructuredJson(`The owner wants: "${goal.trim()}"`, DRAFT_SCHEMA, {
    systemPrompt: DRAFT_SYSTEM,
    signal,
    timeoutMs: 45_000,
  });
  return raw ? blueprintFromDraft(goal, raw) : null;
}

// ---------------------------------------------------------------------------
// 2. Refine ("make it friendlier and ask me before sending anything")
// ---------------------------------------------------------------------------

const REFINE_SCHEMA = {
  type: "object",
  properties: {
    tone: { type: "string", enum: [...TONE_VALUES, "keep"] },
    creativity: { type: "string", enum: ["steady", "balanced", "creative", "keep"] },
    autonomy: { type: "string", enum: [...AUTONOMY_VALUES, "keep"] },
    usesDocuments: { type: "string", enum: ["yes", "no", "keep"] },
    addApprovalStep: { type: "boolean" },
    renameTo: { type: "string", maxLength: 40 },
    addRule: { type: "string", maxLength: 240 },
  },
  required: ["tone", "creativity", "autonomy", "usesDocuments", "addApprovalStep"],
};

export function applyRefinement(bp: Blueprint, raw: unknown): Blueprint {
  if (!isRecord(raw)) return bp;
  let next = bp;
  if (TONE_VALUES.includes(raw.tone as Tone)) next = withAgent(next, { tone: raw.tone as Tone }, "agent.tone", "device-ai");
  if (raw.creativity === "steady" || raw.creativity === "balanced" || raw.creativity === "creative") {
    next = withAgent(next, { temperature: CREATIVITY[raw.creativity] }, "agent.temperature", "device-ai");
  }
  if (AUTONOMY_VALUES.includes(raw.autonomy as AutonomyTier)) next = withAgent(next, { autonomyTier: raw.autonomy as AutonomyTier }, "agent.autonomyTier", "device-ai");
  if (raw.usesDocuments === "yes" || raw.usesDocuments === "no") {
    next = withAgent(next, { knowledgeBase: raw.usesDocuments === "yes" }, "agent.knowledgeBase", "device-ai");
  }
  const rename = str(raw.renameTo, 40);
  if (rename) next = withAgent(next, { name: rename }, "agent.name", "device-ai");
  const rule = str(raw.addRule, 240);
  if (rule && next.agent.instructionsOverride === null) {
    next = withAgent(next, { baseInstructions: `${next.agent.baseInstructions}\n\nAlso: ${rule}` }, "agent.instructions", "device-ai");
  }
  if (raw.addApprovalStep === true) {
    next = withWorkflow(next, true);
    if (next.workflow && !next.workflow.steps.some((s) => s.kind === "approval")) {
      const steps = [...next.workflow.steps];
      const at = steps[steps.length - 1]?.kind === "output" ? steps.length - 1 : steps.length;
      steps.splice(at, 0, { id: `r${Date.now().toString(36)}`, kind: "approval", label: "A person approves", detail: "Added on request - nothing goes out until someone says yes." });
      next = setSource({ ...next, workflow: { ...next.workflow, steps } }, "workflow.steps", "device-ai");
    }
  }
  return next;
}

export async function refineWithEdge(bp: Blueprint, instruction: string, signal?: AbortSignal): Promise<Blueprint | null> {
  if (!tier1StructuredAvailable()) return null;
  const raw = await tier1StructuredJson(
    `Current agent: name "${bp.agent.name}", tone ${bp.agent.tone}, autonomy ${bp.agent.autonomyTier}, documents ${bp.agent.knowledgeBase ? "on" : "off"}.\nThe owner now says: "${instruction.trim()}"\nUse "keep" for anything they did not ask to change. addApprovalStep is true only if they want a person to approve before something is sent.`,
    REFINE_SCHEMA,
    { systemPrompt: "You adjust an AI agent's settings from a plain-language request. Change only what was asked.", signal, timeoutMs: 30_000 }
  );
  return raw ? applyRefinement(bp, raw) : null;
}

// ---------------------------------------------------------------------------
// 3. Dry run - talk to the draft agent, on-device, before it is created
// ---------------------------------------------------------------------------

export interface DryRun {
  send(text: string, onToken: (delta: string) => void, signal?: AbortSignal): Promise<string>;
  destroy(): void;
}

/** Null when no on-device model is ready. Tools are off: this tests the instructions and tone, not integrations. */
export async function openDryRun(systemPrompt: string): Promise<DryRun | null> {
  try {
    if (typeof window === "undefined" || !edgeEnabled()) return null;
    const caps = await detectCapabilities();
    if (!(await getTier1RunModel(caps))) return null;
    const session = await createTier1Session(caps, { systemPrompt, disableTools: true, sessionKey: "build-dry-run" });
    return { send: (text, onToken, signal) => session.prompt(text, onToken, signal), destroy: () => session.destroy() };
  } catch {
    return null;
  }
}
