# VegaDūta documentation

These pages are also published as a searchable site (`mkdocs.yml`, `make docs-serve`); see [Publishing these docs](maintainers/docs-site.md).

## Start here

| Page | For |
|---|---|
| [Getting started](getting-started.md) | Lite install (installer or checkout), first agent, first workflow |
| [Installation](installation.md) | Personal (Setup app or CLI), Lite, Full (Docker Compose) and Kubernetes (Helm); images and signature verification |
| [Console guide](console/README.md) | Every signed-in screen: the [Assistant](console/assistant.md) surface, build, channels, monitor, admin |
| [Configuration](configuration.md) | Spring profiles, core shapes, environment variables, feature flags |
| [Extending](extending.md) | Tools from OpenAPI, MCP servers, `SKILL.md` skills, LLM providers, workflow nodes (`NodeHandler`, `NODE_FORMS`), channels (adapter registry), tool sources, the HTTP request pipeline, LLM call sinks, the turn journal |
| [SDKs and dependencies](sdk/README.md) | The Java and Python Agent SDKs (governance inside your own application, plus the Java risk scanner) and the generated TypeScript and Python API clients |
| [Plugins, apps and mobile](plugins-and-apps.md) | Editor and browser plugins, the Android app, the MCP server: status, catalog, publishing |
| [Repository structure](repository-structure.md) | How the repository is arranged and how the platform, public pages, SDKs, docs and plugins are linked |
| [Site modes and public pages](site-modes.md) | Open-source vs commercial public pages, and where every surface is linked |
| [Architecture overview](architecture/overview.md) | Deployables, data, authentication, and why the chat path stays in one process |

## Features

| Page | For |
|---|---|
| [Personal mode](personal-mode.md) | One person on one machine: the Setup app (installs Docker and a free local model, repairs a failed start) or `scripts/vegaduta personal`, local sign-in, local models, the Assistant's workspace and desktop tools, skills, sandbox levels, limitations |
| [Cost and ROI](cost-and-roi.md) | Per-agent cost, outcome rates, tenant-entered value assumptions; `/roi` and `/api/tenant/roi/**` |
| [Running a 30-day pilot](pilots/README.md), [case-study template](pilots/case-study-template.md) | The pilot process, baseline, one-page pilot report, demo data (`scripts/vegaduta seed-demo`) |
| [Run free with local models](local-models.md) | Ollama in Compose or Helm, the model catalog (`scripts/vegaduta models`), on-device models at `/edge` |
| [Share an agent as a file](agent-bundles.md) | Export an agent as a bundle, import one safely, ready-made examples in `examples/agent-bundles/` |
| [Edge relay](edge-relay.md) | A signed-in browser as the model behind your SMS and WhatsApp numbers |
| [SMS conversations](sms-channel.md) | Plain-text channel on a Twilio number |
| [Signal](channels/signal.md), [Matrix](matrix-channel.md), [iMessage](imessage-channel.md) | Personal channels through a signal-cli server, a Matrix bot user and a BlueBubbles Mac; default deny with pairing |
| [WhatsApp QR](channels/whatsapp-qr.md) | Link your own WhatsApp number as a companion device — no Meta Business account, no public URL |
| [WhatsApp policy](channels/whatsapp-policy.md) | Meta's rules as read on 2026-10-14 and what the policy guard enforces |
| [DPDP obligations and controls](compliance/dpdp-obligations.md) | Consent, purposes, retention, export and erasure; engineering notes, not legal advice |

## Deploying

- [deploy/README.md](../deploy/README.md): Compose tiers, profiles, domains and HTTPS, backup, upgrade, troubleshooting
- [deploy/helm/README.md](../deploy/helm/README.md): Helm charts, values and validation
- [operations/local-stack-guide.md](operations/local-stack-guide.md): run it locally, what listens where, and how to see the infrastructure with Docker, `kubectl` and `k9s`
- [architecture/ADR-cloud-portability.md](architecture/ADR-cloud-portability.md): which managed services on AWS, Azure and GCP each dependency can use, and what is verified
- [architecture/kubernetes.md](architecture/kubernetes.md): Kubernetes design: namespaces, isolation, workload security, scaling, and what is verified
- [architecture/HOT-PATH-PERFORMANCE.md](architecture/HOT-PATH-PERFORMANCE.md): what a chat turn costs the platform before the model is called, the rules that bound it, measured query budgets and what is still unfinished
- [deploy/DEPLOYMENT-SHAPES.md](deploy/DEPLOYMENT-SHAPES.md): API, jobs and ingest shapes, who runs which scheduled bean, running more than one API replica, autoscaling and the database connection budget
- [operations/tunnel-hosting.md](operations/tunnel-hosting.md): host the Full tier behind a Cloudflare Tunnel with no open ports

## Reference

- [core/mcp/REGISTERING-A-CUSTOM-MCP-SERVER.md](core/mcp/REGISTERING-A-CUSTOM-MCP-SERVER.md): MCP registry API in depth
- [connectors/](connectors/README.md): example OpenAPI connector specs
- [architecture/mcp-spec-alignment.md](architecture/mcp-spec-alignment.md) and [architecture/a2a-conformance.md](architecture/a2a-conformance.md): MCP and A2A, requirement by requirement, with what is not done
- [architecture/](architecture/): current design documents: the [overview](architecture/overview.md), the generated [core module graph](architecture/core-module-graph.md), the [agents module map](architecture/agents-decomposition/README.md) (the ten-way split of `core/agents`, [ADR-0023](architecture/ADR-0023-agents-module-decomposition.md)), ADRs ([ADR-0011 provider plugins](architecture/ADR-0011-provider-plugins.md), [ADR-0013 embeddable agent SDK, proposed](architecture/ADR-0013-embeddable-agent-sdk.md), [ADR-0014 horizontal scaling](architecture/ADR-0014-horizontal-scaling.md), [ADR-0015 hybrid tenancy, proposed](architecture/ADR-0015-hybrid-tenancy.md), [agent job durability](architecture/ADR-AGENT-JOB-DURABILITY-2026-08-17.md); the proposed, design-only ADR-0016, ADR-0017 and ADR-0018 are in the [ADR index](adr/README.md); first slices exist for [ADR-0019 conformance by construction](architecture/ADR-0019-conformance-by-construction.md), [ADR-0020 turn journal](architecture/ADR-0020-turn-journal.md), [ADR-0021 turn inspector](architecture/ADR-0021-turn-inspector.md) and [ADR-0022 explicit turn context](architecture/ADR-0022-explicit-turn-context.md)), the [architecture bets](architecture/ARCHITECTURE-BETS-2026-10.md) (proposed, with status per bet), the [structural audit](architecture/STRUCTURAL-AUDIT-2026-10-14.md), guardrails, provider modalities, the consumer secrets vault and LLM latency. Where a document disagrees with the code, the code wins.
- [architecture/archive/](architecture/archive/README.md): historical design notes from before the repository was published, kept because code comments cite them.
- [platform-documentation/](platform-documentation/index.html): the HTML handbook from the project's `agentic-ai` era (architecture, API, security model, development and testing, decision records ADR-0001 to ADR-0010). **Legacy**: the Markdown `docs/` tree is the maintained source and wins where the two disagree; the handbook is kept for the older ADRs. Browse it by opening `docs/platform-documentation/index.html` in a browser, or serve the folder (`python3 -m http.server -d docs/platform-documentation`).
- [adr/](adr/README.md): index of every decision record, numbered or not.
- [api/](api/README.md): the OpenAPI description (`openapi.json`) and the generated TypeScript and Python SDKs.
- [security-model.md](security-model.md): what taint tracking, the STDIO sandbox and channel pairing do and do not protect against.
- [security/authorization.md](security/authorization.md): roles, `PERM_*` permissions, resource grants, conversation ownership, and the tests that keep new endpoints gated.
- [engineering/](engineering/): [security hardening settings](engineering/SECURITY-HARDENING.md), [Flyway versioning](engineering/FLYWAY-VERSIONING.md), [integration tests](engineering/INTEGRATION-TESTS.md), [characterisation tests](engineering/CHARACTERISATION-TESTS.md), the [big-files split plan](engineering/BIG-FILES-SPLIT-PLAN.md) (status per file), the Error Prone baseline and the standards audit.
- [plans/adr/](plans/adr/): decision records for work behind feature flags.
- Console engineering notes: [web/docs/RUNTIME-CONFIG.md](../web/docs/RUNTIME-CONFIG.md), [web/docs/TYPESCRIPT-STRICTNESS.md](../web/docs/TYPESCRIPT-STRICTNESS.md).

## Operating

- [operations/](operations/README.md): backup and restore, upgrade, [key rotation](operations/key-rotation.md), observability.

## Maintainers

- [Repository setup](maintainers/repo-setup.md): identity placeholders, repository settings, labels, history
- [Branch protection](maintainers/branch-protection.md): the required checks (`CI OK`, `CodeQL OK`) and ruleset
- [Releasing](maintainers/releasing.md): tags, what a release publishes, signatures, pre-releases and `latest`
- [Releasing the Java Agent SDK](maintainers/releasing-sdk.md): `sdk-v*` tags, Maven Central Portal, GPG, provenance
- [Publishing these docs](maintainers/docs-site.md): the MkDocs site on GitHub Pages

## Project

- [Contributing](../CONTRIBUTING.md) · [AGENTS.md](../AGENTS.md) for AI coding agents
- [Roadmap](../ROADMAP.md) · [Changelog](../CHANGELOG.md)
- [Governance](../GOVERNANCE.md) · [Maintainers](../MAINTAINERS.md)
- [Security policy](../SECURITY.md) · [Support](../SUPPORT.md) · [Code of Conduct](../CODE_OF_CONDUCT.md)
