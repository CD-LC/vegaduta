# Agent coordination

Several AI coding agents work on this repository at the same time: cloud sessions and agents in
local editors. This page is how they avoid undoing each other's work, cancelling each other's CI,
or shipping a release or a production deploy without the latest fixes. **Read it before you push,
merge, tag, release or deploy, and add a line to the log at the bottom when you do one of those.**

## Rules

1. **No direct pushes to `main`.** Work on a branch and open a pull request; merge only when
   `CI OK` and `CodeQL OK` are green on the PR's head.
2. **One release at a time, run by one agent.** The maintainer pushes the `vX.Y.Z` tag; the agent
   that owns the release (see *Current owners*) dispatches `.github/workflows/release.yml` on it.
   Nobody else dispatches, re-runs or re-tags it.
3. **Between a tag and its published release, nothing merges to `main`.** The release waits for
   `CI OK` on the tagged commit, and a new push to `main` cancels that CI run (one concurrency
   group per branch). That is what delayed v0.1.7 by an hour.
4. **Claim before you edit.** Add your files or area to *Current owners* first; stay off files
   another agent has claimed until their PR merges, or ask them.
5. **Production runs published releases only.** Deploy a release's images by version
   (`VEGADUTA_VERSION=X.Y.Z`); never images built from a branch or from `main`.
6. **Never change `APP_ENCRYPTION_KEY`** on a deployment that has data (AGENTS.md, rule 5).

## Current owners

| Agent | Where | Owns |
|---|---|---|
| Release / install agent | cloud session, branch `claude/why-the-hell-k4tttl` | installer (`installer/`), launchers (`scripts/vegaduta`, `scripts/vegaduta.ps1`), `deploy/compose/` personal and local-AI overlays, `.github/workflows/release.yml`, releases and tags; PR #13 (platform-model warm-up: `core/chat/PlatformPoolWarm*`, `web/app/lib/warmPlatformModel.ts`, `web/app/components/chat/ModelWakingUpNotice.tsx`) |
| Local-model agent | cloud session, branches `ccr-*` | local-model performance (`docker-compose.local-ai.yml` keep-alive, Ollama options, `web/app/components/assistant/LocalModelTip.tsx`), through PRs |
| Production agent | local editor | the production deployment: which release it runs, its configuration and its verification. Does not change application code without a PR |

## What production must run

| Release | Status | What it brings |
|---|---|---|
| **v0.1.7** | published 2026-10-21, latest | Setup and install fixes. These reach users through the Setup app and the release downloads, not through the server: the console's `/downloads` page reads the latest GitHub release at request time (cached up to an hour), so it offers 0.1.7 without a redeploy. Production should still run the 0.1.7 images. |
| **v0.1.8** | PR #13 merging, then tag and release | Platform model warm-up: `POST /api/platform-llm/warm` loads the pool model when someone signs in, and the chat says "The model is starting up..." after 8 s without a reply. This fixes the 70 s first reply in production **only once production runs 0.1.8**. |

### After a production deploy, check

- `GET /actuator/health` is `UP` on core, and the console loads.
- `/downloads` lists `VegaDuta-Setup-<latest>.dmg` and `vegaduta-setup-windows-amd64.zip` for the
  latest release.
- From 0.1.8: signed in, `POST /api/platform-llm/warm` answers `202`, and core logs
  `Platform model '<model>' warmed in <n> ms`. The next first chat answers without the long wait.
  Turn it off with `APP_PLATFORM_LLM_WARM_ENABLED=false` if the pool provider bills per request
  and that matters.
- The core image tag in use matches the release you meant to deploy.

## Log

Newest first. One line per push, merge, tag, release or deploy: time (UTC), agent, what.

- 2026-10-21 05:55, release agent: v0.1.7 published and verified (installers, checksums, no
  symlinks in the source tarball, images pull). PR #13 marked ready for 0.1.8.
- 2026-10-21 03:58, local-model agent: merged #12 to `main`, which cancelled the v0.1.7 tag's CI;
  the release agent re-ran it. Rule 3 above comes from this.
