# Contributing to VegaDūta

Thanks for helping. This guide covers licensing and sign-off, the development
setup for each component, how a change gets merged, and the checks every
change must pass. AI coding agents should also read [AGENTS.md](AGENTS.md).

## Quick path

```sh
git clone https://github.com/CD-LC/vegaduta.git && cd vegaduta
./scripts/vegaduta up --lite --build        # run Lite: 5 containers built from your checkout (first build is slow)
cd core && mvn -B -ntp -DskipTests package  # build the Java reactor (JDK 21+, Maven 3.9+)
mvn -B -ntp -pl agents test                 # test one module
cd .. && make gates                         # the static checks (same scripts as the CI static-gates workflow)
```

Pick the part you are changing in [Development setup](#development-setup). `--build` builds
every image from your checkout instead of pulling the published release images.

## License and DCO sign-off

VegaDūta is licensed under the [Business Source License 1.1](LICENSE)
(converting to Apache-2.0 on each version's change date). Contributions are
accepted under the same license through the [Developer Certificate of Origin](https://developercertificate.org/)
(DCO). Sign off every commit to certify that you wrote it or otherwise have the
right to submit it under the project license:

```sh
git commit -s -m "fix(tenancy): reject schema names with a leading digit"
```

This adds a `Signed-off-by: Your Name <you@example.com>` trailer. The sign-off
must use the same email address as the commit author. The `DCO sign-off` job in
CI checks every commit of a pull request, and it is part of the required
`CI OK` check, so pull requests with unsigned commits cannot be merged. To fix
a branch: `git rebase --signoff main` and force-push. The DCO applies from the
first public commit; commits imported before the repository was published
predate it. Dependabot and GitHub Actions bot commits on the bots' own pull
requests are skipped (they never sign off); a human commit pushed onto such a
branch is still checked. The check is `scripts/ci/dco-check.sh`, tested by
`scripts/ci/test-dco-check.sh`; see [Dependabot pull requests](docs/maintainers/dependabot-prs.md).

## Development setup

The fastest start is the dev container (`.devcontainer/`, works with VS Code
and GitHub Codespaces): it installs the toolchains below, generates
`deploy/compose/.env` and starts Postgres, Valkey and Keycloak.

### Toolchains

| Tool | Version | Used by |
|---|---|---|
| JDK | **21 or newer** (the pom targets release 21; CI and the images use Temurin 21; JDK 24 also works) | `core/` |
| Maven | 3.9+ | `core/` |
| Node.js | 22 (CI); 20.9+ works | `web/`, `tests/e2e`, Node services |
| Python | 3.12 (matches the service images) | `services/*` |
| Docker + Compose v2 | recent | running the stack, Postgres for core's context tests |
| gitleaks | 8.18+ | secret scan in `make gates` and the pre-push hook |
| helm, kubeconform | helm 3.x | `make helm-lint` (kubeconform falls back to Docker) |

### Infrastructure for local development

```sh
bash scripts/dev-up.sh          # writes deploy/compose/.env, starts postgres + valkey + keycloak
make hooks                      # pre-push hook: runs the static gates before you push
```

`deploy/compose/.env` holds every generated secret. Keep it out of git (it is
ignored) and reuse the **same** `APP_ENCRYPTION_KEY` for every process that
talks to the same database (see [AGENTS.md](AGENTS.md)).

### core/ (Java 21, Spring Boot, Maven reactor)

```sh
cd core
mvn -B -ntp -DskipTests package         # compile + package every module
mvn -B -ntp -pl agents test             # one module's tests
mvn -pl app -am spring-boot:run         # run the API on :8080
```

- **Run one Maven build at a time.** Parallel builds (other worktrees, IDE
  background builds) share `~/.m2` and can read each other's half-written jars.
- `-pl <module> -am` builds the module's dependencies, not the modules that
  depend on it. After changing a signature used elsewhere, compile the whole
  reactor (`mvn -DskipTests package`) before calling it done.
- `CoreApplicationTests` boots the full Spring context against Postgres on
  `DB_HOST:DB_PORT` (default `localhost:5432`) and needs the pgvector + Apache
  AGE image from `deploy/postgres`; it skips when no database is reachable.
  CI runs it against a fresh container. Don't point it at a database you care about.
- Environment for `spring-boot:run` (profiles, keys) is listed at the top of
  `scripts/dev-up.sh` and in [docs/configuration.md](docs/configuration.md).

Integration tests (`*IT` classes, Testcontainers, need Docker) are outside the
default run. Run them with the `it` profile, for example
`mvn -P it -pl app -am verify -Dtest=NONE -Dsurefire.failIfNoSpecifiedTests=false -Dit.test=MigrationsIT`;
each class is skipped, not failed, when no Docker daemon is reachable. Details:
[docs/engineering/INTEGRATION-TESTS.md](docs/engineering/INTEGRATION-TESTS.md).

**Java formatting** is google-java-format in AOSP style (4-space indent, ASCII-sorted
ungrouped imports), enforced for changed files by Spotless: `mvn spotless:check` or
`mvn spotless:apply` from `core/` (it compares against `origin/main`; pass
`-Dspotless.ratchet.ref=main` if you have no `origin`). If you must change a file
that is not yet formatted, commit the formatting alone first ("style-first":
`style(module): format Foo.java`, no behavior change), then your change in a
second commit, so review shows only the real diff. Do not reformat unrelated code.

**File size ratchet:** `sh scripts/ci/check-file-size.sh` fails on any source file
over the 700-line hard cap, or within 5% of it (over 665 lines), that is not in
`scripts/ci/file-size-baseline.txt`. The baseline only shrinks: a baselined file may
not grow, and a change may not add a path to the baseline (checked against the
merge-base with `origin/main`). Split code out instead of growing a large file; after a
split, `sh scripts/ci/check-file-size.sh --update` lowers or drops the entry.

### web/ (Next.js; includes the merged platform-admin section under app/platform-admin/)

```sh
cd web
npm ci
npm run dev       # :3000 (platform admin at /platform-admin)
npm run lint && npm run typecheck && npm test
```

Browser-facing URLs come from `NEXT_PUBLIC_*` variables. Never hardcode
`localhost` in code that ships to the browser.

`web/next-env.d.ts` is generated by `next dev` and `next build` (they write different
paths into it) and is gitignored, so a build leaves the tree clean. Do not commit it.

### services/ (Python)

```sh
cd services/sidecar
python -m venv .venv && . .venv/bin/activate
pip install torch --index-url https://download.pytorch.org/whl/cpu   # CPU wheels, optional speed-up
pip install -r requirements.txt -r requirements-dev.txt
python -m pytest tests/ -q
```

`services/code-intel` tests are plain scripts, not pytest: run
`python -m tests.<module>` from `services/code-intel` (see `make test-python`).

### Full stack in containers

```sh
./scripts/vegaduta up --lite --build      # 5 containers, built from your checkout
./scripts/vegaduta up --full --build --profile temporal
./scripts/vegaduta logs core
./scripts/vegaduta down
```

## Making a change

1. For anything larger than a bug fix, open an issue (or a Discussion) first so
   the approach can be agreed before you write it.
2. Branch from `main`, keep the change focused, and include tests.
3. Commits are signed off (`git commit -s`) and their messages follow [Conventional Commits](https://www.conventionalcommits.org/):
   `type(scope): summary`, for example `feat(knowledge): add web-crawl connector`,
   `fix(workflow): ...`, `docs: ...`. Types: `feat`, `fix`, `docs`, `refactor`,
   `perf`, `test`, `build`, `ci`, `chore`. Mark breaking changes with `!`
   (`feat(api)!: ...`) and a `BREAKING CHANGE:` footer.
4. Add a line under `## [Unreleased]` in [CHANGELOG.md](CHANGELOG.md) for
   anything a user or operator would notice.
5. Open a pull request with the template and state exactly how you verified the
   change. "Compiled" is not "tested", and "tested" is not "ran it on a stack".

A maintainer reviews every pull request. Two checks must be green before
merge, and they are the only ones the branch ruleset is meant to require (the
ruleset is described in `docs/maintainers/branch-protection.md`; until it is
configured on GitHub, the maintainer enforces this by hand):

- **`CI OK`** aggregates everything in `ci.yml`: the component builds and tests
  that the change's paths trigger, the static gates and secret scan
  (`static-gates.yml`), the DCO sign-off check, dependency review and the docs
  link check. Jobs skipped by the path filters count as passed.
- **`CodeQL OK`** aggregates the CodeQL analysis for Java, JavaScript/TypeScript
  and Python. It runs on every pull request, including docs-only ones.

Advisory jobs (marked "advisory" in their names) report but never block. See
[docs/maintainers/branch-protection.md](docs/maintainers/branch-protection.md).

## Checks (`make gates`)

The same scripts run in the CI static-gates workflow (`.github/workflows/static-gates.yml`, called
by `ci.yml` on every pull request) and, if you install it with `make hooks`, in a pre-push hook.
Run `make gates` locally to catch them before pushing.

| Command | What it guards |
|---|---|
| `make gates` | everything below |
| `bash scripts/ci/flyway-lint.sh` | migrations: no edits to applied files, destructive DDL marked, no duplicate versions |
| `bash scripts/ci/entity-name-lint.sh` | entity, table, bean and controller base-path collisions |
| `bash scripts/ci/skip-expiry.sh` | test skips past their `expires:` date |
| `sh scripts/ci/check-file-size.sh` | ratchet: hard cap 700 lines; no new source file over 665 (within 5% of the cap); baseline only shrinks |
| `sh scripts/ci/check-module-size.sh` | ratchet (ADR-0023): at most 300 main `.java` files per `core/*` module and no classes directly in `com.agenticai.core.agents`, unless in `scripts/ci/module-size-baseline.txt`; baseline only shrinks (`--update` lowers it after a split) |
| `bash scripts/ci/docs-link-check.sh` | relative links and anchors in `README.md`, `CONTRIBUTING.md` and `docs/` (offline; `ci.yml` also runs lychee, which has its own rules) |
| `python3 scripts/docs/test_gen_shapes.py` | the generator's own fixture tests (YAML reader, reason text, class annotations) |
| `python3 scripts/docs/gen-shapes.py --check` | the ownership tables in `docs/deploy/DEPLOYMENT-SHAPES.md` match the `@Scheduled` sources and shape profiles (drop `--check` to regenerate) |
| `bash scripts/ci/secret-scan.sh` | gitleaks over your new commits |
| `make spring-wiring` | core/app's `InjectableConstructorTest` and `UniqueBeanNamesTest`: a bean with several constructors and none `@Autowired`, duplicate Spring bean or JPA entity names across modules (separate from `make gates` because it needs Maven; `mvn verify` runs them) |
| `make compose-validate` / `make helm-lint` | deploy artifacts render and validate (separate from `make gates`; the `ci.yml` compose and helm jobs run them) |
| `make lint` / `make test` | component linters and tests |

### Flyway migrations

Migrations live in `core/app/src/main/resources/db/migration/controlplane/` and
`.../tenant/`, and the two folders **share one version namespace**: `V412` in
one folder means `V412` is taken in the other. A duplicate is a boot failure
for every core instance. Pick the next number free across both folders.

A migration that has been released must never be edited, renamed or
renumbered; add a new one. Destructive DDL (`DROP TABLE`, `DROP COLUMN`,
tightening a column to `NOT NULL`) needs a marker comment:

```sql
-- gated:contract
DROP TABLE some_deprecated_table;
```

### Test skips

A skip that tracks a known gap must carry an expiry, after which CI fails until
it is fixed or deliberately re-dated:

```java
@Disabled("Flaky under load, see #91, expires:2026-12-01")
```

### Secrets

Never commit real keys, tokens or passwords, including in `.env*.example`
files, fixtures or docs. Fake credentials for tests belong under `src/test/` or
in `*.test.*` / `*.spec.*` files, which `.gitleaks.toml` allows.

## Good first contributions

Small, real gaps taken from [ROADMAP.md](ROADMAP.md) and
[the cloud portability ADR](docs/architecture/ADR-cloud-portability.md). Comment on (or
open) an issue before starting so work is not duplicated; maintainers label starter
issues `good first issue`.

1. **A runnable example per extension point.** ROADMAP item. `examples/` has workflow
   templates, two MCP servers, agent bundles and the SDK examples; add a minimal custom skill
   (`SKILL.md`) example that follows
   [docs/extending.md](docs/extending.md#3-add-a-skill-skillmd).
2. **Slim sidecar image.** Document extraction without PyTorch: split the
   requirements in `services/sidecar/requirements.txt` and add an image variant to
   `services/sidecar/Dockerfile`; measure the size difference and state it.
3. **Promote an advisory Python job to blocking.** Pick one `advisory` job in
   `.github/workflows/ci.yml`, fix its findings (`ruff check services/<name>`) and drop the label.
4. **Unit tests for provider validators.** `core/providers/.../validation` has no test for
   the DeepSeek, Groq, Mistral, Ollama, Azure OpenAI, Bedrock or Vertex validators. Pick one and add failure-path tests
   (bad key, timeout, malformed response) with a mocked HTTP server.
5. **Docs pass.** Follow `docs/getting-started.md` or `docs/operations/local-stack-guide.md`
   on a clean machine and fix any command, port or step that is wrong. Run
   `bash scripts/ci/docs-link-check.sh`.
6. **Record a verification.** Run one row of the ADR matrix you have access to (for
   example a managed Redis with TLS) and open a PR that updates the "Status" cell with exactly what you ran.

## Reporting security issues

Do not open a public issue. See [SECURITY.md](SECURITY.md).

## Code of conduct

Participation is governed by the [Code of Conduct](CODE_OF_CONDUCT.md).
