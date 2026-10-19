# AI for everyone: where the edge, hosted and channel paths fall short, and what to build

**Date:** 2026-10-14. **Status:** gap analysis with the first three items shipped (see "Shipped
with this document"). Everything below is read from the code in this repository on that date;
file paths are relative to the repo root. Nothing here was measured on real users.

## The claim being tested

"AI reachable to everyone" means someone with **no laptop, no GPU, no English, no data plan, no
card and no account** can still use an agent. The platform already has three paths that each
cover part of that, and no other open-source agent platform combines them:

| Path | What it is today | Who it reaches |
|---|---|---|
| **Edge** (`web/app/lib/edge`, ~35k lines) | WebGPU models in the browser (WebLLM), Chrome's built-in model, on-device vision, English speech in and out, local tools, a guarded bridge to server tools, WebMCP | A signed-in person on a recent laptop or Android with WebGPU |
| **Hosted free pool** (`core/providers` `platform_hosted_llm_provider`, `core/agents/FreeTierChatClientService`) | The operator's shared model, by default the `--local-ai` Ollama container, for every FREE tenant | Anyone with an account on an instance whose operator set a pool up |
| **Channels** (`core/omnichannel`) | WhatsApp, Telegram, Slack, Discord, Teams, Messenger, Instagram, X, email, phone calls with DTMF menus, and now SMS | Anyone who already uses one of those apps, or any phone |

Plus: a console in 30 locales including all 22 scheduled Indian languages, Sarvam for Indic speech
and translation, 77 agent templates aimed at non-technical people (homework helper, farmer advisory,
citizen scheme guide, pharmacy counter), public share links and an embeddable widget.

## Gaps found

Ordered by how many people each one locks out. "Pointer" is where the gap is in the code.

### A. Reach: who cannot get a message to an agent at all

| # | Gap | Pointer | Status |
|---|---|---|---|
| A1 | **Plain SMS could not hold a conversation.** An inbound SMS could only fire a workflow; without one it was acknowledged and dropped. Feature phones (no data) had no way in. | `TwilioSmsWebhookController` (old javadoc: "no chat/pairing/session model for plain SMS"); `ChannelType` had no SMS | **Shipped** (see below) |
| A2 | No USSD, no "give a missed call" entry, no RCS/Signal/Viber/LINE/Zalo/KakaoTalk/WeChat | no adapters; "missed call" appears only in marketing copy `web/app/help/workflow-recipes/recipes-automotive-telecom.tsx:33` | open |
| A3 | Phone calls are English-only: no `language`/voice on the TwiML, hard-coded "Hi, connecting you now." | `TwilioVoiceWebhookController.java:96,172-176` | open, small |
| A4 | No shared-device or kiosk mode (library, school, village centre, clinic): no anonymous multi-user session on one device, no wipe between users, no "continue on your phone" | no code; "kiosk" only in `recipes-transit-temple.tsx:177` | open |
| A5 | Share links have no QR code in the agent Share panel (only in the studio demo) | `ShareAgentPanel.tsx` | **Shipped** |
| A6 | Unknown senders are held for admin pairing on every channel by default; a public number needs `autoApproveNewSenders` and nothing in the UI explains the trade-off | `ChannelDefaultAgent.java:39-49` | open, docs/UX |

### B. Cost: who is told to pay for something that is free

| # | Gap | Pointer | Status |
|---|---|---|---|
| B1 | **A local model was billed like a paid API.** Ollama tags are not in the pricing table, so every call cost the $0.50/$2.00 fallback, debited the $0.15/day per-user budget, counted against the 400k-token monthly quota ("upgrade to Premium" on your own laptop) and sorted last in cheapest-first failover. | `LlmPricingCatalog.java:534-538`, `FreeTierChatClientService.java:616-624` (the code admitted it), `PerUserBudgetService`, `FreeTierUsageLimiterService:129` | **Shipped** |
| B2 | A fresh install has no free path unless the operator passes `--local-ai` or adds a pool row; `app.local-llm.bootstrap.enabled` is off by default and the no-provider banner tells a new user to add a key | `LocalLlmPoolBootstrap.java:50`, `providerGate.ts:39-49` | open; a deliberate default, revisit for the OSS site mode |
| B3 | Defaults disagreed: Java and Compose said `llama3.2`, the CLI pulls `qwen3.5:4b`; neither tag is in the model catalog seed | `LocalLlmPoolBootstrap.java:82`, `lite.yml:215`, `ProviderModelCatalogService:226-232` | **Shipped** (defaults aligned; catalog seed still open) |
| B4 | No way for one tenant to sponsor another's usage (a school paying for a village, an NGO for its field workers). The only grant is the Academy scholarship, service-to-service | `CompGrantService.java`; wallets in `core/payments/wallet` are for agents spending, not for AI usage | open |
| B5 | `app.llm.default-source`, `app.free-tier.*`, per-user budget keys existed only as `@Value` defaults, in no YAML and no doc | `TenantLimitsServiceImpl:83-94`, `FreeTierUsageLimiterService:129-131` | partly shipped (documented the two that matter) |

### C. Language: who cannot be understood

| # | Gap | Pointer | Status |
|---|---|---|---|
| C1 | **No local text-to-speech anywhere on the server.** Indic voice needs a paid Sarvam key; Sarvam TTS is fixed to `en-IN` and covers 11 of 22 languages | `services/sidecar/app/tts.py:56`, `TtsModelProvider.java:43-52` | open |
| C2 | On-device speech is English-only (`whisper-tiny.en`, Kokoro English voices); a multilingual swap is described as one line | `whisperWorker.ts:45-50`, `kokoroWorker.ts:41` | open, small |
| C3 | No language-aware model selection; the edge catalog has no language metadata; guardrail regexes and the NER redactor are English; `local_wikipedia` is `en.` only | `tierRouter.ts`, `edgeGuardrails.ts`, `localToolsWeb.ts` | open |
| C4 | OCR is English only (bare `tesseract-ocr`, no language packs, no `lang` passed) | `services/sidecar/Dockerfile:31`, `extraction.py` | open, small |
| C5 | RTL is marked but not applied: 522 physical margin classes vs 7 logical, no `rtl:` variants, no RTL tests | `web/app` | open |
| C6 | Edge UI strings outside the `edge` namespace are untranslated (branding, setup nudge, chip, admin page) | `branding.ts:9-12`, `OnDeviceSetupNudge.tsx` | open |

### D. Device: who has no GPU, no stable network, no storage

| # | Gap | Pointer | Status |
|---|---|---|---|
| D1 | **The CPU fallback (tier 2) is unreachable**: no UI ever installs it, and it was never loaded in a real browser | `tier2.ts:82-94`, `EdgeConsole.tsx:300` filters it out | open |
| D2 | **No offline console**: PWA manifest exists, but no app service worker, no install prompt; only already-cached weights work offline, the page itself does not load | `web/public/manifest.webmanifest`, `layout.tsx:292`, no `beforeinstallprompt` | open |
| D3 | `/edge` needs sign-in; the docs call it "no server at all". Signed-out on-device use exists only on `/code` with pre-cached weights | `publicRoutes.ts:75-111`, `docs/local-models.md:66` (corrected in this change) | open (doc fixed) |
| D4 | Weights come from public CDNs with no hash check for any tier-1, speech, vision or face model; the instance cannot mirror them; low-bandwidth users re-download per device | `tier1.ts:977,1233`, `downloadManager.ts:17-21` (all `sha256: null`) | open |
| D5 | Downloads are not in a worker and have no Background Fetch; Save-Data and 2G only warn | `downloadManager.ts:29-32`, `preflight.ts:103-160` | open |
| D6 | No mobile client source in the repo (Android is a beta APK built elsewhere; iOS never compiled) | `docs/plugins-and-apps.md`, `surfaces.ts` | open, out of scope here |

### E. Hosted-pool robustness (affects everyone on the free path)

| # | Gap | Pointer |
|---|---|---|
| E1 | No capability registry per pool row or model (tools, streaming, context); small local models' tool quality is a doc warning only | `AgentChatClientFactory.java:13-32`, `docs/local-models.md:77-79` |
| E2 | No fallback from a tenant's own key to the pool; FREE tenants cannot set a backup provider | `AgentService.java:1752-1757` |
| E3 | Pool health and latency are per replica, in memory, empty after a restart | `HostedProviderHealth` |
| E4 | `providerGate.ts:41` treats `isFreePlan` as "on the pool" even when no pool exists, so a stored-FREE tenant with free mode off can see no banner and then a 400 | `providerGate.ts:41` |

## Shipped with this document

1. **SMS conversations** (A1, A5 via the QR): `ChannelType.SMS`, `TwilioSmsChannelAdapter`
   (`channel.sms.send`, sentence-boundary cut at `app.omnichannel.sms.max-reply-chars`),
   `TwilioSmsWebhookController` rewritten on the shared session/pairing/approval machinery with the
   workflow trigger kept, `SmsChannelController` (`PUT /api/channels/sms/default-agent`), the
   SMS card and health probe, `channel.sms.message.received`, the inbox filter and the Channels
   routing card. Docs: `docs/sms-channel.md`. Unit tests only; not run against a Twilio number.
2. **Zero-cost pool rows** (B1, B3): `zero_cost` column (V484, with a backfill for Ollama rows),
   `LlmPricingCatalog.zeroCost()` view used by `HostedLlmChatClientFactory`, the per-user budget
   advisor releasing instead of charging, the free-tier chain skipping both reservations when
   every row is free (`app.free-tier.meter-zero-cost-rows` restores the quota), zero-cost rows first
   in failover order, the bootstrap row created as free, the Platform Admin toggle and badge, and
   one default model (`qwen3.5:4b`) across Java, Compose and the CLI. Unit tests only; not run on
   a stack.
3. **QR code on the Share panel** (A5).
4. **Edge relay** (next-steps item 1, shipped the same day): `core/omnichannel/relay` (Valkey
   registry, per-channel queues, lease and reply list, hosted fallback on the inbound thread),
   `EdgeRelayController` under `/api/channels/edge-relay`, hooks in the Twilio SMS, Twilio
   WhatsApp and Meta WhatsApp webhooks, `web/app/lib/edge/relay.ts` and the /edge card. Docs:
   `docs/edge-relay.md`. Unit tests on both sides; not run against a real number.

## What to build next, in order

Each item is scoped so one person can finish it; the first four are the ones no comparable
platform offers out of the box.

1. **Edge relay: your laptop is the model behind your number.** Shipped (see above). Open
   follow-ups: Telegram and the other channels (a three-line hook each), media turns, a
   "relay answered" marker in the conversation inbox, and a measured run against a real number.
2. **Kiosk mode** (A4): a share-link variant that starts a fresh anonymous session per visitor,
   wipes on idle, hides history, large-type UI, optional language picker on the first screen, and a
   "continue on your phone" QR that hands the session to WhatsApp or SMS. Mostly `web/app/a/` plus
   a session TTL on the share controller.
3. **Offline-first console** (D1, D2): one app service worker that caches the shell of `/edge`,
   `/code` and the agent playground, an install prompt, and an install card for the CPU tier so a
   phone without WebGPU gets the 0.6B model instead of nothing. Verify tier 2 in a real browser
   first; it has never been loaded.
4. **Pay it forward** (B4): a tenant-level sponsorship that moves a monthly token or dollar
   allowance from one tenant's cap to another (or to "any new tenant on this instance"), built on
   the existing `CompGrantService` shape with a ledger row per grant and a Platform Admin view. A
   school, NGO or company can then fund usage for the people it serves.
5. **Speak every language** (C1, C2, C3): add a local TTS engine to the sidecar (Piper or
   Kokoro-multilingual) as a `TtsModelProvider`, swap the on-device Whisper to the multilingual
   tiny model, pass the detected language to the Prompt API session and to `<Say language>` on
   Twilio, and add `languages` to the edge model manifest so the router can prefer a model that
   speaks the user's language.
6. **Mirror the weights** (D4): let an instance host its own copy of the edge manifest's assets
   with sha256, so a classroom downloads once from the LAN and the integrity check runs. The
   download manager already verifies hashes when they are present.
7. **Phone-first entry points** (A2, A3): missed-call callback (Twilio status callback → outbound
   call or SMS), the caller's language on the TwiML, and a USSD gateway adapter behind the same
   `ChannelType` machinery.
8. **Hosted-pool hygiene** (E1 to E4): a per-row capability JSON, own-key-to-pool fallback for
   FREE tenants, pool health in Valkey, and the `providerGate` fix.

## How this was verified

Three code sweeps (edge stack, hosted pool, channels and reach), each with file:line evidence,
cross-checked against the docs and the changelog; the shipped items compile across the Maven
reactor and have unit tests. No stack was started and no device, phone number or real model
was exercised for this document.
