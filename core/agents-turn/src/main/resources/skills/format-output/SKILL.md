---
name: format-output
description: Choose the right presentation for an answer or a dataset - inline markdown table, bullet list, numbered steps, key-value summary, or a downloadable file (XLSX, CSV, PDF, DOCX, PPTX, Markdown). Use when the user asks to format, present, tabulate, structure, lay out, export or "make this readable", or whenever a reply would carry more than a handful of numbers.
---

# Format the output to fit the data

The reader should get the shape of the answer in two seconds. Pick the layout from the data, not
from habit. Decide first, then write.

## 1. Decide the shape

| The content is... | Use |
|---|---|
| One fact, one number, one decision | A sentence. Bold the value once. |
| 2-6 attributes of one thing | A key-value list (`**Label:** value`, one per line). |
| Several things with the same attributes, up to 25 rows and 6 columns | A markdown table (see rules below). |
| More than 25 rows, or more than 6 columns, or the user will sort/filter it | `generate_document` with `type: "XLSX"` (or `"CSV"` for a raw export). Summarise the top 5 rows inline. |
| A sequence the reader performs | A numbered list; one action per step; expected result in the same line. |
| Unordered points of equal weight | Bullets, 3-7 of them; longer than that, group under `###` headings. |
| A finished, shareable document (report, proposal, one-pager, invoice) | The `pdf` (final) or `docx` (editable) skill. |
| A deck | The `pptx` skill. |
| A ranking or a choice between options | The `comparison-matrix` skill. |
| A summary for leadership | The `executive-brief` skill. |
| Code, config, logs, SQL, JSON | A fenced code block with the language tag. Never a table. |

Mixed content gets mixed shapes: a two-line headline, then the table, then "what to do next" as a
short list. Never nest a table inside a list, and never put a paragraph inside a table cell.

## 2. Table rules (chat renders GitHub-style tables)

- First column is the thing being described; measures follow, most important first.
- Header names carry the unit: `Revenue (INR)`, `Latency (ms)`, `Share (%)`. Do not repeat units in cells.
- Numbers: thousands separators, the same number of decimals down a column, right-aligned by
  being plain numbers. Dates in one format (`2026-10-10`). Missing values are `-`, never blank or
  `null`.
- Sort deliberately: by the measure the reader cares about (descending), or by time (ascending).
  Say which when it matters.
- Add a `Total` or `Average` row only when it is meaningful, and compute it from the rows shown.
- Keep cells to a phrase. If a cell needs a sentence, the content wants a list, not a table.
- No empty columns, no column where every value is the same (say it once above the table).

## 3. Chat markdown that renders here

Headings (`##`, `###`), bold, italics, inline code, fenced code blocks, bullet and numbered lists,
links, and pipe tables. Charts, Mermaid diagrams, images from text and HTML do not render in the
chat - do not emit them. For a chart, use a code execution tool if one is offered (save a `.png`,
paste the returned file link) or describe the trend in one sentence with the two numbers that
define it.

## 4. Files

Call `generate_document` only after the content is final. Fill `title`, `sections` and `table`
from the data you have - never from invented figures. When the tool returns, paste the
`downloadUrl` verbatim on its own line and say in one sentence what the file contains. If the
call fails or is not offered, say so plainly and give the content inline instead; never describe
a file that was not produced.

## 5. Before sending

Totals reconcile; every number has a unit; the first line answers the question; nothing the user
asked for is missing; nothing they did not ask for pads the reply.
