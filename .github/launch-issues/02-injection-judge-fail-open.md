---
title: "Prompt-injection screening on tool output fails open and is regex-only"
labels: [security, owasp, high]
status: draft
owasp: ASI01, LLM01, MCP06
---

# Prompt-injection screening on tool output fails open and is regex-only

OWASP: ASI01, LLM01, MCP06. Source: [docs/security/owasp-mapping.md](../../docs/security/owasp-mapping.md). Rated High by the author's own judgement, not by a scoring system.

## Problem

`SemanticGuardrailService` (the LLM injection judge, default implementation) catches every exception and passes the output through ("Fail open, deliberately"), and it also passes when no verifier client is available. The always-on layer is `PatternBasedGuardrailService`'s five hard-block regexes. A paraphrased instruction, a non-English one, or a judge outage lets injected tool output reach the model. There is no structural separation of tool output from instructions (e.g. a consistent untrusted-data envelope for every tool, not only A2A replies).

## Proposed fix

- Make the failure mode configurable: `app.guardrails.semantic.on-error=allow|block-untrusted` (default `allow` to keep today's behaviour; recommend `block-untrusted` for tools classified `UNTRUSTED_EXTERNAL`).
- Wrap every `UNTRUSTED_EXTERNAL` tool result in the same delimited "third-party data, not instructions" envelope the A2A tool uses (`A2aRemoteAgentToolCallback`), applied in `GuardedToolCallback` after sanitisation.
- Emit a metric when the judge is skipped (error or no client) so an outage is visible.
- Extend the regex list from a maintained corpus and add multilingual cases.

## Tests

Judge throws -> behaviour per setting; no client -> same; envelope present for an MCP tool and absent for a trusted built-in; metric increments on skip. Add a corpus test of known injection strings with an expected catch rate so regressions show.

## Status

None yet; behaviour today is documented in `SemanticGuardrailService` javadoc.
