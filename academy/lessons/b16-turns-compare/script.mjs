export default {
  slug: "b16-turns-compare",
  title: "VegaDūta Academy — Turn inspector & compare",
  subtitle: "Replay exactly what the agent did",
  next: "b17 — Audit trail",
  voice: "en-US-AndrewNeural",
  scenes: [
    {
      say: "The Turn inspector is the flight recorder for a single agent turn — intake, model calls, tool calls, guard checks, commit — hash-chained so the record itself can't be rewritten.",
      do: [["goto", "/agents/turns"], ["wait", 2500]],
    },
    {
      say: "Here's a real one: the turn where our guardrail blocked a poisoned knowledge search. Agent ID and turn ID in, Inspect — the chain unfolds.",
      do: [["goto", "/agents/turns"], ["wait", 1500],
           ["fill", "css=input >> nth=2", "434bc9b9-b90e-40fa-97de-537ee4e23567"],
           ["fill", "css=input >> nth=3", "84d6cba8-39e3-4063-858e-2192b30dd54c"],
           ["click", "button:has-text('Inspect turn')"], ["wait", 3000]],
    },
    {
      say: "Two knowledge-search calls, both stamped blocked — the same interception you watched live in the guardrails episode, now as evidence. Every turn that runs today produces this same receipt.",
      do: [["goto", "/agents/turns"], ["wait", 1500],
           ["fill", "css=input >> nth=2", "434bc9b9-b90e-40fa-97de-537ee4e23567"],
           ["fill", "css=input >> nth=3", "84d6cba8-39e3-4063-858e-2192b30dd54c"],
           ["click", "button:has-text('Inspect turn')"],
           ["waitFor", "text=/blocked/i", 20000],
           ["tryHighlight", "text=/search_knowledge_base/i", 2500], ["scroll", 400]],
    },
    {
      say: "Compare answers the next question: same prompt, two configurations — did the change help or hurt? Pick the two runs and read them side by side before you ship a prompt edit to the whole desk.",
      do: [["goto", "/compare"], ["wait", 3000]],
    },
    {
      say: "Inspection for what happened, comparison for whether your change is better — quality control with receipts. Next: the audit trail, where everything a human did is recorded too.",
      do: [["goto", "/agents/turns"], ["wait", 2500]],
    },
  ],
};
