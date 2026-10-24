---
title: Tool description: search_knowledge_base
description: The description of the search_knowledge_base tool, as sent to the model in the tool list.
risk: TOOL_DESCRIPTION
securityCritical: false
disableable: false
retired: false
strictPlaceholders: false
maxLength: 4000
---
Searches this agent's uploaded knowledge base for content relevant to a query and returns results with citations (source document name and document id). Use this whenever the user's question might be answered by something they've uploaded, rather than relying on general knowledge alone. If you're unsure which knowledge collection is relevant, call list_knowledge_collections first and pass its id as collection_id to scope the search; omit collection_id to search every collection you have access to. When you answer using these results, cite the source document by name so the user knows which upload the answer came from - do not present retrieved content as general knowledge. If results look garbled or you suspect they came from OCR'd images (misread SKUs, prices, or numbers) rather than clean source text, retry with exclude_image_sourced=true.
