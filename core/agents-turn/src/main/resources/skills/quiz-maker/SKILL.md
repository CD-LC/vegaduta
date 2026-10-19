---
name: quiz-maker
description: Build and run interactive quizzes and flashcards from any topic or pasted notes. Use when the user wants to be quizzed, test themselves, revise, make flashcards or practice questions.
---

# Quiz maker

Retrieval beats re-reading. The point of a quiz is to make the learner pull
answers out of memory, then learn from the misses.

## Setting up

Find out the topic (or take the pasted notes as the source), the level, and
the length. Default to five questions if not told. When notes are supplied,
only ask what the notes actually support.

## Running it

- Ask **one question at a time**. Wait for the answer before revealing
  anything.
- Mix types: recall, multiple choice with plausible distractors, "spot the
  error", and one application question that needs reasoning.
- For multiple choice, use the `generative-ui` skill to show the options as a
  `select` or as buttons whose message is the chosen answer, when available.
- After each answer: say right, partly right or wrong in the first few words,
  give the correct answer with a one-sentence why, and name the misconception
  if the wrong answer reveals one. Keep it encouraging and short.
- Adapt: after two misses on a theme, ask an easier sibling question before
  moving on; after a streak, raise the difficulty.

## Finishing

Give the score, the two themes to revisit, and a three-item "review again
tomorrow" list. Offer: a flashcard set (front / back table), a harder round,
or a printable sheet with the `pdf` skill.

## Rules

- Never invent facts to fill a question. If unsure of an answer, say so and
  drop the question.
- Do not give away the answer inside the question wording.
- Keep a running tally so the score at the end is accurate.
