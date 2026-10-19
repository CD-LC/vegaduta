// Showtime - the full-screen trailer and its live twin.
//
// ONE use case, told end to end: a farmer's phone app, "Mandi Buddy". She types a
// crop, taps "Best day to sell?", and an agent searches the live web, reads the
// price page, does the maths in code, and asks HER before anything is sent. The
// same six moves a visitor makes in the real studio: design the app, find tools
// in the registry, attach an agent, wire a workflow, run it, put it on a phone.
//
// The trailer is a pure function of time: every scene renders from a progress
// value p (0..1) inside its act, so pause, scrub, speed and jump-to-act all just
// work. It is a SCRIPT and is labelled as one (the sample run says "sample"); the
// live mode next to it drives the real studio backend with the same shape.

import { PLATFORM_FACTS } from "../../../lib/platform-facts";

export const clamp01 = (x: number) => Math.min(1, Math.max(0, x));
/** Progress of p between a and b, clamped 0..1. */
export const seg = (p: number, a: number, b: number) => clamp01((p - a) / (b - a));
export const ease = (t: number) => (t < 0.5 ? 2 * t * t : 1 - Math.pow(-2 * t + 2, 2) / 2);
export const lerp = (a: number, b: number, t: number) => a + (b - a) * t;
export const typed = (s: string, t: number) => s.slice(0, Math.floor(s.length * clamp01(t)));

export type ActId = "open" | "design" | "registry" | "agent" | "flow" | "run" | "ship" | "end";

export type Act = {
  id: ActId;
  n: number;
  title: string;
  kicker: string;
  /** One word for the chapter chips. */
  short: string;
  ms: number;
  /** Subtitle / voice-over. One or two short sentences. */
  caption: string;
  /** What is really happening, and where the visitor finds it. */
  explain: { feature: string; where: string }[];
};

export const ACTS: Act[] = [
  {
    id: "open", n: 0, title: "One afternoon. One phone.", kicker: "The trailer", short: "Open", ms: 7000,
    caption: "Ramesh grows tomatoes. He needs to know one thing: which day to sell. Watch what happens when that job gets an agent.",
    explain: [{ feature: "The use case", where: "A real job: live prices in, a decision out, a human in the loop." }],
  },
  {
    id: "design", n: 1, title: "Design the app", kicker: "Act 1", short: "App", ms: 11000,
    caption: "First, the app. A header, a crop field, one button. No code - blocks dropped onto a phone.",
    explain: [
      { feature: "App studio", where: "/build - phone apps you design and publish" },
      { feature: "Runs anywhere", where: "Installable on a phone, works offline once opened" },
    ],
  },
  {
    id: "registry", n: 2, title: "Open the tool registry", kicker: "Act 2", short: "Tools", ms: 13000,
    caption: "Now the hands. The platform's registry lists every tool already switched on. Search 'web', attach it.",
    explain: [
      { feature: "Built-in tools", where: "40+ ready to attach: web search, page reading, code, APIs, memory, documents" },
      { feature: "MCP registry", where: "Search published MCP servers; connect them in a workspace" },
    ],
  },
  {
    id: "agent", n: 3, title: "Give the button a brain", kicker: "Act 3", short: "Agent", ms: 10000,
    caption: "An agent with a mission, three tools and a careful mind - wired to the button.",
    explain: [
      { feature: "Agent", where: "Mission, tools, thinking depth and guardrails - Build stage" },
      { feature: "Binding", where: "Any agent can sit behind any button, form or schedule" },
    ],
  },
  {
    id: "flow", n: 4, title: "Put a human in the loop", kicker: "Act 4", short: "Workflow", ms: 12000,
    caption: "The workflow: tap, agent works, Ramesh approves, the answer lands on his phone. Nothing goes out without his yes.",
    explain: [
      { feature: "Workflow builder", where: `${PLATFORM_FACTS.workflowNodeTypes} node types in a workspace; approval steps really pause the run` },
      { feature: "Audit trail", where: "Who approved what, and when - logged" },
    ],
  },
  {
    id: "run", n: 5, title: "Tap. Watch it work.", kicker: "Act 5", short: "Run", ms: 17000,
    caption: "He taps. The agent searches the live web, reads the price page and checks the maths in real code. Every step is visible.",
    explain: [
      { feature: "Live trace", where: "Every search, page and line of code streams on screen - Run stage" },
      { feature: "Real tools", where: "Web search, page fetch and code execution run for real in the sandbox" },
    ],
  },
  {
    id: "ship", n: 6, title: "Scan. It's in his pocket.", kicker: "Act 6", short: "Ship", ms: 9000,
    caption: "One link, one QR code. On a phone in seconds - or on Telegram, WhatsApp, the Android app.",
    explain: [
      { feature: "Share link + QR", where: "Deploy stage - live in the sandbox" },
      { feature: "9 channels", where: "WhatsApp, Telegram, Email free; Slack, Discord, Instagram, Messenger, X, voice in a workspace" },
    ],
  },
  {
    id: "end", n: 7, title: "Now it's your job.", kicker: "Your turn", short: "Your turn", ms: 7000,
    caption: "That was a script. The studio is real. Pick any job - it takes minutes.",
    explain: [{ feature: "Your turn", where: "Build it live right here, no signup" }],
  },
];

export const TOTAL_MS = ACTS.reduce((n, act) => n + act.ms, 0);
export const ACT_STARTS: number[] = ACTS.reduce<number[]>((acc, _a, i) => {
  acc.push(i === 0 ? 0 : acc[i - 1] + ACTS[i - 1].ms);
  return acc;
}, []);

export function locate(t: number): { index: number; p: number } {
  const tt = Math.min(Math.max(t, 0), TOTAL_MS - 1);
  let index = 0;
  for (let i = 0; i < ACTS.length; i++) if (tt >= ACT_STARTS[i]) index = i;
  return { index, p: (tt - ACT_STARTS[index]) / ACTS[index].ms };
}

// ---- the app and the agent ----
export const APP = {
  title: "Mandi Buddy",
  tagline: "Ask. Decide. Sell.",
  crop: "Tomato",
  button: "Best day to sell?",
};

export const AGENT = {
  name: "Mandi analyst",
  mission:
    "You advise a small farmer on when to sell. Given a crop, search the live web for this week's mandi prices near them, read the most relevant price page, then compute in code whether prices are rising or falling and name the best day to sell. Show the numbers you used. Say plainly when data is thin.",
  tools: ["web_search", "fetch_web_page", "run_code"],
};

export type ToolTile = { id: string; label: string; blurb: string; kind: "builtin" | "mcp" };

/** The trailer's registry wall. Built-ins mirror the palette's real names; MCP tiles are generic categories, not named products. */
export const REGISTRY_TILES: ToolTile[] = [
  { id: "fetch_web_page", label: "Read a web page", blurb: "Opens a URL and reads it.", kind: "builtin" },
  { id: "web_search", label: "Search the web", blurb: "Live results, cited.", kind: "builtin" },
  { id: "run_code", label: "Run code", blurb: "Exact maths, not guesses.", kind: "builtin" },
  { id: "http_request", label: "Call any API", blurb: "GET and POST to services.", kind: "builtin" },
  { id: "search_knowledge", label: "Search your documents", blurb: "Answers from your pages.", kind: "builtin" },
  { id: "remember_fact", label: "Remember", blurb: "Keeps facts between runs.", kind: "builtin" },
  { id: "mcp_calendar", label: "Calendar", blurb: "From the MCP registry.", kind: "mcp" },
  { id: "mcp_maps", label: "Maps and places", blurb: "From the MCP registry.", kind: "mcp" },
  { id: "mcp_code", label: "Code host", blurb: "From the MCP registry.", kind: "mcp" },
];

/** The scripted trace of the sample run. Clearly a sample - no real figures are claimed. */
export const SAMPLE_TRACE = [
  { tool: "web_search", label: "Search the web", detail: "tomato mandi prices this week", result: "results from price boards and news" },
  { tool: "fetch_web_page", label: "Read a web page", detail: "the mandi price page", result: "seven daily prices read" },
  { tool: "run_code", label: "Run code", detail: "trend over the seven days, best day", result: "rising two days running - peak forecast Thursday" },
];

export const SAMPLE_ANSWER =
  "Hold until Thursday. Prices have risen two days running and the trend points to Thursday as the peak. Confidence: medium - one price page, so check the mandi board before loading the truck.";

export const FLOW_NODES = [
  { id: "tap", label: "Button tapped", sub: "Trigger", tone: "sky" },
  { id: "agent", label: "Mandi analyst", sub: "Agent · 3 tools", tone: "violet" },
  { id: "ok", label: "Ramesh approves", sub: "Approval", tone: "amber" },
  { id: "out", label: "Answer on his phone", sub: "Output", tone: "emerald" },
] as const;
