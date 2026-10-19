// The Blueprint: ONE object that holds every setting of what /build is about to
// create, so the person sees (and can change) every value BEFORE anything exists.
//
// Design rules (from the competitor review + the platform's honesty rules):
//   - Every row states its value, WHERE that value came from (a safe default, the
//     person's own words, the on-device AI, or the person), and WHY it matters.
//   - Simple / Standard / Advanced are views over the SAME blueprint, never
//     different builders (Flowise splits modes; we do not).
//   - A row is "applied" only when a real API accepts it at create time (or a
//     follow-up PATCH). Settings the platform sets by default but this screen does
//     not change are shown read-only, labelled "Platform default", so "everything"
//     is visible without pretending it is editable here.
//   - Pure module: no network, no React. The on-device drafting call lives in
//     edgeDraft.ts so this file stays unit-testable.

import { pickRecipe, recipeById, type Recipe, type RecipeConnectStep } from "../capabilities";
import { SECRET_NAME_RE, parseSecrets } from "./blueprintWiring";

export type Level = "simple" | "standard" | "advanced";
export type FieldSource = "default" | "goal" | "device-ai" | "cloud-ai" | "you";
export type Tone = "friendly" | "professional" | "concise";
export type ReasoningDepth = "FAST" | "CAREFUL" | "RIGOROUS" | "PLANNED";
export type AutonomyTier = "SUPERVISED" | "SEMI_AUTONOMOUS" | "FULLY_AUTONOMOUS";
export type FlowKind = "trigger" | "agent" | "branch" | "approval" | "action" | "output";
/** What the Build button produces - an envoy (managed, trust ladder) or a
 * Git-backed app via the app-builder service. */
export type OutputKind = "agent" | "app";

export interface FlowStep {
  id: string;
  kind: FlowKind;
  label: string;
  detail: string;
}

export interface AppSpec {
  name: string;
  /** An app-builder template id (templates/ in the app-builder service). */
  templateId: string;
  accent: string;
  tagline: string;
  /** Freeform, one feature per line - folded into the scaffold prompt. */
  features: string;
}

/** Every connection around the envoy that the blueprint declares in plain
 *  words. Freeform lists keep a text shape here ("NAME - why", one per line)
 *  so a paste-friendly textarea is all the UI they need; toBrief() parses
 *  them into schema objects. */
export interface WiringSpec {
  /** Channel kinds the envoy listens on (brief-schema enum values). */
  channels: string[];
  /** "MY_API_KEY - what it unlocks" per line. Names are normalised to the
   *  schema pattern ^[A-Z][A-Z0-9_]{2,63}$ at compile time. */
  secretsText: string;
  /** "registry id or server name" per line. */
  mcpsText: string;
  /** "name - what it should do" or "name https://spec-url" per line.
   *  A trailing URL becomes an OPENAPI tool; anything else a CODE tool the
   *  platform drafts. "key: SECRET_NAME" links a declared secret. */
  toolsText: string;
  trigger: "MANUAL" | "SCHEDULE" | "WEBHOOK" | "CHANNEL";
  /** Only read when trigger === "SCHEDULE". */
  cron: string;
}

export interface Blueprint {
  goal: string;
  recipeId: string;
  output: OutputKind;
  /** Used when output === "app"; the agent section above stays filled either
   *  way so flipping the toggle never destroys work. */
  app: AppSpec;
  /** Channels / secrets / MCP servers / tools / trigger - the wiring around
   *  the agent. Agent-output only; the app path ignores it. */
  wiring: WiringSpec;
  agent: {
    name: string;
    /** The recipe's base instructions with the goal folded in. */
    baseInstructions: string;
    /** Set when the person edits the instructions directly; wins over base + tone. */
    instructionsOverride: string | null;
    tone: Tone;
    knowledgeBase: boolean;
    reasoningDepth: ReasoningDepth;
    autonomyTier: AutonomyTier;
    /** "" = let the platform pick the model for the connected provider. */
    model: string;
    temperature: number;
    topP: number;
    maxTokens: number;
    topK: number | null;
    stopSequences: string[];
    frequencyPenalty: number | null;
    presencePenalty: number | null;
    seed: number | null;
  };
  /** null = agent only. */
  workflow: { name: string; description: string; steps: FlowStep[] } | null;
  connect: RecipeConnectStep[];
  skills: string[];
  /** field key -> where its value came from. Missing key = "default". */
  sources: Record<string, FieldSource>;
}

export const TONES: { value: Tone; label: string; line: string }[] = [
  { value: "friendly", label: "Friendly", line: "Write in a warm, friendly, plain-spoken voice." },
  { value: "professional", label: "Professional", line: "Write in a polished, professional, courteous voice." },
  { value: "concise", label: "Concise", line: "Be brief and direct: short sentences, no filler." },
];

export const DEFAULTS = {
  tone: "friendly" as Tone,
  reasoningDepth: "FAST" as ReasoningDepth,
  autonomyTier: "SEMI_AUTONOMOUS" as AutonomyTier,
  temperature: 0.7,
  topP: 1.0,
  maxTokens: 1024,
};

// ---------------------------------------------------------------------------
// Workflow shapes. A recipe with its own workflow uses its shape; any other
// recipe can opt in to the generic one.
// ---------------------------------------------------------------------------

export const step = (id: string, kind: FlowKind, label: string, detail: string): FlowStep => ({ id, kind, label, detail });

const FLOWS: Record<string, FlowStep[]> = {
  leadflow: [
    step("t", "trigger", "A lead enquiry arrives", "Starts the workflow with the enquiry text."),
    step("a", "agent", "Qualify and score the lead", "The agent extracts who, what, budget and urgency, then scores hot / warm / cold."),
    step("b", "branch", "Hot lead?", "Hot leads take the fast path; the rest are nurtured."),
    step("d", "agent", "Draft the follow-up message", "A short, specific reply that fits this enquiry."),
    step("p", "approval", "A person approves the message", "Nothing is sent until someone says yes."),
    step("o", "output", "Message ready to send", "The approved message and the lead record are the result."),
  ],
};

export function genericFlow(recipe: Recipe): FlowStep[] {
  return [
    step("t", "trigger", "A request comes in", "Starts the workflow with what the person asked for."),
    step("a", "agent", `${recipe.agent.name} does the work`, "The agent uses its tools and knowledge to handle the request."),
    step("p", "approval", "A person reviews the result", "The result is held until someone approves it."),
    step("o", "output", "Result delivered", "The approved result is the output of the run."),
  ];
}

export const FLOW_KIND_LABEL: Record<FlowKind, string> = {
  trigger: "Starts when",
  agent: "Agent step",
  branch: "Decision",
  approval: "Human approval",
  action: "Action",
  output: "Result",
};

// ---------------------------------------------------------------------------
// Construction
// ---------------------------------------------------------------------------

// ---------------------------------------------------------------------------
// App output: the app-builder templates this surface can scaffold.
// ---------------------------------------------------------------------------

export interface AppTemplateChoice {
  id: string;
  label: string;
  hint: string;
}

export const APP_TEMPLATES: AppTemplateChoice[] = [
  { id: "node-react", label: "Web app", hint: "Vite + React - dashboards, tools, portals" },
  { id: "astro-site", label: "Website", hint: "Astro - marketing, blogs, docs" },
  { id: "capacitor-app", label: "Mobile app", hint: "Capacitor - one codebase, Android + iOS + web" },
  { id: "python-fastapi", label: "API / backend", hint: "FastAPI - a service with endpoints" },
  { id: "static-html", label: "Static page", hint: "Plain HTML/CSS - simplest possible start" },
];

/** Keyword guess from the person's own words; editable on the blueprint. */
export function pickTemplateId(goal: string): string {
  const g = goal.toLowerCase();
  if (/\b(android|ios|iphone|apk|mobile app|phone)\b/.test(g)) return "capacitor-app";
  if (/\b(api|backend|endpoint|webhook|service|microservice)\b/.test(g)) return "python-fastapi";
  if (/\b(blog|docs|documentation|marketing|landing|website|site|page)\b/.test(g)) return "astro-site";
  return "node-react";
}

export const CHANNEL_KINDS = ["WEB", "WHATSAPP", "EMAIL", "TELEGRAM", "SLACK", "DISCORD", "TEAMS", "SMS", "VOICE"] as const;

export function defaultWiring(recipe: Recipe): WiringSpec {
  const onChannel = recipe.connect.some((c) => c.kind === "channel");
  return {
    channels: onChannel ? ["WEB", "WHATSAPP"] : ["WEB"],
    secretsText: "",
    mcpsText: "",
    toolsText: "",
    trigger: "MANUAL",
    cron: "0 9 * * *",
  };
}

export function defaultAppSpec(goal: string): AppSpec {
  const words = goal.trim().split(/\s+/).filter(Boolean);
  return {
    name: words.slice(0, 3).join(" ").replace(/[^\w -]/g, "") || "My app",
    templateId: pickTemplateId(goal),
    accent: "#6d5df2",
    tagline: "",
    features: "",
  };
}

export function blueprintFromRecipe(recipe: Recipe, goal: string, fromGoal = true): Blueprint {
  const flow = recipe.workflow ? (FLOWS[recipe.id] ?? genericFlow(recipe)) : null;
  return {
    goal: goal.trim(),
    recipeId: recipe.id,
    output: "agent",
    app: defaultAppSpec(goal),
    wiring: defaultWiring(recipe),
    agent: {
      name: recipe.agent.name,
      baseInstructions: recipe.agent.systemPrompt(goal),
      instructionsOverride: null,
      tone: DEFAULTS.tone,
      knowledgeBase: recipe.agent.knowledgeBase,
      reasoningDepth: DEFAULTS.reasoningDepth,
      autonomyTier: DEFAULTS.autonomyTier,
      model: "",
      temperature: DEFAULTS.temperature,
      topP: DEFAULTS.topP,
      maxTokens: DEFAULTS.maxTokens,
      topK: null,
      stopSequences: [],
      frequencyPenalty: null,
      presencePenalty: null,
      seed: null,
    },
    workflow: recipe.workflow && flow ? { name: recipe.workflow.name, description: recipe.workflow.description, steps: flow } : null,
    connect: recipe.connect,
    skills: recipe.skills,
    sources: {
      "agent.name": "default",
      "agent.instructions": fromGoal && goal.trim() ? "goal" : "default",
      "agent.knowledgeBase": "default",
    },
  };
}

export function blueprintFromGoal(goal: string): Blueprint {
  return blueprintFromRecipe(pickRecipe(goal), goal);
}

export function blueprintFromRecipeId(id: string, goal = ""): Blueprint | null {
  const recipe = recipeById(id);
  return recipe ? blueprintFromRecipe(recipe, goal, false) : null;
}

export function composeInstructions(bp: Blueprint): string {
  if (bp.agent.instructionsOverride !== null) return bp.agent.instructionsOverride;
  const tone = TONES.find((t) => t.value === bp.agent.tone);
  return `${bp.agent.baseInstructions}\n\n${tone?.line ?? ""}`.trim();
}

/** Turn the (possibly edited) flow into the instruction the server's workflow generator receives. */
export function workflowInstruction(bp: Blueprint): string {
  if (!bp.workflow) return "";
  const lines = bp.workflow.steps.map((s, i) => `${i + 1}. [${FLOW_KIND_LABEL[s.kind]}] ${s.label} - ${s.detail}`);
  const goal = bp.goal ? `\n\nWhat the owner asked for, in their words: "${bp.goal}"` : "";
  return `Build a workflow with exactly these steps, in this order:\n${lines.join("\n")}\n\nUse an agent step for every "Agent step", and a human approval step for every "Human approval".${goal}`;
}

// ---------------------------------------------------------------------------
// Editing
// ---------------------------------------------------------------------------

export function setSource(bp: Blueprint, key: string, source: FieldSource): Blueprint {
  return { ...bp, sources: { ...bp.sources, [key]: source } };
}

export function withAgent(bp: Blueprint, patch: Partial<Blueprint["agent"]>, sourceKey: string, source: FieldSource = "you"): Blueprint {
  return setSource({ ...bp, agent: { ...bp.agent, ...patch } }, sourceKey, source);
}

export function withApp(bp: Blueprint, patch: Partial<AppSpec>, sourceKey: string, source: FieldSource = "you"): Blueprint {
  return setSource({ ...bp, app: { ...bp.app, ...patch } }, sourceKey, source);
}

export function withWiring(bp: Blueprint, patch: Partial<WiringSpec>, sourceKey: string, source: FieldSource = "you"): Blueprint {
  return setSource({ ...bp, wiring: { ...bp.wiring, ...patch } }, sourceKey, source);
}

export function withOutput(bp: Blueprint, output: OutputKind): Blueprint {
  return setSource({ ...bp, output }, "output", "you");
}

export function withWorkflow(bp: Blueprint, enabled: boolean): Blueprint {
  if (!enabled) return { ...bp, workflow: null };
  if (bp.workflow) return bp;
  const recipe = recipeById(bp.recipeId) ?? pickRecipe(bp.goal);
  return {
    ...bp,
    workflow: {
      name: `${bp.agent.name} workflow`,
      description: `Runs ${bp.agent.name} with a human approval before the result goes out.`,
      steps: FLOWS[bp.recipeId] ?? genericFlow(recipe),
    },
  };
}

export function moveStep(bp: Blueprint, index: number, delta: -1 | 1): Blueprint {
  if (!bp.workflow) return bp;
  const to = index + delta;
  const steps = [...bp.workflow.steps];
  if (to < 0 || to >= steps.length) return bp;
  [steps[index], steps[to]] = [steps[to], steps[index]];
  return setSource({ ...bp, workflow: { ...bp.workflow, steps } }, "workflow.steps", "you");
}

export function removeStep(bp: Blueprint, index: number): Blueprint {
  if (!bp.workflow || bp.workflow.steps.length <= 2) return bp;
  const steps = bp.workflow.steps.filter((_, i) => i !== index);
  return setSource({ ...bp, workflow: { ...bp.workflow, steps } }, "workflow.steps", "you");
}

export function addStep(bp: Blueprint, kind: FlowKind): Blueprint {
  if (!bp.workflow) return bp;
  const labels: Record<FlowKind, string> = {
    trigger: "Something happens",
    agent: "Another agent step",
    branch: "Decide which way to go",
    approval: "A person approves",
    action: "Call a tool",
    output: "Deliver the result",
  };
  const next = step(`n${Date.now().toString(36)}`, kind, labels[kind], "Describe what should happen here.");
  const steps = [...bp.workflow.steps];
  // Keep the result step last.
  const at = steps[steps.length - 1]?.kind === "output" ? steps.length - 1 : steps.length;
  steps.splice(at, 0, next);
  return setSource({ ...bp, workflow: { ...bp.workflow, steps } }, "workflow.steps", "you");
}

export function editStep(bp: Blueprint, index: number, patch: Partial<Pick<FlowStep, "label" | "detail">>): Blueprint {
  if (!bp.workflow) return bp;
  const steps = bp.workflow.steps.map((s, i) => (i === index ? { ...s, ...patch } : s));
  return setSource({ ...bp, workflow: { ...bp.workflow, steps } }, "workflow.steps", "you");
}

// ---------------------------------------------------------------------------
// ---------------------------------------------------------------------------
// App output -> the app-builder service. The spec compiles to a natural-
// language scaffold instruction; the generated source lands in the tenant's
// Git repo, never here.
// ---------------------------------------------------------------------------

export function toAppPrompt(bp: Blueprint): string {
  const t = APP_TEMPLATES.find((x) => x.id === bp.app.templateId);
  const lines = [
    `Build "${bp.app.name.trim() || "My app"}" (${t?.label ?? bp.app.templateId}).`,
    bp.goal.trim() ? `What it is for: ${bp.goal.trim()}` : "",
    bp.app.tagline.trim() ? `Tagline under the app name: "${bp.app.tagline.trim()}"` : "",
    `Visual style: clean, modern, uncluttered. Accent colour ${bp.app.accent || "#6d5df2"} (set it as the --brand token in the theme file).`,
  ];
  const feats = bp.app.features
    .split("\n")
    .map((f) => f.trim())
    .filter(Boolean);
  if (feats.length) lines.push(`Must-have features:\n${feats.map((f) => `- ${f}`).join("\n")}`);
  return lines.filter(Boolean).join("\n");
}

/** Blocking problems (the Build button stays disabled) and gentle warnings. */
export function validate(bp: Blueprint): { errors: string[]; warnings: string[] } {
  const errors: string[] = [];
  const warnings: string[] = [];
  if (bp.output === "app") {
    if (!bp.app.name.trim()) errors.push("Give the app a name.");
    if (!APP_TEMPLATES.some((t) => t.id === bp.app.templateId)) errors.push("Pick a kind of app.");
    if (!bp.goal.trim() && !bp.app.features.trim()) warnings.push("A sentence about what the app does gives a much better first build.");
    if (bp.app.accent && !/^#[0-9a-fA-F]{3,8}$/.test(bp.app.accent)) warnings.push("The accent colour does not look like a hex value - the builder will guess instead.");
    return { errors, warnings };
  }
  if (!bp.agent.name.trim()) errors.push("Give the agent a name.");
  else if (bp.agent.name.trim().length < 3) errors.push("The agent name needs at least 3 characters.");
  if (bp.agent.name.trim().length > 80) errors.push("Keep the agent name under 80 characters.");
  if (bp.goal.length > 4000) errors.push("Keep the description of what you want under 4000 characters.");
  if (!composeInstructions(bp).trim()) errors.push("The agent needs instructions.");
  if (bp.workflow && bp.workflow.steps.some((s) => !s.label.trim())) errors.push("Every workflow step needs a label.");
  if (bp.wiring.trigger === "SCHEDULE" && !bp.wiring.cron.trim()) errors.push("A scheduled envoy needs a cron expression.");
  if (bp.wiring.trigger === "CHANNEL" && bp.wiring.channels.length === 0) errors.push("A message-triggered envoy needs at least one channel ticked.");
  for (const s of parseSecrets(bp.wiring.secretsText)) {
    if (!SECRET_NAME_RE.test(s.name)) warnings.push(`Secret "${s.name || "?"}" is not a valid vault name (start with a letter, caps + digits + _). It will be skipped at build time.`);
  }
  if (bp.agent.temperature > 0.85 && bp.recipeId === "support") warnings.push("High creativity on a support agent can make answers vary between customers.");
  if (bp.agent.autonomyTier === "FULLY_AUTONOMOUS") warnings.push("Fully autonomous agents act without sign-off. Keep an approval step for anything that reaches customers.");
  if (bp.agent.knowledgeBase) warnings.push("Knowledge is on, but you still need to upload documents after the build.");
  return { errors, warnings };
}

// ---------------------------------------------------------------------------
// Readiness checklist ("what still blocks a working agent") - the n8n-style
// missing-piece list. Pure: the caller passes what it already knows.
// ---------------------------------------------------------------------------

export interface WorkspaceFacts {
  hasProviderKey: boolean;
  platformModelAvailable: boolean;
}

export interface ReadinessItem {
  id: string;
  label: string;
  /** ready = done; blocked = the agent cannot work until this is fixed; optional = a nice-to-have the agent works fine without. */
  state: "ready" | "optional" | "blocked";
  why: string;
  href?: string;
}

export function readiness(bp: Blueprint, facts: WorkspaceFacts): ReadinessItem[] {
  const items: ReadinessItem[] = [];
  const modelOk = facts.hasProviderKey || facts.platformModelAvailable;
  items.push({
    id: "model",
    label: "An AI model to think with",
    state: modelOk ? "ready" : "blocked",
    why: modelOk
      ? facts.hasProviderKey ? "Your own provider key is connected." : "The platform's hosted model is available to you."
      : "No provider key is connected and no hosted model is available. The agent cannot answer until one is.",
    href: modelOk ? undefined : "/providers",
  });
  for (const c of bp.connect) {
    items.push({ id: `connect-${c.kind}-${c.label}`, label: c.label, state: "optional", why: c.why, href: c.href });
  }
  return items;
}

