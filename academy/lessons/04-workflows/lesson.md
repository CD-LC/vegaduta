# Ep 4 — Workflows: the production line

**Length** ~5 min · **Level** builder · **Renders to** `ep04-workflows.mp4`

A workflow is the newspaper's production line: a graph of nodes — fetch,
classify, call an agent, wait on a human, publish — wired port to port and
set off by a trigger. Agents think; workflows make sure the thinking
happens in the right order, on schedule, with a record of every step.

## The scenes

| Screen | What it is | The Ledger |
|---|---|---|
| `/workflows` | List, stats, and the describe-it box | The production line's front door |
| describe box | Plain words → draft canvas | "Every weekday at 6am, gather sources, draft, fact-check, wait for sign-off, publish" |
| designer (`/workflows/<id>`) | Canvas + node palette + settings | The edition pipeline, left to right |
| Trigger node | Exactly one per workflow — schedule, webhook or channel | Weekday-morning press time |
| Agent node | Calls a staff agent; retries + approval options | The Editor-in-Chief drafts the front page |
| Human Approval node | Pauses the run into the Approvals queue | Nobody prints without a human yes |
| Validate / Run now | Graph check, then a live run | Press check before the edition |
| Runs / flight log | Per-node timing, replayable | Find the step that ate the morning |

## Mental model

- **Trigger + nodes + edges.** One trigger starts it; every node does one
  job; edges are the order of the desk.
- **Drafts are inert.** Nothing runs or sends until you hit *Activate* —
  iterate freely.
- **Approval is a node, not an afterthought.** Put the human gate exactly
  where the risk is — ours sits right before publish.
- **Every run leaves a flight recorder.** Timing per node, replayable —
  debugging is replay, not guesswork.

## After watching

Continue to [Ep 5 — Approvals & channels](../05-approvals-channels/lesson.md),
where the paused run lands: the human decision queue and the channels that
deliver the edition.
