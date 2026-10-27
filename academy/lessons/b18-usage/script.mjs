export default {
  slug: "b18-usage",
  title: "VegaDūta Academy — Usage & data usage",
  subtitle: "The meter and the ledger behind it",
  next: "b19 — Admin operations",
  voice: "en-US-AndrewNeural",
  scenes: [
    {
      say: "Usage is the meter — every model call your agents, workflows and quick tasks made, counted in tokens. These are real numbers: the turns this newsroom has already run today.",
      do: [["goto", "/usage"], ["wait", 3000]],
    },
    {
      say: "Read it like a press run: which agents burn the most, which hours peak. When the morning edition triples your call volume, this is where you see it — and where a budget ceiling would stop it.",
      do: [["goto", "/usage"], ["wait", 1500],
           ["tryHighlight", "text=/token|call|model/i", 2000], ["scroll", 350]],
    },
    {
      say: "Data usage answers the quieter question — not how much compute, but where your data went: what's stored, what's synced, what left the machine. Transparency about the substance, not just the spend.",
      do: [["goto", "/data-usage"], ["wait", 3000]],
    },
    {
      say: "Two ledgers every editor should read weekly: usage for the cost, data usage for the footprint. Next: the admin operations floor — access, issues, maintenance.",
      do: [["goto", "/usage"], ["wait", 2500]],
    },
  ],
};
