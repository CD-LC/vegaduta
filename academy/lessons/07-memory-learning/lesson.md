# Ep 7 — Memory & learning: the newsroom remembers

**Length** ~5 min · **Level** everyone · **Renders to** `ep07-memory-learning.mp4`

A newspaper that relearns its readers every morning isn't a newspaper.
This episode covers the four places VegaDūta retains and improves:
`/memory` for notes and learned facts, `/skills` + `/skills/library` for
packaged expertise, and `/agents/learning` for the feedback loop that
tunes each agent over real runs.

## The scenes

| Step | Click-path | What happens |
|---|---|---|
| Your notes | `/memory` → **Your notes** → Persona textarea → **Save** | Writes the Ledger persona note; counters show `X / 1200` per field |
| Shared memory | `/memory` → **Assistants** tab → per-assistant switch | Notes only reach chats where shared memory is on — it's an assistant-level setting |
| Learned facts | `/memory` → **Learned facts** → **What is remembered** / **Most used facts** / **Where did this come from?** | Facts saved during conversations, with use counts and provenance back to the source chat |
| Write a fact | **Learned facts** → "Add a memory" textarea → **Add memory** | Saves `Ledger subscribers care most about the markets desk…` as a fact for all shared-memory assistants |
| Skills | `/skills` → "Things to try" cards → **New skill** | Packaged instructions an agent loads only when a task matches; agents see name + description until then |
| Review | `/skills/library` → **Pending review** tab | Skills agents drafted from experience arrive here — scanned and checked, inert until approved |
| Learning | `/agents/learning` → **Feedback signals** / **Model routing arms** / **Policy controls** | Thumbs up/down feed a reward trend; a bandit policy (Thompson sampling or UCB1) explores model, reasoning-depth and prompt-variant arms |

## Mental model

- **Notes are what you declare; facts are what agents observe.** Persona,
  About me, Current work and project notes are hand-written; learned facts
  accumulate from real conversations — both are yours to read, edit and
  delete.
- **Nothing is shared by default.** Shared memory is off per assistant
  until you flip the switch — an assistant with it on reads your notes and
  saves to the workspace's shared fact pool.
- **Provenance is built in.** Every fact links back to the conversation
  that produced it; curation is first-class, not an afterthought.
- **Skills are draftable but gated.** Experience can produce new skills,
  but they sit in Pending review — visible, checked, inert — until a human
  approves them.
- **Learning is a bandit, not a rewrite.** The policy shifts traffic
  toward better arms (models, reasoning depths, prompt variants) at a
  controlled exploration rate — feedback without feedback loops run amok.

## After watching

Continue to [Ep 8 — Quality](../INDEX.md), where we grade the paper
before it prints: golden examples, scored runs and turn replay.
