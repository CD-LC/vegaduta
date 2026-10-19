---
name: comparison-matrix
description: Compare options, vendors, plans, tools or approaches side by side in a criteria matrix with scores, weights and a verdict - inline markdown or an Excel workbook through generate_document. Use when the user asks to compare, evaluate, choose between, rank, shortlist or weigh alternatives, or asks for pros and cons.
---

# Comparison matrix

A comparison is only useful if the criteria come first and the verdict follows from them. Never
start from the option you prefer.

## Workflow

1. **Fix the criteria** - 4-8 of them, each measurable or at least observable, each with a
   weight (1-5) that reflects what the user said matters. State the weights; if the user gave
   none, propose them in one line and proceed.
2. **Fill the matrix** - options as columns, criteria as rows (or the reverse when there are more
   than 5 options). Cells hold a fact (`$29/user/month`, `99.9% SLA`, `No`) and, when scoring, a
   score 1-5. Facts you do not have are `-` with a note; never guess a price, a limit or a
   feature.
3. **Score and total** - weighted total per option, computed from the cells shown. Round to one
   decimal.
4. **Verdict** - one sentence naming the winner and the single criterion that decided it, then
   the condition under which a runner-up wins instead ("if offline use matters more than price,
   choose B").
5. **Caveats** - data gaps, assumptions, how old the facts are.

## Layout

- Inline: a markdown pipe table, criteria as the first column, `Weight` as the second, one
  column per option, a final `Weighted total` row in bold. Up to 5 options x 8 criteria.
- Larger, or the user will adjust weights themselves: `generate_document` with
  `type: "XLSX"`, `title`, and `table` with headers `["Criterion", "Weight", <option names...>]`
  and the total row last. Keep a 3-row preview and the verdict inline.
- Pros and cons requested: still build the matrix (it keeps you honest), then present each
  option as `**Option** - pros: ...; cons: ...` in 2-3 lines, followed by the verdict.

## Guardrails

- Same facts for every option; a criterion left blank for one option is a gap to state, not a
  zero.
- Do not let the number of criteria favour an option (five "nice to have" rows should not
  outweigh one "must have"); use weights, or a "must have" gate row first.
- When the tool returns, paste the `downloadUrl` verbatim on its own line and say what the file
  contains. If no file was produced, say so rather than describing one.
