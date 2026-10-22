# VegaDūta developer entry points. `make help` lists targets.
# Install/run targets delegate to scripts/vegaduta; build/test targets call each
# component's own tool. Run one Maven build at a time (shared ~/.m2).

SHELL := bash
.SHELLFLAGS := -eu -o pipefail -c
.DEFAULT_GOAL := help

VEGADUTA ?= ./scripts/vegaduta
MVN      ?= mvn
NPM      ?= npm
PYTHON   ?= python3
SYFT_IMAGE ?= anchore/syft:v1.52.0
SHELLCHECK_IMAGE ?= koalaman/shellcheck:v0.10.0
PROFILE  ?=
comma := ,

.PHONY: help
help: ## List targets
	@awk 'BEGIN {FS = ":.*## "} /^[a-zA-Z0-9_.-]+:.*## / {printf "  \033[36m%-18s\033[0m %s\n", $$1, $$2}' $(MAKEFILE_LIST)

## ---- Run -----------------------------------------------------------------

.PHONY: up-personal
up-personal: ## Start the Personal tier (postgres + valkey + core + web, no Keycloak) with scripts/vegaduta
	$(VEGADUTA) personal

.PHONY: up-lite
up-lite: ## Start the Lite tier (5 containers) with scripts/vegaduta
	$(VEGADUTA) up --lite

.PHONY: up
up: ## Start the Full tier; add profiles with PROFILE="temporal observability"
	$(VEGADUTA) up --full $(foreach p,$(PROFILE),--profile $(p))

.PHONY: down
down: ## Stop the running stack (volumes are kept)
	$(VEGADUTA) down

.PHONY: doctor
doctor: ## Check Docker, memory, ports and the generated .env
	$(VEGADUTA) doctor

.PHONY: hooks
hooks: ## Use the repo's git hooks (pre-push static gates)
	git config core.hooksPath .githooks

## ---- Build -----------------------------------------------------------------

.PHONY: build
build: build-core build-web ## Build core and web from source (no images)

.PHONY: build-core
build-core: ## mvn package (JDK 21+, tests skipped)
	cd core && $(MVN) -B -ntp -DskipTests package

.PHONY: build-web
build-web: ## Production build of the tenant console
	cd web && $(NPM) ci --no-audit --no-fund && $(NPM) run build

.PHONY: images
images: ## Build the Full-tier images from source (compose build overlay); PROFILE adds profiled services
	$(VEGADUTA) secrets
	COMPOSE_PROFILES="$(subst $(eval) ,$(comma),$(strip $(PROFILE)))" docker compose --env-file deploy/compose/.env 	  -f deploy/compose/docker-compose.yml -f deploy/compose/docker-compose.build.yml build

## ---- Test and lint -----------------------------------------------------------

.PHONY: test
test: test-core test-web test-python test-sdk-clients ## All unit/integration tests

.PHONY: test-core
test-core: ## mvn verify (context tests need Postgres with pgvector+AGE on DB_HOST:DB_PORT, else they skip)
	cd core && $(MVN) -B -ntp verify

.PHONY: spring-wiring
spring-wiring: ## Startup-only Spring wiring checks without a context: constructor choice, duplicate bean/entity names (part of test-core too)
	cd core && $(MVN) -B -ntp -pl app -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest='InjectableConstructorTest,UniqueBeanNamesTest,ShapeInjectionPointsTest' test

.PHONY: boot-check
boot-check: ## Boot the real core jars (api, migrate-only, jobs, worker) against throwaway pgvector+AGE Postgres and Redis (about 6 min; needs Docker and JAVA_HOME on a JDK 21+)
	bash scripts/ci/boot-check.sh

.PHONY: test-web
test-web: ## web: vitest
	cd web && $(NPM) test

.PHONY: contracts
contracts: ## Consumer-driven contracts (ADR-0025): web contract is current, then core verifies every consumer and the committed OpenAPI spec
	cd web && node scripts/api-contract.mjs --check
	cd web && $(NPM) exec -- vitest run app/lib/api/consumerContract.test.ts
	cd core && $(MVN) -B -ntp -pl app -am test -Dsurefire.failIfNoSpecifiedTests=false -Dtest='ConsumerContractVerificationTest,OpenApiSpecFreshnessTest'

.PHONY: test-python
test-python: ## sidecar (pytest) and code-intel (script harness); needs their requirements installed
	cd services/sidecar && $(PYTHON) -m pytest tests/ -q
	cd services/code-intel && for m in test_auth test_service test_guardrails test_agent \
	    test_approval_reconcile test_exec_and_mcp test_gitops_auth_token \
	    test_gitops_confinement test_run_modes test_store_search; do \
	  $(PYTHON) -m "tests.$$m" || exit 1; done

.PHONY: test-sdk-clients
test-sdk-clients: ## SDK REST clients + python-agent, incl. consumer contracts (contracts/consumers); python-agent needs pytest
	node --test sdk/test/*.test.mjs
	cd sdk/python && $(PYTHON) -m unittest discover -s tests
	cd sdk/python-agent && PYTHONPATH=. $(PYTHON) -m pytest tests/ -q

.PHONY: lint
lint: ## ESLint + tsc for the console, ruff for Python, shellcheck
	cd web && $(NPM) run lint && $(NPM) run typecheck
	ruff check services/sidecar services/code-intel
	$(MAKE) shellcheck

.PHONY: shellcheck
shellcheck: ## shellcheck (severity=error) on tracked shell scripts; uses Docker if not installed
	@files="$$( { git ls-files -- '*.sh' '*.bash'; git ls-files -- '.githooks/*'; } )"; \
	if command -v shellcheck >/dev/null 2>&1; then shellcheck --severity=error $$files; \
	else docker run --rm -v "$(CURDIR):/mnt" -w /mnt $(SHELLCHECK_IMAGE) --severity=error $$files; fi

.PHONY: load-gate
load-gate: ## 32-user chat benchmark on an EXISTING kind stage; fails on p95/failed-rate thresholds (needs LT_USER LT_PASS LT_AGENT_ID or tests/load/kind/gate.env; see tests/load/README.md)
	@set -a; if [ -f tests/load/kind/gate.env ]; then . tests/load/kind/gate.env; fi; set +a; \
	bash tests/load/kind/gate.sh

## ---- Validate deploy artifacts ------------------------------------------------

.PHONY: compose-validate
compose-validate: ## docker compose config on every file in deploy/compose
	bash scripts/ci/compose-validate.sh

.PHONY: helm-lint
helm-lint: ## helm lint + template + kubeconform on deploy/helm charts
	bash scripts/ci/helm-validate.sh

## ---- Gates and supply chain --------------------------------------------------

.PHONY: gates
gates: ## The static gates (static-gates.yml runs them when CI is triggered; run locally, or `make hooks` for pre-push) (Flyway, bean/entity names, skips, prices, file size, module size, API breaking changes, internal-jargon ratchet, no-telemetry, secrets)
	bash scripts/ci/flyway-lint.sh
	bash scripts/ci/sync-platform-guide-docs.sh --check
	bash scripts/ci/entity-name-lint.sh
	bash scripts/ci/skip-expiry.sh
	sh scripts/ci/check-file-size.sh
	sh scripts/ci/check-module-size.sh
	sh scripts/ci/dockerfile-poms.sh
	python3 core/tools/module-graph/gen_module_graph.py --check
	node sdk/generate.mjs --check
	node scripts/docs/asyncapi.mjs --check
	node --test scripts/ci/openapi-breaking.test.mjs
	node scripts/ci/openapi-breaking.mjs
	python3 scripts/docs/test_gen_shapes.py
	python3 scripts/docs/gen-shapes.py --check
	python3 scripts/ci/conformance-report.py --check
	bash tests/install/vegaduta-install.test.sh
	bash scripts/ci/docs-link-check.sh
	bash scripts/ci/internal-reference-lint.sh
	bash scripts/ci/no-telemetry-check.sh
	bash scripts/ci/test-internal-reference-lint.sh
	python3 deploy/helm/scripts/check-values-docs.py
	bash scripts/ci/secret-scan.sh

.PHONY: sbom
sbom: ## SPDX JSON SBOM of the source tree into dist/ (syft via Docker)
	mkdir -p dist
	docker run --rm -v "$(CURDIR):/src" $(SYFT_IMAGE) dir:/src \
	  --exclude './**/node_modules/**' --exclude './**/target/**' --exclude './**/.next/**' \
	  -o spdx-json=/src/dist/sbom-source.spdx.json
	@echo "wrote dist/sbom-source.spdx.json"

# ---- documentation site (mkdocs.yml; published by .github/workflows/docs.yml)
DOCS_VENV ?= .venv-docs

$(DOCS_VENV)/bin/mkdocs: docs/requirements.txt
	python3 -m venv $(DOCS_VENV)
	$(DOCS_VENV)/bin/pip install -q -r docs/requirements.txt

docs-build: $(DOCS_VENV)/bin/mkdocs ## Build the documentation site (strict) into site/
	$(DOCS_VENV)/bin/mkdocs build --strict

docs-serve: $(DOCS_VENV)/bin/mkdocs ## Serve the documentation site with live reload
	$(DOCS_VENV)/bin/mkdocs serve

.PHONY: docs-build docs-serve
