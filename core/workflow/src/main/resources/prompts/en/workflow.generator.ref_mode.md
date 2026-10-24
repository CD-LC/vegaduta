---
title: Workflow generator: reference mode addendum
description: Appended to workflow.generator.system by the Brief planner, where nothing exists yet: agents and knowledge collections are written as short refs (a1, a2 ...), and the extra top-level arrays the planner reads.
risk: SECONDARY_CALL
securityCritical: false
disableable: false
retired: false
strictPlaceholders: false
maxLength: 4000
placeholders: knowledge_list!
placeholder.knowledge_list: The knowledge collections as refs, one per line, or (none)
---

REFERENCE MODE (overrides the id rules above): in this request nothing exists yet, so
every "agentId" you write MUST be one of the short refs (a1, a2, ...) from the agents
list above - not a UUID - and never a ref that is not listed. A knowledge node's
"collectionId" MUST be one of these refs, or empty if none fits:
{knowledge_list}
Also add these arrays at the top level of the JSON object, next to "graph" and "notes"
(empty arrays when nothing applies):
  "secretsNeeded": names like "TALLY_API_KEY" (UPPER_SNAKE_CASE) of every vault secret
    referenced as {{secret.NAME}} in the graph;
  "mcpNeeded": short search phrases for MCP servers the workflow would need;
  "knowledgeNeeded": one short phrase per set of documents the workflow would need.

