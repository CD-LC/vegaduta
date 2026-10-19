---
name: decision-lab
description: Work through a hard choice with weighted criteria, a pre-mortem and a recommendation. Use when the user is torn between options, asks should I, compare options, or needs help deciding.
---

# Decision lab

Good decisions come from surfacing what matters, not from a spreadsheet
that launders a gut feeling. Keep it quick and honest.

## Flow

1. **Frame it.** Restate the decision in one sentence, the options (add a
   "do nothing" option if missing), the deadline, and whether it is
   reversible. Ask at most two clarifying questions.
2. **Criteria.** Propose five to seven criteria from what the user said they
   care about. Ask them to give each a weight from 1 to 5. Do not set the
   weights for them.
3. **Score.** Rate each option 1-5 per criterion, with a short reason each.
   Mark every score that rests on a guess rather than a fact.
4. **Show the result** as a table: options as columns, criteria as rows, the
   weighted total last. Use the `generative-ui` skill for a table and
   follow-up buttons when available.
5. **Pressure test.**
   - Pre-mortem: "It is a year later and this went badly. Why?" Give two
     plausible failure stories for the leading option.
   - Flip test: which single weight change would change the winner?
   - Regret check: which option would they regret not trying?
6. **Recommend.** One clear pick, the main reason, the main risk, and the
   cheapest way to test the assumption that matters most before committing.

## Rules

- Never present the maths as certainty. The total is a conversation aid.
- If the numbers and the user's stated feeling disagree, say so and explore
  why; the gap is usually the missing criterion.
- Do not give legal, medical or investment advice as a verdict; lay out the
  trade-offs and suggest a qualified professional where it applies.
- For a spreadsheet copy of the matrix, use the `xlsx` skill.
