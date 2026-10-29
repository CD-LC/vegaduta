# Ep 10 — Ship it: schedules & long-running work

**Length** ~3.5 min · **Level** everyone · **Renders to** `ep10-ship.mp4`

The finale: the newsroom works without being spoken to. Scheduled tasks
run prompts on a clock; quick tasks grind one-off jobs in the background;
both report back into the same console surfaces you already know.

## Coverage

- `/scheduled` — the task list, quota (`N of 25`), next-run preview
- `/scheduled` example gallery — Morning briefing, Inbox digest, Industry
  news, Weekly review, Competitor watch…
- `/quick-task` — one-shot background instructions
- `/assistant` — where long-running threads live

## Click path

| Step | Where | What happens |
|---|---|---|
| Task list | `/scheduled` | Real task on screen: **Morning briefing**, every weekday 08:00, next runs listed |
| From an example | `/scheduled` → "Inbox digest" | Prefilled form — prompt, agent, days, time, delivery |
| Quick task | `/quick-task` | One instruction → background run → result back in the app |
| Long threads | `/assistant` | Background work surfaces in the same thread/turn/approval machinery |

## Mental model

- **Scheduled** = the same prompt, on a clock, to you (or a channel —
  wire email/WhatsApp from Ep 5 and editions deliver themselves).
- **Quick task** = one instruction, run now, in the background.
- Both still produce real turns — inspectable in `/agents/turns`,
  approvable in `/admin/approvals`, metered in `/usage`.

## After watching

Series complete. The Series-B deep-dive library (see
[INDEX](../INDEX.md)) takes each console surface one at a time.
