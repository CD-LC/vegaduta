// Competitor-comparison data — SEO authority pack (2026-07-25).
//
// SOURCE OF TRUTH: VEGADUTA-DOMINANCE-ROADMAP-2026-07.md (repo root) — §2
// competitor table and §3 differentiators. Every competitor claim below is
// traceable to that document and carries the citation the roadmap gives for
// it. All prices are third-party-reported 2026 figures and are labelled as
// such wherever they appear — never present them as verified.
//
// Positioning rules honoured here (roadmap §1 "Positioning to KILL"):
// - VegaDūta is NEVER pitched as a generic n8n/workflow-automation
//   alternative. The angle is always WhatsApp-first, Indian-language,
//   multi-tenant agents. The n8n page says outright that n8n is better at
//   generic workflow automation.
// - No Langflow-CVE material anywhere (roadmap: not before the §4 security
//   review) — Langflow intentionally has no page here.
// - Honest "what they do better" and "where VegaDūta is behind" content on
//   every page, straight from the roadmap's gap columns.
//
// FACT REFRESH 2026-08-17 — n8n integration count. This file (and
// why-vegaduta's "smaller ecosystem" bullet) said "400+ integrations", a
// figure that came in with the 2026-07-25 roadmap and had gone badly stale:
// n8n.io/integrations advertised 2,056 when checked on 2026-08-17. Rewritten
// as "over 2,000 integrations by its own count" — attributed, and a round
// number so it does not re-stale every quarter. Note the direction of the
// error: we were UNDERSTATING a competitor by 5x on a page whose entire
// premise is honest comparison, which is worse for credibility than
// overstating our own side would be. Third-party counts vary (500-1,200
// depending on whether nodes or operations are counted); n8n's own headline
// figure is the one to cite. Re-check this on the next content pass.
//
// FACT REFRESH 2026-10-03 — both sides of the table.
// THEM: prices now come from each vendor's OWN pricing page, read on
// 2026-10-03 (n8n.io/pricing, dify.ai/pricing, botpress.com/pricing), not
// from third-party roundups; all three had moved since August. n8n's page
// also lists MCP Server Trigger / MCP Client nodes and metric-based
// evaluations, so rows that implied n8n had neither were understating it
// again. Botpress's "no MCP story" line is removed: nobody re-verified it.
// Cells for things we did not check say "Not assessed in our research".
// US: VegaDūta cells were written before Agent Lab, Agent Skills, teams,
// versions, eval gates and App Studio existed, said "self-hosted" (prod moved
// to Azure 2026-09-09) and "agents converse in 22 Indian languages incl.
// TTS" (22 is the INTERFACE count; speech coverage is narrower — on the
// do-not-claim list). Counts follow lib/platform-facts.ts.
//
// ROWS ADDED 2026-10-04 — six more shared rows (models & cost control,
// workflow engine, tools & skills, CRM & outreach, app builder, voice &
// vision) covering what shipped since the table was first written. Counts
// again follow lib/platform-facts.ts (35 node types, 50+ tools, 14 skills,
// 14 BYOK providers). "Them" cells stay NOT_ASSESSED where nothing was
// re-checked.

export type Citation = { label: string; href: string };

export type CompareRow = {
  label: string;
  vega: string;
  them: string;
  cites?: Citation[];
};

export type Faq = { q: string; a: string };

export type Competitor = {
  slug: string;
  name: string;
  metaTitle: string; // <= 60 chars
  metaDescription: string; // <= 155 chars
  h1: string;
  intro: string[]; // opening paragraphs — honest framing first
  rows: CompareRow[];
  theyDoBetter: string[];
  whenToPickThem: string[];
  whenToPickVega: string[];
  faqs: Faq[];
  citations: Citation[];
};

// Real dates, from `git log` on this file (GEO pass, 2026-09-19) — shown on
// the page and emitted as Article datePublished/dateModified. BUMP `updated`
// (and its label) in the same commit as any change to a competitor's rows or
// citations, and never without one: comparison facts go stale fastest of
// anything on this site, so a date that moved on its own would be vouching
// for prices nobody re-checked.
export const COMPARE_DATES = {
  published: "2026-07-25",
  updated: "2026-10-04",
  updatedLabel: "4 October 2026",
};

export const PRICE_DISCLAIMER =
  "Competitor prices were read from each vendor's own pricing page on 3 October 2026, at annual-billing rates. Plans change — check the official page before deciding.";

// Honest current-state limitations, shown on every comparison page.
// Straight from the roadmap's own caveats (§1 "Where ambition currently
// outruns evidence") — honesty converts and ranks.
export const VEGA_BEHIND: string[] = [
  "Fewer integrations than the big workflow tools — VegaDūta's tool surface is MCP connectors plus built-in tools, measured in dozens, not hundreds.",
  "A smaller community and template ecosystem than any platform on this page.",
  "One cloud region today, and no SOC 2 or ISO certification yet. If your procurement process requires those, we are not there yet.",
  "Young features: Agent Lab, agent teams and SSO/SCIM shipped in September 2026 and have far fewer production hours behind them than the older products on this page.",
];

// Shared VegaDūta cells — the same product facts on every page, phrased once.
const VEGA = {
  tenancy:
    "Schema-per-tenant Postgres — every tenant is isolated in its own database schema, with per-tenant encrypted secrets.",
  channels:
    "Nine built-in channels: WhatsApp (Meta Cloud API or Twilio), Telegram, Email, voice calls, Slack, Discord, Instagram, Messenger and X. WhatsApp, Telegram and Email are on the Free plan.",
  languages:
    "Console in 30 languages, including all 22 scheduled Indian languages. Agents reply in the language the customer writes in. Speech is narrower than the console: agents can use Sarvam speech tools, but spoken-language coverage depends on the speech provider you connect. Sarvam voice in live in-app voice calls is currently Indian English, and phone calls through Twilio use Twilio's relay speech with its default language.",
  upi: "Plans priced for India and payable by UPI.",
  sandbox:
    "Anonymous 5-day sandbox at /try — a real tenant, no account, no card.",
  academy:
    "Free Academy with 280+ lessons, from a first agent to running one in production, linked from the console pages they teach.",
  pricing:
    "Free tier plus India-priced paid plans, payable by UPI — see the pricing page for current numbers.",
  behind:
    "Dozens of integrations rather than hundreds; smaller community; one cloud region today; no SOC 2 yet.",
  lab: "Agent Lab replays any run as a graph, re-runs it from a chosen step, removes prompt sections one at a time to show which ones matter, and repeats an input to check consistency. A Request X-ray shows the exact prompt, tools and budget sent to the model. Eval datasets scored by six LLM judges can gate promoting a version to Production.",
  models:
    "Bring your own key for 14 providers (Anthropic, OpenAI, Azure OpenAI, Google Vertex, Gemini, AWS Bedrock, Mistral, DeepSeek, Groq, Grok, OpenRouter, Ollama, Sarvam, Meta) or use the platform-hosted model pool — with per-user daily dollar budgets and a cost tier shown per model in the picker.",
  workflows:
    "Visual builder with 35 node types including human approval, an expression language, data pinning, live run streaming with per-step token and tool-call detail, comments and version diffs; a Temporal durable-execution engine can be switched on per deployment.",
  tools:
    "50+ built-in agent tools, 14 built-in Agent Skills (agent-drafted skills stay off until an admin approves them), and a browser-use fallback for sites with no connector.",
  crm:
    "Built-in leads CRM and outreach: a leads pipeline, a bulk-email workflow node, reply and bounce detection, and agents that run on a schedule.",
  builder:
    "App Studio: describe an app and get a multi-page app with live preview, per-project Postgres roles, self-repairing previews, GitHub import and two-way sync, a template gallery and store-ready mobile builds.",
  senses:
    "Beyond text: live camera and screen vision (VegaDūta Sight) and a voice-menu IVR node with real DTMF keypress handling on the call trigger.",
  teams:
    "A lead agent hands parts of a task to specialist agents, several at once when the team has two or more members, under one shared per-run budget. Each delegation shows up in the run graph.",
  mcp: "MCP in both directions: 20 one-click MCP servers plus any server you point it at, and VegaDūta is itself an MCP server, so other AI clients can call your agents and workflows.",
  source:
    "Apache-2.0 licence and a Docker Compose self-host stack in the repository. The public repository is not published yet.",
};

// Rows that are identical on our side on every page (2026-10-03). The "them"
// cell is per competitor and says "Not assessed" where we did not check.
const NOT_ASSESSED = "Not assessed in our research.";
function sharedRows(them: {
  lab: string;
  teams: string;
  mcp: string;
  source: string;
  models: string;
  workflows: string;
  tools: string;
  crm: string;
  builder: string;
  senses: string;
  cites?: { lab?: Citation[]; mcp?: Citation[]; source?: Citation[]; models?: Citation[] };
}): CompareRow[] {
  return [
    { label: "Models and cost control", vega: VEGA.models, them: them.models, cites: them.cites?.models },
    { label: "Workflow engine", vega: VEGA.workflows, them: them.workflows },
    { label: "Tools and skills", vega: VEGA.tools, them: them.tools },
    { label: "CRM and outreach", vega: VEGA.crm, them: them.crm },
    { label: "App builder", vega: VEGA.builder, them: them.builder },
    { label: "Voice and vision", vega: VEGA.senses, them: them.senses },
    { label: "Testing and debugging agents", vega: VEGA.lab, them: them.lab, cites: them.cites?.lab },
    { label: "Agent teams", vega: VEGA.teams, them: them.teams },
    { label: "MCP (Model Context Protocol)", vega: VEGA.mcp, them: them.mcp, cites: them.cites?.mcp },
    { label: "Source and self-hosting", vega: VEGA.source, them: them.source, cites: them.cites?.source },
  ];
}

const N8N_CITES = {
  official: {
    label: "n8n.io — official pricing page (read 3 October 2026)",
    href: "https://n8n.io/pricing/",
  },
  cloudPricing: {
    label: "connectsafely.ai — n8n Cloud pricing guide (2026)",
    href: "https://connectsafely.ai/articles/n8n-cloud-pricing-guide",
  },
  pricingGuide: {
    label: "lowcode.agency — n8n pricing (2026)",
    href: "https://www.lowcode.agency/blog/n8n-pricing",
  },
  whiteLabel: {
    label: "TaskJuice — n8n white-label cost for agencies",
    href: "https://taskjuice.ai/blog/n8n-white-label-cost-agencies",
  },
  multiTenancy: {
    label: "n8n community — white-label / multi-tenancy thread",
    href: "https://community.n8n.io/t/can-n8n-be-used-as-a-white-label-solution-supporting-multi-tenancy/8493",
  },
};

const DIFY_CITES = {
  official: {
    label: "dify.ai — official pricing page (read 3 October 2026)",
    href: "https://dify.ai/pricing",
  },
  pricing: {
    label: "dupple.com — Dify pricing (2026)",
    href: "https://dupple.com/pricing/dify",
  },
  overview: {
    label: "xpay.sh — Dify agentic-framework overview",
    href: "https://www.xpay.sh/resources/agentic-frameworks/dify/",
  },
  review: {
    label: "dev.to — six-month hands-on Dify review (2026)",
    href: "https://dev.to/nova_gg/dify-review-2026-i-used-it-for-6-months-to-build-ai-agents-honest-verdict-2d25",
  },
};

const BOTPRESS_CITES = {
  pricing: {
    label: "botpress.com — official pricing page (read 3 October 2026)",
    href: "https://botpress.com/pricing",
  },
  roundup: {
    label: "talkbar.ai — Botpress pricing and alternatives (2026)",
    href: "https://talkbar.ai/blog/botpress-pricing-and-alternatives",
  },
};

// Each competitor's price list is written ONCE and reused by its table row
// and its pricing FAQ, so a re-check updates one string and the two can
// never disagree.
const DIFY_PRICING =
  "Sandbox free (200 message credits), Professional $590/yr (about $49/mo, 5,000 credits a month), Team $1,590/yr (about $133/mo, 10,000 credits a month), Enterprise custom. The Community edition is free to self-host.";
const BOTPRESS_PRICING =
  "Free $0 (25 conversations, webchat only); Plus $150/mo (250 conversations, $25 AI usage included); Team $750/mo (1,500 conversations, $150 AI usage included, routing and RBAC); Enterprise custom. Paid plans billed annually.";
// Botpress Team plan in rupees, at the rate named in the string.
const BOTPRESS_TEAM_INR = "₹63,000 a month at ₹84 to the dollar";

export const COMPETITORS: Competitor[] = [
  // ------------------------------------------------------------------ n8n
  {
    slug: "n8n-alternative-india",
    name: "n8n",
    metaTitle: "VegaDūta vs n8n: WhatsApp AI Agents in India (2026)",
    metaDescription:
      "Honest 2026 comparison: n8n for workflow automation vs VegaDūta for governed AI agents on WhatsApp, voice and seven more channels. Pricing and real gaps.",
    h1: "VegaDūta vs n8n for WhatsApp AI agents in India (2026)",
    intro: [
      "n8n is one of the best workflow-automation tools you can run today: over 2,000 integrations by its own count, a huge template ecosystem, a strong brand, and a free self-hostable Community Edition. If what you need is general-purpose workflow automation, n8n is a strong choice — and this page will not try to talk you out of it.",
      "VegaDūta solves a different problem. You describe a job in a sentence and get an agent that answers your customers on WhatsApp, Telegram, email, voice calls and five more channels, with approval gates, budgets and an audit log around everything it does. It runs on a multi-tenant backend where one deployment serves many isolated client tenants, paid for in rupees over UPI. Workflows exist inside VegaDūta, with 35 node types, but as a supporting capability for agents, not as the product.",
      "The table below compares the two on the dimensions that actually differ — including, honestly, the ones where n8n is ahead.",
    ],
    rows: [
      {
        label: "Native multi-tenancy",
        vega: VEGA.tenancy,
        them: "No native multi-tenancy — agencies run one instance per client, or pay ~$50k/yr (third-party report, 2026) for white-label embedding.",
        cites: [N8N_CITES.multiTenancy, N8N_CITES.whiteLabel],
      },
      {
        label: "Customer channels",
        vega: VEGA.channels,
        them: "WhatsApp reachable via generic HTTP requests or community nodes — n8n is workflow-first, not channel-native.",
      },
      {
        label: "Indian languages",
        vega: VEGA.languages,
        them: "No Indian-language stack.",
      },
      {
        label: "UPI billing",
        vega: VEGA.upi,
        them: "USD/EUR card pricing.",
      },
      {
        label: "Try without signup",
        vega: VEGA.sandbox,
        them: "Free self-host Community Edition; the cloud trial requires signup. No provisioned no-signup sandbox.",
      },
      {
        label: "Learning funnel",
        vega: VEGA.academy,
        them: "n8n's academy is courseware — lessons are not tied to a provisioned sandbox.",
      },
      {
        label: "Pricing (official page, 3 October 2026)",
        vega: VEGA.pricing,
        them: "Starter €20/mo (2,500 executions), Pro €50/mo (10,000), Business €667/mo (40,000), Enterprise custom — all billed annually. White-label embedding ~$50k/yr (third-party report).",
        cites: [N8N_CITES.official, N8N_CITES.whiteLabel],
      },
      ...sharedRows({
        lab: "Execution history with the data at every node, and metric-based evaluations on the Pro plan.",
        teams: NOT_ASSESSED,
        mcp: "MCP Server Trigger and MCP Client nodes, listed on the Starter and Pro plans.",
        source: "Community Edition is free to self-host.",
        models: "Any model over HTTP or AI nodes — no hosted model pool or per-user spend budgets built in.",
        workflows: "The strongest engine on this page — thousands of integrations and a mature executor. This row is n8n's, honestly.",
        tools: "Thousands of integrations and community nodes — VegaDūta's tool surface is much smaller.",
        crm: "No built-in CRM — though wiring an external CRM and mail tools is exactly what n8n is good at.",
        builder: "No — n8n automates workflows, it does not generate apps.",
        senses: "Reachable through integrations; nothing native for live vision or IVR telephony.",
        cites: { lab: [N8N_CITES.official], mcp: [N8N_CITES.official] },
      }),
      {
        label: "Where each is behind",
        vega: VEGA.behind,
        them: "No tenant isolation, no Indian-language stack, no channel-native agents, no UPI, no learning-to-deployment funnel.",
      },
    ],
    theyDoBetter: [
      "Integration breadth — thousands of integrations against VegaDūta's dozens.",
      "A massive template library and years of community-built content.",
      "Brand recognition — n8n effectively owns the 'automation' keyword.",
      "Mature cloud operations and high availability.",
      "Community size and momentum.",
    ],
    whenToPickThem: [
      "You need general-purpose workflow automation across hundreds of SaaS tools.",
      "You want a free, self-hosted automation server and are comfortable operating it yourself.",
      "Your automations are internal back-office pipelines, not customer-facing conversations.",
      "You want to start from a large library of ready-made workflow templates.",
    ],
    whenToPickVega: [
      "Your product is an agent that answers customers on WhatsApp, Telegram, email or a phone call, in the language they write in.",
      "You are an agency deploying agents for many clients and need real tenant isolation without an instance per client or a ~$50k/yr white-label contract.",
      "You need governance around what agents do: per-user dollar budgets, human approval steps, audit logs, guardrails.",
      "You want to see why an agent answered the way it did: replay the run, fork it from one step, and gate a new version on an eval before it reaches customers.",
      "You want to pay in rupees over UPI, at Indian price points.",
      "You want to try the real product before creating an account — the /try sandbox is a live tenant, no signup.",
    ],
    faqs: [
      {
        q: "Is VegaDūta an n8n alternative for workflow automation?",
        a: "No — and it does not try to be. n8n is stronger for general-purpose workflow automation, with over 2,000 integrations by its own count and a free self-hostable Community Edition. VegaDūta is a platform for customer-facing AI agents on WhatsApp, voice and seven more channels, on a multi-tenant backend with approvals, budgets and audit built in; workflows exist in it as a supporting capability for agents, not as the headline product.",
      },
      {
        q: "Does n8n support multi-tenancy for agencies?",
        a: "Not natively. Per n8n community discussions and third-party analyses, agencies typically run one n8n instance per client, or pay roughly $50k/yr (a third-party figure from 2026 — verify with n8n) for white-label embedding. VegaDūta is schema-per-tenant by design: one deployment, many isolated tenants, each in its own database schema.",
      },
      {
        q: "Can n8n run WhatsApp AI agents in Indian languages?",
        a: "You can call WhatsApp APIs from n8n through generic HTTP requests or community nodes, but there is no native WhatsApp channel or Indian-language stack. VegaDūta ships WhatsApp (Meta Cloud API and Twilio), Telegram, email and voice as first-class channels, with a console in all 22 scheduled Indian languages and Sarvam speech tools available to agents.",
      },
      {
        q: "How much does n8n cost in 2026?",
        a: "On n8n's own pricing page on 3 October 2026, billed annually: Starter €20/mo (2,500 executions), Pro €50/mo (10,000), Business €667/mo (40,000) and Enterprise at a custom price. White-label embedding is reported by third parties at around $50k/yr. Plans change — check n8n's pricing page before deciding.",
      },
      {
        q: "Does n8n support MCP and evaluations?",
        a: "Yes. n8n's pricing page lists MCP Server Trigger and MCP Client nodes and metric-based evaluations. VegaDūta also speaks MCP in both directions and adds agent-specific tooling on top: Agent Lab to replay and fork a run, and eval datasets that gate which agent version is promoted to Production.",
      },
    ],
    citations: [
      N8N_CITES.official,
      N8N_CITES.cloudPricing,
      N8N_CITES.pricingGuide,
      N8N_CITES.whiteLabel,
      N8N_CITES.multiTenancy,
    ],
  },

  // ----------------------------------------------------------------- Dify
  {
    slug: "dify-alternative-india",
    name: "Dify",
    metaTitle: "VegaDūta vs Dify: WhatsApp AI Agents in India (2026)",
    metaDescription:
      "Dify vs VegaDūta in 2026: open-source LLM app builder vs a governed agent platform on WhatsApp, voice and email. Tenant isolation, UPI, honest gaps.",
    h1: "VegaDūta vs Dify for WhatsApp AI agents in India (2026)",
    intro: [
      "Dify won the open-source 'LLM app builder' mindshare battle: a polished visual builder, built-in RAG, broad model support, a plugin marketplace and a very large OSS community — free to self-host. For building internal AI apps and RAG tools, it is a fine default.",
      "VegaDūta plays a different game: customer-facing AI agents on WhatsApp, Telegram, email and voice calls, with real tenant isolation, per-user spend budgets, human approval steps — and billing in rupees over UPI. If your agents need to meet customers on the channels Indians actually use, and you need to prove afterwards what each agent did, that is the gap this comparison is about.",
    ],
    rows: [
      {
        label: "Native multi-tenancy",
        vega: VEGA.tenancy,
        them: "Workspace-level multi-user sharing — not tenant isolation.",
      },
      {
        label: "Customer channels",
        vega: VEGA.channels,
        them: "No WhatsApp-native channel story.",
      },
      {
        label: "Indian languages",
        vega: VEGA.languages,
        them: "No Indian-language stack.",
      },
      {
        label: "UPI billing",
        vega: VEGA.upi,
        them: "USD card pricing.",
      },
      {
        label: "Try without signup",
        vega: VEGA.sandbox,
        them: "Free Sandbox tier (200 message credits) — requires signup.",
        cites: [DIFY_CITES.official],
      },
      {
        label: "Learning funnel",
        vega: VEGA.academy,
        them: "No education funnel.",
      },
      {
        label: "Pricing (official page, 3 October 2026)",
        vega: VEGA.pricing,
        them: DIFY_PRICING,
        cites: [DIFY_CITES.official],
      },
      ...sharedRows({
        lab: NOT_ASSESSED,
        teams: NOT_ASSESSED,
        mcp: NOT_ASSESSED,
        source: "Open-source Community edition, free to self-host, with all core features in the public repository.",
        models: "Broad provider support is a Dify strength; cloud usage is metered in message credits.",
        workflows: "A visual pipeline builder for LLM apps and RAG — chatflow blocks rather than a general automation engine.",
        tools: "A plugin marketplace — a strength VegaDūta has no equivalent of.",
        crm: "Not the product's territory — build it yourself or integrate an external CRM.",
        builder: "Builds LLM chat/agent apps with simple webapp wrappers — not multi-page deployable apps with their own database roles.",
        senses: NOT_ASSESSED,
        cites: { source: [DIFY_CITES.official], models: [DIFY_CITES.official] },
      }),
      {
        label: "Where each is behind",
        vega: VEGA.behind,
        them: "Tenant isolation, Indian channels and languages, UPI, and a learning-to-deployment funnel; reported struggles with complex business logic and real-time integrations.",
        cites: [DIFY_CITES.review],
      },
    ],
    theyDoBetter: [
      "RAG UX polish — Dify's knowledge-base experience is more mature.",
      "Visual builder maturity.",
      "A marketplace of tools, models and integrations. VegaDūta's community template gallery and skills registry are much smaller.",
      "Sheer OSS contributor volume and community size.",
      "A published open-source repository today. VegaDūta's is not public yet.",
    ],
    whenToPickThem: [
      "You are building internal AI apps or RAG tools, not customer-facing channel agents.",
      "You want a free, self-hosted LLM app platform with a big community behind it.",
      "A visual builder and plugin marketplace matter more to you than channels or tenancy.",
    ],
    whenToPickVega: [
      "Your agents must meet customers on WhatsApp, Telegram or a phone call, in Indian languages.",
      "You serve multiple clients or business units and need schema-level tenant isolation, not workspace sharing.",
      "You need spend control and oversight: per-user dollar budgets, approval flows, audit logs, guardrails.",
      "You want the app as well as the agent: App Studio builds a multi-page app around your agents from a description.",
      "You want Indian pricing with UPI, and a no-signup sandbox to evaluate with.",
    ],
    faqs: [
      {
        q: "Is VegaDūta a Dify alternative?",
        a: "For building general LLM apps and RAG tools, Dify is excellent and free to self-host — VegaDūta does not compete there. If the goal is customer-facing agents on WhatsApp, Telegram, email and voice calls, with schema-per-tenant isolation, approval gates and UPI billing, that is the problem VegaDūta was built for and Dify was not.",
      },
      {
        q: "Is Dify multi-tenant?",
        a: "Dify offers workspace-level multi-user sharing rather than isolated tenancy. VegaDūta isolates every tenant in its own Postgres schema with per-tenant encrypted secrets — the difference matters if you host multiple clients or need hard data separation.",
      },
      {
        q: "How well does Dify handle complex production agents?",
        a: "A six-month hands-on review published on dev.to in 2026 reported struggles with complex business logic and real-time integrations. Dify remains strong for RAG apps and prototypes — as always, verify against your own workload rather than anyone's marketing, ours included.",
      },
      {
        q: "How much does Dify cost in 2026?",
        a: `On Dify's own pricing page on 3 October 2026: ${DIFY_PRICING} Plans change — check Dify's pricing page before deciding.`,
      },
    ],
    citations: [DIFY_CITES.official, DIFY_CITES.overview, DIFY_CITES.review],
  },

  // ------------------------------------------------------------- Botpress
  {
    slug: "botpress-alternative-india",
    name: "Botpress",
    metaTitle: "VegaDūta vs Botpress: WhatsApp AI Agents India (2026)",
    metaDescription:
      "Botpress vs VegaDūta for Indian SMBs in 2026: rupee pricing with UPI, WhatsApp on the Free plan, voice, multi-tenancy — and what Botpress does better.",
    h1: "VegaDūta vs Botpress for WhatsApp AI agents in India (2026)",
    intro: [
      "Botpress is a genuine conversational-AI specialist: a polished agent builder, years of chatbot-specific UX depth, agent-handoff and inbox tooling, multilingual NLU, and WhatsApp support in its Plus plan. Of all the platforms we compare against, it is the closest in spirit to VegaDūta — which makes the differences worth being precise about.",
      `Those differences are India-shaped. On Botpress's own pricing page, WhatsApp starts at the paid Plus plan, and the Team plan works out to roughly ${BOTPRESS_TEAM_INR} — a non-starter for most Indian SMBs. On VegaDūta, WhatsApp, Telegram and email are on the Free plan. There is also no UPI billing and no anonymous sandbox.`,
    ],
    rows: [
      {
        label: "Native multi-tenancy",
        vega: VEGA.tenancy,
        them: "Not multi-tenant by design — no agency-style sub-tenant model with schema-level isolation.",
      },
      {
        label: "Customer channels",
        vega: VEGA.channels,
        them: "Webchat only on the Free plan. WhatsApp and a white-label webchat from the Plus plan; a voice channel is listed under Enterprise.",
        cites: [BOTPRESS_CITES.pricing],
      },
      {
        label: "Indian languages",
        vega: VEGA.languages,
        them: "Multilingual NLU. Indian-language voice was not assessed in our research.",
      },
      {
        label: "UPI billing",
        vega: VEGA.upi,
        them: "USD pricing, with AI-spend metering on top of the subscription.",
      },
      {
        label: "Try without signup",
        vega: VEGA.sandbox,
        them: "No anonymous sandbox — signup comes before value.",
      },
      {
        label: "Learning funnel",
        vega: VEGA.academy,
        them: "Not assessed in our research.",
      },
      {
        label: "Pricing (official page, 3 October 2026)",
        vega: VEGA.pricing,
        them: BOTPRESS_PRICING,
        cites: [BOTPRESS_CITES.pricing],
      },
      ...sharedRows({
        lab: NOT_ASSESSED,
        teams: NOT_ASSESSED,
        mcp: NOT_ASSESSED,
        source: "Managed cloud product. Self-hosting was not assessed in our research.",
        models: "Models are bundled; AI usage is metered on top of the plan.",
        workflows: "A conversation-first flow builder with autonomous and AI-task nodes; general-purpose automation is not its aim.",
        tools: NOT_ASSESSED,
        crm: "No built-in CRM; handoff to external CRMs runs through integrations.",
        builder: "No app-generation feature — the product is agents and flows.",
        senses: NOT_ASSESSED,
        cites: { models: [BOTPRESS_CITES.pricing] },
      }),
      {
        label: "Where each is behind",
        vega: VEGA.behind,
        them: "Western-SMB pricing out of reach for most Indian SMBs; WhatsApp not on the free plan; no UPI; no anonymous sandbox.",
      },
    ],
    theyDoBetter: [
      "Conversation-design UX depth — years of chatbot-specific product polish.",
      "Agent-handoff and shared-inbox tooling for human takeover.",
      "Established relationships with WhatsApp Business Solution Providers (BSPs).",
      "A more mature managed-cloud operation.",
    ],
    whenToPickThem: [
      "Your team lives in conversation-design tooling and wants the deepest flow-authoring UX available.",
      "You need built-in human-handoff and shared-inbox workflows on day one.",
      "Western-market pricing in USD is not a constraint for you.",
    ],
    whenToPickVega: [
      `You are an Indian SMB or agency and roughly ${BOTPRESS_TEAM_INR} for a team plan is not a serious option.`,
      "Your customers write in Hindi, Tamil, Telugu, Bengali or Marathi, and some of them will call, not type.",
      "You deploy for multiple clients and need real tenant isolation under one deployment.",
      "You want UPI billing, per-user dollar budgets, human approval steps and audit logs.",
      "You want more than a conversation flow around the agent: a 21-node workflow engine with expressions and approvals, an experiment lab, a leads CRM and an app builder in one tenant.",
      "You want to evaluate in a live no-signup sandbox before committing.",
    ],
    faqs: [
      {
        q: "Is VegaDūta a Botpress alternative for Indian businesses?",
        a: "For Indian SMBs and agencies, yes — that is the exact market VegaDūta is built for: a console in all 22 scheduled Indian languages, WhatsApp via Meta Cloud API or Twilio on the Free plan, voice calls, rupee pricing over UPI, and schema-per-tenant isolation for agencies. For teams that want the deepest conversation-design UX and built-in human-handoff inbox tooling regardless of price, Botpress remains the stronger pick.",
      },
      {
        q: "How much does Botpress cost in 2026?",
        a: `On Botpress's own pricing page on 3 October 2026: ${BOTPRESS_PRICING} WhatsApp and the white-label webchat start at the Plus plan. The Team plan is roughly ${BOTPRESS_TEAM_INR}. Plans change — check botpress.com/pricing.`,
      },
      {
        q: "Does Botpress support Indian languages?",
        a: "Botpress offers multilingual NLU; we did not assess its Indian-language voice support. VegaDūta's console is available in all 22 scheduled Indian languages, its agents reply in the language the customer writes in, and agents can use Sarvam speech tools. Spoken-language coverage depends on the speech provider you connect; Sarvam voice in live in-app voice calls is currently Indian English, and phone calls through Twilio use Twilio's relay speech with its default language.",
      },
      {
        q: "What does Botpress do better than VegaDūta?",
        a: "Conversation-design UX depth, agent-handoff and shared-inbox tooling, years of chatbot-specific polish, and established BSP relationships. VegaDūta is younger: fewer integrations, a smaller community, one cloud region today, and no SOC 2 certification yet.",
      },
    ],
    citations: [BOTPRESS_CITES.pricing, BOTPRESS_CITES.roundup],
  },
];

export function findCompetitor(slug: string): Competitor | undefined {
  return COMPETITORS.find((c) => c.slug === slug);
}
