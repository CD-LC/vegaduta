# Multi-Agent Graph Memory (MAG) Research & Architecture
**Date:** 2026-07-31  
**Status:** Foundation Implemented, Enhancements Scoped  
**Key Technology:** Apache AGE (Postgres extension for graph queries)

---

## Executive Summary

The platform implements a **two-tier memory system** for agents:

1. **Vector Memory (AgentMemoryService)** - Semantic search via embeddings
   - Storage: PostgreSQL with pgvector
   - Query: Cosine distance for similarity search
   - Scope: Per-agent, per-subject
   - Use: "What did this user mention before?"

2. **Graph Memory (GraphMemoryService)** - Structural relationships via Apache AGE
   - Storage: PostgreSQL with Apache AGE extension (Cypher queries)
   - Query: Multi-hop traversal along relationship edges
   - Scope: Per-agent, per-subject (currently NOT cross-agent)
   - Use: "What are the relationships between these entities?"

**Vision:** Eventual **cross-agent shared graph** where all agents in a tenant can write to and learn from a shared entity/relationship graph, enabling collective intelligence.

---

## Part 1: Current Implementation (GraphMemoryService)

### 1.1 Architecture Overview

```
GraphMemoryService (Java)
  ↓
Apache AGE (Postgres Extension)
  ↓
Cypher Queries (Graph Query Language)
  ↓
PostgreSQL (with agtype column support)
```

### 1.2 Data Model

**Vertices (Entities):**
```cypher
(:Entity {
  name: "John Doe",
  agent_id: "uuid-123",
  subject_key: "user-456",
  properties: {...}
})
```

**Edges (Relationships):**
```cypher
(:Entity)-[r:RELATION {
  type: "WORKS_AT",
  confidence: 0.95,
  agent_id: "uuid-123",
  subject_key: "user-456"
}]->(:Entity)
```

**Supported Relationship Types (Examples):**
- `WORKS_AT` - Employment relationship
- `KNOWS` - Acquaintance/contact
- `OWNS` - Possession
- `PURCHASED` - Transaction
- `LOCATED_IN` - Geography
- `PARENT_OF` - Family relation
- Custom types (agent-defined)

### 1.3 Core Operations

#### Remember (Store Graph Triples)
```java
graphMemoryService.remember(agentId, subjectKey, List<GraphTriple> triples)
```

**What happens:**
1. Each triple is `(subject, relationshipType, object)`
2. Example: `("John", "WORKS_AT", "Acme Corp")`
3. Uses Cypher `MERGE` (idempotent - no duplicates)
4. **Fails open:** Graph write failure doesn't break the chat

**SQL Under the Hood:**
```sql
LOAD 'age';
SELECT * FROM ag_catalog.cypher('{schema}_agent_graph', $$
  MERGE (a:Entity {name: 'John', agent_id: '...', subject_key: '...'})
  MERGE (b:Entity {name: 'Acme Corp', agent_id: '...', subject_key: '...'})
  MERGE (a)-[r:WORKS_AT {agent_id: '...', subject_key: '...'}]->(b)
  RETURN *
$$) AS (v ag_catalog.agtype);
```

#### Recall (Traverse Relationships)
```java
List<GraphFact> facts = graphMemoryService.recallRelated(
  agentId,           // Which agent's graph
  subjectKey,        // Which subject's memories
  "John",            // Starting entity hint
  2                  // Max hops to traverse
)
```

**What happens:**
1. Finds entity matching "John" (case-insensitive substring)
2. Traverses relationships up to 2 hops away
3. Returns all discovered relationships
4. **Fails open:** Graph read failure returns empty list (not an error)

**SQL Under the Hood:**
```sql
LOAD 'age';
SELECT * FROM ag_catalog.cypher('{schema}_agent_graph', $$
  MATCH (a:Entity)-[r:RELATION*1..2]-(b:Entity)
  WHERE
    a.agent_id = '{uuid}' AND a.subject_key = '{key}'
    AND toLower(a.name) CONTAINS 'john'
    AND b.agent_id = '{uuid}' AND b.subject_key = '{key}'
  RETURN a.name, r, b.name
  LIMIT 20
$$) AS (subject ag_catalog.agtype, relations ag_catalog.agtype, object ag_catalog.agtype);
```

### 1.4 Current Scope: Agent-Level (NOT Cross-Agent)

**Current Design:**
- Every vertex/edge tagged with `(agent_id, subject_key)`
- Graph memory is **per-agent-per-subject** only
- Agent A's facts NOT visible to Agent B

**Example:**
```
Agent A (Customer Service): John → WORKS_AT → Acme Corp
Agent B (Sales Agent): John → PURCHASED → Widget (different graph scope!)
```

**Rationale:** Deliberate isolation to prevent "one agent's bad inference poisoning another's behavior"

**Current Status:** Implementation is correct per spec, but represents a deliberate design choice for v1 (not a technical limitation).

---

## Part 2: Cross-Agent Shared Graph Vision (Future)

### 2.1 Proposed Enhancement: Tenant-Level Shared Graph

**Problem:** Current design prevents shared learning across agents

**Current limitation:**
- Agent A learns "John works at Acme"
- Agent B encounters John separately
- Agent B has to rediscover "John works at Acme" (no learned context)

**Proposed solution:** Add tenant-level shared graph tier

```
Per-Agent Private Graph:
  Agent A: John → WORKS_AT → Acme (PRIVATE to Agent A)
  Agent B: John → PURCHASED → Widget (PRIVATE to Agent B)

Tenant Shared Graph (NEW):
  John → WORKS_AT → Acme (shared by all agents)
  John → PURCHASED → Widget (shared by all agents)
  John → LOCATED_IN → San Francisco (shared by all agents)
```

### 2.2 Implementation Approach (Proposed)

**Add new graph tables:**
```sql
-- Existing: {schema}_agent_memory_graph (one per agent-subject)
-- New:     {schema}_shared_tenant_graph (one per tenant)
```

**Add new tools:**
```java
// Existing - per-agent private memory
tools.add(new RememberFactTool())         // Private vector memory
tools.add(new RememberGraphTripleTool())  // Private graph memory

// New - tenant-shared memory (proposed)
tools.add(new RememberSharedFactTool())   // Shared vector memory
tools.add(new RememberSharedTripleTool()) // Shared graph memory
```

**Recall strategy (proposed):**
```java
List<GraphFact> recallRelated(agentId, subjectKey, entity, hops) {
  // 1. Get agent's private facts
  List<GraphFact> privateFacts = queryAgentGraph(agentId, subjectKey, entity, hops);
  
  // 2. Get tenant-shared facts (NEW)
  List<GraphFact> sharedFacts = queryTenantGraph(tenantId, entity, hops);
  
  // 3. Merge and deduplicate
  List<GraphFact> merged = merge(privateFacts, sharedFacts);
  
  return merged;
}
```

**Safety guarantees (proposed):**
- Shared facts are ADDITIVE to private facts (not replacing)
- Agent that never calls `RememberSharedTripleTool` sees no change
- Shared graph is APPEND-ONLY (no deletion via agent) to prevent one agent poisoning shared context
- Admin-only operations for deletion/correction

### 2.3 Use Cases Unlocked

**Case 1: Customer 360 View**
```
Agent A (Order Processing) learns: Customer → PURCHASED → Product X
  ↓ Shared to tenant graph
Agent B (Support) learns: Customer → HAS_ISSUE_WITH → Product X
  ↓ Shared to tenant graph
Agent C (Upsell) learns: Customer → INTERESTED_IN → Product Y
  ↓ All three agents now see the full picture:
    Customer → PURCHASED → Product X → HAS_ISSUE_WITH → Product X
           → INTERESTED_IN → Product Y
```

**Case 2: Entity Resolution**
```
Agent A: "John Smith" → WORKS_AT → Acme
Agent B: "John S" → PURCHASED → Widget
  ↓ Shared graph can deduplicate and link:
    "John Smith" (canonical) → WORKS_AT → Acme
                            → PURCHASED → Widget
```

**Case 3: Collective Risk Assessment**
```
Agent A: Vendor → QUALITY_SCORE → 4.5/5
Agent B: Vendor → PAYMENT_DELAY → 30 days
Agent C: Vendor → CUSTOMER_COMPLAINTS → 12
  ↓ Risk assessment agent sees all factors:
    Vendor (shared context) = RISKY (composite from all agents' learnings)
```

---

## Part 3: Technical Deep-Dive

### 3.1 Apache AGE Architecture

**Why Apache AGE (not a separate graph DB)?**

Pros:
- ✅ Single database (Postgres) - no separate infrastructure
- ✅ ACID transactions
- ✅ Row-level security compatible
- ✅ Backup/restore as part of Postgres snapshots
- ✅ Same connection pool as rest of app

Cons:
- ❌ Graph operations slower than standalone graph DB
- ❌ Limited query optimization (no graph-specific planner)
- ❌ Cypher parser/executor less mature than Neo4j

**Comparison Table:**

| Aspect | Apache AGE | Neo4j | Apache Gremlin |
|--------|-----------|-------|-----------------|
| Architecture | Postgres extension | Standalone | Generic traversal |
| ACID | Yes | Partial | Varies |
| Queries | Cypher | Cypher | Gremlin |
| Infrastructure | Single DB | Separate | Varies |
| Scaling | Via Postgres sharding | Native clustering | Custom |

### 3.2 Cypher Query Language Basics

**CREATE vertices:**
```cypher
CREATE (n:Person {name: 'Alice', age: 30})
RETURN n
```

**CREATE relationships:**
```cypher
MATCH (a:Person {name: 'Alice'}), (b:Company {name: 'Acme'})
CREATE (a)-[r:WORKS_AT {since: 2020}]->(b)
RETURN r
```

**MATCH patterns:**
```cypher
// Simple 1-hop
MATCH (a:Person)-[r:KNOWS]-(b:Person)
WHERE a.name = 'Alice'
RETURN b

// Multi-hop traversal
MATCH (a:Person)-[r:KNOWS*1..3]-(b:Person)
WHERE a.name = 'Alice'
RETURN b, length(r) as hop_count

// Relationship filter
MATCH (a:Person)-[r:KNOWS|WORKS_WITH]-(b:Person)
WHERE a.name = 'Alice'
RETURN r.type, b.name
```

**MERGE (idempotent):**
```cypher
// MERGE = MATCH + CREATE if not found
MERGE (n:Person {name: 'Bob'})
ON CREATE SET n.created = timestamp()
ON MATCH SET n.last_seen = timestamp()
RETURN n
```

### 3.3 Isolation & Scoping

**Schema Isolation (via GraphMemorySchemaSupport):**
```java
String graphName = GraphMemorySchemaSupport.currentGraphName();
// Returns: {schema}_{purpose}_graph
// Example: tenant_acme_agent_memory_graph
```

**Each tenant gets its own graph:**
```
Postgres Schema: public
  Graph: tenant_acme_agent_memory_graph
  
Postgres Schema: public
  Graph: tenant_bigcorp_agent_memory_graph
```

**Per-Agent Scoping (via properties):**
```cypher
WHERE agent_id = '{uuid}' AND subject_key = '{key}'
```

**Current: NO Cross-Tenant Visibility** ✅ (Secure by default)

### 3.4 Performance Characteristics

**Benchmark (estimated, not measured):**

| Operation | Cost | Latency | Scalability |
|-----------|------|---------|-------------|
| Write 1 triple (MERGE) | 1 SQL + Cypher | ~50-100ms | Per-connection |
| Recall 1-hop | 1 Cypher query | ~30-50ms | O(edges per entity) |
| Recall 3-hop | 1 Cypher query | ~100-300ms | O(edges^3) explosion |
| Store 1M facts | 1M Cypher calls | Hours (batched) | Depends on batch size |

**Optimization opportunities (not yet implemented):**
- Batch writes (multi-MERGE in single Cypher)
- Indexes on (agent_id, subject_key, name)
- Query result caching
- Async recall (don't block chat on graph traversal)

---

## Part 4: Integration with Vector Memory

### 4.1 Two-Tier Recall Strategy

**Tier 1: Vector Similarity (AgentMemoryService)**
- User: "Tell me about John"
- Query: Embed "John", find closest facts by cosine distance
- Returns: All mentions of John (regardless of relationship type)
- Latency: ~100ms
- Use: "What do we know about this entity?"

**Tier 2: Graph Traversal (GraphMemoryService)**
- User: "What does John do?"
- Query: Cypher traversal starting from "John"
- Returns: Entity → WORKS_AT → Company, Entity → LOCATED_IN → City
- Latency: ~100-300ms
- Use: "What are the relationships around this entity?"

**Combined approach (current, in AgentChatService):**
```java
// During agent context building:
List<VectorFact> vectorFacts = agentMemoryService.recall(
  agentId, subjectKey, userMessage, topK=5
);
List<GraphFact> graphFacts = graphMemoryService.recallRelated(
  agentId, subjectKey, entityFromUserMessage, hops=2
);
// Merge into context for agent
```

### 4.2 Complementary Strengths

| Question | Best Answered By | Why |
|----------|------------------|-----|
| "What facts about John?" | Vector (similarity) | Returns all semantic matches |
| "What entities is John connected to?" | Graph (traversal) | Follows relationships |
| "How is John connected to Acme?" | Graph (traversal) | Finds paths: John → WORKS_AT → Acme |
| "Similar customers to John?" | Vector (similarity) | Embeddings find semantic neighbors |
| "Network of John's connections?" | Graph (multi-hop) | Traverses multiple relationship types |

---

## Part 5: Proposed Roadmap

### Phase 1 (Current - v1): Per-Agent Graphs
- ✅ Implement GraphMemoryService
- ✅ Schema migration (V44__graph_memory.sql)
- ✅ Integration with AgentChatService
- ✅ RememberGraphTripleTool for agent-initiated storage
- ✅ Graph recall during context building

### Phase 2 (Proposed - v1.1): Tenant-Shared Graphs
- [ ] New table: `{schema}_shared_tenant_graph`
- [ ] RememberSharedTripleTool for shared writes
- [ ] Extended recall to include shared facts
- [ ] Admin API for managing shared graph (audit, corrections)
- [ ] Safety guardrails (append-only for agents, delete for admins)

### Phase 3 (Proposed - v1.2): Smart Cross-Agent Learning
- [ ] GraphML export (for visualization)
- [ ] Relationship type suggestions (agent → LLM → type)
- [ ] Entity deduplication (John Smith = John S)
- [ ] Relationship confidence scoring
- [ ] Temporal evolution tracking (who learned what when)

### Phase 4 (Long-term): Advanced Graph Features
- [ ] Graph-based knowledge base (not just vector search)
- [ ] Pattern matching (rule engine on graph)
- [ ] Multi-hop authorization (entity-level access control)
- [ ] Query optimization (graph planner)
- [ ] Distributed graphs (across tenants for federation)

---

## Part 6: Current Gaps & Future Work

### 6.1 Known Limitations

**Performance:**
- ❌ No query result caching (every recall = fresh Cypher query)
- ❌ No multi-hop index (O(edges^hops) explosion)
- ❌ No async recall (blocks chat turn)
- ⚠️ Regex-based agtype parsing (brittle)

**Functionality:**
- ❌ No cross-agent sharing yet (by design for v1)
- ❌ No relationship type suggestions (agent decides)
- ❌ No entity deduplication (manual or LLM-driven)
- ❌ No temporal tracking (when was this learned?)

**Observability:**
- ❌ No graph telemetry (query counts, sizes)
- ⚠️ Failures logged but not surfaced to user
- ⚠️ No visibility into why recall returned empty

### 6.2 Future Enhancement Proposals

**Proposal 1: Cross-Agent Shared Graph** (HIGH PRIORITY)
- Solves: "How do agents learn from each other?"
- Effort: ~3-5 days (new table, 2 new tools, recall merge logic)
- Value: Unlocks collective intelligence

**Proposal 2: Graph Caching Layer**
- Solves: "Recall latency" (multi-hop expensive)
- Effort: ~2 days (Redis cache of traversals)
- Value: ~3-5x faster recall

**Proposal 3: Entity Deduplication**
- Solves: "John Smith" vs "John S" vs "J. Smith" confusion
- Effort: ~5-7 days (LLM-driven or UI-driven matching)
- Value: Cleaner graphs, fewer traversals

**Proposal 4: Temporal Evolution**
- Solves: "When did we learn this?" "How has this relationship changed?"
- Effort: ~3 days (timestamp fields, query filtering)
- Value: Audit trail, bias detection

---

## Part 7: Configuration & Enablement

### 7.1 Spring Profile

**Current:**
```yaml
spring.profiles.active=security,knowledge,workflow,graph-memory,mcpserver
```

**What `-graph-memory` enables:**
- ✅ GraphMemoryService bean instantiation
- ✅ Graph recall during AgentChatService context building
- ✅ GraphTriple input type for tools
- ✅ RememberGraphTripleTool availability

**Requires:**
- ✅ PostgreSQL with Apache AGE extension installed
- ✅ Database migration V44__graph_memory.sql applied
- ✅ Core running with `graph-memory` profile active

### 7.2 Dependency Chain

```
Core starts with graph-memory profile
  ↓
GraphMemoryService bean created
  ↓
AgentChatService injects GraphMemoryService
  ↓
During chat context build: recall both vector + graph
  ↓
RememberFactToolCallback gets graph triple extraction
```

### 7.3 How to Verify It's Working

```bash
# 1. Check profile is active
curl http://localhost:8080/actuator/env | grep graph-memory

# 2. Verify table exists
docker exec agentic-postgres psql -U agentic -d agentic_ai \
  -c "SELECT tablename FROM pg_tables WHERE tablename LIKE '%graph%';"

# 3. Check AGE extension installed
docker exec agentic-postgres psql -U agentic -d agentic_ai \
  -c "SELECT * FROM pg_extension WHERE extname = 'age';"

# 4. Query sample graph (if using graph-memory)
docker exec agentic-postgres psql -U agentic -d agentic_ai \
  -c "SELECT graphname FROM ag_graph;"
```

---

## Conclusion

**Current State:**
- ✅ GraphMemoryService implemented and operational
- ✅ Per-agent graph memory working
- ✅ Integrated with agent context building
- ⚠️ NOT cross-agent sharing (by design)

**Immediate Next Step:**
Implement **Tenant-Shared Graph** tier (Proposal 1) to unlock collective intelligence between agents.

**Vision:**
Multi-agent systems that learn from each other, remember shared context, and make better decisions through collective knowledge - not just individual agent knowledge.

---

**Research Date:** 2026-07-31  
**Technology:** Apache AGE + PostgreSQL  
**Status:** Foundation ready, enhancements scoped  
**Owner:** Core-3 agent scope
