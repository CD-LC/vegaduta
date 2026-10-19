# Personal mode

Personal mode runs VegaDūta for **one person on one machine**: a laptop, a desktop or a small box you own. It is
the same platform as Lite and Full with less around it. There is no Keycloak, no Kafka, no Temporal and no
object store. You sign in with a password that core itself holds, and the defaults lean toward safety over
convenience: untrusted content is tracked, tools that run local code are sandboxed, and the agent can learn
skills that you approve.

It is not a different product and it is not a separate codebase: the same images start with the `personal`
Spring profile ([`application-personal.yml`](https://github.com/CD-LC/vegaduta/blob/main/core/app-config/src/main/resources/application-personal.yml)),
and Lite and Full behave exactly as before.

| | Personal | Lite | Full |
|---|---|---|---|
| Containers always on | Postgres, Valkey, core, web (`--minimal`: just Postgres, core, web) | Postgres, Valkey, Keycloak, core, web | Lite plus PgBouncer, storage, sidecar and more |
| Sign-in | local, one owner (core) | Keycloak | Keycloak |
| Container memory limits (sum) | about 2.4 GB (about 2.2 GB with `--minimal`) | more (Keycloak alone is about 768 MB) | more |
| Billing, plans, payments | off | on unless disabled | on unless disabled |
| Meant for | one person | trying it | one server, a team |

## Requirements

- **Docker with Compose v2.24 or newer.** The launcher checks it. Docker needs about 4 GB of memory (2 GB if core
  runs on the host with `--native-core`), and you need roughly 8 GB of free disk for images and data.
- **A model.** Either an LLM provider key (Anthropic, OpenAI, Gemini, ...) added in the console, or a local model
  through [Ollama](https://ollama.com/download) (see [Picking a local model](#picking-a-local-model)). A local
  model needs its own memory on top of the stack; the launcher warns below 8 GB of host RAM.
- **Java 21 or newer** only for `--native-core`, which runs core on your machine's JVM instead of in a container.
- macOS, Linux, or Windows. On Windows the container install runs through `scripts/vegaduta.ps1` (or the Setup
  app's Windows wizard, which drives it); `-NativeCore` is rejected there with a pointer to WSL2.

## The Setup app (no terminal)

Once a release is published, the easiest install is `vegaduta-setup` from the
[Releases](https://github.com/CD-LC/vegaduta/releases) page (macOS, Windows, Linux): a single binary that opens a
setup wizard in your browser, checks the machine, installs or starts Docker when asked, downloads the verified
release, starts the stack and hands you the first-run setup link. On personal it has an **Extras** step: the
optional add-ons (the Assistant's build terminal, private web search, document reading, the Assistant's browser and
free local model packs) with their disk, memory and licence, the recommended ones ticked (the build terminal and web
search), and a total against this computer's free disk, Docker memory and RAM. Shortfalls are warnings, never
blocks. See [Add-ons](#add-ons). The wizard also offers a language picker: the choice lands in
`.env`'s `APP_LOCALE` and the console opens in it (all 30 console locales). The binary is not yet code-signed, so
the OS warns once. Details and headless flags: [`installer/README.md`](https://github.com/CD-LC/vegaduta/blob/main/installer/README.md).

The same result from a terminal against a published release:

```sh
curl -fsSL https://raw.githubusercontent.com/CD-LC/vegaduta/main/install.sh | sh -s -- personal
```

`install.sh` downloads the release tarball, verifies it (SHA256SUMS, and the Sigstore signature when `cosign` is
installed) and runs the same `scripts/vegaduta` the Setup app calls. **No release has been published yet**, so for
now build from your checkout, the same as the Lite quickstart:

## Start with one command

```sh
./scripts/vegaduta personal --build
```

`personal` checks the machine (`scripts/vegaduta doctor --personal` runs the same checks), generates the secrets in
`deploy/compose/.env`, starts the stack, waits until it is healthy and prints the console URL and the first-run
link. Later runs do not need `--build` once the images exist; plain `./scripts/vegaduta up` also starts a personal
install, because the choice is remembered in the state file. After a release is published, drop `--build` and the
images are pulled instead.

| Command | What it does |
|---|---|
| `./scripts/vegaduta personal` | start the personal stack (unchanged containers are left running) |
| `./scripts/vegaduta restart` | restart core and print a fresh first-run setup link and the sign-in e-mail. Use it for a lost or expired link |
| `./scripts/vegaduta personal --native-core` | core on the host JVM (`-Xmx1g`, SerialGC), the rest in Docker. Saves the container's memory limit and the Docker VM overhead; logs go to `~/.vegaduta/personal/core.log` |
| `--local-ai=host`, `--local-model TAG` | use the Ollama on this machine with that model (see below) |
| `--cloud` | do not adopt a local Ollama; you will add a provider key in the console. Remembered: a later plain `personal` stays cloud. Install Ollama later and run `personal --local-ai=host` to switch |
| `--search` / `--no-search` | start or leave out the private SearXNG that gives agents `web_search` with no API key. On by default for a fresh install; the choice is remembered (see [What is on from the start](#what-is-on-from-the-start)) |
| `--no-tools` | leave out the MCP connector runner and its starter connectors. `--profile tools` turns it back on |
| `--no-valkey` | leave out the Valkey container; core's counters and caches run in its in-process fallback (see [Running without Valkey](#running-without-valkey)). `--valkey` turns it back on |
| `--minimal` | the floor: `postgres` + `core` + `web` and nothing else (implies `--no-search --no-tools --no-valkey` and `--no-browser`). Every piece a flag turned off stays off until you ask for it again |
| `--profile rag` | add the ML sidecar (document extraction for PDF and scans, sidecar embeddings, transcription) and switch core's sidecar on |
| `--profile workspace` | add the `workspace-runner` container and the projects folder — the Assistant's build terminal (see [Build projects with the Assistant](#build-projects-with-the-assistant)). The Setup app turns this profile on by default and offers `rag` as its documents checkbox |
| `--dry-run` | print the plan, change nothing |
| `./scripts/vegaduta personal status` / `logs [core]` / `down` | inspect or stop it. `down --volumes --yes` also deletes the data |
| `./scripts/vegaduta backup` | database dump, files and `.env`. **Keep the backup of `.env`**: `APP_ENCRYPTION_KEY` must never change |
| `make up-personal` | the same as `personal` |

By default the console is on <http://localhost:3000> and the API on <http://localhost:8080>, both bound to
`127.0.0.1` only. Postgres and Valkey publish no port (with `--native-core` they publish on `127.0.0.1` for the host
JVM). Change `BIND_ADDR` or the `*_HOST_PORT` values in `deploy/compose/.env` to expose the console on your network,
and set `PUBLIC_APP_URL` / `PUBLIC_API_URL` to the address people will actually type. If you only change a port,
`scripts/vegaduta personal` rewrites the two URLs to match when they are still the generated `http://localhost:<port>`
form; a hostname or `https://` value you set is never touched.

The console's sign-in proxy runs inside the web container and reaches core at `CORE_INTERNAL_URL` (the container
core: `http://core:8080`; with `--native-core`: `http://host.docker.internal:<CORE_HOST_PORT>`). On Docker Desktop
that reaches a host core bound to loopback. On **Linux with `--native-core`** a core bound to `127.0.0.1` is not
reachable from the web container, and the doctor warns: use the container core, or set `BIND_ADDR=0.0.0.0` behind a
firewall. With `--native-core`, `--profile rag`, `--search` and a container Ollama (`--local-ai`) publish their ports
on `127.0.0.1` for the host JVM (sidecar 8001, SearXNG 8888, Ollama 11434; override with `SIDECAR_HOST_PORT`,
`SEARXNG_HOST_PORT`).

## What is on from the start

A **fresh** personal install (no `deploy/compose/.vegaduta-state` yet) starts with two things switched on, so the
Assistant can search the web and read a page the first time you ask:

- **Private web search.** A SearXNG container (profile `search`, no API key, no published port, 512 MB limit) behind
  the built-in `web_search` tool. A Brave, Tavily, Exa or Serper key is still a platform-admin setting that a personal
  owner has no page for; search works without one.
- **The MCP connector runner** (`mcp-stdio-runner`, profile `tools`). STDIO connector servers (`npx` or `uvx`
  packages) run in this container, away from core's secrets, database and Valkey, so the slim core image needs no
  Node or Python tooling. It is on its own network (`mcp-stdio-net`) shared only with core, publishes no port,
  mounts no volume (the projects folder is deliberately not visible to it), is limited to 768 MB and 8 servers at a
  time, and keeps its seccomp profile and dropped capabilities. It has internet access, because a server downloads
  its own package, and that also means it can reach what this computer exposes to Docker containers: the built-in
  `fetch_web_page` tool has an address guard in core, the MCP `fetch` server has none of its own. With
  `--native-core` the runner is not used (core runs STDIO servers itself under the OS sandbox).

On the first start where the runner is on, core registers three **starter connectors** for the Personal workspace,
each pinned to an exact version and recognised by the connector catalog as that exact spec, so none lands in the
review queue:

| Connector | Package | State |
|---|---|---|
| `fetch` | `mcp-server-fetch@2026.8.18` (uvx) | on |
| `time` | `mcp-server-time@2026.8.18` (uvx) | on |
| `sequential-thinking` | `@modelcontextprotocol/server-sequential-thinking@2026.8.31` (npx) | off: every enabled connector's tools are offered to every agent, which crowds a small model |

They are read-only and need no account or key. Like every MCP server, their output is treated as untrusted
third-party content, so a write later in the same turn asks for approval first. They appear on the **Tools** page as
ordinary connectors (`fetch` and `time` under "Custom"). **Deleting or editing a starter is final**: core records
that it offered each one and never puts it back or overwrites it; a connector you already named `fetch`, `time` or
`sequential-thinking` is never touched. Not registered on purpose: `filesystem` (the Assistant's `workspace_*` tools
already cover the projects folder, and mounting it would expose it to any later third-party server), `memory` (the
runner deletes each server's private home when it exits, so the graph would not last; use `remember_fact`) and
`brave-search` (its npm package is deprecated; the native `web_search` takes a Brave key).

The pinned PyPI sha256 and npm integrity values are recorded next to the versions in `McpStarterCatalog`, but `npx`
and `uvx` resolve a version, not a hash, so the runner does not refuse a download that differs from them.

**Opting out.** `--no-search` and `--no-tools` on the command line, or `VEGADUTA_SEARCH=0` / `VEGADUTA_TOOLS=0` in
`deploy/compose/.env` or the environment (`1` forces them on). A flag beats the environment, which beats the answer
remembered in the state file, and the choice is remembered (`search=` and `tools=` in `.vegaduta-state`). The Setup
app passes `--no-search` itself when you untick web search. An install that **already existed** keeps what it had:
with no remembered answer it is left alone until you pass `--search` or `--profile tools` once. Declining the
interactive web-search question is now remembered as an explicit no. On Windows `scripts\vegaduta.ps1` has no such
flags yet, so a Windows install does not get these defaults.

**What it costs.** Two more images to pull on the first start (SearXNG, and the runner with Node, uv and Python)
and about 1.3 GB more of memory limits (512 MB + 768 MB; limits, not measured use), which takes the personal budget
from about 2.4 GB to about 3.7 GB. The first start takes longer; `--wait-timeout` already defaults to 900 s. None of
this was run on a real Docker stack when it was written: the launcher plan, the compose files and the registration
logic are tested, the containers were not started.

## Running without Valkey

`./scripts/vegaduta personal --no-valkey` (or `--minimal`, or `VEGADUTA_VALKEY=0`) starts the stack without the
Valkey container. Valkey is a cache and counters store here, never a system of record, and every Redis-backed
counter in core already degrades to a bounded in-process table (`RedisFixedWindowCounter` →
`LocalRateLimitFallback`): rate limits, budget windows and spend counters keep working and keep failing closed
under pressure — a full fallback table refuses new keys rather than letting traffic through uncounted. The choice
is remembered (`valkey=` in `.vegaduta-state`), and `--valkey` puts the container back.

What you give up, on purpose:

- **Counters are per-process, per-boot.** Rate-limit and budget windows restart with core and are not shared —
  fine for the single user a personal install is for; a lite or full stack (several users, maybe several replicas)
  keeps Valkey always on and the flag is ignored there.
- **A Redis outage pattern still works.** If the container is up but the store inside it dies, core skips Redis
  calls for ~10 s after each failure instead of paying a connect timeout on every request — the same circuit
  breaker that makes `--no-valkey` cheap.
- **No cross-process state.** Anything a second process would need (shared session buffers between replicas,
  future queue backends) has nowhere to live. If a feature ever needs shared state, it is added behind the
  `valkey` compose profile, not silently required.

## One container: the omnibus image

For the smallest possible *operational* footprint there is also a single-container image, `omnibus`, built from
the release's own images: PostgreSQL with pgvector + Apache AGE, a Redis-protocol store, core and the console in
one box, supervised by a small entrypoint. It is the same personal Spring profile and the same jar — the
difference is packaging, not capability:

```sh
docker run -d --name vegaduta \
  -v vegaduta-data:/data \
  -p 3000:3000 -p 8080:8080 \
  ghcr.io/cd-lc/omnibus:0.1.3
```

Everything mutable lives under `/data` (the database cluster, uploaded files, Redis scratch space and the
generated secrets file, mode 600). `APP_ENCRYPTION_KEY` is generated on first boot and written there — never
rotate it, and back up `/data` like you would `.env`. The optional knobs are `PUBLIC_API_URL`, `PUBLIC_APP_URL`,
`POSTGRES_DB`, `PERSONAL_JAVA_OPTS_EXTRA` and `SPRING_PROFILES_ACTIVE` (`-e` on `docker run`).

Honest tradeoffs vs the compose tier: one process supervisor instead of per-service restarts (any child exiting
stops the container), one volume for everything, Postgres and Redis exposed only inside the container's loopback,
and no per-service resource limits — one memory ceiling for the whole box. It is the right shape for a laptop or
a demo VM; the compose tiers stay the shape for anything shared.

## Add-ons

An **add-on** is an optional capability of a personal install. The **Add-ons** page of the console (sidebar,
Configuration group, personal install only; [console guide](console/add-ons.md)) lists them with what each unlocks,
its disk size, the memory it needs, its licence and whether this computer suits it, and has one button to turn each on
or off. The catalog is `core/providers/src/main/resources/addons/catalog.json`; the Setup app carries an identical
copy (a test fails if they drift).

| Add-on | What it adds | On disk (about) | Switched by |
|---|---|---|---|
| Build terminal and projects folder | the Assistant's `local_shell` and project tools (`workspace` profile) | 1.35 GB | on by default |
| Web search | private SearXNG (`--search`) | 377 MB | on by default for a fresh install |
| Documents and transcription | the ML sidecar (`rag` profile): PDF and scan reading, embeddings, audio to text | 4.1 GB | profile `rag` |
| Assistant browser | the agent's own browser (see [The Assistant's browser](#the-assistants-browser)) | about 2 GB download (3.4 GB on disk), up to 2 GB of memory while it runs | `scripts/vegaduta personal --browser` (the Setup app offers it too) |
| Local model packs | Qwen 3.5 2B, 4B, 9B and gpt-oss 20B, sizes and memory from `deploy/models/catalog.txt` | 1.5 to 14 GB | core downloads them through Ollama |

Three more are listed as **planned** and cannot be turned on: a tools runner (it is on by default through the
launcher, see above, but is not yet a switch), local image generation and local spoken replies. Sizes are
approximate; the documents figure is on-disk size, while the Setup app's older "about 2 GB" is a download figure.

How switching works, because core runs in a container and cannot start containers:

- **Model packs** are downloaded by core itself through the Ollama it already uses, with live progress on the card.
  That Ollama must be reachable (otherwise `503 ollama_unreachable` and nothing is recorded). Turning a pack off
  keeps the downloaded files; remove them with `ollama rm TAG`.
- **Compose add-ons** are only *recorded* by core, with the exact launcher command. The card says "Waiting to be
  turned on" until the host has applied it and never "On" before. Today you apply a change by re-running the Setup
  app and ticking it in its Extras step, or by running the command the page shows. The command line can only add:
  `./scripts/vegaduta personal --profile ...` adds to the remembered profile list (it does not replace it), so
  switching an add-on **off** is done in the Setup app, which stops the container (it does not delete volumes,
  projects, documents, sign-ins or downloaded models). PowerShell has no `--search` option.
- **An add-on you already had** (documents installed with the checkbox, a model pulled with Ollama) is recognised
  automatically: core sees the sidecar enabled, the SearXNG URL set, the workspace runner URL set or the model in
  Ollama, and treats it as wanted.
- **Dependencies.** Turning one on turns on what it requires; turning one off while something needs it is refused
  (`409 required_by`) unless you confirm turning those off too. No shipped add-on requires another today.
- **Who may change them.** Anyone signed in can read. Changing needs `platform-admin`, or `tenant-admin` on a
  personal install (the owner) or when `app.addons.tenant-admin-may-change=true`. Every change is audited
  (`addon.enable`, `addon.disable`), and the state is install-wide (`control_plane.addon_state`, V656).

**The Setup link is not wired yet.** Core already serves `GET /api/addons/host/wanted` and `POST
/api/addons/host/report` for the Setup app, behind a shared secret (`app.addons.host-token`, at least 32 characters;
without it these answer `503 host_bridge_disabled`), but nothing generates that token in `.env` yet and the Setup
app does not poll, so the console correctly says Setup is not linked and shows the command to run.

## Local sign-in

With `app.auth.mode=local` core is its own identity provider for one owner. Nothing in this section applies if you
point a personal install back at Keycloak (`APP_AUTH_MODE=keycloak`).

You can unlock the console in four ways, alone or together: a **passkey** (Touch ID, Windows Hello, a phone or a
security key), a **PIN** on a browser you have enrolled, a **password**, and one-time **recovery codes** for the day
the other ways are gone. The sign-in screen shows a passkey button first, a PIN pad when this browser holds a PIN,
and asks for no e-mail address unless you choose "Use a password instead".

1. **First start.** Core creates one owner and a default tenant named `Personal` through the normal sign-up path,
   with a random password nobody sees, and refuses to sign that account in until you set a real way in.
2. **Setup link.** Core prints a one-time link (`<console>/login?setup=...`) to its log and saves it, owner-readable
   only, in `first-run-setup.txt` in the auth data directory. The launcher prints it at the end of `personal`. Open
   it and choose **how you unlock**: a passkey (recommended), a PIN, or a password (at least 12 characters, at most
   72 bytes, not your e-mail address). A passkey or a PIN is enough on its own: the account then has no password at
   all, and the screen shows 8 recovery codes once, which you must tick "I saved these" to leave. The link works
   once, is used up only after your choice has been stored, and expires after 24 hours; restarting while setup is
   still pending prints a fresh link and retires the old one. The sign-in e-mail is `owner@vegaduta.local` unless you
   set `app.auth.local.owner-email`; the launcher prints it, and the sign-in form remembers the address that last
   signed in on that browser. A lost or expired link: `scripts/vegaduta restart` restarts core and prints a fresh
   one (the launcher reads the link from the file, so it never prints a link that was already used).
3. **Passkeys.** A passkey is a WebAuthn credential that stays in your device or password manager; core keeps only
   its public key. Core asks for user verification (the fingerprint, face or device PIN), so a stolen laptop that is
   unlocked is not enough on its own to sign in as you. Up to 20 per account.
   They work only where the browser treats the page as a secure context:
   - `http://localhost:<port>` counts as secure, so the default install (console on `http://localhost:3000`) works
     with no certificate.
   - Anything else needs **HTTPS and a real host name** (for example `https://assistant.example.com` behind Caddy
     or a tunnel). **An IP address can never be used**, with or without HTTPS: WebAuthn does not allow an IP
     address as the relying-party ID, so a console opened as `http://192.168.1.20:3000` or `https://10.0.0.5` offers
     only PIN and password, and the screen says why (core answers `400 passkey_origin_unsupported`).
   - **A passkey belongs to the host name it was made on.** Moving the console to another host name, or between
     `https://your-domain` and `http://localhost`, makes the old passkeys unusable. Sign in another way and add the
     passkey again; remove the dead ones in Account security. Core stores the relying-party ID with each passkey
     and refuses a replayed challenge, a wrong origin, a tampered signature and a counter that went backwards.
   - To allow only certain addresses set `app.auth.local.passkey.origins`
     ([configuration](configuration.md#local-sign-in-appauthmodelocal)).
   - Challenges live 2 minutes, are single-use and are kept in core's memory, so a core restart cancels a
     registration or sign-in that was in progress; just start it again.
4. **PIN.** A PIN is 6 to 12 digits. Core refuses an obvious one: a single repeated digit, a straight run (123456,
   987654), a short block repeated to fill it (121212, 123123), doubled digits (112233) and a short list of common
   PINs. A PIN is **tied to the browser it was set up in**. Enrolling a browser stores a random 32-byte device
   secret in an HttpOnly cookie there, and core accepts the PIN only together with that secret, so someone who learns
   your digits still cannot use them from another machine. **Ten wrong PINs in a row revoke the device**; the
   browser then falls back to the other methods, and a correct PIN resets the count. Clearing the browser's site
   data removes the device binding: sign in another way and add a PIN again in Account security. Up to 20 devices.
   Removing a PIN device is a revoke: the row is kept and the device can never sign in again.
5. **Recovery codes.** Eight codes of 16 letters and digits, shown as `XXXX-XXXX-XXXX-XXXX` (no 0, O, 1 or I;
   capitals, small letters and dashes are all accepted when you type one). Each works once. Core stores only a hash
   of each code, so it cannot show them again; **making a new set replaces the whole old set**. "I lost my device"
   on the sign-in screen takes a code, signs you in and sends you to Account security to add a new passkey or PIN.
   Because recovery is checked against one shared throttle, a few wrong codes from anyone briefly lock recovery for
   everyone (growing up to 15 minutes); passkey, PIN and password sign-in are not affected.
6. **Account security.** Open `<console>/login?security=1` (also linked from the change-password page) to add or
   remove passkeys, add a PIN for this browser, remove PIN devices and make new recovery codes. These changes need a
   **sign-in from the last 10 minutes**, with any method; otherwise the page shows "Confirm it's you" and sends you
   to sign in again (`403 reauth_required` from the API; the sign-in time survives token refreshes, so only signing
   in again renews it). You cannot remove your last way in: core answers `409 last_credential` rather than lock you
   out. A PIN-only account has no password, so it has no change-password step.
7. **Sessions.** Access tokens last 15 minutes. The refresh token is opaque, rotates on every use, and is kept in an
   HttpOnly, SameSite=Strict cookie by the console's same-origin proxy; page scripts never see it. A login ends after
   90 days at the latest, and changing your password signs out every other login. The token records how you signed in
   (`amr`: `pwd` password, `hwk` passkey, `pin`, `rec` recovery code). Five wrong passwords on an
   account (or 20 from one address) lock sign-in with a growing delay (30 seconds up to 15 minutes), and an unknown
   account costs the same work as a wrong password; PIN, passkey and recovery attempts go through the same throttle.
   An address that already signed this account in (within 30
   days) is still checked while the lock is on, once every 2 seconds, and a wrong guess from it neither counts nor
   extends the lock, so a stranger cannot keep you out of your own console. Behind the bundled web container every
   browser shares the container's address unless a proxy such as Caddy passes `X-Forwarded-For`.
8. **More people.** Invitations and role editing work. There is no mail server, so **Administration > Access >
   Invite people** shows a one-time setup link (valid 24 hours) to copy and send by chat or message; **New invite
   link** on the person's row makes a fresh one until they have chosen how to unlock. The link only opens where the
   console is reachable: with the default `BIND_ADDR=127.0.0.1` that is this computer alone, so set `BIND_ADDR` and
   `PUBLIC_APP_URL` / `PUBLIC_API_URL` first (see above) or put it behind a tunnel. Everyone then signs in to the
   same console and tenant, and each person sets up their own passkey, PIN and recovery codes. Public sign-up
   (`POST /api/signup`) is closed in local mode.
9. **Platform admin.** `scripts/vegaduta make-admin` grants a Keycloak role and refuses on a personal install. To let
   the owner open the platform-admin pages, put `APP_AUTH_LOCAL_OWNER_EXTRA_ROLES=platform-admin` in
   `deploy/compose/core.env` and restart core.
10. **Keys.** Core signs tokens with a key it generates into `signing-keys.json` in the auth data directory (rotated
    after 90 days; the retired key keeps verifying for a day). In the container stack that directory is
    `/data/storage/.auth` on the storage volume. A corrupt key file stops core from starting instead of silently
    minting a new key. Run one core process per data directory (passkey challenges are also per process).
11. **Locked out.** If you still have a recovery code, use "I lost my device". If you have none, there is no "forgot
    password" (no mail server). From a shell with database access run
    `UPDATE control_plane.local_account SET setup_pending = true WHERE email_lower = '<owner e-mail>'`, then restart
    core: sign-in is refused until the fresh setup link it prints is used, and completing it signs every login out.
    Deleting the account row does not work; the tenant would still exist and the next first-run sign-up is refused as
    a duplicate.

Not built: one-time codes from an authenticator app (TOTP), conditional-UI passkey autofill (the passkey button is
pressed, never prompted on page load), a passkey sign-in for Lite and Full (Keycloak has its own), and account
self-service beyond the pieces above. Access tokens cannot be revoked one by one; logout, disabling the user and a
password change revoke the refresh tokens, so they stop working within 15 minutes. The core side is tested with a
software authenticator and, for the stores, against a real Postgres; **no real passkey (Touch ID, Windows Hello, a
security key) and no real browser has been used with it yet**, so expect rough edges and report them.

## Picking a local model

Agents need a model. Personal mode has three ways to get a free local one, all through Ollama:

- **At install.** If Ollama is already running on the machine, `personal` uses it (on a first run, and on later runs
  until you say `--cloud`), picks a model that fits your RAM from the shared catalog and registers it as the free
  platform pool. Choose another with `--local-model TAG`, or opt out with `--cloud`. On a terminal the launcher
  offers to `ollama pull` a model (and the `nomic-embed-text` embedding model) that is missing; otherwise it prints
  the exact command, and the final summary says **NOT PULLED YET** instead of announcing a model that is not there.
  Nothing is pulled or started without your say-so. `--local-ai=host` states it explicitly;
  `--local-ai` (container) starts an Ollama container instead, which on macOS is CPU-only.
- **In the console.** **Providers → Set up a local model** (`/providers/local-model`) detects your hardware (RAM,
  cores, an NVIDIA card or Apple Silicon; with the container core the launcher passes the host's RAM and
  accelerator, because the container alone would report its own 1.5 GB limit) and Ollama (by default the one on this
  computer, `host.docker.internal:11434`), recommends a model, shows the real download size and a RAM
  warning, pulls it with progress, checks that it can call tools, creates the provider and a first agent. A failed
  check does not block you; it is shown as unverified.
- **Any OpenAI-compatible server.** The **Local / OpenAI-compatible server** provider takes the address of vLLM,
  llama.cpp (`llama-server --jinja`), LM Studio, Jan or LocalAI, lists the models it serves, meters calls at exactly
  $0 and has a **Check tool calling** button per model.

Core refuses to call private and loopback addresses on a tenant's say-so. A server on your own machine therefore
needs the operator to list it once in `APP_SECURITY_OUTBOUND_ALLOWED_PRIVATE_HOSTS`, for example
`host.docker.internal:8000` when core runs in a container (put it in `deploy/compose/.env`) or `localhost:8000`
with `--native-core` (put it in `deploy/compose/core.env`; when `--local-ai` is also used the launcher sets the
Ollama entry itself and that value replaces this variable for the host JVM). The launcher adds the entry for the
Ollama it sets up; the refusal message names the entry for any other server. Small models are weaker at tools: the catalog marks each model's tool-calling
grade, and [Free local models](local-models.md) explains what core does about models that write tool calls as text.

Embeddings (the vectors behind the knowledge base and memory) use `app.embeddings.provider=auto` in this profile:
a cloud key if you set one, else the Ollama it already uses (pull the model once: `ollama pull nomic-embed-text`;
the launcher checks and offers), else the sidecar. "The Ollama it already uses" includes one you registered in the
console's local-model wizard (Settings, Providers, Local model) even when the local-AI overlay was not used: core
looks that up per call and picks it up within about 30 seconds, no restart. The sidecar is off (`app.sidecar.enabled=false`) unless you add
`--profile rag`, which starts the container and switches it on. Without it, PDF and scanned-image extraction answer
`503` and say which setting to change; see [Embeddings and knowledge](configuration.md#embeddings-and-knowledge).
Override either value in `deploy/compose/core.env` (`APP_EMBEDDINGS_PROVIDER`; `APP_SIDECAR_ENABLED` goes in
`deploy/compose/.env`, which Compose reads for the core container).

When something does not work, **Troubleshoot** (`/troubleshoot`, in the Configuration group) is the owner's
"something is wrong" panel for a personal install: it checks what the browser can see (core reachable, the local
model server answering, the recommended model pulled, tool calling working), offers the fix for each — including
one-click model pulls from the same catalog the launcher reads — and links to Academy guides when the install has
an Academy URL (`NEXT_PUBLIC_ACADEMY_URL`, `academy.vegaduta.ai` by default on personal). What the page cannot
see (Docker, host ports) it delegates to `scripts/vegaduta doctor --personal`.

## Skills

Skills are folders with a `SKILL.md` (and optional scripts and files) that an agent loads on demand. The skill
library at **Skills → Skill library** (`/skills/library`) holds them:

- **Import** a zip or a bare `SKILL.md`. Import is strict: path tricks, oversized archives and ambiguous names are
  refused, and scripts are stored as text and never run at import time. A skill's script can only run inside the
  tool sandbox when an agent asks for it.
- **Review states.** A skill is pending, active, rejected or archived, and imported, learned or authored. Only active
  skills reach an agent. Approve, reject, archive, restore, edit (an edit of an active skill is a new version, 20
  are kept), enable per agent, export, delete.
- **Usage.** The library counts how often a skill was loaded and, when learning is on, whether the turn went well,
  and flags a skill stale after 90 days unused or when it keeps failing.
- **Learning loop** (`app.skills.learning.enabled`, on in `personal`, off elsewhere). After a completed turn that took
  at least three tool calls, mostly succeeded and used no skill, the agent's own model may draft a skill. The
  transcript and the draft are scrubbed for secrets first (a draft that needed more than three redactions is
  dropped), and the draft waits as **pending, learned** with its rationale. Nothing a learned skill says reaches an
  agent until you approve it, so read the body before you do. Limits: one drafting attempt per agent per 30
  minutes, three per day, ten pending drafts per workspace. Only the interactive chat path is observed, not scheduled
  tasks or jobs.

`allowed-tools` and other optional frontmatter keys are kept on import and export but are informational: they never
grant a tool.

## Channels

Personal mode has no public address, so channels that need one are not available out of the box. What works from a
laptop:

- **Telegram** by long polling (no webhook URL needed; on in the `personal` profile). Give it a bot token in
  **Channels**.
- **WhatsApp QR, Signal, Matrix and iMessage** (below) reach out to a bridge you run. WhatsApp QR is the
  personal-WhatsApp option: scan once and your own number answers.
- **Email** if you set `SMTP_*` in `.env`.
- Business WhatsApp (Meta Cloud API or Twilio), Slack, Teams, SMS and voice all need a URL the provider can reach; see
  [Hosting behind a Cloudflare Tunnel](operations/tunnel-hosting.md) if you want that.

Every inbound channel is **default deny**. A stranger gets one pairing code (or is ignored, if you choose that) and
nothing reaches the agent until you approve them in the console. Replies go through the approval gate as
`channel.<name>.send`. There is also a per-sender cap on inbound turns (`app.omnichannel.rate-limit.*`). All four
channels below need `APP_SECURITY_OUTBOUND_ALLOWED_PRIVATE_HOSTS` to list the bridge when it sits on a private
address.

### Signal

Signal needs a [signal-cli REST API](https://github.com/bbernhard/signal-cli-rest-api) that you run. The repository
ships a Compose profile for it, written against Lite's network; personal mode uses Compose's default network the
same way, but that combination has not been run:

```sh
docker compose -f deploy/compose/personal.yml -f deploy/compose/signal.yml \
  --env-file deploy/compose/.env --profile signal up -d
```

Link your phone (a QR code) or register a number, following the steps at the top of `deploy/compose/signal.yml`.
Add `signal-cli:8080` to `APP_SECURITY_OUTBOUND_ALLOWED_PRIVATE_HOSTS`, restart core, then **Channels → Signal**:
enter `http://signal-cli:8080` and the number, and choose the agent. signal-cli has no authentication of its own,
which is why the file publishes no port. Groups are ignored unless you set `MENTION` or `ALL`; attachments are
described, not downloaded. Details: [Signal channel](channels/signal.md).

### WhatsApp QR

WhatsApp QR links **your own number** as a companion device - the WhatsApp-Web-style QR the Connect
tab has been missing on a laptop. No Meta Business account and no public URL, because the bridge
dials out to WhatsApp itself. In `.env` set `WHATSAPP_QR_BRIDGE_SECRET` to an `openssl rand -hex 32` value
(the overlay hands it to core, and the bridge URL defaults to `http://whatsapp-qr:8080`; set
`APP_WHATSAPPQR_BRIDGE_URL` / `APP_WHATSAPPQR_BRIDGE_SECRET` only to override them) and add `whatsapp-qr:8080` to
`APP_SECURITY_OUTBOUND_ALLOWED_PRIVATE_HOSTS`. Without a bridge configured the channel reads "WhatsApp bridge
not configured" and core does not poll. Then:

```sh
docker compose -f deploy/compose/personal.yml -f deploy/compose/whatsapp-qr.yml \
  --env-file deploy/compose/.env --profile whatsapp-qr up -d --build
```

In the console open
**Channels → WhatsApp QR**, press **Link a device** and scan with WhatsApp > Settings > Linked
devices. Unlinking in the console also removes the device on WhatsApp's side. Two honest caveats:
linked-device sessions are the unofficial surface (Meta can flag an account that uses a
third-party client - the Business Platform is the official path for anything that matters), and
groups are not supported in this version. Details:
[WhatsApp QR channel](channels/whatsapp-qr.md).

### Matrix

Matrix works against any homeserver you can reach. Create a separate bot user, copy its access token, and connect in
**Channels → Matrix**. Receiving is off until you start the sync loop:
put `APP_MATRIX_SYNC_ENABLED=true` in `deploy/compose/core.env` (the `personal` profile does not set it). For a
homeserver on your own network add its `host:port` to the private-host list. Sender policy is `PAIRING`
(default) or `ALLOWLIST`; the bot joins rooms on invitation only when you allow it and the inviter is on the list.
**There is no end-to-end encryption**: the bot answers once in an encrypted room that it cannot read, then ignores
it. Details: [Matrix channel](matrix-channel.md).

### iMessage

Apple has no API for iMessage. The only workable open path is a **Mac that stays powered on, awake and signed in to
Messages**, running the free [BlueBubbles](https://bluebubbles.app) server. Core talks to that server's REST API;
if the Mac sleeps, loses its network or signs out, iMessage stops until it is back, and Apple can change this at any
time. Set a server password in BlueBubbles, list the Mac's `host:port` in the private-host list, then **Channels →
iMessage**: connect, add the shown webhook URL in BlueBubbles (the Mac must be able to reach core), or choose
polling and set `app.imessage.polling.enabled=true`. Allow-list your own number or Apple ID first. This channel has
only been tested against a fake BlueBubbles server and has never run against a real Mac; treat it as experimental.
Details: [iMessage channel](imessage-channel.md).

## Connecting Google

Calendar, Drive, Sheets, Gmail (send) and YouTube need a Google OAuth client; Google offers no way to connect
without one. Personal mode uses a Google **Desktop app** client (`app.google.oauth.mode=loopback`): the redirect
goes to this computer's own address on whatever port the console runs on, so there is no redirect URI to
register, and the code exchange uses PKCE. Only the `personal` profile does this; Lite, Full and Kubernetes keep
the Web-application client with a fixed https redirect (see [Configuration](configuration.md)).

Release tarballs ship the project's client in `deploy/compose/google-oauth.env` when the release build had it, and
then **Connect → Google** is one click. Otherwise (a git checkout, or a release built without it) the console
says so, and you bring your own:

1. Google Cloud Console → APIs & Services → Credentials → Create OAuth client → *Desktop app*.
2. Put `GOOGLE_OAUTH_CLIENT_ID` and `GOOGLE_OAUTH_CLIENT_SECRET` in `deploy/compose/google-oauth.env` (or `core.env`)
   and restart. While your own client is unverified, Google limits it to the test users you list on its consent screen.

The console must be opened on this computer (`http://localhost:3000`): a Desktop client only redirects to
loopback. If you serve a personal install on a domain or LAN address, set `GOOGLE_OAUTH_MODE=web`, create a
Web-application client and set `GOOGLE_OAUTH_REDIRECT_URI` to the https callback you registered on it.

## Sandbox levels

Core can start STDIO MCP servers for you (an `npx` or `uvx` package, a script). Those are programs from the
internet running as you, so `app.mcp.stdio.sandbox` wraps them:

| Level | What it does | Where it works |
|---|---|---|
| `none` | no wrapping (the default outside personal mode) | everywhere |
| `os` | the child runs under the operating system's sandbox: read-only filesystem except its own data directory, your credential folders (`~/.ssh`, `~/.aws`, `~/.gnupg`, `~/.kube`, `~/.docker`, `~/.vegaduta`, keychains, browser profiles) hidden, network off unless allowed | macOS (`sandbox-exec`) and Linux with bubblewrap installed. Without bubblewrap a degraded `unshare` fallback applies namespaces only and does **not** confine the filesystem; core logs a warning on every start. Windows is unsupported |
| `container` | each server runs in `docker` or `podman` with all capabilities dropped, a read-only root, memory and process limits, no network by default, and one mounted data directory | any host where core can run `docker` or `podman` |

Three things to know:

- **The container stack sets `none`.** The `os` level cannot work inside the core container (no bubblewrap, and the
  container drops the capabilities `unshare` needs), and a required but unavailable sandbox stops core at start-up.
  The container is the boundary there, and the personal profile set has no `mcp` profile, so core starts no tenant
  STDIO servers in it anyway. For the `os` level use `--native-core` on macOS or on Linux with bubblewrap; set
  `APP_MCP_STDIO_SANDBOX=container` only if core can reach a container runtime.
- **Network is off by default**, so `npx -y <package>` cannot download its package the first time. Allow the server
  by name, or `*`, in `app.mcp.stdio.sandbox-network-allow`, or warm the cache first.
- **It covers servers you register in the console, not static ones.** STDIO servers configured in
  `application-mcp.yml` are operator-configured and start unsandboxed.

`GET /api/system/stdio-sandbox` shows what is enforced, and every key is in
[Configuration](configuration.md#stdio-mcp-sandbox). The design and its limits are in
[MCP STDIO sandboxing](architecture/MCP-STDIO-SANDBOXING-DESIGN.md).

## The Assistant's terminal

On a native install (`--native-core`) the Assistant can use your computer's terminal, in the same OS sandbox as the
STDIO servers above. Two tools, offered only while that sandbox is on, available and confining the filesystem:

| Tool | What it does | Asks you? |
|---|---|---|
| `local_shell_read` | inspection only: `ls`, `cat`, `head`, `tail`, `grep`, `find`, `wc`, `sort`, `diff`, `jq`, `git status/log/diff/show`, `date`, `df`, `ps` and pipes between them; no redirects, `$( )`, `;`, `&&` or `&` | no |
| `local_shell` | any command (builds, scripts, file processing) | no on this profile - see `approval-required` below; the card would show the exact command |

Commands start in a workspace folder (default `~/vegaduta-workspace`, `app.assistant.local-shell.workspace`), the only
place they can write; the rest of the disk is readable by absolute path except your credential folders, and there is
no network unless you list `local-shell` (or `*`) in `app.mcp.stdio.sandbox-network-allow`. Other secrets on disk (project
`.env` files, `~/Documents`) stay readable, as for STDIO servers: add them to `app.mcp.stdio.sandbox-extra-deny-read`.
`sudo`, `su`, power
control and wiping a root are refused outright. A command that runs past its time limit (60 s, up to 300 s) is
stopped, and output is cut at 20,000 characters.

It is on by default in the personal profile. In the container stack (where the sandbox is `none`) it works through
the workspace runner instead: see [Build projects with the Assistant](#build-projects-with-the-assistant).
Switch it off with `APP_ASSISTANT_LOCAL_SHELL_ENABLED=false`. On a shared server it is never offered unless
`app.assistant.local-shell.acknowledge-shared-host=true`, because any tenant's agent would then run commands on the
host.

**Approval.** Everywhere else each `local_shell` call parks on a card showing the exact command, even for a fully
autonomous agent - `local_shell` is on the tool guard's always-confirm list. The `personal` profile sets
`app.assistant.local-shell.approval-required=false` (`APP_ASSISTANT_LOCAL_SHELL_APPROVAL_REQUIRED`), which lifts
that pin: commands run inside the sandbox without asking, still logged to the tool-call audit. Two guardrails are
untouched: a `SUPERVISED`-tier agent still asks before every tool call, and commands that leave the workspace (or a
turn carrying content an outsider authored, under taint enforcement) can still be gated. Set it `=true` to restore
per-command approval. Every key is in [Configuration](configuration.md#the-assistants-terminal).

**What you are accepting** when the approval pin is lifted. A command still runs only inside the sandbox or the
workspace-runner container, and that container holds no platform secrets and mounts nothing but the projects
folder. But it does sit on the private Docker network with normal outbound access, so a command can reach the
internet — which is also how `npm install` and `mvn package` work — and can touch the other containers on
unauthenticated ports. The mitigation for "a web page told my assistant to run this" is taint enforcement
(`app.agents.taint.mode=enforce`): content that came from outside marks the turn, and actions that follow it can
still be gated. That makes the taint classifications the load-bearing control in this profile — if you build a
tool that returns attacker-influenced text, keep its `untrusted` marking accurate, and consider
`APP_ASSISTANT_LOCAL_SHELL_APPROVAL_REQUIRED=true` on a machine where a wrong `rm -rf` would hurt more than an
approval click costs.

## The Assistant's desktop tools

`local_shell` is sandboxed; `local_desktop_*` is not - it is computer-use on the real screen, so it
lives in a sidecar you run on the laptop yourself, `services/desktop-agent`:

```sh
export DESKTOP_AGENT_SECRET=$(openssl rand -hex 32)
node services/desktop-agent/server.js          # listens on 127.0.0.1:8787 only
```

Grant the running app **Accessibility** and **Screen Recording** (System Settings > Privacy &
Security); the API names the missing grant if you forget. Then point core at it in `.env`:

```
APP_ASSISTANT_DESKTOP_AGENT_URL=http://host.docker.internal:8787
APP_ASSISTANT_DESKTOP_AGENT_SECRET=<same value>
```

(`127.0.0.1` instead of `host.docker.internal` for `--native-core`.) That gives the Assistant:
`local_desktop_screen` (screenshot saved under `~/vegaduta-workspace/screenshots`),
`local_desktop_elements` (the frontmost app's buttons, fields and menus with positions - the
reliable way to act without guessing pixels), `local_desktop_press` (press a named element),
`local_desktop_click`, `local_desktop_type`, `local_desktop_key`, `local_desktop_open`,
`local_desktop_activate`, `local_desktop_notify` (system notification) and
`local_desktop_clipboard` (read or set).

These actions can touch anything you can. Actuating tools are parked for approval by default;
on this profile `APP_ASSISTANT_DESKTOP_APPROVAL_REQUIRED=false` lifts that pin (the sidecar's
existence is the opt-in). Set it `=true` to approve each click and keystroke. Screenshots and
the element list come from whatever your apps show, so they taint the turn under taint
enforcement - a subsequent send action can still ask.

## Scheduled tasks

`schedule_task` is **on** in this profile (`SCHEDULED_TASKS_ENABLED`): "every weekday at 8am
check my mail and message me the summary" becomes a recurring unattended run - the heartbeat
loop. Each firing is a normal agent job (your budget, the tenant caps, the kill switch); a
tool call that still needs approval parks in the approvals inbox instead of running, so the
agent never self-approves. Results deliver in-app, by email, Telegram, Slack or over the
linked **WhatsApp QR** channel (`delivery: whatsapp_qr`) - the notification that reaches you
even when the console is closed. Every setting is under
`app.scheduled-tasks.*` in [Configuration](configuration.md).

## Build projects with the Assistant

`--profile workspace` gives the Assistant a projects folder and a build container, so a request like "build a Spring
Boot project with a REST endpoint and tests" runs for as long as it takes:

```sh
./scripts/vegaduta personal --profile workspace
```

- **Projects folder.** `~/VegaDuta-Projects`. It is mounted into core and into the `workspace-runner` container, and
  nothing else of your computer is. To work in another folder on this computer, run
  `scripts/vegaduta projects ~/my-folder`: it points `VEGADUTA_PROJECTS_DIR` there and recreates core and the runner
  (a folder that is the whole disk, your home folder or a system folder is refused). `scripts/vegaduta projects`
  alone prints the current one. The **Attach a folder** popover shows the same path and command.
- **Preview.** Asked to run what it built, the Assistant starts a dev server in the runner (`local_shell` with
  `background: true`; the server gets `PORT` and must listen on `0.0.0.0`). The runner publishes ports 13210-13219 on
  this computer's loopback only, and the **Preview** button on `/assistant` opens the running page beside the
  conversation (reload, open in a new tab, stop). Servers die when the runner restarts; at most ten at a time.
- **Attach a folder.** On `/assistant`, **Attach a folder** picks a project folder or creates one. The Assistant is told
  to work there with every message, and the tools refuse any path outside the projects folder (symlinks included).
- **Attach files, take a photo, call.** The **+** in the box uploads files (or drop them on the page, or paste a
  screenshot), opens the camera, or starts a voice call that lands in the same conversation. Each file is saved in
  the projects folder (in `Chat uploads`, or the attached project folder), up to 25 MB; a PDF also gets its text saved
  next to it as `<name>.pdf.txt`. The Assistant searches inside with `workspace_search` and reads with
  `workspace_read`. Scans and spreadsheets are saved but not searchable as text. An image is also shown to a model
  that can see. The camera needs a secure page (https or localhost) and is on only while its window is open.
- **Files** (`workspace_list`, `workspace_read`, `workspace_search`, `workspace_write`, `workspace_edit`) do not ask: they can only change
  the projects folder.
- **Commands** (`local_shell`, `local_shell_read`) run in the `workspace-runner` container: JDK 21, Maven, Node 22 and
  npm, git and python3, with network for dependencies, no platform secret and no Docker socket. On this profile
  `local_shell` runs without a per-command approval (`APP_ASSISTANT_LOCAL_SHELL_APPROVAL_REQUIRED=false`; set it to
  `true` to ask each time). After any approval it does get, the Assistant carries on by itself while a folder
  is attached, so a long build needs no "continue" messages. A command may run up to 30
  minutes (`WORKSPACE_RUNNER_MAX_TIMEOUT_SECONDS`); Maven, Gradle and npm caches live in the runner's own volume.
- **Model.** A turn may use up to 150 tool calls in the personal profile (`GUARDRAILS_MAX_TOOL_CALLS_PER_TURN`), and core waits up to 240 s for a local
  model's first token (`LLM_HTTP_STREAM_IDLE_TIMEOUT`; 90 s elsewhere). Building a real project needs a capable model:
  add a provider key (Anthropic, OpenAI, Gemini) for this. The 4B local default can make small edits but stalls on a
  whole project.

Turn it off by starting without the profile; the folder and its files stay where they are.

## The Assistant's browser

The agent can drive its own headless Chromium (`browser_*` tools: navigate, read the page text, take a screenshot, click, type), and you can sign in to a site once in a live view of that same page; the login is saved as an encrypted profile and the agent keeps working logged in. In the personal profile the flags are already on (`BROWSER_V2_ENABLED`, `BROWSER_PROFILES_ENABLED`, `BROWSER_TAKEOVER_ENABLED`); TOTP stays off. What the default install lacks is a Chromium: the slim `core` image has none, so the browser tools are not offered and `GET /api/browser/automation/capabilities` answers `browserAvailable=false` with a reason, and the Assistant's Browser button says how to turn it on.

Turn it on (container stack):

```sh
scripts/vegaduta personal --browser        # PowerShell: .\scripts\vegaduta.ps1 personal -Profile browser
scripts/vegaduta personal --no-browser     # turn it off again (the choice is remembered)
```

This adds one container, `browser-runner` (`services/browser-runner`, a Playwright 1.49.0 server with Chromium; about 2 GB to download, 3.4 GB on disk, a 2 GB memory limit you can change with `BROWSER_RUNNER_MEM_LIMIT`). Core stays on the slim image and attaches to it (`APP_BROWSERUSE_REMOTE_URL=ws://browser-runner:3000/`). Until the image is published for your release, add `--build`. A host JVM (`--native-core`) does not use it: install Chromium on the host and set `APP_BROWSERUSE_CHROMIUM_EXECUTABLE_PATH` instead. The older route still works and needs none of this: `CORE_IMAGE_VARIANT=-full` (the 6 GB `core-full` image with Chromium inside core; raise `CORE_MEM_LIMIT=2560m`).

To let the agent sign in to a site, name it in `deploy/compose/.env`: `BROWSER_ALLOWED_DOMAINS=github.com,*.github.com`.

What protects you, unchanged: the domain policy (an empty allow-list means a takeover needs the site in the profile's allowed domains; `BROWSER_BLOCKED_DOMAINS` always wins), the confirmation gate on buy/pay/delete clicks, and the SSRF guard (core refuses private and loopback addresses before the browser is told to go there, and again on every request the page makes). Saved logins are encrypted with `APP_ENCRYPTION_KEY`. The runner itself publishes no port, has no capabilities and a read-only root, and sits on a private network shared only with core, so a page the agent opens cannot reach Postgres, Valkey or the workspace runner. A Playwright server has no password of its own: that network is the boundary, so do not publish its port.

What was run for real (2026-10-19, slim core plus the runner, Docker Desktop on a Mac): the Assistant opened a page through the runner (`browser_navigate` returned the real title) and the Browser button showed its ready state instead of "unavailable". An opt-in test, `BrowserRemoteRealTest`, drives the session manager against the runner: it checks the add-on is reachable, opens a public page, reads its text, takes a JPEG screenshot, clicks a link and sees the page change, and confirms a private address is still refused. Run it with `BROWSER_REMOTE_URL=ws://127.0.0.1:<port>/ mvn -pl agents-browser test -Dtest=BrowserRemoteRealTest` (the test's javadoc shows how to reach the runner from the host). Not run for real: the sign-in takeover live view (it is covered by mock tests), saved-login reuse, and `browser_click_element` and the other indexed tools. With the 4B local model, multi-step browsing is flaky: Ollama sometimes rejects the model's tool call as malformed XML (HTTP 500), which is a model-server problem, not the browser; a hosted model or a retry gets past it.

## Taint tracking

In personal mode `app.agents.taint.mode` is `enforce`. When a turn reads untrusted content (a fetched web page, a
retrieved document, an unclassified MCP tool's output), that output is passed to the model as delimited data, and for
the rest of that turn any tool that writes data, uses stored credentials or sends something out waits for your
approval. A fetched page that says "email my contacts" therefore parks the send as an approval instead of running
it. See [Security model](security-model.md) for what this does and does not stop.

## Billing is off

`app.edition.billing-enabled=false` removes plans, prices, payments, receipts and upgrade prompts, and every limit
resolves as unlimited. Usage and cost are still counted, so the Cost and ROI views stay truthful. The console hides
billing screens unless `WEB_BILLING_ENABLED=1`.

Beyond billing, a local-auth install drops the surfaces a single user never reaches — from the nav rail, the
command palette and the /learn screen index alike (`web/app/lib/personalInstall.ts` is the one list): Shared with
me, the public agent Directory, Studio, Cost & ROI, and under Administration the agent trust, commerce and payments
consoles, Branding, MCP access, API keys, SSO, Leads and Plans. The pages still answer on a deep link; they just are not
advertised.

## Configuration

Personal mode needs nothing in `.env` beyond what the CLI generates. The keys it adds, and the five that the
profile flips, are in [Configuration](configuration.md#personal-agent-settings); the Compose-level knobs
(`PERSONAL_JAVA_OPTS_EXTRA`, `CORE_MEM_LIMIT`, `VEGADUTA_CORE_HEAP`, ...) are commented in
`deploy/compose/.env.example`. Anything else core understands goes in `deploy/compose/core.env`, which both the
container core and `--native-core` read.

Moving to Lite or Full later keeps the Postgres volume only if the Compose project name is the same, and local
accounts do not migrate to Keycloak.

## Honest limitations

- **Valkey stays.** About 60 classes use Redis directly (session memory, turn cancel, budgets, rate limits, the
  effect journal), so personal mode runs a 64 MB Valkey instead of replacing it. Expect about 1 GB for Postgres,
  Valkey and core together, plus web, plus the Docker VM. Measured on a 16 GB laptop with other stacks running: core
  container about 765 MiB, Postgres about 135 MiB, Valkey about 12 MiB, start in about 21 seconds on an empty
  database. The web container, the AppCDS option and a from-source `--build` were not measured.
- **Single owner, single process.** One core per data directory; the Matrix, Signal and Telegram receivers must each
  run in exactly one process.
- **No workflow engine beyond the in-process one.** No Temporal and no Kafka, so durable workflow waits and
  event-driven features that need them are not available.
- **Taint is per turn.** It does not carry across a conversation, and a message from a non-owner on a channel is
  not yet marked untrusted before the turn starts. It gates actions, not reads: a tainted turn can still fetch a URL
  (a query string can carry context out) unless you set `app.agents.taint.http-get-allow-hosts`, and search tools
  that take a query cannot be restricted by host. Tools that are not in the classification table are treated as
  side-effecting, which is why approvals can appear for tools you consider harmless.
- **Sandbox gaps.** See the table above: the Linux fallback without bubblewrap, static STDIO servers, and the fact
  that the Linux and podman paths are covered by argument-level tests but have not been executed. The macOS file
  policy is a deny-list: `~/Documents`, `~/Desktop` and project `.env` files stay readable to a sandboxed server
  unless you add them to `app.mcp.stdio.sandbox-extra-deny-read`. A server allowed the network gets outbound TCP only
  (no unix sockets, no loopback unless `sandbox-allow-loopback=true`).
- **Matrix pairing is per room.** In the default `PAIRING` sender mode an approved room lets every member drive the
  agent; use `ALLOWLIST` for rooms you do not control. The inbound rate limit (on in this profile) keys Slack,
  Discord, Teams and X on the room or conversation, so a busy shared room shares one budget.
- **Channels are young.** Signal, Matrix and iMessage have run against fakes, not against a real signal-cli, a
  real homeserver or a real Mac. No end-to-end encryption on Matrix. No inbound attachments are read on Signal or
  iMessage. None of the three has a workflow trigger yet.
- **Windows and WSL2.** The PowerShell launcher and the Setup app's Windows wizard work (WSL2 is still required
  underneath), but the Windows path has had less exercise than macOS and Linux.
- **Translations.** New screens (skill library, local-model wizard, Signal, Matrix, iMessage) are English only; other
  locales fall back to English.
- **Local models are weaker.** A 4B model on a laptop answers more slowly and calls tools less reliably than a
  hosted one. The probe tells you how well it does; it is advisory, and it does not change how a chat turn runs.

## Features that need Lite or Full

A personal install is one person on one machine, so some console pages have nothing behind them. The rail does not list
them, and a direct link shows **"Not available in the personal installation"** with the reason and a pointer to the
[installation guide](installation.md): install the Lite or Full edition (Docker Compose, or the Helm chart on your
infrastructure) to use them. The pages are single sign-on (needs Keycloak), Shared with me, Agent trust and API keys
(team features), Plans, Billing, Cost & ROI, Agent payments and Agent commerce (billing is off), and the public
Directory, Studio, Leads, Branding and MCP access (multi-tenant surfaces). Code Intel shows a similar state on any install
that has no Code Intel service configured. The list is one map, `web/app/lib/editionCapabilities.ts`.
