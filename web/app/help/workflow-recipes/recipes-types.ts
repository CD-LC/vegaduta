import type { LucideIcon } from "lucide-react";
import { AudioLines, BookOpen, Bot, Code2, Cpu, GitBranch, Layers, Network, Repeat, Send, UserCheck, Workflow, Wrench, Zap } from "lucide-react";

// ---------------------------------------------------------------------------
// LIGHT module: the shared shape of the workflow-recipes guide + NODE_META.
//
// This exists so a consumer can render the guide's chrome WITHOUT importing
// recipes-data.tsx, which pulls in every sector pack (~1.9 MB of source). The
// guide page, the landing page and Academy all import types/NODE_META from
// HERE and load the actual recipe packs lazily (see recipes-registry.ts).
// Splitting this out is the whole reason the catalogue can grow without
// growing the initial bundle.
// ---------------------------------------------------------------------------

export type NodeKind =
  | "trigger"
  | "agent"
  | "knowledge"
  | "condition"
  | "approval"
  | "output"
  | "tool"
  | "voice"
  | "device"
  | "loop"
  | "code"
  | "parallel"
  | "supervisor"
  | "subworkflow";

export const NODE_META: Record<NodeKind, { label: string; icon: LucideIcon }> = {
  trigger: { label: "Trigger", icon: Zap },
  agent: { label: "Agent", icon: Bot },
  knowledge: { label: "Knowledge", icon: BookOpen },
  condition: { label: "Condition", icon: GitBranch },
  approval: { label: "Approval", icon: UserCheck },
  output: { label: "Output", icon: Send },
  tool: { label: "Tool (MCP)", icon: Wrench },
  voice: { label: "Voice", icon: AudioLines },
  device: { label: "Device", icon: Cpu },
  loop: { label: "Loop", icon: Repeat },
  code: { label: "Code", icon: Code2 },
  parallel: { label: "Parallel", icon: Layers },
  supervisor: { label: "Supervisor", icon: Network },
  subworkflow: { label: "Sub-workflow", icon: Workflow },
};

export type RecipeStep = { kind: NodeKind; title: string; detail: string };

// One agent in a multi-agent recipe: its role, the MODEL TIER it runs on (this
// is where cost-effectiveness lives — cheap/fast models for triage, a premium
// model only on the hard step), and its own system prompt.
export type AgentRole = { role: string; model: string; prompt: string };

// The concrete "how you'd actually build it" spec for a recipe — the practical
// layer on top of the flow: how many agents, the real system prompt you'd
// paste in, which MCP connectors + built-in tools it calls, the guardrails that
// keep it safe, and how the final output is generated/delivered. Optional so a
// recipe can exist as a flow-only sketch, but every flagship recipe carries it.
export type RecipeBuild = {
  agents: number;
  agentsNote?: string; // e.g. "1 analyst agent" or "2 agents: a triager + a writer"
  pattern?: string; // orchestration pattern, e.g. "Supervisor → specialists" (multi-agent recipes)
  prompt: string; // the primary/supervisor agent's system prompt, verbatim-ready
  agentRoles?: AgentRole[]; // per-agent breakdown for multi-agent recipes (role + model tier + prompt)
  mcp: string[]; // MCP servers / connectors the workflow calls
  tools: string[]; // built-in agent tools used (web_search, http_request, knowledge_search…)
  guardrails: string[]; // concrete guardrail rules enforced
  cost?: string; // cost-effectiveness strategy (model tiering, cost routing, budget caps)
  output: string; // how the final output is generated + where it's delivered
};

export type Recipe = {
  id: string;
  title: string;
  scenario: string;
  triggerLabel: string;
  steps: RecipeStep[];
  connectors: string[];
  outcome: string;
  impact: string;
  build?: RecipeBuild;
};

export type Industry = {
  id: string;
  name: string;
  icon: LucideIcon;
  tagline: string;
  featured?: boolean;
  recipes: Recipe[];
};
