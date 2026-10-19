# Out of the box: what a personal install must do on its own, and what is missing

**Date:** 2026-10-19. **Status:** gap analysis and design; nothing in it is built unless a track
says so. The "Status after this tranche" section at the end says what has shipped since this was written,
and the corrections noted inline in tracks B, C and G mark where the first draft was wrong. Everything about the platform was read from this repository on that date (main at
`754c1f8d8`); file paths are relative to the repo root. Nothing was measured on real users.

## The brief

Six asks, taken from the product owner's words:

1. **A proper tour of the product on first login** that explains everything a person can do.
2. **No username and password.** A PIN of at least six digits, or the system's biometrics
   (Touch ID, Windows Hello, Android), and security must not get worse for it.
3. **Right information and direction everywhere**, so a person can find and understand every
   capability.
4. **Defaults that just work**: web search (Brave or equivalent) and a verified set of MCP
   connectors on from the start, and every capability tunable from the console.
5. **Reach the physical world**: Bluetooth from the browser, IoT, and automations of the kind
   people build in Power Automate.
6. **Free local media models**: install image, audio and video models with a few clicks and
   generate with them; extra functionality as options a consumer can switch on.

The personal install is the shape this is about (`docs/personal-mode.md`: Postgres, Valkey, core,
web; one owner; `app.auth.mode=local`). Lite and Full keep Keycloak and multi-tenant behaviour;
where a track touches them it says so.

## What exists today, per ask

| Ask | What is there | Pointer |
|---|---|---|
| Tour | A custom tour engine with ~25 steps in three audiences (platform-admin, tenant-admin, member), a Court map, page guides on ~57 routes, a `/start` first-agent flow, a `/learn` Academy, a "Get set up" checklist | `web/app/components/TourProvider.tsx`, `components/tour/*`, `components/pageguide/*`, `web/app/start`, `web/app/learn` |
| Sign-in | Email + 12-character password held by core; one-time setup link on first run; Argon2id/bcrypt; two-bucket throttle; rotating refresh token in an HttpOnly cookie; `amr` claim | `core/admin/.../localauth/*`, `core/security/.../localauth/*`, V510 |
| Passkeys | An interface and a doc line, no implementation, no client code | `LocalCredentialVerifier.java`; `docs/personal-mode.md:135` "Not built: passkeys" |
| Direction | Page guides, Cmd+K palette, `vegaduta_setup_guide` tool, `/troubleshoot`, "Not available in the personal installation" states | `components/pageguide/pageGuides.ts`, `SetupGuideToolSource` |
| Web search | Native `web_search` with Brave, Tavily, Exa, Serper and SearXNG providers; SearXNG container behind `--search`; Brave key is a platform-admin credential | `core/agents-browser/.../web/*`, `scripts/vegaduta:282`, `/platform-admin/tool-credentials` |
| MCP | STDIO, SSE and Streamable HTTP; curated catalog of 14 reference and 5 remote servers; per-tenant registry with review gate; OS or container sandbox for STDIO | `core/mcp`, `McpReferenceServerCatalog.java`, `DynamicMcpConnectionManager.java` |
| Local models | Hardware detection, catalog with a RAM ladder, pull with progress, tool-call probe, warm and keep-alive, context window, reasoning effort; all from `/providers/local-model` | `core/providers/.../localmodels/*`, `web/app/components/localModel/*` |
| Media | Image (OpenAI, Gemini, Vertex), video (Veo), music (ElevenLabs), TTS (ElevenLabs, Sarvam, Kokoro in the browser), STT (faster-whisper in the sidecar, Whisper in the browser) | `core/imagegen`, `core/videogen`, `core/elevenlabs`, `web/app/lib/edge/audio/*`, `services/sidecar` |
| Bluetooth | Web Bluetooth tools for battery, heart rate and temperature, offered to in-browser models only; the pairing function has no UI caller | `web/app/lib/edge/deviceTools*.ts` |
| IoT | A Home Assistant MCP example, never smoke-tested; `DEVICE_CONTROL` workflow node and a paired-device command queue | `examples/mcp-servers/home-assistant`, `core/devices` |
| Automation | 35 workflow node types; cron, webhook, poll, form and 11 channel triggers; "Describe it, automate it"; ~40 templates; routines (scheduled prompts); routines proposed from habits | `WorkflowNodeType.java`, `DescribeComposer.tsx`, `/scheduled` |
| Add-ons | Compose profiles (`rag`, `workspace`, `search`, `voice`, `browser`...), `CORE_IMAGE_VARIANT=-full`, one installer checkbox (documents) | `deploy/compose/personal.yml`, `installer/runner.go:19` |

## Gaps

Ordered within each track by what a new person hits first.

### Track A: the tour does not know the personal install

| # | Gap | Pointer |
|---|---|---|
| A1 | The tour has no **personal** audience. Its steps sell plans, two-factor through Keycloak, the platform console and teammates, none of which exist here, and they anchor on rail items the personal rail hides (`/playground`, `/features`, `/shared-with-me`). | `components/tour/audience.ts` (three audiences), `steps/chrome.ts` "Know what your plan gives you", "Turn on two-factor authentication"; `lib/personalInstall.ts` hides 16 routes |
| A2 | The landing page after sign-in on a personal install is `/assistant`, and **no tour step and no page guide mentions it**: not the one box, not the Chat / In background / Quick task modes, not the + menu (files, photo, call), not the Browser button, not the Work rail, not the Learned section. | `pageGuides.ts` has no `/assistant` entry; no `data-tour` anchor in `components/assistant/*` |
| A3 | The arming rule waits for `data-tour="nav-rail"` and then needs an anchor for step 0; the personal landing has the rail but step 0 (`start`) anchors the Build Center, so the first-run welcome may never fire. | `tour/useTourArming.ts`, `steps/onboarding.ts` |
| A4 | The completion screen offers "Create an agent"; on a personal install the first move is to talk to the Assistant, connect a phone channel, or pull a model. | `tour/consumerGuide.ts` |
| A5 | No page guide for `/assistant`, `/connect`, `/scheduled`, `/research`, `/ai-models`, `/admin/instructions`, `/troubleshoot`, `/agents/turns`. | `pageguide/guidesWorkspace.ts` |

### Track B: sign-in is a password, and only a password

| # | Gap | Pointer |
|---|---|---|
| B1 | No passkey (WebAuthn) verifier, no `navigator.credentials` call anywhere, no credential table. | `LocalCredentialVerifier.java` javadoc; survey found zero hits in `web/`, `core/`, `installer/` |
| B2 | No PIN. The policy is 12 characters minimum in three places. | `LocalPasswords.MIN_LENGTH`, `LocalLogin.tsx` `MIN_PASSWORD`, `SignupRequest.java` |
| B3 | The sign-in form asks for an e-mail on an install that has one owner. | `LocalLogin.tsx` `LAST_EMAIL_KEY` |
| B4 | No recovery path except SQL and a restart. | `docs/personal-mode.md` item 7 |
| B5 | ~~Spring Boot 3.5.16 brings Spring Security 6.5, which ships WebAuthn support on `webauthn4j`; it is on the classpath and unused.~~ **Correction:** webauthn4j was not on the classpath (Spring Security's WebAuthn module declares it optional, so `~/.m2` held the Spring classes but not the library). The tranche added `webauthn4j-core` 0.31.11 to `core/admin` and had to pin `jackson-annotations` 2.22 in `core/pom.xml`, because webauthn4j 0.31 parses with Jackson 3.2 and Spring Boot's BOM manages 2.21. | `core/admin/pom.xml`, `core/pom.xml` |

### Track C: nothing is on by default

| # | Gap | Pointer |
|---|---|---|
| C1 | `web_search` is off on a fresh personal install unless the launcher was run with `--search` or a Brave key was stored on a **platform-admin** page the owner cannot open without `APP_AUTH_LOCAL_OWNER_EXTRA_ROLES`. The Setup app has no search checkbox at all. | `BuiltInWebTools.java` (offered only when a provider is configured), `scripts/vegaduta:712` `ask_search`, `installer/ui/index.html:67` (one checkbox, documents) |
| C2 | Brave is the default provider name, but a Brave key needs a Brave account with a card on file; the key-free provider (SearXNG) is the one a consumer can have on day one. | `application.yml:2427` `provider: brave` |
| C3 | **No MCP server is registered for any tenant by default**, and the personal core image cannot run a STDIO server: it is the slim image with no Node or uv, and the stack has no `mcp-stdio-runner`. "Verified" means "hand-typed into a Java list"; nothing pins a package version or a tarball hash. | `personal.yml:136` "Slim image (no Node/uv/browsers)", `McpCatalogEntry.verified` javadoc, `McpToolPinController` pins tools, not packages |
| C4 | Half the catalog's `ready` flags are false because the server needs a key; the one-click remote servers (GitHub, Linear, Atlassian, OAuth) are not surfaced as a starter set. | `McpReferenceServerCatalog.java`, `McpPlatformAndRemoteCatalog.java` |
| C5 | Tenant-scoped web search settings do not exist: the provider and its key are platform-level. | `PlatformToolCredentialController` (platform-admin only) |

### Track D: capabilities without a console surface

| # | Gap | Pointer |
|---|---|---|
| D1 | 25 controllers have no page or fetch in `web/` (procedures, outbound webhooks, audit seals, moderation review, realtime voice sessions, call recording, e-mail delivery status, A/B experiments, checkout, UPI, AG-UI, A2A card, grader bundles, wait-node resume, FIM, agent-turn completions, share vision, stdio sandbox status, comp grants...). Some are machine-facing by design; procedures, webhooks, audit seals, moderation review, experiments and call recording are not. | the route survey, section 5 |
| D2 | Workspace-default agent hooks are read-only in the UI. | `/api/settings/agent-hooks` |
| D3 | There is no `/settings` page; tuning is spread over `/providers`, `/ai-models`, `/providers/local-model`, the agent workbench, `/admin/instructions`, `/admin/guardrails`, `/usage`. A person has to know where to look. | the route survey, section 3 |
| D4 | No "What can I do here" page that reads the install's live state. The `vegaduta_setup_guide` tool computes it for the model; no page shows it to the person. | `SetupGuideToolSource.java` |
| D5 | No keyboard shortcut sheet. | none |

### Track E: devices and IoT

| # | Gap | Pointer |
|---|---|---|
| E1 | Web Bluetooth tools exist but only an in-browser model can call them; the server-side Assistant cannot ask the browser to read a device, and no button pairs one. | `lib/edge/localTools.ts:134`, `deviceToolsPair.ts` (no caller) |
| E2 | No WebSerial, WebUSB or WebHID; no MQTT; no Matter or Zigbee. | survey, zero hits |
| E3 | Home Assistant is an example, not a starter: no smoke test, page removed from the integrations index, hidden on personal installs. | `McpPlatformAndRemoteCatalog.java:58-110` "not yet smoke-tested" |
| E4 | The paired-device command queue (`core/devices`) is built for the mobile SDK (TAP, TYPE_TEXT), not for a browser acting as a device gateway. | `DeviceCommand.java` |

### Track F: automation for a consumer

| # | Gap | Pointer |
|---|---|---|
| F1 | The workflow canvas is a developer surface; the consumer entry points are "Describe it, automate it", templates and routines, spread over `/workflows`, `/workflows/templates` and `/scheduled`, with the last two reachable from the palette only. | nav survey |
| F2 | Missing consumer triggers: a file landing in a folder, an e-mail arriving (IMAP or Gmail polling for the Assistant, not a bulk channel), a device reading crossing a threshold, a calendar event starting. | `WorkflowTriggerService`, `WorkflowPollTrigger` (HTTP only) |
| F3 | No step-by-step "Power Automate" style builder (trigger, condition, action from a picker) for a person who will not read a canvas. | `WorkflowCanvas.tsx` |

### Track G: free local media

| # | Gap | Pointer |
|---|---|---|
| G1 | Every image, video and music generator is a paid cloud API. The model catalog holds text LLMs only. | `core/imagegen` (OpenAI, Gemini, Vertex), `catalog.txt` |
| G2 | Server-side TTS is cloud-only (ElevenLabs, Sarvam); Kokoro runs only in the browser; no Piper. STT has a free path (sidecar faster-whisper) behind the `rag` profile. | `services/sidecar/app/transcription.py`, `lib/edge/audio/kokoro.ts` |
| G3 | No hardware check for media models (VRAM for diffusion, disk for weights). | `HardwareDetector.java` (text models only) |
| G4 | No add-on concept: optional services are compose profiles and CLI flags; the Setup app exposes one checkbox; the console cannot show which are on or turn one on. | `installer/runner.go:19`, `scripts/vegaduta:598` |

## Design

### A. A first-login tour for the personal install

Keep the engine, add the audience, write the steps, anchor the Assistant.

1. **Audience `personal`** in `tour/audience.ts`, resolved when `isLocalAuth()`; it outranks
   tenant-admin. `selectSteps` then takes the personal step list and none of the plan, 2FA,
   platform-console or teammate steps.
2. **Steps**, in the order a person meets them, each with a `data-tour` anchor added to the
   component and a "Show me" that routes to a page the personal rail actually shows:
   - *Welcome*: what this install is (one box on your machine, your model, your data stays here),
     and the live facts the tour can verify: local model present or not
     (`GET /api/local-models/recommendation`), search on or off, projects folder on or off.
   - *The box* (`assistant-composer`): type anything; the Assistant decides whether to answer,
     run a task in the background or schedule it.
   - *Modes* (`assistant-mode-bar`): Chat, In background, Quick task, and the agent picker.
   - *Attach* (`assistant-attach`): files, a photo from the camera, a call with voice and camera.
   - *Browser* (`assistant-browser`): the agent's own browser, sign in yourself, hand it back.
   - *Work rail* (`assistant-work-rail`): running, needs you, scheduled; Learned: skills,
     preferences, routines it proposes, nothing applies until you approve.
   - *Threads* (`assistant-threads`).
   - Rail stops: Agents (tune or add specialists), Connect (Telegram, WhatsApp, SMS, Google),
     Workflows (automations), Memory, Skills, Knowledge, Tools (MCP), Providers (local model,
     speed and behaviour), Troubleshoot, Help (page guides and this tour).
3. **Arming**: on a local-auth install the first-run welcome arms on `/assistant` when the
   composer anchor exists; the Build Center redirect in `tour/firstRun.ts` is skipped for
   `personal` (the personal landing is already decided in `web/app/page.tsx`).
4. **Completion**: "What should I do next" with three verified moves (say hello to the
   Assistant, connect Telegram in two minutes, pull a model or add a key), plus the reopen hint.
5. **Page guides** for `/assistant`, `/connect`, `/scheduled`, `/research`, `/troubleshoot`,
   `/admin/instructions`, `/ai-models`, `/agents/turns`.
6. **Tests**: audience selection, step reachability against the personal rail, arming on
   `/assistant` (the existing `provider.test.tsx` and `steps.test.ts` patterns).

Copy must follow the tour's own rule: no claim without code behind it (`steps/index.ts` comment,
`consumerGuide.ts` "NO FICTION").

### B. Sign-in without a password: passkey first, PIN as the device unlock

The asks are "no username and password", "PIN of at least six digits or biometrics", "security is
important". A six-digit PIN has a million values; sent over a network as the only credential it
is weaker than the password it replaces. The design below keeps it as strong by making the PIN a
**device unlock**, never a network credential, and by making the biometric path a real passkey.

**Methods**

| Method | What it is | `amr` |
|---|---|---|
| Passkey | WebAuthn, platform authenticator (Touch ID, Windows Hello, Android), `userVerification: required`, `residentKey: preferred`, attestation `none`, RP ID = the console's host. Verified server-side with webauthn4j (added to `core/admin` by the first tranche; it was not on the classpath, see B5). | `hwk` |
| PIN | 6 to 12 digits, accepted **only from a browser that holds a device binding** (below). Hashed with the password encoder over `pin ‖ ':' ‖ deviceSecret`; no PIN hash exists without a device. Trivial PINs refused (all one digit, straight runs, repeated blocks, doubled-digit runs, a short list of common PINs). | `pin` |
| Recovery code | One of 8 one-time codes of 16 characters, shown once; only the SHA-256 of a code is stored (80 bits of entropy, so a fast hash is enough). | `rec` |
| Password | Kept as an optional method for people who prefer it; the 12-character policy stays. | `pwd` |

**Device binding.** After the first full proof of ownership (the one-time setup link, which only
the person with the machine's filesystem can read; later a passkey or password sign-in), the
console may enrol the browser: core issues a 32-byte random device secret, stored in an HttpOnly,
SameSite=Strict cookie scoped to `/api/local-auth` like the refresh token, and keeps only its hash
with a device name, created and last-used time. A PIN sign-in sends the PIN; the cookie carries the
device. No cookie, no PIN sign-in: a stranger on the LAN or behind a tunnel has nothing to guess.
The owner sees and revokes devices on the account page.

**Enrolment flow on first run.** The setup link opens the "Choose how you unlock" screen:
*Use Touch ID / Windows Hello* (passkey, recommended; falls back to the next option when
`PublicKeyCredential.isUserVerifyingPlatformAuthenticatorAvailable()` is false), *Use a PIN* (shows
the device binding note), *Use a password*. At least one method must be set. Recovery
codes (8, one-time) are shown once and can be regenerated.

> **Correction (as built).** The first draft required a passkey or password before a PIN could be added. It is
> not: setup may finish with a PIN only or a passkey only, so the account then has no
> password at all (`password_hash` was already nullable since V510, so no schema change was needed) and the 8
> recovery codes are shown at the end of setup and must be acknowledged. The last way to sign in cannot be
> removed (409 `last_credential`), which is what keeps a PIN-only account from locking itself out. A PIN is
> useless without its device secret, so a stranger who learns the digits can still do nothing from another
> machine.

**Sign-in screen on a personal install.** No e-mail field by default; it appears under "Use a password
instead". As built, `GET /api/auth/local/config` reports a `credentialTypes` list (`password`, `pin`, `passkey`,
`recovery`) rather than the `owner` and `methods` fields drafted here, and an older core that omits it is read as
password only. The page leads with a passkey **button** and shows a PIN pad when this browser holds a device. It
does not auto-prompt on load and does not use conditional mediation (that needs an autofill input, which the
default view deliberately does not have). "I lost my device" accepts a recovery code.

**Throttle and lockout.** The two-bucket `LoginThrottle` applies to every method. PIN adds a
third rule: 10 consecutive failures on one device revoke that device's binding, so the only way
on is a passkey, password or recovery code. The per-account lock keeps the "an address that
signed in before is still checked" exemption so the owner is never locked out of their own
console.

**WebAuthn specifics.** Challenges are 32 random bytes with a 2-minute TTL, single use, held **in memory** in
core (not in a table, correcting the storage paragraph below), so a core restart voids an in-flight ceremony and
the store is only valid with one core process, which matches the rest of local mode. The origin check uses `APP_WEB_PUBLIC_URL` and `APP_WEB_ALLOWED_ORIGINS`
(already in `personal.yml:200-201`), so a passkey registered on `http://localhost:3000` is refused
from any other origin. `localhost` is a secure context in every browser, so no TLS is needed on the
default install; a network-exposed console (`BIND_ADDR` not loopback) must be behind HTTPS for
WebAuthn to work at all, and the enrolment screen says so. Changing the console host changes the
RP ID and invalidates passkeys: the doc and the Troubleshoot page say to re-enrol. Sign-count
regression refuses the assertion and marks the credential suspect. Multiple passkeys allowed.

**Storage.** New control-plane tables (one migration): `local_passkey` (account, credential id,
COSE public key, sign count, AAGUID, transports, name, created, last used),
`local_device` (account, secret hash, name, user agent, created, last used, PIN hash, PIN
failures, revoked at), `local_recovery_code` (account, hash, used at). Migration V655 added exactly these
three; there is no challenge table (challenges are in memory, see above) and `local_account` did not change:
`password_hash` has been nullable since V510.

**Sessions** are unchanged: same access token, same rotating refresh token; the `amr` claim says
how the person signed in, so a future policy can require a fresh passkey for high-risk actions.

**Lite and Full (Keycloak).** Out of scope for the personal track; Keycloak has passwordless
WebAuthn built in. The realm change is a `webauthn-register-passwordless` required action and a
browser flow with "WebAuthn Passwordless Authenticator" as an alternative; the theme already
inherits those pages. One follow-up, listed below.

### C. Defaults that work on day one

1. **Search on by default.** The Setup app gains a "Web search (private, no API key, about 300 MB)"
   checkbox, on by default, which passes `--search`; `scripts/vegaduta personal` keeps asking on a
   terminal. The personal profile sets `WEB_SEARCH_PROVIDER=searxng` when the search profile is
   present and leaves `brave` otherwise. The Assistant's launchpad shows "Web search is off" with a
   one-click link to the search settings when `web_search` is not offered.
2. **Web search settings for the owner** (`/tools` gains a "Web search" card): provider, key for
   Brave, Tavily, Exa or Serper stored as a tenant secret (the vault exists), fallback and the
   daily cap; status of the SearXNG container. On a personal install this writes the platform tool
   credential through a new owner-scoped endpoint guarded by `app.auth.mode=local`; on other shapes
   the card shows the platform-admin pointer it shows today.
3. **Connectors runner in the personal stack.** A `tools` profile adds `mcp-stdio-runner` to
   `personal.yml` and sets `MCP_STDIO_SIDECAR_ENABLED=true`, so STDIO servers run in their own
   container without the 6 GB `-full` core image. *(Built as a launcher choice rather than a Setup
   checkbox; see the status section.)*
4. **Starter set**, registered for the Personal tenant on first run (seeded through
   `McpRegistryService`, each entry enabled only when its prerequisite holds): `filesystem` scoped to the
   projects folder, `memory`, `sequential-thinking`, `time` and `fetch` (uvx, Python packages; the runner image
   needs uv), `brave-search` enabled once a key is stored, `playwright-vision` when the runner has browsers,
   and the OAuth remotes (GitHub, Linear, Atlassian) shown as one-click connect cards on `/tools`.
   *As built, the set is smaller on purpose:* `fetch` and `time` (on) and `sequential-thinking` (off), seeded
   by a startup component and recorded in `mcp_starter_seed` (V659) so a deleted or edited one is never put
   back. `filesystem` duplicates the Assistant's `workspace_*` tools and would expose the projects folder to
   the runner; `memory` loses its data when the runner reaps the server; `brave-search` is deprecated on npm.
5. **Verified means pinned.** A catalog entry gains `packageSpec` with an exact version and the
   npm or PyPI integrity hash; the runner refuses a spec that does not match. The review queue
   stays for anything off-catalog.

### D. Every capability has a surface

1. A **Settings hub** at `/settings` (personal rail: "Settings") that links the existing pages
   by question: "Which model answers?" (`/providers`, `/ai-models`, local model), "How does it
   behave?" (`/admin/instructions`, agent workbench), "What may it do?" (`/admin/guardrails`,
   tool policies, approvals), "What does it cost?" (`/usage`), "Who can reach it?"
   (`/admin/channels`, `/connect`), "How do I unlock?" (account: passkeys, PIN, devices,
   recovery codes), "What is installed?" (add-ons, track G).
2. **Pages for the six human-facing orphan controllers**: procedures, outbound webhooks, audit
   seals (verify and export), moderation review, A/B experiments (the API client already exists),
   call recordings. Workspace-default hooks become editable.
3. **"What can I do here"** at `/help/capabilities`: the live state the setup-guide tool already
   computes (model, search, MCP servers and tool enablement, channels, Google, browser, projects
   folder, sidecar, add-ons), each row with what it enables and the page that changes it. The
   same data feeds the tour's welcome step.
4. A **shortcut sheet** on `?` and in the palette.

### E. Bluetooth, IoT and the browser as a device gateway

1. **The browser as a paired device.** Register the console tab with `core/devices` as a device of
   kind `BROWSER` with capabilities from `lib/edge/capabilities.ts` (`ble.read`, later
   `serial`, `usb`, `hid`). New command types `DEVICE_READ` and `DEVICE_LIST` ride the existing
   queue; the tab polls or streams its commands, runs them through the existing
   `deviceToolsRead.ts`, and posts the result. The server-side Assistant gains tools
   `device_list` and `device_read` from the same `ToolSource` family as `channelControl`, so
   "what is my heart-rate monitor saying" works on the real model, not only in-browser. Pairing
   is a button in the + menu ("Pair a Bluetooth device") that calls the existing
   `pairBluetoothDevice()`; Chrome and Edge only (Safari and Firefox have no Web Bluetooth) and
   the menu says so. Every read is user-visible in the trace; writes to a device are a later
   step and go through approval.
2. **IoT via Home Assistant first.** Smoke-test the example server against a real Home Assistant,
   promote it to a starter (track C), restore its integrations page, and add a "Smart home" card
   on `/connect` that asks for the URL and a long-lived token and stores them in the vault.
   Matter, Zigbee and Thread come through Home Assistant, not through core. A native
   `mqtt_publish` / `mqtt_subscribe` tool with an allowlisted broker is the second step, for
   people with a broker and no Home Assistant.
3. **WebSerial and WebUSB** follow the same gateway pattern once Bluetooth is proven; both need
   an explicit chooser per device, which the + menu provides.

### F. Automations a consumer can build

1. One **Automations** entry on the personal rail (`/automations`) that fronts routines,
   "Describe it, automate it" and templates, and leaves the canvas as the "Open in editor" step.
2. A **trigger / condition / action** builder on that page: a trigger picker (a time, a message
   on a channel, a web form, an e-mail arriving, a file in the projects folder, a device
   reading), optional conditions, and actions drawn from the tool catalog (send a message,
   create a calendar event, append a row, generate a document, ask me). It writes the same graph
   the canvas reads, so nothing is a second engine.
3. **New triggers**: folder watch (workspace runner reports new files), Gmail polling for the
   connected account, device threshold (from track E readings), calendar event start (Google).
4. Keep every side effect behind the existing approval gate; the builder shows which actions
   will ask.

### G. Free local media as add-ons

1. **An add-on registry** (as built: `core/providers/src/main/resources/addons/catalog.json`, served by
   `/api/addons` and embedded in the Setup app; the `deploy/addons/*.yml` and `scripts/vegaduta addons` shape
   drafted here was not used): id, title, what it enables, download size, RAM and GPU need, compose files and profiles,
   `.env` keys, health check. First entries: `search` (SearXNG), `tools` (MCP runner),
   `documents` (rag), `browser` (Chromium core), `voice` (LiveKit + voice-agent), `image`,
   `speech`, `music`, `video`.
2. **Media service** (`services/media`, Python, profile `media`): OpenAI-compatible endpoints so
   core needs no new client families: `/v1/images/generations` (diffusers: SDXL-Turbo and
   FLUX.1-schnell on CUDA or Apple MPS, Stable Diffusion 1.5 on CPU as the small option),
   `/v1/audio/speech` (Kokoro-82M ONNX on CPU, Piper as the multilingual option),
   `/v1/audio/transcriptions` (faster-whisper, moved out of the sidecar so documents and speech
   are separate downloads), `/v1/audio/music` (ACE-Step or MusicGen-small, GPU-gated), and
   video as text-to-video with LTX-Video or Wan 2.1 small, gated on 12 GB VRAM and marked slow.
   Each model is pulled on first use with the same progress job shape as `LocalModelPullService`,
   and the catalog lists licence and size the way `catalog.txt` does.
3. **Core**: a "Local media server" provider beside "Local / OpenAI-compatible server": the
   existing `OpenAiImageGenerationClient` takes a base URL, TTS gets a `LocalMediaTtsProvider`,
   STT points `SidecarWhisperProvider` at the new service. The `generate_image`,
   `generate_music` and `generate_video` tools then work with no key, metered at $0 like the
   zero-cost pool rows.
4. **Console**: `/add-ons` lists the registry with live state (on, off, pulling, needs more memory),
   the hardware verdict from `HardwareDetector` extended with VRAM and free disk, and an Enable
   button. Core cannot run Docker, so Enable writes the desired set to a tenant setting and shows
   the one command (`./scripts/vegaduta addons enable image`); the Setup app, which reconciles the
   personal stack, reads the desired set and applies it when opened, and its wizard offers every
   add-on as a checkbox at install time. A resident tray helper that applies changes without
   opening the app is the follow-up.
5. **Files**: generated media lands in `/files` and the projects folder as today.

## Order of work

Sized so one person finishes each in its own branch; the first three are independent of each
other.

| # | Piece | Tracks | Size |
|---|---|---|---|
| 1 | Personal tour: audience, steps, Assistant anchors, arming on `/assistant`, completion, eight page guides | A | medium |
| 2 | Passkey + PIN + devices + recovery codes: migration, verifiers, endpoints, sign-in and account screens, docs | B | large |
| 3 | Day-one defaults: search checkbox and provider default, owner web-search card, `tools` profile with the runner, starter MCP set, package pinning | C | medium |
| 4 | Settings hub, "What can I do here", shortcut sheet | D1, D3, D4, D5 | small |
| 5 | Pages for the six orphan controllers, hook defaults editor | D1, D2 | medium |
| 6 | Add-on registry, CLI and Setup app checkboxes, `/add-ons` page | G1, G4 | medium |
| 7 | Media service with image and speech first, core providers, catalog | G2, G3 | large |
| 8 | Browser as device gateway, Bluetooth pairing in the + menu, `device_read` tool | E1, E4 | medium |
| 9 | Home Assistant smoke test and starter card; MQTT tool | E2, E3 | small |
| 10 | Automations page and trigger / condition / action builder; folder and Gmail triggers | F | large |
| 11 | Keycloak passwordless WebAuthn for Lite and Full | B | small |
| 12 | Music and video in the media service | G | medium, GPU-gated |

## How this was verified

Four code sweeps (sign-in and onboarding, MCP and tools, console routes and tuning surfaces,
models, media, automation and devices), each with file-level evidence, cross-checked against
`docs/personal-mode.md`, `docs/local-models.md`, `CHANGELOG.md` and the two earlier gap documents
(`AI-FOR-EVERYONE-2026-10-14.md`, `PERSONAL-ASSISTANT-SURFACE-2026-10-17.md`). No stack was
started and no browser, device or model was exercised for this document.

## Status after this tranche

Written on 2026-10-19 after the first build tranche. "Shipped" means merged into the working tree with unit or
component tests; it does not mean exercised on a real stack. Verification notes are in the changelog entries and
in `docs/personal-mode.md`.

| Piece | State | What is true |
|---|---|---|
| B. Passkey, device PIN, recovery codes | Shipped (core and web), not tried with a real authenticator | V655 (`local_passkey`, `local_device`, `local_recovery_code`); webauthn4j-core 0.31.11 and a `jackson-annotations` 2.22 pin; `/api/auth/local/unlock/**` plus the console's same-origin proxy that keeps the device secret in an HttpOnly cookie; setup can finish with a passkey or a PIN; sensitive changes need a sign-in from the last 10 minutes. Passkeys were tested with a software authenticator that builds the attestation and assertion by hand, never with Touch ID, Windows Hello or a security key, and the console screens were tested in jsdom, not driven in a browser |
| C1, C3, C4. Defaults | Shipped, never run on a Docker stack | A fresh personal install turns on SearXNG search and the `tools` profile (the MCP runner) unless declined (`--no-search`, `--no-tools`, `VEGADUTA_SEARCH=0`, `VEGADUTA_TOOLS=0`), and registers `fetch`, `time` and `sequential-thinking` pinned to exact versions. The Setup app preselects search as well, so an installer run does not lose the default |
| C2, C5. Owner web-search settings | Not built | A Brave, Tavily, Exa or Serper key is still a platform-admin credential that a personal owner cannot reach. Search works without a key through SearXNG |
| C5. Pinned digests enforced | Not built | Exact versions are pinned and the PyPI sha256 and npm integrity values are recorded in `McpStarterCatalog` comments; `npx` and `uvx` resolve a version, so the runner does not refuse a mismatching download |
| G1, G4. Add-on registry | Shipped (core, console, Setup app); the host bridge is built on the core side only | `core/providers` catalog, V656 `addon_state`, `/api/addons` and the `/add-ons` page; model packs are pulled by core through Ollama; compose add-ons record the wish and show the exact command. The Setup app has an Extras step and `--extras`. **Not built:** the Setup app does not yet poll `GET /api/addons/host/wanted` or report back, and nothing generates `ADDONS_HOST_TOKEN`, so the bridge stays off and a console switch is applied by re-running Setup or pasting the command |
| A. Personal tour | Not shipped | A half-finished personal tour step file does not typecheck and is not in the tree; its English strings are in `web/messages/en.json` under `tour.*` and are unused. The first-run welcome still does not mention the Assistant |
| D. Settings hub, "what can I do here", shortcut sheet, orphan controllers | Not built | |
| E. Bluetooth gateway, IoT, WebSerial | Not built | |
| F. Automation builder and consumer triggers | Not built | |
| G2, G3, G5. Media service (images, speech, music, video) | Not built | The catalog lists "Local image generation" and "Local spoken replies" as planned, with no switch |
| 11. Keycloak passwordless WebAuthn for Lite and Full | Not built | |

What remains, in the order this document proposed: the tour (A), the settings hub and capabilities page (D), the
media service (G), the browser as a device gateway (E), the automation builder (F). Before those, three
follow-ups in what shipped: finish the host bridge (installer polling and `ADDONS_HOST_TOKEN`), decide whether the
Assistant browser add-on is the 6 GB `-full` image or the lighter browser-runner container the launcher now has,
and make the `tools` runner a real, switchable add-on (it is still listed as "Coming soon" even though the
launcher turns it on).
