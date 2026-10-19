---
name: pptx
description: Create PowerPoint slide decks and presentations as downloadable .pptx files. Use when the user asks for a deck, slides, a presentation, a pitch, or a PowerPoint, or wants existing content turned into slides.
---

# PowerPoint decks

You produce .pptx files with the `generate_document` tool (`type: "PPTX"`). This skill is about
making the deck good, and about structuring the tool input so it renders the way you intend.

## How your input becomes slides

- `title` becomes the **title slide**.
- Each entry in `sections` becomes **exactly one slide**: `heading` is the slide title,
  `paragraphs` are plain body lines, `bulletPoints` are bulleted lines (paragraphs render first).
- `table`, if present, becomes **one extra slide at the end** holding the whole table.
- There are no images, charts, speaker notes, themes or custom layouts. Do not promise them. If
  the user needs a chart, put the numbers in a small table slide and say the chart can be added
  in PowerPoint.

So: **plan the deck as a list of slides first, then map one slide to one section.**

## Writing slides people can actually present

1. **One idea per slide.** If a heading needs "and", split the slide.
2. **Headings are conclusions, not labels.** "Churn fell 18% after onboarding redesign", not
   "Churn". A reader skimming only the headings should get the whole argument.
3. **3-5 bullets per slide, under ~12 words each.** Slides are not documents. Cut filler words;
   keep numbers.
4. **Use `paragraphs` sparingly** - one short framing sentence at most. Default to bullets.
5. **Keep tables small**: at most ~6 columns and ~8 rows, or they become unreadable on a slide.
   Put larger data in a spreadsheet instead (the `xlsx` skill) and summarise it here.
6. **Length**: if the user gives no length, aim for 6-10 slides. A 5-minute talk is ~5 slides.

## A reliable default structure

1. Title (from `title`)
2. The point - the single takeaway, stated up front
3. Context / problem
4. 2-5 slides of evidence or proposal, one idea each
5. Risks or open questions (when it is a decision deck)
6. Next steps - who does what by when

For a pitch, a status update or a training deck, see [STRUCTURES.md](STRUCTURES.md) for the
section-by-section outline. Read it only when the request is one of those.

## Before you call the tool

- If the user has not told you the audience or purpose and it changes the content, ask **one**
  short question. Otherwise proceed - a draft they can react to beats a questionnaire.
- Never invent facts, figures, names or quotes to fill a slide. Use what the user gave you or
  what you found with your tools; mark a genuine gap as `[to confirm]`.
- `filename` has no extension and should be descriptive: `q3-board-update`, not `presentation`.

## After the tool returns

Include the returned `downloadUrl` verbatim on its own line - that is how the user gets the
file. Then give a two-line summary of the slide flow and offer one concrete revision (shorter,
different audience, add a slide on X). Do not paste the full slide text back into the chat.
