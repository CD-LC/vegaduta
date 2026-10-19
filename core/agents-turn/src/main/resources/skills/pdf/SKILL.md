---
name: pdf
description: Create PDF files as downloadable documents - finished reports, summaries, invoices, quotes, certificates, one-pagers, handouts. Use when the user asks for a PDF, or for a final, non-editable, ready-to-send or ready-to-print document.
---

# PDF documents

You produce PDFs with the `generate_document` tool (`type: "PDF"`). A PDF is the **final**
form: the reader cannot fix it, so it has to be right and complete before you generate it. If
the user is still iterating on wording, suggest a Word document first (the `docx` skill).

## How your input becomes a PDF

- `title` is the document title; each `sections` entry is a heading followed by its
  `paragraphs`, then its `bulletPoints`; `table` renders as one table after the sections.
- No images, logos, signatures, page numbers, custom fonts or fillable form fields. Do not claim
  them. This tool **creates** PDFs only - it cannot read, merge, split, sign or edit an existing
  PDF. If asked to, say so and offer what you can do instead (for an uploaded PDF you can read
  in the conversation, you can summarise it or rebuild its content as a new document).

## Finality checklist - run it before calling the tool

1. **Completeness**: no `[to confirm]`, `TBD` or placeholder text left in. If something is
   genuinely unknown, ask now rather than shipping a gap in a final document.
2. **Numbers reconcile**: line items sum to the stated total; percentages add up; dates are in
   one format throughout.
3. **Names and identifiers** are spelled exactly as the user gave them.
4. **Self-contained**: a reader with no access to this chat understands who it is from, who it
   is for, what date it was issued, and what they should do next.
5. **Length fits the purpose**: a one-pager is 3-4 short sections; do not pad.

## Common PDF types

**One-pager / summary**: headline section stating the point -> 2-3 supporting sections -> a
"Next step" section with a contact or action.

**Invoice / quote**: a header section (issuer, recipient, document number, issue date, due
date - only values the user supplied) -> `table` of line items with headers like `Description`,
`Qty`, `Unit price (INR)`, `Amount (INR)` -> a final `Total` row you have computed and
re-checked -> a payment terms section. Never invent a tax rate, tax ID, bank detail or invoice
number; ask for them. State clearly this is a generated document, not a tax filing.

**Certificate / letter**: keep it to the title plus 1-2 sections; wording should be formal and
exact.

**Report**: use the report skeleton from a normal document - Summary, Findings,
Recommendations - and keep tables to what fits a page width (about 6 columns).

## After the tool returns

Include the `downloadUrl` verbatim on its own line. Say in one line what the PDF contains, and
remind the user it is final - if they want edits, you will regenerate it.
