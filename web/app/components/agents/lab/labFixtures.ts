// Agent Lab - sample agent + transcript for the console-preview page and the
// panel tests. The step lists are shaped exactly as AgentPlayground builds
// them: only tool events carry a server id (AgentStepEventEmitter counts
// tool calls from 1); every other event gets its 1-based POSITION in the turn
// as a fallback id (normalizeServerStep(event, turnSteps.length + 1)). So ids
// collide across event types on purpose - run_started is 1 and so is the
// first tool call - and nothing may treat `id` alone as unique.
import type { LabAgent, LabTurn } from "./types";

export const FIXTURE_AGENT: LabAgent = {
  id: "agt_support_copilot",
  name: "Support copilot",
  // Three headed sections separated by blank lines, so the knockout
  // splitter has something to split.
  systemPrompt: [
    "# Role",
    "You are the support copilot for Acme Traders. Answer questions about orders, deliveries and refunds.",
    "",
    "# Tone",
    "Be brief and friendly. Use plain language and never blame the customer.",
    "",
    "# Escalation",
    "If a refund is over 5000 rupees, or the customer asks for a person, hand the conversation to the billing team.",
  ].join("\n"),
  instructions: null,
  temperature: 0.7,
  providerName: "PLATFORM",
  model: "gemini-2.5-flash",
  responseCachingEnabled: false,
  semanticResponseCachingEnabled: false,
};

export const FIXTURE_TURNS: LabTurn[] = [
  { role: "user", content: "Where is order A-10432, and can I get a refund if it is late?" },
  {
    role: "assistant",
    content:
      "Order A-10432 shipped on 22 September and is due on 25 September. I could not read your refund history just now, so I have passed the refund question to the billing team.",
    ref: "fixture-turn-1",
    steps: [
      { type: "run_started", id: 1, origin: "server" },
      { type: "plan", id: 2, message: "1. Look up the order. 2. Check refund eligibility. 3. Answer.", origin: "server" },
      { type: "tool_call", id: 1, tool: "orders_lookup", args: '{"orderId":"A-10432"}', origin: "server" },
      { type: "tool_call", id: 2, tool: "billing_specialist", args: '{"question":"refund eligibility for A-10432"}', origin: "server" },
      // Emitted while billing_specialist (tool call 2) is still open.
      { type: "handoff", id: 5, origin: "server" },
      { type: "tool_result", id: 1, tool: "orders_lookup", preview: '{"status":"SHIPPED","eta":"2026-09-25"}', ms: 412, origin: "server" },
      { type: "error", id: 2, tool: "billing_specialist", message: "Refund history is unavailable right now.", ms: 1980, origin: "server" },
      { type: "guardrail", id: 8, tool: "tools", message: "Removed an account number from a tool result.", origin: "server" },
      { type: "run_finished", id: 9, ms: 3120, origin: "server" },
    ],
    metrics: { elapsedMs: 3120, toolCalls: 2, promptTokens: 1840, completionTokens: 96, totalTokens: 1936, estimatedCostUsd: 0.0007 },
  },
  { role: "user", content: "Thanks. What are your support hours?" },
  {
    role: "assistant",
    content: "We are here Monday to Saturday, 9 am to 7 pm IST.",
    ref: "fixture-turn-2",
    steps: [
      { type: "run_started", id: 1, origin: "server" },
      { type: "run_finished", id: 2, ms: 640, origin: "server" },
    ],
    metrics: { elapsedMs: 640, toolCalls: 0, promptTokens: 1210, completionTokens: 18, totalTokens: 1228, estimatedCostUsd: 0.0003 },
  },
];
