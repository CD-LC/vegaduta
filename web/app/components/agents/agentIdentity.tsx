"use client";

import type { CSSProperties, ReactNode } from "react";
import { AudioLines, Bot, Database, Eye, Image as ImageIcon, Languages, LineChart, Mic, Video, Workflow } from "lucide-react";
import { cn } from "../../lib/utils";

/**
 * Agent identity + state taxonomy — the "what kind of thing is this / what is
 * it doing" layer for the Agents screen.
 *
 * Why this module exists: the workbench used to say almost nothing about an
 * agent's identity. Every agent got the same generic <Bot/> in the same
 * primary tint, and its status was the literal word "live" in muted grey. In a
 * switcher list of ten agents nothing was scannable — you had to read names.
 *
 * Two separate systems, kept separate on purpose (see
 * docs/DESIGN-SYSTEM-CONTRACT-2026-07-29.md §2/§3):
 *
 *   CATEGORY  = "what kind of agent is this"      → rounded SQUARE + icon (.cat-chip)
 *   STATE     = "what is it doing right now"      → CIRCLE dot / pill, animates when live
 *
 * Category is never carried by hue alone — every category ships a mandatory
 * icon, because simulated protanopia collapses cat-assistant/cat-comms to a
 * perceptual distance of 6.7. State is readable in greyscale because the pill
 * always carries a word, and "live" is distinguished by MOTION, not only hue.
 *
 * IMPORTANT: every predicate below reads a field that really exists on
 * AgentResponse (core/agents/.../dto/AgentResponse.java). Nothing here is
 * inferred from a field the API does not send. The same functions also accept
 * the workbench's in-progress *draft* form state, which carries the same field
 * names for the subset a draft has.
 */

// ---------------------------------------------------------------------------
// Category taxonomy
// ---------------------------------------------------------------------------

export type AgentCategoryKey = "creative" | "comms" | "automation" | "data" | "analysis" | "assistant";

export type AgentCategory = {
  key: AgentCategoryKey;
  /** CSS custom-property reference — never a literal colour. */
  token: string;
  label: string;
  /** Plain-English "why this agent got this category", used as the chip title. */
  blurb: string;
  Icon: any;
};

export const AGENT_CATEGORIES: Record<AgentCategoryKey, AgentCategory> = {
  creative: {
    key: "creative",
    token: "var(--cat-creative)",
    label: "Voice & video",
    blurb: "Has a voice or video model attached — this agent speaks or sees, not just types.",
    Icon: AudioLines,
  },
  comms: {
    key: "comms",
    token: "var(--cat-comms)",
    label: "Multilingual",
    blurb: "Sarvam translate / transcribe / speak tools are enabled for this agent.",
    Icon: Languages,
  },
  automation: {
    key: "automation",
    token: "var(--cat-automation)",
    label: "Automation",
    blurb: "Acts on the world — a restricted tool set, code workspace, sandboxed execution, delegation or full autonomy.",
    Icon: Workflow,
  },
  data: {
    key: "data",
    token: "var(--cat-data)",
    label: "Knowledge",
    blurb: "Retrieval-backed — this agent searches your knowledge collections before answering.",
    Icon: Database,
  },
  analysis: {
    key: "analysis",
    token: "var(--cat-analysis)",
    label: "Analysis",
    blurb: "Tuned for deliberation — deeper reasoning, or cost-aware model routing.",
    Icon: LineChart,
  },
  assistant: {
    key: "assistant",
    token: "var(--cat-assistant)",
    label: "Assistant",
    blurb: "General conversational agent — prompt and model, no extra capabilities attached yet.",
    Icon: Bot,
  },
};

/** Jackson serialises Java `Set<..>` as a JSON array; be tolerant of null/Set too. */
function countOf(value: any): number {
  if (!value) return 0;
  if (Array.isArray(value)) return value.length;
  if (typeof value.size === "number") return value.size;
  return 0;
}

/**
 * Whether `agent.allowedToolNames` contains a given registered tool-callback
 * name (e.g. "generate_image"). Same array-or-Set tolerance as `countOf`
 * above, since Jackson serialises a Java `Set<String>` as a JSON array but a
 * few callers still hand this a real `Set`.
 */
function hasToolName(agent: any, name: string): boolean {
  const value = agent?.allowedToolNames;
  if (!value) return false;
  if (Array.isArray(value)) return value.includes(name);
  if (typeof value.has === "function") return value.has(name);
  if (typeof value.size === "number") {
    try {
      return Array.from(value as Iterable<string>).includes(name);
    } catch {
      return false;
    }
  }
  return false;
}

/**
 * Most-distinctive-capability-wins. The order is deliberate: a voice agent is
 * "the voice one" even if it also has knowledge attached, because that is the
 * thing a human scanning the list recognises it by.
 */
export function agentCategoryKey(agent: any): AgentCategoryKey {
  if (!agent) return "assistant";
  if (agent.voiceModelProvider || agent.voiceModel || agent.videoModelProvider || agent.videoModel) {
    return "creative";
  }
  if (agent.sarvamEnabled) return "comms";
  if (
    agent.codeWorkspaceEnabled ||
    agent.e2bEnabled ||
    countOf(agent.collaboratorAgentIds) > 0 ||
    agent.autonomyTier === "FULLY_AUTONOMOUS" ||
    countOf(agent.allowedToolNames) > 0
  ) {
    return "automation";
  }
  if (agent.knowledgeBaseEnabled || countOf(agent.allowedKnowledgeCollectionIds) > 0) return "data";
  if (agent.reasoningDepth === "CAREFUL" || agent.reasoningDepth === "RIGOROUS" || agent.costRoutingEnabled) {
    return "analysis";
  }
  return "assistant";
}

export function agentCategory(agent: any): AgentCategory {
  return AGENT_CATEGORIES[agentCategoryKey(agent)];
}

// ---------------------------------------------------------------------------
// Deterministic per-agent sigil
// ---------------------------------------------------------------------------

/** FNV-1a. Stable across reloads and machines — same agent, same mark, always. */
function hash32(seed: string): number {
  let h = 0x811c9dc5;
  for (let i = 0; i < seed.length; i += 1) {
    h ^= seed.charCodeAt(i);
    h = Math.imul(h, 0x01000193);
  }
  return h >>> 0;
}

/**
 * A 5x5 horizontally-mirrored dot matrix seeded from the agent id — the same
 * identicon idea GitHub/Gravatar use, so two agents in the same category are
 * still told apart at a glance without reading their names. Drawn in
 * `currentColor` so it inherits whatever `--cat` the enclosing chip resolved,
 * i.e. it never introduces a colour of its own.
 */
export function AgentSigil({ seed, className }: { seed?: string | null; className?: string }) {
  const h = hash32(seed || "unsaved-draft");
  const cells: ReactNode[] = [];
  for (let col = 0; col < 3; col += 1) {
    for (let row = 0; row < 5; row += 1) {
      const on = (h >>> (col * 5 + row)) & 1;
      if (!on) continue;
      cells.push(<rect key={`${col}-${row}`} x={col} y={row} width={1} height={1} />);
      if (col < 2) cells.push(<rect key={`${col}-${row}-m`} x={4 - col} y={row} width={1} height={1} />);
    }
  }
  return (
    <svg viewBox="0 0 5 5" aria-hidden="true" className={className} fill="currentColor" shapeRendering="crispEdges">
      {cells}
    </svg>
  );
}

// ---------------------------------------------------------------------------
// AgentMark — the scannable identity: category chip + per-agent sigil
// ---------------------------------------------------------------------------

export type AgentMarkSize = "sm" | "md" | "lg" | "hero";

const MARK_ICON_SIZE: Record<AgentMarkSize, number> = { sm: 12, md: 15, lg: 19, hero: 24 };
// Every size is a shared .cat-chip-* utility — no box metrics live in this
// file. `hero` briefly used an inline width/height/border-radius override
// while .cat-chip-xl did not exist yet; it does now (globals.css), and the
// inline values are gone so a second hero mark can't drift away from this one.
const MARK_CLASS: Record<AgentMarkSize, string> = {
  sm: "cat-chip-sm",
  md: "",
  lg: "cat-chip-lg",
  hero: "cat-chip-xl",
};

/**
 * One mark carries BOTH signals the redesign brief asked for: the hue+icon say
 * which category, the dot matrix behind them is unique-but-stable per agent.
 */
export function AgentMark({
  agent,
  size = "md",
  className,
  title,
}: {
  agent: any;
  size?: AgentMarkSize;
  className?: string;
  title?: string;
}) {
  const category = agentCategory(agent);
  const Icon = category.Icon;
  return (
    <span
      className={cn("cat-chip relative overflow-hidden", MARK_CLASS[size], className)}
      style={{ ["--cat" as any]: category.token } as CSSProperties}
      title={title ?? `${category.label} — ${category.blurb}`}
      data-agent-category={category.key}
    >
      <AgentSigil
        seed={agent?.id || agent?.name || null}
        className="pointer-events-none absolute inset-0 size-full opacity-[0.18]"
      />
      <Icon size={MARK_ICON_SIZE[size]} className="relative" strokeWidth={1.9} />
    </span>
  );
}

/** Text-only category label, tinted via .cat-tint (icon still mandatory). */
export function AgentCategoryTag({ agent, className }: { agent: any; className?: string }) {
  const category = agentCategory(agent);
  const Icon = category.Icon;
  return (
    <span
      className={cn(
        "inline-flex items-center gap-1.5 rounded-full border px-2 py-0.5 text-[0.6875rem] font-medium leading-5",
        className
      )}
      style={
        {
          ["--cat" as any]: category.token,
          color: "var(--cat)",
          background: "color-mix(in srgb, var(--cat) 12%, transparent)",
          borderColor: "color-mix(in srgb, var(--cat) 30%, transparent)",
        } as CSSProperties
      }
      title={category.blurb}
    >
      <Icon size={11} strokeWidth={2} />
      {category.label}
    </span>
  );
}

// ---------------------------------------------------------------------------
// State taxonomy
// ---------------------------------------------------------------------------

export type AgentStateKey = "draft" | "working" | "degraded" | "recovered" | "locked" | "ready";

export type AgentState = {
  key: AgentStateKey;
  token: string;
  label: string;
  /** Live states animate (expanding ring). Idle states must NOT. */
  live: boolean;
  hint: string;
};

export const AGENT_STATES: Record<AgentStateKey, AgentState> = {
  draft: {
    key: "draft",
    token: "var(--state-queued)",
    label: "Draft",
    live: false,
    hint: "Not created yet — nothing has been saved to the server.",
  },
  working: {
    key: "working",
    token: "var(--state-running)",
    label: "Working",
    live: true,
    hint: "A change is being applied to this agent right now.",
  },
  degraded: {
    key: "degraded",
    token: "var(--state-error)",
    label: "Degraded",
    live: false,
    hint: "This agent's fallback provider has itself failed at least once.",
  },
  recovered: {
    key: "recovered",
    token: "var(--state-warning)",
    label: "Fell back",
    live: false,
    hint: "This agent has had to fail over to its fallback provider.",
  },
  locked: {
    key: "locked",
    token: "var(--state-idle)",
    label: "Locked",
    live: false,
    hint: "Its edit window has closed — duplicate it to keep changing it.",
  },
  ready: {
    key: "ready",
    token: "var(--state-success)",
    label: "Live",
    live: false,
    hint: "Created and ready to serve chat.",
  },
};

/**
 * Every branch reads a real AgentResponse field: fallbackFailedCount /
 * fallbackTriggeredCount (#955 counters) and `editable` (server-computed
 * EditLockPolicy). `busy` is the caller's own in-flight mutation flag — the
 * only genuinely "running right now" signal this screen has.
 */
export function agentStateKey(agent: any, opts: { busy?: boolean } = {}): AgentStateKey {
  // busy first: a draft that is mid-create is "working", not "draft".
  if (opts.busy) return "working";
  if (!agent) return "draft";
  if (agent.fallbackFailedCount > 0) return "degraded";
  if (agent.fallbackTriggeredCount > 0) return "recovered";
  if (agent.editable === false) return "locked";
  return "ready";
}

export function agentState(agent: any, opts: { busy?: boolean } = {}): AgentState {
  return AGENT_STATES[agentStateKey(agent, opts)];
}

export function AgentStateDot({ state, className }: { state: AgentState; className?: string }) {
  return (
    <span
      className={cn("state-dot", state.live && "state-dot-live", className)}
      style={{ ["--state" as any]: state.token } as CSSProperties}
      aria-hidden="true"
    />
  );
}

/** Pill always carries the word, so the signal survives greyscale printing. */
export function AgentStatePill({
  state,
  label,
  className,
}: {
  state: AgentState;
  label?: string;
  className?: string;
}) {
  return (
    <span
      className={cn("state-pill", className)}
      style={{ ["--state" as any]: state.token } as CSSProperties}
      title={state.hint}
    >
      <AgentStateDot state={state} />
      {label ?? state.label}
    </span>
  );
}

// ---------------------------------------------------------------------------
// Capability read-out
// ---------------------------------------------------------------------------

export type AgentCapability = { key: string; label: string; token: string; Icon: any; title: string };

/**
 * The secondary "what is bolted on to this agent" row. Same rule as
 * categories: every entry has an icon, so the tint is decoration, not meaning.
 * Only capabilities that are actually ON are returned — an empty array is a
 * legitimate answer for a plain assistant.
 */
export function agentCapabilities(agent: any): AgentCapability[] {
  if (!agent) return [];
  const out: AgentCapability[] = [];
  if (agent.knowledgeBaseEnabled || countOf(agent.allowedKnowledgeCollectionIds) > 0) {
    const scoped = countOf(agent.allowedKnowledgeCollectionIds);
    out.push({
      key: "knowledge",
      label: scoped > 0 ? `Knowledge · ${scoped}` : "Knowledge",
      token: "var(--cat-data)",
      Icon: AGENT_CATEGORIES.data.Icon,
      title: scoped > 0 ? `Restricted to ${scoped} collection(s).` : "Searches every collection this tenant has.",
    });
  }
  const tools = countOf(agent.allowedToolNames);
  if (tools > 0) {
    out.push({
      key: "tools",
      label: `Tools · ${tools}`,
      token: "var(--cat-automation)",
      Icon: AGENT_CATEGORIES.automation.Icon,
      title: `Restricted to ${tools} explicitly allowed tool(s).`,
    });
  }
  // Output-generation capability: allowedToolNames enabling generate_image /
  // generate_video / speak (the exact registered tool-callback names — see
  // GenerateImageToolCallback#L43, GenerateVideoToolCallback#L45,
  // SpeakToolCallback#L42, and AgentChatService.MEDIA_GENERATION_TOOL_NAMES
  // in core/agents, which is the same trio kept in sync there). These are
  // distinct from the "voice"/"video" chips above, which describe an STT/
  // video-input MODEL attached to the agent, not an output tool it can call
  // — hence the distinct icon and the "Generates ·" prefix so the two never
  // read as duplicates in the chip row.
  if (hasToolName(agent, "generate_image")) {
    out.push({
      key: "generates-image",
      label: "Generates · Image",
      token: "var(--cat-creative)",
      Icon: ImageIcon,
      title: "The generate_image tool is enabled — this agent can produce images, not just describe them.",
    });
  }
  if (hasToolName(agent, "generate_video")) {
    out.push({
      key: "generates-video",
      label: "Generates · Video",
      token: "var(--cat-creative)",
      Icon: Video,
      title: "The generate_video tool is enabled — this agent can produce video clips, not just describe them.",
    });
  }
  if (hasToolName(agent, "speak")) {
    out.push({
      key: "generates-audio",
      label: "Generates · Audio",
      token: "var(--cat-creative)",
      Icon: Mic,
      title: "The speak tool is enabled — this agent can produce spoken audio, not just text.",
    });
  }
  // Input-side sense capability ("Sight"): the agent's resolved
  // model accepts image input - `modalities.image`, the exact same
  // backend-computed flag (ModelModalitiesService, via AgentResponse's
  // modalities block) that gates the playground's camera panel and the
  // inline-attachment picker, so the chip can never claim sight a send would
  // then reject. Distinct from the "Generates · Image" chip above (an OUTPUT
  // tool) and from the "voice"/"video" model chips below (attached STT/video
  // models) - this one says frames can be SHOWN to it, hence the eye.
  if (agent.modalities?.image) {
    out.push({
      key: "sight",
      label: "Sight",
      token: "var(--cat-creative)",
      Icon: Eye,
      title: "This agent's model accepts images - point a camera or share a screen and it can see.",
    });
  }
  if (agent.codeWorkspaceEnabled) {
    out.push({
      key: "code",
      label: "Code workspace",
      token: "var(--cat-creative)",
      Icon: AGENT_CATEGORIES.creative.Icon,
      title: "Can create, apply and run code changes in a granted repo.",
    });
  }
  // "how do i enable this per agent" - direct feedback. Mirrors the
  // codeWorkspaceEnabled chip above exactly - same category token/icon reuse
  // convention, since e2bEnabled/sarvamEnabled are per-agent opt-ins of the
  // same shape (see AgentWorkbench.tsx's toggleE2b/toggleSarvam).
  if (agent.e2bEnabled) {
    out.push({
      key: "e2b",
      label: "Code execution",
      token: "var(--cat-creative)",
      Icon: AGENT_CATEGORIES.creative.Icon,
      title: "The run_code tool is enabled — this agent can run short Python/Node/Bash snippets in a disposable sandbox.",
    });
  }
  if (agent.sarvamEnabled) {
    out.push({
      key: "sarvam",
      label: "Sarvam",
      token: "var(--cat-creative)",
      Icon: AGENT_CATEGORIES.creative.Icon,
      title: "Sarvam AI's translation/speech tools are enabled for this agent.",
    });
  }
  if (agent.voiceModelProvider || agent.voiceModel) {
    out.push({
      key: "voice",
      label: agent.voiceModel || "Voice",
      token: "var(--cat-creative)",
      Icon: AGENT_CATEGORIES.creative.Icon,
      title: `Voice turns route through ${agent.voiceModelProvider || "the default chain"}.`,
    });
  }
  if (agent.videoModelProvider || agent.videoModel) {
    out.push({
      key: "video",
      label: agent.videoModel || "Video",
      token: "var(--cat-creative)",
      Icon: AGENT_CATEGORIES.creative.Icon,
      title: `Video turns route through ${agent.videoModelProvider || "the default chain"}.`,
    });
  }
  if (agent.sarvamEnabled) {
    out.push({
      key: "sarvam",
      label: "Sarvam",
      token: "var(--cat-comms)",
      Icon: AGENT_CATEGORIES.comms.Icon,
      title: "Sarvam translate / transcribe / speak tools are enabled.",
    });
  }
  if (countOf(agent.collaboratorAgentIds) > 0) {
    out.push({
      key: "collab",
      label: `Delegates · ${countOf(agent.collaboratorAgentIds)}`,
      token: "var(--cat-automation)",
      Icon: AGENT_CATEGORIES.automation.Icon,
      title: "Can hand work to other agents in this tenant.",
    });
  }
  if (agent.costRoutingEnabled) {
    out.push({
      key: "routing",
      label: "Cost routing",
      token: "var(--cat-analysis)",
      Icon: AGENT_CATEGORIES.analysis.Icon,
      title: "Simple turns are routed to a cheaper economy model.",
    });
  }
  return out;
}

/** Small icon+label chip for one capability. Icon is mandatory, per §2. */
export function CapabilityChip({ capability, className }: { capability: AgentCapability; className?: string }) {
  const Icon = capability.Icon;
  return (
    <span
      className={cn(
        "inline-flex items-center gap-1.5 rounded-md border px-2 py-1 text-[0.6875rem] font-medium leading-4",
        className
      )}
      style={
        {
          ["--cat" as any]: capability.token,
          color: "var(--cat)",
          background: "color-mix(in srgb, var(--cat) 10%, transparent)",
          borderColor: "color-mix(in srgb, var(--cat) 26%, transparent)",
        } as CSSProperties
      }
      title={capability.title}
    >
      <Icon size={11} strokeWidth={2} />
      {capability.label}
    </span>
  );
}
