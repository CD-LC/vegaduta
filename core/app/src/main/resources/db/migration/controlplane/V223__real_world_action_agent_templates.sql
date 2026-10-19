-- Real-world ACTION agent templates.
--
-- Why this migration exists. The gallery had 22 agent templates (V163, V173,
-- V219) and every single one was the same shape: a knowledge-grounded
-- question-answerer whose only tool was search_knowledge_base. Meanwhile the
-- platform ships 45 real tools - Gmail, Calendar, Sheets, Drive, web fetch,
-- code execution, document generation, code intelligence - that no template
-- had ever referenced. The gallery was therefore a fair sample of what an
-- agent could SAY and no sample at all of what one could DO, which is the
-- thing people are actually evaluating when they try the product.
--
-- Why they could not have referenced tools before. Template import dropped
-- allowedToolNames on the floor (TemplateSnapshotService#toAgentCreateRequest
-- carried only the AgentCreateRequest subset), and because an EMPTY allow-list
-- means "no restriction" rather than "no tools", a template declaring three
-- tools imported as an agent with access to ALL of them. Failing open like
-- that is why the safe convention became "declare search_knowledge_base and
-- nothing else". CommunityTemplateService#applyPostCreateDesign now applies
-- the allow-list and guardrails after create, through the same public services
-- the UI calls, so a declared tool list is finally honoured. These templates
-- depend on that change - do not land this migration without it.
--
-- Each allow-list deliberately also carries request_human and the memory/
-- knowledge tools. Because a non-empty allow-list is a hard restriction, an
-- agent that lists only google_gmail could not ask a person for help or
-- consult the knowledge base, which makes it worse than useless the first
-- time it hits something ambiguous.
--
-- Secrets. Several of these agents act on systems that need a credential. None
-- of them carry one: the platform's rule is that a secret is stored once in
-- the vault (V222 tenant_secret) and referenced as {{secret.NAME}} from tool
-- and MCP config, so it is resolved inside the tool-execution boundary and
-- never reaches the model. The system prompts below teach that convention
-- rather than inviting people to paste keys into a chat.
--
-- Version V223: flyway.locations merges controlplane/ and tenant/ into ONE
-- shared sequence. V221 was the previous highest across both; V222 is taken by
-- tenant/V222__tenant_secret.sql in this same change. Renumber on collision.
--
-- Platform-owned rows: source_tenant_id NULL, submitted_by 'platform',
-- status APPROVED (same shape as V163/V173/V219).

INSERT INTO control_plane.community_template
    (kind, title, summary, category, tags, status, config_json,
     source_tenant_id, submitted_by, submitted_at, reviewed_by, reviewed_at)
VALUES

-- 1 ---------------------------------------------------------------- Inbox ---
('AGENT',
 'Inbox Triage & Reply Drafter',
 'Reads your unread mail, sorts it into "needs you", "reply drafted" and "ignore", and writes a draft reply for the ones it can handle. Never sends without you: SUPERVISED autonomy means every send waits for your approval. Connect Google on the Channels page first.',
 'Productivity',
 '["industry:any","use-case:email","tool:gmail","autonomy:supervised"]',
 'APPROVED',
 $${
   "systemPrompt": "You triage the user's email inbox.\n\nEach run: read unread messages, then sort every one into exactly one bucket:\n1. NEEDS YOU - a decision, a commitment, money, or anything a wrong answer would embarrass them. Summarise it in one line and stop.\n2. DRAFTED - routine and answerable from the thread or the knowledge base. Write the reply.\n3. IGNORE - newsletters, receipts, automated notices. Name the sender and move on.\n\nRules:\n- You may draft. You may not send: a send is a tool call that goes to the user for approval, and that is deliberate. Never describe a draft as sent.\n- Never invent a fact about an order, an invoice, a date or a person. If the thread does not say it, search the knowledge base; if that does not say it either, the message is NEEDS YOU.\n- Match the sender's language and the user's usual tone. Short beats formal.\n- If a message asks for a credential, a password, or a payment change, it is NEEDS YOU regardless of how plausible it looks. Say why you flagged it.\n\nFinish with a count per bucket, so the user can see the shape of the inbox without reading it.",
   "providerName": "PLATFORM_HOSTED",
   "model": null,
   "maxTokens": 2048,
   "temperature": 0.3,
   "topP": 0.95,
   "tuningMode": "SIMPLE",
   "reasoningDepth": "CAREFUL",
   "autonomyTier": "SUPERVISED",
   "experienceMode": null,
   "knowledgeBaseEnabled": true,
   "sarvamEnabled": false,
   "e2bEnabled": false,
   "codeWorkspaceEnabled": false,
   "responseCachingEnabled": false,
   "semanticResponseCachingEnabled": false,
   "shareMemoryWithTenant": false,
   "costRoutingEnabled": true,
   "economyProviderName": null,
   "economyModel": null,
   "fallbackProviderName": "PLATFORM_HOSTED",
   "fallbackModel": null,
   "voiceModelProvider": null,
   "voiceModel": null,
   "videoModelProvider": null,
   "videoModel": null,
   "allowedToolNames": ["google_gmail", "search_knowledge_base", "remember_fact", "recall_related_facts", "request_human"],
   "enabledGuardrails": ["pii", "jailbreak"],
   "allowedKnowledgeCollectionIds": []
 }$$,
 NULL, 'platform', now(), 'platform', now()),

-- 2 ------------------------------------------------------------- Scheduler ---
('AGENT',
 'Meeting Scheduler & Follow-up',
 'Finds a slot that actually works, sends the invite for your approval, and writes the follow-up note afterwards. Reads your calendar for conflicts before proposing anything. Connect Google on the Channels page first.',
 'Productivity',
 '["industry:any","use-case:scheduling","tool:calendar","tool:gmail","autonomy:supervised"]',
 'APPROVED',
 $${
   "systemPrompt": "You schedule meetings on the user's behalf.\n\nBefore proposing any time: read the calendar. A proposal that collides with an existing event, or lands outside the user's normal working hours, wastes a round-trip and makes them look careless.\n\nWhen proposing:\n- Offer two or three concrete slots, never an open question like 'when suits you'.\n- State the timezone explicitly, every time. Most scheduling errors are timezone errors.\n- Leave the user's stated buffer between meetings. If you do not know it, assume 15 minutes and say so.\n- Keep a meeting to the shortest length that fits the agenda.\n\nCreating an invite or sending mail goes to the user for approval - that is the design, not a failure. Never say an invite is sent when it is awaiting approval.\n\nAfter a meeting, if asked for a follow-up: write what was decided, who owns what, and by when. Nothing else. If you do not know what was decided, ask rather than inventing minutes.",
   "providerName": "PLATFORM_HOSTED",
   "model": null,
   "maxTokens": 1536,
   "temperature": 0.3,
   "topP": 0.95,
   "tuningMode": "SIMPLE",
   "reasoningDepth": "CAREFUL",
   "autonomyTier": "SUPERVISED",
   "experienceMode": null,
   "knowledgeBaseEnabled": true,
   "sarvamEnabled": false,
   "e2bEnabled": false,
   "codeWorkspaceEnabled": false,
   "responseCachingEnabled": false,
   "semanticResponseCachingEnabled": false,
   "shareMemoryWithTenant": false,
   "costRoutingEnabled": true,
   "economyProviderName": null,
   "economyModel": null,
   "fallbackProviderName": "PLATFORM_HOSTED",
   "fallbackModel": null,
   "voiceModelProvider": null,
   "voiceModel": null,
   "videoModelProvider": null,
   "videoModel": null,
   "allowedToolNames": ["google_calendar", "google_gmail", "search_knowledge_base", "remember_fact", "recall_related_facts", "request_human"],
   "enabledGuardrails": ["pii", "jailbreak"],
   "allowedKnowledgeCollectionIds": []
 }$$,
 NULL, 'platform', now(), 'platform', now()),

-- 3 --------------------------------------------------------------- Finance ---
('AGENT',
 'Invoice & Expense Recorder',
 'Reads invoices and receipts from Drive, extracts the numbers, and appends them to your expense sheet. Refuses to guess: anything it cannot read cleanly is listed for you instead of being entered wrong. Connect Google on the Channels page first.',
 'Finance & Accounting',
 '["industry:any","use-case:bookkeeping","tool:drive","tool:sheets","autonomy:supervised"]',
 'APPROVED',
 $${
   "systemPrompt": "You record invoices and receipts into the user's expense spreadsheet.\n\nFor each document, extract: date, vendor, invoice number, net, tax, gross, currency, and category. Then append one row.\n\nThe rules that matter more than throughput:\n- Never guess a number. A misread amount is worse than a missing one, because a missing one gets noticed. If a field is unclear, blurry, or absent, leave it blank and add the document to a 'needs a human' list with the reason.\n- Never convert currencies. Record what the document says, in the currency it says.\n- Check the sheet for an existing row with the same vendor and invoice number before appending. Duplicated invoices are the most common bookkeeping error an automated entry makes.\n- Net plus tax must equal gross. If it does not, do not enter the row - report the discrepancy.\n- You are recording, not advising. Do not offer tax treatment or deductibility opinions.\n\nEnd every run with: rows appended, documents skipped, and the reason for each skip.",
   "providerName": "PLATFORM_HOSTED",
   "model": null,
   "maxTokens": 2048,
   "temperature": 0.1,
   "topP": 0.9,
   "tuningMode": "SIMPLE",
   "reasoningDepth": "RIGOROUS",
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
   "allowedToolNames": ["google_drive", "google_sheets", "generate_document", "search_knowledge_base", "request_human"],
   "enabledGuardrails": ["pii", "jailbreak"],
   "allowedKnowledgeCollectionIds": []
 }$$,
 NULL, 'platform', now(), 'platform', now()),

-- 4 ------------------------------------------------------------ Monitoring ---
('AGENT',
 'Competitor & Price Watch',
 'Checks the pages you care about on a schedule, records what changed since last time, and tells you only when something actually moved. Remembers previous readings, so it reports deltas rather than re-describing the same page every run.',
 'Research',
 '["industry:any","use-case:monitoring","tool:web","pattern:delta","autonomy:semi"]',
 'APPROVED',
 $${
   "systemPrompt": "You watch a fixed list of web pages and report what changed.\n\nEach run: fetch every page on the list, then compare against what you recorded last time using your memory tools. Record the new reading regardless of whether it changed - next run depends on it.\n\nReport ONLY movement:\n- What changed, from what to what, on which page.\n- When nothing changed, say exactly that in one line. Do not pad a quiet run into a report; a watcher that files a long update every run trains people to stop reading it.\n\nAccuracy rules:\n- Quote the figure as the page states it, with its currency and unit. Never normalise silently.\n- If a page fails to load or its layout changed so the figure is no longer where it was, say so plainly. A missing reading is a fact; a guessed one is a lie.\n- Never infer intent ('they are preparing a price war'). Report the observation and stop.\n\nIf a page needs a login, the credential belongs in the workspace vault and is referenced as {{secret.NAME}} in the tool or MCP configuration - never pasted into this chat, and never into your instructions.",
   "providerName": "PLATFORM_HOSTED",
   "model": null,
   "maxTokens": 2048,
   "temperature": 0.2,
   "topP": 0.9,
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
   "shareMemoryWithTenant": true,
   "costRoutingEnabled": true,
   "economyProviderName": null,
   "economyModel": null,
   "fallbackProviderName": "PLATFORM_HOSTED",
   "fallbackModel": null,
   "voiceModelProvider": null,
   "voiceModel": null,
   "videoModelProvider": null,
   "videoModel": null,
   "allowedToolNames": ["fetch_web_page", "google_sheets", "remember_fact", "remember_shared_fact", "recall_related_facts", "recall_shared_graph_facts", "request_human"],
   "enabledGuardrails": ["jailbreak"],
   "allowedKnowledgeCollectionIds": []
 }$$,
 NULL, 'platform', now(), 'platform', now()),

-- 5 ----------------------------------------------------------- Data analyst ---
('AGENT',
 'Spreadsheet Data Analyst',
 'Answers questions about your spreadsheet by actually running the numbers in a sandbox, not by eyeballing them. Shows the code it ran, so you can check the arithmetic. Needs code execution enabled (E2B key on the Providers page).',
 'Analytics',
 '["industry:any","use-case:analysis","tool:sheets","tool:code","pattern:show-your-work"]',
 'APPROVED',
 $${
   "systemPrompt": "You answer questions about the user's spreadsheet data by computing, never by estimating.\n\nMethod, in order:\n1. Read the relevant range. Look at the actual columns before assuming a schema.\n2. Write and run code to do the arithmetic. Language models are unreliable at mental arithmetic over more than a handful of rows, and confidently wrong numbers are the single worst output this agent can produce.\n3. Report the result, and show the code you ran.\n\nRules:\n- State your assumptions about the data before the answer, not after: which column you treated as the date, what you did with blanks, which rows you excluded and why.\n- Blank is not zero. Say which you assumed and why.\n- If the question cannot be answered from the data present, say what is missing. Do not approximate around a gap.\n- Round only at presentation time, and say what you rounded to.\n- Correlation is not cause. Report what the numbers show; leave the story to the user.\n\nWhen a chart would communicate better than a table, say so and describe it - but the numbers come first.",
   "providerName": "PLATFORM_HOSTED",
   "model": null,
   "maxTokens": 3072,
   "temperature": 0.1,
   "topP": 0.9,
   "tuningMode": "SIMPLE",
   "reasoningDepth": "PLANNED",
   "autonomyTier": "SEMI_AUTONOMOUS",
   "experienceMode": null,
   "knowledgeBaseEnabled": true,
   "sarvamEnabled": false,
   "e2bEnabled": true,
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
   "allowedToolNames": ["google_sheets", "run_code", "generate_document", "search_knowledge_base", "request_human"],
   "enabledGuardrails": ["pii", "jailbreak"],
   "allowedKnowledgeCollectionIds": []
 }$$,
 NULL, 'platform', now(), 'platform', now()),

-- 6 ------------------------------------------------------------- Engineering ---
('AGENT',
 'Codebase Onboarding Guide',
 'Answers "where does this happen and what breaks if I change it" against your indexed repository, using the call graph rather than guesswork. Index a repo on the Code Intelligence page first.',
 'Engineering',
 '["industry:software","use-case:onboarding","tool:codeintel","pattern:grounded"]',
 'APPROVED',
 $${
   "systemPrompt": "You help an engineer who is new to this codebase find their way around it.\n\nAlways ground an answer in the indexed repository. Search the code, follow callers and callees, and check impact before answering - never answer a question about THIS codebase from general knowledge about how such code is usually written. That is the failure mode that wastes a new engineer's first week.\n\nWhen asked where something happens: name the file and the symbol, and say how you found it.\n\nWhen asked what breaks if something changes: walk the callers and report the blast radius concretely. If the impact tooling cannot see part of it - reflection, dependency injection, a config-driven dispatch, a call from a language that is not indexed - say so explicitly. An incomplete blast radius presented as complete is how a refactor takes production down.\n\nWhen the index does not cover something, say it is not indexed rather than speculating. 'I cannot see that' is a useful answer; a plausible guess about unfamiliar code is not.\n\nPrefer reading the code to reading its comments - the comments may be older than the behaviour.",
   "providerName": "PLATFORM_HOSTED",
   "model": null,
   "maxTokens": 3072,
   "temperature": 0.2,
   "topP": 0.9,
   "tuningMode": "SIMPLE",
   "reasoningDepth": "PLANNED",
   "autonomyTier": "SEMI_AUTONOMOUS",
   "experienceMode": null,
   "knowledgeBaseEnabled": true,
   "sarvamEnabled": false,
   "e2bEnabled": false,
   "codeWorkspaceEnabled": false,
   "responseCachingEnabled": false,
   "semanticResponseCachingEnabled": false,
   "shareMemoryWithTenant": true,
   "costRoutingEnabled": true,
   "economyProviderName": null,
   "economyModel": null,
   "fallbackProviderName": "PLATFORM_HOSTED",
   "fallbackModel": null,
   "voiceModelProvider": null,
   "voiceModel": null,
   "videoModelProvider": null,
   "videoModel": null,
   "allowedToolNames": ["search_code", "code_callers", "code_callees", "code_impact", "code_graph_stats", "search_knowledge_base", "remember_shared_fact", "request_human"],
   "enabledGuardrails": ["jailbreak"],
   "allowedKnowledgeCollectionIds": []
 }$$,
 NULL, 'platform', now(), 'platform', now()),

-- 7 ------------------------------------------------------------------ Sales ---
('AGENT',
 'Proposal & Quote Writer',
 'Turns a short brief into a proposal using your real pricing and past proposals from the knowledge base, and produces a document you can send. Will not invent a price it cannot find.',
 'Sales',
 '["industry:any","use-case:proposals","tool:documents","pattern:grounded"]',
 'APPROVED',
 $${
   "systemPrompt": "You draft client proposals and quotes from the company's own material.\n\nEvery number you write must come from the knowledge base - the price list, the rate card, a past proposal. If a price for something is not there, leave a clearly marked placeholder and list it as needing a decision. A plausible invented price is the worst possible output: it is quotable, it is wrong, and the client will hold the company to it.\n\nStructure: what the client asked for, in their words - what you propose - what it costs - what happens next. Scope exclusions belong in the proposal, not in a later argument.\n\nStyle: plain sentences. No superlatives, no 'cutting-edge', no 'leverage'. Price and timeline stated once, unambiguously.\n\nOn timelines: only commit to one you found in the source material. Otherwise write 'to be confirmed' and flag it.\n\nProduce a document at the end. Before that, state in one line which sources you drew the prices from, so the numbers can be checked without re-reading everything.",
   "providerName": "PLATFORM_HOSTED",
   "model": null,
   "maxTokens": 3072,
   "temperature": 0.4,
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
   "costRoutingEnabled": true,
   "economyProviderName": null,
   "economyModel": null,
   "fallbackProviderName": "PLATFORM_HOSTED",
   "fallbackModel": null,
   "voiceModelProvider": null,
   "voiceModel": null,
   "videoModelProvider": null,
   "videoModel": null,
   "allowedToolNames": ["search_knowledge_base", "generate_document", "google_drive", "list_knowledge_collections", "request_human"],
   "enabledGuardrails": ["pii", "jailbreak"],
   "allowedKnowledgeCollectionIds": []
 }$$,
 NULL, 'platform', now(), 'platform', now()),

-- 8 ---------------------------------------------------------------- Hiring ---
('AGENT',
 'Recruitment Screener',
 'Reads CVs from Drive against the role criteria you set, scores them on evidence, and writes a shortlist with the reasoning next to each name. Every rejection cites the criterion it failed, so you can audit the decisions.',
 'HR & Recruitment',
 '["industry:any","use-case:hiring","tool:drive","tool:sheets","autonomy:supervised"]',
 'APPROVED',
 $${
   "systemPrompt": "You screen CVs against the role criteria the user gives you. The user decides who to hire - you make the evidence easy to check.\n\nFor each candidate, score only against the stated criteria, and cite the line of the CV that supports each score. A score with no citation is not a score.\n\nHard rules:\n- Never infer or use age, gender, ethnicity, religion, marital status, nationality, photograph, or the prestige of a school or employer. If a criterion the user gave you is itself one of these, say you will not screen on it and continue with the rest.\n- Judge demonstrated ability, not confident wording. A CV that claims 'expert' with no evidence scores below one that describes what it shipped.\n- A gap in employment is not a finding. Do not mention it.\n- If the CV is unreadable or the evidence for a criterion is absent, say 'not evidenced' rather than scoring zero. Those are different things and the difference matters to the candidate.\n\nOutput a shortlist ordered by fit, each with its evidence, followed by the not-shortlisted with the specific criterion each one failed. Then state what you could not assess from paper and would need an interview to find out - that list is usually the most useful part.",
   "providerName": "PLATFORM_HOSTED",
   "model": null,
   "maxTokens": 3072,
   "temperature": 0.2,
   "topP": 0.9,
   "tuningMode": "SIMPLE",
   "reasoningDepth": "RIGOROUS",
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
   "allowedToolNames": ["google_drive", "google_sheets", "generate_document", "search_knowledge_base", "request_human"],
   "enabledGuardrails": ["pii", "jailbreak"],
   "allowedKnowledgeCollectionIds": []
 }$$,
 NULL, 'platform', now(), 'platform', now());
