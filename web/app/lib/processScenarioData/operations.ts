// Process Theater scenarios for the "Operations" audience. Operations processes: customer support and employee onboarding.
// Assembled, in display order, by ../processScenarios.ts (see its honesty rules).
import { designPrompt, type ProcessScenario } from "./types";

export const supportScenario: ProcessScenario = {
  id: "support",
  audience: "Operations",
  title: "Support escalations across every channel",
  pitch:
    "One queue for WhatsApp, email, Slack and voice: answered from your knowledge, escalated with context, followed up until closed.",
  span: "Hours to days per ticket",
  needs: "Your channels, help-centre documents, ticketing API",
  steps: [
    {
      node: "trigger",
      label: "Message on any channel",
      tech: "Multi-trigger graph: one Trigger node per inbound channel",
      clock: "T+0s",
    },
    {
      node: "knowledge",
      label: "Ground the answer",
      tech: "Retrieval over your help centre, with citations",
      clock: "T+2s",
    },
    {
      node: "agent",
      label: "Answer or classify",
      tech: "Agent replies, or labels severity and product area with guardrails on",
      clock: "T+5s",
    },
    {
      node: "condition",
      label: "Needs a human?",
      tech: "Condition node on severity, sentiment and confidence",
      clock: "T+5s",
    },
    {
      node: "http",
      label: "Open the ticket",
      tech: "HTTP node creates the ticket with the full transcript attached",
      clock: "T+6s",
    },
    {
      node: "loop",
      label: "Follow up until closed",
      tech: "Loop node checks ticket state and nudges owner and customer",
      clock: "T+1 to 3 days",
      waits: true,
    },
    {
      node: "output",
      label: "Close the loop",
      tech: "Confirms resolution with the customer on their original channel",
      clock: "T+3 days",
    },
  ],
  agents: [
    {
      name: "Front-line Answerer",
      role: "Replies on whatever channel the message came in on, grounded in your help centre.",
      tools: ["Help centre", "WhatsApp / email / Slack / voice"],
    },
    {
      name: "Triage & Escalation",
      role: "Labels severity and product area; hands off with the full transcript.",
      tools: ["Ticketing API"],
    },
    {
      name: "Follow-up Tracker",
      role: "Loops on ticket state and nudges owner and customer until closed.",
      tools: ["Ticketing API", "WhatsApp / email / Slack / voice"],
    },
  ],
  connectors: [
    {
      name: "WhatsApp / email / Slack / voice",
      kind: "channel",
      detail: "One Trigger node per inbound channel — all land in the same run.",
    },
    { name: "Help centre", kind: "builtin", detail: "Knowledge retrieval with citations over your documents." },
    { name: "Ticketing API", kind: "http", detail: "HTTP node opens and updates tickets with context attached." },
    {
      name: "Your helpdesk",
      kind: "mcp",
      detail: "Optional: attach a Zendesk/Freshdesk MCP server instead of raw HTTP.",
    },
  ],
  prompt: designPrompt(
    "customer messages arrive on WhatsApp, email, Slack and voice; answer from the help centre with citations, classify and escalate hard ones to a ticket with context, follow up until closed, and confirm resolution"
  ),
};

export const onboardingScenario: ProcessScenario = {
  id: "onboarding",
  audience: "Operations",
  title: "Employee onboarding, day 0 to day 30",
  pitch:
    "One run per new hire that lives for a month: accounts, paperwork, training nudges, check-ins, each at the right moment.",
  span: "Runs for 30 days",
  needs: "HR system API, email, Slack or WhatsApp",
  steps: [
    {
      node: "trigger",
      label: "Offer accepted",
      tech: "Your HR system calls the workflow run API as a background run",
      clock: "Day 0",
    },
    {
      node: "parallel",
      label: "Accounts and equipment",
      tech: "Parallel branches raise IT, payroll and access requests",
      clock: "Day 0",
    },
    {
      node: "agent",
      label: "Answer their questions",
      tech: "Agent on WhatsApp or email, grounded in your policy documents",
      clock: "Any time",
    },
    {
      node: "approval",
      label: "Manager confirms access",
      tech: "Approval node before sensitive systems are granted",
      clock: "Day 1",
      waits: true,
    },
    {
      node: "loop",
      label: "Weekly check-ins",
      tech: "Loop node sends a check-in, reads the reply, flags risk to the manager",
      clock: "Day 7, 14, 21",
      waits: true,
    },
    {
      node: "output",
      label: "30-day summary",
      tech: "Summarises what was completed and what is still open",
      clock: "Day 30",
    },
  ],
  agents: [
    {
      name: "Onboarding Coordinator",
      role: "Runs the month-long run: accounts, equipment, approvals, check-ins.",
      tools: ["HR system API"],
    },
    {
      name: "Policy Q&A",
      role: "Answers the new hire's questions on WhatsApp or email, grounded in your policies.",
      tools: ["Policy documents", "Email / Slack / WhatsApp"],
    },
    {
      name: "Check-in Nudger",
      role: "Weekly check-ins that read the reply and flag risk to the manager.",
      tools: ["Email / Slack / WhatsApp"],
    },
  ],
  connectors: [
    {
      name: "HR system API",
      kind: "http",
      detail: "Offer accepted → HR calls the run API; accounts raised over HTTP.",
    },
    {
      name: "Email / Slack / WhatsApp",
      kind: "channel",
      detail: "The hire talks to the agent on whichever channel you connect.",
    },
    { name: "Policy documents", kind: "builtin", detail: "Upload your handbook; retrieval answers with citations." },
    {
      name: "Identity provider",
      kind: "custom",
      detail: "Optional: a small MCP tool — create_account(hire), grant_access(...).",
    },
  ],
  prompt: designPrompt(
    "a new employee joins; create accounts and equipment requests in parallel, answer their policy questions, get manager approval for sensitive access, run weekly check-ins for a month, then summarise"
  ),
};
