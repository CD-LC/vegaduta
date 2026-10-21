# Roadmap

What we plan to work on, in rough priority order. Dates are not promised.
Anything here can be picked up by a contributor: comment on the linked issue
(or open one) before starting, so work is not duplicated. Status words:
**done** (in `main`), **in progress**, **planned**.

## 1. Easy to install, fast to run

| Item | Status |
|---|---|
| Three install tiers from the same images: Lite (5 containers), Full (Compose profiles), Kubernetes (Helm umbrella chart) | in progress |
| `scripts/vegaduta` CLI: `up`, `down`, `doctor`, `logs`, `secrets`, `upgrade`, `make-admin` | in progress |
| Local-disk file storage for Lite, S3-compatible storage for Full/Kubernetes | in progress |
| Embeddings from an OpenAI-compatible endpoint (for example Ollama) as an alternative to the ML sidecar | in progress |
| Consoles read browser URLs at runtime, so one published image works on any domain | done (`web/docs/RUNTIME-CONFIG.md`, ADR-0007 revised) |
| Faster core start-up: migrations in a one-shot job instead of the API boot, JVM AOT/CDS cache | in progress (Helm migrate Job and `APP_TENANCY_MIGRATE_ONLY` shipped; AOT/CDS pending) |
| Slim ML sidecar image without PyTorch for document extraction | planned |
| Upgrade the Spring stack (Spring Boot 4, Spring AI 2) | planned |

The chat path stays in one process; see
[docs/architecture/overview.md](docs/architecture/overview.md) for why splitting
it into services is not on this roadmap.

## 2. Supply-chain trust

| Item | Status |
|---|---|
| CI on every pull request; CodeQL for Java, TypeScript and Python | done |
| OpenSSF Scorecard and dependency review on pull requests | done |
| Release images signed with cosign (keyless), SPDX SBOM and SLSA provenance attestations, Helm charts as signed OCI artifacts | done (releases published since v0.1.0) |
| All workflow actions pinned by commit SHA; Dependabot for every ecosystem | done |
| OpenSSF Best Practices badge (passing) | planned |

## 3. Developer experience and ecosystem

| Item | Status |
|---|---|
| [Extending guide](docs/extending.md): tools from OpenAPI, MCP servers, `SKILL.md` skills, LLM providers, workflow nodes, channels | done (first version) |
| [AGENTS.md](AGENTS.md) for AI coding agents | done |
| Runnable example for each extension point under `examples/` | planned |
| Typed client SDKs (TypeScript, Python) generated from the OpenAPI description of the API | done (`sdk/`, regenerated from `docs/api/openapi.json`; drift checked in `make gates`) |
| Java Agent SDK: governance for agents inside your own Java application ([ADR-0013](docs/architecture/ADR-0013-embeddable-agent-sdk.md)) | in progress (`sdk/java`; Maven Central publication pending) |
| A navigable documentation site built from `docs/` | done (MkDocs, GitHub Pages workflow; goes live when Pages is enabled) |
| Per-agent toggle to publish any agent or workflow as its own MCP server and A2A agent | in progress ("Expose as MCP tool" switch for agents and workflows on the shared `/mcp` server, off by default: `APP_MCP_SERVER_EXPOSE_AGENTS_ENABLED`; per-agent A2A cards and endpoints) |
| `good first issue` backlog and GitHub Discussions | planned |

## 4. Standards conformance

| Item | Status |
|---|---|
| MCP client (stdio, SSE, Streamable HTTP) and MCP server at `/mcp` | done |
| A2A agent cards (signed) and JSON-RPC task methods | done |
| AG-UI event stream | done |
| OpenTelemetry GenAI: `invoke_agent` and `execute_tool` spans in addition to `gen_ai.*` chat attributes | done (`AgentInvocationObservation`, `GenAiToolObservationConvention`, `GenAiSpansTest`) |
| Conformance table for MCP, A2A, AG-UI and OpenTelemetry, generated from source checks | done (`scripts/ci/conformance-report.py` → `docs/api/conformance.md`, `--check` in static gates) |
| Mapping of the platform's controls to the OWASP guidance for agentic applications | done ([docs/security/owasp-mapping.md](docs/security/owasp-mapping.md)) |

## 5. Quality

| Item | Status |
|---|---|
| Raise `core/` line coverage and make coverage a no-regression check | in progress |
| Nightly Playwright suite against the Compose stack | done (workflow), suite expanding |
| Promote the advisory Python service test jobs to blocking | done (`python-extra` in `ci.yml` is blocking through `CI OK`; the whole-tree ruff step is still advisory) |
| Merge the platform-admin console into the main console | done (`web/app/platform-admin/`) |

## How the roadmap changes

Maintainers review this file at least once per release. Proposals for new
items go through GitHub Discussions (see [GOVERNANCE.md](GOVERNANCE.md)).
