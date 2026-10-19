// /integrations hub data: voice, workspace, code and CRM integrations. Re-exported in order by integrations-data.ts.

import type { HubPage } from "../lib/seo-hub";

export const SERVICE_INTEGRATIONS: HubPage[] = [
  {
    slug: "sarvam-ai",
    name: "Sarvam AI",
    metaTitle: "Sarvam AI Integration — Indian Language Agents | VegaDūta",
    metaDescription:
      "VegaDūta's Sarvam AI integration gives agents Indian-language speech-to-text, text-to-speech, translation, transliteration, and Sarvam chat models as LLMs.",
    h1: "Sarvam AI on VegaDūta: Indian-language agents",
    kicker: "Integrations · Voice & language AI",
    cardBlurb:
      "Sarvam speech-to-text, text-to-speech, translation, and transliteration as agent tools, plus Sarvam LLMs.",
    intro: [
      "VegaDūta integrates Sarvam AI across the full surface of its API: speech-to-text, text-to-speech with the bulbul model, translation, transliteration, and language identification — all exposed to agents as callable tools — plus Sarvam's chat models (such as sarvam-105b), which you can select as an agent's LLM. If you are searching for how to build Sarvam-powered agents or Indian-language AI agents, this is that page: one platform where Sarvam handles both the language layer and, if you choose, the reasoning layer.",
      "Every Sarvam call is logged with per-call records and governed by quotas, and the whole capability switches on per agent — so you decide which of your agents get Sarvam tools.",
    ],
    sections: [
      {
        heading: "What agents get from Sarvam",
        body: "The integration is not a single endpoint bolted on — it covers Sarvam's language capabilities and makes each one an agent tool the model can call mid-conversation.",
        bullets: [
          "Speech-to-text for Indian-language audio.",
          "Text-to-speech via the bulbul model for spoken replies.",
          "Translation across Indian languages.",
          "Transliteration between scripts.",
          "Language identification for routing and reply-language decisions.",
          "Sarvam chat models (e.g. sarvam-105b) selectable as an agent's LLM.",
        ],
      },
      {
        heading: "Sarvam models as the agent's brain",
        body: "Beyond tools, you can pick a Sarvam chat model as the LLM an agent runs on — useful when you want Indian-language reasoning end to end rather than a Western model with translation bolted on. Model selection lives in the agent builder alongside every other connected provider.",
      },
      {
        heading: "Governance: logging, quotas, per-agent control",
        body: "Every Sarvam call is logged per invocation and counted against quotas, and the integration has a per-agent enable toggle. That means costs are visible, limits are enforceable, and an agent only carries Sarvam tools if you switched them on for it.",
      },
      {
        heading: "Why this matters for Indian-language agents",
        body: "How well an agent handles a language depends on its model; Sarvam adds speech and script handling built for India. A WhatsApp voice note in Kannada can be transcribed, understood, and answered — in text or speech — inside one conversation.",
      },
    ],
    faqs: [
      {
        q: "Can I build an AI agent that speaks Indian languages?",
        a: "Yes. The Sarvam AI integration adds speech-to-text, text-to-speech (bulbul), translation, transliteration, and language identification as agent tools. You can also run the agent itself on a Sarvam chat model.",
      },
      {
        q: "Can I use Sarvam's LLM as my agent's model?",
        a: "Yes. Sarvam chat models such as sarvam-105b are selectable as an agent's LLM in the agent builder, the same way you would pick any other connected provider's model.",
      },
      {
        q: "How is Sarvam usage controlled and billed for visibility?",
        a: "Every Sarvam call is logged per invocation and governed by quotas, and the capability has a per-agent enable toggle. For plan-level details, see current pricing on the billing page.",
      },
      {
        q: "What Sarvam capabilities does VegaDūta expose as tools?",
        a: "Transcribe (speech-to-text), text-to-speech via the bulbul model, translate, transliterate, and identify-language — each callable by the agent mid-conversation when the toggle is on for that agent.",
      },
    ],
    related: [
      { href: "/ai/voice-ai", label: "Voice AI on VegaDūta" },
      { href: "/integrations/elevenlabs", label: "ElevenLabs integration" },
      { href: "/integrations/whatsapp", label: "WhatsApp integration" },
      { href: "/tutorials/setup-voice-agent", label: "Tutorial: set up a voice agent" },
      { href: "/billing", label: "Current pricing" },
    ],
  },
  {
    slug: "elevenlabs",
    name: "ElevenLabs",
    metaTitle: "ElevenLabs TTS & Voice Cloning Integration | VegaDūta",
    metaDescription:
      "VegaDūta uses ElevenLabs for agent voice replies: multilingual text-to-speech and instant voice cloning with per-tenant voice libraries and usage quotas.",
    h1: "ElevenLabs on VegaDūta: voice for your agents",
    kicker: "Integrations · Voice & language AI",
    cardBlurb:
      "Multilingual ElevenLabs TTS and instant voice cloning give agents a voice — with an honest scope note.",
    intro: [
      "VegaDūta integrates ElevenLabs for agent voice replies: multilingual text-to-speech turns an agent's answer into speech, and instant voice cloning lets you create custom voices stored in a per-tenant voice library. Usage is governed by quotas, and the capability is currently in beta. To be precise about scope: VegaDūta uses ElevenLabs for TTS and voice cloning only — it does not use ElevenLabs' Conversational AI agents. The conversation intelligence is VegaDūta's own agent runtime; ElevenLabs supplies the voice.",
    ],
    sections: [
      {
        heading: "What the integration provides",
        body: "Two ElevenLabs capabilities are wired in, both in service of giving your agent a voice on channels where spoken replies make sense.",
        bullets: [
          "Text-to-speech with ElevenLabs' multilingual model for agent voice replies.",
          "Instant voice cloning — create a custom voice and reuse it.",
          "A per-tenant voice library, isolated per workspace.",
          "Usage quotas on voice generation.",
        ],
      },
      {
        heading: "Honest scope: TTS and cloning, not ElevenLabs agents",
        body: "ElevenLabs also sells a Conversational AI agent product; VegaDūta does not use it. Your agent's reasoning, tools, guardrails, and memory are all VegaDūta's runtime — ElevenLabs is the speech synthesis layer on top. If you specifically want ElevenLabs' own conversational agents, this integration is not that, and we would rather say so here.",
      },
      {
        heading: "Where agent voice fits",
        body: "Voice replies matter on channels where users speak rather than type, and for accessibility. Paired with the Sarvam integration's speech-to-text, an agent can hear a voice message, reason over it, and answer aloud — a full voice loop with each layer handled by the provider best at it. The capability is in beta, so expect it to keep evolving.",
      },
    ],
    faqs: [
      {
        q: "Can my AI agent reply with a voice?",
        a: "Yes. VegaDūta uses ElevenLabs' multilingual text-to-speech to turn agent replies into speech, using a voice from your tenant's voice library. The capability is currently in beta.",
      },
      {
        q: "Can I clone a voice for my agent?",
        a: "Yes. The integration supports ElevenLabs instant voice cloning: you create a custom voice, it is stored in your per-tenant voice library, and your agents can speak with it. Voice generation is governed by quotas.",
      },
      {
        q: "Does VegaDūta use ElevenLabs Conversational AI agents?",
        a: "No. VegaDūta uses ElevenLabs for text-to-speech and voice cloning only. The conversational intelligence — reasoning, tools, guardrails — is VegaDūta's own agent runtime, with ElevenLabs providing the voice output.",
      },
      {
        q: "Are cloned voices shared between customers?",
        a: "No. Voices live in a per-tenant voice library, consistent with the platform's schema-per-tenant isolation — your workspace's voices are yours alone.",
      },
    ],
    related: [
      { href: "/ai/voice-ai", label: "Voice AI on VegaDūta" },
      { href: "/integrations/sarvam-ai", label: "Sarvam AI integration" },
      { href: "/tutorials/setup-voice-agent", label: "Tutorial: set up a voice agent" },
      { href: "/platform/security", label: "Security & tenant isolation" },
    ],
  },
  {
    slug: "google-workspace",
    name: "Google Workspace",
    metaTitle: "Google Workspace AI Agent Tools | VegaDūta",
    metaDescription:
      "Per-tenant Google OAuth gives VegaDūta agents tools for Gmail, Google Calendar, Drive, Sheets, and YouTube Analytics — acting across your workspace safely.",
    h1: "Google Workspace tools for VegaDūta agents",
    kicker: "Integrations · Productivity",
    cardBlurb:
      "One OAuth connection gives agents Gmail, Calendar, Drive, Sheets, and YouTube Analytics tools.",
    intro: [
      "VegaDūta connects to Google Workspace through per-tenant Google OAuth: you authorize your Google account once, and your agents gain tools for Gmail, Google Calendar, Google Drive, Google Sheets, and YouTube Analytics. That means an agent in a WhatsApp or Slack conversation can check a calendar, read or send mail, pull a document, update a sheet, or report on channel analytics as steps in its work.",
    ],
    sections: [
      {
        heading: "The five tool surfaces",
        body: "One OAuth grant unlocks a set of agent tools across the Google products teams actually run on.",
        bullets: [
          "Gmail — read and send mail as part of a conversation or workflow.",
          "Google Calendar — check availability and manage events.",
          "Google Drive — access documents and files.",
          "Google Sheets — read and update spreadsheet data.",
          "YouTube Analytics — pull channel performance data.",
        ],
      },
      {
        heading: "Per-tenant OAuth, not shared credentials",
        body: "Each tenant authorizes its own Google account through OAuth; credentials are stored per tenant with the platform's schema-per-tenant isolation. Nothing is shared across workspaces, and you can see exactly which account each agent acts as.",
      },
      {
        heading: "Guardrails on real-world actions",
        body: "Sending email or editing a sheet is a real-world side effect, so the platform's controls apply: guardrails screen the content, and human approval gates can require a person's sign-off before a tool call executes. You choose which Google actions an agent may take autonomously.",
      },
    ],
    faqs: [
      {
        q: "Can an AI agent read and send Gmail?",
        a: "Yes. After you connect Google via per-tenant OAuth, agents get Gmail tools for reading and sending mail. You can put those actions behind human approval gates so nothing sends without sign-off if you prefer.",
      },
      {
        q: "How does VegaDūta authenticate with Google?",
        a: "Through per-tenant Google OAuth: you authorize your own Google account from the console, and the resulting credentials are stored for your tenant only. No passwords are shared, and other tenants never touch your grant.",
      },
      {
        q: "Which Google products do agents get tools for?",
        a: "Gmail, Google Calendar, Google Drive, Google Sheets, and YouTube Analytics. Each becomes a callable agent tool once the OAuth connection is in place.",
      },
      {
        q: "Can I control what the agent is allowed to do in my Google account?",
        a: "Yes. Tool calls run under the platform's guardrails, and you can require human approval before sensitive actions like sending mail or editing data. The agent only has the tools you have enabled for it.",
      },
    ],
    related: [
      { href: "/integrations/email", label: "Email channel integration" },
      { href: "/ai/agents", label: "How VegaDūta agents work" },
      { href: "/ai/guardrails", label: "Guardrails" },
      { href: "/platform/workflow-automation", label: "Workflow automation" },
    ],
  },
  {
    slug: "atlassian",
    name: "Jira & Confluence",
    metaTitle: "Jira & Confluence AI Agent Integration | VegaDūta",
    metaDescription:
      "Connect Jira and Confluence to your AI agents in one click. VegaDūta speaks OAuth 2.1 to Atlassian's official Remote MCP server — no API token, no app to register.",
    h1: "Jira and Confluence agents on VegaDūta",
    kicker: "Integrations · Business systems",
    cardBlurb:
      "One-click OAuth connection to Atlassian's official Remote MCP server — Jira and Confluence become agent tools.",
    intro: [
      "VegaDūta connects to Jira and Confluence through Atlassian's own Remote MCP server, and the connection is genuinely one click: you pick Atlassian from the connector catalog in the console, approve the consent screen on Atlassian's side, and come back to a working connection. There is no API token to copy, and — unlike most OAuth integrations on this platform — no Atlassian app for you to register first, because VegaDūta registers a single platform-wide OAuth client via Dynamic Client Registration and each tenant authorizes against it.",
      "Once connected, whatever Atlassian's server exposes becomes tools your agents can call: look up an issue mid-conversation, read a Confluence page as context, file a ticket as a workflow step. The connection is per-tenant, and you can disconnect it at any time from the same screen.",
    ],
    sections: [
      {
        heading: "One click, no token, no app registration",
        body:
          "Most business-system integrations start with a scavenger hunt: create an app, copy a client secret, paste an API token. This one does not. VegaDūta holds one OAuth client registered with Atlassian through Dynamic Client Registration, and your tenant authorizes against it with Authorization Code + PKCE. You click Connect, consent on Atlassian's screen, and you are done.",
        bullets: [
          "OAuth 2.1 Authorization Code + PKCE against Atlassian's official Remote MCP server",
          "No per-tenant Atlassian app to register and no API token to store",
          "The OAuth state is AES-256-GCM encrypted, so a tampered redirect simply fails to decrypt",
          "Disconnect from the same screen — the connection is per-tenant and revocable",
        ],
      },
      {
        heading: "No long-lived token sitting in a database",
        body:
          "This connector deliberately does not store a static authorization header the way a hand-registered MCP server does. The access token is minted fresh on each real connection attempt from the tenant's stored grant, which is encrypted at rest. That keeps the blast radius of a database read smaller than a plaintext-token integration, and means an expired token refreshes on the platform's schedule rather than failing in the middle of a customer conversation.",
      },
      {
        heading: "What an Atlassian-connected agent can do",
        body:
          "The tool surface is whatever Atlassian's Remote MCP server publishes for your account — typically searching and reading Jira issues and Confluence pages, and creating or updating them. Your agents call those tools inside the same conversations and workflows as everything else, so a WhatsApp message can become a Jira ticket, or a workflow step can post a Confluence update after a run completes.",
      },
      {
        heading: "Governed like every other connector",
        body:
          "Being first-party does not exempt it from the platform's controls. Tool calls run under the same guardrails (PII, toxicity, jailbreak detection), human approval gates can require a person to sign off before an agent writes to your tracker, and every decision lands in the tenant-scoped audit log. Which agents get Atlassian tools at all is your choice — capabilities are per-agent, not global.",
      },
    ],
    faqs: [
      {
        q: "How do I connect Jira and Confluence to a VegaDūta AI agent?",
        a: "Open the connector catalog in the console, choose Atlassian, and approve the consent screen. VegaDūta uses OAuth 2.1 (Authorization Code + PKCE) against Atlassian's official Remote MCP server, so there is no API token to generate and no Atlassian app for you to register beforehand.",
      },
      {
        q: "Does VegaDūta store my Atlassian API token?",
        a: "No — there is no API token in this flow at all. The tenant's OAuth grant is encrypted at rest and an access token is minted fresh for each connection attempt rather than being stored as a static authorization header.",
      },
      {
        q: "What can an agent do in Jira or Confluence?",
        a: "Whatever Atlassian's Remote MCP server exposes for your account — typically searching and reading issues and pages, and creating or updating them. Those become callable agent tools, usable mid-conversation on any channel or as a workflow step.",
      },
      {
        q: "Can I stop an agent from writing to Jira without my say-so?",
        a: "Yes. Human approval gates can require a person to approve a tool call before it executes, guardrails run on the traffic either way, and the decision is audit-logged. You also control which agents have the Atlassian tools enabled at all.",
      },
      {
        q: "Can I disconnect Atlassian later?",
        a: "Yes. The connection is per-tenant and revocable from the same screen you connected it on; disconnecting removes the stored grant so agents lose the tools immediately.",
      },
    ],
    related: [
      { href: "/ai/mcp", label: "Model Context Protocol on VegaDūta" },
      { href: "/tutorials/connect-mcp-server", label: "Tutorial: connect an MCP server" },
      { href: "/integrations/crm", label: "Connecting a CRM" },
      { href: "/platform/security", label: "Security & tenant isolation" },
      { href: "/ai/guardrails", label: "Guardrails" },
    ],
  },
  {
    slug: "e2b",
    name: "E2B Code Execution",
    metaTitle: "E2B Sandboxed Code Execution for Agents | VegaDūta",
    metaDescription:
      "VegaDūta runs agent-written code in sandboxed E2B cloud environments — isolated from the platform, toggled per agent, using the E2B key your workspace connects.",
    h1: "E2B code execution for VegaDūta agents",
    kicker: "Integrations · Developer tools",
    cardBlurb:
      "Agents run code in isolated E2B cloud sandboxes — per-agent toggle, bring your own E2B key.",
    intro: [
      "VegaDūta integrates E2B to let agents execute code safely: when an agent needs to run code, it runs inside a sandboxed E2B cloud environment, completely outside the VegaDūta platform itself. The capability is switched on per agent and runs on the E2B key your workspace connects on the Providers page — an E2B API key plus the id of a code-execution template. The result is an agent that can compute, transform data, and verify its own work without any code ever touching platform infrastructure.",
    ],
    sections: [
      {
        heading: "Why sandboxed execution matters",
        body: "Letting a language model run code is powerful and dangerous in equal measure. E2B's cloud sandboxes give each execution an isolated environment away from the platform and away from your data stores — if generated code misbehaves, it misbehaves inside a disposable sandbox.",
        bullets: [
          "Execution happens in E2B's cloud sandboxes, outside the platform.",
          "Per-agent enable toggle — only agents you choose can run code.",
          "Bring your own E2B key and code-execution template id, connected once per workspace on the Providers page.",
        ],
      },
      {
        heading: "What agents use it for",
        body: "Anything where reasoning alone is not enough: calculations that must be exact, data transformations, generating and checking output programmatically. The agent writes the code, the sandbox runs it, and the result comes back into the conversation or workflow. Where a deployment has the data-analysis option switched on (it is off by default), files attached in chat — CSV, XLSX, JSON, PDF or TXT — are placed in the sandbox for the code to read, and the charts, workbooks and scripts it saves come back as downloads.",
      },
      {
        heading: "Control and governance",
        body: "Code execution is off by default and enabled per agent, so the capability exists only where you have deliberately granted it. Like every tool call, executions run under the platform's guardrails and can sit behind human approval gates.",
      },
    ],
    faqs: [
      {
        q: "Can a VegaDūta agent run code?",
        a: "Yes, if you enable it for that agent. Code executes inside a sandboxed E2B cloud environment, isolated from the VegaDūta platform, and the result returns to the agent's conversation or workflow.",
      },
      {
        q: "Do I need my own E2B account or API key?",
        a: "Yes. Connect an E2B API key and a code-execution template id on the Providers page, then switch Code execution on for each agent that should have it.",
      },
      {
        q: "Is agent code execution safe?",
        a: "Execution is sandboxed in E2B's cloud, entirely outside the platform, so generated code never runs on VegaDūta infrastructure or near your tenant data. The capability is per-agent opt-in and subject to the platform's guardrails.",
      },
      {
        q: "Which agents can execute code?",
        a: "Only the ones you have toggled it on for. E2B execution is a per-agent setting, so you can give a data-analysis agent the capability while your customer-facing WhatsApp agent goes without.",
      },
    ],
    related: [
      { href: "/platform/agent-builder", label: "Agent builder" },
      { href: "/ai/agents", label: "How VegaDūta agents work" },
      { href: "/ai/guardrails", label: "Guardrails" },
      { href: "/platform/security", label: "Security & tenant isolation" },
    ],
  },
  {
    slug: "crm",
    name: "CRM Systems",
    metaTitle: "Connect Your CRM to an AI Agent via MCP | VegaDūta",
    metaDescription:
      "VegaDūta has a built-in leads list but no native Salesforce or HubSpot connector. Connect any CRM that exposes an MCP server through the registry.",
    h1: "Connecting your CRM to an AI agent",
    kicker: "Integrations · Business systems",
    cardBlurb:
      "A built-in leads list, and no native connector for outside CRMs: any CRM with an MCP server connects through the registry.",
    intro: [
      "Straight answer first: VegaDūta does not ship a native connector for Salesforce, HubSpot, Zoho, or any other specific CRM. What it does have is a general-purpose path that covers all of them — the Model Context Protocol connector registry. Any CRM that has an MCP server, or can host one, connects to your agents through it: browse the official MCP registry from the console, or register a custom server with encrypted credentials. Once connected, the CRM's operations become tools your agent can call in a conversation or workflow.",
      "If you do not have a CRM yet, the platform has a small one built in: a leads list that workflows can write to. It is a place to track leads, not a replacement for a full CRM suite. We would rather tell you both things plainly than list CRM logos we cannot back up.",
    ],
    sections: [
      {
        heading: "How the MCP route works",
        body: "MCP is the open protocol that lets AI systems call external tools. If your CRM vendor publishes an MCP server — a growing number do — or you host one against your CRM's API, VegaDūta connects to it like any other MCP server.",
        bullets: [
          "Browse the official MCP registry directly from the console.",
          "Register a custom MCP server for a CRM not in the registry.",
          "Server credentials are stored encrypted.",
          "Connected servers pass admin review before agents can use them.",
        ],
      },
      {
        heading: "The built-in leads list",
        body: "The admin console includes a leads list. A CRM lead node in a workflow creates or updates a lead, matched by email address, and each lead moves through stages: new, contacted, replied, qualified, proposal, won, or lost. The bulk email node sends personalised outreach with a recipient cap, a send-rate limit, and a suppression list. When a recipient replies, the reply is recorded on the lead and the lead moves to the replied stage; bounces are detected as well. Leads can be imported and exported as CSV.",
      },
      {
        heading: "What a CRM-connected agent can do",
        body: "Whatever the MCP server exposes: look up a customer record mid-conversation on WhatsApp, create a lead from an Instagram DM, update a deal as a workflow step. The agent calls the CRM's tools the same way it calls any other tool, inside the same conversations and workflows.",
      },
      {
        heading: "Governance: review, guardrails, approvals",
        body: "Third-party servers do not get a free pass. Registered MCP servers go through admin review, tool calls run under the platform's guardrails (PII, toxicity, jailbreak detection), and human approval gates can require sign-off before an agent writes to your CRM. Credentials stay encrypted at rest.",
      },
      {
        heading: "What to check before you connect",
        body: "Two questions decide feasibility: does your CRM have an MCP server (check the registry from the console), and if not, can you host one against its API? If the answer to both is no, VegaDūta cannot connect to that CRM today — that is the honest boundary of this integration.",
      },
    ],
    faqs: [
      {
        q: "Does VegaDūta integrate with Salesforce, HubSpot, or Zoho?",
        a: "Not natively — there is no built-in connector for any specific outside CRM, and we say that plainly. If the CRM has or can host an MCP server, it connects through VegaDūta's MCP connector registry, and its operations become agent tools. A simple built-in leads list is available if you do not use an outside CRM.",
      },
      {
        q: "How do I connect my CRM to an AI agent?",
        a: "Through the Model Context Protocol: browse the official MCP registry from the VegaDūta console for an existing server, or register a custom MCP server pointed at your CRM's API. Credentials are stored encrypted, and the server passes admin review before agents can use it.",
      },
      {
        q: "What can an agent do once my CRM is connected?",
        a: "Whatever tools the MCP server exposes — typically reading records, creating leads, and updating fields. The agent calls them mid-conversation or as workflow steps, with guardrails and optional human approval gates on writes.",
      },
      {
        q: "Is it safe to give an AI agent access to CRM data?",
        a: "The platform is built to make it governable: encrypted credential storage, admin review of registered servers, guardrails including PII detection on tool traffic, and approval gates that put a human before sensitive actions. You decide which agents get CRM tools at all.",
      },
    ],
    related: [
      { href: "/ai/mcp", label: "Model Context Protocol on VegaDūta" },
      { href: "/tutorials/connect-mcp-server", label: "Tutorial: connect an MCP server" },
      { href: "/ai/guardrails", label: "Guardrails" },
      { href: "/platform/workflow-automation", label: "Workflow automation" },
      { href: "/integrations/atlassian", label: "Jira & Confluence integration" },
    ],
  },
];
