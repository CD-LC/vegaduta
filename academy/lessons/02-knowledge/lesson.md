# Ep 2 — Knowledge: feeding the newsroom

**Length** ~4 min · **Level** everyone · **Renders to** `ep02-knowledge.mp4`

The Ledger's Editor-in-Chief exists as a draft, but it can't quote the
paper's style guide until that guide lives somewhere it can search.
`/knowledge` is that somewhere: upload documents, group them into
collections, and agents retrieve them by meaning — not keyword.

## The scenes

| Step | Click-path | What happens |
|---|---|---|
| Collections | `/knowledge` → **New collection** → name + description → **Create** | Makes a shelf (`Ledger Style Guide`) with its own embedding model |
| Upload | per-collection card → **Choose a file to upload** | Picker lists accepted formats; Docling-enabled deployments add `.pptx`, `.xlsx`, `.csv`, `.tiff`, `.bmp` |
| Pipeline | collection card → **INGESTION PIPELINE · LIVE** | Upload → Extract → Embed → Index; badge flips to `✓ ready` |
| Test retrieval | collection card → **Semantic search** box → **Go** | Runs a real query, shows matched chunks and latency |
| Tuning | collection card → **Retrieval quality** | Chunking + embedding model determine what search returns; measure changes here |
| Attach | header → **Agent workbench** | Collections are wired to agents (or workflows) in their own pickers |

## Mental model

- **Collections are shelves.** Each has its own files, chunk settings and
  embedding model — keep unrelated material on separate shelves.
- **Retrieval is semantic.** Agents get back *chunks*, the passages most
  similar in meaning to the question — test with reader-phrased queries.
- **Nothing reaches an agent by accident.** A collection must be attached
  in the agent workbench (or a workflow) before it shows up in context.
- **The pipeline is observable.** Every stage — extract, embed, index —
  reports live status, so a stuck document is visible, not silent.

## After watching

Continue to [Ep 3 — Tools, MCP & SaaS connectors](../03-tools-mcp/lesson.md),
where we give the newsroom web search and a publishing connector.
