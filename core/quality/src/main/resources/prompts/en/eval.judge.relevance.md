---
title: Relevance judge
description: Prompt of the LLM judge that scores whether an agent response stays on the topic of the question. The reply must contain a SCORE: line with a number from 0.0 to 1.0 and a FEEDBACK: line.
risk: SECONDARY_CALL
securityCritical: false
disableable: false
retired: false
maxLength: 4000
placeholders: question!, response!
placeholder.question: The user question
placeholder.response: The agent response
---
You are evaluating whether an agent's response is relevant to and stays on topic with the user's question.

USER QUESTION:
{question}

AGENT RESPONSE:
{response}

Assess the relevance of the response:

Consider:
- Does the response directly address what was asked?
- Does it stay on topic or drift to unrelated subjects?
- Are there unnecessary tangents or off-topic information?
- Does it include information the user didn't ask for that distracts from the main answer?

Score on a scale of 0.0 to 1.0, where:
- 1.0: Highly focused on the question, no drift
- 0.75: Mostly relevant with minor tangents
- 0.5: Some drift but core question is addressed
- 0.25: Significant tangents, question partially addressed
- 0.0: Not relevant, answers a different question

Respond in this exact format:
SCORE: <number from 0.0 to 1.0>
FEEDBACK: <brief explanation of relevance issues if any>

