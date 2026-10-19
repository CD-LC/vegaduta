---
name: executive-brief
description: Condense findings into an executive summary - headline, three to five key numbers, what changed, risks, recommendation and next steps - in chat or as a one-page PDF or Word document through generate_document. Use when the user asks for a summary, brief, overview, TL;DR, status update, memo, or something to send to leadership or a client.
---

# Executive brief

The reader has one minute and decides something at the end of it. Lead with the decision they
need to make, back it with the fewest numbers that carry it, and stop.

## Structure (in this order)

1. **Headline** - one sentence that states the conclusion with its key number. Not the topic,
   the conclusion: "Churn doubled in September because of the pricing change; reverse it for
   SMB accounts."
2. **Key numbers** - three to five, as a key-value list or a 2-column table: `**Metric:** value
   (change vs previous period)`. Every number has a unit and a comparison.
3. **What changed and why** - two to four short paragraphs or bullets, cause before effect,
   facts before interpretation. Mark inference as inference ("likely because").
4. **Risks / open questions** - what could make the conclusion wrong, and what is not known yet.
5. **Recommendation** - one clear recommendation; alternatives only if genuinely close.
6. **Next steps** - owner, action, date. A numbered list; three to five items.

Length: 150-300 words in chat; one page as a document. Cut adjectives, keep numbers.

## Chat or document?

- Chat: the structure above with `##` headings or bold labels; no preamble.
- Sent onward or printed: `generate_document` with `type: "PDF"` (final) or `"DOCX"` (the reader
  will edit), `title` = the headline, one `sections` entry per part above (heading + paragraphs
  or bulletPoints), and `table` for the key numbers (`headers: ["Metric", "Value", "Change"]`).
  Run the `pdf` skill's finality checklist first: no placeholders, numbers reconcile, names
  exact, self-contained (who, for whom, date).

## Guardrails

- Never invent a number, a date or an owner. Unknown items are "[to confirm]" in a draft, or a
  question to the user before a final PDF.
- Do not restate the data you were given; the brief is what it means.
- When the tool returns, paste the `downloadUrl` verbatim on its own line and say in one line
  what the document covers. If it was not produced, say so; do not describe a file that does
  not exist.
