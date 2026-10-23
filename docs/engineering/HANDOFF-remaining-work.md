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

## Part 6: local-model latency and agent persistence (measured 2026-10-23, personal install on 0.1.8 images)
Measured on a 24 GB Apple-silicon Mac, Ollama 0.40.3, qwen3.5:9b (mlx runner), core env APP_LLM_OLLAMA_NUM_CTX=32768.
- Raw Ollama is fast (~27 tok/s, 0.3 s for a tiny prompt, no reload when num_ctx changes).
- Core sends a fixed prefix of ~8.9K tokens (35.7K chars: system messages + tool schemas) on every call. Prefill runs at ~600 tok/s, so a cold call
  takes ~13-19 s before the first token (llm_call_log: 8588 prompt tokens -> 19166 ms). A repeat with an identical prefix took 2.6 s, so caching works only when the prefix is stable.
- The agent's own system prompt is only 4.7K chars; tool schemas are budgeted at ~3000 tokens (app.tools.auto-selection.*). Find where the other ~25K chars come from
  (platform prompt blocks, skills, guidance) and cut or conditionally include them for local models. Hypothesis: the per-turn tool selection changes the tools block and breaks the cache.
- The SSE endpoints exist (AgentChatStreamController, resumable streams). The "delivered as a letter" feeling is the dead time before the first token plus silence during tool steps; verify in the UI that deltas and step events render as they arrive.
- "No web search" replies: web_search is in the agent's allowed tools and SearXNG is configured (WEB_SEARCH_PROVIDER=searxng), so the per-turn tool selector probably drops it
  (no-match budget is 4 tools) and the model is not told other tools exist. Check that tool_search is enabled and that the prompt tells the model it can search for tools.
- Persistence: log shows "reply stopped at an unfinished step; continuation pass 1 ... made no tool call; stopping". The loop gives up after one pass.
Goals: first token in 2-3 s on a warm session; stream text and step events continuously; the agent keeps going until the task is done or a real limit is hit; never claim a capability is missing when it is allowed.
Ideas: prefix-cache warm-up at session open, stable prefix ordering (static first, per-turn tools last), a compact local-model prompt profile, keep tool_search always available, raise continuation passes with a "no tool call but task unfinished" re-prompt, and a smaller default local model (qwen3.5:4b) on machines under memory pressure (swap was 5.9 GB of 7 GB).

### Part 6 requirement: nothing hardcoded, everything user-configurable
- Hardcoded today: `PlatformHostedLlmProvider.SMALL_MODEL_MAX_BILLIONS = 8.0` (core/providers). It decides which local models get small-model mode,
  and qwen3.5:9b misses it, so it runs the full prompt with 12 tools. Make it a setting in the catalog (platform < tenant < row override), show it in the UI, document it in docs/settings.md.
- Already configurable: `app.chat.small-model.tool-cap` (6), directive texts via the prompt registry (prompts/en/directive.*.md), `app.tools.auto-selection.*`.
- Audit the latency path for any other constants (timeouts, token budgets, prefix thresholds such as prompt.prefix-warn-tokens read from env only) and move each into the settings catalog.
- Identify the ~11K unaccounted characters in the fixed prefix (skills block, memory blocks, MCP text) and make each part switchable and sized by a setting.

### Part 6 status (2026-10-23, after the latency fix)
Done (commit "fix(chat): stream replies live and keep local-model prompts cacheable"):
- Streaming: FAST turns with a generation/MCP tool stream live; only the part from the first link is held
  (`app.chat.parity.fast-buffering-mode`, `fast-hold-markers`). This was the "whole answer at once" behaviour on every provider.
- Local models get a stable tool set (`app.chat.local-model.stable-tools`, `.providers`), so Ollama's prompt cache holds and web_search is no longer dropped.
- Small-model size limit is a setting (`app.providers.small-model-max-billions`).
- Measured (24 GB M5, qwen3.5:9b, MLX): cold 9K-token prompt ~15 s to first token; cached <1.5 s; decode ~23 tok/s.
Still open:
- Not yet measured end to end on a rebuilt install (the running install was the 0.1.8 images).
- Cold first message after a model load still reads the whole prefix. Next: prefix warm-up after the model loads (MLX keeps whole-state
  snapshots only every 8192 tokens, at prompt end, and at branch points; two requests that diverge at the user message create the
  boundary snapshot), and a shorter prefix (the schema budget counts only input schemas, not descriptions; always-kept tools are uncapped).
- The per-turn memory/date block (message B) sits before the history; moving it after the history would keep more of the prompt cacheable.
- Hosted (non-local) turns still drop web_search on messages that do not mention it (no-match budget); the small-model intent table is not used there.
