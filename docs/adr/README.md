# Architecture decision records

Every ADR in the repository, wherever it lives. Numbered ADRs are in the HTML
handbook; later decisions were written as Markdown next to the code they
describe and are listed here by title until they are numbered. Where a record
disagrees with the code, the code wins.

| # | Decision | Status | Where |
|---|---|---|---|
| 0001 | Modular monolith | verified | [handbook](../platform-documentation/decisions/adr-0001-modular-monolith.html) |
| 0002 | Schema per tenant | verified | [handbook](../platform-documentation/decisions/adr-0002-schema-per-tenant.html) |
| 0003 | Spring AI dynamic clients | verified | [handbook](../platform-documentation/decisions/adr-0003-spring-ai-dynamic-clients.html) |
| 0004 | Hosted container runtime | verified | [handbook](../platform-documentation/decisions/adr-0004-hosted-container-runtime.html) |
| 0005 | Dual-issuer JWT (Keycloak) | verified | [handbook](../platform-documentation/decisions/adr-0005-dual-issuer-keycloak.html) |
| 0006 | Managed Redis | verified | [handbook](../platform-documentation/decisions/adr-0006-managed-redis.html) |
| 0007 | Frontend public config (revised: runtime) | verified | [handbook](../platform-documentation/decisions/adr-0007-build-time-frontend-config.html) |
| 0008 | Single-revision deploys | verified | [handbook](../platform-documentation/decisions/adr-0008-single-revision-deploys.html) |
| 0009 | Untrusted-code sandboxing | verified | [handbook](../platform-documentation/decisions/adr-0009-untrusted-code-sandboxing.html) |
| 0010 | Secret injection model | verified | [handbook](../platform-documentation/decisions/adr-0010-secret-injection.html) |
| 0011 | Vendor providers as build-time starters behind a core SPI | phases 0-2 implemented | [architecture/ADR-0011-provider-plugins.md](../architecture/ADR-0011-provider-plugins.md) |
| 0012 | Spring Boot 4.1 + Spring AI 2.0 migration | see record | [architecture/ADR-0012-spring-ai-2-boot-4.md](../architecture/ADR-0012-spring-ai-2-boot-4.md) |
| 0014 | Scale the core by workload class and replica, not by module | accepted, locally verified | [architecture/ADR-0014-horizontal-scaling.md](../architecture/ADR-0014-horizontal-scaling.md) |
| 0015 | Hybrid tenancy: dedicated schemas plus shared tables with row-level security | proposed, design only | [architecture/ADR-0015-hybrid-tenancy.md](../architecture/ADR-0015-hybrid-tenancy.md) |
| 0016 | Remove the per-transaction `SET search_path` round trips | proposed, design only | [architecture/ADR-0016-search-path-round-trips.md](../architecture/ADR-0016-search-path-round-trips.md) |
| 0017 | Connection budget, read replicas and cells | proposed, design only | [architecture/ADR-0017-connections-replicas-cells.md](../architecture/ADR-0017-connections-replicas-cells.md) |
| 0018 | Ad Studio: a separate-service ad-creative platform | proposed, design only | [architecture/ADR-0018-ad-studio.md](../architecture/ADR-0018-ad-studio.md) |
| – | Cloud portability through open protocols: managed Postgres, Redis, S3, Kafka on AWS, Azure and GCP | accepted, partially implemented | [architecture/ADR-cloud-portability.md](../architecture/ADR-cloud-portability.md) |
| – | Durable execution for agent jobs: Temporal vs Postgres | accepted | [architecture/ADR-AGENT-JOB-DURABILITY-2026-08-17.md](../architecture/ADR-AGENT-JOB-DURABILITY-2026-08-17.md) |
| – | Generic MCP OAuth (RFC 9728/8414/7591, PKCE) | accepted, flag off | [architecture/ADR-mcp-generic-oauth.md](../architecture/ADR-mcp-generic-oauth.md) |
| – | Outbound A2A client | first slice, flag off | [plans/adr/a2a-outbound-client.md](../plans/adr/a2a-outbound-client.md) |
| – | General coding-agent harness | proposed, stage 0 behind flag | [plans/adr/coding-agent-harness.md](../plans/adr/coding-agent-harness.md) |
| – | Late-interaction retrieval and modern embeddings | proposed | [plans/adr/late-interaction-retrieval.md](../plans/adr/late-interaction-retrieval.md) |
| – | MCP Apps (`ui://` resources) in chat | proposed, library only | [plans/adr/mcp-apps-ui-resources.md](../plans/adr/mcp-apps-ui-resources.md) |
| – | OS-level sandbox for custom tools and stdio MCP | accepted, first slice | [plans/adr/os-level-sandbox-custom-tools.md](../plans/adr/os-level-sandbox-custom-tools.md) |
| – | Stateless MCP, unpinning stdio MCP from one replica | proposed | [plans/adr/stateless-mcp.md](../plans/adr/stateless-mcp.md) |
| – | Step journal and resumable jobs/swarms | first slice, flags off | [plans/adr/step-journal-resumable-jobs.md](../plans/adr/step-journal-resumable-jobs.md) |
| – | Swarm goal verifier for `delegate_parallel` | implemented behind flag | [plans/adr/swarm-goal-verifier.md](../plans/adr/swarm-goal-verifier.md) |
| – | Verifier-escalated model router | first slice, flag off | [plans/adr/verifier-escalated-router.md](../plans/adr/verifier-escalated-router.md) |

Write a new ADR as Markdown under `docs/plans/adr/` while it is a proposal, with
`Status:` and `Date:` on the first lines; add it here in the same change.
