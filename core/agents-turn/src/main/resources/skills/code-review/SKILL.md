---
name: code-review
description: Review code changes like a senior engineer - pull requests, diffs, snippets, whole files. Finds bugs, security holes, race conditions, missing tests and unclear naming, ranked by severity with concrete fixes. Use when the user shares code or a diff and asks for a review, feedback, an audit, or asks what is wrong with it.
---

# Code review

Review what was changed, not the whole world. Your job is to stop defects reaching production, not to restyle the code.

## Order of work

1. **Understand intent first.** State in one sentence what the change is trying to do. If you cannot, ask; a review of misunderstood code is noise.
2. **Correctness.** Walk the unhappy paths: null or empty input, zero and negative numbers, duplicates, concurrent callers, partial failure halfway through, retries, timeouts, time zones, large inputs.
3. **Security.** Injection (SQL, shell, HTML, prompt), authorization checked on the server for every object, secrets in code or logs, unsafe deserialization, path traversal, SSRF, missing rate limits.
4. **Data and state.** Migrations reversible and safe under load, transactions around multi-step writes, idempotency of handlers that can be retried, cache invalidation.
5. **Tests.** Does a test fail without this change? Are the edge cases from step 2 covered? Flag tests that assert nothing.
6. **Readability.** Only what will cost the next reader real time: misleading names, dead code, a function doing three jobs.

## Output format

Start with a one-line verdict: **Approve**, **Approve with nits**, or **Request changes**.

Then findings, most severe first. Each finding:

- **Severity**: Blocker, Major, Minor, or Nit
- **Where**: file and line, or the quoted snippet
- **What breaks**: a concrete input or sequence that produces the wrong result
- **Fix**: the corrected code, not a description of it

End with what is good about the change in one or two lines, honestly, or leave it out.

## Rules

- Never invent a line number or an API. If you have not seen the code that is called, say the finding depends on it.
- A finding without a failure scenario is an opinion; label it as a Nit.
- Do not demand a rewrite when a three-line fix works.
- Match the existing style of the project rather than your preference.
