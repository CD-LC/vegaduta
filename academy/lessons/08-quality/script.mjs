export default {
  slug: "ep08-quality",
  title: "VegaDūta Academy — Ep 8 · Agent quality",
  subtitle: "Grading the paper before it prints",
  next: "Ep 9 — Operate: cost, health, audit",
  voice: "en-US-ChristopherNeural",
  scenes: [
    {
      say: "Episode eight: grading the paper. Agent Quality scores an agent's real conversations turn by turn — helpfulness, accuracy, tone, length, relevance and safety — so drift shows up in a number before it shows up in print.",
      do: [["goto", "/agents/quality"], ["tryClick", "text=Skip for now"], ["wait", 2500]],
    },
    {
      say: "Pick the agent — here, our Editor-in-Chief — and Live quality tracks its scores as conversations happen. Nothing has been graded on this install yet, so today we look at the machinery, not the verdict.",
      do: [["goto", "/agents/quality"], ["wait", 2000],
           ["highlight", "text=Live quality", 1600]],
    },
    {
      say: "Datasets & gate is the golden set: the conversations you decide an agent must handle well, and the score floor that fails a change before it ships. This is the difference between the paper feeling right and the paper being right.",
      do: [["goto", "/agents/quality"], ["click", "text=Datasets & gate"], ["wait", 2200]],
    },
    {
      say: "Simulations run a whole scripted scenario against the agent before it ever talks to a reader — a hostile source, an impossible deadline, a flood of tips — and grade every turn it took. Reliability and Solutions cover the failure side: what broke, and the fix history.",
      do: [["goto", "/agents/quality"], ["click", "text=Simulations"], ["wait", 2200]],
    },
    {
      say: "And when one turn goes wrong, the Turn inspector replays it — intake, guardrails, model calls, tools, approvals, the final commit — every event hash-chained so the record itself is tamper-evident.",
      do: [["goto", "/agents/turns"], ["wait", 2500]],
    },
    {
      say: "Quality is how a newsroom earns trust in its own product: live scores on real turns, a golden gate for changes, simulations for the edge cases, and a replayable record when something slips. Next: operating the whole thing — cost, health, audit.",
      do: [["goto", "/home"], ["wait", 2500]],
    },
  ],
};
