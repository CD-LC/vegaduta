export default {
  slug: "b21-troubleshoot",
  title: "VegaDūta Academy — Troubleshoot",
  subtitle: "When the edition goes wrong, start here",
  next: "b22 — Workflow templates",
  voice: "en-US-AndrewNeural",
  scenes: [
    {
      say: "Troubleshoot is the forensics table — system status, the failed calls, and the trail behind each. When a reader says the agent answered wrong, the investigation opens here, not in a support queue.",
      do: [["goto", "/troubleshoot"], ["wait", 3000]],
    },
    {
      say: "Each failure is listed, not just counted — click through and you get the call, the error, and the turn it belonged to. From 'something broke overnight' to the exact step in under a minute.",
      do: [["goto", "/troubleshoot"], ["wait", 1500],
           ["tryHighlight", "text=/fail|error|status|health/i", 2200], ["scroll", 400]],
    },
    {
      say: "Notice the learning links — every section points at the Academy topic that explains it. The page assumes you'd rather understand the fix than memorize it.",
      do: [["goto", "/troubleshoot"], ["wait", 1500],
           ["tryHighlight", "text=/academy|learn|guide/i", 2000]],
    },
    {
      say: "Health at the top, failures in the middle, the path to the turn at the bottom — forensics without the ticket queue. Next: workflow templates, standing on pipelines that already work.",
      do: [["goto", "/troubleshoot"], ["wait", 2500]],
    },
  ],
};
