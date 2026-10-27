# Ep 6 — Guardrails: the editor's red pen

**Length** ~4 min · **Level** everyone · **Renders to** `ep06-guardrails.mp4`

The Ledger's agents can already reach the outside world — which means
outside text reaches them back. `/admin/guardrails` is where the newsroom
draws the line: regex patterns checked against every tool result before it
reaches a model, on top of platform defaults nobody can turn off.

## The scenes

| Step | Click-path | What happens |
|---|---|---|
| Two layers | `/admin/guardrails` → **Where these apply** | Tool results are always checked; conversations are a per-agent opt-in (the agent's Guardrails card on `/agents`) |
| Pattern rules | same page | Rules are regular expressions, matched case-insensitively, anywhere in the text |
| Platform defaults | **Platform defaults** section | Three built-in rules — prompt-injection block, cloud/API key redaction, private-key/JWT redaction — can't be edited or disabled |
| Write a rule | **New rule** → name → description → pattern → **Try it** → **Create rule** | Creates `Codename OPERATION MAGPIE` (Block); the tester live-checks sample text against the pattern before saving |
| Manage | rule card → **Turn off** / **Edit** / **Delete** | Toggle without deleting; delete sits behind a confirm |
| Model call policy | **Model call content policy** → checkboxes → **Save policy** | Redact personal data from outgoing prompts; screen for prompt-injection — flag-only logs to audit, block rejects the call |

## Mental model

- **Tool output is untrusted text.** Every rule here runs on whatever a
  tool returns — API, file, web page, MCP server — before the model reads
  it. No per-agent switch exists for this layer because there is no safe
  version of skipping it.
- **Block vs Redact.** Block withholds the whole result and tells the
  agent which rule fired; Redact swaps only the matched text for
  `[REDACTED]` — usually what you want for a key or an internal id.
- **Custom rules add to the floor, never lower it.** Platform defaults
  stay on no matter what you write; your patterns are extra lines the red
  pen draws.
- **Conversations are opt-in.** Checking what users type and what agents
  reply happens in each agent's Guardrails card — Block works both
  directions there, Redact can only rewrite the reply.
- **Never type a real secret on camera — or in a rule.** A codename like
  `OPERATION MAGPIE` demonstrates the whole flow; an actual credential
  belongs in `/secrets`, not in a regex.

## After watching

Continue to [Ep 7 — Memory & learning](../07-memory-learning/lesson.md),
where the newsroom starts remembering what its readers care about.
