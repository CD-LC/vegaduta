# Phase 0 safety workflow (2026-11)

Gate before the "learning agents" roadmap: a learning agent turns every open safety gap into persistent damage
(a poisoned page writes to memory, memory is trusted forever). Source: the brutal re-audit of 2026-10-28 (findings
numbered there as #1-#14) and `docs/security/owasp-mapping.md`.

**Exit criteria for the phase:** (1) an adversarial suite proves injected content cannot reach long-term memory, drive a
side-effecting tool without a human, or forge a control marker; (2) a `strict` posture exists, is tested, and the
default-flip is decided by the owner; (3) every package below is merged with its tests green.

## Rules for every work package

- One agent, one git worktree, one package. Touch **only** the files in your package's ownership list. A needed edit
  elsewhere goes in your final report as a request, not a change.
- Commit locally on your worktree branch, `git commit -s`, Conventional Commits. **Do not push.** The lead integrates.
- Config, docs and CHANGELOG edits (`application.yml`, `docs/configuration.md`, `CHANGELOG.md`) go in a **separate last
  commit** so the lead can resolve overlaps. Add config keys with a code default so tests do not need the yml.
- Maven: one build at a time machine-wide (`AGENTS.md` rule 1). Wrap every `mvn` call:
  `flock /tmp/vd-mvn.lock mvn -B -ntp -q ...`. Prefer `-pl <module> test -Dtest=...`; run the whole touched module before
  you finish. Compiled is not tested; say which you did.
- Respect `AGENTS.md`: tenant context on async hops, architecture baselines only shrink, no duplicate bean names,
  Flyway versions are one namespace (the next free number is 675; claim yours in your report), no secrets.
- Every fix ships a test that **fails on the old behaviour**. Show it: revert the fix, run, restore.
- Final report (max 40 lines): what changed (files), tests run with counts, what you did NOT verify, requests for
  other packages, and the config/doc snippets you added.

## Work packages

| # | Package | Owns (only these) | Depends on |
|---|---|---|---|
| A | Strict posture profile + posture test | new `application-strict.yml`, new `core/app/.../posture/*`, `core/app/src/test/.../posture/*` | - |
| B | Taint seeding + persisted conversation taint | `core/agents-tools/.../AgentTaintPolicy*`, `core/guardrails/.../taint/*`, taint call sites in `core/omnichannel*`, `core/agents-turn` turn setup | - |
| C | Memory provenance, quarantine, write screen | `core/agents-turn/.../memory/*`, `core/graphmemory/*`, new `core/knowledge/.../MemoryWriteScreen*` | B (reads the taint flag via an interface it defines) |
| D | Out-of-band control markers | `core/chat/.../ToolLoop*`, `ArgumentSanitizingToolCallingManager`, `core/guardrails/.../ApprovalParked`, `ToolResultMarker`, `TurnApprovalState` | - |
| E | Approvals hardening | `core/approval/*`, `core/agents-tools/.../AgentToolCallApprovalExecutor`, `ToolDefinitionDigest` | - |
| F | Spend/quota hardening | `core/security/.../GovernanceFailOpenPolicy`, `core/agents/.../quota/*`, `core/tenancy/.../PlatformFeatureBudgetService`, `core/agents-budget/*` | - |
| G | Client-IP and fraud-challenge fixes | `core/app/.../common/security/{ClientContext,FraudAnalysisController,FraudDetectionInterceptor,AbuseDetectionService}` | - |
| H | Adversarial red-team suite | new `core/app/src/test/.../redteam/*` (tests only) | writes tests for the post-fix behaviour of A-G |
| I | Compliance and docs | `docs/security/*`, `docs/compliance/*`, `docs/architecture/ADR-0027-*` | - |
| J | Real-database verification | `core/app/src/test/.../it/*` (new ITs only), `scripts/ci/*` prerequisites | V675 from the prior fix |
| K | Output-side system-prompt leak control (LLM07) | new `core/guardrails/.../leak/*`, one wiring class | - |
| L | A2A replay protection | `core/agents-interop/*` | - |

## Merge order and gates

1. Lead cherry-picks D, E, F, G, K, L, I (independent) first, running each module's tests after each pick.
2. Then B, then C (C needs B's interface), then A.
3. Then H runs against the integrated branch; every H failure becomes a bug on the owning package.
4. J runs last on the integrated branch. Then `make gates`, `scripts/ci/boot-check.sh`, `mvn verify`.
5. The default-flip (strict as the default posture for non-personal) is the owner's call; A only ships the profile.

## Log

Owner of this workflow: lead session on branch `ccr-d9bcd3ff-ekgsbs`.
