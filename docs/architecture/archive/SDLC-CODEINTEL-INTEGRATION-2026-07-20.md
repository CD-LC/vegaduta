# Code-intel <-> workflow/agent integration + SDLC modernization (2026-07-20)

The maintainer asked to "wire code-intel to workflow and agents" and build real SDLC
modernization functionality, "think out of the box." This grounds that work
in what was actually already designed for this platform (two existing docs,
`ECOSYSTEM-SDLC-CAPABILITY-ANALYSIS.md` and `SDLC-CODEBASE-RAG-DESIGN.md`,
already laid out a staged build plan - P1 through P5 - and most of what
follows fills in specific gaps that analysis identified) rather than
inventing a parallel architecture.

## What was already true before today

- `core/codeintel` bridges Java to a standalone Python `code-intel`
  microservice with a genuinely mature feature set: semantic code search,
  an AST-derived call graph (callers/callees/impact/stats), and - this was
  the big find while researching today - a real **isolated-workspace
  execution model** (`code-intel/app/executor.py`): create a scratch
  workspace (git worktree, primary checkout never touched), apply file
  changes, run a command with a hard timeout/scrubbed env, diff the result,
  tear it down. Fail-closed unless `CODE_INTEL_ALLOW_EXEC=true`. A six-gate
  policy engine (`code-intel/app/policy.py`) mirrors this platform's own
  `core/guardrails` philosophy (deny always wins, least-agency, plan-then-
  apply).
- Only 3 of ~15 real endpoints were wired into Java `ToolCallback`s agents
  could use: `search_code`, `code_callers`, `code_impact` - all read-only,
  auto-offered to every agent once an operator sets `code-intel.enabled=true`.
- The workflow engine already has a `TOOL` node type built specifically to
  invoke one agent-resolvable tool without a full LLM turn - meaning any
  code-intel tool that's wired into `AgentToolProvider` was ALREADY usable
  from a workflow, no new engine code required. The gap was never "workflows
  can't reach code-intel," it was "code-intel barely has any Java-side tools
  to reach."

## What was built today

**1. Two more read-only tools** - `code_callees` (reverse of `code_callers`)
and `code_graph_stats` (files/symbols/edges-by-type/engines/confidence
breakdown, a real structured object, not a flat count). Same auto-offered
pattern as the existing three. All 5 read-only code-intel tools are now
available to every agent (and, transitively, every `tool`/`agent` workflow
node) whenever `code-intel.enabled=true`.

**2. The actual "modernization" capability** - workspace create/apply/run/
diff/delete, wired as new tool callbacks in `core/codeintel`. This is the
part that lets an agent (in principle) not just find a problem but fix it
and prove the fix works, inside an isolated copy, before anything touches
the real checkout. **Deliberately gated, not auto-offered**: `apply`/`run`
route through this platform's REAL human-approval gate
(`core/approval`'s `HumanApprovalGateService`/`ApprovalActionExecutorRegistry`
- new `ApprovalActionExecutor` beans for `codeintel.workspace.run` and
`codeintel.workspace.apply`, both added to `ApprovalPolicyService`'s default
sensitive-action list so a fresh install requires approval without an admin
having to configure anything first) rather than code-intel's own separate,
disconnected `/approvals` endpoint - a pending run now shows up on the
platform's normal Approvals screen, where a tenant admin actually looks.

**3. This is explicitly NOT switched on yet.** The new workspace tools are
built and reviewable (`AgentToolProvider.resolveCodeWorkspaceTools`) but
intentionally not called from the tool-resolution path any agent actually
uses. No agent can invoke them today. Turning this on for real needs, at
minimum: a per-agent opt-in flag (mirroring how knowledge-base collections
are opt-in per agent, not global), a per-agent repo allow-list (nothing
today stops a wired-in agent from targeting any repo the tenant has
registered), and a way for the approved run's actual output (stdout/exit
code) to get back to whoever approved it - today `ApprovalActionExecutor`
is `void`; a human has to separately call `diff_code_workspace` or check
code-intel's own `/audit` log to see what happened. These are real,
scoped follow-up items, not vague future work - see the code's own
`// NOT YET WIRED` comments for exactly where to pick this up.

## The example workflow

`workflow-templates/sdlc-modernization-search-and-propose.json` in this
repo root - a real, valid `WorkflowCreateRequest` JSON body (verified
against `WorkflowCreateRequest`/`WorkflowGraphDto`/`WorkflowNodeDto`'s
actual Java shape, not guessed). Trigger -> `search_code` tool node ->
an analysis agent (which can itself call `code_callers`/`code_impact`/
`code_callees`/`code_graph_stats` to reason about blast radius) -> output.
Stops at "here is the migration plan," matching exactly what's safely
wired today - it does not attempt the apply/run half, since that's not
reachable by any agent yet (see above).

To use it: create an agent first (a focused "SDLC Migration Analyst"
persona is a reasonable start - it doesn't need any special config beyond
existing normally, since the 5 read-only code-intel tools are auto-offered
once `code-intel.enabled=true`), replace both `REPLACE_WITH_A_REAL_AGENT_ID`
placeholders with its real UUID, then `POST` the JSON to
`/api/workflows` (the body this file already matches). Run it with an input
like `{"query": "usages of the deprecated moment.js library", "repo_id": ""}`
- the trigger's input has to already be `search_code`'s expected JSON shape,
since the Tool node's `argsJson` is left blank to pass it straight through.

## Honest gaps / next steps

- Retrieval quality (P2 in the ecosystem analysis - lexical/FTS fusion,
  reranking) wasn't touched today.
- The SDLC knowledge graph (P4 - linking PR/ticket/deploy/incident via the
  GitHub/Sentry MCP connectors + workflow events + audit log) wasn't
  touched today - a real, larger follow-on, not attempted here since it
  needs its own design pass, not a bolt-on.
- The 4 items in the "explicitly NOT safe/complete yet" list above are the
  concrete path to actually letting an agent run the apply/run half - worth
  treating as one deliberately-scoped follow-up task, not something to
  rush into a default-on state.

Everything referenced above is hand-verified by reading the actual source
(Java and Python both) - nothing was compiled, type-checked, or run. Same
standing caveat as every other doc from today.
