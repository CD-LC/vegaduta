export default {
  slug: "ep06-guardrails",
  title: "VegaDūta Academy — Ep 6 · Guardrails",
  subtitle: "The editor's red pen",
  next: "Ep 7 — Memory & learning",
  voice: "en-US-ChristopherNeural",
  scenes: [
    {
      say: "Episode six: the red pen. Guardrails are patterns this workspace refuses to pass through — every rule on this page is checked against the result of every tool your agents call, before that text ever reaches the model.",
      do: [["goto", "/admin/guardrails"], ["tryClick", "text=Skip for now"], ["wait", 2500]],
    },
    {
      say: "Here is a real rule already on this newsroom: no guaranteed-returns claims. It is a Block rule — if a fetched article or a draft promises risk-free returns, the text is stopped before the model or a reader ever sees it.",
      do: [["goto", "/admin/guardrails"], ["wait", 1500],
           ["highlight", "text=No guaranteed-returns claims", 2000], ["highlight", "text=Block", 1400]],
    },
    {
      say: "Each rule is a regular expression matched case-insensitively, anywhere in the text — and it layers on top of the platform's built-in checks for prompt-injection markers and secret-shaped strings. Yours add; they never replace.",
      do: [["goto", "/admin/guardrails"], ["highlight", "text=Tool results", 2000], ["wait", 1400]],
    },
    {
      say: "Two layers, enforced in different places. Tool results are always checked — untrusted text coming in. Conversations are opt-in per agent: the agent's own Guardrails card decides whether people's messages and the agent's replies get checked too.",
      do: [["goto", "/admin/guardrails"], ["highlight", "text=Conversations", 2000], ["wait", 1400]],
    },
    {
      say: "That per-agent layer lives on the Agents screen — each card carries its own Guardrails section, where Block works in both directions and Redact can rewrite the agent's reply. The Editor-in-Chief gets the strictest set; interns get the defaults.",
      do: [["goto", "/agents"], ["wait", 2500]],
    },
    {
      say: "Write the rule once on this page and it applies to every agent, every tool, every workflow in the workspace — that is one line between a confident newsroom and a lawsuit. Next episode: what the platform remembers and learns.",
      do: [["goto", "/admin/guardrails"], ["wait", 2500]],
    },
  ],
};
