# Code Intelligence — web page

A page in the `web` module for the code-intel microservice, merged into the
main app shell and nav rail (direct feedback: "Merge code-intell UI to web
and ensure code intell is rightly plugged and working").

```
web/app/code-intel/
├── page.tsx   # the Code Intelligence console (Repos / Ask the Code / Search / Graph / Execute / Guardrails)
└── lib.ts     # co-located fetch client -> code-intel service (uses the app's Keycloak token)
```

- Reachable at **`/code-intel`**, and now also from the left rail (the
  `GitBranch` icon, labelled `CODE` — `AppShell.tsx`'s `RAIL_ITEMS`, `railCode`
  key in `messages/en.json`'s `nav` namespace).
- Still deliberately **not merged into `web/app/lib/api.ts`**: that client
  targets core (`localhost:8080`); this page talks to the separate
  code-intel microservice on its own origin/port, so its client stays
  co-located here. That's a scoping choice, not a "not really merged" flag —
  the page itself is fully part of the product surface now, wrapped in the
  same `AppShell` and using the same `--ax-*`/`--hud-*` theme tokens as
  every other screen.

## One env var

Point the page at the service (defaults to `http://localhost:8090`, already
set in `.env.local.example`):

```
# web/.env.local
NEXT_PUBLIC_CODE_INTEL_URL=http://localhost:8090
```

## How the page reaches the service (routed through core by default)

By default the page talks to code-intel **through** core's `/api/code-intel`
proxy (`CodeIntelController`), using the app's normal Keycloak session token
against core (`NEXT_PUBLIC_API_BASE_URL`, default `http://localhost:8080`).
This is the important change: a human-initiated `apply`/`run` from the
Execute tab is now filed on the platform's **one central Approvals screen**
via `HumanApprovalGateService` — the exact same request an agent's
`apply_code_changes`/`run_in_code_workspace` tool call creates — instead of
hitting the service on its own origin outside `core/approval`. So in
core-proxy mode the Execute tab shows a **"pending approval"** notice for a
write/run, not an inline result; a tenant-admin approves it on the Approvals
screen and it executes automatically.

To turn it on end to end: enable the bridge on core
(`CODE_INTEL_ENABLED=true`, `CODE_INTEL_INTERNAL_TOKEN=…`) and run code-intel
with `CODE_INTEL_ALLOW_EXEC=true` (for the Execute tab / autonomous resolve).

### Direct mode (escape hatch, not the default)

Set `NEXT_PUBLIC_CODE_INTEL_URL` to talk to the service **directly** (e.g. a
deployment that runs code-intel standalone with no core). Then run it in
keycloak mode with CORS allowing the web origin — `deploy/docker-compose.yml`'s
`code-intel` service already defaults to exactly this:

```
CODE_INTEL_AUTH_MODE=keycloak
CODE_INTEL_KEYCLOAK_ISSUER=http://localhost:8180/realms/agentic-ai
CODE_INTEL_CORS_ORIGINS=http://localhost:3000
CODE_INTEL_ALLOW_EXEC=true
```

Direct mode bypasses the central approval routing — reads/search/graph behave
identically, but a write/execute is then governed only by code-intel's own
guardrail policy, and the Execute tab shows the inline run result again.
code-intel validates the token against the same realm JWKS and reads the
`tenant_id` claim — identical multi-tenancy to core, no separate login.

## The page

Six tabs, a repo picker in the top-right:

- **Repositories** — register a git URL or local path, index + build graph,
  see per-repo status, delete.
- **Ask the Code** — grounded, cited Q&A over the indexed codebase (the
  `/knowledge/query` bridge) — every answer is a real passage with a
  citation, never a generated guess.
- **Search** — semantic code search over the selected repo.
- **Code Graph** — callers / callees / impact (blast radius) for a symbol.
- **Execute & Rework** — create an isolated workspace, run a build/test
  command, see structured pass/fail, apply a fixed file, re-run, view the
  final diff — plus an **Autonomous resolve** panel that drives that same
  create → run → fix → run → diff loop unattended, given a goal and a test
  command (`POST /repos/{repo_id}/agent/resolve`). Needs
  `CODE_INTEL_ALLOW_EXEC=true` and `ANTHROPIC_API_KEY` set server-side for
  the autonomous path.
- **Guardrails** — policy (autonomy mode, exec/write toggles, caps),
  pending approvals queue, audit trail.

To remove the feature entirely: delete the `web/app/code-intel/` folder,
the rail entry in `AppShell.tsx`, and the `railCode` key in
`messages/en.json` — no other footprint.
