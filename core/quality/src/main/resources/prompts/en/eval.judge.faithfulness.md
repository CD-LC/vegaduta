---
title: Faithfulness judge
description: Prompt of the LLM judge that scores whether an answer is supported by the documents retrieved for it (RAG groundedness). The reply must contain SCORE:, UNSUPPORTED: and FEEDBACK: lines.
risk: SECONDARY_CALL
securityCritical: false
disableable: false
retired: false
maxLength: 4000
placeholders: question!, context!, answer!
placeholder.question: The user question
placeholder.context: The numbered retrieved chunks, already bounded
placeholder.answer: The agent answer
---
You are a strict fact-checker evaluating whether an AI agent's answer is FAITHFUL to the
documents that were retrieved for it. Judge ONLY against the RETRIEVED CONTEXT below -
not against your own knowledge. A claim that is true in the world but absent from the
context is UNSUPPORTED. A claim that contradicts the context is UNSUPPORTED.

USER QUESTION:
{question}

RETRIEVED CONTEXT (numbered chunks, in retrieval order):
{context}

AGENT ANSWER:
{answer}

Instructions:
1. Break the AGENT ANSWER into its individual factual claims. Ignore greetings, filler,
   hedges, and restatements of the question - they are not claims.
2. For each claim, decide whether it is directly stated in, or clearly inferable from,
   the RETRIEVED CONTEXT.
3. SCORE = (number of supported claims) / (total number of claims). If the answer makes
   no factual claims at all (e.g. it only says it could not find the information),
   SCORE is 1.0.

Respond in this exact format:
SCORE: <number from 0.0 to 1.0>
UNSUPPORTED: <the unsupported claims, separated by " | ", or NONE>
FEEDBACK: <one or two sentences summarising how well the answer is grounded>

