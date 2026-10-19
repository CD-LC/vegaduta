// /integrations hub data: messaging channels (WhatsApp, Telegram, Slack, Discord, Instagram, Messenger, X, email, SMS). Re-exported in order by integrations-data.ts.

import type { HubPage } from "../lib/seo-hub";

export const MESSAGING_INTEGRATIONS: HubPage[] = [
  {
    slug: "whatsapp",
    name: "WhatsApp",
    metaTitle: "WhatsApp AI Agent & Automation | VegaDūta",
    metaDescription:
      "Connect an AI agent to WhatsApp via the official Meta Cloud API or Twilio. Workflow triggers, media support, guardrails and approvals.",
    h1: "WhatsApp AI agents on VegaDūta",
    kicker: "Integrations · Messaging channels",
    cardBlurb:
      "Put an AI agent on WhatsApp two ways — the Meta Cloud API or Twilio.",
    intro: [
      "VegaDūta connects an AI agent to WhatsApp in two ways: the official Meta WhatsApp Cloud API with Embedded Signup, or Twilio's WhatsApp channel. Once connected, every inbound message routes to your agent, which replies in the conversation and can send media. WhatsApp is VegaDūta's flagship channel and also works as a workflow trigger, so a message can start an automated multi-step process.",
      "You build the agent in plain language, attach it to the channel, and the platform handles webhooks, signature validation, and delivery. Try it without signing up at /try, or follow the step-by-step tutorial to go live on your own number.",
    ],
    sections: [
      {
        heading: "Two ways to connect",
        body: "Pick the connection mode that matches where you are. Both route into the same agent runtime, so you can start on Twilio and move to the Cloud API later without rebuilding anything.",
        bullets: [
          "Meta WhatsApp Cloud API — official Business API with Meta Embedded Signup onboarding, webhook signature validation, and media send.",
          "Twilio WhatsApp — connect through an existing Twilio WhatsApp sender if that is where your number lives.",
        ],
      },
      {
        heading: "What the agent can do on WhatsApp",
        body: "The agent holds real conversations: it sends and receives media, and can call tools mid-conversation — look something up, update a record, run a workflow. Guardrails (PII, toxicity, jailbreak detection) and human approval gates apply to tool calls the same as on every channel.",
      },
      {
        heading: "WhatsApp as a workflow trigger",
        body: "An inbound WhatsApp message can start a workflow, not just a chat reply. That is how teams build intake flows — a customer messages, the workflow classifies the request, gathers details, and hands off or resolves it. See the workflow recipes for worked examples.",
      },
      {
        heading: "What this integration does not do",
        body: "VegaDūta does not offer broadcast or bulk template campaigns — this is a conversational agent product, not a blast tool. WhatsApp group automation is also not possible through the official API, so agents work in one-to-one conversations. If Meta conversation charges matter to you, the cost calculator covers Meta's 2026 India rates.",
      },
    ],
    faqs: [
      {
        q: "Can I connect WhatsApp to an AI agent without the Business API?",
        a: "No. VegaDūta only uses official WhatsApp Business Platform paths — Meta's WhatsApp Cloud API or a Twilio WhatsApp sender. Unofficial personal-number bridges violate WhatsApp's Terms of Service and risk a ban on the number, so they are not supported.",
      },
      {
        q: "Does VegaDūta support the official WhatsApp Cloud API?",
        a: "Yes. The Meta WhatsApp Cloud API is a first-class connection mode with Meta Embedded Signup for onboarding, webhook signature validation on every inbound event, and media send support. Twilio's WhatsApp channel is also supported as a second option.",
      },
      {
        q: "Can a WhatsApp AI agent reply in Indian languages?",
        a: "Yes, as far as the language model you choose supports them, and the Sarvam AI integration adds speech-to-text, translation, and transliteration tools built for Indian languages, plus Sarvam's own chat models. The console interface itself is available in 30 languages, 22 of them Indian.",
      },
      {
        q: "Can VegaDūta send WhatsApp broadcasts or automate groups?",
        a: "No, and we say so plainly. Broadcast and bulk template campaigns are not offered, and group automation is not possible through the official WhatsApp API. VegaDūta agents work in one-to-one conversations, where they can also trigger workflows.",
      },
    ],
    related: [
      { href: "/tutorials/setup-whatsapp-agent", label: "Tutorial: set up a WhatsApp agent" },
      { href: "/whatsapp-cost-calculator", label: "WhatsApp cost calculator (Meta 2026 India rates)" },
      { href: "/ai/agents", label: "How VegaDūta agents work" },
      { href: "/platform/workflow-automation", label: "Workflow automation" },
      { href: "/try", label: "Try it without signing up" },
    ],
  },
  {
    slug: "telegram",
    name: "Telegram",
    metaTitle: "Telegram Bot AI Agent Integration | VegaDūta",
    metaDescription:
      "VegaDūta connects AI agents to Telegram through the official Bot API: per-tenant bot tokens, webhook inbound, outbound messaging, and workflow triggers.",
    h1: "Telegram AI agents on VegaDūta",
    kicker: "Integrations · Messaging channels",
    cardBlurb:
      "Turn a BotFather bot into a full AI agent with webhooks, outbound messaging, and workflow triggers.",
    intro: [
      "VegaDūta connects an AI agent to Telegram through the official Bot API. You create a bot with BotFather, paste the token into the channel settings, and the platform registers the webhook and routes every message to your agent — replies, media, and workflow triggers included. Bot credentials are stored per tenant, so each workspace runs its own bots in isolation.",
      "Beyond answering chats, agents can send Telegram messages as a tool — meaning any agent or workflow can push a notification to a Telegram chat as one step of a larger process.",
    ],
    sections: [
      {
        heading: "How the connection works",
        body: "Inbound messages arrive on a webhook the platform registers for your bot; outbound replies go through VegaDūta's Telegram Bot API client. There is nothing to host yourself — the webhook endpoint, retries, and routing are all platform-managed.",
        bullets: [
          "Official Telegram Bot API — no unofficial clients or session hacks.",
          "Per-tenant bot credentials, isolated per workspace.",
          "Inbound webhook plus a full outbound client for replies.",
        ],
      },
      {
        heading: "Telegram as a workflow trigger",
        body: "A Telegram message can start a workflow, the same as WhatsApp or any of the other trigger channels. Use it for intake bots, support triage, or any flow where a chat message should kick off a multi-step process.",
      },
      {
        heading: "Sending Telegram messages as an agent tool",
        body: "Agents can call a send-Telegram-message tool, so Telegram works as an output channel even in conversations that started elsewhere. A workflow triggered by email or a webhook can end by notifying a Telegram chat.",
      },
    ],
    faqs: [
      {
        q: "How do I connect a Telegram bot to an AI agent?",
        a: "Create a bot with BotFather in Telegram, copy the token it gives you, and paste it into the Telegram channel settings in the VegaDūta console. The platform registers the webhook and routes messages to whichever agent you attach. The tutorial walks through it end to end.",
      },
      {
        q: "Does VegaDūta use the official Telegram Bot API?",
        a: "Yes. The integration is an inbound webhook plus an outbound Telegram Bot API client — entirely on Telegram's official bot platform. No unofficial user-session clients are involved.",
      },
      {
        q: "Can a Telegram message trigger an automated workflow?",
        a: "Yes. Telegram is one of VegaDūta's workflow trigger channels, so an inbound message can start a workflow rather than just a chat reply. That covers intake, triage, and hand-off flows.",
      },
      {
        q: "Can I run multiple Telegram bots on one VegaDūta account?",
        a: "Bot credentials are stored per tenant, so your workspace's bots are isolated from every other tenant's. You attach each bot to an agent through the channel settings in the console.",
      },
    ],
    related: [
      { href: "/tutorials/setup-telegram-bot", label: "Tutorial: set up a Telegram bot" },
      { href: "/platform/workflow-automation", label: "Workflow automation" },
      { href: "/integrations/whatsapp", label: "WhatsApp integration" },
      { href: "/try", label: "Try it without signing up" },
    ],
  },
  {
    slug: "slack",
    name: "Slack",
    metaTitle: "Slack AI Agent Integration | VegaDūta",
    metaDescription:
      "Connect an AI agent to Slack with signature-validated event webhooks, outbound messages, and Slack-triggered workflows — set up from the VegaDūta console.",
    h1: "Slack AI agents on VegaDūta",
    kicker: "Integrations · Messaging channels",
    cardBlurb:
      "An AI agent in your Slack workspace — signature-validated events in, messages out, workflows triggered.",
    intro: [
      "VegaDūta connects an AI agent to Slack through Slack's event webhook system. Inbound events are verified with Slack's request-signature validation before they reach your agent, the agent replies through outbound Slack messages, and any Slack message can trigger a workflow. Setup happens in the console's channel settings — the platform hosts the webhook endpoint for you.",
    ],
    sections: [
      {
        heading: "How the connection works",
        body: "You point your Slack app's event subscription at the platform-hosted endpoint and add the signing secret to the channel settings. Every inbound request is checked against Slack's request signature, so spoofed events are rejected before they touch an agent.",
        bullets: [
          "Event webhook inbound with Slack request-signature validation.",
          "Outbound message sending for agent replies.",
          "Slack as a workflow trigger channel.",
        ],
      },
      {
        heading: "What teams use it for",
        body: "The common pattern is an agent that lives where the team already talks: answering internal questions, running intake, or surfacing information from tools the agent is connected to. Because agents are built in plain language, the Slack channel is just where the conversation happens — the agent's skills come from its tools and knowledge.",
      },
      {
        heading: "Slack messages as workflow triggers",
        body: "A Slack message can start a workflow the same way a WhatsApp or Telegram message can. That makes Slack a control surface for automation: a message in a channel kicks off a multi-step process, with guardrails and human approval gates applying to any tool calls along the way.",
      },
    ],
    faqs: [
      {
        q: "How do I connect Slack to an AI agent?",
        a: "Create a Slack app, subscribe it to message events pointed at VegaDūta's platform-hosted endpoint, and add the credentials in the console's channel settings. Messages then route to whichever agent you attach, and its replies post back to Slack.",
      },
      {
        q: "Is the Slack integration secure?",
        a: "Inbound events are verified with Slack's request-signature validation, so requests that do not carry a valid signature are rejected. On top of that, platform guardrails — PII, toxicity, and jailbreak detection — and human approval gates apply to agent tool calls.",
      },
      {
        q: "Can a Slack message trigger an automated workflow?",
        a: "Yes. Slack is one of VegaDūta's workflow trigger channels, so an inbound message can start a workflow — intake, triage, approvals, or any multi-step process you have built.",
      },
      {
        q: "Does the same agent work on Slack and WhatsApp?",
        a: "Yes. Channels are attachments, not silos — one agent can be connected to Slack, WhatsApp, Telegram, and the rest of the channel lineup at once, holding conversations on each.",
      },
    ],
    related: [
      { href: "/platform/workflow-automation", label: "Workflow automation" },
      { href: "/ai/agents", label: "How VegaDūta agents work" },
      { href: "/integrations/discord", label: "Discord integration" },
      { href: "/try", label: "Try it without signing up" },
    ],
  },
  {
    slug: "discord",
    name: "Discord",
    metaTitle: "Discord AI Agent & Bot Integration | VegaDūta",
    metaDescription:
      "VegaDūta runs a real-time Discord Gateway client, not just webhooks — AI agents read and reply to messages live and can trigger workflows from any server.",
    h1: "Discord AI agents on VegaDūta",
    kicker: "Integrations · Messaging channels",
    cardBlurb:
      "A real-time Gateway connection — not just webhooks — puts a live AI agent inside your Discord server.",
    intro: [
      "VegaDūta connects an AI agent to Discord through a real-time Gateway (WebSocket) client, not just incoming webhooks. The platform holds a live connection to Discord, so your agent sees messages as they happen and replies in the flow of conversation. Webhook handling is supported too, and Discord messages can trigger workflows.",
    ],
    sections: [
      {
        heading: "Gateway client, not just webhooks",
        body: "Many integrations stop at webhooks, which limits what a bot can see and how fast it responds. VegaDūta maintains a persistent Gateway WebSocket connection to Discord, the same mechanism native Discord bots use, so the agent participates in real time.",
        bullets: [
          "Persistent Gateway (WebSocket) connection for live message events.",
          "Webhook handling supported alongside the Gateway client.",
          "Discord as a workflow trigger channel.",
        ],
      },
      {
        heading: "What the agent does in a server",
        body: "Attach an agent to the Discord channel and it answers questions, holds conversations, and calls its tools mid-chat — with the platform's guardrails and human approval gates applying to tool calls, same as everywhere else. The agent itself is built in plain language in the agent builder.",
      },
      {
        heading: "Discord messages as workflow triggers",
        body: "A Discord message can start a workflow — useful for community support intake or moderation-adjacent flows where a message should kick off a structured, multi-step process rather than a one-off reply.",
      },
    ],
    faqs: [
      {
        q: "Does VegaDūta connect to Discord in real time?",
        a: "Yes. The integration runs a real-time Gateway (WebSocket) client — the same connection type native Discord bots use — so the agent receives message events live rather than waiting on webhook deliveries. Webhook handling is also supported.",
      },
      {
        q: "Can I put an AI agent in my Discord server?",
        a: "Yes. You add the bot to your server, connect the credentials in VegaDūta's channel settings, and attach an agent. The agent then reads and replies to messages through the live Gateway connection.",
      },
      {
        q: "Can a Discord message trigger a workflow?",
        a: "Yes. Discord is one of VegaDūta's workflow trigger channels, so an inbound message can start a workflow — intake, triage, or any multi-step automation you have defined.",
      },
      {
        q: "Is the Discord agent the same agent I use on other channels?",
        a: "It can be. Agents and channels are decoupled: one agent can be attached to Discord, Slack, WhatsApp, and more simultaneously, so you build the agent once and deploy it wherever your users are.",
      },
    ],
    related: [
      { href: "/integrations/slack", label: "Slack integration" },
      { href: "/platform/workflow-automation", label: "Workflow automation" },
      { href: "/platform/agent-builder", label: "Agent builder" },
      { href: "/try", label: "Try it without signing up" },
    ],
  },
  {
    slug: "instagram",
    name: "Instagram",
    metaTitle: "Instagram DM AI Agent & Automation | VegaDūta",
    metaDescription:
      "Automate Instagram DMs with an AI agent via the official Meta Graph API. VegaDūta answers direct messages and can trigger workflows from Instagram events.",
    h1: "Instagram DM automation on VegaDūta",
    kicker: "Integrations · Messaging channels",
    cardBlurb:
      "AI-answered Instagram DMs through the official Meta Graph API, with workflow triggers built in.",
    intro: [
      "VegaDūta automates Instagram direct messages through the official Meta Graph API. You connect your Instagram professional account, and inbound DMs route to your agent, which replies in the conversation. An Instagram DM can also trigger a workflow, turning a message into a structured multi-step process.",
    ],
    sections: [
      {
        heading: "Official API, not automation hacks",
        body: "The integration uses Meta's Graph API for Instagram messaging — the sanctioned path for business DM automation. That keeps your account on the right side of Meta's platform rules, unlike browser-automation or session-token approaches.",
        bullets: [
          "Official Meta Graph API connection.",
          "Inbound DM routing to your agent, outbound replies.",
          "Instagram as a workflow trigger channel.",
        ],
      },
      {
        heading: "What an Instagram agent handles",
        body: "Product questions, order status lookups, lead qualification — anything your agent has the tools and knowledge for. Instagram is a high-volume inbound channel for Indian businesses, and an agent answers every DM promptly instead of letting them pile up.",
      },
      {
        heading: "DMs as workflow triggers",
        body: "An inbound DM can start a workflow: capture the lead, gather details, create a record via a connected tool, and escalate to a human when needed. Human approval gates apply to sensitive tool calls, so automation never runs away from you.",
      },
    ],
    faqs: [
      {
        q: "Can an AI agent reply to Instagram DMs automatically?",
        a: "Yes. VegaDūta routes inbound Instagram DMs to your agent through the official Meta Graph API, and the agent replies in the conversation. You control what it knows and which tools it can use through the agent builder.",
      },
      {
        q: "Is Instagram DM automation allowed by Meta?",
        a: "VegaDūta uses the official Meta Graph API for Instagram messaging, which is Meta's sanctioned path for business DM automation. The integration does not use unofficial browser automation or session-token tricks.",
      },
      {
        q: "Can an Instagram DM trigger a workflow?",
        a: "Yes. Instagram is one of VegaDūta's workflow trigger channels, so a DM can start a workflow — lead capture, support intake, or any multi-step flow you have built.",
      },
      {
        q: "Does the Instagram agent speak Indian languages?",
        a: "Reply language depends on the language model the agent runs on, on Instagram as on every channel, and the Sarvam AI integration adds translation and transliteration tools built for Indian languages.",
      },
    ],
    related: [
      { href: "/integrations/facebook-messenger", label: "Facebook Messenger integration" },
      { href: "/integrations/whatsapp", label: "WhatsApp integration" },
      { href: "/platform/workflow-automation", label: "Workflow automation" },
      { href: "/try", label: "Try it without signing up" },
    ],
  },
  {
    slug: "facebook-messenger",
    name: "Facebook Messenger",
    metaTitle: "Facebook Messenger AI Agent | VegaDūta",
    metaDescription:
      "Connect Facebook Messenger to an AI agent via the official Meta Graph API. VegaDūta answers Page messages and can trigger workflows from Messenger events.",
    h1: "Facebook Messenger AI agents on VegaDūta",
    kicker: "Integrations · Messaging channels",
    cardBlurb:
      "AI-answered Facebook Page messages through the official Meta Graph API, with workflow triggers.",
    intro: [
      "VegaDūta connects an AI agent to Facebook Messenger through the official Meta Graph API. Messages sent to your Facebook Page route to your agent, which replies in the conversation. Messenger is also a workflow trigger channel, so an inbound message can start an automated multi-step process.",
    ],
    sections: [
      {
        heading: "How the connection works",
        body: "You connect your Facebook Page and the platform handles the Graph API plumbing: inbound message events route to your attached agent, and the agent's replies post back through the API. There is no separate bot infrastructure to host.",
        bullets: [
          "Official Meta Graph API connection for Page messaging.",
          "Inbound routing to your agent, outbound replies.",
          "Messenger as a workflow trigger channel.",
        ],
      },
      {
        heading: "What a Messenger agent handles",
        body: "Pre-sales questions, support requests, lead qualification from Page visitors — the agent answers with whatever knowledge and tools you have given it. It is the same agent runtime as every other channel, so guardrails and human approval gates on tool calls apply here too.",
      },
      {
        heading: "Messenger messages as workflow triggers",
        body: "An inbound Messenger message can start a workflow: classify the enquiry, gather details, act through a connected tool, and hand off to a human where your flow says so.",
      },
    ],
    faqs: [
      {
        q: "Can an AI agent answer my Facebook Page messages?",
        a: "Yes. VegaDūta routes messages sent to your Facebook Page into your agent via the official Meta Graph API, and the agent replies in the Messenger conversation. You configure its knowledge and tools in the agent builder.",
      },
      {
        q: "Does VegaDūta use the official Messenger API?",
        a: "Yes. The integration runs on Meta's Graph API — the sanctioned path for Page message automation — not on unofficial clients or browser automation.",
      },
      {
        q: "Can a Messenger message trigger a workflow?",
        a: "Yes. Messenger is one of VegaDūta's workflow trigger channels, so an inbound message can start a workflow such as lead capture or support intake.",
      },
      {
        q: "Can one agent cover Messenger, Instagram, and WhatsApp together?",
        a: "Yes. Agents and channels are decoupled, so a single agent can be attached to all three Meta-family channels at once — plus Telegram, Slack, email, and the rest of the lineup.",
      },
    ],
    related: [
      { href: "/integrations/instagram", label: "Instagram integration" },
      { href: "/integrations/whatsapp", label: "WhatsApp integration" },
      { href: "/platform/workflow-automation", label: "Workflow automation" },
      { href: "/try", label: "Try it without signing up" },
    ],
  },
  {
    slug: "x-twitter",
    name: "X (Twitter)",
    metaTitle: "X (Twitter) DM AI Agent Integration | VegaDūta",
    metaDescription:
      "VegaDūta connects AI agents to X (Twitter) DMs via OAuth, with poll-based inbound retrieval and outbound replies — honest about the polling trade-off.",
    h1: "X (Twitter) DM agents on VegaDūta",
    kicker: "Integrations · Messaging channels",
    cardBlurb:
      "AI agents on X direct messages via OAuth — poll-based inbound, so near-real-time rather than instant.",
    intro: [
      "VegaDūta connects an AI agent to X (Twitter) direct messages through OAuth. You authorize the account, and the platform retrieves inbound DMs by polling — X's DM surface does not give us instant webhooks, so messages arrive on a polling cadence rather than the moment they are sent. Outbound replies go through the API, and X DMs work as a workflow trigger channel like every other messaging integration.",
    ],
    sections: [
      {
        heading: "How the connection works",
        body: "The integration authenticates with OAuth against your X account. Inbound DMs are fetched on a polling schedule and routed to your agent; the agent's replies are sent back through the API. Setup is a console-side authorization, not an app you host.",
        bullets: [
          "OAuth-based account connection.",
          "Poll-based inbound DM retrieval — see the honest note below.",
          "Outbound DM replies through the API.",
          "X as a workflow trigger channel.",
        ],
      },
      {
        heading: "Honest note: polling, not instant webhooks",
        body: "Inbound on this channel is poll-based, which means a short delay between a DM being sent and your agent seeing it. If your use case needs second-level latency, WhatsApp, Telegram, Slack, or Discord — all push-based — are better fits. We would rather tell you that here than have you discover it in production.",
      },
      {
        heading: "X DMs as workflow triggers",
        body: "An inbound DM can start a workflow — lead capture from an X audience, support intake, or any structured flow. Guardrails and human approval gates apply to the agent's tool calls, same as on every channel.",
      },
    ],
    faqs: [
      {
        q: "Can an AI agent answer X (Twitter) DMs?",
        a: "Yes. VegaDūta connects to your X account via OAuth, retrieves inbound DMs, and routes them to your agent, which replies through the API. The agent behaves like it does on any other channel — same knowledge, tools, and guardrails.",
      },
      {
        q: "Are X DM replies instant?",
        a: "Not quite — inbound retrieval is poll-based rather than webhook-pushed, so there is a short delay before the agent sees a new DM. Replies send immediately once the agent processes the message. For instant inbound, use a push-based channel like WhatsApp or Telegram.",
      },
      {
        q: "How does VegaDūta authenticate with my X account?",
        a: "Through OAuth: you authorize the connection from the console and the platform stores the resulting credentials per tenant. No password sharing is involved.",
      },
      {
        q: "Can an X DM trigger a workflow?",
        a: "Yes. X is one of VegaDūta's workflow trigger channels, so an inbound DM can start a workflow — subject to the polling cadence on inbound delivery.",
      },
    ],
    related: [
      { href: "/integrations/whatsapp", label: "WhatsApp integration" },
      { href: "/integrations/telegram", label: "Telegram integration" },
      { href: "/platform/workflow-automation", label: "Workflow automation" },
      { href: "/try", label: "Try it without signing up" },
    ],
  },
  {
    slug: "email",
    name: "Email",
    metaTitle: "Email AI Agent — Inbound & Outbound | VegaDūta",
    metaDescription:
      "Give an AI agent its own inbox: VegaDūta sends outbound email and polls IMAP for replies, so agents hold full email conversations and trigger workflows.",
    h1: "Email AI agents on VegaDūta",
    kicker: "Integrations · Messaging channels",
    cardBlurb:
      "Outbound send plus inbound IMAP polling lets an agent hold complete email conversations.",
    intro: [
      "VegaDūta gives an AI agent a working email presence: the platform sends outbound email on the agent's behalf and polls an IMAP inbox for inbound messages, so the agent holds full back-and-forth email conversations rather than only firing notifications. An inbound email can also trigger a workflow, which makes email a practical intake channel for slower-paced, higher-detail requests.",
    ],
    sections: [
      {
        heading: "How the connection works",
        body: "You configure the mailbox credentials in the channel settings. Outbound goes through the platform's email sender; inbound arrives via IMAP polling of the connected inbox, threaded back into the conversation the agent is holding.",
        bullets: [
          "Outbound email send for agent replies and notifications.",
          "Inbound IMAP polling so replies reach the agent.",
          "Email as a workflow trigger channel.",
        ],
      },
      {
        heading: "What an email agent is good at",
        body: "Email suits requests that carry detail — quotes, documents, structured enquiries. The agent reads the inbound message, uses its tools and knowledge to act, and replies in the thread. Because inbound is polling-based, expect near-real-time rather than instant turnaround; for chat-speed conversations, use a messaging channel.",
      },
      {
        heading: "Email as a workflow trigger",
        body: "An inbound email can start a workflow: parse the request, look up context through the agent's tools, draft a response, and route anything sensitive through a human approval gate before it acts. It is the same workflow engine that backs every other trigger channel.",
      },
    ],
    faqs: [
      {
        q: "Can an AI agent read and reply to emails?",
        a: "Yes. VegaDūta polls a connected IMAP inbox for inbound mail and sends the agent's replies as outbound email, so the agent holds complete email conversations. You attach the mailbox in the channel settings.",
      },
      {
        q: "How does VegaDūta receive inbound email?",
        a: "Via IMAP polling of the mailbox you connect. The platform checks the inbox on a schedule and routes new messages to your agent, which means near-real-time handling rather than instant push delivery.",
      },
      {
        q: "Can an incoming email trigger an automated workflow?",
        a: "Yes. Email is one of VegaDūta's workflow trigger channels, so an inbound message can start a workflow — intake, triage, drafting, and approval steps included.",
      },
      {
        q: "Is email agent automation safe to leave running?",
        a: "The same platform controls apply as everywhere else: guardrails for PII, toxicity, and jailbreak detection, plus human approval gates on tool calls you mark as sensitive. You decide what the agent may do autonomously and what needs a person's sign-off.",
      },
    ],
    related: [
      { href: "/integrations/google-workspace", label: "Google Workspace (Gmail tools)" },
      { href: "/platform/workflow-automation", label: "Workflow automation" },
      { href: "/ai/guardrails", label: "Guardrails" },
      { href: "/try", label: "Try it without signing up" },
    ],
  },
  {
    slug: "sms",
    name: "SMS",
    metaTitle: "SMS AI Agent via Twilio | VegaDūta",
    metaDescription:
      "Connect an AI agent to SMS via Twilio: inbound webhook, outbound dispatch, and workflow triggers. Conversational send and receive — not a bulk campaign tool.",
    h1: "SMS AI agents on VegaDūta",
    kicker: "Integrations · Messaging channels",
    cardBlurb:
      "Two-way conversational SMS through Twilio — an agent that texts back, not a bulk blaster.",
    intro: [
      "VegaDūta connects an AI agent to SMS through Twilio: inbound texts arrive on a Twilio webhook and route to your agent, and the agent's replies dispatch as outbound SMS. The SMS channel shares the from-number with the Twilio WhatsApp connection, so one Twilio number can serve both. SMS also works as a workflow trigger channel — an inbound text can start an automated multi-step process.",
    ],
    sections: [
      {
        heading: "How the connection works",
        body: "Point your Twilio number's messaging webhook at the platform endpoint and add your Twilio credentials in the channel settings. Inbound texts route to the attached agent; outbound replies dispatch through Twilio from the same number your Twilio WhatsApp channel uses.",
        bullets: [
          "Twilio SMS inbound webhook for incoming texts.",
          "Outbound SMS dispatch for agent replies.",
          "Shares the from-number with the Twilio WhatsApp connection.",
          "SMS as a workflow trigger channel.",
        ],
      },
      {
        heading: "Where SMS agents fit",
        body: "SMS reaches people who are not on WhatsApp or data connections — a real slice of India. An agent on SMS handles the same conversations it would anywhere else: questions, lookups, intake, with tool calls governed by guardrails and approval gates.",
      },
      {
        heading: "Honest note: conversational, not campaigns",
        body: "This integration is two-way conversational messaging: send and receive. It is not a bulk-SMS or campaign product — there is no blast scheduling, list management, or promotional broadcast tooling. If you need a campaign platform, this is not it, and we would rather say so up front.",
      },
    ],
    faqs: [
      {
        q: "Can an AI agent reply to text messages?",
        a: "Yes. Inbound SMS arrives through a Twilio webhook and routes to your VegaDūta agent, which replies via outbound SMS dispatch. It is a full two-way conversation over text.",
      },
      {
        q: "Do I need a separate number for SMS and WhatsApp?",
        a: "Not if you use Twilio for both — the SMS channel shares the from-number with the Twilio WhatsApp connection, so one Twilio number can carry both channels.",
      },
      {
        q: "Can VegaDūta send bulk SMS campaigns?",
        a: "No. The SMS integration is conversational send and receive, not a campaign product — there is no bulk blast, list management, or promotional scheduling. We state that plainly rather than let you assume otherwise.",
      },
      {
        q: "Can an incoming SMS trigger a workflow?",
        a: "Yes. SMS is one of VegaDūta's workflow trigger channels, so an inbound text can start a workflow such as intake or appointment handling.",
      },
    ],
    related: [
      { href: "/integrations/whatsapp", label: "WhatsApp integration" },
      { href: "/platform/workflow-automation", label: "Workflow automation" },
      { href: "/help/workflow-recipes", label: "Workflow recipes" },
      { href: "/try", label: "Try it without signing up" },
    ],
  },
];
