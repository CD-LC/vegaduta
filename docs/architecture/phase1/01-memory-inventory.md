# Phase 1, 01: Memory, knowledge and learning store inventory (core/)

Status: read-only audit, 2026-11-04. Scope is `core/` only. `sdk/java` and `sdk/python-agent` carry their own memory contracts and are out of scope
here.

Verification level (AGENTS.md rule 11): everything below was established by reading source and migrations. Nothing was compiled, run or booted for
this document. Items marked "not read" were not opened and are listed under Gaps in the audit itself.

Line references are against the working tree at the time of the audit. Paths are relative to `core/` unless they start with `docs/`. Migration paths
are under `app/src/main/resources/db/migration/tenant/`. Every store below lives in the tenant schema (schema-per-tenant: the schema is the tenancy,
there is no `tenant_id` column), except the Redis stores, which are scoped by a hashed tenant+agent+principal session id (see 1.8).

## 0. Summary

There are 17 distinct stores that hold something an agent can later "know". Only 3 of them (`agent_memory`, `tenant_memory`, `knowledge_chunks`) carry
the V676/V677 `trust` and `source_taint` columns. The other 14 have no quarantine state, and 4 of those can be written on a tainted turn or feed text
into a prompt without crossing a trust check (graph memory, scratchpad, session summary, learning proposals via the consolidation review).

Stores: agent_memory, tenant_memory, memory blocks (tenant_memory rows), AGE graph, knowledge_chunks, knowledge_parent_chunk, agent_scratchpad, Redis
session history, Redis session summary, context compaction (prompt view), channel_message_log, imported_chat, procedures, turn_feedback/rl_*, learning
tables, agent_improvement_suggestion, llm_response_cache.

Also touched but not memory stores: `turn_journal_event` (V498, hash-chained evidence, payloads never stored), `tool_call_log` (source for
`restore_tool_result`).

## 1. Store by store

### 1.1 Agent private memory (`agent_memory`)

- Code: `agents-turn/.../agents/memory/AgentMemoryService.java` (498 lines), `AgentMemorySchemaSupport.java`, `AgentMemoryEntry.java`,
  `AgentMemoryController.java`. `@Profile("knowledge")` (AgentMemoryService.java:48).
- Table: `agent_memory`, V18 (`id, agent_id FK agent ON DELETE CASCADE, subject_key, content, embedding vector(384), created_at, last_used_at,
  use_count`). Index `idx_agent_memory_agent_subject`. Trust columns added by V676 (`trust` CHECK in TRUSTED/QUARANTINED, `source_taint` TEXT JSON
  array). No Hibernate mapping; raw JdbcTemplate against a schema-qualified name (`AgentMemorySchemaSupport.qualifiedTableName`).
- Write paths:
  - Model tool `remember_fact`: `RememberFactToolCallback.java:125-126` classifies via `MemoryWriteGate.classify` (MemoryWriteGate.java, fail closed
    to QUARANTINED when the `TurnTaintView` bean is missing or throws) then `AgentMemoryService.remember(..., provenance)`
    (AgentMemoryService.java:119). `MemoryWriteScreen.refusal` (knowledge module) runs first at RememberFactToolCallback.java:120.
  - Person: `POST /api/memory/facts` -> `UserMemoryFactService.addFact` -> `rememberOwn` (AgentMemoryService.java:152) -> `remember(...)` with default
    `MemoryProvenance.TRUSTED`. Per-caller cap `app.memory.self-service.max-facts-per-agent` (25), admission rule `OwnFactAdmission` so a hand add
    never evicts another subject.
  - `MemoryImportService.importFacts` (see 1.12) and `LessonApplier.apply` both go through `rememberOwn`, therefore TRUSTED.
  - Embedding: `SidecarClient.embedQuery` (sidecar, 384-dim), one call per write.
- Read paths: `recall(agentId, subjectKey, query, embedding)` (AgentMemoryService.java:234) with `WHERE agent_id = ? AND subject_key = ? AND trust =
  'TRUSTED'` (line 251), ordered by cosine distance, top-k from setting `memory.recall.agentTopK` (default 5). Increments `use_count`/`last_used_at`
  (line 484). `listAll`, `listAllForSubject`, `listSubjects` return every row including QUARANTINED (lines 300-360) for UI and the learning review.
  Edge path: `EdgeToolExecutionController.java:361-362`.
- Prompt consumer: `turn/ChatMemoryRecall.recallMemoryFacts` (ChatMemoryRecall.java:178-187) renders `- <content>` lines under registry key
  `memory.recall.private`. Invoked pre-turn from `ChatPromptAssembler.java:263-268`; result goes into the system-side memory block
  (`ChatSystemPromptComposer`, `memoryBlock`). Any recalled fact marks the turn tainted (`ChatPromptAssembler.java:429-430`, mark name
  `recall_related_facts`).
- Trust: QUARANTINED rows are written when the turn is tainted, stored, never recalled. `approve` (AgentMemoryService.java:394/405) flips to TRUSTED
  and keeps `source_taint`. HTTP: `POST /api/agents/{agentId}/memories/{memoryId}/approve` and `/reject` (AgentMemoryController.java:92, 109).
- Retention: bounded count, not time. Per-agent cap `app.memory.limits.agent-max-memories` (50) or setting `memory.agent.maxMemories`; `pruneOverCap`
  (line 192) deletes `ORDER BY (trust = 'QUARANTINED') DESC, created_at ASC` (line 209), i.e. quarantined first, then oldest, across all subjects of
  that agent. Time-based purge only via `RetentionPurger.purgeExtractedFields` (`app/.../privacy/RetentionPurger.java:170-177`) which calls
  `purgeCreatedBefore` (AgentMemoryService.java:440) when a tenant retention rule exists.
- Scoping: tenant by schema; `agent_id` plus `subject_key` (Keycloak principal name or channel external id). Not shared between agents. Erasure:
  `forgetAllForSubject` (line 464) called from `UserMemoryController.java:196` and `ContactPrivacyService.java:240`.

### 1.2 Tenant shared memory (`tenant_memory`)

- Code: `agents-turn/.../memory/TenantMemoryService.java` (708 lines), `TenantMemorySchemaSupport.java`, `RememberSharedFactToolCallback.java`.
- Table: `tenant_memory`, V77 (`id, subject_key, content, embedding vector(384), created_by_agent_id FK SET NULL, created_at, last_used_at,
  use_count`), opt-in flag `agent.share_memory_with_tenant` in V78. Trust columns V676.
- Write: tool `remember_shared_fact` (RememberSharedFactToolCallback.java:113-114) with the same gate; person via `rememberOwn`
  (TenantMemoryService.java:485); `TenantMemoryService.remember` at line 441.
- Read: `recall(subjectKey, query, embedding, agentId)` (line 573/636) filters `subject_key = ? AND trust = 'TRUSTED'` (line 586); no agent filter, so
  it is a tenant-wide pool for that subject. Top-k setting `memory.recall.tenantTopK` (5).
- Prompt consumer: `ChatMemoryRecall.java:209-232`, key `memory.recall.shared`, only when `agentService.isShareMemoryWithTenant(agentId)`.
- Trust and retention: as 1.1. Cap `tenant-max-memories` 300 (MemoryLimitsProperties.java:28) or `memory.tenant.maxMemories`; `pruneOverCap` (line
  524) excludes block rows and prefers quarantined-first. `approveFact` line 403. `purgeCreatedBefore` line 646 does NOT exclude block rows (see Gap
  G9).
- Scoping: schema plus `subject_key`. `created_by_agent_id` is provenance only.

### 1.3 Memory blocks (persona, user_profile, project notes, active_project)

- Not a separate table. Stored as `tenant_memory` rows whose `subject_key` is `MemoryBlocks.storagePrefix(subject)` plus label (`#mem:` marker). Code:
  `memory/MemoryBlocks.java` (244 lines), `TenantMemoryService.java:159-330` (`listBlocks` 162, `putBlock` 198, `deleteBlock` 287,
  `renderPromptBlocks` 312).
- Write: person only, `PUT /api/memory/blocks/{label}` (`UserMemoryController.java:258`), and `PreferenceApplier` (agents-jobs
  `learning/PreferenceApplier.java`) appending a bullet to `user_profile` after the person approves a PREFERENCE proposal.
- Read: `renderPromptBlocksWithLabels` -> `ChatMemoryRecall.java:140-160`. Gated on `isShareMemoryWithTenant` (line 144), so persona and profile text
  reach an agent only if it opted in to tenant sharing. Caps: `blocksEnabled`, `maxCharsPerBlock`, `maxTotalChars`, `maxProjectBlocks`
  (TenantMemoryService.java:133-150).
- Trust: rows get column default TRUSTED. Blocks are person-authored only, so no quarantine path is needed today. A future agent write path into
  blocks would bypass V676 unless it sets trust.
- Block recall does NOT mark the turn tainted (ChatMemoryRecall.java:51-60 documents this: only `recalledFacts` count).

### 1.4 Graph memory (Apache AGE)

- Code: `graphmemory/` module (2002 lines): `GraphMemoryService.java` (654), `EntityExtractionService.java`, `MemoryWriteArbitrationService.java`
  (395), `GraphRecallToolCallback.java`, `RecallSharedGraphFactsToolCallback.java`, `GraphMemorySchemaSupport.java`. `@Profile("graph-memory")`
  (GraphMemoryService.java:83).
- Storage: one AGE graph per tenant schema named `<tenant_schema>_kg`, created by V44 (`create_graph`). Vertices `Entity {name, agent_id,
  subject_key}`, edges `RELATION {type, valid_from, valid_to}`. Shared tier uses sentinel agent id `__tenant_shared__` (GraphMemoryService.java:103).
  Temporal: edges are closed with `valid_to`, not deleted (`supersede` line 280, `forgetFactText` line 462).
- Write: only as a side effect of `remember_fact` / `remember_shared_fact` on a CLEAN turn (RememberFactToolCallback.java:127-133,
  RememberSharedFactToolCallback.java:115-121). LLM triple extraction via `EntityExtractionService.extractTriples` (prompt key
  `memory.entity_extraction`), screened by `MemoryWriteGate.screenTriples`, then `MemoryWriteArbitrationService.arbitrateAndWrite` (ADD/UPDATE/NOOP
  via LLM, default off, `app.graph-memory.arbitration.enabled`) or `GraphMemoryService.remember` (line 188) / `rememberShared` (line 320).
- Read: only via tools `recall_related_facts` (`GraphRecallToolCallback.java:41`) and the shared twin; `recallRelated` line 222, `recallRelatedAt`
  line 235, `recallSharedRelated` line 350. Limits `app.graph-memory.recall.max-facts` / `max-paths` (`GraphMemoryProperties`). Not auto-injected into
  the prompt. Tool output is classified UNTRUSTED_EXTERNAL (`agents/.../BuiltInToolClassifications.java:258`).
- Trust: NO trust or taint state. The only control is "a tainted write never reaches the graph" (comments at RememberFactToolCallback.java:128 and
  RememberSharedFactToolCallback.java:116). Consequence: a fact quarantined then approved by a person is never written to the graph (approve paths do
  not call the extractor), and the user edit path explicitly does not write graph triples for new text (UserMemoryController.java:52).
- Retention: none. No cap, no TTL, no purge. Grows until `eraseSubject` (line 398, `DETACH DELETE`) or tenant erasure
  (`tenancy/TenantGraphMemoryEraser.java`).
- Scoping: graph per tenant; `agent_id` plus `subject_key` on every vertex; shared tier by sentinel. User deletion/edit syncs to the graph only when
  `app.graph-memory.user-forget-sync.enabled` is on (default false).

### 1.5 Knowledge chunks (RAG)

- Code: `knowledge/` module: `KnowledgeIngestionService.java`, `KnowledgeChunkScanner.java` (retrieval SQL), `KnowledgeSearchService.java`,
  `AddToKnowledgeBaseToolCallback.java`, `KnowledgeUrlIngester.java`, `KnowledgeQuarantineService.java`, `ChunkProvenance.java`,
  `KnowledgeReembedService.java`, `rag/`.
- Tables: `knowledge_chunks` V10 (+ `collection_id` V15, FTS V87/V154, metadata V192, multi-model V26, `trust`/`source_taint` V677 with partial index
  `idx_knowledge_chunks_quarantined`); `knowledge_collection` V15; `knowledge_document` V69; `knowledge_chunk_reembed_shadow` V363. Raw JDBC for
  chunks.
- Write: (a) person uploads/URLs through the document pipeline (`ChunkProvenance.TRUSTED`, KnowledgeIngestionService.java:116, 236;
  KnowledgeUrlIngester.java:147); (b) agent tool `add_to_knowledge_base` (`AddToKnowledgeBaseToolCallback.java`, `classify()` ~line 337) QUARANTINED
  on tainted or unreadable taint. Agent write access is deny-by-default: `agent.knowledge_write_enabled` plus
  `agent_allowed_knowledge_write_collection` (V198). Insert at KnowledgeIngestionService.java:341-351.
- Read: dense query `KnowledgeChunkScanner.java:154-157` with `AND trust = 'TRUSTED'`; keyword query line 270-277 with `AND c.trust = 'TRUSTED'`. Tool
  `search_knowledge_base` (`AgentKnowledgeToolResolver`, agents-tools), scoped by `Agent.allowedKnowledgeCollectionIds` (empty means every
  collection). Workflow `knowledge` node also reads it. Not auto-injected into chat prompts.
- Review: `KnowledgeQuarantineService` list/approve/reject (lines 34-81).
- Retention: none by count or time. Deleted with document (`KnowledgeDocumentService.java:131`), collection (cascade), contact erasure by `source
  LIKE` (`ContactPrivacyService.java:179,227`). Excluded from tenant data export by design (`TenantDataExportService.java:96`).
- Scoping: tenant by schema; collection, not user or agent. There is no `subject_key`, so a chunk written by agent A is retrievable by every user of
  any agent allowed that collection.

### 1.6 Parent chunks (`knowledge_parent_chunk`)

- V432. Larger passages for parent-child retrieval. `ParentChunkStore.java:55-83` (`linksFor`) joins from a retrieved child id; no trust column and no
  trust predicate (line 68-70). Safe today only because the child must already pass the trust filter. See Gap G7.
- Written at `KnowledgeIngestionService.java:320-321` even when provenance is QUARANTINED.

### 1.7 Scratchpad (`agent_scratchpad`)

- Code: `agents-tools/.../tools/scratchpad/` (`AgentScratchpadService.java` 207 lines, `ScratchpadToolCallback.java` 353, `ScratchpadNote.java` JPA,
  `ScratchpadProperties.java`), resolver `AgentScratchpadToolResolver.java`.
- Table: V673 (`agent_id, session_id nullable, key, body, byte_size, created_at, updated_at`), unique index on `(agent_id, COALESCE(session_id,
  zero-uuid), key)`.
- Write: tools `scratchpad_write/read/list/delete` (ScratchpadToolCallback.java:40-43; write at line 170). Upsert. Caps
  `app.tools.scratchpad.max-note-bytes`, `max-notes`, `evict-oldest-on-cap`; TTL sweep on write: `session-note-ttl` 30 days, `agent-note-ttl` 0
  (never) (ScratchpadProperties.java:44-50, AgentScratchpadService.java:128-136).
- Read: only through the tools; no automatic prompt injection. Prompt only carries a directive that the tools exist
  (`DirectiveKeys.scratchpadReachable`, ChatPromptAssembler.java :447). Tool output goes through the untrusted-content screen
  (ScratchpadToolCallback.java:33-34).
- Trust: none. Write path never consults `TurnTaintView`.
- Scoping: `(agent_id, session_id)` only. NO subject column. A NULL-session ("agent-long-term") note is visible from every session of the agent, i.e.
  to every user of that agent.
- Erasure: not covered by `forgetAllForSubject` or `ContactPrivacyService` (no subject key exists to match on). Dies only with the agent or the tenant
  schema.

### 1.8 Session history (Redis)

- Code: `memory/SessionMemoryService.java` (745 lines), `SessionAppendScript.java` (Lua), `SessionOwnerRecords.java`, `SessionScopedIds.java`,
  `ChatTurn.java`, `ToolLedgerReplay.java`.
- Store: Redis list `agentic:session:<scopedId>` (SessionMemoryService.java:120), sibling keys `agentic:session-owner:` (181),
  `agentic:session-folded:` (173). No Postgres table: Redis is the only store, and a Redis outage means no history (lines 111-118).
- Write: `appendTurn` (line 545), LTRIM to `MAX_STORED_ENTRIES` 400 (line 145). Session id is scoped to tenant, agent, principal and client id by
  `SessionScopedIds` (SHA-256), so a raw id from another user does not collide (line 709-714).
- Read: `getHistory` (line 377) windows by turns, chars, tokens (`platform.chat.history-max-turns|chars|tokens` settings, `HistoryWindowSettings`),
  replays tool ledger via `ToolLedgerReplay`; `getFullHistory` (448) for transcripts.
- Prompt consumer: the chat history messages of every turn; summary prepended via `folder.withSummary` (line 437).
- Retention: TTL `app.chat.session-ttl-hours` default 24h, refreshed on each append (line 523).
- Trust: none; history contains user turns and assistant turns (plus tool ledger), and an assistant turn can restate tainted content.

### 1.9 Session rolling summary

- Code: `SessionSummaryService.java` (401), `SessionSummaryFolder.java` (278). Auxiliary model slot `SESSION_SUMMARY`
  (`auxiliary/AuxiliaryModelProperties.java:67,88`).
- Store: Redis `agentic:session-summary:<id>` (SessionMemoryService.java:205), same TTL as the list. Written in the background when turns leave the
  replay window in batches of `SUMMARY_FOLD_BATCH` 40 (line 164); folded count tracked by a marker key so each batch folds once (Lua
  `FOLD_CLAIM_SCRIPT`).
- Read: prepended to the replay window in `getHistory`. Stated gaps (class javadoc, lines 95-104): a failed fold loses the batch; concurrent folds
  last-writer-wins.
- Trust: none. Summary is LLM output over whatever the transcript contained; there is no taint carried from the source turns (not read: whether
  SessionSummaryService screens input).

### 1.10 Context compaction

- `context/ChatContextCompaction.java:43-46`: prompt view only; history, step journal and `tool_call_log` untouched; `restore_tool_result` recovers
  cleared results. A third summariser beside 1.9 and the lessons loop.

### 1.11 Conversation log (`channel_message_log`)

- V50 (`channel, external_id, channel_session_id, body VARCHAR(2000), created_at`). Written by omnichannel on inbound and outbound messages
  (`ChannelSessionService`, `ChannelMessageQueryPortImpl`). Read by tool `search_conversation_history`
  (`agents-tools/.../messaging/SearchConversationHistoryToolCallback.java`), classified UNTRUSTED_EXTERNAL (BuiltInToolClassificationsTail.java:507).
  Included in tenant export (`TenantDataExportService.java` EXPORT_TABLES) and in retention purge (TRANSCRIPT class, `RetentionPurger.java:131`).
  Scoped by channel plus external id; no agent id column.

### 1.12 Imported chats (`imported_chat`) and MemoryImportService

- `imported_chat`: V452; `agents-chatport/.../ImportedChat*.java`, `ChatImportService.java`. Read-only transcripts, header states "Nothing here is
  ever read by a model, memory or embedding path". Owner `owner_subject`. Exported in tenant export; behind `app.chat-import.enabled`.
- `MemoryImportService` (`agents-turn/.../memory/importer/MemoryImportService.java`, 331 lines): parses a pasted "what do you remember about me"
  answer (`MemoryImportParser`, 504 lines), previews with dedupe (lines 124-176), and `importFacts` (179-280) calls `UserMemoryFactService.addFact`,
  so facts land in `agent_memory` or `tenant_memory` as TRUSTED with per-caller caps (`app.memory.import.max-facts` 200, `max-chars` 20000,
  self-service caps 25 per agent and 50 shared). Provenance is an audit log entry only (lines 317-325). Its class comment (line 52) says "the memory
  tables carry no provenance column"; that is stale since V676, though V676 holds trust, not origin. Imported text is pasted by the person, so TRUSTED
  is reasonable; it is still third-party authored content with no `source` marker on the row.

### 1.13 Procedures (SOPs)

- V270: `procedures`, `procedure_rule` (instruction plus optional machine guard), `agent_procedure` join. Code:
  `guardrails/.../procedures/ProcedureService.java`, `ProcedurePromptBlock.java`.
- Write: admin UI; also created by accepting a NEW_PROCEDURE improvement suggestion (1.16).
- Read/prompt: `agentToolProvider.proceduresPromptBlock(agentId)` -> `ChatPromptAssembler.java:380`, rendered into the system prompt; guards enforced
  deterministically in `GuardedToolCallback`. No trust columns, none needed (admin-authored), but it is a memory-like destination for learned content
  (via 1.16).
- Scoping: `tenant_id` column informational; agent via `agent_procedure`.

### 1.14 Turn feedback and RL

- V108 `turn_feedback` (`agent_id, session_id, turn_ref, signal UP/DOWN/RATING/EDIT, rating, comment, edited_answer, created_by`), V109-V111
  `rl_episode`, `rl_trajectory_step`, `rl_reward_record`, V201 arm table, V474 `rl_episode.subject`. Code: `rl/` module,
  `rl/feedback/FeedbackService.java`, `rl/reward/RewardService.java`, `rl/policy/ContextualBanditService.java`.
- Write: `RlFeedbackController` (`/api/.../feedback`); gate `TurnFeedbackAccessGate` (agents-turn). Converted to a HUMAN `rl_reward_record` on the
  matching episode.
- Read/prompt: no direct injection. Reward moves bandit arms; a PROMPT_VARIANT arm replaces the agent system prompt for the turn
  (`ChatPromptAssembler.java:130, 360-379` `rlPromptOverride`). `edited_answer` and `comment` also feed the preference loop
  (`PreferenceLearningService`) and exports (`RlPreferenceExport`).
- Retention: none found (not read: RL retention). Scoping: agent plus `created_by`; `subject` only on episodes.

### 1.15 Learning loops (agents-jobs, `agents/learning/`)

- Store `learning_proposals` (V612): `subject, agent_id, kind, title, body, body_hash, payload JSONB, evidence JSONB, status PENDING/..., created_at,
  decided_at`; unique pending index `(subject, kind, body_hash)`. Generic gate: nothing in it reaches a prompt until the person approves; approval
  dispatches to a `LearningProposalApplier`. PENDING rows expire after `app.learning.proposals.ttl-days` 30 (LearningProposalService.java:95,319).
  Text is scrubbed of secrets and personal data before storage (class javadoc line 44). Inert unless `app.learning.proposals.enabled=true` (personal
  profile).
- Loops:
  - LESSON: `lessons/LessonLearningService.java` (heuristics plus one small LLM call, from tool retry and user correction) -> `LessonApplier` ->
    `AgentMemoryService.rememberOwn` (1.1).
  - PREFERENCE: `preferences/PreferenceLearningService.java` counts rows in `preference_signals` (V623: key and source only, never message text, older
    rows deleted by the loop) -> `PreferenceApplier` -> `user_profile` block (1.3).
  - ROUTINE: `routines/RoutineLearningService.java`, rows in `routine_observations` (V633, scrubbed text, retention-deleted) -> `RoutineApplier` ->
    scheduled task.
  - CONSOLIDATION: `review/MemoryReviewService.java` + `MemoryReviewDetector.java` (deterministic word-overlap: duplicates, contradictions, stale; no
    model call) + `MemoryReviewSweep` (hourly) + `learning_review_runs` (V643, claim per subject per day) -> `MemoryConsolidationApplier` (rewrites
    via `updateForSubject`, drops via `forgetForSubject`, syncs graph only if `user-forget-sync` on).
  - Desktop recipe suggestions: `recipes/` (not read in detail).
- Trust: no trust column. Collection of facts for review is unfiltered: `MemoryReviewService.collect` (lines 139-157) uses `listAllForSubject` and
  `listFactsForSubject`, which include QUARANTINED rows, and `MemoryFactRef` carries no trust. See Gap G4.

### 1.16 Improvement suggestions (`agent_improvement_suggestion`)

- V274; `app/.../common/improve/AgentImprovementService.java`. LLM-distilled fix per recurring `platform_issue` cluster: kind PROMPT_REVISION (replace
  system prompt) or NEW_PROCEDURE. Admin accepts or dismisses; `applied_ref` records where it landed. Not tied to a subject. This is the only loop
  that can change an agent's system prompt from learned content.

### 1.17 Semantic response cache (`llm_response_cache`)

- V116, `turn/cache/SemanticResponseCacheService.java`: per agent, no subject, wall-clock TTL; opt-in. Answers, not facts.

## 2. Cross-cutting facts

Trust gate (V676/V677) coverage:

| Store | Write gated by taint | Read filters QUARANTINED | Review/approve UI |
|---|---|---|---|
| agent_memory | yes (`MemoryWriteGate`) | yes, recall only (AgentMemoryService.java:251) | yes (controller 92, 109) |
| tenant_memory | yes | yes, recall only (TenantMemoryService.java:586) | yes (controller 133) |
| knowledge_chunks | yes (`AddToKnowledgeBaseToolCallback.classify`) | yes (dense and keyword) | yes (`KnowledgeQuarantineService`) |
| graph | indirect (skipped when quarantined) | n/a | n/a |
| scratchpad | no | n/a | no |
| learning proposals | n/a (approval gate instead) | not applicable | yes |
| session history/summary | no | n/a | no |

Taint propagation on read: only the auto-recalled facts (1.1 and 1.2) mark the turn tainted (ChatPromptAssembler.java:429-430). Blocks (1.3) do not,
by design. Knowledge, graph, scratchpad and conversation-log reads are tool outputs, classified UNTRUSTED_EXTERNAL and so taint at the tool boundary
(graph, conversation log confirmed; scratchpad is screened per ScratchpadToolCallback.java:33-34, classification not checked).

Erasure coverage: `DELETE /api/memory?confirm=true` (UserMemoryController.java:177-229) and `ContactPrivacyService.java:240-249` erase agent_memory,
tenant_memory and the graph only. Knowledge is erased by `source LIKE` (ContactPrivacyService.java:179,227). Scratchpad, Redis session keys, RL
feedback text, learning tables are not subject-erasable. Tenant erasure drops the schema plus `TenantGraphMemoryEraser` (Redis not read).

## 3. Gaps and duplication

- G1 (duplication). Four agent-facing "remember a fact" stores with different semantics for the same job: `agent_memory`, `tenant_memory`, graph
  (`Entity/RELATION` text derived from the same fact string) and `agent_scratchpad`. Facts are stored twice (vector plus graph) by one tool call; no
  id links the vector row to its triples, so delete/edit/approve cannot keep them in sync (UserMemoryController.java:49-52, MemoryConsolidationApplier
  syncGraph best effort).
- G2 (duplication). Three summarisers: `SessionSummaryService` (Redis, 40-turn folds), `LlmCompactionSummarizer` (prompt-view only),
  `LessonLearningService` reflective call. Three settings namespaces, three auxiliary-model slots, no shared notion of "what the summary may contain".
- G3 (duplication). Two copies of the same table definition shape and the same cap/prune/approve /recall/purge code in `AgentMemoryService` and
  `TenantMemoryService` (498 and 708 lines; TenantMemoryService also carries the blocks feature and is over the 665-line ratchet unless baselined, not
  checked). Constants `RECALL_TOP_K_KEY`, `MAX_MEMORIES_KEY` duplicated.
- G4 (trust). Learning review ignores trust. `MemoryReviewService.collect` and `MemoryConsolidationApplier.agentTier/sharedTier` include QUARANTINED
  rows. A merge can pick a QUARANTINED row as the survivor and forget a TRUSTED one; the merged text stays unrecalled (`updateForSubject` does not
  change trust, AgentMemoryService.java:365-376). Net effect is silent loss of a trusted fact, and attacker text is shown to the person inside a
  proposal.
- G5 (trust). Scratchpad has no taint gate and no subject scope. A tainted turn can write a NULL-session note that every later session of the agent,
  for any user, reads back. The read is screened but the write is not quarantined. This is the same ASI06 class V676 closes for `agent_memory`.
- G6 (trust). Graph has no trust state. Approving a quarantined fact never populates the graph (the approve paths do not run extraction), so the graph
  silently diverges from the vector store for exactly the facts a person vetted. Conversely, graph facts cannot be quarantined or audited.
- G7 (trust). `knowledge_parent_chunk` (V432) has no trust column; parents of a QUARANTINED document are written
  (KnowledgeIngestionService.java:320-321) and `ParentChunkStore.linksFor` has no trust predicate. Currently masked because only retrieved (trusted)
  children are expanded. A new retrieval path that reads parents directly would bypass V677.
- G8 (trust). Session summary and history carry no taint. A summary written from a tainted session is replayed as trusted conversational context for
  24 hours after every later turn.
- G9 (retention). Memory pruning is by count and `created_at` only, though `last_used_at` and `use_count` are maintained (AgentMemoryService.java:484)
  and never read by pruning. `purgeCreatedBefore` at TenantMemoryService.java:646 deletes block rows too (`#mem:` rows), unlike `pruneOverCap` (line
  525) which spares them: a retention rule can wipe a person's persona and profile blocks. Graph, scratchpad agent notes (TTL 0), knowledge chunks, RL
  feedback have no retention. The retention rule maps memory to `PrivacyDataClass.EXTRACTED_FIELDS` only (RetentionPurger.java:170-180).
- G10 (scoping). Knowledge chunks and the semantic response cache are agent- or collection-scoped with no subject. Acceptable for documents, risky for
  agent-written notes (a note about user A is searchable by user B on any agent granted that collection) and for cached answers that embed
  user-specific content.
- G11 (scoping). `memory.recall.*` and `memory.*.maxMemories` settings are read per agent (`SettingContext.forAgent`) but `agent_memory` cap is per
  agent across all subjects, so one busy user evicts another's facts (documented in AgentMemoryService.java:29-38). The hand-add admission rule
  protects only the manual path.
- G12 (erasure). Scratchpad, Redis session keys, RL feedback (`comment`, `edited_answer`), `learning_proposals` (`body`, `evidence`) and
  `preference_signals` have no subject-keyed erasure; `forget all` and `ContactPrivacyService` miss them (section 2 table).
- G13 (provenance). No row records where a fact came from beyond trust/taint: not the session, turn id, tool, model, or import source.
  `turn_journal_event` stores hashes only. MemoryImportService uses an audit entry instead (and its comment at line 52 is out of date).
- G14 (coupling). Memory blocks are gated by the agent's `share_memory_with_tenant` flag (ChatMemoryRecall.java:144) although they are the person's
  own profile; opting out of tenant-wide fact sharing also turns off persona and profile.
- G15 (embedding model). `agent_memory`, `tenant_memory` and `llm_response_cache` hard-code `vector(384)`; knowledge supports multiple embedding
  models and a re-embed job (V26, V363). Memory has no re-embed path (not verified by search beyond the migration files).
- Not read, so not claimed: `SessionSummaryService` input screening, RL retention, `recipes/`, `DemoConversionService` memory handling, sdk memory
  contracts, workflow `knowledge` node details, `tool_call_log` retention.

## 4. Overlap table

| Concern | Stores involved | Overlap | Evidence |
|---|---|---|---|
| Durable per-person facts | agent_memory, tenant_memory, graph | Same fact text written to 2 or 3 stores by one tool call; tier by agent vs tenant differs only by a join key | RememberFactToolCallback.java:126-133 |
| Persona/profile text | tenant_memory blocks, `agent.systemPrompt`, preference applier | Profile bullets live in a memory row and are injected beside recalled facts | ChatMemoryRecall.java:140-160 |
| Agent notes | scratchpad, agent_memory, knowledge (via `add_to_knowledge_base`) | Three agent-writable free-text stores with three scopes and three trust models | V673, V18, V677 |
| Conversation recall | Redis history, session summary, compaction, channel_message_log, imported_chat | Five representations of past conversation; only the first two are auto-injected | SessionMemoryService.java:377, SearchConversationHistoryToolCallback |
| "Learned" behaviour | learning_proposals (4 kinds), agent_improvement_suggestion, RL prompt arms, procedures | Three different ways learned content reaches a prompt, each with its own approval path | LessonApplier, PreferenceApplier, AgentImprovementService, ChatPromptAssembler.java:360-380 |
| Feedback signals | turn_feedback, preference_signals, routine_observations | Same user actions re-counted by three consumers with three tables | V108, V623, V633 |
| Caching of past answers | llm_response_cache, Redis exact cache (`LlmResponseCacheService`) | Two layers, one agent-scoped | V116 |
| Retrieval over text | knowledge_chunks, agent_memory, tenant_memory, llm_response_cache | Four pgvector tables, four recall implementations, one embedder | SidecarClient vs KnowledgeChunkScanner |
| Quarantine/approve flow | agent_memory, tenant_memory, knowledge_chunks | Same state machine implemented three times (SQL strings, controllers, UI) | AgentMemoryService.java:394, TenantMemoryService.java:403, KnowledgeQuarantineService.java:53 |
| Cap/prune/TTL | memory (count), scratchpad (count+TTL), Redis (TTL), proposals (TTL), cache (TTL) | Five retention models, none sharing configuration | section 3 G9 |

## 5. Recommended consolidation target

Direction: one governed memory record store with pluggable recall, keeping the existing tenant-schema, pgvector and trust conventions, and treating
graph and knowledge as derived or separate projections rather than peers.

1. Single `memory_item` table (tenant schema) replacing `agent_memory`, `tenant_memory` and the `#mem:` block rows. Columns: `id, kind` (FACT, BLOCK,
   NOTE, LESSON), `scope` (AGENT, TENANT, SESSION), `agent_id` (null for TENANT), `subject_key`, `session_id` (null unless SESSION), `label`,
   `content`, `embedding`, `embedding_model`, `trust`, `source_taint`, `origin` (TOOL, PERSON, IMPORT, LEARNING, GRAPH_DERIVED), `origin_ref` (turn
   id, proposal id, import id), `created_at, last_used_at, use_count, expires_at`. One service, one recall query (`WHERE trust='TRUSTED'` enforced in
   one place), one approve/reject, one prune policy that can use `last_used_at`, one purge that skips BLOCK rows, one erasure keyed on `subject_key`.
   Rationale: removes G3, G9 and G14 and gives G13 a home. Migration is additive: new table, dual-write behind a flag, backfill, then drop (never edit
   V18/V77/V676).
2. Scratchpad folds into `memory_item` as `kind=NOTE`, `scope=SESSION` or `AGENT`, with a mandatory `subject_key` for AGENT-scope notes and the same
   taint gate. Closes G5 and G12. Keep the four tools as a thin facade so prompts and tool names do not change.
3. Graph becomes a derived projection of `memory_item` rows: store `memory_item_id` on the vertex or edge, populate on approve as well as on clean
   write, invalidate when the item is deleted, rejected or edited (replace the best-effort `user-forget-sync` flag with the default). Add trust
   inheritance (only TRUSTED items project). Closes G1 and G6.
4. Knowledge stays a separate store (documents are a different lifecycle and scope) but adopts the shared quarantine contract: extract `TrustState`
   plus `source_taint` handling into one small module used by memory and knowledge, add `trust` to `knowledge_parent_chunk` (or join to the child),
   and keep V677 behaviour. Closes G7. Do not merge it into `memory_item`.
5. Conversation memory: keep Redis history and the rolling summary, but add a `tainted` flag to the summary key (set when any folded turn was tainted)
   and have `getHistory` wrap such a summary as untrusted context. One summariser interface shared by `SessionSummaryService` and
   `LlmCompactionSummarizer`. Closes G2 and G8.
6. Learning loops write only through the proposals gate into `memory_item` (`origin=LEARNING`), and the consolidation review reads only
   `trust='TRUSTED'` rows (immediate fix, independent of the schema work). Closes G4.
7. Leave procedures, RL feedback and the semantic response cache as separate stores; add subject-keyed erasure to feedback comments and proposals
   (G12), and make the response cache key include `subject_key` or skip caching when recalled memory entered the prompt (G10).

Suggested order (smallest safe steps first): (a) filter QUARANTINED in `MemoryReviewService` and `MemoryConsolidationApplier` (G4); (b) taint gate
plus subject scope on scratchpad (G5); (c) skip block rows in `TenantMemoryService.purgeCreatedBefore` (G9); (d) graph write on approve (G6); (e)
`memory_item` with dual write; (f) summary taint flag. Each step that adds or moves a Java class needs the app-module checks in AGENTS.md rule 14 and
a new Flyway number from the single shared namespace (rule 3, run `bash scripts/ci/flyway-lint.sh`).
