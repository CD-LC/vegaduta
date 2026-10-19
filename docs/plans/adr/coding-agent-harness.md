# ADR: General coding-agent harness (roadmap L7)

Status: proposed, stage 0 implemented behind `app.coding-harness.enabled` (default false). Date: 2026-10-01.

## Context

Developers, freelancers and researchers want an agent that edits code, runs the tests, iterates, and hands
back a reviewed change. What exists today:

- `core/sdlc`: E2B sandbox clients (`E2bSandboxClient`, `E2bCodeExecutionClient`, `SdlcSandboxService`) with
  per-tenant BYOK credentials. No agent loop, no VCS.
- `core/agents-studio/.../appbuilder`: App Builder generation (file edits, completion). A fix loop exists for generated
  apps but is specific to that product, not a general workspace.
- `RunBudget` (per-turn USD cap, off by default) and the tool classification confirmation gate
  (`AgentToolGovernance`, park/approve for tools that require human confirmation).

Gap: nothing gives an agent a durable workspace with exact undo, a bounded verify loop, or a review step.

Premise re-verified in code: no `git`/checkpoint/rollback code exists in `core/sdlc` or `appbuilder`
(grep for git workspace, checkpoint, rollback returned nothing relevant).

## Options

1. **Run git and tests on the core host.** Simplest, but executes tenant-influenced code on the core JVM host.
   Rejected outright (same reasoning as roadmap L6).
2. **Workspace in the E2B sandbox, git as the checkpoint mechanism (chosen).** Every checkpoint is a commit;
   rollback is `reset --hard` + `clean`; the diff for review is `git diff base HEAD`. Reuses the existing
   sandbox and its isolation. Cost: sandbox lifetime/timeouts bound a run; no persistence beyond the sandbox
   unless exported.
3. **Snapshot/restore of the whole sandbox (E2B snapshots).** Coarser, vendor-specific, slower, and loses the
   inspectable history that review needs. Possible later as an optimisation.
4. **Virtual file map held in core (like App Builder preview files).** Cannot run real test suites. Fine for
   the App Builder preview, wrong for a general harness.

## Decision

Build the harness as a small, framework-free engine in `core/sdlc` (package `harness`) that talks to the
workspace only through a `ShellRunner` interface (E2B in production, fakes in tests), and takes its budget and
confirmation collaborators as interfaces so `core/sdlc` stays free of a `core/agents` dependency (agents already
depends on sdlc; the reverse would be a cycle).

Components (stage 0, all in this change):

- `GitWorkspace`: `ensureRepository`, `checkpoint(label)`, `rollbackTo(sha)`, `diffSince(sha)`. Shell input is
  restricted (hex SHAs, sanitised labels, no user text in commands).
- `CodingHarnessLoop`: baseline checkpoint, run tests, bounded fix iterations (hard ceiling 10), checkpoint per
  fix, review of the diff against baseline, human approval, rollback on every non-success outcome.
- `HarnessLimits`: clamped bounds. `HarnessBudget`: cost guard; `RunBudgetHarnessBudget` (agents) adapts the
  existing `RunBudget`. `HarnessApprovalGate`: the confirmation hook; no auto-approve implementation exists.
- Config: `app.coding-harness.*`, default OFF, documented in `application.yml`.

## Trade-offs and decisions inside the design

- Rollback target is always the baseline, not the "best" iteration. We have no reliable partial-progress metric
  from a pass/fail exit code; a clean tree is the safe failure state. A later stage may keep the failing branch
  as a ref for the user to inspect before the reset.
- Budget is checked before each fix, and review/fix model text is recorded as estimated spend. `RunBudget` is an
  estimate (chars/4), a runaway guard rather than billing; the harness inherits that limitation.
- The tests the agent runs are chosen by the caller. The reviewer must flag edits that delete or weaken tests; the
  loop does not enforce it structurally in stage 0 (see risks).
- Single sandbox, single run at a time per workspace; no concurrency control yet.

## Staged plan

0. (done) Engine + adapters + unit tests with fake shell. Not wired to any endpoint or tool. Compiled and
   unit-tested only after the lead's serial build; never run against a real E2B sandbox.
1. E2B-backed `ShellRunner` over `SdlcSandboxService`/command execution, plus a built-in tool `run_coding_task`
   (flag-gated registration so no schema tokens when off), marked `requiresHumanConfirmation` in tool
   classification, with `HarnessApprovalGate` bridged to the existing park/approve flow.
2. LLM `Fixer` and `Reviewer` implementations (separate review prompt, ideally a different model than the
   fixer), test-tampering check (diff touches test files => force review flag), run transcript persisted via the
   L1 step journal when it lands.
3. Seed workspace from a user's Git repo or uploaded archive; export result as patch/PR; App Builder fix loop
   migrated onto the same engine.
4. Evals: a small SWE-style task set under the eval module; pass^k reporting.

## Risks

- Untrusted code runs in the sandbox; network egress from the sandbox is governed by the E2B template, not by
  this engine.
- A fixer can make tests pass by weakening them. Mitigated only by the reviewer in stage 0; stage 2 adds a
  structural check.
- `git clean -fd` removes untracked files in the workspace; only safe because the workspace is the harness's own.
- Cost estimation is approximate; the hard iteration ceiling and test timeout are the real bounds.
- Sandbox timeouts can kill a run mid-loop; the loop then reports ERROR and attempts rollback, which may itself
  fail (logged).

## Stage 1 (2026-10-05, branch we/codingagent) - built behind `app.coding-harness.enabled`, unverified on a real sandbox

What exists now, all default OFF (flag off = 404s, no tool, no nav item, no thread):

- `core/agents-studio/.../coding`: `CodingTaskController` (`/api/coding-tasks`: capabilities, start, list, get, diff,
  approve, reject, cancel, rollback), `CodingTaskService` (the job: bounded 2+4 executor, Redis-held state, liveness
  heartbeat - the Agent Lab's substrate, no table, no migration), `LlmCodingFixer` / `LlmCodingReviewer` (the agent's
  own model via `AgentLabCompletionService`, whole-file JSON edits, never a shell command), `CodingTaskBudget` (steps,
  estimated tokens, wall clock, hard caps in `CodingTaskSettings`).
- `core/sdlc/.../harness`: the loop gained a progress listener, a cancel check and an optional initial edit (stage 0
  behaviour unchanged without them). New: `CodingSandbox` (`E2bCodingSandbox` default, `LocalCodingSandbox` dev-only),
  `CodingSandboxFactory`, `CodingCommandPolicy` (mvn/gradle/npm/pytest/go test commands only, no shell syntax),
  `RepoArchive` (zip-slip / link / size / entry caps, repacks to regular files), `GitUrlPolicy` (public https,
  resolved addresses must be public), `WorkspacePaths` + `WorkspaceFiles` (path confinement incl. a symlink check in
  the sandbox).
- `coding_task` agent tool (`ALWAYS_CONFIRM_TOOLS`, EGRESS + irreversible): starts a task and returns the diff and
  test summary; it has no way to approve. The harness approval gate is unconditional, bound to the SHA-256 of the
  exact diff shown, and fails closed on timeout, cancel, store error or an oversized diff.
- Web: `/coding` page (create, live progress, diff viewer, approve/reject, cancel, rollback, patch download).

Known gaps: never run against real E2B (the template needs bash, git, python3, `unshare` and the toolchains;
`unshare -rn` as the no-network mechanism is untested there); commands are capped at ~100 s by the 120 s HTTP read
timeout to the sandbox, so long suites need a detached-run-and-poll runner; with no network a build that must
download dependencies fails (pre-baked template or `app.coding-harness.network-enabled=true`); a zip symlink cannot be
seen by `ZipInputStream` (it arrives as a plain file); the task record is Redis-only, so a core restart ends a live task.
