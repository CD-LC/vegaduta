# Archived design notes

These are **historical design notes**, kept because source-code comments cite
them by file name and because they record why some subsystems look the way they
do. They are not maintained.

- They were written while the platform was developed privately, before this
  repository was published. They mention internal plans, audits and
  coordination documents that are **not** part of this repository, and some
  describe work that was never built or was later changed.
- Dates in the file names are the date the note was written, not a review date.
- **Where a note disagrees with the code, the code wins.** For the current
  design, start with [../overview.md](../overview.md) and the ADRs in
  [../](../).

| Note | Topic | Status when archived |
|---|---|---|
| [AGENT-APPROVAL-OVER-CHANNELS-DESIGN.md](AGENT-APPROVAL-OVER-CHANNELS-DESIGN.md) | approving a coding agent's pending action from a messaging channel | design only |
| [AGENT-RL-METHODOLOGY-AND-SAFETY.md](AGENT-RL-METHODOLOGY-AND-SAFETY.md) | reward design and safety for `core/rl` and `services/rl-trainer` | partly implemented |
| [APP-BUILDER-BYO-REPO-CONTRACT-2026-07-30.md](APP-BUILDER-BYO-REPO-CONTRACT-2026-07-30.md) | App Builder writing to the tenant's own GitHub repository | implemented in `services/app-builder` |
| [Agentic-AI-Platform-Gateway-MultiAgent-OCR-IVR-Research.md](Agentic-AI-Platform-Gateway-MultiAgent-OCR-IVR-Research.md) | LLM gateway, sandboxing and OCR/IVR research | research |
| [Agentic-AI-Platform-Knowledge-Stage-F-Build-Plan.md](Agentic-AI-Platform-Knowledge-Stage-F-Build-Plan.md) | hierarchical knowledge base build plan | superseded by `core/knowledge` |
| [BLUETOOTH-DEVICE-BRIDGE-DESIGN.md](BLUETOOTH-DEVICE-BRIDGE-DESIGN.md) | browser Bluetooth device bridge | partly implemented (`web/app/lib/edge`) |
| [COMMUNITY-TEMPLATES-CONTRACT-2026-07-30.md](COMMUNITY-TEMPLATES-CONTRACT-2026-07-30.md) | shared agent/workflow templates with moderation | implemented in `core/templates` |
| [DESIGN-SYSTEM-CONTRACT-2026-07-29.md](DESIGN-SYSTEM-CONTRACT-2026-07-29.md) | console colour, elevation and state tokens | implemented in `web/` |
| [GENERIC-PROVIDER-CAPABILITY-REGISTRY-2026-08-09.md](GENERIC-PROVIDER-CAPABILITY-REGISTRY-2026-08-09.md) | provider/capability registry | phase 1 implemented; see [ADR-0011](../ADR-0011-provider-plugins.md) |
| [LIVE-VISION-SENSE-RESEARCH-2026-08-05.md](LIVE-VISION-SENSE-RESEARCH-2026-08-05.md) | live camera input for agents | research; parts implemented |
| [MULTI-AGENT-GRAPH-MEMORY-RESEARCH-2026-07-31.md](MULTI-AGENT-GRAPH-MEMORY-RESEARCH-2026-07-31.md) | graph memory on Apache AGE | foundation implemented in `core/graphmemory` |
| [PLATFORM-ADMIN-GOVERNANCE-AND-CONTROLS.md](PLATFORM-ADMIN-GOVERNANCE-AND-CONTROLS.md) | budgets, spend reports, moderation and admin controls | phased roadmap; partly implemented |
| [SDLC-CODEBASE-RAG-DESIGN.md](SDLC-CODEBASE-RAG-DESIGN.md) | code intelligence over repositories | implemented in `services/code-intel` |
| [SDLC-CODEINTEL-INTEGRATION-2026-07-20.md](SDLC-CODEINTEL-INTEGRATION-2026-07-20.md) | wiring code intelligence into agents and workflows | implemented |

Some older code comments cite these notes as `docs/<NAME>.md` or
`docs/architecture/<NAME>.md`; they now live in this folder under the same name.
