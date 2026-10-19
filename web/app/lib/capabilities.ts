// The ONE catalog of what the platform does out of the box. Two surfaces read it
// and nothing else may restate it:
//   - /try            (public)    - CapabilityMap mode="public": tiles a signed-out
//                                   visitor can run live in the demo sandbox
//   - /build "Guided" (signed in) - CapabilityMap mode="console" + GuidedBuilder,
//                                   which turns a goal into a real agent/workflow
//
// Honesty rules (the landing's no-fiction rule applies here in full):
//   1. An entry exists only if the feature is LIVE and reachable from `href`.
//      Built-but-off features do not get a tile.
//   2. `need` is what it takes to RUN it, judged for a signed-out visitor:
//        "none"   - runs right now in the /try sandbox, no account, no key
//        "signin" - real, but needs an account (downloads, saved resources,
//                   credentials, channels). NEVER ask a signed-out visitor for a
//                   credential: an API key must not be typed into a public page
//                   or stored against an anonymous demo tenant.
//      Generated documents are "signin" on purpose: the agent can build one in
//      the sandbox, but /api/documentgen/documents is authenticated, so a
//      signed-out visitor could not download the result.
//   3. No numbers here. Counts live in platform-facts.ts.

export type CapabilityGroup = "build" | "create" | "connect" | "code" | "run" | "trust";

export type CapabilityRun =
  | { kind: "chat"; prompt: string }
  | { kind: "image"; prompt: string }
  | { kind: "magic" };

export type Capability = {
  id: string;
  group: CapabilityGroup;
  title: string;
  /** One line, outcome-first: what the person ends up with. */
  outcome: string;
  need: "none" | "signin";
  /** Present only when need === "none": how /try runs it live. */
  run?: CapabilityRun;
  /** Rough minutes a person spends doing this by hand. An ESTIMATE, always labelled as one in the UI. */
  manualMinutes?: number;
  /** Console screen where a signed-in user does this for real. */
  href: string;
  /** Guided-builder recipe this tile starts, when there is one. */
  recipe?: string;
};

export const CAPABILITY_GROUPS: { id: CapabilityGroup; label: string; blurb: string }[] = [
  { id: "build", label: "Build", blurb: "Make the thing that does the work." },
  { id: "create", label: "Create", blurb: "Have it produce real output." },
  { id: "connect", label: "Connect", blurb: "Plug it into your tools and your customers." },
  { id: "code", label: "Code", blurb: "Point it at software." },
  { id: "run", label: "Run", blurb: "Let it work without you watching." },
  { id: "trust", label: "Trust", blurb: "Stay in control of what it does." },
];

export const CAPABILITIES: Capability[] = [
  // ---- Build
  {
    id: "agent",
    group: "build",
    title: "AI agent from one sentence",
    outcome: "Describe a job; get an agent with instructions, a model and the right tools.",
    need: "none",
    run: { kind: "chat", prompt: "Introduce yourself: what are you set up to do for this business, and which tools can you use to do it?" },
    manualMinutes: 10,
    href: "/agents",
    recipe: "support",
  },
  {
    id: "workflow",
    group: "build",
    title: "Multi-step workflow",
    outcome: "Say the outcome; watch a workflow get planned, built and published.",
    need: "none",
    run: { kind: "magic" },
    manualMinutes: 60,
    href: "/workflows",
    recipe: "leadflow",
  },
  {
    id: "skills",
    group: "build",
    title: "Skills",
    outcome: "Packaged expertise an agent loads only when the task needs it.",
    need: "signin",
    href: "/skills",
  },
  {
    id: "knowledge",
    group: "build",
    title: "Knowledge from your documents",
    outcome: "Upload files; the agent answers from them and cites what it used.",
    need: "signin",
    href: "/knowledge",
    recipe: "knowledge",
  },
  {
    id: "templates",
    group: "build",
    title: "Ready-made templates",
    outcome: "Start from a working agent or workflow instead of a blank page.",
    need: "signin",
    href: "/templates",
  },

  // ---- Create
  {
    id: "plan",
    group: "create",
    title: "A plan you can act on",
    outcome: "A week-by-week launch plan with owners, not a wall of advice.",
    need: "none",
    run: { kind: "chat", prompt: "Write a 4-week launch plan for a new offer from this business. Week by week, with the owner of each task and what 'done' looks like. Keep it to one screen." },
    manualMinutes: 45,
    href: "/quick-task",
  },
  {
    id: "research",
    group: "create",
    title: "Research from a live web page",
    outcome: "Give it a URL; get the key points and what to do about them.",
    need: "none",
    run: { kind: "chat", prompt: "Read https://en.wikipedia.org/wiki/Customer_relationship_management and give me the 5 points that matter most for a small business, then 3 actions I could take this week." },
    manualMinutes: 30,
    href: "/quick-task",
    recipe: "research",
  },
  {
    id: "image",
    group: "create",
    title: "Images",
    outcome: "A marketing image generated from a one-line brief.",
    need: "none",
    run: { kind: "image", prompt: "A clean, bright promotional poster for this business's weekend offer, modern flat illustration style" },
    manualMinutes: 40,
    href: "/studio",
  },
  {
    id: "documents",
    group: "create",
    title: "Word, PDF, Excel and PowerPoint files",
    outcome: "A downloadable report, spreadsheet or slide deck, written and structured for you.",
    need: "signin",
    href: "/files",
    recipe: "documents",
  },
  {
    id: "video",
    group: "create",
    title: "Short video",
    outcome: "A short AI video from a prompt, using a video model you choose.",
    need: "signin",
    href: "/studio",
  },
  {
    id: "voice",
    group: "create",
    title: "Voice",
    outcome: "Talk to your agent and hear it answer.",
    need: "signin",
    href: "/agents",
  },

  // ---- Connect
  {
    id: "channels",
    group: "connect",
    title: "Customer channels",
    outcome: "Put the agent on WhatsApp, Telegram, email and more.",
    need: "signin",
    href: "/admin/channels",
    recipe: "support",
  },
  {
    id: "mcp",
    group: "connect",
    title: "Integrations (MCP)",
    outcome: "Pick a connector from the catalog, add your credentials, and the agent can use it.",
    need: "signin",
    href: "/tools",
  },
  {
    id: "openapi",
    group: "connect",
    title: "Any API as a tool",
    outcome: "Import an OpenAPI document; every operation becomes something the agent can call.",
    need: "signin",
    href: "/tools",
  },
  {
    id: "secrets",
    group: "connect",
    title: "Secrets vault",
    outcome: "Store keys once, encrypted; tools use them without ever showing them.",
    need: "signin",
    href: "/secrets",
  },

  // ---- Code
  {
    id: "codeintel",
    group: "code",
    title: "Ask your codebase",
    outcome: "Connect a repository and ask how it works, where things live, what a change touches.",
    need: "signin",
    href: "/code-intel",
  },
  {
    id: "localcode",
    group: "code",
    title: "Local code assistant",
    outcome: "Open a folder from this device and work on it in the browser; nothing is uploaded.",
    need: "none",
    href: "/code",
  },
  {
    id: "codetools",
    group: "code",
    title: "Your own code as a tool",
    outcome: "Write a small Python or Java program; the agent runs it in an isolated sandbox.",
    need: "signin",
    href: "/tools",
  },

  // ---- Run
  {
    id: "quicktask",
    group: "run",
    title: "One-off tasks",
    outcome: "Hand over a task and watch each step as it happens.",
    need: "signin",
    href: "/quick-task",
  },
  {
    id: "jobs",
    group: "run",
    title: "Background jobs and schedules",
    outcome: "Run the same job every morning without opening the app.",
    need: "signin",
    href: "/jobs",
    recipe: "digest",
  },
  {
    id: "team",
    group: "run",
    title: "Agents that delegate",
    outcome: "One agent hands a sub-question to a specialist and uses the answer.",
    need: "signin",
    href: "/agents",
  },

  // ---- Trust
  {
    id: "approvals",
    group: "trust",
    title: "Approval before action",
    outcome: "Risky tool calls wait for a person to say yes.",
    need: "signin",
    href: "/agents",
  },
  {
    id: "guardrails",
    group: "trust",
    title: "Guardrails",
    outcome: "Block what should never be said or sent, per agent.",
    need: "signin",
    href: "/agents",
  },
  {
    id: "edge",
    group: "trust",
    title: "On-device AI",
    outcome: "Run a model in this browser; the conversation never leaves the device.",
    // "signin": /edge is a console route (not in PUBLIC_ROUTES), so a signed-out
    // tile linking there would hit the auth gate. /try's own on-device fallback
    // is the public taste of this.
    need: "signin",
    href: "/edge",
  },
];

// ---------------------------------------------------------------------------
// Guided-builder recipes. A recipe is a PLAN the builder executes with the same
// API calls the console's own screens make (createAgent, createWorkflow,
// generateWorkflowGraph) - it never invents an endpoint. `connect` steps are
// guidance with a deep link, because credentials and channel linking belong to
// their own screens (and their own admin/edit-lock rules), not to a wizard.
// ---------------------------------------------------------------------------

export type RecipeConnectStep = {
  kind: "mcp" | "channel" | "knowledge" | "schedule";
  label: string;
  why: string;
  href: string;
  /** For kind "mcp": catalog entries whose name/description match are suggested. */
  mcpKeywords?: string[];
};

export type Recipe = {
  id: string;
  title: string;
  /** Words in a free-text goal that select this recipe. */
  keywords: string[];
  agent: { name: string; systemPrompt: (goal: string) => string; knowledgeBase: boolean };
  /** Omit for an agent-only recipe. `instruction` goes to generateWorkflowGraph. */
  workflow?: { name: string; description: string; instruction: (goal: string) => string };
  /** Built-in skills this agent will lean on - shown in the plan, nothing to attach (on by default). */
  skills: string[];
  connect: RecipeConnectStep[];
};

const goalLine = (goal: string) => (goal.trim() ? `\n\nWhat the owner asked for, in their words: "${goal.trim()}"` : "");

export const RECIPES: Recipe[] = [
  {
    id: "support",
    title: "Customer support agent",
    keywords: ["support", "customer", "whatsapp", "reply", "faq", "helpdesk", "enquir", "inquir", "chat"],
    agent: {
      name: "Support Agent",
      knowledgeBase: true,
      systemPrompt: (goal) =>
        "You are the customer support agent for this business. Answer from the knowledge base first and say so when you are not sure - never invent prices, policies or availability. Keep replies short enough to read on a phone. If the customer is upset, asks for a refund, or asks for something you cannot do, hand off to a human rather than improvising." + goalLine(goal),
    },
    skills: [],
    connect: [
      { kind: "knowledge", label: "Add your FAQs and policies", why: "So answers come from your documents, not guesses.", href: "/knowledge" },
      { kind: "channel", label: "Connect WhatsApp, Telegram or email", why: "So customers can actually reach the agent.", href: "/admin/channels" },
    ],
  },
  {
    id: "leadflow",
    title: "Lead follow-up workflow",
    keywords: ["lead", "sales", "follow", "crm", "prospect", "outreach", "pipeline", "workflow", "automate"],
    agent: {
      name: "Lead Qualifier",
      knowledgeBase: false,
      systemPrompt: (goal) =>
        "You qualify inbound leads for this business. From each enquiry extract: who they are, what they want, budget or size if stated, and urgency. Score the lead hot / warm / cold with one sentence of reasoning, then draft a short, specific follow-up message. Never promise pricing or delivery dates that were not given to you." + goalLine(goal),
    },
    workflow: {
      name: "Lead follow-up",
      description: "Qualify an inbound lead, draft the follow-up, and wait for approval before anything is sent.",
      instruction: (goal) =>
        "Build a workflow that takes an inbound lead enquiry as input, uses an agent step to qualify and score it, branches on hot versus not hot, drafts a follow-up message, and requires a human approval step before any message is sent." + goalLine(goal),
    },
    skills: [],
    connect: [
      { kind: "mcp", label: "Save leads to a sheet or CRM", why: "Only if you want qualified leads saved somewhere. Skip it and you still get every score and draft reply inside the platform.", href: "/tools", mcpKeywords: ["crm", "hubspot", "salesforce", "sheet", "notion", "airtable"] },
      { kind: "channel", label: "Start it from WhatsApp or email", why: "Lets new enquiries trigger this on their own. Until then you can paste an enquiry in to try it.", href: "/admin/channels" },
    ],
  },
  {
    id: "documents",
    title: "Reports and decks agent",
    keywords: ["report", "deck", "slides", "presentation", "powerpoint", "excel", "spreadsheet", "pdf", "word", "document", "proposal", "invoice"],
    agent: {
      name: "Documents Agent",
      knowledgeBase: true,
      systemPrompt: (goal) =>
        "You produce finished business documents. When asked for a report, proposal, spreadsheet, PDF or slide deck, use the matching skill and the generate_document tool, and always give the user the download link it returns. Never invent figures: use what you were given or found, and mark real gaps as [to confirm]." + goalLine(goal),
    },
    skills: ["docx", "pdf", "xlsx", "pptx"],
    connect: [
      { kind: "knowledge", label: "Add the source material", why: "Past reports, price lists and brand wording make the output yours.", href: "/knowledge" },
    ],
  },
  {
    id: "research",
    title: "Research and briefing agent",
    keywords: ["research", "brief", "summar", "competitor", "market", "news", "analy", "monitor"],
    agent: {
      name: "Research Agent",
      knowledgeBase: false,
      systemPrompt: (goal) =>
        "You research topics from live web pages and produce briefings a busy person can act on. Read the sources you are given or can fetch, separate what the source says from your own inference, and always end with 'What to do next'. Cite the page each claim came from. If a page cannot be fetched, say so instead of guessing its contents." + goalLine(goal),
    },
    skills: ["docx", "pdf"],
    connect: [
      { kind: "mcp", label: "Add a search or browser connector", why: "So it can find sources itself instead of needing URLs from you.", href: "/tools", mcpKeywords: ["search", "brave", "browser", "fetch", "puppeteer", "playwright"] },
    ],
  },
  {
    id: "knowledge",
    title: "Ask-my-documents agent",
    keywords: ["knowledge", "rag", "policy", "manual", "handbook", "sop", "contract", "my documents", "my files"],
    agent: {
      name: "Knowledge Agent",
      knowledgeBase: true,
      systemPrompt: (goal) =>
        "You answer questions strictly from this workspace's knowledge base. Search it before answering, quote or name the document you relied on, and say plainly when the knowledge base does not cover the question rather than answering from general knowledge." + goalLine(goal),
    },
    skills: [],
    connect: [
      { kind: "knowledge", label: "Upload your documents", why: "The agent can only answer from what you add here.", href: "/knowledge" },
    ],
  },
  {
    id: "digest",
    title: "Scheduled daily digest",
    keywords: ["daily", "every morning", "schedule", "digest", "weekly", "recurring", "cron", "remind"],
    agent: {
      name: "Digest Agent",
      knowledgeBase: false,
      systemPrompt: (goal) =>
        "You prepare a short recurring digest. Each run: gather what you were asked to check, report only what changed or needs a decision, lead with the single most important item, and keep the whole digest under 200 words." + goalLine(goal),
    },
    skills: [],
    connect: [
      { kind: "schedule", label: "Schedule it as a job", why: "So it runs every morning without you opening the app.", href: "/jobs" },
      { kind: "channel", label: "Choose where the digest is delivered", why: "Email or a chat channel, so it reaches you.", href: "/admin/channels" },
    ],
  },
];

/** Deterministic: most keyword hits wins; no match falls back to a general agent built around the goal. */
export function pickRecipe(goal: string): Recipe {
  const text = goal.toLowerCase();
  let best: Recipe | null = null;
  let bestHits = 0;
  for (const recipe of RECIPES) {
    const hits = recipe.keywords.filter((k) => text.includes(k)).length;
    if (hits > bestHits) {
      best = recipe;
      bestHits = hits;
    }
  }
  return best ?? generalRecipe();
}

export function recipeById(id: string | undefined): Recipe | null {
  return RECIPES.find((r) => r.id === id) ?? null;
}

function generalRecipe(): Recipe {
  return {
    id: "general",
    title: "Custom agent",
    keywords: [],
    agent: {
      name: "My Agent",
      knowledgeBase: true,
      systemPrompt: (goal) =>
        "You are an AI agent for this business. Be specific and practical, use your tools when they help, never invent facts, and ask one clarifying question when the request is genuinely ambiguous." + goalLine(goal),
    },
    skills: [],
    connect: [
      { kind: "mcp", label: "Give it more tools", why: "It already reads web pages and searches your documents. Add web search, sheets or email later, only when you need them.", href: "/tools", mcpKeywords: [] },
      { kind: "channel", label: "Put it on a chat channel", why: "Optional. Test it here first, then put it on WhatsApp, Telegram or email when you are happy with it.", href: "/admin/channels" },
    ],
  };
}

// ---------------------------------------------------------------------------
// Plain-language explanations. A first-time user does not know what an "agent"
// or a "workflow" is, or why either beats a chatbot - so the builder says so
// BEFORE it creates anything (the plan) and AFTER (what to do with it now).
// Keyed by recipe id; "general" covers the no-match fallback.
// ---------------------------------------------------------------------------

export type RecipeExplain = {
  /** What the agent will actually do, day to day. */
  agentDoes: string;
  /** What the workflow adds on top. Omitted for agent-only recipes. */
  workflowDoes?: string;
  /** Why this needs an agent rather than a chatbot, a form or a script. */
  whyAgentic: string;
  /** Concrete uses once it exists. */
  useItFor: string[];
};

export const AGENTIC_PRIMER: { term: string; meaning: string }[] = [
  { term: "Agent", meaning: "An AI worker with a job description, a model, and tools it can use on its own - it reads, decides, and acts, instead of only replying." },
  { term: "Workflow", meaning: "A fixed sequence of steps around one or more agents: a trigger, the work, branches, and approvals. It makes the result repeatable and auditable." },
  { term: "Why agentic", meaning: "A chatbot answers a question. An agent finishes a task: it looks things up, calls your tools, produces the file or the message, and stops to ask a person when the stakes are high." },
];

export const RECIPE_EXPLAIN: Record<string, RecipeExplain> = {
  support: {
    agentDoes: "Reads each customer message, looks the answer up in your own documents, replies in a short phone-friendly message, and hands the conversation to a person when it is upset, about money, or outside what it knows.",
    whyAgentic: "An FAQ page cannot hold a conversation and a plain chatbot makes things up. An agent checks your documents before it answers and knows when to stop and call a human.",
    useItFor: ["Answering customer questions on WhatsApp, Telegram or email around the clock", "Taking the repetitive first-line questions off your team", "Testing answers privately in the Playground before customers see them"],
  },
  leadflow: {
    agentDoes: "Reads an enquiry, works out who it is from, what they want and how urgent it is, scores the lead hot / warm / cold with its reasoning, and drafts the follow-up message.",
    workflowDoes: "Wraps the agent in a repeatable pipeline: a lead comes in, the agent qualifies it, hot leads take a different branch, a follow-up is drafted, and nothing is sent until a person approves it.",
    whyAgentic: "A form can capture a lead but cannot judge it. A script can send a template but cannot write a reply that fits this enquiry. The agent does the judging and the writing; the workflow keeps a human in charge of what actually goes out.",
    useItFor: ["Replying to every enquiry within minutes instead of the next day", "Making sure hot leads are seen first", "Keeping a record of every lead, score and approved message"],
  },
  documents: {
    agentDoes: "Turns a brief and your source material into a finished Word report, PDF, Excel sheet or PowerPoint deck, structured properly, and gives you the download link.",
    whyAgentic: "A chatbot gives you text to paste somewhere. This agent uses document skills and a file-generation tool to hand back the actual file, and it marks real gaps as [to confirm] instead of inventing figures.",
    useItFor: ["Weekly or board reports from your notes", "Proposals and quotes from a short brief", "Turning a long document into a slide deck"],
  },
  research: {
    agentDoes: "Fetches the web pages you point it at, pulls out what matters, separates what the source says from its own inference, cites each claim, and ends with what to do next.",
    whyAgentic: "A chatbot answers from memory, which may be out of date. This agent reads the live page with a tool, so the briefing reflects what is actually published today.",
    useItFor: ["Competitor and market briefings", "Summarising long articles, policies or tenders", "A reading list turned into actions"],
  },
  knowledge: {
    agentDoes: "Searches the documents you upload before every answer, names the document it relied on, and says plainly when your documents do not cover the question.",
    whyAgentic: "Search finds a file; you still have to read it. This agent reads it for you and answers the actual question, and refuses to guess when the answer is not there.",
    useItFor: ["Staff asking about policies, SOPs or contracts", "Onboarding new team members", "A single source of truth for product and pricing questions"],
  },
  digest: {
    agentDoes: "On each run, gathers what you asked it to check, reports only what changed or needs a decision, and leads with the single most important item.",
    whyAgentic: "A scheduled report sends the same table every day whether or not anything happened. An agent reads the situation and tells you only what deserves your attention.",
    useItFor: ["A morning briefing delivered to email or chat", "A weekly summary for your team", "Watching something for you so you do not have to check it"],
  },
  general: {
    agentDoes: "Works on the goal you described using the platform's built-in tools - reading web pages, searching your documents, generating files - and asks one clarifying question when your request is genuinely ambiguous.",
    whyAgentic: "You get a worker you can give tasks to, not a text box: it can look things up, use tools and produce real output, and you can extend it with skills, connectors and channels.",
    useItFor: ["Trying your own idea in the Playground", "Adding tools and knowledge until it fits the job", "Putting it in front of customers on a channel when it is ready"],
  },
};

export function explainFor(recipeId: string): RecipeExplain {
  return RECIPE_EXPLAIN[recipeId] ?? RECIPE_EXPLAIN.general;
}
