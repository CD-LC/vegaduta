// ---------------------------------------------------------------------------
// /platform hub — page data (2026-08-14).
//
// Grounding notes (no-fiction rule, see lib/seo-hub.ts): every claim below
// maps to code on main — Temporal workflow engine + 14 node types + 11
// trigger channels (core/workflow), agent builder / studio / template
// gallery (web + core/agents), inbound webhook triggers + signed channel
// webhooks (core/omnichannel, core/billing), developer API + MCP server
// (core/mcp, /api/dev/v1), schema-per-tenant + Keycloak + encrypted
// credentials + guardrails (core/tenancy, core/security, core/crypto,
// core/guardrails), Langfuse/OTel tracing + evals (core/quality). Honest
// limitations (no outbound webhook subscriptions, no SOC 2 / ISO cert,
// no BYO-Langfuse) are stated as such, deliberately.
//
// Content sync 2026-08-17: per-workflow execution limits are now described
// (workflow_definition.max_retry_attempts, V205; PATCH /api/workflows/{id}/
// execution-limits accepts maxRetryAttempts 0-5 alongside maxStepsPerRun and
// stepTimeoutSeconds; both WorkflowExecutionEngine and the Temporal
// WorkflowRunWorkflowImpl read the retry count and step ceiling, which is why
// the copy can say "both engines" for those two). One honest difference the
// copy now states: stepTimeoutSeconds is enforced only by the Temporal engine
// (WorkflowRunWorkflowImpl's activity timeout), so "both engines honour the
// per-step timeout" is not claimed. The TenantLimitExceededException retry
// exclusion is from the same change. The developer-API page now names the five
// MCP tools for the same reason /ai/mcp does — see that module's header.
//
// Content sync 2026-10-03. Stale facts corrected: "runs on Temporal" is gone
// (Temporal is opt-in and OFF by default; WorkflowExecutionEngine is the
// engine a run actually uses, and paused runs are parked on the run row —
// WorkflowRunPauseService), 14 node types -> 21 (WorkflowNodeType.java), and
// "~90 recipes" -> 240+ recipes across about 90 industries (sum of
// recipeCount in help/workflow-recipes/recipes-index.ts). New pages, each
// grounded in the code named here:
//   agent-lab        core/agents/.../lab/AgentLabController (/api/agents/{id}/lab),
//                    web components/agents/lab (run graph, fork, knockout, consistency)
//   agent-skills     core/agents/.../core/skills (SkillService, SkillDistillationService,
//                    BuiltInSkillCatalog; 14 folders under resources/skills), web app/skills
//   agent-teams      core/agents/.../collab/DelegateToTeamToolCallback + RunBudget,
//                    crew/AgentHandoffAuditService, web lib/teamSwarm.ts SWARM_LIMITS
//   versions-and-evals  core/workflow/version, core/quality (dataset, gate/PassHatKGate,
//                    stats, simulation), web app/agents/quality, agentVersionsCard
//   app-studio       app-builder/app (generate.py repair loop, baas.py roles, figma.py,
//                    repo_import.py/repo_sync.py), core/.../common/studioapp
//                    (StudioAppPublicController end-user auth). Custom domains are
//                    NOT claimed: verification exists, serving does not.
//   governance       app.tools confirmation-gate + guardrails.run-budget in
//                    application.yml, core/admin/sso (TenantSsoController,
//                    ScimProvisioningController), core/chat GenAi*Observation*,
//                    web app/admin/audit-tail
//   self-hosting     LICENSE (BSL 1.1) + deploy/docker-compose.yml. Helm and
//                    Terraform directories are drafts and are not presented.
// Deliberately NOT claimed anywhere (flag off in prod): swarm goal verifier,
// eval judge calibration, E2B microVM tool sandbox, graph-memory arbitration.
// ---------------------------------------------------------------------------

import type { HubPage } from "../lib/seo-hub";
import { BUILD_TOPICS } from "./platform-data-build";
import { OPERATE_TOPICS } from "./platform-data-operate";

export const PLATFORM_TOPICS: HubPage[] = [...BUILD_TOPICS, ...OPERATE_TOPICS];
