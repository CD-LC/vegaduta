// Static guide copy for the workflow recipes page (WorkflowRecipesClient.tsx):
// design principles, the pattern picker and the node legend.

import { Coins, BookOpen, UserCheck, Gauge, Lock, RefreshCw, Activity, Layers, Shuffle, Languages } from "lucide-react";
import type { LucideIcon } from "lucide-react";
import { NODE_META, type NodeKind } from "./recipes-types";

// Cross-cutting enterprise design principles — the "why" behind every recipe.
// These are the tenets a reviewer would hold any agentic workflow to: grounded,
// human-governed on consequential actions, cost-tiered, budgeted, least-
// privilege, idempotent, observable, composable, resilient and channel-native.
export const PRINCIPLES: { icon: LucideIcon; title: string; body: string }[] = [
  {
    icon: BookOpen,
    title: "Ground, don't guess",
    body: "Answers come from the Knowledge base with citations. Low confidence escalates or defers to a human — a confident wrong answer is the failure mode to design out.",
  },
  {
    icon: UserCheck,
    title: "Humans own consequential calls",
    body: "Money, safety, legal, clinical, credit and data-changing actions pass an Approval node. Agents propose and prepare; a person decides.",
  },
  {
    icon: Coins,
    title: "Tier your models",
    body: "Cheap/fast models by default, premium only on demand. The agent that runs most often must be the cheapest — that single choice dominates cost at scale (cost routing).",
  },
  {
    icon: Gauge,
    title: "Budget every agent",
    body: "Per-agent tool-call and token caps, plus bounded loops, make cost predictable and stop a runaway before it starts.",
  },
  {
    icon: Lock,
    title: "Least privilege & separation of duties",
    body: "Each agent is scoped to its own tools and Knowledge. Routers route, specialists act, critics critique — no agent holds more power than its job needs.",
  },
  {
    icon: RefreshCw,
    title: "Idempotent & safe to retry",
    body: "Keyed by a stable id (payment, order, item) so a re-run or a duplicate webhook never double-charges, double-sends or double-counts.",
  },
  {
    icon: Activity,
    title: "Observable & audited",
    body: "Every routing decision, approval, model tier and tool call is logged — so you can prove what happened and tune cost against real data.",
  },
  {
    icon: Layers,
    title: "Compose, don't copy",
    body: "Shared logic (KYC, PII redaction, payment verification) lives in one versioned sub-workflow every workflow reuses — fix it once, everywhere inherits it.",
  },
  {
    icon: Shuffle,
    title: "Fail gracefully",
    body: "Fallback provider/model on an outage; degrade to a human, never to a wrong answer. Resilience is a design choice, not an afterthought.",
  },
  {
    icon: Languages,
    title: "Meet users where they are",
    body: "WhatsApp/voice/email on the channel they already use, in their own language (Sarvam covers 22+; ElevenLabs speaks back) — reach beats a portal nobody opens.",
  },
];

// A decision reference: match the situation to the orchestration pattern. Keeps
// teams from reaching for a 5-agent design when one agent will do, or one
// overloaded agent when the job genuinely needs routing/parallelism.
export const PATTERN_GUIDE: { when: string; use: string }[] = [
  { when: "One clear task, one domain", use: "Single agent — Trigger → Agent → Output" },
  { when: "Many domains; each message belongs to one", use: "Supervisor → specialists (route-to-one)" },
  { when: "Gather from many sources, then decide", use: "Parallel fan-out → aggregator (+ guardrail agent)" },
  { when: "Output must clear a quality / accuracy bar", use: "Generator ↔ critic (reflection, round-capped)" },
  { when: "Thousands of items — too big for one context", use: "Map-reduce — Loop/Parallel (map) → reduce" },
  { when: "The same step is reused across workflows", use: "Sub-workflow library (build once, govern centrally)" },
  { when: "Mixed difficulty and cost-sensitive", use: "Cost-tiered escalation — cheap → premium on demand" },
  {
    when: "Any money / legal / clinical / data-changing action",
    use: "Add an Approval node — always, regardless of pattern",
  },
];
// The legend renders NODE_META in its declared order so the most fundamental
// blocks (Trigger → Agent → … → Output) read left-to-right like a sentence.
export const LEGEND_ORDER = Object.keys(NODE_META) as NodeKind[];

// One-line explanation per node type — surfaced on hover/focus of each legend
// chip so the building blocks are self-documenting instead of just labelled.
export const NODE_DESC: Record<NodeKind, string> = {
  trigger: "Starts the workflow — a webhook, WhatsApp message, SMS or inbound call.",
  agent: "An LLM agent with its own prompt, model tier, tools and Knowledge scope.",
  knowledge: "Grounds answers in your documents with citations — no guessing.",
  condition: "Branches the run on a rule — route, filter or gate the next step.",
  approval: "Human-in-the-loop pause: a person signs off before anything consequential runs.",
  output: "Delivers the result on a real channel — WhatsApp, SMS, voice, email, Slack…",
  tool: "Calls an external system through an MCP connector or an HTTP request.",
  voice: "Speaks the reply aloud — ElevenLabs, or Sarvam for 22+ Indian languages.",
  device: "Controls a physical / IoT device (e.g. Home Assistant) as a workflow step.",
  loop: "Repeats a step over a batch or until a condition — with a hard round cap.",
  code: "Runs a deterministic code step for parsing, math or formatting.",
  parallel: "Fans work out to run simultaneously, then aggregates the results.",
  supervisor: "Routes each request to exactly one specialist — it never answers itself.",
  subworkflow: "Invokes a shared, versioned workflow module — build once, reuse everywhere.",
};
