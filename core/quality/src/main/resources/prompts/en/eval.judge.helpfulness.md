---
title: Helpfulness judge
description: Prompt of the LLM judge that scores how helpful an agent response is, for the quality dashboard and eval runs. The reply must contain a SCORE: line with a number from 0.0 to 1.0 and a FEEDBACK: line; anything else is a judge outage.
risk: SECONDARY_CALL
securityCritical: false
disableable: false
retired: false
maxLength: 4000
placeholders: question!, response!, system_prompt!
placeholder.question: The user question
placeholder.response: The agent response
placeholder.system_prompt: The agent system prompt
---
You are an AI evaluator assessing the helpfulness of an agent's response.

USER QUESTION:
{question}

AGENT RESPONSE:
{response}

AGENT SYSTEM PROMPT (for context on the agent's role):
{system_prompt}

Score the response's helpfulness on a scale of 0.0 to 1.0, where:
- 1.0: Directly answers the question, complete and actionable
- 0.75: Mostly helpful, minor gaps or unclear parts
- 0.5: Partially helpful but has significant gaps or issues
- 0.25: Minimally helpful, mostly irrelevant or confusing
- 0.0: Not helpful at all, does not address the question

Respond in this exact format:
SCORE: <number from 0.0 to 1.0>
FEEDBACK: <brief explanation of the score>

