// /platform hub data: build and integrate topics (workflows, agent builder, webhooks, APIs, security, observability). Re-exported in order by platform-data.ts.

import type { HubPage } from "../lib/seo-hub";

export const BUILD_TOPICS: HubPage[] = [
  {
    slug: "workflow-automation",
    name: "Workflow Automation",
    metaTitle: "AI Workflow Automation Platform | VegaDūta",
    metaDescription:
      "VegaDūta workflows: 35 node types, 11 trigger channels, an expression language, data pinning, draft and production runs, approval gates, and 240+ recipes.",
    h1: "Workflow automation with humans in the loop",
    kicker: "Platform · Automation",
    cardBlurb:
      "AI workflows built from 35 node types, started from 11 trigger channels, with approval gates, data pinning, and draft runs.",
    intro: [
      "A VegaDūta workflow is a graph of typed steps that runs on its own: it starts from a message, a phone call, or a webhook, hands steps to AI agents, branches on the result, and pauses for a person where you put an approval or a question. You compose workflows from 35 node types and trigger them from 11 channels, from WhatsApp messages to plain webhooks.",
      "While building, you write expressions to pass data between steps, pin a step's output so later steps can be tested without re-running it, and run the draft before promoting a version to production. More than 240 ready-made recipes across about 90 industries give you a starting point, and any workflow can be shared with a public link.",
    ],
    sections: [
      {
        heading: "35 node types to compose from",
        body: "A VegaDūta workflow is a graph of typed nodes, each doing one job. Agent nodes hand a step to an AI agent, condition nodes branch, loop and parallel nodes control flow, and code nodes run custom logic. Workflow, sub-flow and supervisor nodes let larger automations delegate to smaller ones.",
        bullets: [
          "Trigger, agent, knowledge, condition, approval, output",
          "Tool, HTTP request, code, media, device control",
          "Loop, parallel, fan-out, supervisor, workflow, sub-flow",
          "Human input, voice menu (keypad), bulk email, CRM lead",
        ],
      },
      {
        heading: "Expressions, pinned data, and draft runs",
        body: "Fields on a node accept expressions that read the trigger payload and the outputs of earlier nodes, with a preview as you type and a lint panel that flags problems before a run. Pinning saves one node's output on the draft so the steps after it can be built and tested without calling that node again. A draft run uses pinned data and says so in the run summary; live runs from webhooks, schedules, channels, and share links always execute every node for real.",
        bullets: [
          "An expression language for passing data between steps, documented at /help/workflow-expressions",
          "Pin an output from a step test, a past run, a received event, or pasted data",
          "Draft runs for editors; production runs follow the version labelled production",
          "Background runs return a run id immediately and are polled for status, for workflows that take minutes",
        ],
      },
      {
        heading: "Sales and outreach nodes",
        body: "Two nodes cover outbound sales work. The bulk email node sends a personalised message to each recipient in a list, with a per-run recipient cap, a send-rate limit, an unsubscribe link, and a suppression list. The CRM lead node creates or updates a lead in the built-in leads list, which tracks each lead through stages from new to won or lost. Replies and bounces to sent email are detected and recorded.",
      },
      {
        heading: "Triggered from 11 channels",
        body: "Workflows start from real-world events, not just cron. A customer message on WhatsApp, Telegram, SMS, Messenger, Instagram, Slack, Discord, or X can kick off a run, as can a phone call, an email, or an authenticated webhook from your own systems. The same workflow can serve every channel you connect.",
      },
      {
        heading: "Retries you configure, not a fixed policy",
        body: "Every workflow carries its own execution limits rather than inheriting one platform-wide policy: how many retry attempts a failed step gets (anywhere from 0 to 5), the maximum number of steps a single run may take, and a per-step timeout. Retry counts and the step ceiling apply on both execution engines. One honest difference: the per-step timeout is enforced by the durable Temporal engine — on the default in-process engine it is recorded on the workflow but a step runs until it finishes, and a run whose process dies is marked failed by the recovery reaper instead of resuming.",
        bullets: [
          "0–5 retry attempts per workflow — set 0 for a step you never want repeated",
          "Irreversible steps (email sends, tool calls, sub-workflows) are never retried automatically — a repeat would duplicate the side effect",
          "A quota rejection is never retried — it would fail identically and burn the attempt",
        ],
      },
      {
        heading: "Humans stay in the loop",
        body: "Approval nodes pause a run until a person approves or rejects the step, and human-input nodes pause it to ask a person a question through a form. A running workflow can also be paused and resumed by hand. The paused state is stored on the run record, and the run continues from the node where it stopped. This is how VegaDūta keeps risky actions behind a human decision.",
      },
      {
        heading: "Recipes and sharing",
        body: "More than 240 workflow recipes ship ready to adapt, organized into about 90 industries, so a clinic, kirana store, or agency starts from something that already works. Finished workflows get public share links. If you are comparing tools, we keep an honest comparison against n8n for India-focused teams.",
      },
    ],
    faqs: [
      {
        q: "What workflow engine does VegaDūta use?",
        a: "Workflows run on VegaDūta's own execution engine by default. Failed steps are retried up to the limit set on the workflow, and a run waiting on an approval or a human answer is stored on its run record and resumed from that node. A Temporal-based engine exists in the codebase as an opt-in and is off by default.",
      },
      {
        q: "Can a workflow wait for human approval before acting?",
        a: "Yes. An approval node pauses the run until someone approves or rejects it, and the paused state is stored on the run record until a decision arrives. This is the recommended pattern for payments, deletions, and anything else you want a human to sign off on.",
      },
      {
        q: "What can trigger a VegaDūta workflow?",
        a: "Eleven channels: webhook, WhatsApp, Telegram, SMS, phone call, Facebook Messenger, Instagram, Slack, Discord, X, and email. A single workflow can be wired to several triggers at once, so the same automation answers customers wherever they message you.",
      },
      {
        q: "Can I control how many times a failed workflow step is retried?",
        a: "Yes — retries are configured per workflow, from 0 to 5 attempts, alongside a maximum step count and a per-step timeout. Both engines honour the retry count and the step ceiling; the per-step timeout is enforced by the durable Temporal engine, which is the engine to choose for long, time-bound runs. A quota-exceeded rejection is deliberately excluded from retry, since it would fail identically on the next attempt — as are irreversible steps, whose repetition would duplicate the side effect.",
      },
      {
        q: "Do I have to build workflows from scratch?",
        a: "No. More than 240 workflow recipes ship with the platform, organized into about 90 industries, and you adapt them rather than starting blank. You can also try the builder without signing up in the sandbox at /try.",
      },
      {
        q: "Can I test a workflow without re-running every step?",
        a: "Yes. Pin a node's output and draft runs and step tests use the saved data instead of running that node. The run summary states how many nodes were pinned, and live runs never use pins.",
      },
    ],
    related: [
      { href: "/help/workflow-recipes", label: "Industry workflow recipes" },
      { href: "/help/workflow-expressions", label: "Expressions and pinning guide" },
      { href: "/platform/versions-and-evals", label: "Versions and evals" },
      { href: "/tutorials/create-a-workflow", label: "Tutorial: create a workflow" },
      { href: "/compare/n8n-alternative-india", label: "VegaDūta vs n8n for India" },
      { href: "/platform/webhooks", label: "Webhook triggers" },
      { href: "/try", label: "Try the sandbox" },
    ],
  },
  {
    slug: "agent-builder",
    name: "Agent Builder",
    metaTitle: "AI Agent Builder — Plain-Language Setup | VegaDūta",
    metaDescription:
      "Describe an agent in one plain-language sentence and VegaDūta configures its tools, channels, and guardrails. Iterate in the studio, install templates.",
    h1: "Build an AI agent from one sentence",
    kicker: "Platform · Agents",
    cardBlurb: "Describe the agent you want in plain language; the builder configures tools, channels, and guardrails.",
    intro: [
      'VegaDūta\'s agent builder turns one plain-language sentence into a working agent. Describe what you want — "a Hindi-speaking agent that answers order questions on WhatsApp" — and the builder configures the tools, channels, and guardrails to match. You then refine it in the studio, where changes are immediate and testable, instead of hand-writing configuration.',
      "You can also skip the blank page entirely: the community template gallery offers admin-moderated agent templates you install as a snapshot and make your own.",
    ],
    sections: [
      {
        heading: "Plain language in, configured agent out",
        body: "The builder is deliberately not a form with forty fields. You state the agent's job in a sentence, in plain language, and the platform proposes a configuration: which tools it needs, which channels it should listen on, and which guardrails apply. Everything it chooses stays visible and editable.",
      },
      {
        heading: "A studio for iterating",
        body: "Agents are rarely right on the first try, so the studio is built for the loop: adjust the agent, talk to it, adjust again. Capabilities are per-agent toggles — code execution and Sarvam's Indian-language tools, for example, are switched on per agent rather than globally, so each agent carries only what it needs. Every saved change becomes a version you can restore, and the Agent Lab in the playground shows what a run actually did.",
      },
      {
        heading: "Community templates with moderation",
        body: 'The template gallery at /templates is community-built and admin-moderated: submissions are reviewed before they appear, and installing a template gives you a snapshot copy you fully own and can edit. It is the fastest route from "I want something like that" to an agent running under your account.',
      },
      {
        heading: "What the free tier locks — honestly",
        body: "On the FREE tier, some advanced tuning controls are locked and labeled as such in the builder; the core loop of creating, testing, and running agents stays open. We would rather show you a clearly marked lock than a degraded experience you cannot explain. Plan details live at /billing.",
      },
    ],
    faqs: [
      {
        q: "Do I need to know how to code to build an agent on VegaDūta?",
        a: "No. You describe the agent in one plain-language sentence and the builder configures its tools, channels, and guardrails for you. Code-level capabilities like sandboxed code execution exist, but they are optional per-agent toggles, not a requirement.",
      },
      {
        q: "Can I start from a pre-built agent template?",
        a: "Yes. The community template gallery is admin-moderated, and installing a template creates a snapshot copy under your account that you can edit freely. Changes to your copy never affect the original template.",
      },
      {
        q: "How do I give an agent extra capabilities like code execution?",
        a: "Capabilities are per-agent toggles. Code execution (which needs an E2B key connected to your workspace) and Sarvam tools, for instance, are switched on for the specific agents that need them, so a customer-support agent is not carrying a code sandbox it will never use.",
      },
      {
        q: "Is the agent builder free to use?",
        a: "You can build and run agents on the FREE tier, and you can try the builder without signing up at /try. Some advanced tuning controls are locked on FREE and clearly labeled; see /billing for what each plan includes.",
      },
    ],
    related: [
      { href: "/tutorials/build-your-first-agent", label: "Tutorial: build your first agent" },
      { href: "/templates", label: "Template gallery" },
      { href: "/ai/agents", label: "How VegaDūta agents work" },
      { href: "/platform/agent-skills", label: "Agent Skills" },
      { href: "/platform/agent-teams", label: "Agent teams" },
      { href: "/platform/agent-lab", label: "Agent Lab" },
      { href: "/ai/guardrails", label: "Guardrails" },
      { href: "/try", label: "Try the sandbox" },
    ],
  },
  {
    slug: "webhooks",
    name: "Webhooks",
    metaTitle: "Webhook Triggers for AI Workflows | VegaDūta",
    metaDescription:
      "VegaDūta workflows start from authenticated inbound webhooks, and every channel event arrives signature-validated. Outbound subscriptions are not built yet.",
    h1: "Webhooks: how events get into VegaDūta",
    kicker: "Platform · Integration",
    cardBlurb:
      "Authenticated inbound webhook triggers for workflows, with signature-validated channel events underneath.",
    intro: [
      "VegaDūta uses webhooks in two ways. First, any workflow can expose an inbound trigger webhook — an authenticated URL your own systems call to start a run, with configurable auth modes and hashed tokens. Second, every connected channel — WhatsApp, Telegram, Slack, billing events — reaches the platform over validated webhooks with signature checks, so spoofed events are rejected before they touch a workflow.",
      "One honest limitation up front: general outbound webhook subscriptions — the platform POSTing event notifications to your endpoint with retries — are not built yet. The developer API and MCP server are today's pull-side integration surface.",
    ],
    sections: [
      {
        heading: "Inbound trigger webhooks",
        body: "Give a workflow a webhook trigger and it gets a callable URL. Auth is configurable per trigger, and tokens are stored hashed rather than in plaintext, so a database read never yields a usable secret. This is the standard way to start VegaDūta runs from a CRM, a cron job, or any system that can send an HTTP request.",
      },
      {
        heading: "Signature checks on every channel",
        body: 'The webhooks you never see matter just as much. Incoming events from WhatsApp, Telegram, Slack, and the billing gateway all pass signature validation before they are processed. An event that fails its check is dropped — a forged "payment succeeded" or fake customer message never reaches your agents.',
      },
      {
        heading: "What is not built yet: outbound subscriptions",
        body: "You cannot currently register your own endpoint to receive platform events with delivery retries; outbound webhooks today are limited to platform alerting. If your integration needs to observe VegaDūta, poll the developer API or connect over the MCP server — both cover agents, workflows, and knowledge. We list this plainly because knowing it now beats discovering it mid-build.",
      },
    ],
    faqs: [
      {
        q: "Can I trigger a VegaDūta workflow from an external system via webhook?",
        a: "Yes. Add a webhook trigger to the workflow and it exposes an authenticated URL with configurable auth modes; trigger tokens are stored hashed at rest. Any system that can make an HTTP request — a CRM, a script, another platform — can start the run.",
      },
      {
        q: "Does VegaDūta verify webhook signatures on incoming channel events?",
        a: "Yes. Events from every connected channel — WhatsApp, Telegram, Slack, and billing among them — are signature-checked before processing, and events that fail validation are rejected. This blocks spoofed messages and forged payment notifications.",
      },
      {
        q: "Can VegaDūta send outbound webhooks to my endpoint when events happen?",
        a: "Not yet. General outbound webhook subscriptions with retries are not built; outbound traffic today is platform alerting only. Use the developer API to poll for state, or the MCP server to integrate agents and workflows directly.",
      },
      {
        q: "How are webhook tokens secured?",
        a: "Trigger tokens are hashed before storage, so the platform database never holds a usable plaintext secret, and each trigger's auth mode is configured independently. Inbound channel webhooks are additionally protected by per-channel signature validation.",
      },
    ],
    related: [
      { href: "/platform/apis", label: "Developer APIs" },
      { href: "/ai/mcp", label: "MCP on VegaDūta" },
      { href: "/platform/workflow-automation", label: "Workflow automation" },
      { href: "/tutorials/create-a-workflow", label: "Tutorial: create a workflow" },
    ],
  },
  {
    slug: "apis",
    name: "Developer APIs",
    metaTitle: "Developer API for AI Agents & Workflows | VegaDūta",
    metaDescription:
      "Call your VegaDūta agents and workflows over a REST developer API with scoped API keys and rate limits — the same keys that power the MCP server surface.",
    h1: "Developer APIs: your agents, callable",
    kicker: "Platform · Developers",
    cardBlurb: "A REST developer API with scoped keys and rate limits for calling your agents and workflows from code.",
    intro: [
      "VegaDūta exposes a REST developer API under /api/dev/v1 for calling your agents and workflows from your own code. Access is governed by scoped API keys — a key carries only the permissions you grant it — with rate limits applied per key. The same key system powers the platform's MCP server, so one credential model covers both plain HTTP integrations and MCP-native tools.",
    ],
    sections: [
      {
        heading: "REST access to agents and workflows",
        body: "Anything you build in the console becomes callable: invoke an agent, start a workflow, and integrate the results into your own product or back office. The API speaks plain REST under /api/dev/v1, so it works from any language with an HTTP client, without an SDK dependency.",
      },
      {
        heading: "Scoped keys and rate limits",
        body: "API keys are scoped, not all-powerful: each key carries an explicit set of permissions, so a key that only needs to run one workflow cannot read your knowledge base. Rate limits are enforced per key, keeping a misbehaving integration from starving the rest of your tenant.",
      },
      {
        heading: "One key system, two surfaces",
        body: "The same scoped keys authenticate the MCP server surface, which presents a curated set of five tools to clients like Claude and other MCP-capable hosts: list_my_agents, call_agent, list_my_workflows, call_workflow, and search_knowledge. Build against REST, MCP, or both — the credential you manage is the same either way.",
      },
      {
        heading: "Where to start",
        body: "Create a key, scope it to what your integration actually needs, and make your first call against /api/dev/v1. The docs at /docs cover authentication and endpoints, and the MCP page explains when the MCP surface is the better fit than raw REST — typically when the caller is itself an AI tool.",
      },
    ],
    faqs: [
      {
        q: "Does VegaDūta have an API for calling agents programmatically?",
        a: "Yes. The REST developer API under /api/dev/v1 lets your code invoke agents and start workflows, authenticated with scoped API keys and subject to per-key rate limits. It is plain REST, so any HTTP client works.",
      },
      {
        q: "How are VegaDūta API keys scoped?",
        a: "Each key carries an explicit set of permissions rather than blanket tenant access, so you can issue a key that only runs a specific surface of your account. Rate limits apply per key, which isolates one integration's traffic from another's.",
      },
      {
        q: "What is the relationship between the developer API and the MCP server?",
        a: "They share the same key system. The REST API is for conventional code integrations; the MCP server presents five curated tools — list_my_agents, call_agent, list_my_workflows, call_workflow and search_knowledge — for AI clients. One scoped key can authenticate either surface.",
      },
      {
        q: "Can I try the API without a paid plan?",
        a: "You can explore the platform without signing up at /try, and API access follows your plan's limits once you have an account. Plan details are at /billing; the API itself is documented at /docs.",
      },
    ],
    related: [
      { href: "/ai/mcp", label: "MCP on VegaDūta" },
      { href: "/platform/webhooks", label: "Webhook triggers" },
      { href: "/docs", label: "Documentation" },
      { href: "/tutorials/connect-mcp-server", label: "Tutorial: connect an MCP server" },
    ],
  },
  {
    slug: "security",
    name: "Security",
    metaTitle: "AI Platform Security & Tenant Isolation | VegaDūta",
    metaDescription:
      "VegaDūta isolates every tenant in its own Postgres schema, encrypts credentials at rest, and layers guardrails, approval gates, audit logs, and budgets.",
    h1: "Security: isolation first, honesty throughout",
    kicker: "Platform · Trust",
    cardBlurb: "Schema-per-tenant isolation, Keycloak SSO, encrypted credentials, guardrails, and audit logging.",
    intro: [
      "VegaDūta isolates every tenant in its own Postgres schema — your data lives in a separate schema, not in shared tables filtered by a tenant column. Identity runs on Keycloak with OpenID Connect SSO, provider and channel credentials are encrypted at rest, and every agent action passes through guardrails, optional human approval gates, and audit logging.",
      "We are equally direct about what we are not: the hosted service runs in a single region today, and holds no SOC 2 or ISO 27001 certification. A documented security program is maintained in preparation for formal assessment. If your procurement process requires a certificate, you should know before a sales call, not after.",
    ],
    sections: [
      {
        heading: "Tenant isolation by schema",
        body: "Each tenant gets a dedicated Postgres schema, which makes cross-tenant reads a structural impossibility rather than a query-filter discipline. It also makes tenant data-erasure tractable: the platform ships tooling to erase a tenant's data cleanly when they leave.",
      },
      {
        heading: "Identity and secrets",
        body: "Authentication is Keycloak with OpenID Connect, giving you standard SSO semantics rather than a homegrown login system. Workspace-level single sign-on and SCIM user provisioning are available for connecting your own identity provider; they have not yet been validated against each vendor's product, so plan a test with yours. The API keys, LLM provider credentials, and channel secrets you store are encrypted at rest, and inbound channel webhooks are signature-validated before processing.",
      },
      {
        heading: "Agents kept on a leash",
        body: "Autonomous agents get layered controls: guardrails screen for PII, toxicity, and jailbreak attempts; human approval gates pause risky actions until someone says yes; per-user daily budgets cap spend; and audit logs record what happened. MCP tool connections run sandboxed with SSRF guards and command allowlists, so a malicious or buggy tool server cannot reach into your network.",
        bullets: [
          "PII, toxicity, and jailbreak guardrails on agent traffic",
          "Human approval gates before risky actions",
          "Per-user daily budgets and audit logging",
          "MCP sandboxing, SSRF guards, command allowlists",
        ],
      },
      {
        heading: "Where we stand on compliance — honestly",
        body: "The hosted service runs in a single region today, and we have not completed SOC 2 or ISO 27001 certification. A documented security program exists in preparation for formal assessment. The controls above are real and in the code, which is source-available under the Business Source License 1.1 so you can read them, but we will not dress them up as a certificate we do not hold. This page will change when that status does.",
      },
    ],
    faqs: [
      {
        q: "How does VegaDūta isolate one customer's data from another's?",
        a: "Every tenant gets its own Postgres schema — separate tables, not shared tables filtered by a tenant ID. That makes cross-tenant access structurally impossible at the query layer, and it is paired with tenant data-erasure tooling for clean offboarding.",
      },
      {
        q: "Is VegaDūta SOC 2 or ISO 27001 certified?",
        a: "Not yet. The hosted service is single-region today, and no SOC 2 or ISO 27001 certification has been completed; a documented program is in preparation for formal assessment. The underlying controls — schema isolation, encryption at rest, audit logging, guardrails — exist in the product, but we state the certification status plainly.",
      },
      {
        q: "How are my LLM provider keys and channel credentials stored?",
        a: "Encrypted at rest. Provider keys, channel secrets, and similar credentials are never stored in plaintext, and webhook trigger tokens are hashed. Authentication for users runs on Keycloak with OpenID Connect SSO.",
      },
      {
        q: "What stops an AI agent from doing something dangerous?",
        a: "Several independent layers: guardrails screen inputs and outputs for PII, toxicity, and jailbreaks; human approval gates pause risky actions until a person approves; per-user daily budgets cap spend; and MCP tools run sandboxed with SSRF guards and command allowlists. Agents are also designed to ask before risky actions rather than act silently.",
      },
    ],
    related: [
      { href: "/platform/governance", label: "Governance and cost controls" },
      { href: "/platform/self-hosting", label: "Open source and self-hosting" },
      { href: "/ai/guardrails", label: "Guardrails" },
      { href: "/ai/mcp", label: "MCP sandboxing" },
      { href: "/why-vegaduta", label: "Why VegaDūta" },
      { href: "/docs", label: "Documentation" },
    ],
  },
  {
    slug: "observability",
    name: "Observability & Evals",
    metaTitle: "LLM Observability & Agent Evals | VegaDūta",
    metaDescription:
      "VegaDūta traces every LLM call to Langfuse via OpenTelemetry, scores agent quality over time, and runs sampled LLM-as-judge evaluations on live traffic.",
    h1: "Observability: every LLM call, traced and scored",
    kicker: "Platform · Quality",
    cardBlurb: "Langfuse tracing over OpenTelemetry, per-agent quality scores, and sampled LLM-as-judge evaluations.",
    intro: [
      "Every LLM call your VegaDūta agents make is traced to Langfuse over OpenTelemetry, tagged with the user, session, and metadata that let you reconstruct exactly what happened. On top of the traces, each agent carries a quality score with history, fed by sampled LLM-as-judge evaluations that grade real interactions and write scores back. When a call fails or behaves strangely, a dedicated LLM-call diagnosis surface helps you work out why.",
      "To be precise about scope: this is in-product observability. Connecting your own Langfuse instance is not a supported customer feature today.",
    ],
    sections: [
      {
        heading: "Tracing built in, not bolted on",
        body: 'Tracing is not an SDK you remember to add — the platform emits it for every LLM call automatically, over OpenTelemetry into Langfuse. Traces carry user, session, tags, and metadata, so "what did the agent actually send, and what came back?" is always answerable, per conversation.',
      },
      {
        heading: "Quality scores with history",
        body: "Each agent has a quality score tracked over time, not a one-off grade. That history is what makes changes accountable: adjust a prompt, swap a model, or add a tool, and you can see whether quality moved — and in which direction — instead of relying on vibes.",
      },
      {
        heading: "LLM-as-judge, on real traffic",
        body: "A sample of live interactions is evaluated by an LLM judge, and the resulting scores feed back into each agent's quality history. Sampling keeps evaluation continuous and affordable, and grading real traffic means the scores reflect what your users actually experience rather than a synthetic test set. For tests you control, eval datasets and a promotion gate are described on the versions and evals page.",
      },
      {
        heading: "Diagnosing a failing call",
        body: "When an LLM call fails, the diagnosis surface examines the call and reports what went wrong — a malformed request, a provider-side error, a truncation — so debugging starts from evidence instead of guesswork. One honest boundary: observability lives in the product, and bringing your own Langfuse instance is not currently supported.",
      },
    ],
    faqs: [
      {
        q: "How does VegaDūta trace LLM calls?",
        a: "Every LLM call is exported to Langfuse over OpenTelemetry automatically, with user, session, tags, and metadata attached. There is no instrumentation for you to add — tracing is part of the platform's own call path.",
      },
      {
        q: "How does VegaDūta measure agent quality?",
        a: "Each agent has a quality score with history, fed by sampled LLM-as-judge evaluations of real interactions. Because scores accumulate over time, you can see whether a prompt or model change actually improved the agent.",
      },
      {
        q: "Can I debug why a specific LLM call failed?",
        a: "Yes. The LLM-call diagnosis surface analyzes a failing call and reports the likely cause, such as a provider error or a malformed request, alongside the full trace in Langfuse. That turns debugging into reading evidence rather than reproducing blind.",
      },
      {
        q: "Can I connect my own Langfuse instance to VegaDūta?",
        a: "No — bring-your-own-Langfuse is not a supported customer feature today. Tracing and evaluation are provided in-product, and the trace data is used by the platform's own quality scoring and diagnosis tooling.",
      },
    ],
    related: [
      { href: "/platform/versions-and-evals", label: "Versions and evals" },
      { href: "/platform/agent-lab", label: "Agent Lab" },
      { href: "/ai/agents", label: "How VegaDūta agents work" },
      { href: "/ai/llm-connectors", label: "LLM connectors" },
      { href: "/platform/security", label: "Security" },
      { href: "/docs", label: "Documentation" },
    ],
  },
];
