# AI and engine

The **AI & engine** group holds the model, decision and code runtimes: which providers your agents run on, the
decision layer, code intelligence and the on-device runtimes.

| Label | Route | Nav needs | Backend rule |
|---|---|---|---|
| Providers | `/providers` | any member | `POST /api/providers`, `DELETE /api/providers/{name}`, the ElevenLabs, Sarvam and SDLC credential writes and OCR credential need `PERM_MANAGE_INTEGRATIONS` |
| Decision providers | `/capabilities/decision` | any member | credential, policy and playground writes need `PERM_MANAGE_INTEGRATIONS` |
| Code Intel | `/code-intel` | any member, when the build has a Code Intel address | `/api/code-intel/**` needs the tenant-admin or agent-builder role |
| Browser models | `/edge` | any member | the manifest is public; relay routes need `PERM_MANAGE_CHANNELS` |
| Local code | `/code` | any member (page is also public) | none; no API call is made |

## Providers (`/providers`)

Connect and activate the LLM providers agents run on. This is the first stop for a new workspace: an agent cannot be
created without at least one active provider, and the Agents page links back here when there is none. The page is a
section titled with the active and total provider counts, one card per provider (Anthropic, OpenAI, Google Gemini,
Mistral, Groq, OpenRouter, Ollama and others), each with a key form, a start hint and credential help. The same
screen also holds the keys for adjacent services: ElevenLabs (with a voice list), Sarvam, and the SDLC credential
with the code-execution template and a "run code" sandbox test. Keys are encrypted at rest with `APP_ENCRYPTION_KEY`
([getting started](../getting-started.md)).

On a personal install the page also has **Set up a local model** (`/providers/local-model`), a guided setup for a
free model that runs on this computer through Ollama: it lists the models that fit the machine's memory, downloads
the one you pick and makes agents use it. Its **Speed and behaviour** card sets how long the model thinks before
answering, how long it stays loaded and its context window. A personal install with no provider yet sees a
first-run card for this on the page. On lite and full installs the models the platform provides are set up by the
operator, so these entries are not shown. See [local models](../local-models.md).

A link on the page opens **AI Model Control** (`/ai-models`), where the workspace picks a preferred provider and
model per AI-touching feature instead of the platform default; changing it needs `PERM_MANAGE_TENANT_SETTINGS`
(`GET/PUT/DELETE /api/tenant/ai-model-preferences/{featureKey}`).

API: `GET /api/providers`, `POST /api/providers`, `DELETE /api/providers/{name}`, `/api/elevenlabs/credential`,
`/api/elevenlabs/voices`, `/api/sarvam/credential`, `/api/sdlc/credential`, `/api/sdlc/sandbox/run-code`,
`PUT /api/tenant/settings`.

## Decision providers (`/capabilities/decision`)

A decision provider is not a chat provider: it never generates text. It answers typed questions (a yes or no
probability, a choice over options, a score over levels) and powers the decision layer: cost routing on chat turns
and Quick Task agent selection. The page lets you see whose key the active decision provider is running on for your
traffic (yours, the platform's, or none), bring your own key for any provider the catalog lists (which then takes
precedence for the workspace), and experiment against the resolved key in a playground. `/capabilities` has no page
of its own and redirects here; the old `/jev` URL does too. API: `GET /api/capabilities`,
`/api/capabilities/{cap}/credential`, `/api/capabilities/{cap}/policy`, `/api/capabilities/decision/status`,
`/api/capabilities/decision/playground`.

## Code Intel (`/code-intel`)

The console for the code-intelligence service (a sibling backend service, reached with the app's token at the
configured `NEXT_PUBLIC_CODE_INTEL_URL`). Tabs: **Repositories** (add a repository by git URL or path), **Knowledge**,
**Search** (results use the shared pager), **Graph**, **Execute** and **Governance**. Per-agent repository access is
set on the agent (`PATCH /api/agents/{id}/code-intel-repo-access`).

## Browser models (`/edge`)

Sidebar label: **Browser models** (the page is the VegaDuta Edge console). It holds: a device verdict and a mode master toggle, the on-device model marketplace (real downloads
with visible progress), installed-model management and a privacy band. The page itself explains when this device
cannot run models locally. The catalog comes from the public `GET /api/edge/manifest` (cached for five minutes);
operators edit that catalog in [Platform admin](platform-admin.md). The relay used by the Edge relay feature is
documented in [Edge relay](../edge-relay.md). For free local models see [local-models.md](../local-models.md).

## Local code (`/code`)

A browser-local code assistant: open a folder from this device, index it in the tab, ask questions and apply
suggested edits, with nothing leaving the browser. The route makes no API call and carries no session, which is why
it is also a public route. The sidebar label comes from the `localCode` message namespace, not `nav`. Needs a
browser with the File System Access API; otherwise the page says so.
