---
name: docx
description: Create Word documents as downloadable .docx files - reports, proposals, letters, memos, policies, SOPs, meeting minutes, contracts drafts. Use when the user asks for a Word document, a .docx, or an editable written document they can download.
---

# Word documents

You produce .docx files with the `generate_document` tool (`type: "DOCX"`). Choose DOCX when
the user will **edit** the result; choose the `pdf` skill when they will only read or send it.

## How your input becomes a document

- `title` is the document title.
- Each `sections` entry is a headed section: `heading`, then its `paragraphs` in order, then its
  `bulletPoints`. Paragraphs always render before bullets within a section - if you need
  text after a list, start a new section.
- `table` renders as one table after the sections.
- There is one heading level, and no images, footnotes, tracked changes, headers/footers or
  table of contents. Do not claim otherwise. Number headings yourself ("2.1 Scope") when the
  document needs visible hierarchy.

## Writing the content

1. **Decide the document type first** - it fixes the structure. See the skeletons below.
2. **Front-load.** The first section answers "what is this and what do you want from me?" in
   under 100 words. Busy readers stop there.
3. **Paragraphs are 2-4 sentences.** Each `paragraphs` entry is one paragraph - do not put
   newlines inside one.
4. **Bullets are for parallel items** (requirements, steps, options), not for prose chopped into
   fragments. Keep each bullet grammatical and parallel with its siblings.
5. **Plain language.** Prefer the specific verb and the concrete number. Delete "in order to",
   "it should be noted that", "leverage".
6. **Never invent** names, figures, dates, legal clauses or citations. Use `[to confirm]` for a
   real gap and list those gaps in your reply.

## Skeletons

**Report**: Summary -> Background -> Findings (one section per finding, conclusion as the
heading) -> Recommendations -> Next steps.

**Proposal**: What we propose -> The problem -> Approach -> Deliverables and timeline (table)
-> Cost -> Why us -> Terms and next step.

**SOP / procedure**: Purpose -> Scope (who, when) -> Before you start -> Steps (numbered
bullets, one action each, starting with a verb) -> If something goes wrong -> Owner and review
date.

**Meeting minutes**: Date, attendees -> Decisions -> Action items (table: action, owner, due
date) -> Discussion notes -> Next meeting.

**Letter / memo**: Purpose in the first sentence -> the necessary detail -> the specific action
and date requested -> sign-off.

## Legal, medical, financial documents

You can draft these, but say once, plainly, that it is a draft for review by a qualified
professional and is not advice. Do not present a contract draft as ready to sign.

## Before and after

- Ask one clarifying question only if the answer changes the structure (audience, or which
  document type). Otherwise draft.
- `filename` without extension, descriptive: `vendor-onboarding-sop`.
- After the tool returns, include the `downloadUrl` verbatim on its own line, summarise the
  sections in one line, and list any `[to confirm]` gaps.
