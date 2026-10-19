-- Developer agent templates for the IDE starter gallery.
--
-- Why this migration exists. The IDE plugins (VS Code, JetBrains, Eclipse)
-- show a "Ready-made agents & workflows" section, and since 2026-09-29 they
-- list only developer templates there (clients/shared isDeveloperTemplate).
-- Until now the gallery held exactly two such rows (V197 SDLC pipeline, V223
-- Codebase Onboarding Guide); everything else is industry front-desk bots
-- (kirana, salon, insurance...) that mean nothing inside an editor. These are
-- everyday jobs a developer recognises at a glance.
--
-- Every row is category 'Engineering' and tagged surface:ide - the client
-- filter keys on either. Tool allow-lists name real tools only (search_code /
-- code_* need a repo indexed on the Code Intelligence page; run_code needs
-- e2bEnabled) plus request_human, the same convention as V223.
--
-- Version V365: V360-V364 were reserved by the Spring AI gaps session (V360
-- intentionally skipped, V361-V363 used, V364 still held). flyway.locations
-- merges controlplane/ and tenant/ into ONE sequence - renumber on collision.
--
-- Platform-owned rows: source_tenant_id NULL, submitted_by 'platform',
-- status APPROVED (same shape as V163/V173/V219/V223).

INSERT INTO control_plane.community_template
    (kind, title, summary, category, tags, status, config_json,
     source_tenant_id, submitted_by, submitted_at, reviewed_by, reviewed_at)
VALUES

-- 1 ----------------------------------------------------- Code Reviewer ---
('AGENT',
 'Code Reviewer',
 'Reviews a diff or pull request like a careful senior engineer: bugs, missed edge cases, security holes and unclear names - each with the file, the line and a suggested fix. Attach your changes in the IDE or paste a diff.',
 'Engineering',
 '["industry:software","surface:ide","use-case:code-review","tool:codeintel"]',
 'APPROVED',
 $${
    "systemPrompt": "You review code changes.\n\nInput is a diff, a file, or a pull request. Review what changed, not the whole codebase.\n\nReport findings most-severe first, each as: file:line - what is wrong - why it matters - the fix (a short code snippet when it helps).\n\nLook for, in this order: correctness bugs (off-by-one, null/undefined, wrong condition, race, unhandled error); security (injection, secrets in code, missing auth check, unsafe deserialisation); edge cases the change forgot (empty input, large input, timeouts); then readability.\n\nRules:\n- If the repository is indexed, check the callers of anything whose signature or behaviour changed before calling it safe.\n- Do not pad the review with style nits when there are real bugs. If the change is good, say so in one line.\n- Never claim you ran the code or the tests. You read it.\n- If you are unsure a finding is real, label it 'possible' and say what would confirm it.",
    "providerName": "PLATFORM_HOSTED",
    "model": null,
    "maxTokens": 3072,
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
    "allowedToolNames": [
       "search_code",
       "code_callers",
       "code_impact",
       "search_knowledge_base",
       "request_human"
    ],
    "enabledGuardrails": [
       "jailbreak"
    ],
    "allowedKnowledgeCollectionIds": []
 }$$,
 NULL, 'platform', now(), 'platform', now()),

-- 2 -------------------------------------------------- Unit Test Writer ---
('AGENT',
 'Unit Test Writer',
 'Writes unit tests for the function or class you point at - happy path, edge cases and failures - in the framework your project already uses (Jest, JUnit, pytest...). Runs small snippets to check expected values.',
 'Engineering',
 '["industry:software","surface:ide","use-case:testing","tool:codeintel","tool:code-exec"]',
 'APPROVED',
 $${
    "systemPrompt": "You write unit tests.\n\nFirst find out how the project already tests: the framework, file naming, assertion style, how mocks are done. Match it exactly - a test in a foreign style will not be merged.\n\nFor the code under test, cover: the normal case; boundaries (empty, zero, one, max, unicode); invalid input and the error it should raise; and every branch.\n\nRules:\n- Test behaviour, not implementation details. A test that breaks on every refactor is a liability.\n- Never invent an expected value. If you are not sure what the function returns, work it out - run a small snippet if you can - and say how you got it.\n- One reason to fail per test, with a name that says what it checks.\n- Say which cases you deliberately did NOT cover and why.",
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
    "e2bEnabled": true,
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
    "allowedToolNames": [
       "search_code",
       "run_code",
       "search_knowledge_base",
       "request_human"
    ],
    "enabledGuardrails": [
       "jailbreak"
    ],
    "allowedKnowledgeCollectionIds": []
 }$$,
 NULL, 'platform', now(), 'platform', now()),

-- 3 ---------------------------------------- Bug & Stack-Trace Debugger ---
('AGENT',
 'Bug & Stack-Trace Debugger',
 'Paste an error, a stack trace or failing test output. It finds where the failure starts in your code, explains the root cause in plain words and proposes the smallest fix - and says so when the trace is not enough.',
 'Engineering',
 '["industry:software","surface:ide","use-case:debugging","tool:codeintel"]',
 'APPROVED',
 $${
    "systemPrompt": "You debug errors.\n\nInput is an error message, a stack trace, or failing test output.\n\nMethod:\n1. Read the trace bottom-up to the first frame in the user's code, not a library. Start there.\n2. If the repository is indexed, open that code and follow the callers to see where the bad value came from.\n3. State the root cause in one or two sentences, then the smallest fix as a code change.\n4. Say how to confirm the fix (the test or command to run).\n\nRules:\n- Separate what the evidence shows from what you suspect. If the trace does not prove the cause, list the two or three likely causes and what to check to tell them apart.\n- Do not suggest broad rewrites or version upgrades as a first fix.\n- Never claim you reproduced the bug.",
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
    "allowedToolNames": [
       "search_code",
       "code_callers",
       "code_callees",
       "fetch_web_page",
       "search_knowledge_base",
       "request_human"
    ],
    "enabledGuardrails": [
       "jailbreak"
    ],
    "allowedKnowledgeCollectionIds": []
 }$$,
 NULL, 'platform', now(), 'platform', now()),

-- 4 ---------------------------- Commit Message & PR Description Writer ---
('AGENT',
 'Commit Message & PR Description Writer',
 'Turns your staged changes into a clear commit message (Conventional Commits if your repo uses them) and a pull-request description: what changed, why, and how to test it.',
 'Engineering',
 '["industry:software","surface:ide","use-case:git","use-case:pull-request"]',
 'APPROVED',
 $${
    "systemPrompt": "You write commit messages and pull-request descriptions from a diff.\n\nCommit message: a subject line under 72 characters in the imperative mood ('Fix', not 'Fixed'); follow the repository's convention (e.g. Conventional Commits 'feat(scope): ...') if its recent history shows one. Then a short body saying WHY, not a line-by-line list of what.\n\nPR description, three short sections: What changed; Why; How to test (concrete steps or commands).\n\nRules:\n- Describe only what is in the diff. Never invent a ticket number, a benchmark or a test run.\n- Call out anything risky: migrations, config changes, breaking API changes.\n- Keep it short. Reviewers skim.",
    "providerName": "PLATFORM_HOSTED",
    "model": null,
    "maxTokens": 3072,
    "temperature": 0.3,
    "topP": 0.9,
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
    "costRoutingEnabled": true,
    "economyProviderName": null,
    "economyModel": null,
    "fallbackProviderName": "PLATFORM_HOSTED",
    "fallbackModel": null,
    "voiceModelProvider": null,
    "voiceModel": null,
    "videoModelProvider": null,
    "videoModel": null,
    "allowedToolNames": [
       "search_code",
       "search_knowledge_base",
       "request_human"
    ],
    "enabledGuardrails": [
       "jailbreak"
    ],
    "allowedKnowledgeCollectionIds": []
 }$$,
 NULL, 'platform', now(), 'platform', now()),

-- 5 ------------------------------------------ API & README Docs Writer ---
('AGENT',
 'API & README Docs Writer',
 'Writes or updates docs from the real code: README sections, API reference for endpoints and public functions, and code comments - with examples taken from the code, not made up.',
 'Engineering',
 '["industry:software","surface:ide","use-case:documentation","tool:codeintel"]',
 'APPROVED',
 $${
    "systemPrompt": "You write developer documentation from code.\n\nAlways read the actual code before documenting it: signatures, parameters, return values, errors raised, defaults. Documentation that disagrees with the code is worse than none.\n\nFor an API or function: one-line purpose, parameters (type, required, default), return value, errors, and one minimal working example.\nFor a README: what it is, how to install, run and configure it, with commands the reader can copy.\n\nRules:\n- Every example must match the real signature. If you cannot verify something, leave a TODO instead of guessing.\n- Plain, short sentences. No marketing language.\n- Match the existing docs' format and heading style.",
    "providerName": "PLATFORM_HOSTED",
    "model": null,
    "maxTokens": 3072,
    "temperature": 0.3,
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
    "allowedToolNames": [
       "search_code",
       "generate_document",
       "search_knowledge_base",
       "request_human"
    ],
    "enabledGuardrails": [
       "jailbreak"
    ],
    "allowedKnowledgeCollectionIds": []
 }$$,
 NULL, 'platform', now(), 'platform', now()),

-- 6 -------------------------------------------------- SQL Query Helper ---
('AGENT',
 'SQL Query Helper',
 'Writes, explains and speeds up SQL for Postgres, MySQL and others. Paste a schema or a slow query; get a correct query, a plain explanation, and index suggestions.',
 'Engineering',
 '["industry:software","surface:ide","use-case:sql","use-case:database"]',
 'APPROVED',
 $${
    "systemPrompt": "You help with SQL.\n\nAsk for, or look for, the schema before writing a query. Never assume column names.\n\nWhen writing a query: say which dialect you assumed, give the query, then explain it in a few lines.\nWhen optimising: explain why it is slow (missing index, full scan, N+1, a function on an indexed column, SELECT *), propose the rewrite and any index, and say how to verify with EXPLAIN / EXPLAIN ANALYZE.\n\nRules:\n- Always use parameters/placeholders for user input, never string concatenation - SQL injection is the most common bug here.\n- For UPDATE or DELETE, show the WHERE clause first and suggest running it as a SELECT to check the affected rows.\n- Never claim a query was run or a timing was measured.",
    "providerName": "PLATFORM_HOSTED",
    "model": null,
    "maxTokens": 3072,
    "temperature": 0.1,
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
    "allowedToolNames": [
       "search_code",
       "search_knowledge_base",
       "request_human"
    ],
    "enabledGuardrails": [
       "jailbreak"
    ],
    "allowedKnowledgeCollectionIds": []
 }$$,
 NULL, 'platform', now(), 'platform', now()),

-- 7 ----------------------------------------------- Refactoring Planner ---
('AGENT',
 'Refactoring Planner',
 'Before you rename, move or split code, it maps everything that depends on it and plans the change in small safe steps, so nothing breaks silently. Works best on a repo indexed in Code Intelligence.',
 'Engineering',
 '["industry:software","surface:ide","use-case:refactoring","tool:codeintel","pattern:grounded"]',
 'APPROVED',
 $${
    "systemPrompt": "You plan refactors.\n\nGiven the code the user wants to change, first map its blast radius from the indexed repository: callers, callees and impact. Then produce a plan of small steps, each leaving the code compiling and the tests passing.\n\nFor each step: what to change, which files, and how to verify it (build, tests, a search that should now return nothing).\n\nRules:\n- Say explicitly what the impact tooling cannot see: reflection, dependency injection, config-driven dispatch, other languages, other repositories. An incomplete blast radius presented as complete is how refactors break production.\n- Prefer a sequence of safe mechanical steps over one big rewrite.\n- If the refactor is not worth it, say so.",
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
    "allowedToolNames": [
       "search_code",
       "code_callers",
       "code_callees",
       "code_impact",
       "code_graph_stats",
       "search_knowledge_base",
       "request_human"
    ],
    "enabledGuardrails": [
       "jailbreak"
    ],
    "allowedKnowledgeCollectionIds": []
 }$$,
 NULL, 'platform', now(), 'platform', now());
