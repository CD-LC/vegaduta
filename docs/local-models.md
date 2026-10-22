# Run free with local models

You do not need an API key or a cloud account. VegaDūta can run models on your
own hardware in two ways. They compose: you can use both.

| | In the browser (on-device) | Local server (Ollama) |
|---|---|---|
| Needs | A WebGPU browser (Chrome or Edge 113+, recent Safari) | Docker; the default `qwen3.5:4b` wants about 10 GB of memory with the stack (smaller models need less) |
| Setup | Open **/edge**, click download | `./scripts/vegaduta up --lite --local-ai` |
| Who benefits | One person, on that device | Everyone using the instance (it becomes the free platform pool) |
| Data | Never leaves the device | Never leaves your network |
| Tools and agents | Local tools; server tools run through the guarded tool bridge | Full agent features, subject to the model's tool-calling quality |
| Reaches other people | Yes: with the [edge relay](edge-relay.md) this browser answers your SMS and WhatsApp numbers | Yes, every channel |
| Speed | GPU of the device | CPU in a container; native GPU with the host mode below |

## The installer asks

On a first interactive `./scripts/vegaduta up` you are asked how agents get
their model: a free open-weights model on this machine (the default; the size
is picked from your RAM, a host Ollama is used when one is installed, and
otherwise the Ollama Docker container is added to the stack), a cloud provider
key added later in the console, or a model you choose from the catalog
(`gpt-oss`, `deepseek-r1`, `qwen3.5`, ...). The answer is stored in
`deploy/compose/.vegaduta-state` and never asked again. Scripts and CI (no
terminal) are not asked and keep the cloud default; pass `--cloud` to skip the
question, or `--local-ai` / `--local-model` to answer it up front.

`./scripts/vegaduta personal` (and the Setup app, which runs it) does not ask:
it picks the model that fits the memory it can use and downloads it. On macOS
and Windows it installs Ollama on the computer when it is missing. Only
`--cloud` (`-Cloud` on Windows), or a machine with less than 4 GB of memory,
skips the model. See [Personal mode](personal-mode.md#picking-a-local-model).

### Your first agent, no setup

After sign-up open **Agents → Agent files** (`/agents/bundles`) and pick a
ready-made agent, then open **Connect** (`/connect`) to reach it from Telegram,
WhatsApp, email, Slack or SMS and to add your documents. Or open **Templates** and import **Personal Assistant (works on a
local model)**. It needs no account or API key: it reads web pages, remembers
what you tell it and writes documents, and anything with a side effect waits
for your approval. After `up` with a local model the installer also checks that
the model can really call tools and tells you if it cannot.

### Talk to your agent from your phone

In the console open **Channels → Telegram**, create a bot with
[@BotFather](https://t.me/BotFather) and paste its token. On a lite install no
public URL is needed: core polls Telegram itself, so the bot answers as soon as
you save the token. Send it a message; the first time you will get a pairing
code to approve in the console. Replies go through the same approval gate as
any other channel. Polling runs in one process per bot; a multi-replica
deployment should set `TELEGRAM_WEBHOOK_BASE_URL` and use the webhook
instead.

### Free web search

Add `--search` (the installer also asks) to run a private
[SearXNG](https://github.com/searxng/searxng) container next to core. Agents
then get the `web_search` tool with no search-API key. SearXNG publishes no
port and joins only core's network; it queries public search engines itself,
so results depend on them and can be rate-limited. Without `--search`,
`web_search` reports that search is unavailable and the ready-made agents ask
for a link instead.

## Option 1: one command (Ollama in Compose)

```sh
./scripts/vegaduta up --lite --local-ai
```

This adds an `ollama` service, pulls a default model on first start
(`qwen3.5:4b`, about 3 GB, kept in the `ollama-data` volume), loads it, and
registers it as the **platform pool**. (`personal` sizes the model to the
machine instead.)
Core does the registration at startup and does it again, harmlessly, on every
restart. Sign up, create an agent, and chat: new accounts are on the FREE plan,
which uses the pool, so no provider key is involved.

Choose another model with `--local-model`:

```sh
./scripts/vegaduta up --lite --local-ai --local-model qwen3.5:2b   # smaller, for 8 GB machines
./scripts/vegaduta up --lite --local-ai --local-model qwen3.5:9b   # better quality, ~7 GB
```

Pass the same flags on later `up` runs; the choice is stored in
`deploy/compose/.vegaduta-state`. `scripts/vegaduta doctor` checks the prerequisites
and tells you what is missing.

On Linux with the NVIDIA container runtime, the GPU is used automatically.

## Option 2: use the Ollama already on your machine (best on a Mac)

Docker on macOS cannot reach the Apple GPU, so a containerised Ollama is
CPU-only there. A native Ollama uses Metal and is much faster.

```sh
brew install ollama            # or https://ollama.com/download
ollama pull qwen3.5:4b
./scripts/vegaduta up --lite --local-ai=host --local-model qwen3.5:4b
```

On Linux the host server must listen beyond loopback for containers to reach
it (`OLLAMA_HOST=0.0.0.0`). Ollama has no authentication, so firewall port
11434 from the rest of your network.

**Keep the model warm.** Ollama unloads a model after five idle minutes, and
the next chat then pays the whole weight load (tens of seconds for a 4B model)
before its first token. Core loads the model ahead of time (`POST
/api/local-models/warm`, run after a pull, after a model switch, after the
bootstrap and when a chat screen opens) with `app.local-models.keep-alive`
(default `30m`), but a chat request without that field resets Ollama's timer to
its own default, so set it on the server too: `OLLAMA_KEEP_ALIVE=30m` in the
environment of the Ollama app or service. While the model is loading, the chat
shows "Model is working…" with a running clock; the model's reasoning streams
below it as soon as it starts.

The Compose `local-ai` overlay (Option 1) does more: it loads the model when
the stack starts and never unloads it (`OLLAMA_KEEP_ALIVE=-1`), turns on
`OLLAMA_FLASH_ATTENTION=1`, and sizes the context window to Docker's memory
(see [Choosing a model](#choosing-a-model)). It also turns the hidden thinking
pass off on the local row (`APP_LLM_OLLAMA_REASONING_EFFORT=none`); set
`LOCAL_LLM_REASONING_EFFORT` in `.env` (for example `medium`) to change it.
When the Assistant runs on a local model it shows a short note with a link to
Agent settings, where you can tune it for speed or quality.

## What was registered, and why it is safe

- Core calls the local server only because `host:port` was added to
  `APP_SECURITY_OUTBOUND_ALLOWED_PRIVATE_HOSTS`. Everything else private stays
  blocked by the SSRF guard. See [configuration](configuration.md).
- The Compose Ollama publishes no host port. It joins only core's network.
- The pool row is stored with the same encryption as any provider key.
- The row is marked **Free (self-hosted)**: its calls are recorded at $0, never
  debit a user's daily budget, and the monthly free-plan token quota is not
  applied while every pool row is free. Nothing in the platform will ever ask
  you to "upgrade" to keep using your own laptop; see
  [Zero-cost pool rows](configuration.md#zero-cost-pool-rows).
- Core never fails to start because of this. If the server is unreachable or the
  model is missing, it logs a warning with the exact `ollama pull` command and
  you can fix it while the stack runs.
- The row repairs itself. If `APP_ENCRYPTION_KEY` changed (a re-install that
  kept the database but got a new `.env`), the stored key of the row no longer
  decrypts. At every start core re-keys such a row. It turns a disabled local
  row back on only when nothing else in the pool can serve. A chat that finds
  the pool empty runs the same repair on the spot (at most once every 30
  seconds); only this in-chat repair switches the pool's master switch back on,
  never a restart. `/troubleshoot` says why the row cannot be used (the pool is
  off, the row is disabled, or the row is unusable) instead of calling it in
  use.

## Option 3: in the browser, no server at all

Set `WEB_EDGE_AGENTS=1` (the example env file already does), sign in and open
**/edge**. The page checks your device, shows the download size and source
before you click, then runs the model on your GPU through WebGPU (the engine is
hosted in a service worker so it survives a reload). Nothing is downloaded
without a click, and you can delete the weights from the same page. The weights
come straight from the public model CDNs; the instance does not host them.
Browsers without WebGPU (current Firefox on most platforms, older Safari) get
Chrome's built-in model where it exists, otherwise the hosted route: the CPU
(WebAssembly) fallback exists in the code but has no install path in the UI yet.

## Choosing a model

These are starting points to try on your own hardware, not benchmark results.
Small models handle chat and summarisation well; reliable multi-step tool use
needs a bigger one, so test your own agent before relying on it.

| Hardware | Try |
|---|---|
| 8 GB laptop, CPU | `qwen3.5:2b`, `gemma4:e2b`, `granite4.2:3b`, `phi4-mini` |
| 16 GB laptop or Apple Silicon | `qwen3.5:4b` (default), `qwen3.5:9b`, `gemma4:e4b`, `llama3.1:8b`, `deepseek-r1:8b` (reasoning) |
| 24 GB+ GPU or 32 GB+ Apple Silicon | `gpt-oss:20b`, `qwen3.5:35b`, `gemma4:26b`, `mistral-small3.2`, `deepseek-r1:14b` / `32b` (reasoning) |
| 48 GB Apple Silicon or 32 GB GPU | `gemma4:31b` |
| Workstation, 80 GB+ VRAM or unified memory | `gpt-oss:120b` |

The full list, with parameters, context window, licence and what each model is
best for, is the [model catalog](#model-catalog); the console shows all of it,
including the models that do not fit your machine.

In container mode the `ollama` service's `mem_limit` follows the catalog's RAM
column for the chosen model (the 8 GB default cannot hold `gpt-oss:20b`); set
`OLLAMA_MEM_LIMIT` in `.env` to override it. `scripts/vegaduta doctor` warns
when Docker has less memory than the picked model wants.

On a machine where Docker has little memory the launcher also lowers the model's context window so it fits next to the stack: 8192 tokens under 6 GB, 16384 under 10 GB, the full 32768 otherwise. Set `LOCAL_LLM_NUM_CTX` in `.env` to choose your own (it feeds `app.llm.ollama.num-ctx` and Ollama's `OLLAMA_CONTEXT_LENGTH`).

Check each model's licence on its Ollama library page before redistributing it.

### Tool calls from small models

Small and free models often write a tool call as text instead of sending it
through the provider's tool-calling channel, or narrate "I will now generate
the report" and never call anything. Core has four layers for this; the first
two apply to every provider, the last two only to platform-pool rows that need
them.

1. **Recovery of text-form calls.** A reply that is entirely a call to a tool
   the agent was offered (`{"name": ..., "parameters": ...}`, a `<tool_call>`
   or `<function=...>` block, `[TOOL_CALLS]`, fenced or not) is run through the
   normal tool loop and the model is called again with the result. While
   streaming, a reply is held back only while its first characters could still
   be a call. Bounded by `app.chat.tools.text-call-recovery.max-per-invocation`
   (3); metric `chat.tool.text_call_recovery{outcome}`.
2. **Argument validation.** Before any tool runs, its arguments are checked
   against the tool's schema. A call missing a `required` property is not run;
   the model gets a tool result naming what is missing and the schema, and
   usually gets it right on the next attempt. Metric
   `chat.tool.argument_rejected{tool}`.
3. **A measured tool-call grade on every pool row.** When a platform-hosted
   row is saved, core sends the model one two-tool probe and stores the result
   on the row:

   | Grade | Meaning | What the pool does |
   |---|---|---|
   | `STRUCTURED` | a structured `tool_calls` entry came back | provider's own tool loop, nothing extra |
   | `TEXT` | the call came back written as text | recovery plus the constrained router below |
   | `NONE` | prose, or Ollama reports no `tools` capability (graded without a call) | skipped for chat turns while any other enabled row exists |

   The grade is shown as a badge in Platform Admin, LLM providers.
   `./scripts/vegaduta models pull TAG` measures the same thing after a pull,
   and `models list` shows the catalog's expected value in its TOOLS column.
   `scripts/vegaduta personal` measures the model once on the first start and records the
   result in `.env` as `LOCAL_MODEL_GRADE`; a restart reuses it until the model changes, so it
   does not load the model cold on every start. `VEGADUTA_REPROBE=1 scripts/vegaduta personal`
   measures again.
   The probe is short and permissive by design: `llama3.2:3b` passes it
   (STRUCTURED on Ollama 0.19) yet narrates tool runs once it sees a real
   agent prompt with a dozen tools, which is why the catalog marks it chat
   only and why small-model mode (point 4) exists. Switch
   `app.llm.tool-call-probe.enabled`.

   **Constrained tool router.** A row graded `TEXT` or `NONE` on a server that
   can enforce a constraint has its tool turns routed: on Ollama and llama.cpp
   the tools are taken out of the request, listed in one system message, and
   the reply is constrained to the JSON schema
   `{action: call|answer, tool: <offered tools>, arguments, answer}`
   (`response_format: json_schema`, which Ollama turns into constrained
   decoding); on vLLM the tools stay, a `final_answer` tool is added and
   `tool_choice` is `required`. The chosen call runs through the same tool loop
   as a structured call, and the model is called again with the result until it
   answers or `app.chat.tools.constrained-router.max-rounds` (6) is reached.
   The final answer arrives as one chunk. A STRUCTURED row is never routed; the
   per-row "Constrained tool router" control forces it on or off. Not every
   Ollama build enforces a schema for every model: Ollama 0.19 answers
   "structured output is unavailable" for models its MLX runner serves
   (`qwen3.5:4b` on Apple Silicon); the probe detects that and such a row gets
   no router, and a refusal at run time falls back to the plain request for the
   rest of the process. Metric `chat.tool.constrained_router{outcome}`.
4. **Small-model mode.** A row whose model has 8B parameters or fewer (as the
   server reports, or as written in the tag: `llama3.2:3b`, `qwen3:8b`) runs
   its turns in small-model mode unless the row's "Small-model mode" control
   says otherwise: at most six tools are offered, chosen by relevance to the
   message (a tool named in the message always wins), the compact prompt
   directives are used (a short tool-trust rule; skills listed by name only
   until the model calls `load_skill`), and the turn runs at Fast reasoning
   depth. This is what the research on small models agrees on: they do short,
   structured tool use well when they see few tools, flat schemas and little
   standing text, and badly when they see the full frontier-model prompt.

Together: a 3B-4B model that gets the right tool with a real prompt on this
stack today is `qwen3.5:4b` (structured calls, 7 s per probe on an M-series
laptop); `qwen3:8b` and `granite4.2:3b` are the next ones to try. Keep a
tools-capable row in the pool even if you also keep a chat-only one.

### A free hosted fallback chain

A laptop's Ollama is the cheapest row, not the most available one. Add two
free hosted rows behind it in Platform Admin, LLM providers, so an agent keeps
working when the machine is asleep or the model is busy:

| Priority | Row | Why |
|---|---|---|
| 1 | Local Ollama (`qwen3.5:4b`), free flag on | $0, private, first in the failover order |
| 2 | Groq, model `openai/gpt-oss-20b` (provider Groq, paste a free Groq key) | free tier, fast, structured tool calls; the pool self-heals the model id if Groq renames it |
| 3 | Gemini, model `gemini-2.0-flash-lite` (provider Gemini, free API key) | multimodal fallback, generous free quota |

The pool tries rows in priority order and falls through on a 429, a timeout
or a 5xx (see `FreeTierChatClientService`); the two hosted rows are graded on
save like any other, and Groq's and Gemini's free tiers both grade STRUCTURED.
Free hosted tiers rate-limit by the minute and the day, so an agent that
loops on tools will hit them sooner than a chat does; the Platform Admin
"Hosted LLM pool" page shows which row served each turn.

## Model catalog

One file, `core/providers/src/main/resources/local-models/catalog.txt` (core reads it
from the classpath; `deploy/models/catalog.txt` is a symlink to it in the
repository and a plain copy in release downloads), lists every
open-weight model the CLI and the console offer, so the two can never disagree.
Add a line to offer another model.

Each row is pipe-separated. The first six columns are fixed in place (readers
index them by position); the rest were added for the console's detail card:

| Column | Meaning |
|---|---|
| `tag` | The Ollama tag (`qwen3.5:4b`). |
| `family` | Qwen, Gemma, Llama, Phi, Mistral, DeepSeek, gpt-oss, Granite, Hermes. |
| `download GB` | Approximate size of the weights at Ollama's default quantisation. |
| `RAM GB` | Memory the machine should have free for the model plus the rest of the stack. This is the figure the "fits" judgement uses. |
| `tools` | How the model makes tool calls: `structured` (agents work as they are), `text` (recovered from text, slower), `chat only` (no tool use), `unverified` (not measured here yet; core measures the pool row when it is saved). See [Tool calls from small models](#tool-calls-from-small-models). |
| `note` | One line shown under the model. |
| `parameters B` | Total parameters in billions. Mixture-of-experts rows count every expert; the note names the active share. |
| `context K` | Context window in thousands of tokens. |
| `VRAM GB` | Video or unified memory that holds the whole model, for people with a dedicated GPU. |
| `licence` | Apache-2.0, MIT, Llama community licence, ... Check the model's Ollama library page before redistributing it. |
| `thinking` | `yes` when the model reasons before answering (slower first token), `no` when it answers directly. |
| `best for` | What the model is good at, in a few words. |

The `#ladder|minGB|tag` comment lines map machine memory to the recommended
model for both the CLI (`recommended_local_model`) and core. A machine on its own,
running one person's agents, is described in [Personal mode](personal-mode.md).

### What the console shows

`GET /api/local-models/recommendation` returns the whole catalog, not only the
rows that fit: `recommended` (the ladder's pick), `alternatives` (the runners-up
that fit, best first), `catalog` (every row in file order, each with the detail
columns and a `fits` flag) and `budgetGb`, the memory this machine can give a
model (75% of system memory on Apple Silicon and CPU-only machines; with a
discrete GPU the larger of 90% of its video memory and half of system memory).

The wizard at **Providers, Set up a local model** (`/providers/local-model`)
shows two groups, **Fits your machine** and **Needs more memory (from N GB, you
have M GB)**, with every row selectable and marked when it is already
downloaded or is the model agents currently use. Selecting a row opens a detail
card: parameters, download, memory needed against what was detected, GPU memory,
context window, tool-calling grade, thinking, licence, a speed expectation and
what the model is best for. A model that does not fit can still be downloaded,
after ticking the warning that it may be very slow or fail to load.

Finishing the wizard points the install's local pool row at the chosen model
(`POST /api/local-models/use`, the same switch `/troubleshoot` and the CLI use)
and creates the first agent on that pool, like every other agent. It no longer
creates a per-agent Ollama provider key, which used to make the same model read
as "platform" on one screen and "your own provider" on another. On a personal
install (local sign-in) the console calls that row **Local model (Ollama)**
everywhere it used to say "Platform LLM" or "platform-hosted (shared pool)", and
the "Use platform LLM" toggle is not shown: there is no hosted pool to opt into.

### From the command line

`scripts/vegaduta models` lists the catalog (tag, download size, RAM wanted,
tool grade, note), marks the ones already installed, and installs or removes
them in the Ollama this install uses (the Compose container or the one on your
machine):

```sh
./scripts/vegaduta models list
./scripts/vegaduta models pull qwen3.5:9b
./scripts/vegaduta models rm qwen3.5:9b
```

Installing puts the weights on the server. To make the pool use a new model, use
the console (the wizard above, or **Use** on `/troubleshoot`), or add or edit its
row in Platform Admin, LLM providers: the startup registration never changes a
row that already exists.

## Troubleshooting

| Symptom | Fix |
|---|---|
| Sign-up ends on "Failed to send email" | Keycloak has no SMTP and the account was told to verify its email. The lite tier turns that off (`KEYCLOAK_REQUIRE_EMAIL_VERIFICATION=false`); on the full stack configure `KEYCLOAK_SMTP_*` or set that variable to `false`. |
| Chat says no active provider | Core could not register the pool row. `./scripts/vegaduta logs core | grep -i "local llm"` shows why. |
| Chat answers "No platform LLM is configured…" while Troubleshoot shows the model in use | The local row's stored key no longer decrypts (a new `APP_ENCRYPTION_KEY` with the old database), or the row or the pool was switched off. Core repairs this at every start and again inside a chat that finds the pool empty (see [What was registered](#what-was-registered-and-why-it-is-safe)); restart core or send another message. `/troubleshoot` names the cause. If the log says the SSRF guard refuses the address, add the `host:port` it names to `APP_SECURITY_OUTBOUND_ALLOWED_PRIVATE_HOSTS`. |
| `host not allowed` | The host:port must be in `APP_SECURITY_OUTBOUND_ALLOWED_PRIVATE_HOSTS`; `--local-ai` sets it for you. |
| First reply takes minutes | The model is still downloading. `docker compose ... logs ollama`, or wait for the `ollama` container to turn healthy. |
| `host.docker.internal` does not resolve (Linux) | Use `--local-ai=host`, which adds the mapping, and listen on `0.0.0.0`. |
| Very slow replies | CPU-only. Use a smaller model, or run Ollama natively (Option 2). |
| "Every model this workspace can use failed" after about four minutes | A CAREFUL/RIGOROUS/PLANNED agent ran out of time (`app.llm.http.buffered-turn-timeout`, 240 s): those depths make several model calls per turn, which a small local model cannot fit. Core now answers such a turn at FAST depth without tools and says so in the trace; to stop it recurring set the agent's reasoning depth to FAST (small-model mode does this on its own for models of 8B or fewer, judged by the model tag when the stored size disagrees). |
| A reply fails with "every model failed" and the log shows `HTTP 500 ... XML syntax error on line 3: element <function> closed by </parameter>` | Ollama could not parse a tool call the model wrote (seen with `qwen3.5:4b` after a tool result, mostly on multi-step browser use). Core replays the request once when it sees this (log: "replaying the request once", counter `llm.ollama.toolcall_parse_retry`), which helps when the model's next try is well-formed; it did not help in the browsing case that was reproduced, so a bigger model (`qwen3.5:9b`) or a hosted key is the reliable way past it. Cause still open; see the personal-install audit. |
| Replies are poor or truncated with a long system prompt on Apple Silicon | The MLX runner ignores the per-request `num_ctx` core sends and loads the server default (4096). Set `OLLAMA_CONTEXT_LENGTH=16384` (or the value of `APP_LLM_OLLAMA_NUM_CTX`) in the Ollama server's environment and restart it; core logs a warning with this hint after each warm-up when the loaded context is smaller than requested. |
| The first reply after a pause is slow, later ones are fine | Ollama unloaded the model. On an Ollama you run yourself set `OLLAMA_KEEP_ALIVE=30m` (or `-1`) on the server; the Compose overlay already keeps it loaded. See "Keep the model warm" under Option 2. The chat shows "Model is working…" with a clock while it reloads. |
| Replies take a minute or more on a laptop | Three things set the wait: the model loading (the Compose overlay now loads it at start and never unloads it: `OLLAMA_KEEP_ALIVE=-1`), the hidden thinking pass (the overlay sends `reasoning_effort: none`, `LOCAL_LLM_REASONING_EFFORT` in `.env`) and the size of the model and of the prompt on a CPU. For more speed pick a smaller model (`scripts/vegaduta models list`), keep conversations short, and turn off tools the agent does not need in Agent settings; for better answers at the cost of speed set `LOCAL_LLM_REASONING_EFFORT=medium` and a bigger model. |
| The reply is raw JSON like `{"name": "...", "parameters": ...}` | The model wrote a tool call as text and kept doing so past the recovery limit, or named a tool the agent does not have. See [Tool calls from small models](#tool-calls-from-small-models); check the row's grade badge, and use a larger model for agents with many tools. |
| The agent says "I will now generate the report" and nothing happens | The model narrates instead of calling. Check the row's tool-call grade in Platform Admin; a `NONE` row is chat only. Pull a tools-capable model (`qwen3.5:4b`, `qwen3:8b`) and add it as a row, or force the constrained router on for the row. |
