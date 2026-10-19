// /platform hub data: improve and operate topics (Agent Lab, skills, teams, versions and evals, App Studio, governance, self-hosting). Re-exported in order by platform-data.ts.

import type { HubPage } from "../lib/seo-hub";

export const OPERATE_TOPICS: HubPage[] = [
  {
    slug: "agent-lab",
    keyTakeaways: [
      "Agent Lab is a testing panel in the VegaDūta playground with three instruments: a run graph, prompt knockout, and a consistency check.",
      "The run graph shows each step of a past turn (plan, tool calls, guardrail decisions, handoffs, reply) and lets you fork the conversation from any message and compare the branch with the original.",
      "Prompt knockout removes one section of an agent's system prompt at a time and measures how the score changes on the same questions.",
    ],
    name: "Agent Lab",
    metaTitle: "Agent Lab: Run Graph, Fork & Prompt Knockout | VegaDūta",
    metaDescription:
      "Agent Lab shows what an agent run actually did, lets you fork a conversation from any message, tests which prompt sections matter, and checks answer consistency.",
    h1: "Agent Lab: see what a run did, then test why",
    kicker: "Platform · Agents",
    cardBlurb:
      "A run graph you can fork from, prompt knockout to find which instructions matter, and a consistency check.",
    intro: [
      "Agent Lab is a panel in the agent playground for answering three questions about an agent: what did it do on that turn, which parts of its prompt are doing the work, and does it give the same answer twice. Open it from the Lab button in the playground and it works on the agent and conversation you already have open.",
      "The lab has three tabs. Run graph draws a past turn as a graph of steps and lets you fork the conversation from any message. Prompt knockout removes sections of the system prompt one at a time and measures the effect. Consistency asks the same question several times and reports whether the answers mean the same thing.",
    ],
    sections: [
      {
        heading: "Run graph: every step of a turn",
        body:
          "Each turn in the playground becomes a graph: the user message, the plan, each tool call, guardrail decisions, handoffs to other agents, and the reply. Selecting a step shows its tool name, a shortened preview of its arguments and result, any error, and how long it took. The panel also totals the turn's elapsed time, tool calls, tokens, and estimated cost.",
      },
      {
        heading: "Fork from any message",
        body:
          "Fork rewinds the conversation to just before a message and sends that message again, edited if you want. Later turns are removed from the live conversation and the original is kept as a snapshot, so you can compare the branch with the original side by side, including which tools each version called. Two limits are stated in the dialog: tool calls the original turn already made are not undone, and snapshots are kept in your browser only, for 24 hours.",
      },
      {
        heading: "Prompt knockout: which instructions matter",
        body:
          "Knockout splits the system prompt at headings and blank lines, then runs the same questions with one section removed at a time. Questions come from an eval dataset or from probe questions you type in. The unchanged prompt is run several times first to measure the noise floor, and each section is then labelled load-bearing, harmful, or no measurable effect. When there are too few questions to tell a real change from noise, the lab says so instead of labelling.",
      },
      {
        heading: "Consistency, and what lab runs leave out",
        body:
          "The consistency check asks one question several times and groups the answers by meaning. Knockout and consistency runs ask the model directly with the agent's prompt, sampling settings, and usual model. They do not use tools, memory, knowledge lookups, guardrails, or caching, and they are not added to the conversation. The lab shows the number of model calls before a run starts, the calls count toward your usage limits, and Free workspaces get smaller run sizes.",
      },
    ],
    faqs: [
      {
        q: "How do I see what my AI agent did during a conversation?",
        a: "Open Agent Lab from the playground and pick the turn. The run graph shows the plan, each tool call with a preview of its arguments and result, guardrail decisions, handoffs, and the reply, with timings, token counts, and an estimated cost.",
      },
      {
        q: "Can I re-run an agent from an earlier point in a conversation?",
        a: "Yes. Fork rewinds the conversation to just before a chosen message and sends it again, optionally edited. The original is kept as a snapshot in your browser for 24 hours so you can compare. Tool calls the original turn already made are not undone.",
      },
      {
        q: "How do I find out which parts of a system prompt matter?",
        a: "Use prompt knockout. The lab removes one section of the prompt at a time, runs the same questions, and reports how the score changed against a measured noise floor. Sections come out labelled load-bearing, harmful, or no measurable effect.",
      },
      {
        q: "Do Agent Lab runs behave exactly like production?",
        a: "The run graph shows real playground turns. Knockout and consistency runs do not: they call the model directly without tools, memory, knowledge, guardrails, or caching, so they isolate the prompt. The lab states this next to the run button.",
      },
    ],
    related: [
      { href: "/platform/versions-and-evals", label: "Versions and evals" },
      { href: "/platform/observability", label: "Observability" },
      { href: "/platform/agent-builder", label: "Agent Builder" },
      { href: "/ai/agents", label: "How VegaDūta agents work" },
    ],
  },
  {
    slug: "agent-skills",
    keyTakeaways: [
      "An Agent Skill on VegaDūta is a folder with a SKILL.md file: a name, a description, instructions for one kind of task, and optional bundled files.",
      "Agents see only each skill's name and description until a request matches, then load the full instructions, so adding skills does not lengthen every prompt.",
      "VegaDūta ships 14 built-in skills, and a skill drafted by an agent stays switched off until a workspace admin reviews and enables it.",
    ],
    citations: [
      { label: "Anthropic — Agent Skills", href: "https://www.anthropic.com/news/skills" },
    ],
    name: "Agent Skills",
    metaTitle: "Agent Skills: SKILL.md Packages for AI Agents | VegaDūta",
    metaDescription:
      "Agent Skills package task instructions as SKILL.md folders that agents load only when needed. 14 built in, import and export as .zip, admin review for agent drafts.",
    h1: "Agent Skills: expertise an agent loads when it needs it",
    kicker: "Platform · Agents",
    cardBlurb:
      "SKILL.md packages agents load on demand: 14 built in, zip import and export, and admin review for agent-written drafts.",
    intro: [
      "An Agent Skill is a set of instructions for one kind of task, packaged as a folder with a SKILL.md file and any supporting files. Agents see only each skill's name and description. When a request matches, the agent loads the full instructions, and reads a bundled file only if it needs it. That keeps the prompt short for unrelated requests, however many skills a workspace has.",
      "VegaDūta ships 14 built-in skills and lets a workspace admin add custom ones from the Skills page in the console: write one in the editor, upload a .zip, or ask an agent to help draft one.",
    ],
    sections: [
      {
        heading: "14 built-in skills",
        body:
          "Built-in skills are included with the platform and on for every agent. An admin can switch any of them off. They cannot be edited in place; to customise one, export it, rename it, and upload it as your own.",
        bullets: [
          "Documents: docx, pdf, pptx, xlsx",
          "Writing: email-writer, meeting-notes, research-report, seo-content",
          "Work: code-review, customer-support, data-analysis, sql-query",
          "Platform: generative-ui, skill-creator",
        ],
      },
      {
        heading: "Custom skills: write, upload, export",
        body:
          "A custom skill has a name (lowercase letters, numbers and hyphens, up to 64 characters), a description of up to 1,024 characters that decides when agents use it, and its instructions. Upload a skill as a .zip to include bundled files, and export any skill the same way. A saved or imported skill is used by agents from their next message.",
      },
      {
        heading: "Agent-written skills wait for an admin",
        body:
          "Agents can draft a skill, and the platform can propose draft skills from patterns in successful runs. Both arrive switched off and marked as agent drafts. Nothing an agent writes is used by other agents until a workspace admin reads it and enables it. A proposed rewrite of an existing skill is staged the same way: an admin applies it to the live skill or discards it.",
      },
    ],
    faqs: [
      {
        q: "What is an Agent Skill?",
        a: "A packaged set of instructions for one kind of task, stored as a folder with a SKILL.md file. The agent sees the skill's name and description up front and loads the full instructions only when a request calls for them.",
      },
      {
        q: "Do more skills make an agent slower or more expensive?",
        a: "Not on unrelated requests. Only the name and description of each skill are in the prompt. The full instructions and bundled files are loaded when a request matches the skill.",
      },
      {
        q: "Can I import skills I already have?",
        a: "Yes. Upload a skill folder as a .zip on the Skills page, and export any skill, built-in or custom, the same way. Non-text files in an upload are skipped and the import reports how many.",
      },
      {
        q: "Can an agent create its own skills?",
        a: "An agent can draft one, and drafts can be proposed from successful runs, but every agent-written skill is created switched off. A workspace admin reviews it before any agent can use it.",
      },
    ],
    related: [
      { href: "/platform/agent-builder", label: "Agent Builder" },
      { href: "/platform/agent-teams", label: "Agent teams" },
      { href: "/ai/agents", label: "How VegaDūta agents work" },
      { href: "/ai/mcp", label: "MCP on VegaDūta" },
    ],
  },
  {
    slug: "agent-teams",
    keyTakeaways: [
      "A team on VegaDūta is a lead agent plus the other agents it may hand parts of a task to.",
      "With two or more members, a lead agent can delegate to several members in parallel and combine their answers; delegation goes one level deep.",
      "Each delegation call runs at most 8 parallel branches, each branch is stopped after 240 seconds, and all branches share one per-run budget.",
    ],
    name: "Agent Teams",
    metaTitle: "Multi-Agent Teams: Parallel Delegation | VegaDūta",
    metaDescription:
      "A VegaDūta lead agent delegates work to specialist agents, in parallel when the team has two or more members, with bounded branches, timeouts and a shared budget.",
    h1: "Agent teams: one lead, several specialists",
    kicker: "Platform · Agents",
    cardBlurb:
      "A lead agent hands parts of a task to specialist agents, in parallel, inside fixed limits on branches, time, and spend.",
    intro: [
      "A team is one agent plus the other agents it may hand parts of a task to. The lead agent decides what to delegate, each member does its part, and the lead combines the answers into one reply. With two or more members the lead can send work to several of them at once instead of one after another.",
      "Teams are set up on the agent's Team / Swarm card in the console. Delegation is deliberately bounded: it goes one level deep, the number of parallel branches is capped, each branch has a time limit, and every branch draws on a single budget for the run.",
    ],
    sections: [
      {
        heading: "How delegation works",
        body:
          "Team members are ordinary agents added to the lead agent as collaborators. The lead sees them as tools it can call: ask one member, or delegate to several in parallel. Members answer the lead and cannot hand work on to further agents. Handoffs appear as their own steps in the Agent Lab run graph.",
      },
      {
        heading: "The limits, stated plainly",
        body:
          "Parallel delegation multiplies model calls, so the limits are fixed and shown on the card before you turn a team on.",
        bullets: [
          "At most 8 parallel branches per delegation call; extra members wait for a later call",
          "Each branch is stopped after 240 seconds",
          "All branches share one per-run budget, 2 US dollars by default; the run stops when it is used up",
          "Depth 1 only: members cannot delegate further",
        ],
      },
      {
        heading: "Presets to start from",
        body:
          "Three presets create the member agents for you, using the lead agent's provider and model: a research crew that gathers facts, challenges them, and writes them up; a review pair where one agent drafts and one reviews; and a support triage team that classifies a request, resolves it, and escalates what is left. Existing collaborators are kept when a preset is applied.",
      },
    ],
    faqs: [
      {
        q: "Can several AI agents work on one task at the same time?",
        a: "Yes. When a lead agent has two or more team members it can delegate to several of them in parallel and combine the answers. Up to 8 branches run per delegation call.",
      },
      {
        q: "Can a team member delegate to its own team?",
        a: "No. Delegation is one level deep. Members answer the lead agent and cannot hand work on to further agents, which keeps cost and run time predictable.",
      },
      {
        q: "What stops a multi-agent run from spending too much?",
        a: "All branches of a run share one budget, 2 US dollars by default, and the run stops when it is used up. The figure is an estimate used as a runaway guard, not a billing amount. Each branch also has a 240 second time limit.",
      },
      {
        q: "How is this different from a supervisor node in a workflow?",
        a: "A team is decided by the lead agent at run time inside a conversation. A workflow fixes the steps in advance on a canvas, with supervisor, parallel and fan-out nodes for the same kind of split. Use a team when the agent should choose; use a workflow when you want the steps pinned down.",
      },
    ],
    related: [
      { href: "/ai/agents", label: "How VegaDūta agents work" },
      { href: "/platform/agent-skills", label: "Agent Skills" },
      { href: "/platform/workflow-automation", label: "Workflow automation" },
      { href: "/platform/governance", label: "Governance and cost controls" },
    ],
  },
  {
    slug: "versions-and-evals",
    keyTakeaways: [
      "Every saved change to a VegaDūta agent or workflow is kept as a version that can be restored.",
      "Versions carry staging and production labels, and promoting a version to production can be gated on a passing eval run.",
      "Eval datasets are scored by six LLM judges (accuracy, faithfulness, helpfulness, relevance, safety and tone), with confidence intervals and paired comparisons between versions.",
    ],
    name: "Versions & Evals",
    metaTitle: "Agent Versioning & Eval-Gated Promotion | VegaDūta",
    metaDescription:
      "Every agent and workflow change on VegaDūta is a version with staging and production labels. Eval datasets, six LLM judges and a pass^k gate guard promotion.",
    h1: "Versions and evals: change an agent without guessing",
    kicker: "Platform · Quality",
    cardBlurb:
      "Versioned agents and workflows with staging and production labels, eval datasets, six judges, and a promotion gate.",
    intro: [
      "Every saved change to an agent or a workflow becomes a version. Versions can be labelled staging or production, and any earlier version can be restored. For agents, promotion to production can be tied to a quality gate, so a version that fails its eval dataset does not become the one your customers talk to.",
      "The evaluation side lives on each agent's Quality page: datasets of test cases, LLM judges that score the answers, statistics that say how far to trust a score, and simulated conversations with difficult personas.",
    ],
    sections: [
      {
        heading: "Versions, labels, and restore",
        body:
          "The version list shows each saved configuration. Promote points the production label at a version, and runs follow the label from then on. Restore makes an older version the current configuration; the restore is itself saved as a new version, so it can be undone. Workflows work the same way: editors run the draft, and live triggers run the version labelled production.",
      },
      {
        heading: "Eval datasets and six judges",
        body:
          "A dataset is a list of test cases for one agent, optionally with expected answers. You can save a real conversation turn as a case. A run sends every case to the agent and scores the answers with LLM judges for accuracy, faithfulness, helpfulness, relevance, safety, and tone. Runs have a budget cap and can repeat each case to measure how stable the result is.",
      },
      {
        heading: "A promotion gate that accounts for luck",
        body:
          "An agent that passes a case once may fail it the next time. The gate can therefore require pass^k: a case counts only if it passes on every one of k repeats. Results are shown with confidence intervals instead of a bare percentage, and two runs can be compared case by case, so a two-point difference on twenty cases is not mistaken for an improvement.",
      },
      {
        heading: "Simulated conversations",
        body:
          "Simulation runs a bounded multi-turn conversation between your agent and a persona, built-in or one you write, including adversarial ones. Each persona gets its own isolated run, and each conversation ends with a stated reason such as goal met, maximum turns reached, or no progress.",
      },
    ],
    faqs: [
      {
        q: "Can I roll back an AI agent to an earlier configuration?",
        a: "Yes. Every saved change is a version, and Restore makes any earlier version the current configuration. The restore is saved as a new version, so it can itself be undone.",
      },
      {
        q: "Can I stop a bad agent change from reaching production?",
        a: "Yes. Production is a label on a version, and a promotion gate can require a passing eval run before the label moves. The gate can use pass^k, which requires each test case to pass on every repeat.",
      },
      {
        q: "What does pass^k mean?",
        a: "A case passes only if it passes on all k repeated attempts. It measures reliability instead of a single lucky answer, which matters for agents because the same input can produce different outputs.",
      },
      {
        q: "Are evals available on the Free plan?",
        a: "Yes, with smaller limits on dataset size, repeats, and per-run budget than paid plans. The Quality page shows the limits that apply to your workspace.",
      },
    ],
    related: [
      { href: "/platform/agent-lab", label: "Agent Lab" },
      { href: "/platform/observability", label: "Observability" },
      { href: "/platform/workflow-automation", label: "Workflow automation" },
      { href: "/platform/governance", label: "Governance and cost controls" },
    ],
  },
  {
    slug: "app-studio",
    keyTakeaways: [
      "App Studio on VegaDūta turns a plain-language description into a multi-page app with a live preview.",
      "A project can start from a Figma frame or an existing GitHub repository, and a bounded self-repair loop feeds build errors back to the model.",
      "Published apps can have their own end-user sign-in and call the owner's agents and workflows; custom domains are not served yet.",
    ],
    name: "App Studio",
    metaTitle: "App Studio: Describe an App, Preview It Live | VegaDūta",
    metaDescription:
      "Describe an app and VegaDūta App Studio builds a multi-page app with live preview, Figma and GitHub import, a self-repair loop, and end-user sign-in for published apps.",
    h1: "App Studio: describe an app, get one you can run",
    kicker: "Platform · Apps",
    cardBlurb:
      "Describe an app and get a multi-page build with live preview, Figma and GitHub import, and your agents wired in.",
    intro: [
      "App Studio builds an app from a description. You say what the app should do, the studio generates a multi-page app, and a live preview shows it running as it is built. You then change it by asking for the change in plain language.",
      "The app does not need its own AI backend. You wire the agents and workflows you already have in VegaDūta behind named actions, and the published app calls them.",
    ],
    sections: [
      {
        heading: "Start from a prompt, a design, or a repository",
        body:
          "A project can begin three ways. Describe it in words, optionally with a sketch or screenshot as a design input. Import a Figma frame using your own Figma access token. Or import an existing GitHub repository, after which the studio shows when the branch has changed outside the studio and can pull those commits in.",
      },
      {
        heading: "A repair loop, and a database per project",
        body:
          "When a generated change fails to build, the error is fed back to the model for a corrective pass, a fixed number of times, before the studio stops and shows you what went wrong. A project that needs data gets its own database schema with its own database role, so one project's credentials cannot read another's tables.",
      },
      {
        heading: "Publishing, sign-in, and disposable apps",
        body:
          "A published app is reached by a link. It can run its own end-user accounts, with sign-up and login that are separate from your VegaDūta console users, and each action can be marked as requiring sign-in. Calls from the app run inside the owner's plan and budget. A project can also be created as disposable: it archives itself after a set time, 72 hours by default, unless you extend it or keep it.",
      },
      {
        heading: "What is not there yet",
        body:
          "Custom domains do not serve apps yet. The studio can record a domain and verify that you own it, but published apps are reached through their VegaDūta link today. Phone app store listings are also your own step: the studio provides a readiness checklist, not a submission.",
      },
    ],
    faqs: [
      {
        q: "Can VegaDūta build an app from a description?",
        a: "Yes. App Studio generates a multi-page app from a plain-language description and shows a live preview. You refine it by describing changes.",
      },
      {
        q: "Can I import a Figma design or an existing codebase?",
        a: "Yes. A Figma frame can be imported with your own Figma access token, and a GitHub repository can be imported and kept in sync when the branch changes outside the studio.",
      },
      {
        q: "Can users of my published app sign in?",
        a: "Yes. A published app can have its own end-user accounts with sign-up and login. Those accounts belong to the app and are separate from VegaDūta console users.",
      },
      {
        q: "Can I use my own domain for a published app?",
        a: "Not yet. Domain ownership can be verified in the studio, but apps are not served on custom domains today. Published apps are reached through their VegaDūta link.",
      },
    ],
    related: [
      { href: "/platform/agent-builder", label: "Agent Builder" },
      { href: "/platform/workflow-automation", label: "Workflow automation" },
      { href: "/platform/apis", label: "Developer APIs" },
      { href: "/ai/agents", label: "How VegaDūta agents work" },
    ],
  },
  {
    slug: "governance",
    keyTakeaways: [
      "VegaDūta asks for confirmation before an agent runs a tool classified as sensitive, even when the agent is otherwise set to act on its own.",
      "Spend is bounded at three levels: a per-user daily budget, a cap on tool calls per turn, and a shared per-run budget for multi-agent delegation.",
      "Every platform action is written to an audit log that workspace admins can read as a live tail, and LLM calls emit OpenTelemetry GenAI spans.",
    ],
    citations: [
      { label: "OpenTelemetry — Semantic conventions for generative AI", href: "https://opentelemetry.io/docs/specs/semconv/gen-ai/" },
      { label: "RFC 7644 — System for Cross-domain Identity Management (SCIM) protocol", href: "https://www.rfc-editor.org/rfc/rfc7644" },
    ],
    name: "Governance",
    metaTitle: "AI Agent Governance: Approvals, Budgets, Audit | VegaDūta",
    metaDescription:
      "VegaDūta governs agents with a tool confirmation gate, per-user and per-run budgets, an audit log with live tail, OpenTelemetry GenAI spans, and workspace SSO and SCIM.",
    h1: "Governance: who approved it, what it cost, where it is logged",
    kicker: "Platform · Trust",
    cardBlurb:
      "Tool confirmation, per-user and per-run budgets, an audit log with a live tail, and workspace SSO with SCIM.",
    intro: [
      "Governance on VegaDūta answers three questions about any agent action: did a person have the chance to stop it, was its cost bounded, and is there a record. A confirmation gate sits in front of sensitive tools, budgets cap spend per user and per run, and an audit log records what happened.",
      "These controls are platform settings, not prompt instructions, so they hold whether or not the model follows its prompt.",
    ],
    sections: [
      {
        heading: "Confirmation before sensitive tools",
        body:
          "Built-in tools are classified by what they can do. When an agent is about to call a tool classified as sensitive, the call waits for a person to confirm it, even on an agent set to act autonomously. Approval policies can also be set per tool, including tools that come from MCP servers, and pending approvals appear on the dashboard and in the admin console.",
      },
      {
        heading: "Budgets at three levels",
        body:
          "A per-user daily budget caps what one person's conversations can spend. A limit on tool calls per turn stops a tool loop from running indefinitely. Multi-agent delegation draws on one shared budget per run, and the run stops with a stated reason when it is used up. Each turn's estimated cost is shown in the run graph.",
      },
      {
        heading: "Audit log and traces",
        body:
          "Platform actions are written to an audit log, and workspace admins can watch it as a live tail in the admin console. LLM and tool calls emit OpenTelemetry spans that follow the GenAI semantic conventions, carrying the model and token usage, which is what feeds the platform's own tracing.",
      },
      {
        heading: "Workspace SSO and SCIM",
        body:
          "A workspace can connect its own identity provider for single sign-on and use SCIM to provision and remove users. Both are available. They have not yet been validated against each identity vendor's product, so plan a test with yours before a rollout.",
      },
    ],
    faqs: [
      {
        q: "Can I make an AI agent ask before it uses a tool?",
        a: "Yes. Tools classified as sensitive wait for a person's confirmation by default, and approval policies can be set per tool. The agent proposes the call, a person approves or rejects it, and the decision is logged.",
      },
      {
        q: "How do I cap what AI agents spend?",
        a: "Set per-user daily budgets, and rely on the platform's limit on tool calls per turn and the shared per-run budget for multi-agent delegation. A run that reaches its budget stops with a stated reason.",
      },
      {
        q: "Is there an audit log of agent actions?",
        a: "Yes. Platform actions are recorded in an audit log, and workspace admins can read it as a live tail in the admin console.",
      },
      {
        q: "Does VegaDūta support SSO and SCIM?",
        a: "Workspace single sign-on and SCIM provisioning are available. They have not yet been validated against each identity vendor's product, so test with your provider before relying on them.",
      },
    ],
    related: [
      { href: "/platform/security", label: "Security" },
      { href: "/ai/guardrails", label: "Guardrails" },
      { href: "/platform/observability", label: "Observability" },
      { href: "/platform/agent-teams", label: "Agent teams" },
    ],
  },
  {
    slug: "self-hosting",
    keyTakeaways: [
      "VegaDūta is open source under the Apache-2.0 license.",
      "The supported way to self-host VegaDūta is the Docker Compose stack in the repository's deploy directory.",
      "Kubernetes charts in the repository are drafts and are not a supported install path yet.",
    ],
    citations: [
      { label: "Apache License, Version 2.0", href: "https://www.apache.org/licenses/LICENSE-2.0" },
      { label: "Docker Compose documentation", href: "https://docs.docker.com/compose/" },
    ],
    name: "Open Source & Self-Hosting",
    metaTitle: "Open-Source AI Agent Platform, Self-Hosted | VegaDūta",
    metaDescription:
      "VegaDūta is open source under Apache-2.0 and self-hosts with Docker Compose. Bring your own LLM keys or point it at local models with Ollama.",
    h1: "Open source, and yours to run",
    kicker: "Platform · Open source",
    cardBlurb:
      "Apache-2.0 licensed, with a Docker Compose stack for running the whole platform on your own machines.",
    intro: [
      "VegaDūta is open source under the Apache-2.0 license. You can run the platform yourself with the Docker Compose stack in the repository's deploy directory.",
      "The same code runs in both places. Self-hosting means your conversations, documents, and credentials stay on infrastructure you control.",
    ],
    sections: [
      {
        heading: "What the Compose stack runs",
        body:
          "The stack brings up the platform's services together, including the core API, the web console, the knowledge sidecar that handles document extraction and embeddings, Postgres, Redis, and Keycloak for sign-in. Configuration is by environment file.",
      },
      {
        heading: "Models: your keys or your hardware",
        body:
          "A self-hosted install uses the same 14 bring-your-own-key providers as the hosted service. One of them is Ollama, which points the platform at models running on your own hardware, so an install can work without sending prompts to a hosted model API.",
      },
      {
        heading: "What is not supported yet",
        body:
          "Docker Compose is the install path we support. The repository also contains Helm and Terraform directories; those are drafts and should not be treated as a supported Kubernetes deployment. An optional Temporal-based workflow engine is in the code and is off by default.",
      },
    ],
    faqs: [
      {
        q: "Is VegaDūta open source?",
        a: "Yes. The platform is licensed under Apache-2.0.",
      },
      {
        q: "How do I self-host VegaDūta?",
        a: "With Docker Compose, using the stack in the repository's deploy directory. It runs the core API, web console, knowledge sidecar, Postgres, Redis, and Keycloak, along with supporting services.",
      },
      {
        q: "Can I run VegaDūta on Kubernetes?",
        a: "Not as a supported path today. Helm and Terraform files exist in the repository as drafts. Docker Compose is the supported way to self-host.",
      },
      {
        q: "Can a self-hosted install use local models?",
        a: "Yes. The Ollama connector points the platform at a model server on your own hardware, alongside the other bring-your-own-key providers.",
      },
    ],
    related: [
      { href: "/platform/security", label: "Security" },
      { href: "/ai/llm-connectors", label: "LLM connectors" },
      { href: "/platform/governance", label: "Governance and cost controls" },
      { href: "/docs", label: "Documentation" },
    ],
  },
];
