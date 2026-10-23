# agent-bundles

Ready-to-import agents, one JSON file each. Import with
`POST /api/agent-bundles/import`; preview first with `/preview`. Full guide:
[docs/agent-bundles.md](../../docs/agent-bundles.md).

| File | What it does | Tools |
|---|---|---|
| [`personal-assistant.json`](personal-assistant.json) | Web lookups, remembers facts, writes documents | web, memory, documents |
| [`daily-briefing.json`](daily-briefing.json) | One-page morning brief from sources you give it | web, memory, documents |
| [`research-analyst.json`](research-analyst.json) | Answers from pages it read, cites each, lists gaps | web, memory, documents |
| [`meeting-notes-to-actions.json`](meeting-notes-to-actions.json) | Decisions, action items and open questions from raw notes | documents, memory |

All of them are `SUPERVISED` and need no account, so they run on a free local
model. `SUPERVISED` gates every tool call: each one waits for an approval in the
approvals inbox before it runs, so expect an approval request per tool call until
you change the agent to `SEMI_AUTONOMOUS`. Check any bundle offline: `python3 examples/agent-bundles/validate.py`.
The server test `TemplateBundleServiceTest` imports every file here, so an
invalid example fails CI, and so does a value that is not a real setting
(for example `reasoningDepth: "BALANCED"`; the depths are FAST, CAREFUL, RIGOROUS,
PLANNED).
