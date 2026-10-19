-- Starter agent for a fresh install: "Personal Assistant".
--
-- Why. The gallery's action templates (V223) need a Google connection before
-- they can do anything, so a first-time user on a free local model has nothing
-- to import that works in the first minute. This template uses only tools that
-- need no credential, so a new install can import it and watch an agent
-- actually do something, whatever model backs the platform pool.
--
-- Small-model friendly on purpose: a short prompt, low temperature and only
-- five tools, because a 4B-9B model picks the right tool far more reliably from
-- a short list (see docs/local-models.md, "Tool calls from small models").
-- providerName PLATFORM_HOSTED means "use the platform pool", which on a
-- --local-ai install is the local open-weights model. SUPERVISED autonomy keeps
-- every side effect behind an approval.
--
-- Platform-owned row (same shape as V223): source_tenant_id NULL, APPROVED.

INSERT INTO control_plane.community_template
    (kind, title, summary, category, tags, status, config_json,
     source_tenant_id, submitted_by, submitted_at, reviewed_by, reviewed_at)
VALUES
('AGENT',
 'Personal Assistant (works on a local model)',
 'Looks things up on the web, remembers what you tell it, and writes you a document when you ask. Needs no account or API key, so it works the minute your install is up, including on a free open-weights model. Anything with a side effect waits for your approval.',
 'Productivity',
 '["industry:any","use-case:assistant","tool:web","tool:memory","autonomy:supervised","runs-on:local-model"]',
 'APPROVED',
 $${
   "systemPrompt": "You are the user's personal assistant. Be brief and concrete.\n\nTools, and when to use each:\n- fetch_web_page: when the user gives a link or asks about something current. Read the page, then answer from it. Say which page you used.\n- remember_fact: when the user tells you something about themselves or asks you to remember it. Store one short fact.\n- recall_related_facts: before answering anything personal, check what you already know.\n- generate_document: when the user asks for a written deliverable (brief, summary, plan). Write the full text.\n- request_human: when a request is ambiguous or has a real-world consequence. Ask one short question instead of guessing.\n\nRules: use a tool only when it helps; never invent a page you did not read; if a tool fails, say so and offer what you can do instead.",
   "providerName": "PLATFORM_HOSTED",
   "model": null,
   "maxTokens": 1536,
   "temperature": 0.2,
   "topP": 0.9,
   "tuningMode": "SIMPLE",
   "reasoningDepth": "BALANCED",
   "autonomyTier": "SUPERVISED",
   "experienceMode": null,
   "knowledgeBaseEnabled": true,
   "sarvamEnabled": false,
   "e2bEnabled": false,
   "codeWorkspaceEnabled": false,
   "responseCachingEnabled": false,
   "semanticResponseCachingEnabled": false,
   "shareMemoryWithTenant": false,
   "costRoutingEnabled": false,
   "economyProviderName": null,
   "economyModel": null,
   "fallbackProviderName": "PLATFORM_HOSTED",
   "fallbackModel": null,
   "voiceModelProvider": null,
   "voiceModel": null,
   "videoModelProvider": null,
   "videoModel": null,
   "allowedToolNames": ["fetch_web_page", "remember_fact", "recall_related_facts", "generate_document", "request_human"],
   "enabledGuardrails": ["pii", "jailbreak"],
   "allowedKnowledgeCollectionIds": []
 }$$,
 NULL, 'platform', now(), 'platform', now());
