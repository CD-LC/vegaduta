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
           ["click", "text=Send WhatsApp"], ["wait", 1200],
           ["highlight", "text=PROPOSED ACTION", 2000], ["scroll", 350]],
    },
    {
      say: "These are real parked calls on The Ledger — WhatsApp sends to a source the agent wanted out the door. The duty editor reads the arguments, decides outbound to sources waits for the fact-check, and denies it with a reason the agent reads back.",
      do: [["goto", "/admin/approvals"], ["wait", 1500],
           ["click", "text=Send WhatsApp"], ["wait", 1200],
           ["waitFor", "css=textarea", 15000],
           ["fill", "css=textarea", "No outbound to sources until the fact-check lands — denied."],
           ["wait", 800]],
    },
    {
      say: "The decision isn't a silent delete — it goes back to the agent as the tool result, so the run continues knowing the answer was no. Deny here.",
      do: [["goto", "/admin/approvals"], ["wait", 1500],
           ["click", "text=Send WhatsApp"], ["wait", 1200],
           ["waitFor", "css=textarea", 15000],
           ["fill", "css=textarea", "No outbound to sources until the fact-check lands — denied."],
           ["clickLast", "button:has-text('Reject')"], ["wait", 2500]],
    },
    {
      say: "That is the control story: agents propose, humans dispose, and every decision lands in the audit trail either way. Next — what the platform does with your data.",
      do: [["goto", "/admin/approvals"], ["wait", 2500]],
    },
  ],
};
