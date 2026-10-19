// /tutorials hub data: providers, voice, Agent Lab and skills tutorials. Re-exported in order by tutorials-data.ts.

import type { HubPage } from "../lib/seo-hub";

export const ADVANCED_TUTORIALS: HubPage[] = [
  {
    slug: "connect-llm-provider",
    name: "Connect your own LLM provider",
    metaTitle: "How to Connect Your Own LLM Provider (BYOK) | VegaDūta",
    metaDescription:
      "Bring your own key from any of 14 LLM providers — Anthropic, OpenAI, Gemini, Meta, Ollama and more. Keys are validated live, stored encrypted, and mapped per agent.",
    h1: "How to connect your own LLM provider",
    kicker: "Tutorial · Providers",
    cardBlurb:
      "Paste an API key from any of 14 LLM providers and pick exactly which models each of your agents runs on.",
    intro: [
      "This tutorial shows you how to bring your own LLM to VegaDūta. You paste an API key from any of 14 supported providers — Anthropic, OpenAI, Azure OpenAI, Google Vertex, Gemini, AWS Bedrock, Meta, Mistral, DeepSeek, Groq, Grok, OpenRouter, Ollama, or Sarvam — the platform validates it live, stores it encrypted, and loads that provider's current model catalog so you can choose models per agent.",
      "To be clear about when your keys are used: the free tier runs on the platform-hosted model pool, and your own keys take over on paid tiers.",
    ],
    steps: [
      {
        name: "Open Providers in the console",
        text: "Sign in to the web console and open the Providers screen. This is where every model credential in your workspace lives — one place to add, replace, or remove keys.",
      },
      {
        name: "Pick your provider",
        text: "Choose from the 14 supported providers: Anthropic, OpenAI, Azure OpenAI, Google Vertex, Gemini, AWS Bedrock, Meta (Muse Spark), Mistral, DeepSeek, Groq, Grok, OpenRouter, Ollama, and Sarvam. Ollama covers self-hosted models; Sarvam covers Indian-language models.",
      },
      {
        name: "Paste the API key",
        text: "Paste the key from your provider's dashboard. VegaDūta validates it live against the provider before saving — a bad or expired key fails right here, not later in a customer conversation. Valid keys are stored encrypted.",
      },
      {
        name: "Let the model catalog load",
        text: "Once the key validates, the platform loads the provider's current model list into your catalog. You see what your key can actually reach — no hand-maintained model lists to keep in sync.",
      },
      {
        name: "Select models per agent",
        text: "Open an agent and pick its model from the catalog. Different agents can run on different providers — a fast cheap model for triage, a stronger one for complex work — within the same workspace.",
      },
      {
        name: "Optionally set budgets and fallback",
        text: "You can set per-user daily budgets to cap spend, and configure fallback so that if a provider fails, agents continue on an alternative instead of going down with it. Both are optional but recommended before production traffic.",
      },
    ],
    sections: [
      {
        heading: "Good to know",
        body: "A few honest details about how provider keys behave on the platform.",
        bullets: [
          "Free tier requests run on the platform-hosted pool; your own keys are used on paid tiers.",
          "Keys are encrypted at rest and can be replaced or deleted from the Providers screen at any time.",
          "Validation happens at save time, so provider-side revocations surface as errors on the Providers screen rather than silently.",
        ],
      },
    ],
    faqs: [
      {
        q: "Which LLM providers can I connect to VegaDūta?",
        a: "Fourteen: Anthropic, OpenAI, Azure OpenAI, Google Vertex, Gemini, AWS Bedrock, Meta (Muse Spark), Mistral, DeepSeek, Groq, Grok, OpenRouter, Ollama, and Sarvam. That spans hosted frontier models, aggregators, self-hosted models via Ollama, and Indian-language models via Sarvam.",
      },
      {
        q: "Is my API key stored securely?",
        a: "Keys are validated live against the provider when you save them and stored encrypted at rest. You can replace or delete a key from the Providers screen at any time, and a key that stops working surfaces as an error there rather than failing silently.",
      },
      {
        q: "Do I need my own API key on the free tier?",
        a: "No. The free tier runs on the platform-hosted model pool, so agents work without any provider setup. Your own keys come into play on paid tiers, where they are used for your agents' requests.",
      },
      {
        q: "Can different agents use different models?",
        a: "Yes. Model selection is per agent: once a provider's catalog is loaded, each agent picks its own model. You can also set per-user daily budgets and a fallback chain so a provider outage does not take your agents down.",
      },
    ],
    related: [
      { href: "/ai/llm-connectors", label: "LLM connectors" },
      { href: "/tutorials/build-your-first-agent", label: "Build your first AI agent" },
      { href: "/integrations/sarvam-ai", label: "Sarvam AI integration" },
      { href: "/billing", label: "Plans and billing" },
    ],
  },
  {
    slug: "setup-voice-agent",
    name: "Set up a voice AI agent",
    metaTitle: "How to Set Up a Voice AI Agent (Calls & In-App) | VegaDūta",
    metaDescription:
      "Give your AI agent a voice: Whisper or Sarvam speech-to-text, ElevenLabs or Sarvam voices, Twilio for phone calls, and LiveKit for in-app conversations.",
    h1: "How to set up a voice AI agent",
    kicker: "Tutorial · Voice",
    cardBlurb:
      "Give an agent ears and a voice — Whisper or Sarvam to listen, ElevenLabs or Sarvam to speak, over the phone or in the browser.",
    intro: [
      "In this tutorial you will give a VegaDūta agent a voice: it will listen through speech-to-text, answer through text-to-speech, and take conversations over a real phone line or directly in the browser. You choose the ear (locally-run Whisper or Sarvam), the voice (ElevenLabs or Sarvam — Indian-language voices via Sarvam's bulbul, in the console and in-app), and the wire (Twilio for phone calls, LiveKit for in-app voice).",
      "The agent underneath is the same one you built for chat — same knowledge, same guardrails — so voice is a channel decision, not a rebuild.",
    ],
    steps: [
      {
        name: "Create the agent",
        text: "Build the agent as you would for any channel — describe the job in the builder, add knowledge, test in the playground. Voice sits on top of an agent that already works in text, so get the answers right first.",
      },
      {
        name: "Choose speech-to-text",
        text: "Pick how the agent listens: locally-run Whisper, or Sarvam's speech-to-text. Sarvam is the natural choice for Indian-language voice notes and console or in-app voice; Whisper runs on the platform without an external provider. Phone calls through Twilio use Twilio's own relay speech with its default language.",
      },
      {
        name: "Choose text-to-speech",
        text: "Pick the agent's voice: ElevenLabs, or Sarvam — whose bulbul voices speak Indian English in live in-app voice calls today, and other languages through the agent's speak tool. Phone calls through Twilio use Twilio's relay speech instead. Connect the provider's key on the Providers screen if you have not already, then select the voice in the agent's voice settings.",
      },
      {
        name: "For phone: connect Twilio voice",
        text: "To put the agent on a phone number, connect Twilio voice in the channel settings. The agent then takes inbound calls, and DTMF menus are supported — callers can press keys to navigate, not just speak.",
      },
      {
        name: "For browser or in-app voice: use LiveKit rooms",
        text: "For voice inside a web page or app, the platform uses LiveKit rooms. A caller joins from the browser — no phone number involved — and recording is optional per room if you need a record of the conversation.",
      },
      {
        name: "Test the call and check guardrails",
        text: "Place a real test call, on the phone line or in the browser, and listen end to end: transcription accuracy, voice quality, latency. Guardrails apply to voice exactly as to any channel, so actions you gated behind approval still pause for a human even mid-call.",
      },
    ],
    sections: [
      {
        heading: "Troubleshooting",
        body: "Voice adds moving parts; isolate which one is misbehaving before changing settings.",
        bullets: [
          "Poor transcription of Indian-language voice notes or console voice: switch speech-to-text to Sarvam, which is built for them. This does not change phone calls, which use Twilio's relay speech.",
          "No audio on the agent's replies: confirm the text-to-speech provider's key is valid on the Providers screen and a voice is selected.",
          "Phone calls not connecting: verify the Twilio voice connection and the number's configuration on the Twilio side.",
        ],
      },
    ],
    faqs: [
      {
        q: "Can an AI agent answer real phone calls?",
        a: "Yes. Connect Twilio voice and the agent takes inbound calls on a real phone number, with DTMF menus supported so callers can press keys as well as speak. Browser and in-app voice runs separately over LiveKit rooms, with no phone number needed.",
      },
      {
        q: "Does the voice agent support Indian languages?",
        a: "In the console, in-app and for voice notes, yes: Sarvam's speech-to-text handles Indian-language audio, and the agent's speak tool can ask Sarvam's bulbul voices for a specific language. Phone calls through Twilio are different: the call speech is handled by Twilio's relay with its default language, and the provider and language are not selectable there today. Locally-run Whisper covers transcription and ElevenLabs covers text-to-speech elsewhere.",
      },
      {
        q: "Can voice calls be recorded?",
        a: "In-app and browser conversations run over LiveKit rooms, where recording is optional and set per room. Enable it when you need a record of the conversation; leave it off otherwise.",
      },
      {
        q: "Do guardrails still apply when the agent is on a call?",
        a: "Yes. Voice is a channel like any other, so the agent's guardrails and approval rules apply mid-call exactly as they do in chat. An action gated behind human approval still pauses and waits, even while a caller is on the line.",
      },
    ],
    related: [
      { href: "/ai/voice-ai", label: "Voice AI" },
      { href: "/integrations/elevenlabs", label: "ElevenLabs integration" },
      { href: "/integrations/sarvam-ai", label: "Sarvam AI integration" },
      { href: "/tutorials/setup-whatsapp-agent", label: "Set up a WhatsApp AI agent" },
    ],
  },
  {
    slug: "debug-an-agent-with-agent-lab",
    name: "Debug an agent with Agent Lab",
    metaTitle: "How to Debug an AI Agent: Run Graph & Fork | VegaDūta",
    metaDescription:
      "Use Agent Lab to see each step of an agent's turn, fork the conversation from any message, and find which sections of the system prompt actually matter.",
    h1: "How to debug an agent with Agent Lab",
    kicker: "Tutorial · Testing",
    cardBlurb:
      "See what an agent did on a turn, fork the conversation from any message, and test which prompt sections matter.",
    intro: [
      "This tutorial shows you how to find out why an agent answered the way it did. You open Agent Lab from the playground, read the run graph for the turn that went wrong, fork the conversation from that message to try a change, and then use prompt knockout to see which sections of the system prompt are doing the work.",
      "The run graph works on turns you have already sent. Knockout and consistency runs make extra model calls, and the lab shows the number before you start.",
    ],
    steps: [
      {
        name: "Send a message in the playground",
        text: "Open an agent's playground and send the message you want to investigate. The lab reads turns from the conversation you have open, so there is nothing to look at until the agent has answered at least once.",
      },
      {
        name: "Open the lab and read the run graph",
        text: "Press the Lab button in the playground. The Run graph tab draws the selected turn as steps: user message, plan, tool calls, guardrail decisions, handoffs, and the reply. Use the Turn selector to pick an earlier turn. The totals show time, tool calls, tokens, and estimated cost.",
      },
      {
        name: "Inspect a step",
        text: "Select a step to see its details: the tool name, a preview of the arguments and the result, any error, and the duration. Previews are shortened to 400 characters and may be redacted, so treat them as a pointer to what happened, not a full payload.",
      },
      {
        name: "Fork from a message",
        text: "Choose Fork from here on a user message. Edit the message if you want to try different wording, then press Rewind and re-run. The conversation is rewound to just before that message and the message is sent again. Tool calls the original turn already made are not undone, and attachments are not sent again.",
      },
      {
        name: "Compare the branch with the original",
        text: "Open Compare. The original is shown as a snapshot next to the live branch, with the tools that only one version called listed for each side. Snapshots are kept in this browser only, for 24 hours.",
      },
      {
        name: "Run a prompt knockout",
        text: "Switch to the Prompt knockout tab. The system prompt is split at headings and blank lines; untick any section you want left out of the test. Choose an eval dataset or type probe questions, one per line, set the baseline repeats, check the stated number of model calls, and run. Each section comes back labelled load-bearing, harmful, or no measurable effect.",
      },
    ],
    sections: [
      {
        heading: "Good to know",
        body: "A few limits decide what the lab can and cannot tell you.",
        bullets: [
          "Knockout and consistency runs call the model directly: no tools, memory, knowledge, guardrails, or caching. They isolate the prompt and do not reproduce a full production turn.",
          "With too few questions or repeats the lab reports that it cannot tell a real change from noise, and labels nothing.",
          "If the agent has response caching on, re-sending an unchanged message can return the stored answer. Edit the message to get a fresh one.",
          "A turn that ran on the device, was answered from cache, failed, or was interrupted cannot be forked.",
        ],
      },
    ],
    faqs: [
      {
        q: "How do I see which tools my agent called?",
        a: "Open Agent Lab from the playground and look at the Run graph tab. Each tool call is a step, and selecting it shows the tool name, a preview of its arguments and result, and how long it took.",
      },
      {
        q: "Does forking a conversation undo what the agent already did?",
        a: "No. Fork rewinds the conversation and re-sends the message, but tool calls the original turn made, such as a sent message or a created record, are not undone.",
      },
      {
        q: "How many model calls does a knockout run make?",
        a: "The lab states the number before you start, based on the sections, questions, and baseline repeats you chose, and refuses a run over its per-run limit. Calls count toward your usual usage limits.",
      },
      {
        q: "Where do knockout questions come from?",
        a: "Either from an eval dataset for the agent, where cases with an expected answer are scored against it, or from probe questions you type in, where a judge scores how well each answer addresses the question.",
      },
    ],
    related: [
      { href: "/platform/agent-lab", label: "Agent Lab" },
      { href: "/platform/versions-and-evals", label: "Versions and evals" },
      { href: "/tutorials/build-your-first-agent", label: "Build your first AI agent" },
      { href: "/platform/observability", label: "Observability" },
    ],
  },
  {
    slug: "add-an-agent-skill",
    name: "Add an Agent Skill",
    metaTitle: "How to Add an Agent Skill (SKILL.md) | VegaDūta",
    metaDescription:
      "Write or upload an Agent Skill on VegaDūta: a named set of instructions agents load only when a request matches. Includes import, export, and admin review.",
    h1: "How to add an Agent Skill",
    kicker: "Tutorial · Agents",
    cardBlurb:
      "Package instructions for a recurring task as a skill that agents load only when a request calls for it.",
    intro: [
      "This tutorial shows you how to give your agents a skill: a named set of instructions for one kind of task, which an agent loads only when a request matches it. You will look at the built-in skills, write a custom one, and see how uploaded and agent-drafted skills are handled.",
      "Adding and editing skills needs a workspace admin. Other members can read the skills the workspace has.",
    ],
    steps: [
      {
        name: "Open the Skills page",
        text: "In the console, open Skills under Build. The page lists your workspace's custom skills and the built-in skills. Select any skill to read its instructions.",
      },
      {
        name: "Review the built-in skills",
        text: "Fourteen skills are included and on for every agent, covering documents (docx, pdf, pptx, xlsx), writing, code review, data analysis, and SQL. Switch one off with its toggle if you do not want agents to use it. Built-in skills cannot be edited in place.",
      },
      {
        name: "Create a skill",
        text: "Press New skill. Give it a name using lowercase letters, numbers, and hyphens, up to 64 characters; the name cannot be changed later. Write the description carefully, up to 1,024 characters: it is what agents read to decide when to use the skill, so say what the skill does and when it applies. Then write the instructions and press Save skill.",
      },
      {
        name: "Or upload one as a .zip",
        text: "Press Upload .zip to import a skill folder that contains a SKILL.md file and any supporting text files. Files that are not text are skipped, and the import tells you how many. To change a skill's bundled files later, upload it again.",
      },
      {
        name: "Test it with an agent",
        text: "Open an agent's playground and send a request that matches the skill's description. Agents use a saved skill from their next message. If the skill is not picked up, the description is usually too vague about when it applies.",
      },
      {
        name: "Review agent-drafted skills",
        text: "Skills written by an agent appear with an Agent draft badge and are switched off. Read the draft and enable it only if you are satisfied. A proposed rewrite of an existing skill shows as a staged rewrite: choose Apply to live skill or Discard.",
      },
    ],
    sections: [
      {
        heading: "Going further",
        body: "Skills are portable and cheap to keep around.",
        bullets: [
          "Export any skill, including a built-in one, to keep it in version control or move it to another workspace.",
          "To customise a built-in skill, export it, change its name, and upload it as your own.",
          "Only names and descriptions are in every prompt, so a long list of skills does not lengthen unrelated requests.",
        ],
      },
    ],
    faqs: [
      {
        q: "What goes in a SKILL.md file?",
        a: "A name, a description that says what the skill does and when to use it, and the instructions the agent should follow. Supporting text files can sit alongside it in the same folder.",
      },
      {
        q: "Who can add or edit skills?",
        a: "Workspace admins. Other members see the list of skills and can read them, but cannot add, edit, or delete them.",
      },
      {
        q: "When does a new skill take effect?",
        a: "From each agent's next message. There is nothing to redeploy.",
      },
      {
        q: "Can I stop agents from using a built-in skill?",
        a: "Yes. Each built-in skill has a toggle on the Skills page. Switching it off removes it for every agent in the workspace.",
      },
    ],
    related: [
      { href: "/platform/agent-skills", label: "Agent Skills" },
      { href: "/tutorials/build-your-first-agent", label: "Build your first AI agent" },
      { href: "/tutorials/connect-mcp-server", label: "Connect an MCP server" },
      { href: "/ai/agents", label: "How VegaDūta agents work" },
    ],
  },
];
