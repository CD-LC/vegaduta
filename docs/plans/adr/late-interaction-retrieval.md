# ADR: Late-interaction retrieval and modern embeddings (L8)

Status: PROPOSED (evaluation only; no production switch). Date: 2026-10-01.
Scope: sidecar embedding path and core/knowledge storage. Audience: developers, researchers, freelancers.

## Context (verified in code)

- `sidecar/app/embeddings.py` serves five local sentence-transformers models (`MODEL_REGISTRY`): four at 384 native dims, one at 768. Every vector returned for a collection is zero-padded to `STORAGE_DIMENSIONS = 768`; padding past 768 raises. Zero-padding preserves cosine exactly, so one `vector(768)` column serves all models.
- `core/knowledge` mirrors the registry in `EmbeddingModelCatalog` (must change together), keeps a per-model partial HNSW index (`VectorSchemaInitializer`, model keys limited to `[a-z0-9-]`), and changes a collection's model only via `KnowledgeReembedService` (shadow storage, atomic swap).
- Retrieval is hybrid (vector + keyword, rank fusion) followed by an optional cross-encoder rerank on a small shortlist (`reranking.py`).
- `agent_memory` is still `vector(384)` and takes the unpadded path (`model_key=None`).
- No late-interaction code exists in the repo (grep for colbert/maxsim/multi-vector hit only unrelated files).

## Problem

A single pooled vector per chunk loses token-level detail: exact terms, rare identifiers, multi-aspect queries and code/Indic text suffer, and the 768 ceiling blocks the strongest modern models (1024-dim bge-m3 class, 1024-4096 for newer ones). Late interaction (ColBERT-style: one vector per token, MaxSim scoring) usually improves recall on such queries and generalises better out of domain, at a large storage and compute cost.

## Options

1. **Status quo** plus rerank. Zero cost; ceiling is the pooled-vector quality.
2. **Wider single-vector model** (e.g. 1024-dim multilingual such as bge-m3). Needs the column widened or a second column; migration cost moderate; storage +33%; no new query algorithm. Best Indic coverage win for least machinery.
3. **Matryoshka / truncatable embeddings** (store a prefix, e.g. 768 of a 1024+ model). Fits the existing column with no migration and a small recall loss; only for models trained for truncation. Cheapest way to use a newer model.
4. **Late interaction as a re-scorer over the hybrid shortlist.** Store token vectors off the hot path (separate table or blob), compute MaxSim only for top-N (~50) candidates, like the cross-encoder today. Adds storage but no ANN index over token vectors.
5. **Late interaction as the first stage** (token-level ANN, PLAID-style centroids/pruning). Best recall, highest complexity; pgvector has no native multi-vector index, so this implies a new engine or heavy custom SQL. Rejected for now.
6. **Learned sparse / hybrid-sparse** (SPLADE, bge-m3 sparse head). Reuses the keyword side and inverted indexes; worth benchmarking alongside, out of scope here.

## Trade-offs

| Axis | Single vector (now) | Late-interaction rescorer (4) |
|---|---|---|
| Stored floats per chunk | 768 (padded) | tokens x dim: a 200-token chunk at 128-dim is about 25.6k, roughly 33x; at 384-dim about 100x |
| Query path | one ANN lookup | ANN shortlist, then MaxSim on N candidates (N x doc_tokens x query_tokens x dim FLOPs) |
| Index time | one encode | one encode, store every token vector (quantise to int8/binary to cut 4-32x) |
| Fits `vector(768)` | yes | no: separate storage |
| Local CPU friendly | yes | encode similar to a small BERT; MaxSim cheap for N <= 50 |

Dimension rule: a model with native dims > 768 cannot enter today's path (`embed_texts` raises by design). Late-interaction heads are typically 96-128 dims per token, so they fit any width but break the "one vector per chunk" invariant instead.

## Decision (this pass)

Do not switch anything. Ship `sidecar/bench/retrieval_bench.py` plus a 20-doc / 15-query eval set (`sidecar/bench/eval_set.json`) so the choice is made on evidence. The harness reports recall@k, MRR, p50/p95 query latency, index time and floats per chunk for each `dense:<registry key>` and `late:<repo>` system, with the dense path reproducing the production padding. It adds no dependency and is not imported by the service. The default late system is a bi-encoder's token vectors (a control); the real test needs a ColBERT-family checkpoint packaged for sentence-transformers or PyLate, run by a human with network access.

The eval set is smoke-scale: 15 queries cannot separate systems with confidence. Before any decision, extend it with (a) a real tenant-style corpus of at least 200 chunks and 100 queries, (b) Indic and code queries, (c) exact-identifier queries.

## Staged plan (each stage gated on the previous benchmark)

0. Run the harness on a real corpus; record results here. Success bar to proceed: late interaction (or a wider model) improves recall@5 by at least 5 points on the hard-query slice and p95 added latency stays under 150 ms for N = 50 on the 2-vCPU sidecar.
1. If a wider single-vector or Matryoshka model wins: add it to both registries together (sidecar `MODEL_REGISTRY`, core `EmbeddingModelCatalog`); if native dims > 768 and not truncatable, widen the column in a new tenant migration (shared Flyway namespace with controlplane; pgvector HNSW supports up to 2000 dims for `vector`, beyond that use `halfvec`), rebuild the per-model partial HNSW indexes, and re-embed each collection through `KnowledgeReembedService` (shadow storage, atomic swap). Opt-in per collection; existing collections untouched.
2. If late interaction wins: new `/embed-tokens` and `/maxsim-rerank` sidecar endpoints behind a config flag default OFF; a `knowledge_chunk_tokens` table (chunk_id, model, int8-quantised token matrix as `bytea`) written only for collections that opt in; used strictly as a rescoring pass over the hybrid shortlist, before or instead of the cross-encoder. Backfill via the same re-embed job pattern.
3. Only if (2) shows first-stage recall limits, revisit option 5 as a separate ADR.

## Migration and dimension implications

- Changing a collection's model is always a re-embed (vectors from different models are incomparable); the existing shadow-storage job is the right vehicle and needs no redesign for options 2 and 3.
- Widening `vector(768)` rewrites the table and invalidates HNSW indexes: schedule per tenant, follow `ZERO-DOWNTIME-DEPLOY-RULES.md`, and keep sidecar and core registries deployed together (a model unknown to either side fails loudly by design).
- Per-model index naming is already interpolated from model keys: new keys must match `[a-z0-9-]`.
- `agent_memory` (384) and the LLM response cache (`vector(384)`) are independent columns and stay on the unpadded default model; do not change them as part of this work.
- Late-interaction storage is additive (new table); rollback is dropping the flag, no data loss in existing tables.
- Storage budget: quantise token vectors (int8) and cap tokens per chunk; estimate per-collection cost with the harness's `floats_per_doc` before enabling.

## Risks

- Token vectors from a model not trained for late interaction perform poorly; judge only ColBERT-family checkpoints. Query/document marker tokens and the projection head must match the checkpoint's recipe.
- Multilingual (Indic) late-interaction checkpoints are scarcer than English ones; recall gains may not transfer.
- Licence and model size per checkpoint need review before any is added to the registry.
- Latency on the shared 2-vCPU sidecar is subject to the existing `EMBED_MAX_CONCURRENT` backpressure; MaxSim work must go through the same slot budget.
- Benchmark result noise on a tiny eval set; treat numbers as smoke, not evidence.

## Config keys

None added: this pass changes no runtime behaviour. Future stages introduce flags default OFF, documented in `application.yml`.
