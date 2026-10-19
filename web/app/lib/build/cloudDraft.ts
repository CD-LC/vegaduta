// Cloud-AI drafting for /build: goal -> Blueprint, written by a real model that
// UNDERSTANDS the request instead of keyword-matching it to a canned recipe.
//
// Why this exists: draftWithEdge() needs an on-device WebGPU model the person
// has set up on /edge, so for almost everyone "Draft my blueprint" fell through
// to blueprintFromGoal() - a keyword match plus the person's sentence pasted
// into a template. This is the middle tier: the workspace's own model (the
// hosted pool on Free, the active BYOK key otherwise) via the same one-shot
// endpoint the studio uses, POST /api/assist/code-edit (caller-owned system
// prompt, no form-field framing).
//
// Contract, like edgeDraft.ts: NEVER throws, never toasts. Any failure returns
// a reason the caller can show, and the deterministic recipes stay underneath.
// The model's reply is untrusted: every value is checked against closed lists
// and the person reviews the whole blueprint before anything is created.

import { API_BASE } from "../api";
import { auth } from "../auth";
import { RECIPES } from "../capabilities";
import {
  APP_TEMPLATES,
  CHANNEL_KINDS,
  blueprintFromRecipe,
  blueprintFromRecipeId,
  defaultAppSpec,
  pickTemplateId,
  setSource,
  withAgent,
  withApp,
  withWiring,
  withWorkflow,
  type AutonomyTier,
  type Blueprint,
  type FlowKind,
  type FlowStep,
  type OutputKind,
  type Tone,
  type WiringSpec,
} from "./blueprint";
import { pickRecipe } from "../capabilities";
import { applyViewAsHeader } from "../viewAs";

const RECIPE_IDS = [...RECIPES.map((r) => r.id), "general"];
const TONES: Tone[] = ["friendly", "professional", "concise"];
const AUTONOMY: AutonomyTier[] = ["SUPERVISED", "SEMI_AUTONOMOUS", "FULLY_AUTONOMOUS"];
const FLOW_KINDS: FlowKind[] = ["trigger", "agent", "branch", "approval", "action", "output"];
const TRIGGERS: WiringSpec["trigger"][] = ["MANUAL", "SCHEDULE", "WEBHOOK", "CHANNEL"];
const CREATIVITY = { steady: 0.2, balanced: 0.6, creative: 0.9 } as const;

const AGENT_SYSTEM = [
  "You are the design brain of an AI-agent builder. A business owner describes, in their own words, an AI agent they want. UNDERSTAND what they need and DESIGN the agent - do not copy their sentence back.",
  "Reply with ONE JSON object and nothing else (no markdown fences, no commentary). Keys:",
  `"recipe": the closest starting template, one of: ${RECIPES.map((r) => `${r.id} (${r.title})`).join("; ")}; general (anything else).`,
  '"summary": one sentence restating the job in your own words.',
  '"name": a short job title for the agent, max 40 chars (e.g. "Refund Desk Agent").',
  '"instructions": the agent\'s full system prompt, written to the agent in the second person ("You are..."). 4-10 sentences or short bullet lines covering: its role and audience, what it does step by step, what it must never do, how it handles anything it cannot answer or is outside its scope (hand over to the owner), and its output style. Use the owner\'s specifics (products, policies, languages, limits) but NEVER invent facts, prices, policies or contact details that were not given - tell the agent to ask or escalate instead.',
  '"tone": friendly | professional | concise.',
  '"creativity": steady (facts, support, data) | balanced | creative (writing, brainstorming).',
  '"autonomy": SUPERVISED if it sends anything to customers, moves money or changes records; SEMI_AUTONOMOUS otherwise. Never FULLY_AUTONOMOUS unless the owner explicitly asked for it.',
  '"usesDocuments": true only if answers must come from the owner\'s own files/FAQ/catalogue.',
  '"needsWorkflow": true only for a multi-step process with hand-offs, branches or approvals.',
  `"steps": when needsWorkflow is true, 3-7 objects {"kind","label","detail"}; kind is one of ${FLOW_KINDS.join(" | ")}. Start with a trigger, include at least one agent step, put an approval step before anything that reaches a customer, end with an output. label max 60 chars; detail one sentence of what really happens in THIS process.`,
  `"channels": where people reach it, from: ${CHANNEL_KINDS.join(", ")}. Only channels the request implies; default ["WEB"].`,
  `"trigger": ${TRIGGERS.join(" | ")} (MANUAL unless the request says on a schedule, from a webhook, or on incoming messages). "cron": five-field UTC cron, only when trigger is SCHEDULE.`,
  '"secrets": [{"name":"UPPER_SNAKE_CASE","why":"what it unlocks"}] for third-party credentials the request clearly needs (never values). Max 4, usually none.',
  '"tools": [{"name":"short name","does":"what it should do"}] only for an external capability the request clearly needs and a chat agent could not do alone. Max 3, usually none.',
].join("\n");

const APP_SYSTEM = [
  "You are the design brain of an app builder. A person describes, in their own words, an app or website they want. UNDERSTAND what they need and DESIGN the brief - do not copy their sentence back.",
  "Reply with ONE JSON object and nothing else (no markdown fences, no commentary). Keys:",
  '"summary": one sentence restating what the app is for, in your own words.',
  '"name": a short product name, max 40 chars.',
  `"templateId": one of: ${APP_TEMPLATES.map((t) => `${t.id} (${t.label}: ${t.hint})`).join("; ")}.`,
  '"tagline": one line under the name, max 100 chars, specific to this app.',
  '"accent": a hex colour like #2f6f4e that suits the subject.',
  '"features": 4-9 concrete must-have features as short phrases, each something a developer can build (screens, forms, data, flows). Infer the obvious ones the person did not say; never invent business facts.',
].join("\n");

export interface CloudDraftResult {
  ok: boolean;
  raw?: unknown;
  reason?: string;
}

const isRecord = (v: unknown): v is Record<string, unknown> => typeof v === "object" && v !== null && !Array.isArray(v);
const str = (v: unknown, max: number) => (typeof v === "string" ? v.trim().slice(0, max) : "");

/** Pull the JSON object out of a reply that may be fenced or wrapped in prose. */
export function extractJson(text: string): unknown {
  const t = text.replace(/```(?:json)?/gi, "").trim();
  const start = t.indexOf("{");
  const end = t.lastIndexOf("}");
  if (start < 0 || end <= start) return null;
  try {
    return JSON.parse(t.slice(start, end + 1));
  } catch {
    return null;
  }
}

function reasonFor(status: number): string {
  if (status === 401) return "Sign in again to use AI drafting.";
  if (status === 400) return "No AI model is connected yet - add a provider key or use the included model.";
  if (status === 429) return "Your AI usage allowance is used up for now.";
  if (status === 503) return "The AI model pool is busy right now.";
  return "The AI model could not be reached.";
}

/** One quiet, cancellable call. Resolves to the parsed JSON or a human reason. */
export async function requestCloudDraft(
  goal: string,
  output: OutputKind,
  opts: { timeoutMs?: number; signal?: AbortSignal } = {}
): Promise<CloudDraftResult> {
  const controller = new AbortController();
  const onOuterAbort = () => controller.abort();
  let timer: ReturnType<typeof setTimeout> | undefined;
  try {
    if (opts.signal) {
      if (opts.signal.aborted) return { ok: false, reason: "Cancelled." };
      opts.signal.addEventListener("abort", onOuterAbort);
    }
    timer = setTimeout(() => controller.abort(), opts.timeoutMs ?? 60_000);
    const headers: Record<string, string> = { "Content-Type": "application/json" };
    const token = await auth.getToken({ silent: true }).catch((): string | null => null);
    if (token) headers.Authorization = `Bearer ${token}`;
    applyViewAsHeader(headers);
    const res = await fetch(`${API_BASE}/api/assist/code-edit`, {
      method: "POST",
      headers,
      signal: controller.signal,
      body: JSON.stringify({
        system: output === "app" ? APP_SYSTEM : AGENT_SYSTEM,
        prompt: `The owner says:\n"""\n${goal.trim().slice(0, 4000)}\n"""`,
      }),
    });
    if (!res.ok) return { ok: false, reason: reasonFor(res.status) };
    const body = (await res.json().catch((): null => null)) as { suggestion?: unknown } | null;
    const raw = typeof body?.suggestion === "string" ? extractJson(body.suggestion) : null;
    return isRecord(raw) ? { ok: true, raw } : { ok: false, reason: "The AI model's reply could not be read." };
  } catch {
    return { ok: false, reason: controller.signal.aborted ? "The AI model took too long to answer." : "The AI model could not be reached." };
  } finally {
    if (timer) clearTimeout(timer);
    opts.signal?.removeEventListener("abort", onOuterAbort);
  }
}

const NODE_SECRET_RE = /^[A-Z][A-Z0-9_]{2,63}$/;

/** Turn a model reply into a Blueprint, trusting nothing: closed lists, length caps, fallbacks. */
export function blueprintFromAiDraft(goal: string, raw: unknown, output: OutputKind): Blueprint | null {
  if (!isRecord(raw)) return null;
  const recipeId = RECIPE_IDS.includes(String(raw.recipe)) ? String(raw.recipe) : "general";
  const base = blueprintFromRecipeId(recipeId, goal) ?? blueprintFromRecipe(pickRecipe(""), goal);
  let bp: Blueprint = { ...base, goal: goal.trim(), app: defaultAppSpec(goal) };
  const AI = "cloud-ai" as const;

  // ---- App brief
  const appName = str(raw.name, 40);
  bp = withApp(
    bp,
    {
      ...(appName ? { name: appName } : {}),
      templateId: APP_TEMPLATES.some((t) => t.id === raw.templateId) ? String(raw.templateId) : pickTemplateId(goal),
      tagline: str(raw.tagline, 120),
      accent: /^#[0-9a-fA-F]{6}$/.test(str(raw.accent, 9)) ? str(raw.accent, 9) : bp.app.accent,
      features: Array.isArray(raw.features)
        ? raw.features.map((f) => str(f, 120)).filter(Boolean).slice(0, 10).join("\n")
        : "",
    },
    "app.name",
    AI
  );
  for (const k of ["app.templateId", "app.tagline", "app.accent", "app.features"]) bp = setSource(bp, k, AI);
  if (output === "app") return bp;

  // ---- Agent
  if (appName) bp = withAgent(bp, { name: appName }, "agent.name", AI);
  const tone = raw.tone as Tone;
  if (TONES.includes(tone)) bp = withAgent(bp, { tone }, "agent.tone", AI);
  const creativity = raw.creativity;
  if (creativity === "steady" || creativity === "balanced" || creativity === "creative") {
    bp = withAgent(bp, { temperature: CREATIVITY[creativity] }, "agent.temperature", AI);
  }
  if (AUTONOMY.includes(raw.autonomy as AutonomyTier)) bp = withAgent(bp, { autonomyTier: raw.autonomy as AutonomyTier }, "agent.autonomyTier", AI);
  if (typeof raw.usesDocuments === "boolean") bp = withAgent(bp, { knowledgeBase: raw.usesDocuments }, "agent.knowledgeBase", AI);

  // The point of the exercise: the model WRITES the job description. Too-short
  // output means it did not really answer, so keep the recipe's own text.
  const instructions = str(raw.instructions, 6000);
  if (instructions.length >= 80) bp = withAgent(bp, { baseInstructions: instructions }, "agent.instructions", AI);

  if (raw.needsWorkflow === true) {
    bp = withWorkflow(bp, true);
    const steps: FlowStep[] = [];
    (Array.isArray(raw.steps) ? raw.steps : []).slice(0, 7).forEach((s, i) => {
      if (!isRecord(s) || !FLOW_KINDS.includes(s.kind as FlowKind)) return;
      const label = str(s.label, 60);
      if (!label) return;
      steps.push({ id: `c${i}`, kind: s.kind as FlowKind, label, detail: str(s.detail, 240) || "Drafted by AI from your description - edit freely." });
    });
    if (steps.length >= 3 && steps[0].kind === "trigger" && steps.some((s) => s.kind === "agent") && bp.workflow) {
      bp = setSource({ ...bp, workflow: { ...bp.workflow, steps } }, "workflow.steps", AI);
    }
  } else if (raw.needsWorkflow === false) {
    bp = withWorkflow(bp, false);
  }

  // ---- Wiring
  const channels = (Array.isArray(raw.channels) ? raw.channels : [])
    .map((c) => String(c).toUpperCase())
    .filter((c, i, a) => (CHANNEL_KINDS as readonly string[]).includes(c) && a.indexOf(c) === i);
  const trigger = TRIGGERS.includes(raw.trigger as WiringSpec["trigger"]) ? (raw.trigger as WiringSpec["trigger"]) : "MANUAL";
  const cron = str(raw.cron, 40);
  const wired: Partial<WiringSpec> = {
    channels: channels.length ? channels : bp.wiring.channels,
    // A schedule without a usable cron would block the build; fall back to manual.
    trigger: trigger === "SCHEDULE" && cron.split(/\s+/).length !== 5 ? "MANUAL" : trigger,
    ...(cron.split(/\s+/).length === 5 ? { cron } : {}),
  };
  if (wired.trigger === "CHANNEL" && !(wired.channels?.length)) wired.trigger = "MANUAL";
  const secrets = (Array.isArray(raw.secrets) ? raw.secrets : [])
    .filter(isRecord)
    .map((s) => ({ name: str(s.name, 64).toUpperCase().replace(/[^A-Z0-9]/g, "_"), why: str(s.why, 120) }))
    .filter((s) => NODE_SECRET_RE.test(s.name))
    .slice(0, 4);
  if (secrets.length) wired.secretsText = secrets.map((s) => `${s.name} - ${s.why || "Needed for an integration"}`).join("\n");
  const tools = (Array.isArray(raw.tools) ? raw.tools : [])
    .filter(isRecord)
    .map((t) => ({ name: str(t.name, 40).replace(/\s*-\s*/g, " "), does: str(t.does, 160) }))
    .filter((t) => t.name && t.does)
    .slice(0, 3);
  if (tools.length) wired.toolsText = tools.map((t) => `${t.name} - ${t.does}`).join("\n");
  bp = withWiring(bp, wired, "wiring.channels", AI);
  for (const k of ["wiring.trigger", ...(wired.secretsText ? ["wiring.secretsText"] : []), ...(wired.toolsText ? ["wiring.toolsText"] : [])]) {
    bp = setSource(bp, k, AI);
  }
  return bp;
}

export async function draftWithCloud(
  goal: string,
  output: OutputKind,
  signal?: AbortSignal
): Promise<{ bp: Blueprint | null; summary: string; reason: string }> {
  const res = await requestCloudDraft(goal, output, { signal });
  if (!res.ok) return { bp: null, summary: "", reason: res.reason ?? "The AI model could not be reached." };
  const bp = blueprintFromAiDraft(goal, res.raw, output);
  if (!bp) return { bp: null, summary: "", reason: "The AI model's reply could not be read." };
  return { bp, summary: isRecord(res.raw) ? str(res.raw.summary, 300) : "", reason: "" };
}
