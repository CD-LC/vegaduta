# Structural audit of `core/` (2026-10-14)

A measured review of the Java core: where the structure is strong, where it is
weaker than the documents say, and what to change first. It is read-only. No
code was changed.

## Method and limits

- Static analysis of every `core/*/src/main` Java file (3,674 classes, about
  506,000 lines). Cross-module and cross-package dependencies come from
  `import` statements that resolve to a project class. Wildcard and
  fully-qualified-name uses are not counted, so every coupling number is a floor.
- Constructor and method counts come from a regular expression, not a parser.
  Treat them as indicators. The two largest services and the controller rule were checked by reading the code.
- Not run: Maven, ArchUnit, the tests or the jar boot check.
- Not audited: `web/` beyond file sizes, and `services/`, `sdk/`, `deploy/`.
- The measurement scripts were scratch files. Only the `ObjectProvider` classifier was kept in the repo (see section 6).

## 1. What is strong

| Evidence | Why it matters |
|---|---|
| No module-level dependency cycles; 45 modules with a clear foundation layer (`json`, `crypto`, `security`, `audit`, `tenancy`) that everything stands on | The Maven graph is a real DAG |
| 15 architecture tests under `core/app/.../archrules` covering module boundaries, authorization coverage, replica-store coverage, scheduled-job locking, tenant context on async hops, bean-name collisions and injectable constructors | Rules are executable, and several came from real incidents |
| Kernel-to-vendor and vendor-to-vendor violations are at 0, with empty ArchUnit freeze stores | The ratchet only moves one way |
| Only 10 mutable static fields and 227 field-injection sites across 506,000 lines | Dependencies are explicit almost everywhere |
| Flyway, file-size, secret and entity-name gates in `scripts/ci` | Persistent state and size are guarded |
| ADRs record the reasons, including the decision not to split the chat path | Future contributors can see what was rejected |

## 2. Findings

Ordered by how much each limits the goal of a reference-quality architecture.

### F1. `agents` is a god module (high)

| Measure | Value |
|---|---|
| Source files / lines | 1,075 / 178,188, which is 35% of all core code |
| Sibling modules it depends on | 27 of 44 |
| Sub-packages | 96 |
| Largest sub-packages | `tools` 61,000 lines, root package 26,000, `job` 16,000 |

It holds agent CRUD, the chat services, 338 tool files, MCP connection
management, jobs, memory, A2A, hooks, skills, a coding agent, a demo and more.
It also physically contains 47 vendor-adapter files (3,843 lines) under the
`sarvam`, `elevenlabs`, `google`, `imagegen` and `payments` packages. The
architecture rule passes because it matches package names, but Maven still
compiles those adapters inside `agents`. That is why the 8 kernel-to-vendor
Maven edges exist and why no vendor can be dropped from a build.

### F2. Cycles are hidden below the level the rule checks (high)

- ArchUnit rule (c) checks cycles between top-level packages only.
- At full package granularity, 143 of 317 packages sit inside 40 cycle groups.
- One group holds 44 of the 96 `agents` packages. `workflow` has 9, `quality` 8.
- 20 of 82 connected `agents` sub-package pairs depend on each other in both
  directions. Examples are the root package and `tools` (39 and 27 imports),
  root and `dto` (55 and 16), `job` and `tools`, and `skills` and `tools`.
- Rule (c) was cleared on 2026-10-10 by moving `tools` and `skills` under
  `agents`. The ADR says so: they shared one module because of their mutual
  dependency. The cycle was folded inside one package tree, not removed.

### F3. Several services do too much (high)

| Class | Lines | What it shows |
|---|---:|---|
| `AgentService` | 2,375 | 83 methods and 12 collaborators plus 4 config scalars. Mixes CRUD, access checks, model resolution, prompt versioning, fallback tracking, trifecta policy, autonomy tier and dozens of feature-flag getters and setters |
| `WorkflowNodeExecutor` | 2,263 | 27 collaborators. Depends directly on the email, Telegram, Teams, WhatsApp and Google Drive output services, so the workflow engine knows concrete channels |
| `WorkflowExecutionEngine` | 1,875 | |
| `DynamicMcpConnectionManager` | 1,757 | |
| `WorkflowDataOps` | 1,703 | |

26 files exceed 1,000 lines, 114 exceed 600 and 248 exceed 400. The file-size
ratchet stops growth but does not drive splitting.

### F4. Modules share persistence types instead of ports (medium)

- 172 imports of another module's JPA entity and 84 of another module's repository.
- Largest pairs: `platform` to `tenancy` (24), `agents` to `tenancy` (15),
  `app` to `agents` (11), `omnichannel` to `approval` (10), `agents` to `devices` (9).
- A module's tables are therefore its public API. Moving to the hybrid-tenancy
  or cell designs in ADR-0015 and ADR-0017 means touching every importer.

### F5. Layering rules are thin (medium)

- 47 of 300 controllers import a repository or entity directly: `omnichannel` 14,
  `agents` 11, `platform` 6, `workflow` 4.
- The only controller rule keeps services from injecting controllers. It does
  not require controllers to go through services.
- The rule text itself notes static calls into controllers exist, for example
  `AgentController.midStreamFailureMessage`.

### F6. Lazy wiring is widespread and unclassified (medium)

- `ObjectProvider<…>` appears 983 times in 261 files, and 55 optional
  `@Autowired(required = false)` setters exist.
- Some are legitimate: the jobs and worker shapes exclude controllers, and some
  beans are optional per deployment. Others are likely to be cycle breakers
  (`WorkflowNodeExecutor` takes `ObjectProvider<WorkflowService>`).
- Nobody has separated the two, so the count hides real cycles.

### F7. Test depth is uneven (medium)

Test classes against main classes, for modules with 20 or more main classes:

| Ratio | Modules |
|---|---|
| 0.5 or more | `workflow` 0.51, `app` 0.55, `chat` 0.55, `mcpserver` 0.63 |
| 0.25 to 0.5 | `agents` 0.45, `quality` 0.43, `security` 0.42, `mcp` 0.46 |
| Under 0.25 | `omnichannel` 0.23, `providers` 0.21, `payments` 0.20, `audit` 0.23, `templates` 0.17, `elevenlabs` 0.16, `codeintel` 0.15, `imagegen` 0.13, **`devices` 0.06** |

`providers` and `payments` carry money and credentials. The ratio counts classes,
not coverage, so it only shows where to look.

### F8. The jar boot check was not run by anything, and it had rotted (medium)

`scripts/ci/boot-check.sh` boots the packaged jar against Postgres (pgvector and
Apache AGE) and Redis. Nothing in the Makefile or CI ran it. CI does boot a
Spring test context through the integration profile, but not the jar, and it does
not probe the anonymous 401 routes.

Running it for this audit failed at once. Three days earlier, commit 9cd7c7db2
removed the localhost default for the token issuer and the public console URL, as
AGENTS.md rule 4 requires. The script never set the two now-mandatory values, so it
could no longer start the app. A gate that nothing runs stops working within days.

### F9. Console files are very large (medium)

`web/` has 51 TypeScript files over 1,000 lines. The largest are
`AgentWorkbench.tsx` (5,139), `tier1.ts` (4,214), `WorkflowCanvas.tsx` (3,358),
`StudioSurface.tsx` (3,350), `AgentPlayground.tsx` (3,281) and `AppShell.tsx` (2,926).

### F10. Smaller points

- `quality.stats` is a package split across the `quality` and `reliability` modules.
- `brief` and `platform` have very high outward coupling (16 and 13 sibling modules).
- The `app` module is a composition root that also holds 20 controllers and the
  global exception handlers, which import up to 56 project classes.
- Several request and response records carry 18 to 30 components (for example
  `AgentJobResponse` with 30 and `TemplateResponse` with 26), which suggests flat
  objects that should be grouped.

## 3. Principles to adopt as executable rules

1. **A module boundary is a compile boundary.** If a package belongs to a vendor,
   it is compiled in the vendor's module.
2. **No package cycles at any depth.** Enforce with a frozen baseline that only shrinks.
3. **Depend on ports, not on another module's tables.** Cross-module access goes
   through a service interface and DTOs.
4. **Controllers call services, nothing else.**
5. **Bounded collaborators.** A class with more than 12 injected collaborators
   needs a reason on record.
6. **Optional is not lazy.** `ObjectProvider` is for optional beans. Cycle
   breaking is a defect with a ticket.
7. **Every gate runs in CI**, including the boot check.
8. **Risk-weighted tests.** Money, credentials and device control get a minimum
   test floor.

## 4. Roadmap

Sizes are rough engineer-days. Any change on the chat turn path needs the
32-user benchmark on the kind stage before it is called done.

| # | Work | Size | Risk |
|---|---|---:|---|
| 1 | Run `boot-check.sh` once now, then add it to CI and the Makefile (F8) | 0.5 to 1 | Low |
| 2 | Add the new ArchUnit rules with frozen baselines: package cycles at full depth, controllers use services only, no cross-module repository imports (F2, F4, F5). Baselines record today's counts so nothing gets worse | 2 to 3 | Low |
| 3 | Add a constructor-collaborator ratchet and classify every `ObjectProvider` as optional or cycle-breaker (F3, F6) | 2 | Low |
| 4 | Move the 47 vendor-adapter files from `agents` into their vendor modules, keeping `agentspi` as the contract (F1). Removes the 8 Maven edges | 4 to 6 | Medium |
| 5 | Break the largest `agents` cycle: pull `dto`, `memory` and shared types below the root package, then `tools` below `job` and `skills` (F2) | 8 to 12 | Medium |
| 6 | Split `agents` along the seams: tools, jobs, memory and the A2A, hooks and coding features, leaving a small core (F1) | 15 to 25 | High |
| 7 | Split `AgentService` and `WorkflowNodeExecutor`. Put channel output behind one port so the engine stops naming channels (F3) | 8 to 12 | Medium |
| 8 | Replace cross-module entity use with ports, starting with `tenancy` and `platform` (F4) | 10 to 15 | Medium |
| 9 | Test floors for `payments`, `providers`, `devices` and `audit` (F7) | 5 to 8 | Low |
| 10 | Decide on ADR-0011 phase 3 once items 4 to 7 have made vendors removable | 10 to 15 | High |
| 11 | Split the console files over 2,000 lines (F9) | 8 to 12 | Low |

Items 1 to 3 cost about a week, change no behaviour and make everything after
them safe. Item 4 should come before ADR-0011 phase 3, because phase 3 only
pays off once vendors are physically separate.

## 4a. Progress (2026-10-14, same day)

Roadmap items 1 to 3 are done. Nothing was refactored, so behaviour is unchanged.

| Item | Result |
|---|---|
| 1. Boot check | Fixed the script (it now sets the issuer and public URL with reserved `.invalid` hosts). It passes: ready in 131 s, Flyway at v485, the three anonymous routes answer 401. Added `make boot-check` and an advisory step in the CI `core` job that reuses the jar `mvn verify` builds. It is advisory because it has not run on a runner yet; remove `continue-on-error` after the first green run on main |
| 2. Structure rules | `StructureRulesTest` adds four frozen rules. The baselines below can only shrink. A deliberate test removed two entries from the stores and the build failed on both, then the entries were restored |
| 3. Collaborators and lazy wiring | Constructor rule added (limit 12 collaborator parameters). It first counted every parameter, and the first merge after it went in showed why that was wrong: an upstream change added a `@Value` setting and pushed `DynamicMcpConnectionManager` from 12 to 13. Settings are not collaborators, so the rule now ignores them. `core/tools/structure-audit/object_provider_report.py` classifies every `ObjectProvider` use |

Baselines recorded by the new rules:

| Rule | Recorded violations |
|---|---:|
| Packages inside a dependency cycle (any depth) | 145 |
| Controller dependencies on a repository or JPA entity | 181 (controller and target pairs) |
| Cross-area repository dependencies | 84 |
| Classes with a constructor of more than 12 collaborator parameters (`@Value` settings do not count) | 25 |

Classification of the 989 `ObjectProvider` uses:

| Category | Uses | Meaning |
|---|---:|---|
| conditional | 585 | Target is a conditional bean, or the owner checks availability. Legitimate |
| external | 170 | Target is a Spring or third-party type. Legitimate |
| cycle-direct | 122 | Target reaches the owner in at most two hops. Likely cycle breakers; start here (agents 72, workflow 24) |
| cycle-indirect | 103 | Longer path. Mostly noise inside the `agents` cluster |
| unclassified | 9 | Neither optional nor cyclic. Probably unnecessary laziness |

The classifier works from imports and same-package names, so these are
candidates. Run it with `--list cycle-direct` for the full list.

## 4b. Roadmap status (checked 2026-10-21, v0.1.9)

| Item | State | Evidence |
|---|---|---|
| 1. Boot check | Done, still advisory in CI | `make boot-check`; the CI step still has `continue-on-error: true` |
| 4. Vendor adapters out of `agents` | Done (2026-10-16) | [ADR-0011](ADR-0011-provider-plugins.md) status: 0 kernel to vendor Maven edges; vendor tools in their modules behind `agentspi` |
| 6. Split `agents` | Done (2026-10-16) | [ADR-0023](ADR-0023-agents-module-decomposition.md): kernel plus nine satellite modules; `agents-turn` is over the 300-file cap again (307) |
| 7. Split `AgentService` and `WorkflowNodeExecutor` | Split done; the channel-output port was not checked | `AgentService` 664 lines, `WorkflowNodeExecutor` 527 lines with one `NodeHandler` per node type ([overview](overview.md#internal-seams-of-the-core)) |
| 11. Console files over 2,000 lines | Done | the largest hand-written file under `web/app` is 707 lines (`AssistantPane.tsx`) |
| 5, 8, 9, 10 | Not re-checked | |

## 5. What would change these conclusions

- A parser-based tool such as jdeps or ArchUnit would replace the import counts.
  Expect the real numbers to be higher.
- Some of the 143 packages in cycles may be one deliberate cluster. Items 2 and
  3 will show which.
- Services, SDKs, deployment and the Python sidecars need their own pass.

## 6. Repeatability

The measurement scripts were scratch files. The new ArchUnit rules now enforce
the cycle, layering, repository-access and constructor findings in CI, and
`core/tools/structure-audit/object_provider_report.py` is kept for the lazy-wiring
findings (`--check N` can serve as a ratchet). The module-level size and coupling
tables in section 2 are not yet reproducible from the repo.