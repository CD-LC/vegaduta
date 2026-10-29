export default {
  slug: "b02-agent-templates-learning",
  title: "VegaDūta Academy — Templates & learning",
  subtitle: "Stand on a proven pattern, then let it improve",
  next: "b03 — Playground",
  voice: "en-US-AndrewNeural",
  scenes: [
    {
      say: "Agent Templates is the hiring catalog — proven agent shapes you adopt instead of writing a persona from scratch. For a newsroom: researcher, fact-checker, social desk — each card is a starting configuration, not a locked product.",
      do: [["goto", "/agents/templates"], ["wait", 2500]],
    },
    {
      say: "Every template tells you its prerequisites before you install it — which connectors it expects, which knowledge it assumes. Pick one and it lands in your agents as a draft you tune to the Ledger's voice.",
      do: [["goto", "/agents/templates"], ["wait", 1500],
           ["scroll", 400], ["tryHighlight", "text=/template|install|use/i", 2000]],
    },
    {
      say: "Community templates widen the net — patterns other workspaces published. Same rule as hiring a stringer: read the resume, adapt the angle to your beat.",
      do: [["goto", "/templates"], ["wait", 3000]],
    },
    {
      say: "Agent Learning is the other half — where the platform surfaces what your agents picked up from corrections and runs: proposed improvements, patterns worth keeping, each waiting for a human to say keep it or drop it.",
      do: [["goto", "/agents/learning"], ["wait", 3000]],
    },
    {
      say: "Templates get you a competent hire in minutes; learning makes them better with every edition — but nothing self-applies. A person always reviews first. Next: the playground, where you test agents before they go live.",
      do: [["goto", "/agents/templates"], ["wait", 2500]],
    },
  ],
};
