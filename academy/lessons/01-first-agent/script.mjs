export default {
  slug: "ep01-first-agent",
  title: "VegaDūta Academy — Ep 1 · Your first agent",
  subtitle: "Hiring the Editor-in-Chief",
  next: "Ep 2 — Knowledge: feeding the newsroom",
  voice: "en-US-AndrewNeural",
  scenes: [
    {
      say: "Episode one: we hire our first journalist. An agent is instructions, a model, a budget and a set of tools — the Agents screen is where all four come together.",
      do: [["goto", "/agents"], ["wait", 2200]],
    },
    {
      say: "Click New agent and the workbench opens. Name it first — Editor-in-Chief — because this name shows up on every turn, approval and audit line it ever produces.",
      do: [["goto", "/agents?new=1"], ["wait", 1500],
           ["fill", "input[placeholder*='Name this agent']", "Editor-in-Chief"], ["wait", 1200]],
    },
    {
      say: "The workbench has two ways in: Describe it drafts the whole agent from one sentence, or you write the persona and setup yourself. Every field the builder generates stays plain, editable text.",
      do: [["goto", "/agents?new=1"], ["wait", 1500],
           ["click", "text=Describe it"], ["wait", 1800]],
    },
    {
      say: "The Setup and persona tabs hold the practical knobs — the model it runs on, spend caps, and the system prompt with the editorial rules it never breaks. Triage reporters will run the cheap local model; the Editor-in-Chief gets the strong one.",
      do: [["goto", "/agents?new=1"], ["wait", 1500],
           ["click", "text=Setup"], ["wait", 1600],
           ["click", "text=/PERSONA/"], ["wait", 1600]],
    },
    {
      say: "One level up, Runtime settings govern the whole workspace — default models, timeouts and limits that every agent inherits unless its own card overrides them. This is where you set the newsroom's floor and ceiling.",
      do: [["goto", "/admin/runtime-settings"], ["wait", 2500]],
    },
    {
      say: "Now watch it actually answer. In the Editor-in-Chief's test console we ask the house embargo time — and it replies from memory: six AM. Under the answer you can see the run telemetry: the steps it took, the model that ran, and the tokens it spent.",
      do: [["goto", "/agents?agentId=67eb3d14-ded1-4055-af0d-ea754945ca73"], ["wait", 4000],
           ["waitFor", "textarea[placeholder*='Send a test message']", 20000],
           ["fill", "textarea[placeholder*='Send a test message']", "What is the house embargo time? One short sentence."],
           ["keys", "Enter"], ["waitFor", "text=Run finished", 60000], ["wait", 2000],
           ["tryHighlight", "text=/6:00|6 AM|embargo/i", 2000]],
    },
    {
      say: "Back in the workbench, Save draft keeps the agent private while we iterate — nothing can call it until it's published. Next episode we give it a library to read.",
      do: [["goto", "/agents?new=1"], ["wait", 1500], ["click", "text=Save draft"], ["wait", 2000]],
    },
  ],
};
