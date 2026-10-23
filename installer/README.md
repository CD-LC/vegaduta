# installer — `vegaduta-setup`

A single static binary for non-technical installs: double-click (or run) it, it
opens a setup wizard **in the browser** on `127.0.0.1`, and the wizard checks
the machine, installs or starts Docker when asked, downloads a verified
release, starts the stack and opens the first-run setup link. The product is
already browser-based; the installer is too — no native UI toolkit, no per-OS
GUI code.

It deliberately reuses the existing contract instead of reimplementing it:

- release artifacts and `VEGADUTA_*` env vars work exactly as in
  [`../install.sh`](../install.sh) (SHA256SUMS check always; keyless Sigstore
  verify of SHA256SUMS when `cosign` is installed; `VEGADUTA_REQUIRE_SIGNATURE=1`
  makes it mandatory);
- machine preflight mirrors `scripts/vegaduta doctor` (Docker daemon, compose
  >= 2.24, Docker memory, disk, published ports);
- `secrets` and `up --lite|--full` / `personal` run the unpacked tree's own
  `scripts/vegaduta` (`vegaduta.ps1` on Windows), so the wizard can never drift
  from the CLI;
- personal mode's one-time `…/login?setup=<token>` link (see
  `docs/personal-mode.md`) is picked out of the CLI output and becomes the
  final "finish setup" button.

## Run

```sh
cd installer
go build -o vegaduta-setup .
./vegaduta-setup                    # open the wizard (default tier: personal)
./vegaduta-setup --no-browser       # serve it, print the URL only
./vegaduta-setup --yes              # headless: preflight + install, no browser
./vegaduta-setup --dry-run          # preflight + the plan, change nothing
./vegaduta-setup --from /path/to/checkout   # install from an existing tree
```

Flags: `--dir` (default `~/VegaDuta` or `$VEGADUTA_HOME`), `--tier personal|lite|full`
(default `personal`), `--version X.Y.Z` (or a pre-release such as `1.0.0-rc.1`; default: the
newest published stable `vX.Y.Z` release),
`--repo owner/name` (default `CD-LC/vegaduta`), `--port N`, `--allow-docker-restart` (Windows, see below), `--extras id,id` and `--list-extras` (see [Extras](#extras-personal-tier)); `--documents` and `--browser` still work and mean `--extras ...,documents` / `...,browser`.

## Extras (personal tier)

The wizard's **Extras** step lists the optional add-ons with their disk, memory
and licence, ticks the recommended set (today: the build terminal and private web search, the two things a
fresh install turns on by itself) and totals
what the ticked ones cost against this computer's free disk, Docker memory and
RAM, with a warning (never a block) when it is short. An add-on that needs
another pulls it in and says so; unticking the needed one unticks the rest.
Planned add-ons are shown but cannot be ticked.

- **One source.** The list is [`addons/catalog.json`](addons/catalog.json), an
  embedded, byte-identical copy of the catalog core serves
  (`core/providers/src/main/resources/addons/catalog.json`). `go test` fails when
  they differ (`TestCatalogMatchesCore`); refresh the copy with
  `installer/sync-addons-catalog.sh` (`--check` to only compare). Setup never
  names a profile, flag or `.env` key of its own: it uses the ones the catalog
  gives, after checking them against what the personal launcher accepts.
- **How a choice is applied.** Profiles become `scripts/vegaduta personal
  --profile ...`, flags (`--search`, `--browser`) are passed as is, and any `.env`
  keys an add-on declares are written to `deploy/compose/.env`, the file the Setup
  app and the launcher share (no shipped add-on declares any now). A deselected
  flag, and the `tools` profile, is passed as its `--no-*` twin when the unpacked
  launcher has it (a fresh personal install otherwise turns web search and the
  MCP tools runner on by itself). What is on is read from the launcher's own
  state and `.env`, not from Setup's remembered choice, which only supplies model
  packs; so a change made on the command line or in the console is kept. The
  launcher only ever *adds* `--profile` values to the ones it remembers, so Setup
  first rewrites the `profiles=` line of `deploy/compose/.vegaduta-state` to the
  chosen set. Model packs are pulled after the stack is up: on macOS and Linux with
  `scripts/vegaduta models pull TAG`; on Windows (`vegaduta.ps1` has no `models`
  command) by Setup itself, through the Ollama the install uses - the container's
  `ollama pull` when the launcher state says `local_ai=container`, else the one on
  this computer (`POST /api/pull`). A pull counts only when Ollama reports it
  finished. A failed one is reported in the log and on the done page and does not
  fail the install, but the pack is **not** remembered as installed, so the next
  run of Setup offers it again.
- **Re-run it.** Running Setup again on an existing install shows the extras it
  has now (from `deploy/compose/.vegaduta-addons.json`, or rebuilt from the
  launcher state and `.env` for installs that predate Extras) and applies
  additions and removals. The same choices applied twice change no file.
- **Switching off never deletes.** A deselected add-on's container is stopped
  (`docker stop`, nothing removed); volumes, projects, documents, saved
  sign-ins and downloaded models stay, so ticking it again resumes with its data.
- **Windows.** `vegaduta.ps1` takes profiles but no flags, so add-ons that need a
  flag (web search) are shown as unavailable there. It keeps every profile,
  `tools` included, in `profiles=` of its state file (the bash launcher writes
  `tools=1`); Setup reads both.
- **Headless.** `--extras workspace,search` installs exactly those, `--extras
  none` none; without it a re-run keeps what is installed and a fresh install
  gets the recommended set. `--list-extras` prints the catalog.

## Re-running on an existing install, and versions

A tree already in the install folder (`~/VegaDuta`, `--dir`) is **reused as it is**: Setup
applies the Extras to it and starts it, it never replaces it with a newer release. So the
plan (`--dry-run`, and the wizard's Release line) shows the version that is installed -
the `VEGADUTA_VERSION` its `.env` pins, else the one stamped in `.env.example` - and never
says "latest" for something that will not be downloaded. When a newer stable release exists
(or `--version` asks for another one) Setup says so, with the exact command in the install
folder that does it (`scripts/vegaduta upgrade --version X.Y.Z`, `.\scripts\vegaduta.ps1
upgrade -Version X.Y.Z` on Windows). Upgrading is the launcher's job because it takes a
database backup first.

"Latest" is the newest published **stable** release tagged `vX.Y.Z`: Setup reads the
Location of `https://github.com/<repo>/releases/latest` (as `install.sh` does; no API
quota), and only if that is not a `vX.Y.Z` tag (the SDK releases are tagged `sdk-v...`)
reads the release list from the GitHub API and skips drafts, pre-releases and other tags.
Errors say which of these happened (rate limiting, nothing published, no network) and to
pass `--version`. `--version` is checked when Setup starts (and in `--dry-run`).

## Behind a proxy

Setup reads `HTTPS_PROXY` (`HTTP_PROXY` for plain http), `ALL_PROXY` (either, as a
fallback; `http://`, `https://` and `socks5://` URLs, or `host:port`) and `NO_PROXY`
(host names and `.suffixes`, IPs, CIDR ranges, `host:port`, `*`) - **not** the Windows or
macOS system proxy settings, which a double-clicked app does not see in its environment
even when the browser uses them. Start Setup from a terminal that has them set, for
example `set HTTPS_PROXY=http://proxy.example:8080` and then `vegaduta-setup.exe` in a
Windows Command Prompt. A download that fails to connect says this. The wizard's own
page and the console (this computer) are never sent through a proxy. `vegaduta.ps1` and
`scripts/vegaduta` run their own downloads with their own proxy rules.

## When Docker is missing or stopped

Two wizard buttons call `POST /api/docker/install` and `POST /api/docker/start`:

- **macOS**: `brew install --cask docker-desktop` (the cask's current name; `docker` is its alias) when Homebrew is present, else a link
  to the Docker Desktop installer; `open -a Docker` to start, then the daemon
  is polled for up to 3 minutes.
- **Windows**: `winget install -e --id Docker.DockerDesktop`; start is pointed
  at `Docker Desktop.exe`. WSL2 activation still needs one admin command the
  wizard prints (`wsl --install` + reboot). When Docker has less memory than the
  tier wants, giving it more means restarting Docker Desktop and running
  `wsl --shutdown`, which closes every WSL window the person has open: Setup does
  that **only when the wizard's "Let Setup restart Docker" box is ticked** (or
  `--allow-docker-restart` is passed). Otherwise it says what it did not do and
  how to raise the memory in Docker Desktop. When it does, the person's own
  `~/.wslconfig` is first kept as `.wslconfig.vegaduta.bak` (the first copy is
  never overwritten).
- **Linux**: no silent privilege escalation — the wizard prints the
  get.docker.com command for the user to run with sudo.

## If the install fails

Two layers, both optional and on-device:

1. a deterministic playbook in `ui/index.html` maps the common log signatures
   (busy ports, daemon down, WSL2, disk full, checksum mismatch, unpublished
   images, proxy/TLS, migration errors, OOM) to a plain-language cause and fix;
2. an "Explain this error with a local model" button lazy-loads
   `@mlc-ai/web-llm` and runs **Qwen3-0.6B** on WebGPU (~400 MB download, same
   engine family as the product's `/edge` surface) to explain the log tail.
   No WebGPU or no network → the playbook answer alone is shown.

## Layout

| file | job |
|---|---|
| `main.go` | flags, dispatch: wizard vs `--yes` vs `--dry-run` |
| `preflight.go` + `platform_{darwin,linux,windows}.go` + `diskfree_*.go` | doctor-style machine checks, per-OS fix hints |
| `dockerbootstrap.go` | install/start Docker per OS, daemon-wait loop |
| `release.go` + `proxy.go` + `version.go` | latest-tag resolution (redirect, then API), download through the env proxy, SHA256SUMS/cosign verify, safe untar; the installed version and the upgrade advice |
| `models_pull.go` | model packs: launcher on POSIX, Ollama (container or host) on Windows |
| `runner.go` | run `scripts/vegaduta` / `.ps1`, stream its output |
| `addons.go` + `addons/catalog.json` + `sync-addons-catalog.sh` | the embedded add-on catalog, its validation and the sync with core |
| `extras.go` | selection, dependencies, plan (profiles/flags/env/pulls), disk and memory totals |
| `extras_apply.go` + `extras_run.go` + `extras_http.go` + `hostram_*.go` | `.env` and launcher-state edits, remembered choice, stop/pull steps, the wizard's `/api/extras` |
| `server.go` + `ui/index.html` | loopback wizard: preflight JSON, SSE progress, setup link, playbook + copilot |
| `package.sh` | builds the downloadable artifacts (below) |

## Packaging

`package.sh` builds every artifact into `dist/`:

```sh
./package.sh darwin                          # "VegaDuta Setup.app" inside VegaDuta-Setup-<ver>.dmg
./package.sh darwin --payload tarball.tar.gz #   same, with the release embedded (offline install)
./package.sh windows                         # vegaduta-setup-windows-amd64.zip (exe + first-run README)
./package.sh linux                           # vegaduta-setup-linux-{amd64,arm64}.tar.gz
```

- **macOS** (`darwin`, needs a Mac for `lipo`/`codesign`/`hdiutil`): a universal
  `VegaDuta Setup.app` — a `launcher` shell script as `CFBundleExecutable` that
  fixes the Finder PATH, logs to `~/Library/Logs/vegaduta-setup.log` and execs
  the real binary. Optional `--web-port`/`--core-port` stamp `WEB_HOST_PORT` /
  `CORE_HOST_PORT` into the launcher for machines whose default ports are taken.
  With `--payload`, the tarball plus its `SHA256SUMS` sit in the app's
  `Resources/` and the launcher passes `--release-url file://…`, so no published
  release is needed. The app is ad-hoc signed (not notarized) and packed in a
  `.dmg` next to a "READ ME FIRST" with the current steps for opening it:
  System Settings → Privacy & Security → **Open Anyway** on macOS 15 and later
  (right-click → Open only works up to macOS 14), or
  `xattr -dr com.apple.quarantine "VegaDuta Setup.app"` in Terminal.
- **Windows**: the exe is already the whole installer; the zip adds a
  `READ-ME-FIRST.txt` explaining SmartScreen's "More info → Run anyway".
- **Unsigned**: Gatekeeper blocks the quarantined app and SmartScreen warns,
  once per machine, until a Developer ID signature with Apple notarization
  and a Windows code-signing certificate are wired (they need certificates the
  project does not hold yet; the ad-hoc signature does not replace them). Every
  artifact is still covered by the release's `SHA256SUMS`.

CI: `installer.yml`'s `dist` job (manual dispatch) builds linux/windows on
Ubuntu and the `.dmg` in `dist-darwin` on `macos-latest`. `release.yml`
attaches the same artifacts, including the `.dmg` from `setup-pkg-darwin`, to
the draft release inside `SHA256SUMS`.
