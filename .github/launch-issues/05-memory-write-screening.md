---
title: "Screen agent memory writes (`remember_fact`, graph memory) for injected directives and secrets"
labels: [security, owasp, high]
status: draft
owasp: ASI06, LLM04, LLM02
---

# Screen agent memory writes (`remember_fact`, graph memory) for injected directives and secrets

OWASP: ASI06, LLM04, LLM02. Source: [docs/security/owasp-mapping.md](../../docs/security/owasp-mapping.md). Rated High by the author's own judgement, not by a scoring system.

## Problem

`add_to_knowledge_base` now refuses injection phrases and credentials before ingestion (`KnowledgeWriteScreen`). The other agent-initiated write paths, `remember_fact` / `remember_fact_shared` (`RememberFactToolCallback`, no test found) and graph-memory writes arbitrated by `MemoryWriteArbitrationService` (an LLM, so injectable itself), have no content screen. A stored directive is replayed into later turns, possibly for other users when memory is shared (`share_memory_with_tenant`).

## Proposed fix

- Move the screen to a shared place both modules can use (it is duplicated from `PatternBasedGuardrailService` because `knowledge` cannot depend on `guardrails`; extract a tiny `core/json`-level or new `content-screen` module and delete the copy).
- Apply it in `RememberFactToolCallback` and before `MemoryWriteArbitrationService`.
- Mark shared-memory writes as higher risk: always screened, and logged with `MemoryProvenanceService`.

## Tests

Same cases as `AddToKnowledgeBaseToolCallbackTest.refusesInjectionAndCredentialContentBeforeAnyIngestion` against each path; shared-memory write refused; ordinary fact accepted. Add the missing `RememberFactToolCallback` test.

## Status

KB path done (`KnowledgeWriteScreen`).
