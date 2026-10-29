export default {
  slug: "b06-quick-tasks-jobs",
  title: "VegaDūta Academy — Quick tasks & jobs",
  subtitle: "Fire-and-forget, with a paper trail",
  next: "b07 — Data, files & clips",
  voice: "en-US-AndrewNeural",
  scenes: [
    {
      say: "A quick task is the fastest way to spend an agent: one box, one job, no ceremony. 'Draft three headlines for the markets brief in house style' — and it runs on the autonomous agent.",
      do: [["goto", "/quick-task"], ["wait", 2500],
           ["fill", "textarea[placeholder*='Describe the task']", "Draft three headline options for tomorrow's markets brief in the Ledger's house style."],
           ["wait", 1500]],
    },
    {
      say: "The catch you never see in other tools: a quick task still runs under your guardrails, budget and audit. Fast doesn't mean unsupervised — it means unblocked.",
      do: [["goto", "/quick-task"], ["wait", 2500]],
    },
    {
      say: "Jobs is the queue behind it — background work the desk kicked off: status, what it produced, how long it took. A task you fired at 11pm shows up here with its output, not a mystery.",
      do: [["goto", "/jobs"], ["wait", 3000]],
    },
    {
      say: "Quick task for 'do this one thing now', scheduled tasks for 'do it every dawn', jobs for 'show me everything in flight'. Next: where the files and data live.",
      do: [["goto", "/jobs"], ["wait", 2500]],
    },
  ],
};
