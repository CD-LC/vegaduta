---
title: Tool description: deep_research
description: The description of the deep_research tool, as sent to the model in the tool list.
risk: TOOL_DESCRIPTION
securityCritical: false
disableable: false
retired: false
strictPlaceholders: false
maxLength: 4000
---
Researches a question on the public web in depth: runs several searches in parallel, removes duplicate sources, reads the best pages, and returns numbered sources (S1, S2...) with quoted passages and character offsets you can cite. Use it for questions that need multiple sources - comparisons, literature or market scans, 'what is the current state of X', fact-checking. Do NOT use it for a single quick fact (use web_search) or a page the user gave you (use fetch_web_page). You are the planner: write the brief yourself - effort 'simple' (one angle), 'comparison' (one sub-question per compared item, 2-4), 'complex' (5+ independent angles). Then write the report from the returned passages only and run verify_citations on the quotes you use.
