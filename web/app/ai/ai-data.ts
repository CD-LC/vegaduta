// ---------------------------------------------------------------------------
// /ai hub — AI capability pages (SEO/AEO data module).
//
// Grounding notes: every claim here maps to code on main. MCP client/server
// facts come from core/agents (DynamicMcpConnectionManager, registry review
// service) and core/mcp; agent tools from the core tool registry; LLM
// providers from core/providers; RAG from sidecar/ + core/knowledge;
// guardrails from core/guardrails + core/approval; graph memory from
// core/graphmemory (AGE); voice from sidecar Whisper + core/elevenlabs,
// core/sarvam, Twilio relay, LiveKit; edge from the WebLLM marketplace and
// core/devices pairing. No pricing numbers, no fabricated stats.
//
// Content sync 2026-08-17 (the "mcp" page only): the five outward-facing MCP
// tools are now named individually rather than described as "agents,
// workflows and knowledge search". That is not a copy preference — it is the
// literal contract of McpServerToolConfig#mcpServerSyncToolSpecifications,
// which serves ONLY this module's own five callbacks (McpListAgents,
// McpCallAgent, McpListMyWorkflows, McpCallWorkflow, McpSearchKnowledge)
// precisely so the whole outbound "mcp" toolset cannot leak; a real Kiro
// client verified "Connected (5 tools)". Also added: OAuth-backed connectors
// (core/mcp/atlassian) and edit/delete of registrations.
//
// Content sync 2026-10-03. Removed: Home Assistant as a shipped connector
// (it is on the do-not-claim list in lib/platform-facts.ts: the reference
// connector has never been run against a live Home Assistant), every
// "Temporal-backed" phrase (Temporal is opt-in, off by default), and graph
// memory "write arbitration" (app.graph-memory.arbitration.enabled defaults
// to false). Corrected: 40+ built-in tools -> 50+ (BuiltInToolClassifications
// .java), "10+ channels" -> 9 (ChannelType.java, Teams excluded as untested).
// Added: Agent Skills, teams and Agent Lab on the agents page (see
// platform-data.ts header for the code behind each), the Memory page on the
// graph-memory page (web/app/memory + memoryPage.* in messages/en.json), the
// VOICE_MENU workflow node on the voice page (core/workflow/voicemenu), and
// the tool confirmation gate on the guardrails page (app.tools
// confirmation-gate in application.yml).
// ---------------------------------------------------------------------------

import type { HubPage } from "../lib/seo-hub";

export const AI_TOPICS: HubPage[] = [
  {
    slug: "mcp",
    keyTakeaways: [
      "VegaDūta is both a Model Context Protocol (MCP) client and an MCP server.",
      "As an MCP client, VegaDūta connects agents to MCP servers over STDIO, SSE or streamable HTTP, from the official MCP registry or a custom registration.",
      "As an MCP server, VegaDūta exposes five tools — list agents, call an agent, list workflows, call a workflow and search the knowledge base — to any MCP client such as Claude or an IDE, authenticated with a scoped API key.",
    ],
    citations: [
      { label: "Model Context Protocol — specification", href: "https://modelcontextprotocol.io/specification" },
      { label: "Official MCP server registry", href: "https://registry.modelcontextprotocol.io" },
      { label: "Anthropic — Introducing the Model Context Protocol", href: "https://www.anthropic.com/news/model-context-protocol" },
    ],
    name: "Model Context Protocol (MCP)",
    metaTitle: "Model Context Protocol (MCP) Integrations | VegaDūta",
    metaDescription:
      "VegaDūta is both an MCP client and an MCP server: connect STDIO, SSE, and streamable-HTTP servers from the official registry, and expose agents as MCP tools.",
    h1: "Model Context Protocol on VegaDūta",
    kicker: "AI · Integrations",
    cardBlurb:
      "Connect any MCP server to your agents, and expose your agents as MCP tools to Claude or your IDE.",
    intro: [
      "VegaDūta speaks Model Context Protocol in both directions. As a client, it connects your agents to any MCP server — from the official registry or your own — over STDIO, SSE, or streamable HTTP. As a server, it exposes five curated tools over your own account: list your agents, call an agent, list your workflows, call a workflow, and search your knowledge base. Any MCP client — Claude, an IDE, another platform — connects with a scoped API key.",
      "You browse the official MCP registry (registry.modelcontextprotocol.io) directly from the console with cached search, or register a custom server dynamically with its command, arguments, and encrypted environment credentials. Registrations are editable and removable after the fact, not write-once. A built-in catalog offers 20 one-click entries for common servers, and Atlassian (Jira/Confluence) connects in one click over OAuth with no API token to copy.",
    ],
    sections: [
      {
        heading: "Connect MCP servers to your agents",
        body: "Every transport in the MCP spec is supported: STDIO for local processes, SSE, and streamable HTTP for remote servers. The platform tracks connection liveness and self-heals STDIO servers that die, so a crashed local process does not silently strand your agent without its tools.",
        bullets: [
          "Browse and search the official MCP registry from the console, backed by a cached catalog",
          "Register custom servers with command/args and encrypted env credentials",
          "One-click OAuth connectors where the vendor supports it — Atlassian's Remote MCP server needs no token and no app registration",
          "Edit or delete a registration later — change a URL, rotate a credential, or remove a server without starting over",
          "Connection liveness tracking with self-healing restarts for STDIO servers",
          "Per-plan connector limits (see /billing for plan details)",
        ],
      },
      {
        heading: "Security around third-party servers",
        body: "MCP servers are third-party code, so VegaDūta wraps them in layered controls rather than trusting them by default. URLs pass an SSRF guard, STDIO commands run against an allowlist inside a sandbox, and tool arguments and outputs are sanitized before they reach a model or leave the platform.",
        bullets: [
          "SSRF URL guard and STDIO command allowlist with sandboxed execution",
          "Tool-argument and tool-output sanitization plus credential redaction",
          "Admin review queue for new server registrations",
          "Per-tool-call human approval gates on sensitive actions",
        ],
      },
      {
        heading: "Expose VegaDūta as an MCP server",
        body: "The same protocol runs outward. Connect an MCP client to VegaDūta with a scoped API key and it sees exactly five tools — a deliberately curated set, not your whole internal toolbox re-exported. Rate limits apply per key, and you decide which capabilities each external client is granted.",
        bullets: [
          "list_my_agents — the agents this key is allowed to see",
          "call_agent — run one of them and get its answer back",
          "list_my_workflows and call_workflow — the same for workflows",
          "search_knowledge — retrieval over your own uploaded documents",
        ],
      },
      {
        heading: "Enterprise systems: what is and is not built in",
        body: "Atlassian (Jira/Confluence) is the first-party OAuth connector, and the built-in catalog covers 20 common servers. SAP and similar enterprise systems do not have built-in connectors — but if an MCP server exists for them, you can register it from the registry or as a custom server and your agents get its tools the same way. That is the point of standardizing on MCP instead of hand-writing one integration per system.",
      },
    ],
    faqs: [
      {
        q: "What is the Model Context Protocol (MCP)?",
        a: "MCP is an open protocol that lets AI applications connect to external tools and data sources through a standard interface. Instead of writing a bespoke integration for every system, a client speaks MCP to any compliant server. VegaDūta implements the protocol as both a client and a server.",
      },
      {
        q: "How do I connect an MCP server to VegaDūta?",
        a: "Browse the official MCP registry from the console and add a server with cached search, or register a custom one by supplying its command and arguments (STDIO) or URL (SSE / streamable HTTP). Credentials go into encrypted environment variables. Where a vendor supports OAuth — Atlassian, for example — connecting is a single click with no token to copy. Registrations can pass through an admin review queue before agents use them, and can be edited or deleted afterwards.",
      },
      {
        q: "Can Claude or my IDE use my VegaDūta agents as tools?",
        a: "Yes. Connect any MCP client with a scoped API key and it gets five tools: list_my_agents, call_agent, list_my_workflows, call_workflow, and search_knowledge. That curated set is intentional — the outward server exposes your account's agents, workflows and knowledge, not the internal toolbox those agents use. Rate limits apply per key.",
      },
      {
        q: "Can I connect Jira and Confluence to my agents?",
        a: "Yes, in one click. VegaDūta connects to Atlassian's official Remote MCP server over OAuth 2.1, using a single platform-wide client registered through Dynamic Client Registration — so there is no API token to generate and no Atlassian app for you to register first.",
      },
      {
        q: "Is it safe to run third-party MCP servers?",
        a: "VegaDūta assumes they are untrusted and layers controls around them: an SSRF guard on URLs, a command allowlist and sandbox for STDIO servers, sanitization of tool arguments and outputs, and credential redaction. For risky actions you can require a human to approve each tool call.",
      },
    ],
    related: [
      { href: "/tutorials/connect-mcp-server", label: "Tutorial: connect an MCP server" },
      { href: "/integrations/atlassian", label: "Jira & Confluence integration" },
      { href: "/ai/agents", label: "AI Agents on VegaDūta" },
      { href: "/platform/security", label: "Platform security" },
      { href: "/try", label: "Try it without signing up" },
    ],
  },
  {
    slug: "agents",
    keyTakeaways: [
      "An AI agent on VegaDūta is a role, a set of tools and one or more channels: it carries out work rather than only answering chat messages.",
      "The same agent can be deployed to WhatsApp, Telegram, Slack, Discord, Instagram, Messenger, X, email and voice.",
      "VegaDūta agents ship with more than 50 built-in tools and 14 built-in Agent Skills, and a lead agent can delegate to a team of specialist agents in parallel.",
      "Guardrails and human-approval gates sit in front of risky actions, so an agent asks before it acts.",
    ],
    citations: [
      { label: "Yao et al. — ReAct: Synergizing Reasoning and Acting in Language Models (2022)", href: "https://arxiv.org/abs/2210.03629" },
      { label: "Anthropic — Building effective agents", href: "https://www.anthropic.com/engineering/building-effective-agents" },
    ],
    name: "AI Agents",
    metaTitle: "AI Agents for WhatsApp, Voice & 9 Channels | VegaDūta",
    metaDescription:
      "An AI agent on VegaDūta is a worker you hire: it chats on WhatsApp and 8 other channels, uses 50+ built-in tools and skills, and asks you first before risky actions.",
    h1: "AI Agents on VegaDūta",
    kicker: "AI · Agents",
    cardBlurb:
      "Agents with 50+ built-in tools and 14 built-in skills, deployed across 9 channels, with guardrails that ask you before risky actions.",
    intro: [
      "An AI agent on VegaDūta is closer to a staff member than a chatbot: you give it a role, tools, and channels, and it does real work. Each agent runs on a Java/Spring runtime with more than 50 built-in tools — web fetch, document generation, image and video generation, knowledge search, memory, code execution, and more — and you deploy the same agent to WhatsApp, Telegram, Slack, Discord, Instagram, Messenger, X, email, or voice.",
      "Guardrails and human-approval gates sit in front of risky actions, so the agent asks you first instead of acting unilaterally. Agents can also work as a team, with a lead agent delegating to specialists in parallel, and you can hand anyone a public share link to chat with an agent without creating an account.",
    ],
    sections: [
      {
        heading: "What an agent can actually do",
        body: "Tools are what separate an agent from a chat window. VegaDūta agents ship with more than 50 built-in tools spanning research, content, memory, and action, and you extend the set further through MCP servers.",
        bullets: [
          "Web fetch, document generation, image and video generation",
          "Knowledge search over your uploaded documents, plus memory recall and remember",
          "Google Workspace suite, Sarvam language suite, sandboxed code execution with your own E2B key",
          "Telegram send, speak (text-to-speech), code intelligence, delegate-to-agent",
        ],
      },
      {
        heading: "One agent, every channel",
        body: "You build the agent once and attach channels to it. The same agent answers on WhatsApp, Telegram, Slack, Discord, Instagram, Facebook Messenger, X, email, and voice — no per-channel rebuild. This matters in India, where the customer conversation usually starts on WhatsApp, not on your website.",
      },
      {
        heading: "It asks you first",
        body: "Sensitive tool calls can be gated behind human approval: the agent proposes the action, a person approves or rejects it, and the platform logs the decision. Combined with built-in guardrails for PII, toxicity, and jailbreak attempts, this keeps an autonomous agent inside boundaries you set. Sampled LLM-as-judge quality scoring gives you an ongoing read on how well each agent is performing.",
      },
      {
        heading: "Agents that work together",
        body: "Delegation is itself a tool, so one agent can hand a subtask to a specialist agent and use the result. Add two or more members to an agent's team and the lead can delegate to several of them in parallel and combine the answers. Delegation goes one level deep, each call runs at most 8 branches, each branch is stopped after 240 seconds, and all branches share one per-run budget.",
      },
      {
        heading: "Skills an agent loads on demand",
        body: "An Agent Skill is a packaged set of instructions for one kind of task, stored as a folder with a SKILL.md file. The agent sees only each skill's name and description until a request matches, then loads the full instructions. Fourteen skills are built in, covering documents (docx, pdf, pptx, xlsx), writing, code review, data analysis, and SQL, and a workspace admin can add custom ones.",
      },
      {
        heading: "Testing and versions",
        body: "Every saved change to an agent is a version that can be restored, with staging and production labels. The Agent Lab in the playground draws each turn as a run graph, lets you fork the conversation from any message, and tests which sections of the prompt matter. Eval datasets and a promotion gate keep a failing version out of production.",
      },
    ],
    faqs: [
      {
        q: "What is an AI agent, in plain language?",
        a: "An AI agent is a language model given a role, tools, and permission to act — it can look things up, generate documents, remember facts, and message people, not just reply in a chat box. On VegaDūta you configure the role and tools, attach channels like WhatsApp, and the agent handles conversations and tasks on them.",
      },
      {
        q: "Which channels can a VegaDūta agent work on?",
        a: "WhatsApp, Telegram, Slack, Discord, Instagram, Facebook Messenger, X (Twitter), email, and voice. You attach channels to an agent rather than rebuilding it per channel, so one agent can cover all of them at once.",
      },
      {
        q: "Can I stop an agent from doing something risky?",
        a: "Yes. Risky tool calls can require human approval — the agent asks first, you approve or reject, and the decision is audit-logged. Guardrails for PII, toxicity, and jailbreak detection run on top of that.",
      },
      {
        q: "Can several agents work on one task together?",
        a: "Yes. An agent with two or more team members can delegate parts of a task to them in parallel and combine the results. Delegation is one level deep and bounded by a branch cap, a per-branch time limit, and a shared per-run budget.",
      },
      {
        q: "Can people talk to my agent without an account?",
        a: "Yes. Every agent can have a public share link that opens a chat with it — no sign-up required for the visitor. There is also a no-signup sandbox at /try where you can experience the platform itself before creating an account.",
      },
    ],
    related: [
      { href: "/tutorials/build-your-first-agent", label: "Tutorial: build your first agent" },
      { href: "/integrations/whatsapp", label: "WhatsApp integration" },
      { href: "/ai/guardrails", label: "AI guardrails" },
      { href: "/platform/agent-builder", label: "Agent Builder" },
      { href: "/platform/agent-skills", label: "Agent Skills" },
      { href: "/platform/agent-teams", label: "Agent teams" },
      { href: "/platform/agent-lab", label: "Agent Lab" },
      { href: "/templates", label: "Agent templates" },
    ],
  },
  {
    slug: "llm-connectors",
    name: "LLM Connectors",
    metaTitle: "LLM Connectors: 14 Providers, Bring Your Own Key | VegaDūta",
    metaDescription:
      "VegaDūta connects 14 LLM providers with your own API keys — Anthropic, OpenAI, Meta, Vertex, Bedrock, Groq, Ollama, Sarvam and more — with budgets and fallback.",
    h1: "LLM Connectors on VegaDūta",
    kicker: "AI · Models",
    cardBlurb:
      "Bring your own key for 14 LLM providers, with live model catalogs, per-user budgets, and automatic fallback.",
    intro: [
      "VegaDūta is model-agnostic: you bring your own API key for any of 14 LLM providers and every agent on your tenant can use them. Supported providers are Anthropic (Claude), OpenAI, Azure OpenAI, Google Vertex AI, Google Gemini, AWS Bedrock, Meta (Muse Spark), Mistral, DeepSeek, Groq, Grok (xAI), OpenRouter, Ollama for self-hosted local models, and Sarvam for Indian-language models.",
      "The platform fetches each provider's live model catalog, validates your key when you add it, enforces per-user daily budgets, and can fall back to a platform-hosted model pool, which itself fails over between models in cost order.",
    ],
    sections: [
      {
        heading: "Fourteen providers, one interface",
        body: "Add a key once and the provider's models appear across the platform — agent configuration, workflows, and the playground. Model lists are fetched live from each provider rather than hard-coded, so new models show up without waiting for a platform update.",
        bullets: [
          "Hosted APIs: Anthropic, OpenAI, Azure OpenAI, Google Vertex AI, Google Gemini, AWS Bedrock, Meta (Muse Spark), Mistral, DeepSeek, Groq, Grok (xAI), OpenRouter",
          "Self-hosted: Ollama, for running open models on your own hardware",
          "Indian languages: Sarvam, alongside its speech and translation suite",
        ],
      },
      {
        heading: "Keys are validated and spend is bounded",
        body: "Keys are checked against the provider when you save them, so a typo fails immediately instead of at 2 a.m. in a customer conversation. Per-user daily budgets cap what any one user can spend, and encrypted storage keeps credentials out of logs and prompts.",
      },
      {
        heading: "Fallback and the free tier",
        body: "On the free tier, agent traffic routes through a platform-hosted model pool — you can start without any API key at all. Paid tiers run on your own keys, and an automatic fallback chain can route to the platform pool if a provider call fails. Plan details live at /billing.",
      },
    ],
    faqs: [
      {
        q: "Which LLM providers does VegaDūta support?",
        a: "Fourteen: Anthropic (Claude), OpenAI, Azure OpenAI, Google Vertex AI, Google Gemini, AWS Bedrock, Meta (Muse Spark), Mistral, DeepSeek, Groq, Grok (xAI), OpenRouter, Ollama, and Sarvam. Each is bring-your-own-key, and Ollama lets you point at a self-hosted local model server.",
      },
      {
        q: "Do I need my own API key to use VegaDūta?",
        a: "Not to start. The free tier routes through a platform-hosted model pool, so agents work with no key at all. On paid tiers you connect your own provider keys and your traffic runs on them.",
      },
      {
        q: "Can I run local or self-hosted models?",
        a: "Yes, through the Ollama connector — point it at your own Ollama server and its models become selectable like any hosted provider. For fully in-browser models, see the Edge AI capability, which runs models on-device via WebLLM.",
      },
      {
        q: "What happens if my provider goes down or my key hits a limit?",
        a: "An automatic fallback chain can route the call to the platform-hosted model pool so the conversation continues. Per-user daily budgets also cap spend, so one runaway user cannot exhaust your provider account.",
      },
    ],
    related: [
      { href: "/tutorials/connect-llm-provider", label: "Tutorial: connect an LLM provider" },
      { href: "/ai/agents", label: "AI Agents on VegaDūta" },
      { href: "/ai/edge-ai", label: "Edge AI: models in the browser" },
      { href: "/billing", label: "Plans and billing" },
    ],
  },
  {
    slug: "rag",
    keyTakeaways: [
      "Retrieval-augmented generation (RAG) makes an agent answer from your documents instead of from the model's memory.",
      "VegaDūta ingests PDF, DOCX, HTML, images, audio and video, extracts text with OCR where needed, then chunks and embeds it for retrieval.",
      "Embeddings are computed locally on CPU with sentence-transformers, so documents are not sent to a hosted embeddings API, and a cross-encoder reranker refines the results.",
    ],
    citations: [
      { label: "Lewis et al. — Retrieval-Augmented Generation for Knowledge-Intensive NLP Tasks (2020)", href: "https://arxiv.org/abs/2005.11401" },
      { label: "Reimers & Gurevych — Sentence-BERT (2019)", href: "https://arxiv.org/abs/1908.10084" },
    ],
    name: "Knowledge & RAG",
    metaTitle: "Knowledge Base & RAG for AI Agents | VegaDūta",
    metaDescription:
      "VegaDūta's RAG pipeline extracts PDF, DOCX, HTML and images with OCR, embeds locally on CPU so documents stay in-house, reranks, and transcribes media.",
    h1: "Knowledge & RAG on VegaDūta",
    kicker: "AI · Knowledge",
    cardBlurb:
      "Upload documents, get grounded answers: extraction, OCR, local embeddings, reranking, and transcription in one pipeline.",
    intro: [
      "Retrieval-augmented generation (RAG) on VegaDūta means your agents answer from your documents instead of guessing. You upload PDFs, DOCX files, HTML, images, or audio and video; the pipeline extracts the text (with OCR where needed), chunks it, embeds it, and serves it back to agents through a knowledge-search tool at answer time.",
      "Embeddings are computed locally on CPU with sentence-transformers — your documents are not sent to a hosted embeddings API — and a cross-encoder reranker sharpens retrieval before the model sees it.",
    ],
    sections: [
      {
        heading: "From file to searchable knowledge",
        body: "The ingestion pipeline handles the formats businesses actually have, including scanned documents and recordings. Each stage is purpose-built rather than a single generic parser.",
        bullets: [
          "Extraction for PDF, DOCX, HTML, and images",
          "OCR via Tesseract locally, with Google Vision as a cloud option",
          "Audio and video transcription via Whisper",
          "Chunking tuned for retrieval, then embedding and indexing",
        ],
      },
      {
        heading: "Embeddings stay on the platform",
        body: "Vector embeddings are computed locally on CPU using sentence-transformers models. That is a deliberate privacy stance: document content is not shipped to a third-party embeddings API just to make it searchable. Combined with schema-per-tenant Postgres isolation, one tenant's knowledge never mixes with another's.",
      },
      {
        heading: "Retrieval that agents can use — and write to",
        body: "Knowledge search is an agent tool, so retrieval happens inside the agent's reasoning loop rather than as a bolted-on lookup. A cross-encoder reranks candidate chunks so the model gets the most relevant passages, and agents can also write to the knowledge base, letting them capture new information as they work.",
      },
      {
        heading: "What is honestly not wired",
        body: "AWS Textract and Azure Document Intelligence are not wired as extraction backends today; documents that would use them fall back to Tesseract OCR. If your documents are clean digital PDFs this rarely matters, but heavily degraded scans get Tesseract-grade OCR, not a cloud document-AI service.",
      },
    ],
    faqs: [
      {
        q: "What file types can I upload to the knowledge base?",
        a: "PDF, DOCX, HTML, and images, plus audio and video which are transcribed with Whisper. Scanned documents and images go through OCR — Tesseract locally by default, with Google Vision available as a cloud option.",
      },
      {
        q: "Are my documents sent to an external AI service?",
        a: "Not for embedding. Vectors are computed locally on CPU with sentence-transformers, so document content is not sent to a hosted embeddings API. OCR uses Tesseract locally unless you opt into the Google Vision cloud option, and tenant data is isolated schema-per-tenant in Postgres.",
      },
      {
        q: "How do agents use the knowledge base?",
        a: "Through tools: agents call knowledge search during a conversation to retrieve relevant chunks, which a cross-encoder reranks before the model answers. Agents can also write back to the knowledge base, so what they learn in one conversation can inform the next.",
      },
      {
        q: "Does VegaDūta support AWS Textract or Azure Document Intelligence?",
        a: "Not currently — those extraction backends are not wired, and such documents fall back to Tesseract OCR. Google Vision is available as the cloud OCR option today.",
      },
    ],
    related: [
      { href: "/ai/agents", label: "AI Agents on VegaDūta" },
      { href: "/ai/graph-memory", label: "Graph memory" },
      { href: "/platform/security", label: "Platform security" },
      { href: "/tutorials/build-your-first-agent", label: "Tutorial: build your first agent" },
    ],
  },
  {
    slug: "guardrails",
    keyTakeaways: [
      "Guardrails on VegaDūta are a platform layer applied to every agent, not instructions written into each prompt.",
      "Built-in detectors cover PII, toxicity, profanity, jailbreak attempts and policy violations, and custom rules can be added.",
      "Sensitive tool calls wait for human approval, everything is audit-logged, and flagged content goes to a moderation review queue.",
    ],
    citations: [
      { label: "OWASP Top 10 for Large Language Model Applications", href: "https://owasp.org/www-project-top-10-for-large-language-model-applications/" },
      { label: "NIST AI Risk Management Framework", href: "https://www.nist.gov/itl/ai-risk-management-framework" },
    ],
    name: "AI Guardrails",
    metaTitle: "AI Guardrails: PII, Toxicity & Approval Gates | VegaDūta",
    metaDescription:
      "VegaDūta ships guardrails as a platform layer: PII, toxicity, profanity and jailbreak detection, a configurable rule engine, review queues, and approval gates.",
    h1: "AI Guardrails on VegaDūta",
    kicker: "AI · Safety",
    cardBlurb:
      "Detection for PII, toxicity, and jailbreaks, a configurable rule engine, and human approval gates on sensitive actions.",
    intro: [
      "Guardrails on VegaDūta are a platform layer, not something you prompt into each agent and hope holds. Built-in detectors cover PII, toxicity, profanity, jailbreak attempts, and policy violations; a configurable rule engine and an optional external safety classifier extend them; and human approval gates sit in front of sensitive tool calls so an agent asks before it acts.",
      "Everything is audit-logged, and flagged content lands in a moderation review queue where a person makes the final call.",
    ],
    sections: [
      {
        heading: "Detection built in",
        body: "Agent traffic passes through detectors for personally identifiable information, toxicity, profanity, jailbreak attempts, and policy violations. You do not assemble these from scratch per agent — they are platform capabilities you configure.",
        bullets: [
          "PII detection to keep personal data from leaking through agent responses",
          "Toxicity and profanity screening on conversations",
          "Jailbreak detection for prompt-injection and manipulation attempts",
          "Policy violation rules for tenant-specific red lines",
        ],
      },
      {
        heading: "Configurable rules and an external classifier",
        body: "The rule engine lets you define what counts as a violation for your business, beyond the stock detectors. An external safety classifier can be plugged in as an additional judgment layer when the built-in checks are not enough on their own.",
      },
      {
        heading: "Humans stay in the loop",
        body: "Sensitive tool calls can require explicit human approval: the agent proposes, a person disposes. Built-in tools classified as sensitive wait for confirmation by default, even on an agent set to act autonomously. Approvers can apply policy overrides where appropriate and retry actions that failed, and flagged content flows into a moderation review queue rather than being silently dropped. Every decision is captured in the audit log.",
      },
    ],
    faqs: [
      {
        q: "What guardrails does VegaDūta include out of the box?",
        a: "PII detection, toxicity screening, profanity filtering, jailbreak detection, and policy violation rules, plus a configurable rule engine to add your own. An external safety classifier can be layered on top, and a moderation review queue holds flagged content for human review.",
      },
      {
        q: "Can I require approval before an agent takes an action?",
        a: "Yes. Sensitive tool calls can be gated behind human approval gates — the agent requests the action and waits for a person to approve or reject it. Approvers can also apply policy overrides and retry failed actions.",
      },
      {
        q: "How do I know what my agents have been doing?",
        a: "Guardrail decisions and approvals are audit-logged, and the platform's Langfuse-based tracing records agent runs end to end. Between the two you can reconstruct what an agent did, what was blocked, and who approved what.",
      },
      {
        q: "Can I customize the rules for my business?",
        a: "Yes. The rule engine is configurable per tenant, so you define policy violations that match your domain on top of the stock detectors. Approval gates are likewise configured per tool, so you choose which actions need a human sign-off.",
      },
    ],
    related: [
      { href: "/ai/agents", label: "AI Agents on VegaDūta" },
      { href: "/platform/governance", label: "Governance and cost controls" },
      { href: "/platform/observability", label: "Observability and tracing" },
      { href: "/platform/security", label: "Platform security" },
      { href: "/why-vegaduta", label: "Why VegaDūta" },
    ],
  },
  {
    slug: "graph-memory",
    name: "Graph Memory",
    metaTitle: "Graph Memory: Long-Term AI Agent Memory | VegaDūta",
    metaDescription:
      "VegaDūta gives agents long-term memory: facts from conversations stored as a knowledge graph, recalled as a tool, and a Memory page where you edit or delete them.",
    h1: "Graph Memory on VegaDūta",
    kicker: "AI · Memory",
    cardBlurb:
      "Agents remember across conversations, and a Memory page lets you read, edit, and delete what they remember.",
    intro: [
      "Graph memory is how a VegaDūta agent remembers you next week. Instead of stuffing past transcripts into the prompt, the platform extracts entities and facts from conversations into a knowledge graph — subject-relation-object triples — and agents recall from that graph as a tool when a conversation needs it.",
      "What is remembered is yours to inspect. The Memory page in the console lists what your assistants have saved about you, and every entry can be edited or deleted. An optional tenant-shared graph lets all of a team's agents draw on the same pool of facts.",
    ],
    sections: [
      {
        heading: "Conversations become facts",
        body: "As agents talk, an extraction step identifies entities and the relationships between them and writes them into the graph as triples. A customer's preferences, an order's status, a project's owner — these become structured facts rather than sentences buried in a transcript.",
      },
      {
        heading: "Recall is a tool, not a prompt dump",
        body: "When an agent needs context, it queries the graph through a recall tool and gets back the relevant facts. This keeps prompts small and answers grounded: the agent retrieves the three facts that matter instead of rereading every previous conversation.",
      },
      {
        heading: "A Memory page you control",
        body: "The Memory page shows what your assistants remember about you, and everything on it can be read, edited, and deleted. It has three parts: short notes that are added to conversations with agents that have shared memory turned on (how you want to be spoken to, who you are, what you are working on), a note per project that is used only when your message mentions that project's name, and the facts assistants saved during conversations, each marked as shared or learned by one agent.",
        bullets: [
          "Edit a remembered fact that is wrong, or delete it; deletion cannot be undone",
          "Notes left empty are left out of conversations",
          "A workspace can turn long-term memory off entirely",
        ],
      },
      {
        heading: "Shared memory for teams",
        body: "Optionally, a tenant can enable a shared graph so facts learned by one agent are recallable by the others. Your sales agent and your support agent then agree on who the customer is and what was promised, instead of each keeping a private, diverging picture.",
      },
    ],
    faqs: [
      {
        q: "How does an AI agent remember past conversations?",
        a: "On VegaDūta, facts are extracted from conversations into a knowledge graph as entity-relation triples. In later conversations the agent queries that graph through a recall tool, retrieving just the relevant facts instead of replaying old transcripts.",
      },
      {
        q: "What is graph memory, compared to a vector database?",
        a: "A vector database retrieves chunks of text that are semantically similar to a query; a knowledge graph stores discrete facts and their relationships. VegaDūta uses both — RAG over your documents for reference material, graph memory for structured facts about people, things, and commitments learned in conversation.",
      },
      {
        q: "Can multiple agents share the same memory?",
        a: "Yes, optionally. A tenant-shared graph lets all agents on a team read the same facts, so knowledge learned by one agent is available to the rest. It is a per-tenant setting, not the default.",
      },
      {
        q: "Can I see and delete what an agent remembers about me?",
        a: "Yes. The Memory page in the console lists the notes and facts your assistants hold about you. Each one can be edited or deleted, and a deleted memory is gone for good.",
      },
    ],
    related: [
      { href: "/ai/rag", label: "Knowledge & RAG" },
      { href: "/ai/agents", label: "AI Agents on VegaDūta" },
      { href: "/tutorials/build-your-first-agent", label: "Tutorial: build your first agent" },
      { href: "/platform/security", label: "Platform security" },
    ],
  },
  {
    slug: "voice-ai",
    name: "Voice AI",
    metaTitle: "Voice AI Agents: Phone Calls, TTS & STT | VegaDūta",
    metaDescription:
      "VegaDūta agents speak and listen: Whisper or Sarvam speech-to-text, ElevenLabs or Sarvam voices, inbound Twilio phone calls with DTMF, and LiveKit in-app voice.",
    h1: "Voice AI on VegaDūta",
    kicker: "AI · Voice",
    cardBlurb:
      "Agents that answer the phone: Whisper and Sarvam transcription, ElevenLabs and Sarvam voices, Twilio calls, LiveKit rooms.",
    intro: [
      "VegaDūta agents can hold a spoken conversation, not just a typed one. Speech-to-text runs on locally-hosted Whisper (faster-whisper) or Sarvam; text-to-speech uses ElevenLabs or Sarvam voices; and the same agent that answers your WhatsApp can answer an inbound phone call via Twilio, with a real-time relay and DTMF keypad menus.",
      "For voice inside your own app or the browser, LiveKit rooms carry the audio with call recording. Voice is both a channel an agent lives on and a trigger that can start a workflow.",
    ],
    sections: [
      {
        heading: "The speech stack",
        body: "Each direction of the conversation has a choice of engines, including Sarvam for Indian-language speech-to-text and for voice replies in the console, in-app and voice notes. Phone calls through Twilio use Twilio's relay speech with its default language. Whisper transcription runs locally on the platform via faster-whisper rather than as a third-party API call.",
        bullets: [
          "Speech-to-text: locally-run Whisper (faster-whisper) or Sarvam",
          "Text-to-speech: ElevenLabs or Sarvam voices",
          "Speak is an agent tool, so any agent can produce audio replies",
        ],
      },
      {
        heading: "Real phone calls",
        body: "Inbound calls arrive through Twilio and connect to your agent over a real-time relay, so the caller talks to the agent live rather than leaving a message for later processing. DTMF menus let callers navigate with the keypad — press 1 for orders, press 2 for support — before or during the agent conversation.",
      },
      {
        heading: "Voice in the browser and your app",
        body: "LiveKit rooms provide browser and in-app voice sessions with call recording, and a public agent share link can carry a live voice conversation — someone opens the link and talks to your agent, no phone number involved. To be precise about scope: an embeddable website chat/voice widget is not built yet; share links are how you put an agent on the open web today.",
      },
      {
        heading: "Voice as a trigger",
        body: "Voice is not just an input method; it is a channel and a workflow trigger. An inbound call can start a workflow the same way an inbound WhatsApp message can, so 'customer called' becomes an event your automation reacts to. Inside a workflow, a voice menu node presents keypad options to the caller and branches on the key pressed.",
      },
    ],
    faqs: [
      {
        q: "Can an AI agent answer phone calls?",
        a: "Yes. Inbound calls route through Twilio to your VegaDūta agent over a real-time relay, so the caller has a live spoken conversation. DTMF keypad menus are supported for call routing.",
      },
      {
        q: "What does VegaDūta use for speech-to-text and text-to-speech?",
        a: "Speech-to-text runs on locally-hosted Whisper (faster-whisper) or Sarvam; text-to-speech uses ElevenLabs or Sarvam voices. Sarvam speech-to-text handles Indian-language audio in the console, in-app and voice notes, and the agent's speak tool can request a language. Phone calls through Twilio use Twilio's relay speech with its default language, so Sarvam and ElevenLabs voices are not selectable there today.",
      },
      {
        q: "Can I add voice to my own website or app?",
        a: "Yes, through LiveKit rooms for browser and in-app voice, with call recording included. No phone number is needed for this path — audio flows over the web session directly to the agent.",
      },
      {
        q: "Can a phone call trigger an automation?",
        a: "Yes. Voice is a workflow trigger as well as a channel, so an inbound call can start a workflow — logging the call, creating a follow-up task, or handing off to another agent.",
      },
    ],
    related: [
      { href: "/tutorials/setup-voice-agent", label: "Tutorial: set up a voice agent" },
      { href: "/integrations/elevenlabs", label: "ElevenLabs integration" },
      { href: "/integrations/sarvam-ai", label: "Sarvam AI integration" },
      { href: "/platform/workflow-automation", label: "Workflow automation" },
      { href: "/ai/agents", label: "AI Agents on VegaDūta" },
    ],
  },
  {
    slug: "edge-ai",
    name: "Edge AI",
    metaTitle: "Edge AI: In-Browser LLMs & Device Agents | VegaDūta",
    metaDescription:
      "VegaDūta runs models on-device: in-browser LLMs via WebLLM with a model marketplace, plus paired desktop and mobile companion agents with device tools.",
    h1: "Edge AI on VegaDūta",
    kicker: "AI · Edge",
    cardBlurb:
      "Run LLMs in the browser via WebLLM with a model marketplace, and pair desktop or mobile devices as companion agents.",
    intro: [
      "Edge AI on VegaDūta means running models on the user's own device. In-browser LLMs run via WebLLM — a model marketplace lets you browse, download, and manage installed models, and inference then happens locally in the browser rather than on a server.",
      "Alongside that, device pairing connects desktop and mobile companion agents to the platform, giving agents device tools such as notifications and screen reading. To be precise about the term: 'edge' here means on-device and in-browser models plus paired devices, not edge-datacenter compute.",
    ],
    sections: [
      {
        heading: "LLMs in the browser",
        body: "WebLLM runs open models directly in the browser using the device's own hardware. Once a model is downloaded, inference happens locally — useful when connectivity is unreliable or when the text being processed should not leave the machine.",
      },
      {
        heading: "A marketplace for edge models",
        body: "The model marketplace is the management layer for on-device AI: browse a catalog of 28 in-browser models, download the ones that fit your hardware, and manage what is installed. It turns 'run a local model' from a developer exercise into a click-through experience.",
      },
      {
        heading: "Paired devices as companions",
        body: "Desktop and mobile devices pair with the platform as companion agents, extending what your agents can reach. Device tools such as notifications and screen reading let an agent surface information on the device you are actually looking at, rather than only in a chat window.",
      },
      {
        heading: "What 'edge' means here — honestly",
        body: "This capability is about on-device and in-browser models and paired companion devices. It is not edge-datacenter compute or a CDN-adjacent inference network. If you need heavier models than a browser can hold, the LLM connectors — including self-hosted Ollama — are the right tool.",
      },
    ],
    faqs: [
      {
        q: "Can I run an LLM entirely in my browser?",
        a: "Yes. VegaDūta uses WebLLM to run models in-browser on your own hardware: browse the model marketplace, download a model, and inference runs locally on the device. Text processed this way is not sent to a server for generation.",
      },
      {
        q: "How do I choose and install an edge model?",
        a: "Through the model marketplace: browse the catalog, download a model suited to your device, and manage your installed models from the same place. Larger models need more memory, so the practical choice depends on your hardware.",
      },
      {
        q: "What can a paired device do?",
        a: "Device pairing connects a desktop or mobile companion agent to the platform, and agents gain device tools such as sending notifications and screen reading. It extends agents from the chat window onto the devices you actually use.",
      },
      {
        q: "Is this the same as edge computing in a datacenter?",
        a: "No. On VegaDūta, 'edge' means on-device: in-browser models via WebLLM and paired desktop or mobile devices. It is not edge-datacenter or CDN-based inference — for heavier self-hosted models, use the Ollama LLM connector instead.",
      },
    ],
    related: [
      { href: "/ai/llm-connectors", label: "LLM connectors" },
      { href: "/ai/agents", label: "AI Agents on VegaDūta" },
      { href: "/try", label: "Try it without signing up" },
      { href: "/why-vegaduta", label: "Why VegaDūta" },
    ],
  },
];
