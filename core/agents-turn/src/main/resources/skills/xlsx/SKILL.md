---
name: xlsx
description: Create Excel spreadsheets and CSV files as downloadable files - tables, trackers, budgets, comparison sheets, data exports. Use when the user asks for a spreadsheet, Excel, .xlsx, .csv, a tracker, or wants data organised into rows and columns.
---

# Spreadsheets

You produce spreadsheets with the `generate_document` tool: `type: "XLSX"` for Excel, `type:
"CSV"` when the user wants to import the data into another system.

## What the tool can and cannot do - be honest about this

- The file has **one sheet**. `table.headers` is the bold header row; `table.rows` are the data
  rows. This is the main payload - always send a `table`.
- **Every cell is stored as text. There are no formulas, number formats, charts, colours,
  frozen panes or multiple sheets.** Do not tell the user you added a SUM formula or a chart.
- Because there are no formulas, **you compute every derived value yourself** (totals,
  percentages, differences) and write the result into the cell. Show your arithmetic was done:
  add a clearly labelled `Total` row rather than implying the sheet recalculates.
- If the user explicitly needs live formulas, multiple sheets or charts, build the data sheet
  anyway, tell them plainly which part they will need to add in Excel, and give them the exact
  formula to paste (for example `=SUM(C2:C14)`).

## Designing a table that works as data

1. **One row = one record, one column = one attribute.** No merged concepts ("Name / Email" in
   one column), no blank spacer rows, no sub-headers in the middle of the data.
2. **Header names are short, specific and unique**: `Unit price (INR)`, not `Price` twice.
   Put the unit or currency in the header, never in the cells.
3. **Cells hold bare values**: `1250.50`, not `Rs. 1,250.50 approx`. No thousands separators
   inside numbers - they break sorting and import.
4. **Dates are ISO `YYYY-MM-DD`** so they sort correctly as text and import everywhere.
5. **Every row has the same number of cells as there are headers.** Use an empty string for
   unknown values; never shift columns.
6. **Consistent categories**: pick `In progress` once and reuse it exactly - filters depend on it.
7. Put identifying columns first (name, ID, date), measures after, free-text notes last.

## Totals and summaries

- Add summary rows at the **bottom**, with the label in the first column (`Total`, `Average`).
- Double-check the arithmetic before you call the tool: recompute the total a second way (for
  example sum the rounded column, then compare). A wrong total in a spreadsheet is worse than
  no total.
- Round money to 2 decimals and percentages to 1, consistently down the column.

## CSV specifics

Send only `table` (no `title`, no `sections`) so the file is pure data with a single header
row. Anything else makes it fail on import.

## Before and after

- Never fabricate data to fill rows. If the user wants a template, leave the rows empty or
  include 1-2 rows obviously marked `EXAMPLE`.
- `filename` without extension, descriptive: `2026-q3-marketing-budget`.
- After the tool returns, include the `downloadUrl` verbatim on its own line, state the row and
  column count, and mention any value you had to assume.
