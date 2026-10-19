-- "Marketing Content Engine" workflow template (community gallery) - the
-- WORKFLOW half of the pair started by V219 (previous version, same
-- migration set): wires the four agent templates seeded there into the
-- actual graph the recipe at web/app/help/workflow-recipes/recipes-growth-sales.tsx
-- ("mkt-content-engine") describes in prose - trigger -> content-lead brief
-- -> PARALLEL fan-out to three writers -> APPROVAL gate -> output.
--
-- Node-type note: the content lead is an AGENT node, not a SUPERVISOR node -
-- SUPERVISOR is this platform's branch router (picks one named route from
-- its own `routes` config, see WorkflowNodeType's javadoc), not a fan-out
-- coordinator, so it is the wrong primitive for "one agent hands off to
-- several running at once". The three writer branches converge on the
-- single APPROVAL node via three separate edges (not a dedicated join node),
-- the exact same shape V164's "Parallel Research Aggregator" template
-- (n3/n4/n5 -> n6) already uses for its three-researcher fan-out - this
-- template is that same proven shape with an APPROVAL gate in place of that
-- template's aggregator-agent step. Engine-level proof this shape validates
-- and executes correctly (both the auto-approved completion and the
-- awaiting-approval halt) lives in
-- core/workflow/src/test/java/.../MarketingContentEngineWorkflowTest.java
-- and the added case in OrchestratorServiceTest.
--
-- Same mechanism as V164: one WORKFLOW-kind row in
-- control_plane.community_template, APPROVED + platform-owned, config_json
-- a WorkflowSnapshot. agentId fields are left "" exactly like every existing
-- workflow template - the installing tenant wires in their own imported
-- copies of the four V219 agent templates (or any agents of their own) at
-- import time; nothing here hard-codes another tenant's agent id.
--
-- "Long running": this template does not itself carry a schedule (schedule
-- state - scheduleCron/scheduleEnabled/nextRunAt - lives on the installed
-- WorkflowDefinition row, not the shared template snapshot, exactly like
-- every other workflow on this platform). A tenant enables a recurring run
-- (e.g. nightly) after installing, via the existing schedule endpoints
-- (WorkflowController's GET/PUT/DELETE /api/workflows/{id}/schedule) - no
-- new scheduling mechanism needed, see WorkflowServiceTest's
-- enableScheduleThenFindDueScheduledWorkflowIdsReturnsItOnceDue /
-- runScheduledWorkflowAdvancesTheScheduleRegardlessOfRunOutcome for the
-- proof that path works end to end.
--
-- VERSION V220: next free slot after V219 in this same migration set (see
-- that file's own version-numbering note). FLAGGED FOR MERGE per the same
-- convention - renumber on collision rather than reusing a taken version.

INSERT INTO control_plane.community_template
    (kind, title, summary, category, tags, status, config_json,
     source_tenant_id, submitted_by, submitted_at,
     reviewed_by, reviewed_at)
VALUES

('WORKFLOW',
 'Marketing Content Engine',
 'One captured idea becomes three channel-native drafts - long-form, short-form, and a visual caption - each grounded in your own Knowledge base, held in a single approval queue until you approve or edit them. Nothing is published by the workflow; the agents draft, you ship. Pair with the four "Marketing Content Engine" agent templates for the four agent slots below, or wire in your own agents.',
 'Marketing',
 '["industry:marketing","use-case:content","pattern:parallel","pattern:approval","reasoning:CAREFUL"]',
 'APPROVED',
 $${"name":"Marketing Content Engine","description":"Trigger -> content-lead brief -> three writers in parallel -> human approval -> output. CAREFUL reasoning on the judgement step (the brief), nothing published without a human decision.","reasoningDepth":"CAREFUL","tuningMode":"SIMPLE","stepCachingEnabled":false,"costRoutingEnabled":false,"maxStepsPerRun":20,"stepTimeoutSeconds":60,"graph":{"nodes":[{"id":"n1","type":"trigger","position":{"x":40,"y":260},"data":{"label":"Idea Captured","config":{"channel":"WEBHOOK"}}},{"id":"n2","type":"agent","position":{"x":300,"y":260},"data":{"label":"Content Lead","config":{"agentId":""}}},{"id":"n3","type":"parallel","position":{"x":560,"y":260},"data":{"label":"Draft in Parallel","config":{}}},{"id":"n4","type":"agent","position":{"x":820,"y":80},"data":{"label":"Long-form Writer","config":{"agentId":""}}},{"id":"n5","type":"agent","position":{"x":820,"y":260},"data":{"label":"Short-form Writer","config":{"agentId":""}}},{"id":"n6","type":"agent","position":{"x":820,"y":440},"data":{"label":"Caption Writer","config":{"agentId":""}}},{"id":"n7","type":"approval","position":{"x":1080,"y":260},"data":{"label":"Founder Approval","config":{}}},{"id":"n8","type":"output","position":{"x":1340,"y":260},"data":{"label":"Publish-ready Drafts","config":{"channel":"email"}}}],"edges":[{"id":"e1","source":"n1","target":"n2"},{"id":"e2","source":"n2","target":"n3"},{"id":"e3","source":"n3","target":"n4"},{"id":"e4","source":"n3","target":"n5"},{"id":"e5","source":"n3","target":"n6"},{"id":"e6","source":"n4","target":"n7"},{"id":"e7","source":"n5","target":"n7"},{"id":"e8","source":"n6","target":"n7"},{"id":"e9","source":"n7","target":"n8"}]}}$$,
 NULL, 'platform', now(), 'platform', now());
