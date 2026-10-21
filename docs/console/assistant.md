# Assistant

**Assistant** (`/assistant`, sidebar label "Assistant", Build group) is the single conversation
surface: one composer where you type what you want and the workspace's Assistant agent — or any
agent you pick — does it. On a personal install it is also where **Playground**, **Jobs** and
**Quick Task** lead (since 2026-10-18): those sidebar rows are gone there and the old routes redirect here,
keeping their deep links (`/playground?agent=…`, `/jobs?agentId=…`, `/quick-task?prompt=…`). Lite and full
installs keep those three pages in [Test and monitor](test-and-monitor.md). On a personal install the Assistant is
also the page you land on after signing in.

The design is [the personal-assistant surface](../architecture/PERSONAL-ASSISTANT-SURFACE-2026-10-17.md):
one composer, the dispatch hidden, progress spoken plainly. Every call goes to endpoints that already
exist — the page is a composition, not a new backend.

| | |
|---|---|
| Route | `/assistant` |
| Nav needs | any member (Build group, first row after Home) |
| Backend rule | signed-in; the picked agent's access rules apply |
| Deep links | `?agent=` (agent id), `?mode=background\|task`, `?prompt=` (prefill), `?from=` (which old route sent you) |
| API | `POST /api/agents/{id}/chat` (SSE, `stepEvents:true`), `POST /api/agents/{id}/jobs`, `POST /api/quick-task*`, plus the conversation, file-upload and voice endpoints below |

## The composer

Three mode chips under the box ("How to send") pick **how** a message is sent, and an agent picker ("Who
answers") picks **who**:

- **Chat** (default): a streamed turn on the chosen agent.
- **In background**: starts a durable agent job (`POST /api/agents/{id}/jobs`); shown only when the
  install has jobs. Background work keeps running while you do something else and appears in the
  work rail.
- **Quick task**: a quick task (`POST /api/quick-task`) — the one-shot errand mode the old Quick Task page
  ran. It previews the agent and tools, asks you to confirm, then runs and can deliver the result to
  WhatsApp, SMS, a call or just the thread; the run renders as a card in the conversation. The picker
  offers **Auto** here, so the platform chooses an agent.

Type **/** in the box for commands: `/research`, `/schedule` (create a routine, for example
`/schedule every weekday at 8 summarise my inbox`), `/background`, `/agent` (ask another of your agents),
`/remember`, `/export` (download the thread), `/new` and `/help`. **⌘K** opens a command palette.

The **+** attach menu (or drag-and-drop, or paste) offers files, the camera and a call: uploads land
in the thread (up to 25 MB each; a PDF's text is extracted beside it), the camera needs a secure page
(https or localhost), and the call button starts a voice call that lands in the same conversation —
inside a call you can also share your screen, which the on-device VLM describes to the agent without a
frame ever leaving the browser.
On a personal install with the `workspace` profile there is also **Attach a folder**, which binds the
Assistant to a project folder for the rest of the thread — see
[personal mode](../personal-mode.md#build-projects-with-the-assistant).

## Setup help that knows the install

Two read-only tools are offered to the Assistant on every install. `vegaduta_api_guide` searches this
install's own OpenAPI spec (`/api/openapi.json`). `vegaduta_setup_guide` matches a request to the
console page that sets it up (`core/agents-tools/src/main/resources/platform-guide/topics.json`: MCP
servers, channels, Google, model providers, knowledge, API keys, SSO, custom tools...), reads the live
state where a module can tell (registered/failing MCP servers and which catalogue server fits the
request, channel connections, the Google link), lists what else has to be on (for MCP: the server must be
registered **and** its tools enabled for the agent), and searches a bundled copy of these docs. The
Assistant answers with the page links and steps and asks you to switch it on and send the request again;
it never changes configuration itself. After editing `docs/console/*.md` or a topic's `docs`, run
`bash scripts/ci/sync-platform-guide-docs.sh` (CI runs it with `--check`). Switch off with
`APP_ASSISTANT_PLATFORM_GUIDE_ENABLED=false`.

## Threads and the work rail

The left rail lists your threads (grouped Pinned, Today, Yesterday, This week, Earlier) with search, pin,
rename and a confirmed delete; finished turns collapse under a "Worked for …" line with the tool calls inside it.

When the install keeps chat history (`APP_CHAT_BRANCHING_ENABLED`; on by default for personal, lite and full installs and the Helm chart),
the conversations are stored on the server for 30 days, not only in this browser. The rail then also lists the
threads you started on another browser or device (`GET /api/chat/threads`), reopening an older thread reads it back
from the server (tool steps of earlier turns are not kept: "Restored from the server"), and deleting a thread also
deletes the server copy (`DELETE /api/chat/threads/{agentId}/{sessionId}`). With chat history off, the list lives in
this browser only.

The work rail keeps background jobs and
tasks visible while you chat — waiting-on-you, running and done — and lists routines with their next
run; a task that finishes in the open conversation posts its result there, and a badge counts live
tasks on narrow screens. The tenant-wide ledger (`/jobs/all`), schedules (`/jobs/schedules`) and the
per-job live page (`/jobs/{agentId}/{jobId}`) stay as their own routes. A turn shows a live status line
before the first token arrives (phase events: checking safety, gathering context, the model working),
and a thinking ticker streams the model's reasoning as `thinking` step events when the model exposes
it — shown by default on this surface.

## Per-reply tools

Two toggles sit beside the composer (per browser): **reasoning** shows the model's raw reasoning in
the work trace instead of just the working summary, and **voice** reads each finished reply aloud
(on-device Kokoro where installed, the browser's speech synthesis as a fallback — nothing leaves the
machine). Each finished reply carries a **request X-ray** (the exact prompt, sampling options and
tool list its model calls carried — the same inspector the Playground has), per-turn metrics, and
actions: **Copy**, **Listen**, **Regenerate** (on the last reply) and feedback. The header's **About this assistant** popover names the agent behind
the surface, its model and what it can reach; a **Connect** card offers the messaging channels when
none is linked yet.

When the agent runs on a model on your own computer (Ollama), a short note under the header says replies depend
on how fast the computer is and links to **Agent settings**, where the agent can be tuned for faster or better
answers. **Got it** hides the note in this browser.

When no model is set up yet, the page shows "Pick a model to start" instead of pretending to work, with
**Open providers** (`/providers`) and **Try again**. See [local models](../local-models.md) for running a model on
your own computer.

If a chat on a personal install answers that no platform model is configured, the notice points to
[Troubleshoot](configuration.md#troubleshoot-troubleshoot), which says why the local model cannot serve.
