# A lighter, faster personal install: findings and plan (2026-10-22)

Goal set by the owner: a personal install should be easy to start and reach a working console in minutes, and
should carry only what one person on one laptop needs. This page records what was measured, what the first
tranche changes, and what the next tranches are, in order of value and risk.

How each claim was checked, so nothing here is stronger than its evidence:

- **measured**: seen on a real run, with the numbers and the conditions given.
- **read**: taken from the code, compose files, Dockerfiles or the launcher, not run.
- **proposed**: a target or an expected effect that has not been measured yet.

## 1. What failed

A fresh personal start on the published 0.1.8 images, Docker Desktop on an Apple Silicon laptop, empty database:

- Core took 299 s to report "Started" (**measured**). Compose gave up at 270 s because the health budget was
  a 120 s start period plus 10 checks 15 s apart, so it printed `dependency failed to start`, never created
  the console container, and the install looked broken (**measured**).
- The laptop was heavily loaded during that run (load average about 31 on 10 cores, a Kubernetes control plane
  and an image scanner running in the Docker VM). The repository documentation quotes about 21 s for the same
  start on an idle machine (`docs/personal-mode.md`, **read**). The two figures have not been reconciled; an
  idle-machine run is the first item of the plan below.
- The criterion that matters either way: a fresh install must reach a healthy console on a loaded machine without
  Compose giving up.

## 2. Where the time went in that 299 s run

All times from the core container's start (**measured**, loaded machine).

| Phase | Seconds | Note |
|---|---|---|
| JVM up to Spring "Starting" | 12 | |
| Component scan and 235 JPA repositories | about 37 | about 1045 stereotype classes |
| Connection pool and Flyway, 381 scripts into `public` | about 21 | 10.8 s of it running scripts |
| Hibernate entity manager, 233 entities, validate against 507 tables | about 103 | includes a 56 s stall the pool logged as "thread starvation or clock leap" |
| Remaining bean creation to "Started" | about 112 | |
| After "Started": 256 tenant-schema scripts, first-run sign-up, readiness | up to about 120 | tenant scripts took 9.6 s of it; readiness was already UP at the earliest probe the log kept |

CPU used by the JVM in the same run: main thread 124 s, stop-the-world collections 65 s (SerialGC growing a heap
that started near 24 MB), C2 compiler 64 s, C1 compiler 21 s. The first start also applies about 640 migrations
(382 into `public`, 257 into the owner's schema), each in its own transaction (**read**).

## 3. Where the size goes (default personal install, per CPU architecture, uncompressed)

All **read** from `docker image ls` and the Dockerfiles; the earlier belief that images "doubled" between 0.1.6 and
0.1.8 was wrong: Docker's containerd image store was adding a second CPU platform.

| Image | Size | What it is for |
|---|---|---|
| postgres (Postgres 16, pgvector, Apache AGE) | 0.71 GB | database; the extensions add 40 MB, the Debian base is the rest |
| core | 1.23 GB | the API; 0.34 GB of it is the jar stored twice (fixed below) |
| web | 0.74 GB | console |
| valkey | 0.06 GB | counters, rate limits, session memory |
| searxng | 0.38 GB | private web search (command-line default on, Setup app default off) |
| mcp-stdio-runner | 0.60 GB | runs MCP servers (command-line default on, Setup app default off) |
| **Command-line default** | **about 3.7 GB** | memory limits add up to 3.78 GB |

Opt-in extras: workspace-runner 1.35 GB (3 GB memory limit, on in the Setup app by default), sidecar 4.2 GB,
browser-runner 3.5 GB, `core-full` about 6 GB.

Inside the core image (**read**): a 339 MB application jar, of which the Playwright driver bundle is 198 MB;
Kafka, Temporal, the AWS and Azure SDKs, Twilio and Groovy are in the jar although the personal profile set uses
none of them; a 122 MB Node binary that is only needed when MCP servers run in core; a full JRE where a
trimmed one would be about half the size.

## 4. First tranche (small, reviewed, reversible)

Five pull requests, each reviewable and revertable alone. They land in the release after 0.1.9, not in 0.1.9,
which is already published.

1. **Start-up budget** (health checks, JVM heap, personal profile switches, model test once). Core gets a 10-minute
   start period and every health check probes each 1-2 s while starting, so a slow first start no longer fails
   and a fast one reports healthy within seconds. The console starts without waiting for core to be healthy.
2. **`--minimal` / `--no-valkey` exit in silence** (launcher bug found while measuring).
3. **Preparing an install must not stop another install's containers, and the install test must not overwrite the
   developer's kept secrets.**
4. **Core image stores the jar once** (about 340 MB smaller).
5. This page.

Not measured yet: how much faster the changes make a start. See section 6.

## 5. Plan, in order

### A. Measure on an idle machine first (days; no risk)

Run the published 0.1.9 images and the branch with `-Dapp.startup.profiling.enabled=true` (the built-in
per-bean timeline), cold on an empty database and warm on an existing one, three runs each. Report: time to
"Started", time to readiness, time to a healthy console, bytes pulled. Everything below is ranked by that data.

**Proposed targets**, to be replaced by the measured numbers: idle machine, images present, empty database, core
ready within 90 s and console healthy within 120 s; restart within 30 s; the Compose budget at least 5 times the
measured idle-machine figure; default pull at most 2.5 GB.

### B. Levers inside core that need a measurement before they are kept (days to a week)

| Lever | Expected effect | Risk |
|---|---|---|
| `ddl-auto: none` for the personal profile (Flyway already owns the schema; CI boots the real jar) | removes schema validation of 233 entities against 507 tables | a drifted schema is no longer caught at boot |
| AppCDS archive trained on the personal profile and shipped in the image (the stage exists but trains on the `lite` profile and is not published) | fewer seconds loading and verifying about 1000 classes | image grows by the archive; release workflow change |
| `-XX:TieredStopAtLevel=1` for personal | cuts about 64 s of C2 compiler CPU at boot | lower peak speed on long sessions |
| First-run sign-up (257 tenant scripts) off the readiness path | about 10-15 s on a first start | the setup link must exist when the console opens |
| Stop applying the 257 tenant scripts to `public` | removes duplicated tables and about 10 s | only valid together with `ddl-auto: none`; code that falls back to `public` must be checked |

### C. Image diet (one to two weeks; needs the release workflow owner)

- jlink JRE for core (about 85 MB saved); Node binary only in the variant that runs MCP in core (122 MB).
- Strip the Playwright driver bundle from the slim core image (198 MB); the build stage is shared with `core-full`,
  so this needs a build argument, not a delete.
- Alpine Postgres image: the Dockerfile exists but is not published; 0.71 GB to about 0.25 GB.
- One Python in mcp-stdio-runner instead of two (the apt Python and curl are 58 MB).
- Default stack: Postgres, core, console and Valkey only. Search, MCP runner, workspace runner and browser become
  add-ons pulled the first time they are switched on. The Setup app already works this way for search and tools;
  the command line does not.
- Pull only the platform's own architecture and say so in the docs (the containerd image store double count).

### D. A personal build of core (weeks; the real "remove what personal does not need")

About 20 of the 55 core modules load in a personal install with no gate and do nothing for one person:
billing, payments (UPI mandates), Sarvam speech, Slack, Discord, Teams and X channels, RL training, A2A
interop, quality evals, cross-tenant platform operations, KMS and cloud database authentication
(**read**, from the module inventory). Each needs a profile or property gate and an ArchUnit baseline that only
shrinks, then a `core-personal` assembly without the cloud SDKs. Expected: fewer beans and entities (less scan,
Hibernate and heap) and a jar well below 340 MB. This is the largest lever and the largest change; it should
follow A and B so it is justified by data.

### E. Migrations (weeks; high care)

Released migrations are never edited (AGENTS.md). A baseline for empty databases needs a new mechanism (a snapshot
applied only when the history table is empty, with the existing scripts kept for upgrades) and a test that both
paths produce the same schema. Expected: the 640 first-start script runs shrink to a few. Do it only if A shows
migrations are still a large share of a first start.

## 6. Verification before each step ships

1. Static: `scripts/ci/compose-validate.sh`, the install tests, the profile test, `docker buildx build --check`.
2. A real stack in an isolated Compose project with its own env file and ports, so it cannot touch an existing
   install: empty-database start, restart, console sign-up, one agent turn through the local Ollama model.
3. Startup numbers recorded in the pull request, from the profiler and the container timestamps, with the
   machine load stated.

## 7. Release

All of section 4 ships in the next release after 0.1.9 (0.1.10 or the first 1.0 candidate, the release owner's
call). Releases are immutable, so none of it can appear in 0.1.9.
