---
name: data-analysis
description: Analyse tabular data - CSV, spreadsheets, exports, query results, survey responses. Profiles the data, cleans it, computes statistics, finds trends, outliers and segments, and explains what the numbers mean for the business. Use when the user shares data or asks for analysis, insights, a summary of a dataset, KPIs, cohort or trend analysis.
---

# Data analysis

Turn a table into a decision. The reader wants to know what happened, why, and what to do, in that order.

## Workflow

1. **Profile before you analyse.** Row count, columns and their types, date range, missing values per column, obvious duplicates. Report this in three or four lines so the reader can trust what follows.
2. **Clean, and say what you cleaned.** Trimmed whitespace, parsed dates, unified categories ("UK" and "United Kingdom"), removed exact duplicates. Never silently drop rows; state how many and why.
3. **Answer the question asked.** If none was asked, cover: totals and averages of the key measures, the trend over time, the top and bottom segments, and anything anomalous.
4. **Check yourself.** Do segment totals add up to the grand total? Is a surprising number caused by a data problem rather than the business? Small samples (under about 30) get a warning, not a conclusion.
5. **Explain.** Each finding is one sentence of fact, one of likely cause, one of suggested action.

## Tools

- If a code execution tool is available, compute with it; never eyeball arithmetic on more than a handful of rows.
- When a `run_code` result lists `mountedFiles`, those are the files the user attached: read them by exactly that name from the working directory. An empty or missing list means nothing was attached - ask for the file instead of guessing its contents.
- Save what the user should keep in the working directory: every chart as a `.png` (matplotlib with the `Agg` backend and `plt.savefig`, never `plt.show`), result tables as an `.xlsx` workbook (openpyxl or xlsxwriter), and the complete script as `analysis.py` so the work can be re-run.
- Saved files come back in the result's `files` list. Paste each `files[].url` verbatim on its own line in your reply. Never invent, shorten or rewrite a file link, and never claim a file exists that is not in `files`.
- If `run_code` is not among your tools, say that code execution is not enabled for this agent and work only from what is visible in the conversation; do not pretend to have run anything.
- Without a code execution tool, to hand back a workbook with the results use the `xlsx` skill; for a written report, the `docx` or `pdf` skill.

## Output format

**Headline** - the single most important finding, with its number.

**Key findings** - three to five bullets, each with a figure and a comparison (versus last period, versus average, share of total).

**Table** - the supporting figures, compact.

**Caveats** - data quality issues that could change the conclusion.

**Next steps** - what to do, or what extra data would settle an open question.

## Rules

- Percentages always come with their base: "12% (48 of 400)".
- Correlation is not causation; say "is associated with" unless there was an experiment.
- Median beside mean whenever the data is skewed (revenue, durations, order sizes).
- Never fabricate a value to fill a gap.
