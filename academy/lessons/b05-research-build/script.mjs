export default {
  slug: "b05-research-build",
  title: "VegaDūta Academy — Research & build modes",
  subtitle: "The long desk and the workshop",
  next: "b06 — Quick tasks & jobs",
  voice: "en-US-AndrewNeural",
  scenes: [
    {
      say: "Research mode is the long desk — give it a question that deserves an afternoon, and it runs a multi-source investigation with cited findings instead of a one-shot guess.",
      do: [["goto", "/research"], ["wait", 2500]],
    },
    {
      say: "For a newsroom this is the investigations unit: 'map every public statement about the merger this month, flag contradictions.' The output is a sourced brief, not a vibe.",
      do: [["goto", "/research"], ["wait", 1500],
           ["tryHighlight", "text=/source|cited|research|depth/i", 2000], ["scroll", 350]],
    },
    {
      say: "Build mode is the workshop — agents assembling something bigger than an answer: a tool, a small app, a working artifact you can actually run afterward.",
      do: [["goto", "/build"], ["wait", 2500]],
    },
    {
      say: "Research for the question worth an afternoon, Build for the artifact worth shipping — both still under the same guardrails, approvals and audit as a five-second chat. Next: the fast lane, quick tasks and jobs.",
      do: [["goto", "/research"], ["wait", 2500]],
    },
  ],
};
