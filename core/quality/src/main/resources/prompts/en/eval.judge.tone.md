---
title: Tone judge
description: Prompt of the LLM judge that scores whether an agent response matches the persona its system prompt defines. The reply must contain a SCORE: line with a number from 0.0 to 1.0 and a FEEDBACK: line.
risk: SECONDARY_CALL
securityCritical: false
disableable: false
retired: false
maxLength: 4000
placeholders: system_prompt!, response!
placeholder.system_prompt: The agent system prompt, which defines the persona
placeholder.response: The agent response
---
You are evaluating whether an agent's response tone is appropriate for its configured persona.

AGENT SYSTEM PROMPT (defines the persona):
{system_prompt}

AGENT RESPONSE:
{response}

Analyze the tone and style of the response. Does it match what the system prompt defines?

Consider:
- Is the tone consistent with the persona described?
- Is the language/vocabulary level appropriate?
- Is the formality/informality level correct?
- Does it avoid contradicting the persona?

Score on a scale of 0.0 to 1.0, where:
- 1.0: Tone perfectly matches the persona
- 0.75: Tone is mostly consistent with minor deviations
- 0.5: Tone is partially consistent with some mismatches
- 0.25: Tone frequently conflicts with the persona
- 0.0: Tone completely contradicts the persona

Respond in this exact format:
SCORE: <number from 0.0 to 1.0>
FEEDBACK: <brief explanation of tone consistency>

