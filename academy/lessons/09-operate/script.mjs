export default {
  slug: "ep09-operate",
  title: "VegaDūta Academy — Ep 9 · Operate",
  subtitle: "Cost, health and the audit trail",
  next: "Ep 10 — Ship it: schedules & long-running work",
  voice: "en-US-AndrewNeural",
  scenes: [
    {
      say: "Episode nine: running the press room. Insights answers the two questions every owner asks — what did the agents do, and what did it cost. These are this install's real numbers: eighteen hundred AI calls this week, a ninety-eight percent success rate.",
      do: [["goto", "/insights"], ["tryClick", "text=Skip for now"], ["wait", 2500]],
    },
    {
      say: "The range chips reframe the same counters — seven, thirty or ninety days — and every failure is listed, not just counted. Failed calls are each one clickable down to what broke.",
      do: [["goto", "/insights"], ["highlight", "text=30 days", 1500],
           ["tryHighlight", "text=/failed/i", 1600]],
    },
    {
      say: "Usage is the meter — every model call your agents, workflows and quick tasks made, counted by tokens. This install has no limit, so the count is a reference, not a ceiling — but it is how you would size a budget.",
      do: [["goto", "/usage"], ["wait", 2500]],
    },
    {
      say: "Troubleshoot is the forensics table — system status, failed calls, and the trail behind each. When a reader says the agent gave a wrong answer, this is where the investigation starts.",
      do: [["goto", "/troubleshoot"], ["wait", 2500]],
    },
    {
      say: "Operate answers the unglamorous questions that keep a paper alive: is it working, what is it costing, and what exactly happened. Last episode: shipping — schedules and long-running work.",
      do: [["goto", "/home"], ["wait", 2500]],
    },
  ],
};
