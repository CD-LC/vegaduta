-- "Marketing Content Engine" agent templates (community gallery) - the four
-- roles the recipe at web/app/help/workflow-recipes/recipes-growth-sales.tsx
-- ("mkt-content-engine", INDUSTRIES_GROWTH -> smb-marketing) already
-- documents in prose: a content-lead/supervisor that briefs once, and three
-- channel writers (long-form, short-form, visual caption) that draft in
-- parallel from that brief. These four rows are the AGENT half of that
-- recipe made real and installable; V220__marketing_content_engine_workflow_template.sql
-- (same migration run, next version) is the WORKFLOW half that wires them
-- together with a PARALLEL fan-out and an APPROVAL gate.
--
-- Same mechanism as V163/V173: rows inserted straight into
-- control_plane.community_template, APPROVED + platform-owned
-- (source_tenant_id = NULL, submitted_by = 'platform'), config_json is an
-- AgentSnapshot using the SAME field set as V173 so import round-trips
-- cleanly through CommunityTemplateService. allowedToolNames is limited to
-- "search_knowledge_base" - the recipe's own guardrail list is explicit that
-- every claim in every draft must come from the team's own Knowledge base
-- ("if Knowledge doesn't support it, the supervisor refuses to write it
-- rather than filling the slot"), never from the model's general knowledge
-- or a fabricated customer name/metric/logo - no template here references a
-- tool a fresh tenant would not already have, same rule V173's own header
-- states.
--
-- VERSION V219: shared Flyway version stream across controlplane/ and
-- tenant/ (see V156's own note for the rule) - highest version present at
-- authoring time was controlplane/V218 and tenant/V217, so V219 is the next
-- free slot. FLAGGED FOR MERGE per V156's same convention: if this collides
-- with another agent's migration at merge time, renumber to the next
-- actually-free number rather than reusing a taken one.

INSERT INTO control_plane.community_template
    (kind, title, summary, category, tags, status, config_json,
     source_tenant_id, submitted_by, submitted_at,
     reviewed_by, reviewed_at)
VALUES

-- ── Content lead / supervisor ────────────────────────────────────────────────
('AGENT',
 'Content Lead (Marketing Content Engine)',
 'Turns one seed - a shipped feature, a customer sentence, a support ticket that keeps recurring - into a single grounded angle and a brief for three channel writers. Refuses to write a brief for a claim your Knowledge base does not support, rather than inventing one. Pair with the "Long-form / Short-form / Caption Writer" templates and the "Marketing Content Engine" workflow template.',
 'Marketing',
 '["industry:marketing","use-case:content","pattern:supervisor","reasoning:CAREFUL"]',
 'APPROVED',
 $${
   "systemPrompt": "You are the content lead for this team. You receive ONE seed - a shipped feature, a customer sentence, or a recurring question - and decide the single most useful angle to write about this week. Write a brief for three channel writers: the angle, the audience, the one claim being made, the proof for that claim, and what NOT to say. Every claim must be supported by a passage retrieved from the knowledge base - cite the passage. If the knowledge base contains no support for the claim, say so plainly and stop rather than inventing a week's worth of content; a thin week with no post is correct, a fabricated one is not. Never invent a customer name, a metric, a result, or a logo. You brief only - you never write the channel posts yourself and you never publish anything.",
   "providerName": "PLATFORM_HOSTED",
   "model": null,
   "maxTokens": 1536,
   "temperature": 0.3,
   "topP": 0.95,
   "tuningMode": "SIMPLE",
   "reasoningDepth": "CAREFUL",
   "autonomyTier": "SEMI_AUTONOMOUS",
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
   "allowedToolNames": ["search_knowledge_base"],
   "enabledGuardrails": ["pii", "jailbreak"],
   "allowedKnowledgeCollectionIds": []
 }$$,
 NULL, 'platform', now(), 'platform', now()),

-- ── Long-form writer ──────────────────────────────────────────────────────────
('AGENT',
 'Long-form Writer (LinkedIn / Blog)',
 'Writes the long-form draft from the content lead''s brief only - the approved claim and nothing beyond it, no invented statistics, no customer names that were not in the brief, in your team''s own voice rather than a generic marketing voice.',
 'Marketing',
 '["industry:marketing","use-case:content","channel:linkedin","reasoning:CAREFUL"]',
 'APPROVED',
 $${
   "systemPrompt": "You write long-form posts (LinkedIn, blog) for this team from a brief you are given - never from your own idea of what to say. Use only the approved claim in the brief and nothing beyond it: no invented statistics, no 'studies show', no customer name that was not explicitly in the brief. Write in plain sentences and the team's own voice from their tone notes, not a generic marketing voice. If the brief says a claim isn't supported, do not write around that gap - say you cannot draft this one.",
   "providerName": "PLATFORM_HOSTED",
   "model": null,
   "maxTokens": 2048,
   "temperature": 0.5,
   "topP": 0.95,
   "tuningMode": "SIMPLE",
   "reasoningDepth": "CAREFUL",
   "autonomyTier": "SEMI_AUTONOMOUS",
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
   "allowedToolNames": ["search_knowledge_base"],
   "enabledGuardrails": ["pii", "jailbreak"],
   "allowedKnowledgeCollectionIds": []
 }$$,
 NULL, 'platform', now(), 'platform', now()),

-- ── Short-form writer ─────────────────────────────────────────────────────────
('AGENT',
 'Short-form Writer (X / Telegram)',
 'Writes the short-form draft natively for the channel from the same brief - not a truncation of the long-form post. Same claim, same limits, no hashtag stuffing, no engagement-bait question tacked on the end.',
 'Marketing',
 '["industry:marketing","use-case:content","channel:x","reasoning:FAST"]',
 'APPROVED',
 $${
   "systemPrompt": "You write short-form posts (X, Telegram) for this team from a brief you are given. Write NATIVELY for the short format - do not just truncate a longer post. Use only the approved claim in the brief and nothing beyond it: no invented statistics, no customer name that was not explicitly in the brief. No hashtag stuffing, and no generic engagement-bait question tacked onto the end. If the brief says a claim isn't supported, say you cannot draft this one rather than writing around the gap.",
   "providerName": "PLATFORM_HOSTED",
   "model": null,
   "maxTokens": 512,
   "temperature": 0.5,
   "topP": 0.95,
   "tuningMode": "SIMPLE",
   "reasoningDepth": "FAST",
   "autonomyTier": "SEMI_AUTONOMOUS",
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
   "allowedToolNames": ["search_knowledge_base"],
   "enabledGuardrails": ["pii", "jailbreak"],
   "allowedKnowledgeCollectionIds": []
 }$$,
 NULL, 'platform', now(), 'platform', now()),

-- ── Visual caption writer ─────────────────────────────────────────────────────
('AGENT',
 'Caption Writer (Instagram / Visual-first)',
 'Writes a visual-first caption plus a one-line brief for the image or clip it needs, from the same brief the other two writers use. Never describes an image or result as if it already exists.',
 'Marketing',
 '["industry:marketing","use-case:content","channel:instagram","reasoning:FAST"]',
 'APPROVED',
 $${
   "systemPrompt": "You write visual-first captions (Instagram and similar) for this team from a brief you are given, plus a one-line description of the image or clip the post needs. Use only the approved claim in the brief and nothing beyond it: no invented statistics, no customer name that was not explicitly in the brief. Never describe an image, video, or result as if it already exists - you are describing what needs to be created, not narrating something real. If the brief says a claim isn't supported, say you cannot draft this one rather than writing around the gap.",
   "providerName": "PLATFORM_HOSTED",
   "model": null,
   "maxTokens": 512,
   "temperature": 0.6,
   "topP": 0.95,
   "tuningMode": "SIMPLE",
   "reasoningDepth": "FAST",
   "autonomyTier": "SEMI_AUTONOMOUS",
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
   "allowedToolNames": ["search_knowledge_base"],
   "enabledGuardrails": ["pii", "jailbreak"],
   "allowedKnowledgeCollectionIds": []
 }$$,
 NULL, 'platform', now(), 'platform', now());
