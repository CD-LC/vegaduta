// Shared help-docs content for VegaDūta — one source of truth rendered on BOTH
// surfaces: the public /docs page (landing audience, no session) and the
// in-console /help/docs page (inside AppShell). Copy is inline English on
// purpose — same posture as the /help primer and the workflow-recipes guide
// (messages/en.json is a fragile mid-migration file owned elsewhere; new
// long-form content stays out of it).
//
// No-fiction rule (same as the landing): no invented platform metrics. Every
// capability described here exists in this repo today; pricing figures match
// the /billing page's tier matrix exactly. Plan-entitlement specifics are
// deliberately summarized with pointers to /billing and /features, which read
// the live numbers.
//
// Content sync 2026-10-03: added Agent Lab, Skills, teams, versions, Memory,
// the Quality page, workflow expressions/pinning/draft runs and App Studio;
// removed the Home Assistant connector bullet (do-not-claim list in
// lib/platform-facts.ts). Code behind each addition is listed in the header
// of app/platform/platform-data.ts.

import type { LucideIcon } from "lucide-react";
import { isBillingEnabled, filterBillingItems } from "../lib/billing-flag";
import {
  Rocket,
  LayoutDashboard,
  Bot,
  Zap,
  Workflow,
  BookOpen,
  Blocks,
  GitBranch,
  Radio,
  Eye,
  Sparkles,
  ShieldCheck,
  GraduationCap,
  LifeBuoy,
} from "lucide-react";

export type DocSection = {
  heading?: string;
  paras?: string[];
  bullets?: string[];
  steps?: string[];
};

export type DocLink = {
  label: string;
  href: string;
  external?: boolean;
};

export type DocTopic = {
  id: string;
  icon: LucideIcon;
  title: string;
  tagline: string;
  sections: DocSection[];
  /** In-console destinations — rendered only on the authenticated surface. */
  appLinks?: DocLink[];
};

export const DOCS_TITLE = "VegaDūta Help & Docs";
export const DOCS_SUBTITLE =
  "Everything the platform can do and how to use it — from your first agent to multi-step workflows, knowledge grounding, tools, channels, and plans.";

export const DOC_TOPICS: DocTopic[] = [
  {
    id: "getting-started",
    icon: Rocket,
    title: "Getting started",
    tagline: "From sign-up to a working agent in one sitting.",
    sections: [
      {
        paras: [
          "VegaDūta is an agentic-AI platform: you create AI agents that plan, use tools, work across messaging channels, and ask a human before high-stakes actions. You can start entirely free — the Free plan needs no card and runs on a platform-hosted model.",
        ],
      },
      {
        heading: "Your first ten minutes",
        steps: [
          "Create your account in the web console (email, Google, or GitHub sign-in). A short onboarding introduces the console the first time you sign in.",
          "Open Agents and create your first agent — give it a name and a system prompt describing its job.",
          "Talk to it right away in the built-in chat to check its behaviour.",
          "Ground it in your own material: upload documents under Knowledge and attach them to the agent.",
          "When it behaves the way you want, connect a channel (WhatsApp, Telegram) so it can talk to the outside world.",
        ],
      },
      {
        heading: "Where things live",
        bullets: [
          "Build — Agents, Workflows, Skills, Memory, App Studio, and Quick Task: the things you author.",
          "Resources — Knowledge, Tools, Code Intel, and Channels: what your agents draw on and reach through.",
          "Plans & Help — your plan's entitlements, billing (admins), and this guide.",
          "Administration — tenant-admin-only operations like channel management, billing, and the audit tail.",
        ],
      },
    ],
    appLinks: [
      { label: "Create an agent", href: "/agents" },
      { label: "Upload knowledge", href: "/knowledge" },
    ],
  },
  {
    id: "dashboard",
    icon: LayoutDashboard,
    title: "Dashboard — the Cognition Cockpit",
    tagline: "A live operational view of your workspace.",
    sections: [
      {
        paras: [
          "The home dashboard shows what your workspace is actually doing right now. Every figure on it is real telemetry — nothing is illustrative.",
        ],
      },
      {
        bullets: [
          "Telemetry tiles — tokens per minute, cost today (and per conversation), LLM calls over 24 h, and process uptime.",
          "Pending approvals — the single most actionable item: actions your agents are waiting on a human to approve, with a one-click path to review them.",
          "Latency and model routing — p50/p95/p99 response times and which models handled the last 24 hours of traffic.",
          "Channels live — a dot per connected channel showing its connection state.",
          "Live event tail — the same real audit stream the admin console reads, so you can watch actions as they happen.",
        ],
      },
    ],
  },
  {
    id: "agents",
    icon: Bot,
    title: "Agents",
    tagline: "Create, instruct, ground, and supervise your AI workers.",
    sections: [
      {
        paras: [
          "An agent is a configured AI worker: a model, a system prompt that defines its job, plus the knowledge, tools, and channels you attach to it. Where a chatbot only replies, an agent plans steps, calls tools, and carries a task to completion.",
        ],
      },
      {
        heading: "Creating and shaping an agent",
        bullets: [
          "System prompt — the most important control. Describe the agent's role, tone, boundaries, and what it should refuse or escalate.",
          "Model — Free-plan agents run on the platform-hosted model. Paid plans unlock choosing the provider and model per agent, including bringing your own provider API keys (BYOK); keys are stored encrypted.",
          "Knowledge — attach document collections so the agent answers from your material instead of general memory.",
          "Tools — give the agent capabilities beyond text: connected MCP tools let it call real systems.",
          "Channels — decide where the agent talks: in-console chat, WhatsApp, Telegram, and more.",
          "Skills — packaged instructions for one kind of task. The agent sees each skill's name and description and loads the full instructions only when a request matches. Fourteen are built in; a workspace admin can add more on the Skills page.",
          "Team — add other agents as members and the agent can hand them parts of a task, several at once when there are two or more. Delegation is one level deep, capped at 8 branches per call, with a 240-second limit per branch and one shared budget per run.",
        ],
      },
      {
        heading: "Testing and versions",
        bullets: [
          "Agent Lab — open it from the playground to see each turn as a run graph (plan, tool calls, guardrail decisions, reply), fork the conversation from any message, and test which sections of the system prompt matter.",
          "Versions — every saved change is a version. Label one staging or production, and restore any earlier one; a restore is saved as a new version so it can be undone.",
          "Quality — build an eval dataset, score runs with six LLM judges, read results with confidence intervals, and set a gate that a version must pass before it is promoted to production.",
          "Memory — the Memory page lists what your assistants remember about you. Every note and fact there can be edited or deleted.",
        ],
      },
      {
        heading: "Staying in control",
        paras: [
          "Guardrails keep agents inside the boundaries you set, and the approvals system pauses high-stakes actions until a human confirms them — pending approvals surface on the dashboard and in the admin console. Every action lands in the audit trail.",
        ],
      },
    ],
    appLinks: [
      { label: "Open Agents", href: "/agents" },
      { label: "Open Skills", href: "/skills" },
      { label: "Open Memory", href: "/memory" },
      { label: "Review approvals", href: "/admin/maintenance" },
    ],
  },
  {
    id: "quick-tasks",
    icon: Zap,
    title: "Quick Tasks",
    tagline: "One-off jobs without setting up a full agent.",
    sections: [
      {
        paras: [
          "Quick Task is the fastest path from \"I need this done\" to a result: describe a one-off job and run it immediately, without creating and configuring a persistent agent first. Use it for ad-hoc work — a summary, a draft, an extraction — and graduate to a full agent when the same job keeps coming back.",
        ],
      },
    ],
    appLinks: [{ label: "Run a quick task", href: "/quick-task" }],
  },
  {
    id: "workflows",
    icon: Workflow,
    title: "Workflows",
    tagline: "Orchestrate multi-step automations across agents and tools.",
    sections: [
      {
        paras: [
          "Workflows chain steps into a repeatable automation: route an incoming message, call an agent, branch on the result, notify a human. Runs are tracked with status, and recent runs appear on the dashboard.",
        ],
      },
      {
        heading: "Building and testing",
        bullets: [
          "35 node types, including agent, condition, loop, parallel, code, HTTP request, approval, human input, bulk email, and CRM lead.",
          "Expressions pass data between steps: a field can read the trigger payload or an earlier node's output, with a preview as you type and a lint panel for problems.",
          "Pin a node's output and draft runs use the saved data instead of running that node. Live runs from webhooks, schedules, channels, and share links never use pins.",
          "Editors run the draft; live triggers run the version labelled production. Earlier versions can be restored.",
          "A run can pause for an approval or a human answer and continue from that node; long runs can be started in the background and polled.",
        ],
      },
      {
        heading: "Execution limits are per workflow",
        bullets: [
          "Retry attempts on a failed step: 0–5, set per workflow. Use 0 for a step that must never run twice.",
          "A maximum step count per run and a per-step timeout, configured on the same panel.",
          "A run rejected for exceeding your plan quota is not retried — the next attempt would fail identically.",
        ],
      },
      {
        heading: "Learn by example — industry recipes",
        paras: [
          "The console ships a large library of industry workflow recipes — real, buildable automations for dozens of sectors (retail, healthcare, finance, logistics, creators, and many more), each mapped to the platform's workflow nodes with suggested prompts, agents, tools, and guardrails. It is the fastest way to see what a production workflow looks like before building your own.",
        ],
      },
    ],
    appLinks: [
      { label: "Open Workflows", href: "/workflows" },
      { label: "Browse industry recipes", href: "/help/workflow-recipes" },
      { label: "Expressions and pinning guide", href: "/help/workflow-expressions" },
    ],
  },
  {
    id: "knowledge",
    icon: BookOpen,
    title: "Knowledge",
    tagline: "Ground agents in your own documents.",
    sections: [
      {
        paras: [
          "Upload your documents — PDFs, office files, text — and the platform's knowledge pipeline extracts, chunks, and embeds them so agents can search and cite your material when answering. This is retrieval-augmented generation (RAG): the agent answers from what you know, not just what the model remembers.",
        ],
      },
      {
        heading: "How to use it well",
        bullets: [
          "Organize uploads into collections per topic or per agent — attach only what an agent needs, which keeps retrieval sharp.",
          "Prefer source documents over exports of exports; cleaner input means better answers.",
          "Knowledge capacity varies by plan — see the plan comparison for your tier's allowance.",
        ],
      },
    ],
    appLinks: [{ label: "Open Knowledge", href: "/knowledge" }],
  },
  {
    id: "tools",
    icon: Blocks,
    title: "Tools & MCP",
    tagline: "Give agents real capabilities through the Model Context Protocol.",
    sections: [
      {
        paras: [
          "VegaDūta speaks MCP (Model Context Protocol) in both directions. Outbound, you connect external MCP servers as tools your agents can call — turning them from talkers into doers. Inbound, the MCP gateway exposes five curated tools to MCP-compatible clients using a scoped API key: list_my_agents, call_agent, list_my_workflows, call_workflow, and search_knowledge.",
        ],
      },
      {
        bullets: [
          "Browse the public MCP registry from the Tools screen and connect servers to your workspace.",
          "Some connectors are one click: Atlassian (Jira/Confluence) connects over OAuth with no API token to generate and no Atlassian app to register first.",
          "A built-in catalog offers 20 one-click entries for common MCP servers.",
          "Tool credentials are stored encrypted and redacted from logs.",
          "A registration can be edited or deleted later — change a URL, rotate a credential, or remove a server you no longer use.",
          "Connected tools become available to agents you grant them to — capability is opt-in per agent, not global.",
        ],
      },
    ],
    appLinks: [{ label: "Open Tools", href: "/tools" }],
  },
  {
    id: "app-studio",
    icon: Blocks,
    title: "App Studio",
    tagline: "Describe an app and get one you can preview and publish.",
    sections: [
      {
        paras: [
          "App Studio builds a multi-page app from a plain-language description and shows a live preview as it goes. You change the app by describing the change. Your existing agents and workflows can be wired in behind named actions, so the app needs no AI backend of its own.",
        ],
      },
      {
        bullets: [
          "Start from a description, a sketch or screenshot, a Figma frame (with your own Figma token), or an existing GitHub repository.",
          "When a generated change fails to build, the error is fed back for a bounded number of repair passes before the studio stops and shows it to you.",
          "Published apps are reached by a link and can have their own end-user sign-up and login, separate from console users.",
          "A project can be created as disposable: it archives itself after a set time unless you extend or keep it.",
          "Custom domains can be verified but do not serve apps yet.",
        ],
      },
    ],
    appLinks: [{ label: "Open App Studio", href: "/build" }],
  },
  {
    id: "code-intel",
    icon: GitBranch,
    title: "Code Intelligence",
    tagline: "Index a codebase and let agents answer questions about it.",
    sections: [
      {
        paras: [
          "Code Intelligence indexes source repositories so you — and your agents — can ask questions about the code itself: where something is implemented, how a subsystem fits together, what a change touches. It powers engineering workflows like onboarding to an unfamiliar codebase or grounding an agent in your team's actual implementation.",
        ],
      },
    ],
    appLinks: [{ label: "Open Code Intel", href: "/code-intel" }],
  },
  {
    id: "channels",
    icon: Radio,
    title: "Channels",
    tagline: "Put agents where your users already are.",
    sections: [
      {
        paras: [
          "Agents aren't limited to the console. Connect messaging channels and your agent answers incoming messages there directly — WhatsApp-first, which matters if your users live on it.",
        ],
      },
      {
        bullets: [
          "WhatsApp — connect through Meta's WhatsApp Cloud API (Embedded Signup) or Twilio from the Channels screen.",
          "Telegram — bridge a bot to your agents.",
          "Channel management lives in the admin console's Channels screen; connection state is visible there and on the dashboard.",
          "Channel availability varies by plan — check the plan comparison for what your tier includes.",
        ],
      },
    ],
    appLinks: [{ label: "Manage channels", href: "/admin/channels" }],
  },
  {
    id: "sight",
    icon: Eye,
    title: "VegaDūta Sight — let your agent see",
    tagline: "Point a camera or share your screen; the agent looks and responds.",
    sections: [
      {
        paras: [
          "Sight gives an agent live visual input alongside chat and voice: open your camera or share a screen from an agent conversation and the agent can describe, read, and reason about what it sees — an error screen, a product label, a shelf, a document in your hand.",
        ],
      },
      {
        heading: "Three ways to look",
        bullets: [
          "Snapshot — capture a single frame and ask about it, like attaching a photo without leaving the conversation. The day-one mode for show-and-ask questions.",
          "Watch — the agent keeps a low-frame-rate eye on the feed and speaks up when something meaningful changes, so you can give standing instructions like \"tell me when this finishes\".",
          "Live — a continuous real-time session where you and the agent look at the same thing together and talk about it as it changes.",
        ],
      },
      {
        heading: "Privacy posture",
        bullets: [
          "Frames are analyzed, never stored — only the resulting text (descriptions, answers, remembered facts) persists.",
          "Seeing starts only on your explicit action, with an always-visible on-air indicator and a hard stop.",
          "An on-device option can analyze frames locally in your browser, so images never leave your machine.",
        ],
      },
      {
        heading: "Plan availability",
        paras: [
          "Snapshots are available on every plan with a daily frame allowance; watch and live sessions are a paid feature. The plan comparison shows what your tier includes right now.",
        ],
      },
    ],
    appLinks: [
      { label: "Open Agents", href: "/agents" },
      { label: "See your plan's entitlements", href: "/features" },
    ],
  },
  {
    id: "plans",
    icon: Sparkles,
    title: "Plans & billing",
    tagline: "Start free, upgrade when the work grows.",
    sections: [
      {
        paras: [
          "Three tiers: Free ($0), Premium ($19/mo · ₹399/mo), and Unlimited ($79/mo · ₹1,599/mo). India gets ₹ pricing with UPI and card payments via Razorpay.",
        ],
      },
      {
        bullets: [
          "Free — build and run real agents on the platform-hosted model, no card required. Model/provider selection is locked on this tier.",
          "Premium — unlocks model choice and bring-your-own-key providers, plus higher limits.",
          "Unlimited — the highest limits for teams running agents in production.",
          "The Plans screen in the console shows your tenant's live entitlements — what your current plan actually includes right now; the public pricing page has the full comparison table.",
        ],
      },
      {
        paras: [
          "Billing management (invoices, upgrades) is a tenant-admin surface inside the console.",
        ],
      },
    ],
    appLinks: [
      { label: "See your plan's entitlements", href: "/features" },
      { label: "Pricing", href: "/billing" },
    ],
  },
  {
    id: "security",
    icon: ShieldCheck,
    title: "Security & trust",
    tagline: "Guardrails, approvals, audit, and tenant isolation.",
    sections: [
      {
        bullets: [
          "Sign-in is handled by a dedicated identity service (Keycloak) with Google and GitHub social login; idle sessions are logged out automatically.",
          "Each workspace (tenant) is isolated — your agents, knowledge, and keys are yours alone.",
          "Provider API keys and channel secrets are stored encrypted, and credentials are redacted from tool traffic.",
          "Guardrails constrain what agents may do; the approvals system routes high-stakes actions to a human before they execute.",
          "An audit trail records platform actions — the same stream you can watch live on the dashboard.",
          "Built-in tools classified as sensitive wait for a person's confirmation before they run, and per-user daily budgets cap spend.",
          "Workspace single sign-on and SCIM user provisioning are available for connecting your own identity provider. Test them with your provider before a rollout.",
          "The platform is open source under the Apache-2.0 license and can be self-hosted with Docker Compose.",
        ],
      },
    ],
  },
  {
    id: "academy",
    icon: GraduationCap,
    title: "VegaDūta Academy",
    tagline: "Learn agentic AI free, hands-on.",
    sections: [
      {
        paras: [
          "The Academy is the platform's free learning portal: more than 280 free lessons in a structured, hands-on curriculum that takes you from \"what is an agent?\" through building, grounding, connecting, and evaluating real agents — including a code-intelligence hands-on and a platform-architecture deep dive.",
          "Lessons ladder through tiers — Builder, Operator, Engineer, Architect — so you always know what to learn next. Everything you learn is practised on the real platform: you learn agentic AI by shipping an agent.",
        ],
      },
    ],
  },
  {
    id: "troubleshooting",
    icon: LifeBuoy,
    title: "Troubleshooting & FAQ",
    tagline: "Quick answers to the things people hit first.",
    sections: [
      {
        heading: "\"I can't change my agent's model.\"",
        paras: [
          "Model and provider selection is a paid-plan feature — Free-tier agents run on the platform-hosted model. The Plans screen shows what your tier unlocks and how to upgrade.",
        ],
      },
      {
        heading: "\"Knowledge upload or search is temporarily disabled.\"",
        paras: [
          "The knowledge service is briefly unavailable; the console gates uploads rather than accepting files it can't process. Try again shortly.",
        ],
      },
      {
        heading: "\"We'll be back\" screen",
        paras: [
          "The console can't reach the core service — usually a restart or maintenance window. The page recovers on its own once the service is back.",
        ],
      },
      {
        heading: "\"I was signed out.\"",
        paras: [
          "Idle sessions are ended automatically for security. Sign back in and you're where you left off.",
        ],
      },
      {
        heading: "Still stuck?",
        paras: [
          "The in-console Guide (Plans & Help → Guide) covers every screen in more depth, and the Academy's hands-on lessons walk through the same flows step by step.",
        ],
      },
    ],
  },
];

/** DOC_TOPICS as rendered. Billing off (lib/billing-flag.ts): the "Plans &
 *  billing" topic and every link to a plans/billing page drop out. */
export function visibleDocTopics(enabled = isBillingEnabled()): DocTopic[] {
  if (enabled) return DOC_TOPICS;
  return DOC_TOPICS.filter((topic) => topic.id !== "plans").map((topic) =>
    topic.appLinks ? { ...topic, appLinks: filterBillingItems(topic.appLinks, false) } : topic
  );
}
