export default {
  slug: "b03-playground",
  title: "VegaDūta Academy — Playground",
  subtitle: "Try it before the readers do",
  next: "b04 — Coding",
  voice: "en-US-AndrewNeural",
  scenes: [
    {
      say: "The Playground is the rehearsal room — run an agent against a real prompt with its real tools, watch every step, and change nothing in production while you do.",
      do: [["goto", "/playground"], ["wait", 2500]],
    },
    {
      say: "Ask it a newsroom question. The answer isn't the point — the steps are: which tool it reached for, what came back, how many tokens the turn burned. You're watching the machine think, not trusting a black box.",
      do: [["goto", "/playground"], ["wait", 2500],
           ["fill", "css=textarea", "What beats does the Senior Reporter cover, and what's our embargo rule? One sentence."],
           ["keys", "Enter"], ["wait", 10000],
           ["tryHighlight", "text=/step|tool|run/i", 2000]],
    },
    {
      say: "A good run here becomes a recipe — save it and the steps replay on demand, which is how a flaky answer becomes a reproducible test instead of a shrug.",
      do: [["goto", "/playground"], ["wait", 2500],
           ["tryHighlight", "text=/Save|recipe|eval/i", 2000]],
    },
    {
      say: "Playground for rehearsal, turn inspector for the recording, evals for the grade — that's the quality loop in one screen. Next: coding mode, where the agent touches a real repository.",
      do: [["goto", "/playground"], ["wait", 2500]],
    },
  ],
};
