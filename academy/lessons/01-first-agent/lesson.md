# Ep 1 — Your first agent (runtime settings)

**Length** ~4 min · **Level** beginner · **Renders to** `ep01-first-agent.mp4`

An agent = **instructions + model + budget + tools**. All four are set in
the agent workbench (`/agents` → New agent, or `/agents?new=1`).

## What the episode does

1. Open `/agents` → **New agent** → the workbench.
2. Name it `Editor-in-Chief` (the name appears in every audit line).
3. **Describe it** tab — a sentence becomes a full draft (persona,
   instructions, defaults). Edit everything it generated.
4. **Setup** tab — model, budget, quotas per agent.
5. **Persona / System prompt** tab — the editorial voice as editable text.
6. `/admin/runtime-settings` — workspace-wide defaults every agent
   inherits (default model, timeouts, limits).
7. **Save draft** — private until published; nothing can call it yet.

## Runtime settings mental model

- **Per agent**: overrides — model choice, spend caps, tool allowlist.
- **Workspace**: `/admin/runtime-settings` — the defaults, plus hard
  platform limits.
- Rule of thumb for The Ledger: triage/section reporters on the cheap
  local model, the Editor-in-Chief on the strongest available.

## After watching

[Ep 2 — Knowledge](../02-knowledge/lesson.md): upload the style guide so
the Editor stops hallucinating house rules.
