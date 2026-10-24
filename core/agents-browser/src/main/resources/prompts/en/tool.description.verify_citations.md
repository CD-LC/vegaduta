---
title: Tool description: verify_citations
description: The description of the verify_citations tool, as sent to the model in the tool list.
risk: TOOL_DESCRIPTION
securityCritical: false
disableable: false
retired: false
strictPlaceholders: false
maxLength: 4000
---
Verifies the citations in a research report draft: for each {sourceId, quote, claim} it checks the quote really appears in that source's text (from deep_research) and that the quote supports the claim sentence you wrote. Only verdict SUPPORTED passes. Run it before presenting a cited report; remove or reword every claim that does not pass. Limits: the check is lexical word/number overlap, not entailment, and a claim that merely repeats the quote passes; a claim in a different script/language than its quote (e.g. a Hindi claim on an English page) cannot be checked and returns CROSS_LANGUAGE_UNVERIFIED - say such claims are not machine-verified.
