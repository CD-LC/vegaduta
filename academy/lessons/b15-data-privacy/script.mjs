export default {
  slug: "b15-data-privacy",
  title: "VegaDūta Academy — Data & privacy",
  subtitle: "What the workspace keeps, and for how long",
  next: "b16 — Turns & compare",
  voice: "en-US-AndrewNeural",
  scenes: [
    {
      say: "Data & privacy is the retention desk — what the workspace stores, how long it keeps it, and the switches for export and erasure. For a newspaper holding sources, this is the page that matters most.",
      do: [["goto", "/admin/data-privacy"], ["wait", 3000]],
    },
    {
      say: "Retention isn't a promise here, it's a setting — turn journal kept this long, chat history that long. Set it to the desk's policy once and it applies everywhere.",
      do: [["goto", "/admin/data-privacy"], ["wait", 1500],
           ["tryHighlight", "text=/retention|days|erase|export/i", 2200], ["scroll", 350]],
    },
    {
      say: "Export and erase are first-class, not support tickets — pull everything the workspace holds, or wipe it, from this page. The desk proves it owns its data by being able to leave with it.",
      do: [["goto", "/admin/data-privacy"], ["wait", 2000],
           ["tryHighlight", "text=/export|erase|delete/i", 2000]],
    },
    {
      say: "Guardrails bound what flows through the model; this page bounds what stays afterward. Next: the turn inspector — replaying exactly what an agent did.",
      do: [["goto", "/admin/data-privacy"], ["wait", 2500]],
    },
  ],
};
