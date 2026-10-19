import { PLATFORM_FACTS } from "../lib/platform-facts";

// ---------------------------------------------------------------------------
// /glossary — definitional content (GEO pass, 2026-09-19).
//
// Why this page exists. "What is X?" is the query shape answer engines cite
// most, and this site had no page that answered one: the good definitions were
// buried in hub FAQ arrays (ai-data.ts: "What is the Model Context Protocol?",
// "What is an AI agent, in plain language?") under URLs about something else.
//
// Every entry has the same three parts, and the separation is the point:
//   - `definition`  — the general, vendor-neutral meaning. It must be true of
//     the term everywhere, not just here, and it must stand alone when quoted
//     with nothing around it. NO product mention. A definition that is really
//     an advert gets neither cited nor trusted.
//   - `sources`     — primary sources for THAT definition (the spec, the paper,
//     the statute). Verified to resolve on 2026-09-19. Cited claims are the
//     largest measured lift in the GEO literature (Aggarwal et al., KDD 2024).
//   - `onVegaDuta`  — how the product relates to the term. This is the only
//     part that talks about us, it is labelled as such on the page, and the
//     no-fiction rule applies in full: numbers come from lib/platform-facts.ts,
//     and nothing on PlatformLedger.tsx's do-not-claim list appears (so: no
//     "runs on Temporal" — the engine is opt-in and off by default — no RL,
//     no capacity/latency/uptime figures).
//
// Adding a term: add an entry. The page, its DefinedTermSet JSON-LD, the
// in-page index and llms.txt all read this array. Keep `id` stable — it is the
// URL fragment other sites will link to.
// ---------------------------------------------------------------------------

export const GLOSSARY_DATES = { published: "2026-09-19", updated: "2026-09-19" };

export type GlossarySource = { label: string; href: string };

export type GlossaryTerm = {
  /** URL fragment: /glossary#<id>. Never change once published. */
  id: string;
  term: string;
  /** The section heading — the question a person actually types, grammatical as written. */
  question: string;
  /** Other names people search for. */
  alsoKnownAs?: string[];
  /** 1–3 sentences, vendor-neutral, quotable with no surrounding context. */
  definition: string;
  /** One optional paragraph of plain-language elaboration. */
  detail?: string;
  sources: GlossarySource[];
  onVegaDuta: string;
  /** Internal page that goes deeper. */
  learnMore: { href: string; label: string };
};

const F = PLATFORM_FACTS;

export const GLOSSARY: GlossaryTerm[] = [
  {
    id: "agentic-ai",
    question: "What is agentic AI?",
    term: "Agentic AI",
    alsoKnownAs: ["agentic artificial intelligence", "AI agents"],
    definition:
      "Agentic AI is software in which a large language model does not just produce text but decides what to do next: it plans steps toward a goal, calls tools such as search, databases or APIs, observes the results, and continues until the task is done or it needs a person.",
    detail:
      "The difference from a chatbot is action. A chatbot answers a question; an agentic system can look up the order, draft the reply, update the record and ask for approval before issuing the refund. Because it acts, it needs controls a chatbot does not: permissions, spending limits, guardrails and a human in the loop for consequential steps.",
    sources: [
      { label: "Wang et al. — A Survey on Large Language Model based Autonomous Agents (2023)", href: "https://arxiv.org/abs/2308.11432" },
      { label: "Anthropic — Building effective agents", href: "https://www.anthropic.com/engineering/building-effective-agents" },
    ],
    onVegaDuta: `VegaDūta is an agentic AI platform: you describe an agent in one sentence, give it tools and a knowledge base, and deploy it on up to ${F.channels} channels, with human approval in front of consequential actions.`,
    learnMore: { href: "/ai/agents", label: "AI agents on VegaDūta" },
  },
  {
    id: "ai-agent",
    question: "What is an AI agent?",
    term: "AI agent",
    alsoKnownAs: ["LLM agent", "autonomous agent"],
    definition:
      "An AI agent is a program that uses a large language model to choose and carry out actions toward a goal. It combines instructions that define its role, a set of tools it is allowed to call, memory or knowledge it can consult, and a loop that alternates between reasoning and acting.",
    detail:
      "The reason-then-act loop was formalised in the ReAct paper: the model writes a thought, takes an action, reads the observation, and repeats. Most production agents are a variation on that loop with stricter limits on which tools may run and when.",
    sources: [
      { label: "Yao et al. — ReAct: Synergizing Reasoning and Acting in Language Models (2022)", href: "https://arxiv.org/abs/2210.03629" },
      { label: "OpenAI — Function calling guide", href: "https://platform.openai.com/docs/guides/function-calling" },
    ],
    onVegaDuta: `A VegaDūta agent is a role, tools and channels. Agents have ${F.builtInToolsFloor}+ built-in tools, can be given more through MCP, and the same agent can serve WhatsApp, Telegram, email, voice and the web.`,
    learnMore: { href: "/ai/agents", label: "How VegaDūta agents work" },
  },
  {
    id: "model-context-protocol",
    question: "What is the Model Context Protocol (MCP)?",
    term: "Model Context Protocol (MCP)",
    alsoKnownAs: ["MCP", "MCP server", "MCP client"],
    definition:
      "The Model Context Protocol (MCP) is an open standard, introduced by Anthropic in November 2024, for connecting AI applications to external tools and data. An MCP server exposes tools, resources and prompts in a standard format; an MCP client, such as an AI assistant or an agent platform, discovers and calls them without custom integration code for each one.",
    detail:
      "MCP is often described as a USB-C port for AI: one connector shape instead of a bespoke integration per tool. Servers can run locally over standard input/output or remotely over HTTP.",
    sources: [
      { label: "Model Context Protocol — specification", href: "https://modelcontextprotocol.io/specification" },
      { label: "Anthropic — Introducing the Model Context Protocol", href: "https://www.anthropic.com/news/model-context-protocol" },
      { label: "Official MCP server registry", href: "https://registry.modelcontextprotocol.io" },
    ],
    onVegaDuta: `VegaDūta is both an MCP client and an MCP server. Agents can use MCP servers over STDIO, SSE or streamable HTTP, and VegaDūta exposes ${F.mcpServerTools} tools of its own so an MCP client such as Claude or an IDE can list and call your agents and workflows and search your knowledge base.`,
    learnMore: { href: "/ai/mcp", label: "MCP on VegaDūta" },
  },
  {
    id: "retrieval-augmented-generation",
    question: "What is retrieval-augmented generation (RAG)?",
    term: "Retrieval-augmented generation (RAG)",
    alsoKnownAs: ["RAG", "grounded generation", "knowledge base search"],
    definition:
      "Retrieval-augmented generation (RAG) is a technique in which a language model is given relevant passages retrieved from an external document collection at the moment it answers, so the response is grounded in those documents rather than only in what the model memorised during training.",
    detail:
      "A typical pipeline splits documents into chunks, converts each chunk to a numeric embedding, finds the chunks closest to the question, optionally re-ranks them, and passes the best ones to the model along with the question. RAG reduces fabricated answers and lets a model use private or recent information without retraining.",
    sources: [
      { label: "Lewis et al. — Retrieval-Augmented Generation for Knowledge-Intensive NLP Tasks (2020)", href: "https://arxiv.org/abs/2005.11401" },
      { label: "Reimers & Gurevych — Sentence-BERT: Sentence Embeddings using Siamese BERT-Networks (2019)", href: "https://arxiv.org/abs/1908.10084" },
    ],
    onVegaDuta:
      "VegaDūta agents answer from documents you upload — PDF, DOCX, HTML, images, audio and video, with OCR for scanned files. Embeddings are computed locally rather than sent to a hosted embeddings API, and a reranker refines results before the model sees them.",
    learnMore: { href: "/ai/rag", label: "RAG on VegaDūta" },
  },
  {
    id: "ai-guardrails",
    question: "What are AI guardrails?",
    term: "AI guardrails",
    alsoKnownAs: ["LLM guardrails", "content safety filters"],
    definition:
      "AI guardrails are controls that sit outside a language model and check what goes into it and what comes out of it — for example detecting personal data, prompt-injection and jailbreak attempts, toxic language or policy violations — and then block, redact or escalate. They are enforced by the surrounding system rather than requested in the prompt.",
    sources: [
      { label: "OWASP Top 10 for Large Language Model Applications", href: "https://owasp.org/www-project-top-10-for-large-language-model-applications/" },
      { label: "NIST AI Risk Management Framework", href: "https://www.nist.gov/itl/ai-risk-management-framework" },
    ],
    onVegaDuta: `VegaDūta applies guardrails as a platform layer to every agent: ${F.guardrailTypes} guardrail types covering profanity, jailbreak attempts, personal data, policy violations, toxicity and custom rules, with flagged content sent to a review queue.`,
    learnMore: { href: "/ai/guardrails", label: "Guardrails on VegaDūta" },
  },
  {
    id: "human-in-the-loop",
    question: "What is human-in-the-loop approval?",
    term: "Human-in-the-loop approval",
    alsoKnownAs: ["HITL", "human oversight", "approval gate"],
    definition:
      "Human-in-the-loop approval means an automated system pauses before a consequential action — sending money, deleting data, contacting a customer — and waits for a person to approve or reject it. It keeps a human accountable for decisions the system is not trusted to make alone.",
    detail:
      "Human oversight is a legal requirement for high-risk AI systems under Article 14 of the EU AI Act, and a recommended control in most AI risk frameworks.",
    sources: [
      { label: "EU Artificial Intelligence Act — Article 14: Human oversight", href: "https://artificialintelligenceact.eu/article/14/" },
      { label: "NIST AI Risk Management Framework", href: "https://www.nist.gov/itl/ai-risk-management-framework" },
    ],
    onVegaDuta:
      "VegaDūta has approval as a first-class workflow node and as a gate in front of sensitive agent tool calls: the run pauses until a person approves or rejects, and the decision is written to the audit log.",
    learnMore: { href: "/platform/workflow-automation", label: "Approval steps in workflows" },
  },
  {
    id: "ai-workflow-automation",
    question: "What is AI workflow automation?",
    term: "AI workflow automation",
    alsoKnownAs: ["agentic workflow", "AI workflow"],
    definition:
      "AI workflow automation is the design of a multi-step business process as an explicit graph of steps — triggers, conditions, tool calls, approvals — in which some steps are carried out by a language model or an AI agent. The graph fixes the order and the control points; the model handles the steps that need judgement or language.",
    detail:
      "A workflow is the more predictable of the two common patterns. In a workflow the path is drawn in advance; in a free-running agent the model picks the path. Many production systems combine them: a workflow for the process, agents for individual steps.",
    sources: [
      { label: "Anthropic — Building effective agents (workflows vs. agents)", href: "https://www.anthropic.com/engineering/building-effective-agents" },
    ],
    onVegaDuta: `VegaDūta has a visual workflow builder with ${F.workflowNodeTypes} node types — including agent, knowledge, condition, loop, code, parallel and approval — and workflows can be started by a message, a phone call, an email or a webhook.`,
    learnMore: { href: "/platform/workflow-automation", label: "Workflow automation on VegaDūta" },
  },
  {
    id: "agent-to-agent-protocol",
    question: "What is the Agent-to-Agent (A2A) protocol?",
    term: "Agent-to-Agent protocol (A2A)",
    alsoKnownAs: ["A2A", "Agent2Agent", "agent card"],
    definition:
      "The Agent-to-Agent (A2A) protocol is an open standard that lets AI agents built by different vendors discover each other and exchange tasks. Each agent publishes an agent card — a JSON document, conventionally at /.well-known/agent-card.json — describing what it can do and how to call it.",
    detail:
      "A2A and MCP are complementary: MCP connects an agent to tools and data, while A2A connects an agent to other agents.",
    sources: [
      { label: "A2A Protocol — specification", href: "https://a2a-protocol.org/latest/specification/" },
    ],
    onVegaDuta:
      "VegaDūta implements A2A v1.0 and publishes an agent card at /.well-known/agent-card.json on its API host, so agents on other platforms can discover and call agents built here.",
    learnMore: { href: "/platform/apis", label: "VegaDūta APIs and protocols" },
  },
  {
    id: "ag-ui",
    question: "What is AG-UI?",
    term: "AG-UI (Agent–User Interaction protocol)",
    alsoKnownAs: ["AG-UI protocol"],
    definition:
      "AG-UI is an open, event-based protocol for the connection between an AI agent and the user interface in front of it. It standardises how an agent streams messages, tool calls and state changes to a front end, so any compliant interface can drive any compliant agent.",
    sources: [
      { label: "AG-UI — documentation", href: "https://docs.ag-ui.com/introduction" },
      { label: "AG-UI protocol — source repository", href: "https://github.com/ag-ui-protocol/ag-ui" },
    ],
    onVegaDuta:
      "VegaDūta supports AG-UI alongside A2A and MCP, covering the three connections an agent has: to its tools, to other agents, and to a user interface.",
    learnMore: { href: "/platform/apis", label: "VegaDūta APIs and protocols" },
  },
  {
    id: "bring-your-own-key",
    question: "What does bring your own key (BYOK) mean?",
    term: "Bring your own key (BYOK)",
    alsoKnownAs: ["BYOK", "bring your own model", "model-agnostic"],
    definition:
      "Bring your own key (BYOK) is an arrangement in which a customer supplies their own API credentials for a language-model provider to a platform that runs on top of it. The customer keeps the billing relationship and the choice of model, and the platform is not a reseller of model usage.",
    // No source on purpose: BYOK is a commercial arrangement, not a standard,
    // and there is no primary document that defines it. An empty list is
    // better than a citation that does not actually support the sentence.
    sources: [],
    onVegaDuta: `VegaDūta accepts your own key for ${F.byokProviders} LLM providers, validates it when you add it and stores it encrypted; every agent on the account can then use that provider's models. A platform-hosted model pool is available for accounts that do not bring a key.`,
    learnMore: { href: "/ai/llm-connectors", label: "LLM providers on VegaDūta" },
  },
  {
    id: "multi-tenancy",
    question: "What is multi-tenancy, and what is schema-per-tenant?",
    term: "Multi-tenancy (schema-per-tenant)",
    alsoKnownAs: ["tenant isolation", "schema per tenant"],
    definition:
      "Multi-tenancy is an architecture in which one running application serves many customers (tenants) while keeping each tenant's data separate. In the schema-per-tenant model every tenant gets its own database schema — its own set of tables — inside a shared database, which isolates data more strongly than a shared table filtered by a tenant column.",
    sources: [
      { label: "PostgreSQL documentation — Schemas", href: "https://www.postgresql.org/docs/current/ddl-schemas.html" },
    ],
    onVegaDuta:
      "VegaDūta isolates each customer in its own PostgreSQL schema, with authentication handled by Keycloak. The architecture and its trade-offs are documented publicly.",
    learnMore: { href: "/why-vegaduta", label: "Why VegaDūta is built this way" },
  },
  {
    id: "llm-as-a-judge",
    question: "What is LLM-as-a-judge evaluation?",
    term: "LLM-as-a-judge evaluation",
    alsoKnownAs: ["LLM judge", "model-graded evaluation"],
    definition:
      "LLM-as-a-judge is an evaluation method in which one language model scores the output of another against stated criteria such as accuracy, relevance or tone. It makes it practical to grade large volumes of open-ended answers, at the cost of inheriting the judging model's own biases.",
    sources: [
      { label: "Zheng et al. — Judging LLM-as-a-Judge with MT-Bench and Chatbot Arena (2023)", href: "https://arxiv.org/abs/2306.05685" },
    ],
    onVegaDuta:
      "VegaDūta includes six LLM judges — accuracy, helpfulness, length, relevance, safety and tone — for scoring agent responses.",
    learnMore: { href: "/platform/observability", label: "Observability and quality on VegaDūta" },
  },
];
