export default {
  slug: "b14-approvals",
  title: "VegaDūta Academy — Approvals deep-dive",
  subtitle: "The signature before the action",
  next: "b15 — Data privacy",
  voice: "en-US-AndrewNeural",
  scenes: [
    {
      say: "Approvals is where agent ambition meets a human signature. Anything risky — calling an external tool, publishing, spending — parks here until somebody accountable says yes or no.",
      do: [["goto", "/admin/approvals"], ["wait", 2500]],
    },
    {
      say: "Each pending card is a full disclosure, not a yes/no mystery: the proposed action, the exact arguments it would run with, which policy held it, and what approving lets happen.",
      do: [["goto", "/admin/approvals"], ["wait", 1500],
           ["waitFor", "text=PENDING", 15000],
           ["highlight", "text=PROPOSED ACTION", 2000], ["scroll", 350]],
    },
    {
      say: "These are real parked calls on The Ledger — API-guide tool invocations an agent wanted. The duty editor reads the arguments, decides this one isn't needed, and denies it with a reason the agent reads back as the result.",
      do: [["goto", "/admin/approvals"], ["wait", 1500],
           ["waitFor", "css=textarea", 15000],
           ["fill", "css=textarea", "Ledger desk does not need this call right now — denied."],
           ["wait", 800]],
    },
    {
      say: "The decision isn't a silent delete — it goes back to the agent as the tool result, so the run continues knowing the answer was no. Deny here.",
      do: [["goto", "/admin/approvals"], ["wait", 1500],
           ["waitFor", "css=textarea", 15000],
           ["fill", "css=textarea", "Ledger desk does not need this call right now — denied."],
           ["click", "button:has-text('Deny')"], ["wait", 2500]],
    },
    {
      say: "That is the control story: agents propose, humans dispose, and every decision lands in the audit trail either way. Next — what the platform does with your data.",
      do: [["goto", "/admin/approvals"], ["wait", 2500]],
    },
  ],
};
