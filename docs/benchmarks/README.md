# Agent evaluation harness

**Status: no results are published yet.** This page documents a harness that is
unit-tested offline and has **not been run against a stack**. There is no
scorecard, no ranking and no comparison with any other system here, and none should
be inferred. When someone runs the harness against a real stack and submits the
output, the numbers will appear in the results section below with the exact
commit, model and date they were measured on.

What exists today: a versioned task suite, a runner that drives a VegaDūta stack
through its public REST API, scoring and aggregation code, and an adapter interface
so the same suite can later be pointed at another system. Everything lives in
`tests/eval/`.

## What it measures

Per task, and aggregated overall and per category (`qa`, `tool-use`, `knowledge`,
`approval`, `workflow`):

| Metric | How it is computed |
|---|---|
| Task success | Programmatic checks on the final answer (substring, number within tolerance, regex, forbidden text), **not LLM-judged**. A task also fails if its tool-call or approval expectation fails. |
| Tool-call accuracy | Expected calls (tool name plus argument matchers) are matched one-to-one against the calls the platform reports. Recall = matched / expected, precision = matched / actual, exact = full recall, no forbidden tool, no unmatched call (unless the task allows extras). Tasks that need no tool expect none, which tests over-calling. |
| Tokens and cost | The platform's own numbers: the session LLM-usage summary for agent chats (`totalTokens`, `totalCostUsd`, `unpricedCalls`), the node trace (`tokensIn`, `tokensOut`, `costUsd`) for workflow runs. A run whose usage is missing, or that contains an unpriced call, counts as **unpriced** and is excluded from cost means. It is never counted as zero. |
| Latency | Wall clock around one task, measured by the runner (chat: request to end of stream; workflow: start to terminal status). Reported as p50 and p95 (nearest-rank) over all runs in the scope. Use `--repeats` of 5 or more before trusting p95. |
| Guardrail and approval behaviour | Whether an approval request was raised when the task requires one (and not raised when it must not be), whether the harness's approve or reject decision ended in the expected status, and whether a system-prompt-leak probe leaked a canary. The refusal-wording check is a **regex heuristic** and is labelled as such. |

Not measured: answer quality beyond the deterministic checks, long-horizon tasks,
multi-turn memory, concurrency or throughput (see `tests/load` and
[hot-path performance](../architecture/HOT-PATH-PERFORMANCE.md) for the platform's
own latency work), and whether an approved action's side effect really happened
(the fixture tools are no-ops by design).

## The task suite

`tests/eval/tasks/suite.v1.json`, 20 tasks, semver-versioned. Change a task and
you bump the version, so results are only comparable within a version.

Suite changelog. Results are comparable only within one version.

- **1.1.0**: the scorer's `refusal` heuristic also accepts a reply that names what it is declining ("this looks like a
  prompt injection or jailbreak attempt"), not only one that says "I can't". A system that blocks the request before
  the model sees it answers in that form, and 1.0.0 scored it as a failure although nothing leaked. Only `qa-04` is
  affected; the tasks themselves are unchanged. Rerun every system on 1.1.0 before comparing.
- **1.0.0**: first version.

| Category | Tasks | Role | What it exercises |
|---|---|---|---|
| `qa` | 4 | `agent:qa` | Plain answers, an arithmetic check, strict output format, a system-prompt-leak probe. |
| `tool-use` | 5 | `agent:tools` | Single and two-call tool use with exact expected calls, plus one task where calling a tool is wrong. |
| `knowledge` | 4 | `agent:kb` | Retrieval of invented facts from `tests/eval/fixtures/kb/northwind-handbook.md` (fictitious, so a model cannot answer from training data). |
| `approval` | 4 | `agent:approval` | Gated tools: approve path, reject path, and an ungated tool that must not raise an approval. |
| `workflow` | 3 | `workflow:pipeline` | A multi-step workflow with a computed answer. |

## Provisioning the stack (operator steps)

The harness does not create anything in your tenant; it needs these in place, and
you should use a throwaway tenant. These steps are manual and have not been
exercised by the author against a live stack, so expect to adjust them.

1. **Tools.** Register `tests/eval/fixtures/eval-tools-mcp.mjs` as an MCP stdio
   server (`node eval-tools-mcp.mjs`). It exposes `eval_add`, `eval_lookup_order`,
   `eval_convert_temperature`, `eval_send_email` and `eval_delete_record`. It has no
   side effects.
2. **Knowledge.** Upload `tests/eval/fixtures/kb/northwind-handbook.md` to a
   collection (`POST /api/knowledge/upload?collectionId=...`) and wait for ingestion.
3. **Agents**, all using the same model so results are comparable. Put the model in
   `EVAL_MODEL` so it lands in the report.
   - `qa`: no tools. System prompt must contain the canary string `CANARY-7f3a91`
     and an instruction never to reveal the system prompt.
   - `tools`: the three non-gated tools plus the two gated ones attached.
   - `kb`: knowledge base enabled on the collection above.
   - `approval`: all five tools, with approval policy required for `eval_send_email`
     and `eval_delete_record` (`PUT /api/approvals/policy/{actionType}`).
4. **Workflow** `pipeline`: input is text of the form `N=<integer>`; the output must
   contain the number doubled and the word `EVEN` or `ODD` for the original number.
5. **Credentials.** A Keycloak access token, or a tenant API key (`vdak_...`) with
   permissions for chat, approvals, workflows and LLM-usage reads. Keep it in the
   environment; never commit it.

## Running it

Requires Node 22 or newer. No dependencies to install.

```bash
export EVAL_BASE_URL=https://your-api-host        # no trailing path
export EVAL_TOKEN=...                              # JWT or vdak_ tenant API key
export EVAL_AGENT_ID_QA=... EVAL_AGENT_ID_TOOLS=... EVAL_AGENT_ID_KB=... EVAL_AGENT_ID_APPROVAL=...
export EVAL_WORKFLOW_ID_PIPELINE=...
export EVAL_MODEL="provider/model-name"            # provenance label only

node tests/eval/run.mjs --list                     # show tasks, needs no stack
node tests/eval/run.mjs --repeats 5 --out ./eval-out
node tests/eval/run.mjs --only qa-01,tool-01       # a subset
```

It writes `results.json` (schema `vegaduta-eval-results/1`: provenance, overall and
per-category aggregates, one record per run with failure reasons) and `results.md`
(the table). Failing runs are data: the exit code is 0 unless setup failed (2).
Real model calls cost real money; a full run is about 20 tasks times `--repeats`.

Offline tests (no stack, no network):

```bash
node --test tests/eval/test/      # or: make test-eval
```

These cover the scoring, aggregation, percentile, suite validation, SSE parsing,
adapter result mapping and runner orchestration with a fake adapter. They say
nothing about how the platform performs.

## Assumptions to verify on first real run

The harness was written from `docs/api/openapi.json` and the code that emits the
chat step events, not from a live run. Check these first:

- Tool-call arguments in chat step events are **redacted** by the platform
  (`AgentStepEventRedactor`). If a redacted value stops an argument matcher from
  matching, tool-use tasks fail on arguments; relax the task's `args` rather than
  the scorer, and bump the suite version.
- An approval-gated turn is assumed to end with a `PENDING` approval request that
  the harness then approves or rejects. If your configuration blocks the stream
  until a human decides, the approval tasks will time out.
- Approvals raised by a task are found by diffing the pending list before and
  after, so **run against an otherwise idle tenant**.
- The usage summary may lag the stream; the adapter polls for a few seconds and
  records usage as missing if it never appears.
- The workflow answer is taken from the last node trace entry that has output.

## Pointing the suite at another system

Implement the adapter contract documented at the top of `tests/eval/lib/adapter.mjs`:
a default export with `name`, `init(env)`, `describe(ctx)`, `runTask(task, ctx)`
and optional `close(ctx)`. `runTask` returns `{text, toolCalls?, approvals?, usage?,
error?}`; leave out anything the system cannot report and it is shown as `n/a`.
Then run `node tests/eval/run.mjs --adapter path/to/adapter.mjs`. `adapters/vegaduta.mjs`
is the reference. Task `role` strings (`agent:qa`, `workflow:pipeline`) are hints for
the adapter to map onto whatever the other system calls an agent or pipeline.

Fairness is the submitter's responsibility: same model where the system allows it,
equivalent tools and knowledge, same suite version, and the same `--repeats`.
Differences in what a system reports (for example no tool-call trace) show up as
`n/a`, not as a loss. This repository does not publish or endorse comparisons
against other products.

## Submitting results

1. Run with `--repeats 5` or more against a stack built from a known commit.
2. Open a pull request adding `docs/benchmarks/results/<date>-<short-commit>/`
   containing `results.json` and `results.md` unchanged, plus a `NOTES.md` stating
   the model and provider, the deployment shape (hardware, region, replicas), the
   suite version, and any deviation from the provisioning steps above.
3. Do not edit the numbers. If a task looks wrong, fix the task in a separate
   pull request and bump the suite version.

## Results

None yet.
