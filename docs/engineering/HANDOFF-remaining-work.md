# Handoff: remaining work after the agent-runtime audit

Branch: `sdk-central-plugin-0120`. Target: `main`. Repo: CD-LC/vegaduta.
Done: approval-continuation fix (agent resumes after tool approval), tool ledger, finish-reason handling,
output truncation, soft step limit, settings catalog + UI controls, prompt registry, cleanup, follow-up packages G1-G6.

## Known state when this was written
- Everything is uncommitted on the branch. origin/main is 39 commits ahead of local main; ~63 files overlap.
- Decisions workflow was stopped mid-run: D1 (supervised read-only gating setting) implemented but unreviewed;
  D2 (eval defaults to full pipeline) and D3 (default flips) were half-written. Files touched are under
  core/quality/** and core/agents-jobs/**. They may not compile.
- G7 (non-English translations in web/messages/*.json) was written but not reviewed. Low-resource locales need native review.
- Known gaps: LoadSkill error strings still hardcoded; payments and agent.proposal approvals intentionally do not continue.

## Build notes
- JDK 21 required (JDK 27 breaks Lombok). Run Maven from `core/`: `mvn -o -q -pl <module> -am test -Dtest=<Class> -Dsurefire.failIfNoSpecifiedTests=false`
- Do not run two Maven builds in the same checkout at once (corrupts target/).
- Web: `cd web && npx tsc --noEmit && npx vitest run`
- Commits need DCO sign-off (`git commit -s`).
- Postgres image: Dockerfile uses OPTFLAGS="" (upstream fd8112e6 fixes the same; expect a conflict, take upstream).

## Parts (do in order, one per agent session)
See prompts in the chat handoff; summary:
1. Stabilize the tree (compile, fix or revert D2/D3 half-edits, keep D1 only if it passes review and tests).
2. Commit and merge origin/main.
3. Full verification gate + install smoke tests (personal, lite, test).
4. Finish D1/D2/D3 per docs/engineering/agent-runtime-defaults-review.md.
5. Translations review (G7) and LoadSkill strings.
