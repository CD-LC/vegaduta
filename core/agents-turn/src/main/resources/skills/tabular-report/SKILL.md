---
name: tabular-report
description: Build clean tables from rows of data - column design, units, sorting, subtotals and totals - inline as a markdown table or exported as an Excel workbook or CSV through generate_document. Use when the user asks for a table, spreadsheet, listing, breakdown, ranking, export or anything with rows and columns.
---

# Tabular report

A table is a claim about structure: these rows are comparable, these columns matter. Design the
columns before filling the cells.

## Workflow

1. **Name the row.** What is one row? (one customer, one day, one SKU, one ticket). Say it in the
   lead sentence: "One row per order, October 2026."
2. **Choose columns** - at most 6 inline, at most 12 in a workbook. Identifier first, then the
   measures the reader decides on, then context. Drop columns the reader cannot act on.
3. **Normalise values.** Same unit per column (stated in the header), same decimals, same date
   format, same category spellings. Blank or unknown values become `-`.
4. **Sort and group.** Sort by the deciding measure, descending, unless the data is a time series
   (ascending by date). Group only when a subtotal per group is useful; then add the subtotal
   rows and a final total, computed from the rows shown.
5. **Check it.** Row count matches what the user gave or what the query returned; totals
   reconcile; no duplicate rows; nothing truncated silently. If you trimmed rows, say how many
   are not shown and offer the full export.

## Inline (chat) or file?

- Up to 25 rows x 6 columns: a markdown pipe table in the reply.
- Larger, or the reader will filter, pivot or share it: `generate_document` with
  `type: "XLSX"`, `filename` like `orders-2026-10`, `title`, and `table: { headers, rows }`.
  Put a 3-5 row preview inline so the reply is useful without opening the file.
- Raw data for another system: `type: "CSV"` with the same `table`. No totals row in a CSV.
- Both: the workbook holds everything, the reply holds the headline and the preview.

## Headers that work

`Customer`, `Orders`, `Revenue (INR)`, `Avg order (INR)`, `Last order`, `Status`. Short, Title
case for the first word only, unit in parentheses, no abbreviations the reader has to decode.

## When the tool returns

Paste the `downloadUrl` on its own line, then one sentence: rows, columns, sort order, and
whether a total row is included. If the tool is not offered or fails, give the inline table and
say the export could not be produced - never invent a link.
