// Field catalogue - what the Blueprint view renders. Split out of blueprint.ts.
import {
  APP_TEMPLATES,
  CHANNEL_KINDS,
  TONES,
  composeInstructions,
  withAgent,
  withApp,
  withWiring,
  type AutonomyTier,
  type Blueprint,
  type Level,
  type OutputKind,
  type ReasoningDepth,
  type Tone,
  type WiringSpec,
} from "./blueprintCore";

// Field catalogue - what the Blueprint view renders. `applied` says whether the
// value reaches the platform from this screen.
// ---------------------------------------------------------------------------

export type FieldControl =
  | { kind: "text"; max?: number }
  | { kind: "textarea" }
  | { kind: "select"; options: { value: string; label: string; hint?: string }[] }
  | { kind: "multiselect"; options: { value: string; label: string }[] }
  | { kind: "toggle" }
  | { kind: "range"; min: number; max: number; step: number }
  | { kind: "readonly" };

export interface FieldSpec {
  key: string;
  section: "identity" | "brain" | "knowledge" | "tuning" | "safety" | "defaults" | "app" | "wiring";
  /** Which Build output the field belongs to. Undefined = agent (all the
   *  original fields predate the app output). */
  output?: OutputKind;
  level: Level;
  label: string;
  /** One or two plain sentences: what this setting does and what changes if it moves. */
  help: string;
  control: FieldControl;
  /** Reads the current value as a display string. */
  read: (bp: Blueprint) => string;
  /** Present for editable fields. */
  write?: (bp: Blueprint, raw: string | number | boolean) => Blueprint;
  /** false = shown so the person knows it exists, but this screen does not change it. */
  applied: boolean;
  /** Shown next to a read-only default: where to change it later. */
  href?: string;
}

const num = (v: string | number | boolean, fallback: number) => {
  const n = typeof v === "number" ? v : Number(v);
  return Number.isFinite(n) ? n : fallback;
};
const clamp = (n: number, lo: number, hi: number) => Math.min(hi, Math.max(lo, n));

export const SECTIONS: { id: FieldSpec["section"]; title: string; blurb: string }[] = [
  { id: "identity", title: "Identity", blurb: "Who the agent is and how it sounds." },
  { id: "brain", title: "Brain", blurb: "How carefully it thinks and how much freedom it has." },
  { id: "knowledge", title: "Knowledge", blurb: "What it can look up before answering." },
  { id: "tuning", title: "Response tuning", blurb: "How long, how creative, how repeatable its answers are." },
  { id: "safety", title: "Safety and control", blurb: "Who checks its work." },
  { id: "defaults", title: "Also set by the platform", blurb: "You do not need to touch these. Shown so nothing is hidden." },
  { id: "app", title: "The app", blurb: "What gets scaffolded into your Git repository." },
  { id: "wiring", title: "Wiring", blurb: "How the envoy reaches people and what it may use - channels, secrets, MCP servers, tools, triggers." },
];

/** Wiring rows for the agent output - the envoy's connections to the outside
 *  world. Everything here compiles into the brief (channels/secrets/mcp/tool
 *  arrays, workflow triggers), so "build everything" means exactly that. */
const WIRING_FIELDS: FieldSpec[] = [
  {
    key: "wiring.channels", section: "wiring", level: "simple", label: "Where people reach it", applied: true,
    help: "Tick every channel the envoy should listen on. Channels you pick but have not connected yet show as missing in readiness instead of failing the build.",
    control: { kind: "multiselect", options: CHANNEL_KINDS.map((k) => ({ value: k, label: k.charAt(0) + k.slice(1).toLowerCase() })) },
    read: (bp) => bp.wiring.channels.join(", "),
    write: (bp, v) =>
      withWiring(
        bp,
        { channels: String(v).split(",").map((s) => s.trim().toUpperCase()).filter((s) => (CHANNEL_KINDS as readonly string[]).includes(s)) },
        "wiring.channels"
      ),
  },
  {
    key: "wiring.trigger", section: "wiring", level: "standard", label: "What starts it", applied: true,
    help: "Manual: someone runs it. Schedule: it runs on a timer. Webhook: an external system posts to it. Channel: it reacts when a message arrives on a ticked channel.",
    control: {
      kind: "select",
      options: [
        { value: "MANUAL", label: "Manual", hint: "Run when asked" },
        { value: "SCHEDULE", label: "On a schedule", hint: "Cron timer" },
        { value: "WEBHOOK", label: "Webhook", hint: "Called by another system" },
        { value: "CHANNEL", label: "On a message", hint: "Reacts to a channel" },
      ],
    },
    read: (bp) => ({ MANUAL: "Manual", SCHEDULE: "On a schedule", WEBHOOK: "Webhook", CHANNEL: "On a message" })[bp.wiring.trigger] ?? bp.wiring.trigger,
    write: (bp, v) => withWiring(bp, { trigger: String(v) as WiringSpec["trigger"] }, "wiring.trigger"),
  },
  {
    key: "wiring.cron", section: "wiring", level: "standard", label: "Schedule (cron)", applied: true,
    help: "Five-field cron, UTC - e.g. '0 9 * * *' runs at 9am daily. Only used when 'What starts it' is On a schedule.",
    control: { kind: "text", max: 40 },
    read: (bp) => (bp.wiring.trigger === "SCHEDULE" ? bp.wiring.cron : "Not used - trigger is not a schedule"),
    write: (bp, v) => withWiring(bp, { cron: String(v).trim() }, "wiring.cron"),
  },
  {
    key: "wiring.secretsText", section: "wiring", level: "standard", label: "Keys and secrets it will need", applied: true,
    help: "One per line: NAME - what it unlocks (e.g. 'SHOPIFY_API_KEY - read orders'). Values are NEVER typed here - the envoy creates the secret slots and you fill them in the vault afterwards.",
    control: { kind: "textarea" },
    read: (bp) => bp.wiring.secretsText || "None declared",
    write: (bp, v) => withWiring(bp, { secretsText: String(v) }, "wiring.secretsText"),
  },
  {
    key: "wiring.mcpsText", section: "wiring", level: "advanced", label: "MCP servers to attach", applied: true,
    help: "One per line: the MCP registry id or server name to connect (e.g. 'github-mcp'). Servers that are not in the catalog appear in readiness instead of failing.",
    control: { kind: "textarea" },
    read: (bp) => bp.wiring.mcpsText || "None",
    write: (bp, v) => withWiring(bp, { mcpsText: String(v) }, "wiring.mcpsText"),
  },
  {
    key: "wiring.toolsText", section: "wiring", level: "advanced", label: "Custom tools it needs", applied: true,
    help: "One per line: 'name - what it should do' drafts a code tool, 'name https://api.example.com/openapi.json' wraps a live API. Needs the Manage tools permission at apply time.",
    control: { kind: "textarea" },
    read: (bp) => bp.wiring.toolsText || "None",
    write: (bp, v) => withWiring(bp, { toolsText: String(v) }, "wiring.toolsText"),
  },
];

/** App-output rows. `output: "app"` keeps them out of the agent view. */
const APP_FIELDS: FieldSpec[] = [
  {
    key: "app.name", section: "app", output: "app", level: "simple", label: "App name", applied: true,
    help: "Shown in the title bar, the header and the repository's README. Rename it any time.",
    control: { kind: "text", max: 60 },
    read: (bp) => bp.app.name,
    write: (bp, v) => withApp(bp, { name: String(v).slice(0, 60) }, "app.name"),
  },
  {
    key: "app.templateId", section: "app", output: "app", level: "simple", label: "Kind of app", applied: true,
    help: "The starter that lands in your repository: a web app, a website, a mobile app (Android + iOS + web from one codebase), an API service, or a plain static page. All are real, runnable projects you keep.",
    control: { kind: "select", options: APP_TEMPLATES.map((t) => ({ value: t.id, label: t.label, hint: t.hint })) },
    read: (bp) => APP_TEMPLATES.find((t) => t.id === bp.app.templateId)?.label ?? bp.app.templateId,
    write: (bp, v) => withApp(bp, { templateId: String(v) }, "app.templateId"),
  },
  {
    key: "app.tagline", section: "app", output: "app", level: "simple", label: "Tagline", applied: true,
    help: "One line under the app's name on the landing view. Leave empty to let the builder write one from your description.",
    control: { kind: "text", max: 120 },
    read: (bp) => bp.app.tagline || "Let the builder write it",
    write: (bp, v) => withApp(bp, { tagline: String(v).slice(0, 120) }, "app.tagline"),
  },
  {
    key: "app.accent", section: "app", output: "app", level: "standard", label: "Accent colour", applied: true,
    help: "The main colour (hex like #6d5df2) used for buttons and highlights. Written into the theme file - you can change it later in the Design panel, instantly.",
    control: { kind: "text", max: 9 },
    read: (bp) => bp.app.accent,
    write: (bp, v) => withApp(bp, { accent: String(v).trim() }, "app.accent"),
  },
  {
    key: "app.features", section: "app", output: "app", level: "standard", label: "Must-have features", applied: true,
    help: "One per line - 'login page', 'dark mode', 'a table of orders'. Each line becomes an explicit requirement in the build instructions.",
    control: { kind: "textarea" },
    read: (bp) => bp.app.features || "None listed - the builder infers them from your description",
    write: (bp, v) => withApp(bp, { features: String(v) }, "app.features"),
  },
  {
    key: "app.assets", section: "app", output: "app", level: "standard", label: "Design assets", applied: false, href: "/build",
    help: "Logos, sketches, screenshots and Figma exports can be uploaded right after the app exists - attach them in the chat or the Design tab of the project.",
    control: { kind: "readonly" }, read: () => "Attach after the first build",
  },
];

// (APP_FIELDS is concatenated into FIELDS below - see `export const FIELDS`.)

const AGENT_FIELDS: FieldSpec[] = [
  {
    key: "agent.name", section: "identity", level: "simple", label: "Name", applied: true,
    help: "What you and your team call this agent. It only affects display - rename it any time.",
    control: { kind: "text", max: 60 },
    read: (bp) => bp.agent.name,
    write: (bp, v) => withAgent(bp, { name: String(v).slice(0, 60) }, "agent.name"),
  },
  {
    key: "agent.tone", section: "identity", level: "simple", label: "Tone of voice", applied: true,
    help: "Adds one line to the instructions. Friendly suits customers, Professional suits finance or legal, Concise suits internal tools.",
    control: { kind: "select", options: TONES.map((t) => ({ value: t.value, label: t.label })) },
    read: (bp) => TONES.find((t) => t.value === bp.agent.tone)?.label ?? bp.agent.tone,
    write: (bp, v) => withAgent(bp, { tone: String(v) as Tone }, "agent.tone"),
  },
  {
    key: "agent.instructions", section: "identity", level: "standard", label: "Instructions", applied: true,
    help: "The agent's job description. It follows this on every turn, so be specific about what to do and what never to do. Editing it here replaces the generated text.",
    control: { kind: "textarea" },
    read: (bp) => composeInstructions(bp),
    write: (bp, v) => withAgent(bp, { instructionsOverride: String(v) }, "agent.instructions"),
  },
  {
    key: "agent.reasoningDepth", section: "brain", level: "standard", label: "Thinking depth", applied: true,
    help: "Fast answers straight away. Careful and Rigorous double-check tool calls before acting (slower, fewer mistakes). Planned writes a plan first. Deeper costs more time and tokens.",
    control: {
      kind: "select",
      options: [
        { value: "FAST", label: "Fast", hint: "Answers straight away" },
        { value: "CAREFUL", label: "Careful", hint: "Checks its tool calls" },
        { value: "RIGOROUS", label: "Rigorous", hint: "Independent verification" },
        { value: "PLANNED", label: "Planned", hint: "Plans before acting" },
      ],
    },
    read: (bp) => bp.agent.reasoningDepth,
    write: (bp, v) => withAgent(bp, { reasoningDepth: String(v) as ReasoningDepth }, "agent.reasoningDepth"),
  },
  {
    key: "agent.autonomyTier", section: "safety", level: "simple", label: "How much freedom it has", applied: true,
    help: "Supervised: a person signs off on tool use. Semi-autonomous (recommended): it acts, and risky actions still follow your approval rules. Fully autonomous: it acts on its own with a higher step limit.",
    control: {
      kind: "select",
      options: [
        { value: "SUPERVISED", label: "Supervised", hint: "A person signs off on tool use" },
        { value: "SEMI_AUTONOMOUS", label: "Semi-autonomous", hint: "Recommended" },
        { value: "FULLY_AUTONOMOUS", label: "Fully autonomous", hint: "Acts on its own" },
      ],
    },
    read: (bp) => bp.agent.autonomyTier,
    write: (bp, v) => withAgent(bp, { autonomyTier: String(v) as AutonomyTier }, "agent.autonomyTier"),
  },
  {
    key: "agent.model", section: "brain", level: "advanced", label: "Model override", applied: true,
    help: "Leave empty to use the provider's default model. Type an exact model id only if you need a specific one.",
    control: { kind: "text", max: 120 },
    read: (bp) => bp.agent.model || "Provider default",
    write: (bp, v) => withAgent(bp, { model: String(v).trim() }, "agent.model"),
  },
  {
    key: "agent.knowledgeBase", section: "knowledge", level: "simple", label: "Answer from my documents", applied: true,
    help: "When on, the agent searches the documents you upload before answering and can cite them. You add the documents on the Knowledge page after it is built.",
    control: { kind: "toggle" },
    read: (bp) => (bp.agent.knowledgeBase ? "On" : "Off"),
    write: (bp, v) => withAgent(bp, { knowledgeBase: Boolean(v) }, "agent.knowledgeBase"),
  },
  {
    key: "agent.temperature", section: "tuning", level: "standard", label: "Creativity (temperature)", applied: true,
    help: "Low (0-0.3) gives steady, repeatable answers - good for support and data. High (0.8+) gives more varied wording - good for brainstorming.",
    control: { kind: "range", min: 0, max: 1, step: 0.05 },
    read: (bp) => bp.agent.temperature.toFixed(2),
    write: (bp, v) => withAgent(bp, { temperature: clamp(num(v, 0.7), 0, 1) }, "agent.temperature"),
  },
  {
    key: "agent.maxTokens", section: "tuning", level: "standard", label: "Maximum reply length (tokens)", applied: true,
    help: "Caps one reply (about 0.75 words per token). Lower keeps replies short and cheap; the platform allows at most 8192.",
    control: { kind: "range", min: 128, max: 8192, step: 128 },
    read: (bp) => String(bp.agent.maxTokens),
    write: (bp, v) => withAgent(bp, { maxTokens: clamp(Math.round(num(v, 1024)), 128, 8192) }, "agent.maxTokens"),
  },
  {
    key: "agent.topP", section: "tuning", level: "advanced", label: "Word-choice spread (top-p)", applied: true,
    help: "Limits how many likely words it considers. 1.0 uses them all. Change this OR creativity, not both.",
    control: { kind: "range", min: 0, max: 1, step: 0.05 },
    read: (bp) => bp.agent.topP.toFixed(2),
    write: (bp, v) => withAgent(bp, { topP: clamp(num(v, 1), 0, 1) }, "agent.topP"),
  },
  {
    key: "agent.topK", section: "tuning", level: "advanced", label: "Candidate words (top-k)", applied: true,
    help: "Only consider the K most likely next words (1-1000). Empty = the provider's default.",
    control: { kind: "text", max: 4 },
    read: (bp) => (bp.agent.topK === null ? "Provider default" : String(bp.agent.topK)),
    write: (bp, v) => {
      const s = String(v).trim();
      return withAgent(bp, { topK: s === "" ? null : clamp(Math.round(num(s, 40)), 1, 1000) }, "agent.topK");
    },
  },
  {
    key: "agent.frequencyPenalty", section: "tuning", level: "advanced", label: "Repetition penalty", applied: true,
    help: "Positive values discourage repeating the same words (-2 to 2). Empty = provider default.",
    control: { kind: "text", max: 5 },
    read: (bp) => (bp.agent.frequencyPenalty === null ? "Provider default" : String(bp.agent.frequencyPenalty)),
    write: (bp, v) => {
      const s = String(v).trim();
      return withAgent(bp, { frequencyPenalty: s === "" ? null : clamp(num(s, 0), -2, 2) }, "agent.frequencyPenalty");
    },
  },
  {
    key: "agent.presencePenalty", section: "tuning", level: "advanced", label: "New-topic push", applied: true,
    help: "Positive values push it toward new topics instead of staying on one (-2 to 2). Empty = provider default.",
    control: { kind: "text", max: 5 },
    read: (bp) => (bp.agent.presencePenalty === null ? "Provider default" : String(bp.agent.presencePenalty)),
    write: (bp, v) => {
      const s = String(v).trim();
      return withAgent(bp, { presencePenalty: s === "" ? null : clamp(num(s, 0), -2, 2) }, "agent.presencePenalty");
    },
  },
  {
    key: "agent.stopSequences", section: "tuning", level: "advanced", label: "Stop sequences", applied: true,
    help: "Comma-separated text that ends a reply the moment it appears (up to 8, 64 characters each). Empty = none.",
    control: { kind: "text" },
    read: (bp) => (bp.agent.stopSequences.length ? bp.agent.stopSequences.join(", ") : "None"),
    write: (bp, v) =>
      withAgent(
        bp,
        { stopSequences: String(v).split(",").map((s) => s.trim().slice(0, 64)).filter(Boolean).slice(0, 8) },
        "agent.stopSequences"
      ),
  },
  {
    key: "agent.seed", section: "tuning", level: "advanced", label: "Repeatable seed", applied: true,
    help: "A number that makes answers more repeatable for testing. Empty = random each time.",
    control: { kind: "text", max: 9 },
    read: (bp) => (bp.agent.seed === null ? "Random" : String(bp.agent.seed)),
    write: (bp, v) => {
      const s = String(v).trim();
      return withAgent(bp, { seed: s === "" ? null : Math.round(num(s, 0)) }, "agent.seed");
    },
  },
  // ---- Read-only platform defaults: visible so "everything" really is visible.
  {
    key: "default.tools", section: "defaults", level: "standard", label: "Tools", applied: false, href: "/tools",
    help: "The agent may use every built-in tool and every connector you enable. Restrict it to a chosen list on the agent's page.",
    control: { kind: "readonly" }, read: () => "All enabled tools",
  },
  {
    key: "default.skills", section: "defaults", level: "standard", label: "Skills", applied: false, href: "/skills",
    help: "Built-in skills load only when a task needs them; nothing to attach.",
    control: { kind: "readonly" }, read: (bp) => (bp.skills.length ? bp.skills.join(", ") : "Built-in skills, on demand"),
  },
  {
    key: "default.guardrails", section: "defaults", level: "standard", label: "Guardrails", applied: false, href: "/admin/guardrails",
    help: "Content guardrails are off until you turn some on for this agent. Available ones depend on your plan.",
    control: { kind: "readonly" }, read: () => "None enabled",
  },
  {
    key: "default.experience", section: "defaults", level: "advanced", label: "Experience mode", applied: false,
    help: "Simple: the agent uses the streamlined chat experience.",
    control: { kind: "readonly" }, read: () => "Simple",
  },
  {
    key: "default.voice", section: "defaults", level: "advanced", label: "Voice, speech and video", applied: false, href: "/agents",
    help: "Speech-to-text, text-to-speech and video models use the platform's default chain until you pick providers on the agent's page.",
    control: { kind: "readonly" }, read: () => "Platform default",
  },
  {
    key: "default.edge", section: "defaults", level: "advanced", label: "On-device routing", applied: false, href: "/edge",
    help: "Whether replies may run on the person's own device instead of the server. Set per agent after creation.",
    control: { kind: "readonly" }, read: () => "Platform default",
  },
  {
    key: "default.memory", section: "defaults", level: "advanced", label: "Memory", applied: false,
    help: "Conversation memory follows your workspace settings; you can review and delete what it remembers on the agent's page.",
    control: { kind: "readonly" }, read: () => "Workspace setting",
  },
];

export const FIELDS: FieldSpec[] = [...AGENT_FIELDS, ...APP_FIELDS, ...WIRING_FIELDS];

const LEVEL_RANK: Record<Level, number> = { simple: 0, standard: 1, advanced: 2 };
export const fieldsForLevel = (level: Level, output: OutputKind = "agent") =>
  FIELDS.filter(
    (f) => LEVEL_RANK[f.level] <= LEVEL_RANK[level] && (f.output ?? "agent") === output
  );

/** Rows visible at each level - used for the "N settings" label on the level picker. */
export const fieldCount = (level: Level, output: OutputKind = "agent") => fieldsForLevel(level, output).length;
