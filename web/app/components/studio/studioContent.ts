// Copy and presets for the /try Agent Studio. Plain English on purpose: this
// page is the shop window, and every sentence here describes something the
// visitor can actually switch on and watch - nothing aspirational.
import { SITE_URL } from "../../lib/site-url";

import type { WorkflowStep } from "./studioClient";

export type MissionPreset = {
  id: string;
  name: string;
  tagline: string;
  mission: string;
  tools: string[];
  reasoningDepth: "FAST" | "CAREFUL" | "RIGOROUS" | "PLANNED";
  sampleMissions: string[];
};

export const MISSION_PRESETS: MissionPreset[] = [
  {
    id: "scout",
    name: "Research scout",
    tagline: "Finds facts on the live web and cites where they came from.",
    mission:
      "You are a research scout. When given a question, search the web, open the most credible pages, and answer with a short brief: the answer first, then 2-4 bullet points of supporting facts, each with the URL it came from. Say clearly when sources disagree or when you could not verify something. Never invent a source.",
    tools: ["web_search", "fetch_web_page", "remember_fact", "recall_related_facts"],
    reasoningDepth: "CAREFUL",
    sampleMissions: [
      "What did the RBI announce about UPI transaction limits most recently? Cite sources.",
      "Find three Indian SaaS companies that raised funding this month and list the amounts with links.",
      `Read ${SITE_URL}/pricing and tell me which plan a 5-person shop should pick and why.`,
    ],
  },
  {
    id: "numbers",
    name: "Numbers checker",
    tagline: "Pulls live figures, then does the maths in real code.",
    mission:
      "You are a careful analyst. For any question with numbers in it, get the current figures from the web when they are not given, then do every calculation with the run_code tool rather than in your head. Show the formula, the inputs and the result. Round sensibly and state the date of any rate you used.",
    tools: ["web_search", "fetch_web_page", "run_code", "http_request"],
    reasoningDepth: "RIGOROUS",
    sampleMissions: [
      "What is 2.5 lakh rupees in US dollars at today's rate, and what would it have been a year ago?",
      "A shop sells 340 units a day at 85 rupees each with a 22% margin. Monthly profit? Show the working.",
      "Compare the GST on a 12,000-rupee phone versus a 12,000-rupee restaurant bill.",
    ],
  },
  {
    id: "support",
    name: "Support triage",
    tagline: "Reads a customer message, decides what it is, drafts the reply.",
    mission:
      "You handle inbound customer messages for a small Indian business. For each message: classify it as ORDER, COMPLAINT, QUESTION, REFUND or SPAM; rate urgency low/medium/high; then draft a warm, short reply in the customer's own language. Put the classification on the first line like 'ORDER · urgency: high'. Never promise refunds or dates you cannot know - offer to confirm instead.",
    tools: ["remember_fact", "recall_related_facts"],
    reasoningDepth: "FAST",
    sampleMissions: [
      "Bhaiya mera order 3 din se nahi aaya, tracking bhi update nahi ho raha. Kya hua?",
      "Hi, do you deliver to Whitefield and what's the minimum order?",
      "The saree I received has a tear near the border. I want my money back.",
    ],
  },
  {
    id: "writer",
    name: "Content drafter",
    tagline: "Researches a topic first, then writes something you could publish.",
    mission:
      "You write marketing copy for small businesses. Before writing, look up anything factual (prices, dates, competitors) on the web so nothing is made up. Then produce the piece asked for - a post, a product description, an email - in a clear, warm voice with one concrete call to action. Keep it tight: no filler, no clichés, no 'in today's fast-paced world'.",
    tools: ["web_search", "fetch_web_page"],
    reasoningDepth: "CAREFUL",
    sampleMissions: [
      "Write a 3-line WhatsApp broadcast for a Diwali offer at a Bengaluru bakery: 15% off on orders above 999 rupees this week.",
      "Draft a LinkedIn post announcing that our clinic now books appointments over WhatsApp.",
      "Describe a handloom cotton saree for an online listing - 80 words, two colour options.",
    ],
  },
  {
    id: "api",
    name: "API caller",
    tagline: "Talks to public APIs and turns raw JSON into an answer.",
    mission:
      "You are an integration agent. When a task can be answered from a public API, call it with the http_request tool and read the JSON yourself rather than guessing. Explain in one line which endpoint you called. If an API needs a key you do not have, say so and suggest the closest public alternative.",
    tools: ["http_request", "fetch_web_page", "run_code"],
    reasoningDepth: "CAREFUL",
    sampleMissions: [
      "Call https://api.exchangerate-api.com/v4/latest/USD and tell me the INR, EUR and AED rates.",
      "Use https://api.open-meteo.com to get today's temperature in Bengaluru (12.97, 77.59) and say whether to carry an umbrella.",
      "Fetch https://api.github.com/repos/vercel/next.js and summarise the repo in two lines with its star count.",
    ],
  },
  {
    id: "legacy",
    name: "Legacy code archaeologist",
    tagline: "Explains COBOL, JCL, ABAP or PL/SQL you paste in, then rewrites it and proves the rewrite in code.",
    mission:
      "You are a modernization engineer. When given legacy code - COBOL, JCL, ABAP, PL/SQL, VB6, shell - first explain what it does in plain English, including every business rule hiding in a condition or a magic number. Then write the modern equivalent the person asks for, and when the target is Python or JavaScript, run it with the run_code tool on a few sample inputs to show the behaviour matches, especially for packed decimals, rounding and date edge cases. Never change a business rule silently; list every open question about code you cannot see.",
    tools: ["run_code", "fetch_web_page", "remember_fact", "recall_related_facts"],
    reasoningDepth: "RIGOROUS",
    sampleMissions: [
      "Explain this COBOL and rewrite it in Java: 05 WS-AMT PIC S9(7)V99 COMP-3. COMPUTE WS-TOTAL ROUNDED = WS-AMT * WS-RATE / 100.",
      "This JCL step abended with SB37 writing to PAYROLL.OUT.MONTHLY. What happened and what do I change before the rerun?",
      "Rewrite this ABAP so it passes a clean-core check: LOOP AT lt_orders INTO ls_order. SELECT SINGLE name FROM kna1 INTO ls_order-name WHERE kunnr = ls_order-kunnr. ENDLOOP.",
    ],
  },
];

export const REASONING_DEPTHS: { id: string; label: string; blurb: string }[] = [
  { id: "FAST", label: "Fast", blurb: "Answers in one pass. Cheapest, best for simple replies." },
  { id: "CAREFUL", label: "Careful", blurb: "Checks its own answer once before replying." },
  { id: "RIGOROUS", label: "Rigorous", blurb: "Plans, works, then reviews. Slower, noticeably better on hard tasks." },
  { id: "PLANNED", label: "Planned", blurb: "Writes an explicit plan first and follows it step by step." },
];

export const AUTONOMY_TIERS: { id: string; label: string; blurb: string }[] = [
  { id: "SUPERVISED", label: "Supervised", blurb: "Every tool call waits in an approvals inbox for a human." },
  {
    id: "SEMI_AUTONOMOUS",
    label: "Semi-autonomous",
    blurb: "Acts freely on safe tools; sensitive actions (sending, paying) wait for approval.",
  },
  {
    id: "FULLY_AUTONOMOUS",
    label: "Autonomous",
    blurb: "Acts without asking, with a higher step budget. For jobs you already trust.",
  },
];

export const GUARDRAILS: { id: string; label: string; blurb: string }[] = [
  { id: "profanity", label: "Profanity", blurb: "Blocks offensive language in messages and replies." },
  {
    id: "jailbreak",
    label: "Jailbreak attempts",
    blurb: "Stops the usual tricks for talking an agent out of its mission.",
  },
  { id: "pii", label: "Personal data", blurb: "Keeps emails, phone numbers and card numbers out of replies." },
  { id: "policy_violation", label: "Policy violations", blurb: "Refuses violence, illegal activity and hate speech." },
  {
    id: "toxicity",
    label: "Toxic tone",
    blurb: "A second model judges hostile or abusive replies before they go out.",
  },
];

/** A chain composed from the visitor's OWN agents - their use case, not a canned pattern. */
export type StarterChain = {
  name: string;
  steps: WorkflowStep[];
  /** Label for the trigger's input box, e.g. "The job". */
  inputLabel: string;
};

const CHAIN_MAX_AGENTS = 3;

/**
 * Chains the visitor's own agents, in the order they built them: the job arrives, each agent takes its turn on the
 * previous one's output, a human approves, the result is delivered. One agent gives a one-agent chain (the use case
 * itself, with the approval gate that makes it safe); several give a hand-off. Returns null when they have no agent yet.
 * Pure - no network, no model - so the Chain stage is never an empty canvas and never somebody else's example.
 */
export function chainFromAgents(agents: { id: string; name: string; mine: boolean }[]): StarterChain | null {
  const own = agents.filter((a) => a.mine).slice(0, CHAIN_MAX_AGENTS);
  if (own.length === 0) return null;
  const steps: WorkflowStep[] = [
    { id: "t", type: "trigger", label: "The job arrives", config: { inputLabel: "The job" } },
    ...own.map((a, i): WorkflowStep => ({
      id: `a${i + 1}`,
      type: "agent",
      label: a.name,
      config: {
        agentId: a.id,
        instruction: i === 0 ? "{{input}}" : "Carry on from here. The previous agent produced this:\n\n{{previous}}",
      },
    })),
    {
      id: "approve",
      type: "approval",
      label: "You check it before it goes out",
      config: { message: "Approve this result?" },
    },
    { id: "out", type: "output", label: "Result", config: {} },
  ];
  const name = own.length === 1 ? `${own[0].name} - with your sign-off` : own.map((a) => a.name).join(" → ");
  return { name: name.slice(0, 120), steps, inputLabel: "The job" };
}

export const STEP_TYPES: { type: WorkflowStep["type"]; label: string; blurb: string }[] = [
  { type: "agent", label: "Agent", blurb: "One of your agents does a job on the previous output." },
  { type: "condition", label: "Condition", blurb: "Continue only if the previous output contains a word." },
  {
    type: "approval",
    label: "Approval",
    blurb: "Pause until you approve or reject. A real pause, not a picture of one.",
  },
  {
    type: "output",
    label: "Output",
    blurb: "Deliver the result. On a real account: email, WhatsApp, Telegram, Drive.",
  },
];

/** `mcp__demo-1a2b3c4d-github__list_repos` -> { server: "github", tool: "list repos" } for a studio connector tool, else null. */
export function parseConnectorTool(name: string): { server: string; tool: string } | null {
  const m = /^mcp__demo-[0-9a-f]{8}-(.+?)__(.+)$/.exec(name);
  return m ? { server: m[1], tool: m[2].replace(/_/g, " ") } : null;
}

export function connectorToolLabel(name: string): string {
  return parseConnectorTool(name)?.tool ?? name;
}

export function toolLabel(name: string): string {
  const connector = parseConnectorTool(name);
  if (connector) return `${connector.server}: ${connector.tool}`;
  switch (name) {
    case "web_search":
      return "Search the web";
    case "fetch_web_page":
      return "Read a web page";
    case "http_request":
      return "Call an API";
    case "run_code":
      return "Run code";
    case "remember_fact":
      return "Remember";
    case "recall_related_facts":
      return "Recall";
    case "search_knowledge_base":
      return "Search knowledge";
    default:
      return name.replace(/_/g, " ");
  }
}

export function newStepId(): string {
  return `s${Math.random().toString(36).slice(2, 8)}`;
}
