-- Platform workflow intelligence templates (Agent Intelligence parity, Section 4).
-- Four canonical workflow configurations matching the four ReasoningDepth/TuningMode
-- combinations the agent templates use (V163), adapted for workflows.
-- kind='WORKFLOW', source_tenant_id=NULL = platform-owned, status='APPROVED' = live immediately.
-- config_json is WorkflowSnapshot format: {name, description, graph, intelligenceSettings}.

INSERT INTO control_plane.community_template
    (kind, title, summary, category, tags, status, config_json,
     source_tenant_id, submitted_by, submitted_at,
     reviewed_by, reviewed_at)
VALUES

-- Template A: Linear Customer Journey (FAST + MANUAL + step caching)
('WORKFLOW',
 'Linear Customer Journey',
 'Simple four-step linear workflow: trigger → classify → agent respond → output. FAST reasoning and step caching keep repeat queries instant. Best for high-volume, predictable customer-service flows.',
 'Customer Service',
 '["reasoning:FAST","caching:step","cost:low","tuning:manual","pattern:linear"]',
 'APPROVED',
 $${"name":"Linear Customer Journey","description":"Four-node linear flow: trigger, classify, agent respond, output. Step caching enabled for fast repeat queries.","reasoningDepth":"FAST","tuningMode":"MANUAL","stepCachingEnabled":true,"costRoutingEnabled":false,"maxStepsPerRun":10,"stepTimeoutSeconds":30,"graph":{"nodes":[{"id":"n1","type":"trigger","position":{"x":80,"y":160},"data":{"label":"Customer Message","config":{}}},{"id":"n2","type":"condition","position":{"x":340,"y":160},"data":{"label":"Classify Intent","config":{"contains":"help"}}},{"id":"n3","type":"agent","position":{"x":600,"y":160},"data":{"label":"Support Agent","config":{"agentId":""}}},{"id":"n4","type":"output","position":{"x":860,"y":160},"data":{"label":"Send Reply","config":{"channel":"log"}}}],"edges":[{"id":"e1","source":"n1","target":"n2"},{"id":"e2","source":"n2","target":"n3","sourceHandle":"true"},{"id":"e3","source":"n2","target":"n4","sourceHandle":"false"},{"id":"e4","source":"n3","target":"n4"}]}}$$,
 NULL, 'platform', now(), 'platform', now()),

-- Template B: Approval Gate (CAREFUL + SIMPLE + cost routing)
('WORKFLOW',
 'Approval Gate',
 'Five-node workflow with a human-in-the-loop approval branch. CAREFUL reasoning validates each step; SIMPLE tuning infers step limits automatically. Cost routing keeps simple classification steps cheap. Ideal for any process requiring human sign-off before action.',
 'Governance',
 '["reasoning:CAREFUL","caching:off","cost-routing:on","tuning:simple","pattern:approval"]',
 'APPROVED',
 $${"name":"Approval Gate","description":"Trigger → agent analysis → approval gate → action or reject. CAREFUL reasoning + cost routing.","reasoningDepth":"CAREFUL","tuningMode":"SIMPLE","stepCachingEnabled":false,"costRoutingEnabled":true,"maxStepsPerRun":15,"stepTimeoutSeconds":45,"graph":{"nodes":[{"id":"n1","type":"trigger","position":{"x":80,"y":160},"data":{"label":"Request Received","config":{}}},{"id":"n2","type":"agent","position":{"x":340,"y":160},"data":{"label":"Analyse Request","config":{"agentId":""}}},{"id":"n3","type":"approval","position":{"x":600,"y":160},"data":{"label":"Manager Approval","config":{}}},{"id":"n4","type":"output","position":{"x":860,"y":80},"data":{"label":"Execute Action","config":{"channel":"log"}}},{"id":"n5","type":"output","position":{"x":860,"y":240},"data":{"label":"Notify Rejected","config":{"channel":"log"}}}],"edges":[{"id":"e1","source":"n1","target":"n2"},{"id":"e2","source":"n2","target":"n3"},{"id":"e3","source":"n3","target":"n4","sourceHandle":"approved"},{"id":"e4","source":"n3","target":"n5","sourceHandle":"rejected"}]}}$$,
 NULL, 'platform', now(), 'platform', now()),

-- Template C: Parallel Research Aggregator (PLANNED + SIMPLE + step caching + cost routing)
('WORKFLOW',
 'Parallel Research Aggregator',
 'Fan out to three parallel research agents simultaneously, then aggregate results into a single output. PLANNED reasoning builds a research plan first; SIMPLE tuning infers step limits from the workflow description. Both cost routing and step caching reduce spend on repeated sub-queries.',
 'Research',
 '["reasoning:PLANNED","caching:step","cost-routing:on","tuning:simple","pattern:parallel","pattern:research"]',
 'APPROVED',
 $${"name":"Parallel Research Aggregator","description":"Fan-out parallel research then aggregate. PLANNED reasoning + step caching + cost routing.","reasoningDepth":"PLANNED","tuningMode":"SIMPLE","stepCachingEnabled":true,"costRoutingEnabled":true,"maxStepsPerRun":40,"stepTimeoutSeconds":60,"graph":{"nodes":[{"id":"n1","type":"trigger","position":{"x":80,"y":200},"data":{"label":"Research Request","config":{}}},{"id":"n2","type":"parallel","position":{"x":340,"y":200},"data":{"label":"Fan Out","config":{}}},{"id":"n3","type":"agent","position":{"x":600,"y":80},"data":{"label":"Researcher 1","config":{"agentId":""}}},{"id":"n4","type":"agent","position":{"x":600,"y":200},"data":{"label":"Researcher 2","config":{"agentId":""}}},{"id":"n5","type":"agent","position":{"x":600,"y":320},"data":{"label":"Researcher 3","config":{"agentId":""}}},{"id":"n6","type":"agent","position":{"x":860,"y":200},"data":{"label":"Aggregator","config":{"agentId":""}}},{"id":"n7","type":"output","position":{"x":1120,"y":200},"data":{"label":"Research Report","config":{"channel":"log"}}}],"edges":[{"id":"e1","source":"n1","target":"n2"},{"id":"e2","source":"n2","target":"n3"},{"id":"e3","source":"n2","target":"n4"},{"id":"e4","source":"n2","target":"n5"},{"id":"e5","source":"n3","target":"n6"},{"id":"e6","source":"n4","target":"n6"},{"id":"e7","source":"n5","target":"n6"},{"id":"e8","source":"n6","target":"n7"}]}}$$,
 NULL, 'platform', now(), 'platform', now()),

-- Template D: Escalation / Retry Loop (RIGOROUS + MANUAL)
('WORKFLOW',
 'Escalation / Retry Loop',
 'Attempt → evaluate → retry or escalate to human. RIGOROUS reasoning double-checks every step output and verifies the evaluation before escalating. MANUAL tuning lets you precisely control step count and timeout for sensitive retry flows. Best for high-stakes processes where a wrong decision costs more than latency.',
 'Operations',
 '["reasoning:RIGOROUS","caching:off","cost-routing:off","tuning:manual","pattern:escalation","pattern:retry"]',
 'APPROVED',
 $${"name":"Escalation / Retry Loop","description":"Attempt → evaluate → supervisor routes to retry or escalate. RIGOROUS reasoning on every step.","reasoningDepth":"RIGOROUS","tuningMode":"MANUAL","stepCachingEnabled":false,"costRoutingEnabled":false,"maxStepsPerRun":25,"stepTimeoutSeconds":60,"graph":{"nodes":[{"id":"n1","type":"trigger","position":{"x":80,"y":160},"data":{"label":"Task Start","config":{}}},{"id":"n2","type":"agent","position":{"x":340,"y":160},"data":{"label":"Attempt Task","config":{"agentId":""}}},{"id":"n3","type":"supervisor","position":{"x":600,"y":160},"data":{"label":"Evaluate Result","config":{"agentId":"","instructions":"Evaluate whether the attempt succeeded. Route to retry if fixable automatically, escalate if human intervention needed.","routes":[{"handle":"retry","label":"Retry"},{"handle":"escalate","label":"Escalate"}]}}},{"id":"n4","type":"agent","position":{"x":860,"y":80},"data":{"label":"Retry Attempt","config":{"agentId":""}}},{"id":"n5","type":"approval","position":{"x":860,"y":240},"data":{"label":"Human Escalation","config":{}}},{"id":"n6","type":"output","position":{"x":1120,"y":160},"data":{"label":"Resolution","config":{"channel":"log"}}}],"edges":[{"id":"e1","source":"n1","target":"n2"},{"id":"e2","source":"n2","target":"n3"},{"id":"e3","source":"n3","target":"n4","sourceHandle":"retry"},{"id":"e4","source":"n3","target":"n5","sourceHandle":"escalate"},{"id":"e5","source":"n4","target":"n6"},{"id":"e6","source":"n5","target":"n6"}]}}$$,
 NULL, 'platform', now(), 'platform', now());
