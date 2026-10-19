-- Full SDLC Modernization Pipeline - a demo-able platform workflow template
-- showing Jira ticket -> AI triage/codegen -> human approval -> test-gated
-- deploy -> monitor, built entirely from primitives that already exist in
-- core/workflow (WorkflowNodeType: TRIGGER/AGENT/TOOL/APPROVAL/CONDITION/
-- CODE/OUTPUT - see that enum's javadoc) and core/codeintel (search_code /
-- apply_code_changes / run_in_code_workspace tool callbacks, resolved onto
-- an agent's tool catalog by AgentToolProvider#resolveCodeIntelTools /
-- #resolveWriteCodeIntelTools).
--
-- Follows the exact platform-template pattern V156__community_template.sql
-- established and V164__workflow_intelligence_templates.sql already used for
-- seeding workflow designs the same way: kind='WORKFLOW', source_tenant_id=
-- NULL (not owned by any real tenant - see control_plane.community_template's
-- V156 comment for why a platform-owned row cannot carry a fake tenant_id),
-- submitted_by/reviewed_by='platform', status='APPROVED' so it is visible in
-- the public gallery immediately, no moderation queue detour needed for a
-- platform-authored row. config_json is TemplateSnapshotService.WorkflowSnapshot
-- shape ({name, description, graph}) - the same shape CommunityTemplateService
-- #importTemplate reads via snapshotService.toWorkflowGraph() to call
-- WorkflowService#create(title, description, graph) on import, so this
-- template becomes a real, editable, runnable workflow_definition row (V20)
-- in whichever tenant schema imports it - identical path a human-submitted
-- template takes, just skipping the human submission.
--
-- VERSION V197: re-checked immediately before authoring this file - highest
-- version present in either controlplane/ or tenant/ was tenant/V196, so
-- V197 is the next free slot in the shared Flyway version stream (see
-- V147__resource_sharing.sql / V156's own comment for why the numbering is
-- global across both folders, not per-folder).
--
-- HONESTY NOTE (read before assuming this is turnkey - it is a scaffold):
--   - TRIGGER (n1): channel is set to WEBHOOK, matching the "Jira issue
--     created/transitioned webhook" intent - but per-node webhook
--     tokens/auth mode are runtime-provisioned (WorkflowController's
--     trigger-channel endpoints, WorkflowWebhookController), never stored in
--     graph_json/config_json, so nothing here needs a real secret at insert
--     time. After import, a tenant admin still has to: (a) actually wire a
--     Jira webhook to POST to this node's generated trigger URL, which
--     requires the Jira/Atlassian OAuth connector another parallel task is
--     adding to the MCP catalog - referenced below by tool-name convention
--     only, since MCP tool resolution happens at chat-turn runtime, not at
--     migration-apply time, and (b) pick SHARED_SECRET vs OAUTH2 auth mode.
--   - AGENT (n2, n4) and TOOL (n3): agentId is deliberately blank ("") -
--     every agent-referencing node in every platform template seeded so far
--     (see V164) leaves this blank, because agent ids are tenant-scoped
--     UUIDs with no cross-tenant meaning (exactly why TemplateSnapshotService
--     never carries a real agentId either - see its class javadoc). The
--     importing tenant must open each node in NodeConfigDrawer and point it
--     at a real agent after import, before the workflow can Run.
--   - TOOL (n3) names the tool "search_code" (SearchCodeToolCallback) by
--     exact ToolDefinition name - read-only, available to any agent as long
--     as a CodeIntelClient bean is configured (AgentToolProvider
--     #resolveCodeIntelTools), no approval needed.
--   - AGENT (n4) is where "apply_code_changes" / "run_in_code_workspace"
--     actually get called, NOT a Tool node - those two are only resolved
--     onto an agent's catalog when that agent has allowed code-intel repo
--     ids configured (AgentToolProvider#resolveWriteCodeIntelTools,
--     AgentService#getAllowedCodeIntelRepoIds) and are independently
--     approval-gated inside the tool callback itself (HumanApprovalGateService
--     #evaluate, per ApplyCodeChangesToolCallback's own javadoc) - the
--     explicit APPROVAL node below (n5) is a second, workflow-level gate on
--     top of that, not a replacement for it.
--   - APPROVAL (n5): adminOnly=true since this gates a real repository write.
--   - CONDITION (n6): a placeholder "contains" text match ("BUILD SUCCESS")
--     standing in for a real test/CI signal - the engine has no native
--     "run this repo's test suite and branch on the result" primitive today;
--     wiring n4's output to actually contain that phrase (e.g. via a CI tool
--     call this platform doesn't have yet) is exactly the kind of follow-up
--     work this template scaffolds rather than solves.
--   - CODE (n7, "Deploy"): explicitly a placeholder. core/workflow's CODE
--     node type is a fixed library of safe text transforms only (extract/
--     regex_replace/template/case/json parse-stringify - see
--     WorkflowNodeExecutor#executeCodeNode's javadoc for why real code
--     execution was deliberately ruled out) - it CANNOT invoke this repo's
--     actual deploy scripts or CI. This node uses the "template" operation
--     purely to render a labeled deploy-intent string; it does not touch
--     deploy/, Docker, or any infrastructure. One-click prod deploy needs
--     human-gated infra access no migration can grant - see this repo's
--     ZERO-DOWNTIME-DEPLOY-RULES.md for what that would actually require.
--   - OUTPUT (n8, "Monitor & Report"): channel=event publishes onto the
--     workflow.events topic via WorkflowEventPublisher - a real, already-
--     wired publish call (see WorkflowNodeExecutor#executeOutputNode's
--     "event" case), not a new integration. It does NOT push a metric into
--     Grafana/Prometheus/Loki directly - there is no such OUTPUT channel or
--     TOOL today. The intent, left for follow-up work, is for this event (or
--     the run's own trace) to surface on the existing observability stack's
--     dashboards the same way other platform events already do, not a new
--     external Datadog/PagerDuty-style integration.

INSERT INTO control_plane.community_template
    (kind, title, summary, category, tags, status, config_json,
     source_tenant_id, submitted_by, submitted_at,
     reviewed_by, reviewed_at)
VALUES
('WORKFLOW',
 'Full SDLC Modernization Pipeline',
 'Demo-able end-to-end SDLC pipeline: a Jira issue webhook triggers an agent that triages the ticket into a task spec, a Tool node searches this platform''s own repo via code-intel for relevant context, a second agent generates the code change (able to call code-intel''s apply/run tools once configured), a human-approval gate reviews before anything is applied, a condition branches on whether tests passed, a placeholder Deploy step stands in for real CI/CD, and a final Output step reports the run onto the platform''s event/observability topic. Built entirely from existing Workflow primitives (Trigger/Agent/Tool/Approval/Condition/Code/Output) to demonstrate the orchestration engine''s range end-to-end - see this row''s migration file for exactly which stages are fully wired today vs. placeholders (Jira OAuth, per-node agent selection, and the deploy/CI signal itself all still need tenant-side setup after import).',
 'Engineering',
 '["sdlc","devops","code-intel","jira","ci-cd","platform-demo"]',
 'APPROVED',
 $${"name":"Full SDLC Modernization Pipeline","description":"Jira issue -> AI triage -> code-intel search -> AI codegen (apply/run tool callbacks) -> human approval -> tests pass/fail -> deploy (placeholder) -> monitor/report. Scaffold built from existing Workflow/CodeIntel/Approval primitives - see the seeding migration's comments for exactly which stages are fully wired vs. placeholders.","graph":{"nodes":[{"id":"n1","type":"trigger","position":{"x":80,"y":160},"data":{"label":"Jira Issue Trigger","config":{"channel":"WEBHOOK"}}},{"id":"n2","type":"agent","position":{"x":340,"y":160},"data":{"label":"Triage & Plan Ticket","config":{"agentId":""}}},{"id":"n3","type":"tool","position":{"x":600,"y":160},"data":{"label":"Search Repo (code-intel)","config":{"agentId":"","toolName":"search_code","argsJson":""}}},{"id":"n4","type":"agent","position":{"x":860,"y":160},"data":{"label":"Generate Code Changes","config":{"agentId":""}}},{"id":"n5","type":"approval","position":{"x":1120,"y":160},"data":{"label":"Human Approval: Apply Changes","config":{"message":"Review the proposed code changes before they are applied to the repository via code-intel's apply_code_changes tool.","adminOnly":true}}},{"id":"n6","type":"condition","position":{"x":1380,"y":160},"data":{"label":"Tests Passed?","config":{"contains":"BUILD SUCCESS"}}},{"id":"n7","type":"code","position":{"x":1640,"y":60},"data":{"label":"Deploy (placeholder)","config":{"operation":"template","field":"","pattern":"","replacement":"","template":"[PLACEHOLDER - not wired to real deploy infrastructure or CI. Intended to hand off to this repo's deploy scripts/pipeline once a human-gated infra integration exists.] Deploy request for: {{input}}"}}},{"id":"n8","type":"output","position":{"x":1900,"y":160},"data":{"label":"Monitor & Report","config":{"channel":"event"}}}],"edges":[{"id":"e1","source":"n1","target":"n2"},{"id":"e2","source":"n2","target":"n3"},{"id":"e3","source":"n3","target":"n4"},{"id":"e4","source":"n4","target":"n5"},{"id":"e5","source":"n5","target":"n6"},{"id":"e6","source":"n6","target":"n7","sourceHandle":"true"},{"id":"e7","source":"n6","target":"n8","sourceHandle":"false"},{"id":"e8","source":"n7","target":"n8"}]}}$$,
 NULL, 'platform', now(), 'platform', now());
