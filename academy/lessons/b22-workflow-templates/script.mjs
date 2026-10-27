export default {
  slug: "b22-workflow-templates",
  title: "VegaDūta Academy — Workflow templates",
  subtitle: "Start from a pipeline that already works",
  next: "b23 — Help & learning",
  voice: "en-US-AndrewNeural",
  scenes: [
    {
      say: "Workflow templates are proven pipelines you adopt instead of building from a blank canvas — the same way a news desk keeps boilerplate for recurring beats.",
      do: [["goto", "/workflows/templates"], ["wait", 2500]],
    },
    {
      say: "Each card is a complete recipe: the trigger, the agents, the approval steps, all wired. For The Ledger, the pattern that matters is gather, draft, verify, approve, publish — a template gets you ninety percent there.",
      do: [["goto", "/workflows/templates"], ["wait", 1500],
           ["tryHighlight", "css=main [class*='card'], main article, main li", 2000], ["scroll", 400]],
    },
    {
      say: "Adopting one isn't a commitment — it materializes into your workspace as a normal workflow you rename, re-point at your own agents, and tune. The wiring diagram we toured in episode four is what every template becomes.",
      do: [["goto", "/workflows"], ["wait", 2500],
           ["tryHighlight", "text=/Vegaduta Marketing|Lead Capture/i", 2200]],
    },
    {
      say: "Templates for the shape, your agents for the voice, guardrails for the safety — that's how a newsroom ships a pipeline in an afternoon. Last stop: every place the platform teaches you itself.",
      do: [["goto", "/workflows/templates"], ["wait", 2500]],
    },
  ],
};
