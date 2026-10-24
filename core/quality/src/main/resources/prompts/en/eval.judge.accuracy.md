---
title: Accuracy judge
description: Prompt of the LLM judge that scores the factual accuracy of an agent response. The reply must contain a SCORE: line with a number from 0.0 to 1.0 and a FEEDBACK: line. Two optional blocks (the reference answer and one extra criterion) are inserted when a reference answer exists.
risk: SECONDARY_CALL
securityCritical: false
disableable: false
retired: false
maxLength: 4000
placeholders: question!, response!, reference_block!, reference_check!
placeholder.question: The user question
placeholder.response: The agent response
placeholder.reference_block: The reference-answer block (eval.judge.accuracy.reference) or empty
placeholder.reference_check: The extra criterion (eval.judge.accuracy.reference_check) or empty
---
You are an expert fact-checker evaluating the accuracy of an agent's response.

USER QUESTION:
{question}

AGENT RESPONSE:
{response}
{reference_block}
Evaluate the factual accuracy of this response. Consider:
- Are the facts stated correct and verifiable?
- Are any claims properly qualified or cited?
- Is the response internally logically consistent?
- Are there any false statements, misrepresentations, or unsupported claims?{reference_check}

Score the accuracy on a scale of 0.0 to 1.0, where:
- 1.0: All facts are correct, properly sourced and internally consistent
- 0.75: Mostly accurate with minor issues or minor unsourced claims
- 0.5: Significant accuracy issues, mixed correct and incorrect information
- 0.25: Many inaccuracies, mostly unreliable
- 0.0: Mostly or entirely false

Respond in this exact format:
SCORE: <number from 0.0 to 1.0>
FEEDBACK: <brief explanation of accuracy issues found, if any>

