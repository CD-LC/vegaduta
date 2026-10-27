// ep06 — Guardrails, approvals and the turn journal, demonstrated live for
// The Ledger newsroom. Every scene is real: the rule actually fires on a
// seeded advertiser submission, the journal replay is a real recorded turn,
// and a real pending approval is denied on camera.
//
// The staged data behind the demo:
//   - workspace rule "No guaranteed-returns claims" (Block) — created by
//     academy/tools/seed-ledger.mjs
//   - knowledge doc "coinvault-ad-submission.md" in Ledger Style Guide —
//     its draft copy contains "guaranteed returns", so a knowledge search
//     that returns it is blocked by the rule (live in scene 3)
//   - journaled turn 84d6cba8-… — a real recorded turn whose two
//     search_knowledge_base calls were blocked (scene 4)
//   - four real pending tool approvals on the Assistant agent (scenes 5-6)

export default {
  slug: "guardrails",
  title: "Guardrails, approvals and the turn journal",
  subtitle: "Watch The Ledger's no-certainty rule block a real tool call, replay the tamper-evident journal, and clear the approval queue",
  next: "07-memory-learning",
  voice: "en-US-AndrewNeural",
  scenes: [
    {
      say: "This is The Ledger's rulebook. The workspace wrote one rule — no guaranteed-returns claims — and it runs against every tool result before that text ever reaches the model. Today we watch it actually fire, replay the turn it blocked, and clear the approval queue it feeds.",
      do: [
        ["goto", "/admin/guardrails"],
        ["tryClick", "text=Skip for now"],
        ["waitFor", "text=No guaranteed-returns claims"],
        ["highlight", "text=Your rules"],
      ],
    },
    {
      say: "The rule blocks any copy promising guaranteed or sure-fire financial returns — The Ledger never prints certainty about markets. Below it, platform defaults sit underneath every workspace: prompt-injection markers are blocked and cloud secrets are redacted, always on and not editable.",
      do: [
        ["goto", "/admin/guardrails"],
        ["tryClick", "text=Skip for now"],
        ["waitFor", "text=No guaranteed-returns claims"],
        ["highlight", "text=(guaranteed|sure", 2000],
        ["scroll", 500],
        ["highlight", "text=Prompt-injection markers"],
      ],
    },
    {
      say: "Now the real check. An advertiser slipped a CoinVault draft into the knowledge base promising guaranteed returns. The assistant goes to fetch that ad submission — watch what the rule does to the tool result.",
      do: [
        ["goto", "/agents?agentId=434bc9b9-b90e-40fa-97de-537ee4e23567"],
        ["tryClick", "text=Skip for now"],
        ["waitFor", "css=textarea", 20000],
        ["fill", "css=textarea", "Use your search_knowledge_base tool to find the CoinVault ad submission draft. Call the tool first, then quote what it says."],
        ["keys", "Enter"],
        ["waitFor", "text=guardrail", 150000],
        ["wait", 2500],
        ["highlight", "text=guardrail", 2500],
      ],
    },
    {
      say: "The reply says it plainly — a workspace guardrail blocked the search results. And this is the recorded proof: the turn journal for the same run, hash-chained end to end. Intake, the model call, two knowledge-search calls that were both blocked by the guard, the outbound audit, the verification check, and the final commit.",
      do: [
        ["goto", "/agents/turns"],
        ["tryClick", "text=Skip for now"],
        ["fill", "css=input >> nth=2", "434bc9b9-b90e-40fa-97de-537ee4e23567"],
        ["fill", "css=input >> nth=3", "84d6cba8-39e3-4063-858e-2192b30dd54c"],
        ["click", "button:has-text('Inspect turn')"],
        ["waitFor", "text=blocked", 20000],
        ["tryHighlight", "text=/search_knowledge_base.*blocked|blocked.*search/i", 3000],
        ["scroll", 400],
      ],
    },
    {
      say: "Rules shape what reaches the model; approvals shape what the agent may do at all. Four tool calls are parked here right now — each one shows the proposed action, its arguments, the policy that held it, and exactly what happens if you approve or deny.",
      do: [
        ["goto", "/admin/approvals"],
        ["tryClick", "text=Skip for now"],
        ["waitFor", "text=PENDING"],
        ["highlight", "text=PROPOSED ACTION", 2200],
        ["scroll", 500],
      ],
    },
    {
      say: "This queue is a decision point, not a formality. The queued request is an API-guide call the newsroom doesn't need — so the duty editor denies it, with a reason the agent reads back as the result. Watch the queue take the decision.",
      do: [
        ["goto", "/admin/approvals"],
        ["tryClick", "text=Skip for now"],
        ["waitFor", "css=textarea", 20000],
        ["fill", "css=textarea", "Not needed for the Ledger desk — this API-guide call stays denied by the duty editor."],
        ["highlight", "button:has-text('Deny')", 1600],
        ["click", "button:has-text('Deny')"],
        ["wait", 3000],
        ["tryHighlight", "text=/decision|denied|Deny/i", 2000],
        ["wait", 1500],
      ],
    },
    {
      say: "The same Controls column holds the workspace's runtime settings — seventy of them, each showing the value in effect right now against the platform default, the allowed range, and the risk of changing it. The desk already touched one: finish cut-off replies.",
      do: [
        ["goto", "/admin/runtime-settings"],
        ["tryClick", "text=Skip for now"],
        ["waitFor", "text=of 70 settings", 20000],
        ["highlight", "text=Finish cut-off replies", 2000],
        ["highlight", "text=In effect now", 2200],
      ],
    },
    {
      say: "And nothing here is silent — the Change history tab is the audit trail. There's the desk's override: the value that was set, the reason they gave, who did it and when. That's the controls story end to end: rules bound the content, approvals bound the actions, and every single decision leaves a record.",
      do: [
        ["goto", "/admin/runtime-settings"],
        ["tryClick", "text=Skip for now"],
        ["waitFor", "text=Change history"],
        ["click", "text=Change history"],
        ["wait", 1500],
        ["highlight", "text=chat.finish.enabled", 2500],
      ],
    },
  ],
};
