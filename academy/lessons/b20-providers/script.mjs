export default {
  slug: "b20-providers",
  title: "VegaDūta Academy — Providers & decision routing",
  subtitle: "Which model thinks, and who decides",
  next: "b21 — Troubleshoot",
  voice: "en-US-AndrewNeural",
  scenes: [
    {
      say: "Providers is the model roster — every engine this workspace can call: the local Ollama models that run free and private, the hosted ones that cost tokens and travel.",
      do: [["goto", "/providers"], ["wait", 3000]],
    },
    {
      say: "On The Ledger's desk the split is deliberate — triage and drafts on the local model, editorials on the strong hosted one. The roster is where you add a provider or check what's healthy right now.",
      do: [["goto", "/providers"], ["wait", 1500],
           ["tryHighlight", "text=/ollama|local|anthropic|model/i", 2200], ["scroll", 350]],
    },
    {
      say: "Decision providers is the subtler dial — which model gets used for judgment calls inside the platform itself: routing, grading, assisting. Cheap where it's invisible, strong where it shows.",
      do: [["goto", "/capabilities/decision"], ["wait", 3000]],
    },
    {
      say: "One roster for the muscle, one dial for the judgment — you tune cost and quality per kind of thinking, not one-size-fits-all. Next: troubleshoot, when the answer still goes wrong.",
      do: [["goto", "/providers"], ["wait", 2500]],
    },
  ],
};
