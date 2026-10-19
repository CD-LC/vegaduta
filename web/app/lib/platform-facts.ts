import { getPublicEnv } from "./env";
// ---------------------------------------------------------------------------
// Platform facts — the ONE place a published number about VegaDūta lives
// (GEO pass, 2026-09-19).
//
// Why this file exists. An AI assistant deciding what to say about a product
// cross-checks the sources it retrieved. On 2026-09-19 this site disagreed
// with itself: llms.txt said both "13" and "14" LLM providers thirty lines
// apart, "13 node types" against the ledger's 14, and "runs on Temporal" in
// the present tense against PlatformIntelligenceSection's explicit rule that
// Temporal is opt-in and OFF by default. A source that contradicts itself is
// the one that gets dropped, and the no-fiction rule was being broken by
// drift rather than by anyone deciding to break it.
//
// Rules:
//   1. Every value names the file it was counted in. Recount THERE before
//      changing it; never copy a number in from marketing copy.
//   2. A number two counting rules disagree on is published as a floor
//      ("40+"), not as whichever count is larger. See BUILT_IN_TOOLS_FLOOR.
//   3. Machine-facing surfaces (llms.txt, JSON-LD featureList, /glossary)
//      read from here. Translated landing copy in messages/*.json cannot
//      import a constant — platform-facts.test.ts holds the English source
//      to these values instead.
//   4. The do-not-claim list in PlatformLedger.tsx applies here unchanged:
//      no capacity, latency, uptime or ROI numbers, no RL, no Home Assistant /
//      SAP connectors, nothing that has not had a production deploy entry.
//
// Counted against core/ on 2026-09-19; recounted 2026-10-03 (node types
// 14 -> 21, tools floor 40 -> 50, skills added). ChannelType.java now also
// has TEAMS (V240), but it has never been exercised against a real Teams
// tenant, so the published channel count stays at 9 until someone does.
//
// Corrected 2026-10-03 (copy audit, public claims vs code):
//   - Channels: ChannelType has 10 values (9 below + TEAMS). Copy that said
//     "WhatsApp, voice, and ten other channels" meant 12; the published total
//     is 9 ("WhatsApp, voice and seven other").
//   - Speech is NOT a 22-language claim. Phone calls (Twilio ConversationRelay,
//     TwilioVoiceWebhookController) carry no language/provider attribute and
//     VoiceRelayWebSocketHandler only consumes Twilio's voicePrompt text, so
//     Sarvam/ElevenLabs speech is the console / in-app (LiveKit) / voice-note
//     path, NOT inbound phone calls. Never say Indian-language callers.
//     22 / 30 are the CONSOLE (interface)
//     counts. Sarvam's translate tool and STT model are described in code as
//     covering 22 / 23 languages (SarvamTranslateToolCallback and
//     SarvamSpeechToTextClient javadoc) - those are Sarvam's own upstream
//     figures and nothing here exercises them per language. Live voice calls
//     speak Sarvam TTS in en-IN only (SarvamCallSpeechSynthesizer, VoiceCallController's Sarvam tier,
//     SarvamTextToSpeechProvider.DEFAULT_TARGET_LANGUAGE: "per-agent
//     target-language selection is not yet wired"); only the agent "speak"
//     tool takes a language code. So: never pair "voice", "speech" or "calls"
//     with a language count; say "languages depend on the speech provider you
//     connect" and, where Sarvam on calls is named, "currently Indian English".
//   - Hosting: production is Azure (Container Apps + managed Postgres) in ONE
//     region, with Azure Managed Grafana / Azure Monitor managed Prometheus /
//     Log Analytics for metrics and logs (infra/terraform/environments/
//     azure-phase1/main.tf, "Observability"). Prometheus + Grafana + Loki +
//     Alertmanager is the STAGING Docker Compose stack, not production. Never
//     describe production observability with those four names.
//   - RL ("agents that learn from feedback", Thompson-sampling bandit): the
//     code exists (core/rl) but V201 has never had a recorded production pass,
//     so it may be described only as "experimental, off by default" - no
//     algorithm names, no reward-hacking guarantees.
//   - Plugins: VS Code, JetBrains and Chrome builds are served from our own
//     /plugins page (clients/PUBLISHING.md). None is on its public store; the
//     Chrome extension was submitted to the Web Store 2026-08-19 and its
//     outcome is unrecorded. Never say "in the marketplace".
// ---------------------------------------------------------------------------

export const FACTS_VERIFIED_ON = "2026-10-03";

export const PLATFORM_FACTS = {
  // core/providers/.../ProviderName.java: ANTHROPIC, OPENAI, AZURE_OPENAI,
  // GOOGLE_VERTEX, AWS_BEDROCK, OLLAMA, OPENROUTER, GROK, GEMINI, SARVAM,
  // MISTRAL, DEEPSEEK, GROQ, META — plus PLATFORM_HOSTED, which is the hosted
  // pool and not a key a customer brings. (LOCAL_OPENAI, added later, is a
  // self-hosted server rather than a vendor key and is not counted: the
  // "14 providers" copy across the marketing pages stays true.)
  byokProviders: 14,

  // core/.../ChannelType.java: WHATSAPP, MESSENGER, INSTAGRAM, SLACK, DISCORD,
  // X, TELEGRAM, EMAIL, VOICE (+ TEAMS and SMS, enum values not published here
  // until they have been exercised against a real tenant; MATRIX likewise (unit-tested
  // against a fake homeserver only, 2026-10-17); SMS conversations
  // are unit-tested only, 2026-10-14).
  channels: 9,
  // Migration V225 (2026-09-12): WhatsApp, Telegram and Email need no paid
  // plan. The other six are not "paid" either — they need the customer's own
  // third-party credentials. ChannelWall.tsx owns that distinction.
  freeChannels: ["WhatsApp", "Telegram", "Email"],

  // core/workflow/.../WorkflowNodeType.java: TRIGGER, AGENT, KNOWLEDGE,
  // CONDITION, APPROVAL, OUTPUT, DEVICE_CONTROL, TOOL, LOOP, CODE, WORKFLOW,
  // SUPERVISOR, PARALLEL, FAN_OUT, HTTP, MEDIA, HUMAN_INPUT, SUB_FLOW,
  // VOICE_MENU, BULK_EMAIL, CRM_LEAD.
  workflowNodeTypes: 35,

  // core/.../ContentGuardrailType.java: PROFANITY, JAILBREAK, PII,
  // POLICY_VIOLATION, TOXICITY, CUSTOM.
  guardrailTypes: 6,

  // BuiltInToolClassifications.java: 59 named entries on 2026-10-03 (it was
  // 46-48 depending on the counting rule in September). Published as a floor
  // so no two pages can contradict each other.
  builtInToolsFloor: 50,

  // vegaduta-webapp-prototype/vegaduta-webapp/ui/src/lib/curriculum-totals.ts:
  // LESSONS_PER_LEVEL sums to 288 on 2026-10-03; copy says "280+".
  academyLessonsFloor: 280,

  // Upstream MCP registry size, quoted as 18,000+ across the site
  // (CapabilityAtlas ledger). Live upstream data: not recounted in this repo.
  mcpRegistryEntriesFloor: 18000,

  // core/agents-turn/src/main/resources/skills: one folder per built-in SKILL.md.
  builtInSkills: 14,

  // core/mcpserver/.../tools/: call agent, call workflow, list agents, list
  // my workflows, search knowledge.
  mcpServerTools: 5,

  // web/app/lib/i18n-config.ts SUPPORTED_LOCALES: all 22 Eighth Schedule
  // languages + 8 international (30 files in web/messages, counted
  // 2026-10-03). Interface languages — NOT a speech claim
  // ("speech ≠ 22 languages" is on the do-not-claim list).
  indianLanguages: 22,
  interfaceLocales: 30,

  // Locale Sarvam TTS speaks on live voice calls today (see the header).
  liveCallSarvamVoiceLocale: "en-IN",

  // app/billing/page.tsx Product offers + plan_limits. USD per month.
  plans: [
    { name: "Free", priceUsd: 0 },
    { name: "Premium", priceUsd: 19 },
    { name: "Unlimited", priceUsd: 79 },
  ],
} as const;

// Where and how the hosted service runs. Source: infra/terraform/environments/
// azure-phase1/main.tf (location default "centralindia", one resource group) and
// modules/azure-database/main.tf (backup_retention_days = 7,
// geo_redundant_backup_enabled = false, no high_availability block).
export const HOSTING_FACTS = {
  cloud: "Azure",
  regions: 1,
  databaseStandby: false,
  backupRetentionDays: 7,
  geoRedundantBackups: false,
} as const;

// Short, extractable capability statements for SoftwareApplication.featureList
// and llms.txt. Each is a sentence the ledger already stands behind; numbers
// come from PLATFORM_FACTS so they cannot drift from it.
export const FEATURE_LIST: string[] = [
  "Build an AI agent from a one-sentence description, no code required",
  `Deploy agents on ${PLATFORM_FACTS.channels} channels: WhatsApp, Telegram, Email, Slack, Discord, Instagram, Messenger, X and voice calls`,
  `${PLATFORM_FACTS.freeChannels.join(", ")} available on the Free plan`,
  `Bring your own key for ${PLATFORM_FACTS.byokProviders} LLM providers, or use the platform-hosted model pool`,
  `Visual workflow builder with ${PLATFORM_FACTS.workflowNodeTypes} node types, including human approval steps`,
  "Retrieval-augmented generation over your own documents, with OCR for scanned files",
  "Model Context Protocol (MCP) client and server: connect MCP servers to agents, and call your agents as MCP tools",
  `${PLATFORM_FACTS.builtInToolsFloor}+ built-in agent tools`,
  `${PLATFORM_FACTS.guardrailTypes} content guardrail types and LLM-judged quality evaluation`,
  "Human approval on consequential actions, with a full audit trail",
  "Agent Lab: replay a run as a graph, re-run it from any step, and test which prompt sections matter",
  `Agent Skills: ${PLATFORM_FACTS.builtInSkills} built-in SKILL.md skills that an agent loads only when a task needs them`,
  "Agent teams: a lead agent delegates to specialist agents in parallel under one shared run budget",
  "Agent and workflow versions with Staging and Production labels, restore, and eval-gated promotion",
  "App Studio: build a multi-page app around your agents from a description",
  "Per-user spending budgets and rate limits",
  `Interface in ${PLATFORM_FACTS.interfaceLocales} languages, including all ${PLATFORM_FACTS.indianLanguages} scheduled Indian languages`,
  "Agent-to-Agent (A2A) protocol and AG-UI support",
  "Schema-per-tenant data isolation on PostgreSQL",
];

// Registered-entity facts for the hosted deployment's Organization JSON-LD.
// Source: app/lib/legalEntity.ts (deployment-configured).
//   - foundingYear and region describe the upstream project's operator;
//     forks should adjust them alongside their legalEntity values.
//   - No street address, phone, employee count or social profile here until
//     one is verified: an Organization node is an identity claim, and a wrong
//     one is worse than a short one.
export const ENTITY_FACTS = {
  foundingYear: "2025",
  addressRegion: "Karnataka",
  addressCountry: "IN",
  contactEmail: getPublicEnv("NEXT_PUBLIC_CONTACT_EMAIL") || "contact@example.com",
} as const;
