# Stage F Build Plan: Hierarchical Knowledge Base + Multi-Modal Ingestion

Follow-up to the Autonomy/Guardrails Strategy doc (Stages A-E) - this is the first workstream from the broader "best-in-class Agentic AI SaaS" discussion, chosen to build first. Same conventions as that doc and the Billing Build Plan: decisions stated with reasoning, stages independently shippable and verifiable, honest about what's deferred.

## 1. Where this starts (current state)

- Knowledge is a single flat table per tenant schema (`knowledge_chunks`: id, source, chunk_index, content, embedding, created_at) - no grouping, no hierarchy.
- Upload accepts `.md` only (`KnowledgeController.upload`), read as raw UTF-8 text, chunked by word count, embedded locally (sentence-transformers `all-MiniLM-L6-v2`, 384 dims, CPU, no hosted API), stored via raw JDBC (pgvector's `vector` type has no Hibernate mapping - `KnowledgeSchemaSupport` hand-resolves the tenant schema the same way Hibernate would).
- Search is one unfiltered cosine-distance scan of the whole table, `LIMIT topK`. No collection concept to filter by.
- An agent gets exactly one tool, `search_knowledge_base`, gated by a single `Agent.knowledgeBaseEnabled` boolean - on or off, no selection of *what*.
- The Python sidecar (FastAPI) currently does exactly two things: chunk text, embed text. No file-format handling at all - that's entirely the Java core's job today (and it only knows `.md`).

## 2. Decisions

**Collections, not folders-with-files.** A `KnowledgeCollection` is a named, described group of chunks, optionally nested under a parent collection (`parent_collection_id`, self-referencing, nullable). The `description` field is not cosmetic - it's what a model reads to decide *which* collection is relevant to a scenario (Stage F2), so upload/collection-creation UX should treat it as required, not optional, going forward. "Hierarchy" here means collection nesting, not a filesystem-style per-document folder tree - simpler to reason about, and matches how the existing tool-selection pattern (MCP registry, allow-lists) already works: pick from a described, flat-ish list of things, not navigate a tree.

**Collection filter lives in the raw-JDBC layer, same as tenant scoping already does.** `knowledge_chunks` gets a `collection_id` column (FK to the new `knowledge_collection` table, same tenant schema). No move to Hibernate for the vector table - that decision (documented in `VectorSchemaInitializer`'s javadoc) still holds; `KnowledgeSchemaSupport`'s pattern just grows one more parameter.

**Agent-to-collection access reuses the Stage A allow-list pattern exactly.** `Agent.allowedToolNames` (Stage A) is `Set<String>`, empty = unrestricted, opt-in to restrict. `Agent` gets `allowedKnowledgeCollectionIds` (`Set<UUID>`) the same shape, same semantics, same `@ElementCollection` mapping style. `knowledgeBaseEnabled` stays as the master on/off switch (unchanged, backward compatible) - the allow-list only matters once that's true.

**Tool shape: one list tool + one parameterized search tool, not one tool per collection.** Considered "one `search_<collection>` tool per collection" - rejected: tool-list size would grow unbounded with usage, working against the step-ceiling/context-budget guardrails Stage A just built. Instead: `list_knowledge_collections` (cheap - no embedding call, just returns id/name/description/parent for every collection this agent may access) plus `search_knowledge_base(query, collection_id?)` where omitting `collection_id` searches every allowed collection (today's behavior, unchanged default) and passing one scopes the search. This is the "progressive disclosure" principle the original PRD already named, applied to knowledge instead of MCP servers. Both tools go through the exact same `AgentToolProvider.guardAndLog` pipeline every other tool does - they inherit per-tool HITL gating, output sanitization, step-ceiling counting, and (as of the fix just shipped) correct tenant-context propagation, for free.

**Multi-modal extraction happens in the sidecar, not the Java core.** The core/sidecar boundary is already "core orchestrates, sidecar does ML/heavy content work" (embeddings live there today for exactly this reason - CPU-bound, benefits from Python's ecosystem). Format extraction follows the same boundary: PDF/docx/html/image/audio/video bytes go to the sidecar, plain text comes back, and everything downstream (chunk, embed, store) is unchanged. This also means a parser crash or hang on a malicious/malformed file happens in the sidecar process, not inside the core API that's serving every other tenant's requests.

**Large-file processing (audio/video especially) is async, not a synchronous upload call.** A 20-minute video transcription can take minutes on CPU - holding an HTTP request open for that is a bad user experience and an easy way to trip proxy/load-balancer timeouts. Upload returns immediately with a job id and `PROCESSING` status; a status-polling endpoint (or a webhook/notification, later) reports `COMPLETE`/`FAILED`. Text-native formats (docx/html/pdf-text-layer) are fast enough to stay synchronous, matching today's `.md` behavior.

**Images: OCR first, not captioning.** OCR (extract visible text via Tesseract) covers the highest-value real cases - screenshots, scanned documents, receipts, photographed whiteboards - cheaply and locally, consistent with the "no hosted API required" embeddings philosophy. Full image captioning/vision-embedding (describing *what's in* a photo with no text) is a materially heavier lift (needs a vision-capable model) and is explicitly deferred, not silently dropped - noted in Section 5.

## 3. Stages

**F1 - Collection data model + agent access control.** `KnowledgeCollection` entity + repository + CRUD controller (`/api/knowledge/collections`, tenant-schema Flyway migration, backfill existing `knowledge_chunks` rows into an auto-created "Default" collection per tenant, then tighten `collection_id` to `NOT NULL`, same backfill-then-constrain convention used for every prior migration in this project). `Agent.allowedKnowledgeCollectionIds`. `KnowledgeIngestionService`/`KnowledgeSearchService` take a `collectionId`/`collectionIds` parameter. `KnowledgeController.upload` accepts an optional `collectionId` (defaults to the tenant's Default collection).

**F2 - Scenario-based tool exposure.** `list_knowledge_collections` tool. `search_knowledge_base` gains the optional `collection_id` parameter and enforces it against the calling agent's `allowedKnowledgeCollectionIds`. Update `KnowledgeSearchToolCallback`'s tool description so the model understands the two-step "list, then search" pattern.

**F3a - Text-native format ingestion (PDF, docx, html).** Sidecar `/extract` endpoint (mimetype-dispatched), `KnowledgeController.upload` accepts these extensions, extraction runs before the existing chunk/embed call. Stays synchronous.

**F3b - Image OCR ingestion.** Sidecar OCR via Tesseract, same synchronous flow as F3a (OCR on a single image is fast).

**F3c - Audio transcription ingestion.** Local Whisper-class model in the sidecar (CPU-capable, e.g. `faster-whisper`). Introduces the async job/status-polling pattern (needed here first because this is where processing time actually gets long).

**F3d - Video ingestion.** ffmpeg audio-track extraction feeding F3c's transcription path. Frame-sampling/visual-OCR on video is explicitly out of scope for this stage (see Section 5).

## 4. Risk notes specific to this stage

File parsing libraries are a real, historically-exploited attack surface (OOXML zip bombs in docx, PDF parser CVEs) - per-format size caps, a hard extraction timeout, and running extraction in the already-separate sidecar process (not in-process in core) are load-bearing safety properties here, not nice-to-haves. Every new table (`knowledge_collection`) follows the existing tenant-schema-per-tenant Flyway convention - control-plane placement would repeat the exact cross-tenant leak class already found and fixed twice in this project's history for other tables.

## 5. What this deliberately does not cover (stated honestly, not silently dropped)

Image captioning/vision-embedding (only OCR, F3b). Frame-level visual analysis of video (only its audio track, F3d). A full filesystem-style per-document folder tree (collections nest; individual documents don't get their own sub-tree). Cross-tenant or cross-collection knowledge sharing (every collection is single-tenant, same isolation boundary as today). Re-processing/re-chunking already-ingested content when chunk-size defaults change later (out of scope until there's a concrete need).

## 6. Execution order

F1 -> F2 -> F3a -> F3b -> F3c -> F3d, each compiled and verified independently before the next starts, matching how every prior stage in this project has shipped.
