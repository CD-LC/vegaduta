// Copy / import a Blueprint as JSON. Export is plain JSON.stringify; import is
// strict: anything that is not recognisably a blueprint is rejected with a
// message, and optional gaps are filled from a safe default so an older or
// hand-trimmed export still loads.

import {
  CHANNEL_KINDS,
  FLOW_KIND_LABEL,
  TONES,
  blueprintFromGoal,
  type Blueprint,
  type FieldSource,
} from "../lib/build/blueprint";

export const MAX_BLUEPRINT_JSON_CHARS = 200_000;

export interface ParsedBlueprint {
  ok: boolean;
  blueprint?: Blueprint;
  error?: string;
}

const REASONING = ["FAST", "CAREFUL", "RIGOROUS", "PLANNED"];
const AUTONOMY = ["SUPERVISED", "SEMI_AUTONOMOUS", "FULLY_AUTONOMOUS"];
const TRIGGERS = ["MANUAL", "SCHEDULE", "WEBHOOK", "CHANNEL"];
const SOURCES: FieldSource[] = ["default", "goal", "device-ai", "cloud-ai", "you"];
const CONNECT_KINDS = ["mcp", "channel", "knowledge", "schedule"];

export function serializeBlueprint(bp: Blueprint): string {
  return JSON.stringify(bp, null, 2);
}

const isObj = (v: unknown): v is Record<string, unknown> => !!v && typeof v === "object" && !Array.isArray(v);

/** Returns the first problem found in the optional object `o`, or null. */
function check(o: Record<string, unknown>, where: string, rules: Record<string, (v: unknown) => boolean>): string | null {
  for (const [k, ok] of Object.entries(rules)) {
    if (k in o && o[k] !== undefined && !ok(o[k])) return `${where}.${k} has the wrong type or an unknown value.`;
  }
  return null;
}

const str = (v: unknown) => typeof v === "string";
const bool = (v: unknown) => typeof v === "boolean";
const num = (v: unknown) => typeof v === "number" && Number.isFinite(v);
const numOrNull = (v: unknown) => v === null || num(v);
const oneOf = (list: readonly string[]) => (v: unknown) => typeof v === "string" && list.includes(v);
const strArray = (v: unknown) => Array.isArray(v) && v.every(str);

export function parseBlueprintJson(text: string): ParsedBlueprint {
  const raw = (text || "").trim();
  if (!raw) return { ok: false, error: "Paste a blueprint JSON first." };
  if (raw.length > MAX_BLUEPRINT_JSON_CHARS) return { ok: false, error: "That is too large to be a blueprint." };
  let data: unknown;
  try {
    data = JSON.parse(raw);
  } catch {
    return { ok: false, error: "That is not valid JSON." };
  }
  if (!isObj(data)) return { ok: false, error: "A blueprint is a JSON object, not a list or a single value." };
  if (!isObj(data.agent) || !str(data.agent.name)) {
    return { ok: false, error: 'This does not look like a blueprint: it has no "agent" with a name.' };
  }

  let bad = check(data, "blueprint", {
    goal: str,
    recipeId: str,
    output: oneOf(["agent", "app"]),
    skills: strArray,
  });
  if (bad) return { ok: false, error: bad };

  bad = check(data.agent, "agent", {
    name: str,
    baseInstructions: str,
    instructionsOverride: (v) => v === null || str(v),
    tone: oneOf(TONES.map((t) => t.value)),
    knowledgeBase: bool,
    reasoningDepth: oneOf(REASONING),
    autonomyTier: oneOf(AUTONOMY),
    model: str,
    temperature: num,
    topP: num,
    maxTokens: num,
    topK: numOrNull,
    stopSequences: strArray,
    frequencyPenalty: numOrNull,
    presencePenalty: numOrNull,
    seed: numOrNull,
  });
  if (bad) return { ok: false, error: bad };

  if (data.app !== undefined) {
    if (!isObj(data.app)) return { ok: false, error: "blueprint.app must be an object." };
    bad = check(data.app, "app", { name: str, templateId: str, accent: str, tagline: str, features: str });
    if (bad) return { ok: false, error: bad };
  }
  if (data.wiring !== undefined) {
    if (!isObj(data.wiring)) return { ok: false, error: "blueprint.wiring must be an object." };
    bad = check(data.wiring, "wiring", {
      channels: (v) => Array.isArray(v) && v.every((c) => typeof c === "string" && (CHANNEL_KINDS as readonly string[]).includes(c)),
      secretsText: str,
      mcpsText: str,
      toolsText: str,
      trigger: oneOf(TRIGGERS),
      cron: str,
    });
    if (bad) return { ok: false, error: bad };
  }
  if (data.workflow !== undefined && data.workflow !== null) {
    const w = data.workflow;
    if (!isObj(w) || !str(w.name) || !Array.isArray(w.steps)) {
      return { ok: false, error: "blueprint.workflow needs a name and a list of steps." };
    }
    const kinds = Object.keys(FLOW_KIND_LABEL);
    for (const [i, s] of w.steps.entries()) {
      if (!isObj(s) || !str(s.id) || !str(s.label) || !oneOf(kinds)(s.kind) || (s.detail !== undefined && !str(s.detail))) {
        return { ok: false, error: `Workflow step ${i + 1} is malformed (needs id, label and a known kind).` };
      }
    }
    if (w.description !== undefined && !str(w.description)) return { ok: false, error: "workflow.description must be text." };
  }
  if (data.connect !== undefined) {
    if (!Array.isArray(data.connect) || !data.connect.every((c) => isObj(c) && oneOf(CONNECT_KINDS)(c.kind) && str(c.label))) {
      return { ok: false, error: "blueprint.connect must be a list of {kind, label, ...} entries." };
    }
  }
  if (data.sources !== undefined) {
    if (!isObj(data.sources) || !Object.values(data.sources).every((s) => typeof s === "string" && (SOURCES as string[]).includes(s))) {
      return { ok: false, error: "blueprint.sources has an unknown value." };
    }
  }

  // Fill gaps from a safe default, then lay the validated input over it.
  const base = blueprintFromGoal(typeof data.goal === "string" ? data.goal : "");
  const d = data as Record<string, any>;
  const merged: Blueprint = {
    ...base,
    ...d,
    agent: { ...base.agent, ...d.agent },
    app: { ...base.app, ...(d.app ?? {}) },
    wiring: { ...base.wiring, ...(d.wiring ?? {}) },
    workflow:
      d.workflow === undefined
        ? base.workflow
        : d.workflow === null
          ? null
          : { description: "", ...d.workflow, steps: d.workflow.steps.map((s: any) => ({ detail: "", ...s })) },
    connect: d.connect ?? base.connect,
    skills: d.skills ?? base.skills,
    sources: d.sources ?? base.sources,
    output: d.output ?? "agent",
  };
  return { ok: true, blueprint: merged };
}
