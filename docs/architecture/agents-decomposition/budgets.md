# Agents decomposition: budgets and spend caps

Status: done (first cut). Part of splitting the `agents` module (see ADR-0023, written
separately). This page records what moved into `core/agents-budget`, why the edge points the
way it does, and what was left behind on purpose.

## What moved

New Maven module `core/agents-budget` (artifactId `agents-budget`, same `groupId` and version as
the rest of the core reactor). It holds 9 main classes and 3 test classes that used to live in
`core/agents`.

| Class | Old package | New package |
|---|---|---|
| `PerUserBudgetService` | `com.agenticai.core.agents` | `com.agenticai.core.agents.budget` |
| `PerUserBudgetExhaustedException` | `com.agenticai.core.agents` | `com.agenticai.core.agents.budget` |
| `PerUserBudgetTrackingAdvisor` | `com.agenticai.core.agents` | `com.agenticai.core.agents.budget` |
| `PerUserBudgetTtlRepair` | `com.agenticai.core.agents` | `com.agenticai.core.agents.budget` |
| `TurnReservationGuards` | `com.agenticai.core.agents` | `com.agenticai.core.agents.budget` |
| `BudgetPolicy` (JPA `@Embeddable` of `Agent`) | `com.agenticai.core.agents` | `com.agenticai.core.agents.budget` |
| `SpendCapService`, `SpendCapReachedException`, `SpendCapAlertSink` | `com.agenticai.core.agents.spendcap` | unchanged |

Tests that moved with them: `PerUserBudgetServiceTest`, `PerUserBudgetTrackingAdvisorTest`
(now in `...agents.budget`) and `SpendCapServiceTest` (still in `...agents.spendcap`).

`TurnReservationGuards` is included because `PerUserBudgetTrackingAdvisor` needs it to release a
turn's spend reservation exactly once across the hosted fallback chain. It is a 66-line class
with two `AtomicBoolean`s and no other dependency.

The root package of `agents` drops from 154 to 148 files.

## Dependency direction

```
agents ──> agents-budget ──> chat, providers, security, tenancy, audit
```

`agents-budget` does not depend on `agents`. Budgets are a lower layer: the turn path in
`agents` (`FreeTierChatClientService`, `AgentChatService`, `HostedChainLink`,
`ChatTurnDispatcher`) calls them, and nothing in the budget code needs an agent type. No inverted
interfaces were needed. The only references from the moved code to `agents` classes were
javadoc `{@link}` tags, which are now `{@code}` so the module compiles and documents without
`agents` on the classpath.

Because the Maven edge points one way, ArchUnit's package-cycle rule cannot find a cycle through
`agents.budget` or `agents.spendcap`. The frozen violation store
(`core/app/src/test/resources/archunit_store`) did not change: no frozen entry named a moved
class.

## What did not change

- **Bean names.** Simple class names are unchanged, so the default bean names
  (`perUserBudgetService`, `spendCapService`) are the same. `bean-name-collision.sh` passes.
- **JPA.** `BudgetPolicy` keeps its columns; only its Java package changed. Embeddables are found
  through the `Agent` entity that embeds them, and the application's component scan root
  (`com.agenticai.core`) covers the new package anyway. No migration.
- **REST.** No controller moved, so no path changed.
- **Configuration.** Same property keys (`app.per-user-budget.config-cache-ms`,
  `app.platform-llm.premium-multiplier`, `app.platform-llm.unlimited-multiplier`).
- **Hot path (AGENTS.md rule 6).** Same classes, same in-process calls, same threads. The split
  is a compile-time boundary only; no network hop and no new blocking call.
- **Tenancy (rule 12).** `SpendCapService`'s alert executor keeps its entry in
  `core/app/src/test/resources/archrules/async-hop-allowlist.txt` under the unchanged
  fully qualified name.

## Wiring

- `core/pom.xml`: `<module>agents-budget</module>` before `agents`, plus a
  `dependencyManagement` entry.
- `core/agents/pom.xml`: depends on `agents-budget`.
- `core/app`, `core/app-jobs`, `core/app-worker`: declare `agents-budget` directly (they already
  had it transitively through `agents`).
- `core/Dockerfile`: `COPY agents-budget/pom.xml agents-budget/` in the dependency-cache layer
  (checked by `scripts/ci/dockerfile-poms.sh`).
- `docs/architecture/core-module-graph.md`: regenerated with
  `core/tools/module-graph/gen_module_graph.py`.

Callers outside `agents` that imported the moved root classes (`app`, `platform`, `mcpserver`,
`workflow`, one javadoc in `tenancy`) now import `com.agenticai.core.agents.budget.*`. Callers of
`spendcap` needed no change.

## Left in `agents`, and why

| Class | Why it stayed | What it would take to move |
|---|---|---|
| `AgentRunBudgetService`, `AgentRunBudgetController` (`/api/agents/{agentId}/run-budget`) | They read and write `Agent.runBudgetUsd` through `AgentService` (access checks, edit lock) and use `collab.RunBudget` / `collab.RunBudgetCaps` and `dto.AgentRunBudgetResponse`. | Move `RunBudget`, `RunBudgetCaps` and `AgentRunBudgetResponse` into `agents-budget` (they are pure; about 12 importers in `agents.tools`, `agents.collab` and `agents.appbuilder` change one import line each), then put a small port in `agents-budget` (`requireViewer`, `read`, `write` of the agent's cap) implemented by an adapter over `AgentService` in `agents`. |
| `FreeTierUsageLimiterService`, `FreeTierTokenTrackingAdvisor` | Free-tier message and token quotas, a separate ledger from per-user spend budgets. They only reference `agents` types in javadoc, so they could move next. | A straight move plus import updates in their callers. |
| `DailyQuotaResetScheduler` | Uses the `PlatformQuotaResetLog` entity and repository, which stay with `agents` for now. | Move the entity and repository with it, or keep the scheduler in `agents` as the composition point. |

## Verification done

- Full core reactor compile (`-DskipTests package` from `core/`, which also compiles tests).
- Moved tests: `PerUserBudgetServiceTest` (41), `PerUserBudgetTrackingAdvisorTest` (7) and
  `SpendCapServiceTest` (17) pass in `agents-budget`.
- Budget-adjacent tests in `agents`, `platform`, `mcpserver` and `app` pass.
- `StructureRulesTest`, `ModuleBoundaryRulesTest` and `AsyncHopTenantScopeRulesTest` pass with
  an unchanged store.
- `bean-name-collision.sh`, `entity-name-lint.sh`, `spring-constructor-lint.sh`,
  `dockerfile-poms.sh` and `check-file-size.sh` pass.
- Not done: booting the real jar (`scripts/ci/boot-check.sh`) and a load run. Run the boot
  check after merging, since `BudgetPolicy` changed package inside a JPA entity.
