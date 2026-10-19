// /tutorials hub data: channel setup and first-build tutorials. Re-exported in order by tutorials-data.ts.

import type { HubPage } from "../lib/seo-hub";

export const SETUP_TUTORIALS: HubPage[] = [
  {
    slug: "setup-whatsapp-agent",
    name: "Set up a WhatsApp AI agent",
    metaTitle: "How to Build a WhatsApp AI Agent (No Code) | VegaDūta",
    metaDescription:
      "Set up an AI agent that answers customers on WhatsApp — via the official Meta Cloud API or Twilio. No code, live in minutes on VegaDūta.",
    h1: "How to set up a WhatsApp AI agent",
    kicker: "Tutorial · Channels",
    cardBlurb:
      "Put an AI agent on WhatsApp — Meta Cloud API or Twilio — and have it answering customers today.",
    intro: [
      "By the end of this tutorial you will have an AI agent answering customers on a real WhatsApp number. You describe the agent in one sentence, VegaDūta builds it, and you connect WhatsApp in one of two modes — Meta Cloud API via Embedded Signup, or Twilio. No code is required at any point.",
      "The agent handles text and media and runs behind guardrails you control. If you just want to see it work first, the sandbox at /try needs no account.",
    ],
    steps: [
      {
        name: "Try it or sign up",
        text: "Open the sandbox at /try — no account, no card — to test-drive an agent immediately. When you are ready to connect a real WhatsApp number, create a free account in the web console; the console is where channels live.",
      },
      {
        name: "Describe the agent",
        text: "In the agent builder, type one sentence describing the job — 'Answer order and delivery questions for my kirana store', for example. VegaDūta assembles the agent from that: persona, tools, and default guardrails. You can refine everything afterwards.",
      },
      {
        name: "Pick a WhatsApp connection mode",
        text: "Open the WhatsApp channel settings and choose how to connect. Meta Cloud API via Embedded Signup is the official Business route. Twilio works if your number already lives there.",
      },
      {
        name: "Connect and send a test message",
        text: "Complete the signup flow (or enter your Twilio credentials), then message the number from your own phone. A new sender is held until you approve them, so your first message gets a pairing code instead of an answer. Open Contacts in the console, approve your number under Pending approval, and message again: the agent now replies within moments. Send an image or a voice note too — media is supported on the channel.",
      },
      {
        name: "Approve new senders",
        text: "Every new person who writes in is held until you approve them, on every channel, and you are not notified when that happens. Check Contacts > Pending approval after connecting and whenever someone says the agent is not replying. Approving lets the agent answer their next message; earlier messages are not replayed. If you would rather not approve each person, set the channel to auto-approve new senders in Contacts.",
      },
      {
        name: "Set guardrails and approvals",
        text: "Before real customers arrive, open the agent's guardrails and mark which actions need a human sign-off. The agent will pause and ask before doing anything you flagged as risky, instead of acting on its own.",
      },
      {
        name: "Go live",
        text: "Share the number with customers. The agent answers around the clock, and everything it does shows up in the conversation history in your console.",
      },
    ],
    sections: [
      {
        heading: "Troubleshooting",
        body: "Most WhatsApp setup problems come down to the connection mode, not the agent.",
        bullets: [
          "Agent not replying to a new customer: they are probably waiting for approval. Check Contacts > Pending approval, and Approvals if the pairing reply never reached them.",
          "Meta Cloud API messages not arriving: finish every step of Embedded Signup, including phone-number verification on Meta's side.",
          "Agent replies in the wrong language: state the reply language in the agent's instructions if you want one fixed.",
        ],
      },
    ],
    faqs: [
      {
        q: "Do I need the WhatsApp Business API to use an AI agent?",
        a: "Yes. VegaDūta connects through official WhatsApp Business Platform paths only — Meta's WhatsApp Cloud API (Embedded Signup makes this a guided flow) or a Twilio WhatsApp sender. Unofficial personal-number bridges violate WhatsApp's Terms of Service, so they are not supported.",
      },
      {
        q: "Can the WhatsApp agent handle images and voice notes?",
        a: "Yes. Media is supported on the WhatsApp channel, so customers can send images and voice notes and the agent can work with them. Replies can include media as well.",
      },
      {
        q: "What languages does the WhatsApp agent speak?",
        a: "Reply language depends on the language model the agent runs on, and you can state a fixed reply language in the agent's instructions. The Sarvam integration adds translation and Indian-language speech tools. Separately, the VegaDūta console interface is available in 30 languages, 22 of them Indian.",
      },
      {
        q: "How do I stop the agent from doing something risky on WhatsApp?",
        a: "Use guardrails and approval rules. Any action you mark as needing sign-off makes the agent pause and ask a human before proceeding. That applies on WhatsApp exactly as it does on every other channel.",
      },
    ],
    related: [
      { href: "/integrations/whatsapp", label: "WhatsApp integration" },
      { href: "/whatsapp-cost-calculator", label: "WhatsApp cost calculator" },
      { href: "/tutorials/build-your-first-agent", label: "Build your first AI agent" },
      { href: "/ai/guardrails", label: "Guardrails" },
    ],
  },
  {
    slug: "setup-telegram-bot",
    name: "Set up a Telegram bot agent",
    metaTitle: "How to Set Up a Telegram Bot AI Agent | VegaDūta",
    metaDescription:
      "Create a bot with @BotFather, paste the token into VegaDūta, and the webhook registers automatically. Your AI agent answers in Telegram in minutes.",
    h1: "How to set up a Telegram bot agent",
    kicker: "Tutorial · Channels",
    cardBlurb:
      "Turn a @BotFather token into a working AI agent on Telegram — the webhook is registered for you.",
    intro: [
      "This tutorial takes you from nothing to an AI agent answering messages in Telegram. You create a bot with Telegram's own @BotFather, paste the bot token into VegaDūta's Telegram channel settings, and the platform registers the webhook for you — there is no server to run and no code to write. Tokens are stored per workspace, so your bot stays yours.",
    ],
    steps: [
      {
        name: "Create the bot with @BotFather",
        text: "In Telegram, open a chat with @BotFather and send /newbot. Give the bot a display name and a username ending in 'bot'. BotFather replies with an API token — that token is the only credential you need.",
      },
      {
        name: "Copy the bot token",
        text: "Copy the token exactly as BotFather sent it. Treat it like a password: anyone holding it controls the bot. You can revoke and reissue it from @BotFather at any time if it leaks.",
      },
      {
        name: "Paste the token into VegaDūta",
        text: "In the console, open Channels, choose Telegram, and paste the token into the channel settings. Credentials are stored per tenant, so each workspace connects its own bot. Save, and pick which agent answers on this channel.",
      },
      {
        name: "Let the platform register the webhook",
        text: "On save, VegaDūta registers the Telegram webhook for the bot automatically. There is nothing to host and no polling loop to babysit — Telegram starts delivering messages to the platform immediately.",
      },
      {
        name: "Test in Telegram",
        text: "Open your bot in Telegram and send it a message. The agent should reply within moments. If nothing comes back, re-check the token in the channel settings — a stale or revoked token is the usual culprit.",
      },
      {
        name: "Optionally wire it into a workflow",
        text: "The Telegram channel can also act as a workflow trigger. In the workflow builder, choose Telegram as the trigger and incoming messages will start an automated run — useful for intake forms, alerts, or routing.",
      },
    ],
    sections: [
      {
        heading: "Going further",
        body: "A Telegram bot is often the fastest channel to stand up, which makes it a good place to iterate on the agent itself.",
        bullets: [
          "Refine the agent's instructions and knowledge in the builder — changes apply to the connected bot immediately.",
          "Add guardrails so the bot asks before taking consequential actions.",
          "Connect the same agent to WhatsApp or other channels once you are happy with its behaviour.",
        ],
      },
    ],
    faqs: [
      {
        q: "Do I need to host a server for a Telegram bot?",
        a: "No. VegaDūta registers the Telegram webhook for you when you save the token, and the platform receives and answers messages on the bot's behalf. There is no code to deploy and no polling process to run.",
      },
      {
        q: "Where do I get a Telegram bot token?",
        a: "From @BotFather, Telegram's official bot for creating bots. Send it /newbot, choose a name and username, and it replies with the API token. Paste that token into VegaDūta's Telegram channel settings.",
      },
      {
        q: "Is my bot token shared with other VegaDūta customers?",
        a: "No. Channel credentials are stored per tenant, so your token belongs to your workspace alone and connects only your bot. You can replace it any time by issuing a new token from @BotFather.",
      },
      {
        q: "Can a Telegram message trigger an automated workflow?",
        a: "Yes. Telegram is one of the channels that can act as a workflow trigger, so an incoming message can start a multi-step automated run — an agent step, conditions, approvals, and so on — instead of just a chat reply.",
      },
    ],
    related: [
      { href: "/integrations/telegram", label: "Telegram integration" },
      { href: "/tutorials/setup-whatsapp-agent", label: "Set up a WhatsApp AI agent" },
      { href: "/tutorials/create-a-workflow", label: "Create an automated workflow" },
      { href: "/platform/workflow-automation", label: "Workflow automation" },
    ],
  },
  {
    slug: "build-your-first-agent",
    name: "Build your first AI agent",
    metaTitle: "How to Build Your First AI Agent (No Code) | VegaDūta",
    metaDescription:
      "Describe a job in one sentence and VegaDūta builds the agent — LLM, knowledge, guardrails included. Test in the sandbox at /try, then deploy to any channel.",
    h1: "How to build your first AI agent",
    kicker: "Tutorial · Getting started",
    cardBlurb:
      "From one plain-language sentence to a deployed, knowledge-backed AI agent — no code and no account needed to start.",
    intro: [
      "In this tutorial you will build a working AI agent from a single plain-language sentence, teach it your documents, and deploy it to a channel — without writing code. Open the sandbox at /try — no account, no card. Type one sentence describing the job, and VegaDūta assembles the agent: tools, guardrails, and a persona you can test immediately.",
      "The free tier runs on the platform-hosted model pool, so there is nothing to configure before your first conversation. When you want a specific model, you can bring your own key from any of 14 providers.",
    ],
    steps: [
      {
        name: "Open the sandbox or sign up",
        text: "Go to /try to build without an account, or sign up in the web console if you want the agent to persist in your workspace. Everything below works the same in both places.",
      },
      {
        name: "Describe the job in plain language",
        text: "Type one sentence: who the agent is and what it does. 'A support agent for my tailoring shop that answers questions about orders and alterations' is plenty. The builder reads it and configures the agent — persona, tools, and sensible default guardrails.",
      },
      {
        name: "Pick a model",
        text: "On the free tier the agent runs on the platform-hosted model pool with nothing to set up. If you prefer a specific provider, open Providers and add your own API key — 14 providers are supported, from Anthropic and OpenAI to Ollama and Sarvam.",
      },
      {
        name: "Add knowledge documents",
        text: "Upload the documents the agent should know — price lists, policies, FAQs. VegaDūta indexes them for retrieval-augmented generation, so answers are grounded in your material rather than the model's general knowledge.",
      },
      {
        name: "Test in the playground",
        text: "Chat with the agent in the playground before anyone else does. Ask the awkward questions your customers actually ask. Adjust the instructions and re-test until the answers hold up.",
      },
      {
        name: "Deploy to a channel or share a link",
        text: "Connect the agent to WhatsApp, Telegram, or another channel from the console — or share it with a public link so anyone can talk to it in the browser. Guardrails follow the agent to every channel.",
      },
    ],
    sections: [
      {
        heading: "Going further",
        body: "The one-sentence build is a starting point, not a ceiling.",
        bullets: [
          "Give the agent tools via MCP servers to let it act, not just answer.",
          "Chain it into multi-step workflows with conditions and human approvals.",
          "Open the Agent Lab from the playground to see each step of a turn and test which parts of the prompt matter.",
          "Add voice so the same agent can take phone or in-app calls.",
        ],
      },
    ],
    faqs: [
      {
        q: "Can I build an AI agent without writing code?",
        a: "Yes. You describe the job in one plain-language sentence and VegaDūta configures the agent — persona, tools, and guardrails. Refinement happens through the console, not code, and the sandbox at /try lets you do all of this without even creating an account.",
      },
      {
        q: "Do I need my own OpenAI or Anthropic API key to start?",
        a: "No. The free tier runs on the platform-hosted model pool, so your first agent works with zero provider setup. When you want a specific model, you can bring your own key from any of 14 supported providers.",
      },
      {
        q: "How do I make the agent answer from my own documents?",
        a: "Upload them as knowledge documents in the builder. VegaDūta indexes the content for retrieval-augmented generation, so the agent grounds its answers in your material. You can add and remove documents at any time.",
      },
      {
        q: "How do people actually talk to the agent once it's built?",
        a: "Deploy it to a channel — WhatsApp, Telegram, and others — or share a public link that opens a chat in the browser. The same agent, knowledge, and guardrails apply wherever it runs.",
      },
    ],
    related: [
      { href: "/try", label: "Try the sandbox" },
      { href: "/tutorials/setup-whatsapp-agent", label: "Set up a WhatsApp AI agent" },
      { href: "/tutorials/connect-llm-provider", label: "Connect your own LLM provider" },
      { href: "/ai/rag", label: "RAG on VegaDūta" },
      { href: "/tutorials/debug-an-agent-with-agent-lab", label: "Debug an agent with Agent Lab" },
      { href: "/templates", label: "Agent templates" },
    ],
  },
  {
    slug: "connect-mcp-server",
    name: "Connect an MCP server",
    metaTitle: "How to Connect an MCP Server to Your AI Agent | VegaDūta",
    metaDescription:
      "Browse the official MCP registry or register your own STDIO or remote server. Credentials stay encrypted and tools appear on your agent behind guardrails.",
    h1: "How to connect an MCP server to your agent",
    kicker: "Tutorial · Tools & MCP",
    cardBlurb:
      "Give your agent real tools by connecting MCP servers — from the official registry or your own — with encrypted credentials and guardrails in front.",
    intro: [
      "This tutorial shows you how to give a VegaDūta agent real tools by connecting an MCP (Model Context Protocol) server. You either pick a server from the official MCP registry — browsable and searchable straight from the console — or register your own STDIO or remote server. Once connected, the server's tools appear on your agent, sitting behind guardrails and optional human approval.",
      "Security is built into the flow rather than bolted on: credentials are encrypted at rest, STDIO servers run sandboxed against a command allowlist, remote connections pass SSRF guards, and some registrations go through an admin review before they activate.",
    ],
    steps: [
      {
        name: "Open the MCP registry screen",
        text: "In the console, open the MCP registry. This is the one place for both directions: browsing servers others have published and registering servers of your own.",
      },
      {
        name: "Browse the official registry or go custom",
        text: "Search the official MCP registry — the catalog is cached locally, so browsing is fast. If the server you need is not listed, or it is your own, choose 'register custom server' instead.",
      },
      {
        name: "Enter the server details",
        text: "For a STDIO server, enter the command and its arguments. For a remote server, enter the URL — SSE and streamable HTTP are both supported — plus any credentials it needs. Credentials are encrypted at rest and sent as headers to remote servers; they are never exposed to the agent as plain text.",
      },
      {
        name: "Submit for review where required",
        text: "Some registrations — depending on your workspace's policy — go to an admin for review before they activate. STDIO servers additionally run sandboxed and must match a command allowlist; remote URLs pass SSRF guards. If review is required, the connection shows as pending until approved.",
      },
      {
        name: "Watch the connection go live",
        text: "Once approved, the connection activates with liveness tracking, so you can see at a glance whether the server is up. A failing server shows its status here rather than failing silently mid-conversation.",
      },
      {
        name: "Use the tools from your agent",
        text: "The server's tools now appear on your agent. Every call goes through guardrails, and you can require human approval for specific tools, so the agent asks before doing anything consequential. Test a tool call in the playground before going live.",
      },
    ],
    sections: [
      {
        heading: "Going further",
        body: "MCP is how a VegaDūta agent stops being a chatbot and starts doing work.",
        bullets: [
          "Atlassian (Jira and Confluence) connects in one click over OAuth, with no API token to copy.",
          "Combine MCP tools with workflow approval nodes for actions that must always be signed off.",
          "Register internal servers to expose your own systems to agents without opening them to the internet.",
        ],
      },
    ],
    faqs: [
      {
        q: "What is an MCP server and why would my agent need one?",
        a: "MCP (Model Context Protocol) is an open standard for giving AI agents tools — actions they can take and data they can read. Connecting an MCP server to a VegaDūta agent gives it those tools, so it can act on systems like calendars, databases, or issue trackers instead of only answering questions.",
      },
      {
        q: "Are my MCP server credentials stored safely?",
        a: "Credentials are encrypted at rest and, for remote servers, sent as request headers when the platform calls the server. They are never handed to the model as plain text. You can rotate or remove them from the registry screen at any time.",
      },
      {
        q: "Can I connect a local STDIO MCP server, or only remote ones?",
        a: "Both. STDIO servers are registered with a command and arguments and run sandboxed against a command allowlist; remote servers connect over SSE or streamable HTTP behind SSRF guards. Both kinds get liveness tracking once connected.",
      },
      {
        q: "How do I stop an MCP tool from being used without permission?",
        a: "Every MCP tool call already passes through the agent's guardrails, and you can additionally require human approval for specific tools. The agent then pauses and asks before executing, which is the recommended setup for anything that changes state.",
      },
    ],
    related: [
      { href: "/ai/mcp", label: "MCP on VegaDūta" },
      { href: "/integrations/atlassian", label: "Jira & Confluence integration" },
      { href: "/ai/guardrails", label: "Guardrails" },
      { href: "/tutorials/build-your-first-agent", label: "Build your first AI agent" },
    ],
  },
  {
    slug: "create-a-workflow",
    name: "Create an automated workflow",
    metaTitle: "How to Create an Automated AI Workflow | VegaDūta",
    metaDescription:
      "Build an automated workflow in VegaDūta: pick a trigger, add agent and approval nodes, pin test data, run the draft, then go live. Start from 240+ recipes.",
    h1: "How to create an automated workflow",
    kicker: "Tutorial · Automation",
    cardBlurb:
      "Chain agents, conditions, and human approvals into an automated workflow — starting from a recipe or a blank canvas.",
    intro: [
      "This tutorial walks you through building an automated workflow in VegaDūta: a multi-step process that runs on its own, triggered by a webhook or an incoming message, with AI agent steps and human approvals where you want them. You can start from one of more than 240 ready-made recipes or from a blank canvas, test the draft with pinned data before any live trigger touches it, and pause a run for a person wherever you choose.",
    ],
    steps: [
      {
        name: "Start from a recipe or a blank canvas",
        text: "Browse the recipe library at /help/workflow-recipes — more than 240 recipes organized into about 90 industries — and pick one close to your process, or open a blank canvas in the workflow builder. Recipes are editable, not fixed.",
      },
      {
        name: "Choose a trigger",
        text: "Decide what starts a run: a webhook call from another system, or an incoming message on any of 11 channels, including WhatsApp, Telegram, and email. One workflow, one trigger; you can build sibling workflows for other entry points.",
      },
      {
        name: "Add the nodes",
        text: "Drag in the steps: agent nodes that think and act, condition nodes that branch, knowledge nodes that retrieve from your documents, plus code, loop, parallel, and sub-flow nodes for more involved logic — 21 node types in all. Connect them in the order the work should flow.",
      },
      {
        name: "Pass data between steps with expressions",
        text: "Fields on a node accept expressions that read the trigger payload and earlier nodes' outputs. The field previews the result as you type, and the lint panel lists problems before you run. The full syntax is documented at /help/workflow-expressions.",
      },
      {
        name: "Pin an output while you build",
        text: "Test a step, then choose Pin output on it. Draft runs and step tests now use that saved output instead of running the node again, so you can build the steps after a slow or costly node without calling it each time. A pinned node shows a Pinned badge; Unpin removes it. Live runs never use pins.",
      },
      {
        name: "Add an approval node where a human should sign off",
        text: "Anywhere the workflow is about to do something consequential — send an external message, change a record — insert an approval node. The run pauses there until a person approves or rejects, then continues down the matching branch.",
      },
      {
        name: "Run the draft and watch the execution",
        text: "Run the draft and watch it execute step by step. If any nodes are pinned, the run is labelled as a draft run that used pinned data, so a green result is not mistaken for a real end-to-end run. A run that reaches an approval node is parked there and continues from that node once someone decides. When the draft behaves, promote the version to production; webhooks, schedules, and channel triggers run the production version.",
      },
      {
        name: "Share it if you want to",
        text: "A workflow can be shared with a one-tap public link, letting someone outside your console kick off a run. Keep approval nodes in place on shared workflows so public triggers still pass through your sign-off.",
      },
    ],
    sections: [
      {
        heading: "Troubleshooting",
        body: "When a workflow does not behave, look at the run history before editing the graph.",
        bullets: [
          "A run stuck 'waiting' is usually parked at an approval node — check the approvals queue.",
          "A trigger that never fires is usually a channel not yet connected, or a webhook URL not yet called by the other system.",
          "Test condition branches with runs that deliberately take each path; an untested branch is where surprises live.",
        ],
      },
    ],
    faqs: [
      {
        q: "What can trigger a workflow in VegaDūta?",
        a: "A webhook call from any external system, or an incoming message on any of 11 supported channels — including WhatsApp, Telegram, and email. That means a customer message can start a full multi-step process, not just a chat reply.",
      },
      {
        q: "Can a human approve a step before the workflow continues?",
        a: "Yes. Insert an approval node at any point and the run pauses there until a person approves or rejects it. The paused state is stored on the run record, and the run continues from that node once a decision arrives.",
      },
      {
        q: "Can I test a workflow without calling every step for real?",
        a: "Yes. Pin a node's output and draft runs use the saved data instead of running that node. The run summary states how many nodes were pinned. Live runs from webhooks, schedules, channels, and share links always run every node.",
      },
      {
        q: "Do I have to build workflows from scratch?",
        a: "No. There are more than 240 ready-made recipes, organized into about 90 industries, at /help/workflow-recipes. Pick the closest one, then edit its triggers and nodes to fit your process — a recipe is a starting point, not a locked template.",
      },
    ],
    related: [
      { href: "/help/workflow-recipes", label: "Workflow recipe library" },
      { href: "/help/workflow-expressions", label: "Expressions and pinning guide" },
      { href: "/platform/workflow-automation", label: "Workflow automation" },
      { href: "/platform/webhooks", label: "Webhooks" },
      { href: "/tutorials/connect-mcp-server", label: "Connect an MCP server" },
    ],
  },
];
